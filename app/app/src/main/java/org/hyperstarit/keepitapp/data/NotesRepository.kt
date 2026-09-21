package org.hyperstarit.keepitapp.data

import android.content.Context
import android.net.Uri
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.hyperstarit.keepitapp.data.offline.CacheSnapshot
import org.hyperstarit.keepitapp.data.offline.LocalStore
import org.hyperstarit.keepitapp.data.offline.MediaCache
import org.hyperstarit.keepitapp.data.offline.MediaStaging
import org.hyperstarit.keepitapp.data.offline.Outbox
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.SyncEngine
import org.hyperstarit.keepitapp.data.offline.activeListCounts
import org.hyperstarit.keepitapp.data.offline.applyListOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.applyPending
import org.hyperstarit.keepitapp.data.offline.applyPendingLists
import org.hyperstarit.keepitapp.data.offline.pendingMedia
import org.hyperstarit.keepitapp.data.offline.visibleNotes
import org.hyperstarit.keepitapp.data.offline.withUploadedMedia
import org.hyperstarit.keepitapp.ui.markdown.stripMarkdown
import org.hyperstarit.keepitapp.widget.KeepItWidget
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Which slice of notes the grid shows — mirrors the web's `NotesView`. */
enum class NotesView { ACTIVE, ARCHIVED, TRASHED, REMINDERS }

/** The grid's current filter: a view plus an optional set of list ids (union). */
data class NotesFilter(
    val view: NotesView = NotesView.ACTIVE,
    val listIds: Set<String> = emptySet(),
)

/**
 * A trimmed note snapshot the widget can render without auth or network. For checklist notes
 * [checklist] holds each item as its own display line (so the widget stacks them); for text notes
 * [preview] holds a one-line body excerpt and [checklist] is empty.
 */
@Serializable
data class WidgetNote(
    val id: String,
    val title: String,
    val preview: String,
    val color: String?,
    val checklist: List<String> = emptyList(),
)

/**
 * App-scoped, **offline-first** state for notes and lists. The merged cache (all three views) is
 * the source of truth the UI reads: it's loaded from disk at startup, refreshed by the sync
 * engine's fetches, and mutated *locally and instantly* — every mutation applies to the cache and
 * lands in the persisted outbox, then the sync engine replays it whenever the server is reachable.
 * Filtering (view / lists / ordering) is local, so browsing works fully offline. The home-screen
 * widget's snapshot is rewritten on every cache change.
 */
class NotesRepository(
    private val client: ApiClient,
    private val appContext: Context,
    private val store: LocalStore,
    private val outbox: Outbox,
    private val scope: CoroutineScope,
    private val staging: MediaStaging,
) : SyncEngine.CacheUpdater {

    private val widgetPrefs = appContext.getSharedPreferences("keepit_widget", Context.MODE_PRIVATE)

    val filter = MutableStateFlow(NotesFilter())

    /** Every note the user can see, across active/archive/trash — pending local edits applied. */
    private val cache = MutableStateFlow<List<NoteDto>>(emptyList())

    /** The unfiltered cache, read-only — the reminder scheduler derives its alarms from this. */
    val allNotes: StateFlow<List<NoteDto>> get() = cache
    private val cachedLists = MutableStateFlow<List<ListDto>>(emptyList())

    /** Which user the on-disk cache belongs to; a different sign-in wipes it. */
    private var cacheUserId: String = ""

    /** temp id → server id for notes created offline, so an open editor survives the remap. */
    private val idAliases = ConcurrentHashMap<String, String>()

    /** temp id → server id for lists created offline — a dialog or editor may still hold the old one. */
    private val listAliases = ConcurrentHashMap<String, String>()

    /** Wired by the app container after construction (repo and engine reference each other). */
    var syncEngine: SyncEngine? = null

    /**
     * Downloaded note images. Keyed by the media id, which is immutable, so nothing here ever needs
     * invalidating — only capping.
     */
    val mediaCache = MediaCache(java.io.File(appContext.filesDir, "offline/media")) { noteId, mediaId, size ->
        client.api.downloadNoteMedia(noteId, mediaId, size)
    }

    val notes: StateFlow<List<NoteDto>> =
        combine(cache, filter) { all, f -> visibleNotes(all, f) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Lists with their counts recomputed locally, so the drawer stays correct offline. */
    val lists: StateFlow<List<ListDto>> =
        combine(cachedLists, cache) { ls, all ->
            val counts = activeListCounts(all)
            ls.map { it.copy(noteCount = counts[it.id] ?: 0) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val loading = MutableStateFlow(false)

    private val diskLoad = Mutex()
    private var loadedFromDisk = false

    /**
     * Restores cache + outbox from disk, before the session resolves.
     *
     * Idempotent, and deliberately so: `AppRoot` calls it when the UI composes, but a widget tap
     * can start this process without any UI at all, and the widget's refresh has to be able to load
     * the cache itself rather than sync against an empty outbox. The mutex makes the two callers
     * safe to race — the second one waits and then finds the work already done.
     */
    suspend fun loadFromDisk() {
        diskLoad.withLock {
            if (loadedFromDisk) return@withLock
            outbox.load()
            store.loadCache()?.let { snapshot ->
                cacheUserId = snapshot.userId
                cache.value = snapshot.notes
                cachedLists.value = snapshot.lists
            }
            loadedFromDisk = true
        }
    }

    /**
     * Ties the local store to the signed-in user: a different account wipes the previous user's
     * cache and queued changes (they must never replay as someone else).
     */
    suspend fun onSignedIn(userId: String) {
        if (cacheUserId.isNotEmpty() && cacheUserId != userId) {
            cache.value = emptyList()
            cachedLists.value = emptyList()
            idAliases.clear()
            listAliases.clear()
            outbox.clear()
            mediaCache.clear()
        }
        cacheUserId = userId
        persistCache()
    }

    /** Drops all local data (sign-out). The widget keeps its last snapshot, as before. */
    suspend fun clearLocal() {
        cache.value = emptyList()
        cachedLists.value = emptyList()
        cacheUserId = ""
        idAliases.clear()
        listAliases.clear()
        outbox.clear()
        store.clear()
        // Downloaded images are as personal as the notes they hang off — the next account to sign
        // in on this device must not find them sitting in the cache.
        mediaCache.clear()
    }

    /** Full sync: replay queued changes, then refetch everything. No-op offline (cache stands). */
    suspend fun refreshAll() {
        loading.value = true
        syncEngine?.sync()
        loading.value = false
    }

    fun setFilter(newFilter: NotesFilter) {
        filter.value = newFilter
    }

    fun noteById(id: String): NoteDto? {
        val real = resolve(id)
        return cache.value.find { it.id == real }
    }

    /** Cache first; a direct GET only as fallback (widget deep links after a cache wipe). */
    suspend fun fetchNote(id: String): NoteDto? =
        noteById(id) ?: runCatching { client.api.note(resolve(id)) }.getOrNull()

    // ---- mutations: instant local apply + persisted outbox + sync kick ----

    /** Creates the note locally under a temp id; the sync replaces it with the server's. */
    suspend fun create(dto: CreateNoteDto): NoteDto {
        val op = PendingOp.Create(
            tempId = PendingOp.newTempId(),
            dto = dto.copy(listIds = dto.listIds?.map(::resolveList)),
            enqueuedAtUtc = nowUtc(),
        )
        mutate(op)
        return cache.value.first { it.id == op.tempId }
    }

    suspend fun update(id: String, dto: UpdateNoteDto) =
        mutate(PendingOp.Update(resolve(id), dto, enqueuedAtUtc = nowUtc()))

    suspend fun setState(id: String, state: NoteStateDto) =
        mutate(PendingOp.SetState(resolve(id), state, enqueuedAtUtc = nowUtc()))

    suspend fun setLists(id: String, listIds: List<String>) =
        mutate(PendingOp.SetLists(resolve(id), listIds.map(::resolveList), enqueuedAtUtc = nowUtc()))

    suspend fun setReminder(id: String, dto: SetNoteReminderDto) =
        mutate(PendingOp.SetReminder(resolve(id), dto, enqueuedAtUtc = nowUtc()))

    suspend fun clearReminder(id: String) =
        mutate(PendingOp.ClearReminder(resolve(id), enqueuedAtUtc = nowUtc()))

    suspend fun delete(id: String) =
        mutate(PendingOp.Delete(resolve(id), enqueuedAtUtc = nowUtc()))

    // ---- note media ----

    /**
     * Attaches an image to a note. The bytes are staged locally *first*, so the attachment survives
     * the picker's revocable grant, a reboot, and an arbitrarily long offline stretch.
     *
     * @return false when the picked content could not be read at all.
     */
    suspend fun attachMedia(context: Context, noteId: String, uri: Uri): Boolean {
        val tempMediaId = UUID.randomUUID().toString()
        val staged = staging.stage(context, uri, tempMediaId) ?: return false
        mutate(
            PendingOp.AttachMedia(
                noteId = resolve(noteId),
                stagedPath = staged.absolutePath,
                tempMediaId = tempMediaId,
                enqueuedAtUtc = nowUtc(),
            ),
        )
        return true
    }

    /** Removes an image from a note. */
    suspend fun removeMedia(noteId: String, mediaId: String) =
        mutate(PendingOp.DeleteMedia(resolve(noteId), mediaId, enqueuedAtUtc = nowUtc()))

    /**
     * Saves an image, at full size, to the device's gallery. Usually instant: the viewer has already
     * pulled the full rendition into [mediaCache] to show it.
     */
    suspend fun saveMediaToGallery(noteId: String, mediaId: String): SaveImageResult {
        val file = mediaCache.file(resolve(noteId), mediaId, "full") ?: return SaveImageResult.UNAVAILABLE
        return if (GallerySaver.save(appContext, file, "keepIT_${mediaId.take(8)}")) {
            SaveImageResult.SAVED
        } else {
            SaveImageResult.FAILED
        }
    }

    /**
     * The attachments still queued for a note, so the editor can show a picked image immediately.
     *
     * Projected from the outbox rather than stored on [NoteDto], which must stay an exact mirror of
     * the server DTO — a client-only "pending" field there is precisely the drift the hand-sync
     * rule forbids.
     */
    fun pendingMediaFor(noteId: String): Flow<List<PendingOp.AttachMedia>> =
        outbox.pendingOps.map { ops -> pendingMedia(ops, resolve(noteId)) }

    // ---- lists: offline-first like notes — applied locally at once, queued for the server ----

    /** Creates a list under a temp id; notes can be filed into it before the server has seen it. */
    suspend fun createList(name: String) = mutate(
        PendingOp.CreateList(
            tempId = PendingOp.newTempId(),
            dto = CreateListDto(name.trim()),
            enqueuedAtUtc = nowUtc(),
        ),
    )

    /** Renames a list. */
    suspend fun renameList(id: String, name: String) =
        mutate(PendingOp.UpdateList(resolveList(id), UpdateListDto(name = name.trim()), enqueuedAtUtc = nowUtc()))

    /** Deletes a list (notes survive, memberships go), clearing it from the filter if active. */
    suspend fun deleteList(id: String) {
        val real = resolveList(id)
        mutate(PendingOp.DeleteList(real, enqueuedAtUtc = nowUtc()))
        filter.update { f -> if (real in f.listIds) f.copy(listIds = f.listIds - real) else f }
    }

    private suspend fun mutate(op: PendingOp) {
        // Intent lands on disk before the cache: after a crash the worst case is a re-sent op
        // (replay is idempotent), never a change that looks saved but was lost.
        val dropped = outbox.enqueue(op)
        // Coalescing can discard a queued attachment (deleting the note annihilates its ops); the
        // staged bytes are ours, so they go with it rather than sitting in staging forever.
        dropped.filterIsInstance<PendingOp.AttachMedia>().forEach { staging.delete(it.stagedPath) }
        cache.update { applyOp(it, op) }
        cachedLists.update { applyListOp(it, op) }
        persistCache()
        syncEngine?.kick()
    }

    /**
     * The id a note goes by now: the server's once a note created offline has synced, else [id].
     * Anything that holds on to a note across a sync — the open editor — must look it up through
     * this, or it loses the note the moment its temp id is swapped out.
     */
    fun resolve(id: String): String = idAliases[id] ?: id

    /** [resolve] for lists: a list created offline goes by the server's id once it has synced. */
    private fun resolveList(id: String): String = listAliases[id] ?: id

    // ---- SyncEngine.CacheUpdater ----

    override suspend fun onFetched(notes: List<NoteDto>, lists: List<ListDto>, stillPending: List<PendingOp>) {
        // Overlay anything still queued so local edits don't flicker away mid-replay.
        cache.value = applyPending(notes, stillPending)
        cachedLists.value = applyPendingLists(lists, stillPending)
        persistCache()
        prefetch()
    }

    private var prefetchJob: Job? = null

    /**
     * Starts a thumbnail warm-up alongside the sync instead of inside it.
     *
     * Awaiting it here used to make every `sync()` last as long as [MAX_PREFETCH] downloads, which
     * the widget's refresh feels most: its [SyncEngine.sync] runs inside a broadcast's async window,
     * and that window is measured in seconds. Notes are on screen the moment [persistCache] has run,
     * so the images can keep arriving afterwards. Cancel-and-replace because two overlapping passes
     * would also mean two evictions.
     */
    private fun prefetch() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch { prefetchThumbnails(cache.value) }
    }

    /**
     * Warms the thumbnail cache for the notes in the grid, then trims it.
     *
     * Without this the grid works offline for everything *except* images, which is exactly the
     * moment a photo note is least useful. Best-effort and bounded: a failure here must never
     * surface as a sync error, and only thumbnails are warmed — full-size images are fetched on
     * demand when a note is opened.
     */
    private suspend fun prefetchThumbnails(notes: List<NoteDto>) {
        notes.asSequence()
            .filter { !it.isTrashed }
            .flatMap { note -> note.media.asSequence().map { note.id to it.id } }
            .take(MAX_PREFETCH)
            .forEach { (noteId, mediaId) ->
                runCatching { mediaCache.file(noteId, mediaId, "thumb") }
            }

        mediaCache.evict(MAX_MEDIA_CACHE_BYTES)
    }

    override suspend fun onIdRemapped(tempId: String, realId: String) {
        idAliases[tempId] = realId
        cache.update { all -> all.map { if (it.id == tempId) it.copy(id = realId) else it } }
        persistCache()
    }

    override suspend fun onMediaUploaded(noteId: String, media: NoteMediaDto) {
        cache.update { withUploadedMedia(it, noteId, media) }
        persistCache()
    }

    override suspend fun onListIdRemapped(tempId: String, realId: String) {
        listAliases[tempId] = realId
        fun List<String>.remapped() = map { if (it == tempId) realId else it }
        cachedLists.update { all -> all.map { if (it.id == tempId) it.copy(id = realId) else it } }
        cache.update { all -> all.map { n -> if (tempId in n.listIds) n.copy(listIds = n.listIds.remapped()) else n } }
        filter.update { f -> if (tempId in f.listIds) f.copy(listIds = f.listIds - tempId + realId) else f }
        persistCache()
    }

    override suspend fun onListDropped(tempId: String) {
        // Exactly what deleting it would have done locally.
        val gone = PendingOp.DeleteList(tempId)
        cachedLists.update { applyListOp(it, gone) }
        cache.update { applyOp(it, gone) }
        filter.update { f -> if (tempId in f.listIds) f.copy(listIds = f.listIds - tempId) else f }
        persistCache()
    }

    // ---- persistence + widget ----

    private suspend fun persistCache() {
        store.saveCache(CacheSnapshot(cacheUserId, cache.value, cachedLists.value))
        updateWidget()
    }

    /**
     * Re-render requests, coalesced. Glance holds a session lock for the better part of a minute
     * after rendering and silently drops update requests that arrive while it's held, so a burst of
     * `updateAll` calls can leave a widget stuck on its loading layout. Bursts are the normal case
     * here, not the exception: replaying the outbox and `onFetched` after a full refetch both walk
     * the cache and persist repeatedly. [collectLatest] cancels the previous wait, so N requests in
     * quick succession collapse into one render [WIDGET_RENDER_DEBOUNCE_MS] after the last.
     *
     * The snapshot itself is still written synchronously on every change — it's only SharedPrefs,
     * and the widget reads it on its next render, so delaying the render never shows stale data.
     */
    private val widgetRenderRequests = MutableStateFlow(0)

    init {
        scope.launch {
            // drop(1): the StateFlow's initial value is not a request, just its starting state.
            widgetRenderRequests.drop(1).collectLatest {
                delay(WIDGET_RENDER_DEBOUNCE_MS)
                // NonCancellable: the debounce guards the *wait*, not the render. Letting a newer
                // request cancel an in-flight updateAll would tear down a half-set-up Glance
                // session, which is the very thing that wedges a widget on its loading layout.
                withContext(NonCancellable) {
                    runCatching { KeepItWidget().updateAll(appContext) }
                }
            }
        }
    }

    /** Writes the top notes to the widget's local cache and asks Glance to re-render. */
    private fun updateWidget() {
        writeWidgetSnapshot()
        widgetRenderRequests.update { it + 1 }
    }

    private fun writeWidgetSnapshot() {
        val notes = visibleNotes(cache.value, NotesFilter())
        widgetPrefs.edit().putString(WIDGET_KEY, encodeWidgetSnapshot(widgetNotesFrom(notes))).apply()
    }

    /**
     * Writes the widget snapshot and renders it **now**, skipping the burst debounce.
     *
     * The debounced path above is built for the app: many cache writes, one render once they settle.
     * A widget tap is the opposite — a single deliberate request whose result has to be on screen
     * before the process is allowed to die, and after the refresh callback returns this process is a
     * cached one that Android may kill long before a delayed render would have run.
     */
    suspend fun renderWidgetNow() {
        // Only rewrite the snapshot when there is a cache behind it. A process that started purely
        // to serve this tap and found nothing on disk must re-render what the widget already had,
        // not blank it. An account that genuinely has no notes still empties it the normal way,
        // through the [onFetched] -> [persistCache] path.
        if (cache.value.isNotEmpty()) writeWidgetSnapshot()
        withContext(NonCancellable) {
            runCatching { KeepItWidget().updateAll(appContext) }
        }
    }

    companion object {
        private const val WIDGET_KEY = "notes_json"
        private const val WIDGET_NOTE_COUNT = 6
        private const val WIDGET_CHECKLIST_LINES = 4
        private const val WIDGET_RENDER_DEBOUNCE_MS = 500L
        private val WidgetJson = Json { ignoreUnknownKeys = true }

        private fun nowUtc(): String = Instant.now().toString()

        /**
         * Projects the note cache into the widget's snapshot. Pure and free of Android types on
         * purpose: this is the write half of the two-process contract [readWidgetNotes] reads, and
         * the only part of the widget's data path worth pinning in a JVM test.
         */
        internal fun widgetNotesFrom(notes: List<NoteDto>): List<WidgetNote> =
            notes.take(WIDGET_NOTE_COUNT).map { n ->
                val isChecklist = n.type == NoteTypes.CHECKLIST
                WidgetNote(
                    id = n.id,
                    title = n.title?.takeIf { it.isNotBlank() } ?: "",
                    // The Glance widget renders plain strings, so Markdown syntax is stripped here.
                    preview = if (isChecklist) "" else stripMarkdown(n.body ?: "").replace('\n', ' ').take(100),
                    color = n.color,
                    checklist = if (isChecklist) {
                        // Unchecked first, so the few lines that fit the widget show what's still to do.
                        n.checklistItems
                            .inDisplayOrder()
                            .take(WIDGET_CHECKLIST_LINES)
                            .map { (if (it.isChecked) "☑ " else "☐ ") + it.text }
                    } else {
                        emptyList()
                    },
                )
            }

        /** Serializes a snapshot into the SharedPrefs blob the widget process reads. */
        internal fun encodeWidgetSnapshot(notes: List<WidgetNote>): String = Json.encodeToString(notes)

        /**
         * Parses a snapshot blob. Total by design: the blob outlives app updates, so one written
         * by an older (or newer) build has to degrade to "no notes" rather than throw inside the
         * widget's composition — [WidgetJson] ignores unknown keys, and anything else falls
         * through to an empty list.
         */
        internal fun decodeWidgetSnapshot(raw: String?): List<WidgetNote> {
            if (raw == null) return emptyList()
            return runCatching {
                WidgetJson.decodeFromString<List<WidgetNote>>(raw)
            }.getOrDefault(emptyList())
        }

        /** Upper bound on thumbnails warmed after a sync — bounded work, not the whole library. */
        private const val MAX_PREFETCH = 200

        /** Cache ceiling for downloaded images; oldest are evicted past this. */
        private const val MAX_MEDIA_CACHE_BYTES = 256L * 1024 * 1024

        /** Read side for the Glance widget (sync, no network, works while signed out). */
        fun readWidgetNotes(context: Context): List<WidgetNote> = decodeWidgetSnapshot(
            context.getSharedPreferences("keepit_widget", Context.MODE_PRIVATE)
                .getString(WIDGET_KEY, null)
        )
    }
}

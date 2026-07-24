package org.hyperstarit.keepitapp.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.hyperstarit.keepitapp.data.offline.CacheSnapshot
import org.hyperstarit.keepitapp.data.offline.LocalStore
import org.hyperstarit.keepitapp.data.offline.Outbox
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.SyncEngine
import org.hyperstarit.keepitapp.data.offline.activeListCounts
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.applyPending
import org.hyperstarit.keepitapp.data.offline.visibleNotes
import org.hyperstarit.keepitapp.ui.markdown.stripMarkdown
import org.hyperstarit.keepitapp.widget.KeepItWidget
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Which slice of notes the grid shows — mirrors the web's `NotesView`. */
enum class NotesView { ACTIVE, ARCHIVED, TRASHED }

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
    scope: CoroutineScope,
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
    private var lastSyncUtc: String = ""

    /** temp id → server id for notes created offline, so an open editor survives the remap. */
    private val idAliases = ConcurrentHashMap<String, String>()

    /** Wired by the app container after construction (repo and engine reference each other). */
    var syncEngine: SyncEngine? = null

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

    /** Restores cache + outbox from disk. Call once at startup, before the session resolves. */
    suspend fun loadFromDisk() {
        outbox.load()
        val snapshot = store.loadCache() ?: return
        cacheUserId = snapshot.userId
        lastSyncUtc = snapshot.lastSyncUtc
        cache.value = snapshot.notes
        cachedLists.value = snapshot.lists
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
            outbox.clear()
        }
        cacheUserId = userId
        persistCache()
    }

    /** Drops all local data (sign-out). The widget keeps its last snapshot, as before. */
    suspend fun clearLocal() {
        cache.value = emptyList()
        cachedLists.value = emptyList()
        cacheUserId = ""
        lastSyncUtc = ""
        idAliases.clear()
        outbox.clear()
        store.clear()
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
        val op = PendingOp.Create(tempId = PendingOp.newTempId(), dto = dto, enqueuedAtUtc = nowUtc())
        mutate(op)
        return cache.value.first { it.id == op.tempId }
    }

    suspend fun update(id: String, dto: UpdateNoteDto) =
        mutate(PendingOp.Update(resolve(id), dto, enqueuedAtUtc = nowUtc()))

    suspend fun setState(id: String, state: NoteStateDto) =
        mutate(PendingOp.SetState(resolve(id), state, enqueuedAtUtc = nowUtc()))

    suspend fun setLists(id: String, listIds: List<String>) =
        mutate(PendingOp.SetLists(resolve(id), listIds, enqueuedAtUtc = nowUtc()))

    suspend fun setReminder(id: String, dto: SetNoteReminderDto) =
        mutate(PendingOp.SetReminder(resolve(id), dto, enqueuedAtUtc = nowUtc()))

    suspend fun clearReminder(id: String) =
        mutate(PendingOp.ClearReminder(resolve(id), enqueuedAtUtc = nowUtc()))

    suspend fun delete(id: String) =
        mutate(PendingOp.Delete(resolve(id), enqueuedAtUtc = nowUtc()))

    // ---- lists CRUD: online-only (like the web), applied to the cache on success ----

    /** Creates a list. Online-only — offline callers get a failed [Result] to surface. */
    suspend fun createList(name: String): Result<Unit> = runCatching {
        val created = client.api.createList(CreateListDto(name.trim()))
        cachedLists.update { (it + created).sortedBy { l -> l.name.lowercase() } }
        persistCache()
    }

    /** Renames a list. Online-only. */
    suspend fun renameList(id: String, name: String): Result<Unit> = runCatching {
        val updated = client.api.updateList(id, UpdateListDto(name = name.trim()))
        cachedLists.update { all -> all.map { if (it.id == id) updated else it }.sortedBy { l -> l.name.lowercase() } }
        persistCache()
    }

    /** Deletes a list (notes survive, memberships go). Online-only. */
    suspend fun deleteList(id: String): Result<Unit> = runCatching {
        client.api.deleteList(id)
        cachedLists.update { all -> all.filter { it.id != id } }
        // Notes filed in the list still carry its id locally; drop it and clear the filter if active.
        cache.update { all -> all.map { n -> if (id in n.listIds) n.copy(listIds = n.listIds - id) else n } }
        filter.update { f -> if (id in f.listIds) f.copy(listIds = f.listIds - id) else f }
        persistCache()
    }

    private suspend fun mutate(op: PendingOp) {
        // Intent lands on disk before the cache: after a crash the worst case is a re-sent op
        // (replay is idempotent), never a change that looks saved but was lost.
        outbox.enqueue(op)
        cache.update { applyOp(it, op) }
        persistCache()
        syncEngine?.kick()
    }

    private fun resolve(id: String): String = idAliases[id] ?: id

    // ---- SyncEngine.CacheUpdater ----

    override suspend fun onFetched(notes: List<NoteDto>, lists: List<ListDto>, stillPending: List<PendingOp>) {
        // Overlay anything still queued so local edits don't flicker away mid-replay.
        cache.value = applyPending(notes, stillPending)
        cachedLists.value = lists
        lastSyncUtc = nowUtc()
        persistCache()
    }

    override suspend fun onIdRemapped(tempId: String, realId: String) {
        idAliases[tempId] = realId
        cache.update { all -> all.map { if (it.id == tempId) it.copy(id = realId) else it } }
        persistCache()
    }

    // ---- persistence + widget ----

    private suspend fun persistCache() {
        store.saveCache(CacheSnapshot(cacheUserId, cache.value, cachedLists.value, lastSyncUtc))
        updateWidget(visibleNotes(cache.value, NotesFilter()))
    }

    /** Writes the top notes to the widget's local cache and asks Glance to re-render. */
    private suspend fun updateWidget(notes: List<NoteDto>) {
        val top = notes.take(WIDGET_NOTE_COUNT).map { n ->
            val isChecklist = n.type == NoteTypes.CHECKLIST
            WidgetNote(
                id = n.id,
                title = n.title?.takeIf { it.isNotBlank() } ?: "",
                // The Glance widget renders plain strings, so Markdown syntax is stripped here.
                preview = if (isChecklist) "" else stripMarkdown(n.body ?: "").replace('\n', ' ').take(100),
                color = n.color,
                checklist = if (isChecklist) {
                    n.checklistItems.sortedBy { it.order }.take(WIDGET_CHECKLIST_LINES).map {
                        (if (it.isChecked) "☑ " else "☐ ") + it.text
                    }
                } else {
                    emptyList()
                },
            )
        }
        widgetPrefs.edit().putString(WIDGET_KEY, Json.encodeToString(top)).apply()
        runCatching { KeepItWidget().updateAll(appContext) }
    }

    companion object {
        private const val WIDGET_KEY = "notes_json"
        private const val WIDGET_NOTE_COUNT = 6
        private const val WIDGET_CHECKLIST_LINES = 4
        private val WidgetJson = Json { ignoreUnknownKeys = true }

        private fun nowUtc(): String = Instant.now().toString()

        /** Read side for the Glance widget (sync, no network, works while signed out). */
        fun readWidgetNotes(context: Context): List<WidgetNote> {
            val raw = context.getSharedPreferences("keepit_widget", Context.MODE_PRIVATE)
                .getString(WIDGET_KEY, null) ?: return emptyList()
            return runCatching {
                WidgetJson.decodeFromString<List<WidgetNote>>(raw)
            }.getOrDefault(emptyList())
        }
    }
}

package org.hyperstarit.keepitapp.data.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.hyperstarit.keepitapp.data.ApiClient
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.SetNoteListsDto
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicBoolean

/** What the sync engine is doing right now, for the notes screen's status strip. */
enum class SyncStatus { IDLE, SYNCING, OFFLINE }

/**
 * Drains the [Outbox] against the REST API and then refetches everything — the counterpart of the
 * web's "mutate then refetch", batched up for reconnection. Triggered ([kick]) on sign-in, on
 * connectivity return, on every enqueue, on SignalR pushes, and by pull-to-refresh; runs are
 * single-flight and a kick during a run schedules exactly one follow-up pass.
 *
 * Failure policy per op: network/5xx stops the run (the queue keeps its head and a later trigger
 * retries); a 401 defers to the session (queue retained — re-login resumes replay); any other 4xx
 * is permanent for that op, which is dropped with a message on [syncErrors] so the user learns a
 * change didn't stick (e.g. the note was deleted on another device).
 *
 * In standalone mode ([isStandalone]) there is no server, so every run is a no-op and the outbox is
 * left exactly as it is — it is the device's record of everything, waiting for a server to be
 * connected. This holds for every caller, the widget's refresh and background worker included,
 * which is why the check lives here rather than at the call sites.
 */
class SyncEngine(
    private val client: ApiClient,
    private val outbox: Outbox,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val updater: CacheUpdater,
    private val staging: MediaStaging,
    private val isStandalone: () -> Boolean,
) {
    /** How synced data lands in the repository's cache — implemented by NotesRepository. */
    interface CacheUpdater {
        suspend fun onFetched(notes: List<NoteDto>, lists: List<ListDto>, stillPending: List<PendingOp>)
        suspend fun onIdRemapped(tempId: String, realId: String)
        suspend fun onMediaUploaded(noteId: String, media: NoteMediaDto)
        suspend fun onListIdRemapped(tempId: String, realId: String)

        /** A list the server refused to create is gone for good; drop it and every membership in it. */
        suspend fun onListDropped(tempId: String)
    }

    var onUnauthorized: (() -> Unit)? = null

    val status = MutableStateFlow(SyncStatus.IDLE)

    /** Human-readable messages for ops dropped as permanently failed. */
    val syncErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val syncMutex = Mutex()
    private val kickPending = AtomicBoolean(false)

    /** Fire-and-forget sync request; safe to call from anywhere, collapses concurrent calls. */
    fun kick() {
        if (isStandalone()) return
        if (kickPending.getAndSet(true)) return // an already-scheduled pass will cover this
        scope.launch { sync() }
    }

    /** Replays the outbox, then refetches notes + lists. Suspends until this pass completes. */
    suspend fun sync() {
        if (isStandalone()) return
        syncMutex.withLock {
            kickPending.set(false)
            status.value = SyncStatus.SYNCING
            val drained = replay()
            status.value = when {
                !drained -> SyncStatus.OFFLINE
                fetchAll() -> SyncStatus.IDLE
                else -> SyncStatus.OFFLINE
            }
        }
    }

    /** True when the queue fully drained (permanent 4xx drops count as drained). */
    private suspend fun replay(): Boolean {
        while (true) {
            val op = outbox.peek() ?: return true
            try {
                when (op) {
                    is PendingOp.Create -> {
                        val created = client.api.createNote(op.dto)
                        outbox.remapId(op.tempId, created.id)
                        updater.onIdRemapped(op.tempId, created.id)
                    }

                    is PendingOp.Update -> client.api.updateNote(op.noteId, op.dto)
                    is PendingOp.SetState -> client.api.setNoteState(op.noteId, op.state)
                    is PendingOp.SetLists -> client.api.setNoteLists(op.noteId, SetNoteListsDto(op.listIds))
                    is PendingOp.SetReminder -> client.api.setReminder(op.noteId, op.dto)
                    is PendingOp.ClearReminder -> client.api.clearReminder(op.noteId)
                    is PendingOp.Delete -> client.api.deleteNote(op.noteId)

                    is PendingOp.AttachMedia -> {
                        val file = java.io.File(op.stagedPath)
                        if (!file.exists()) {
                            // Nothing left to send — drop it rather than retry forever.
                            outbox.removeFirst(op.opId)
                            continue
                        }
                        val media = client.api.uploadNoteMedia(
                            op.noteId,
                            MultipartBody.Part.createFormData(
                                "file",
                                "image.jpg",
                                file.asRequestBody("image/*".toMediaType()),
                            ),
                        )
                        staging.delete(op.stagedPath)
                        // Into the cache before the op leaves the outbox, so the image hands over
                        // from its pending preview to the real thing instead of vanishing until the
                        // refetch below.
                        updater.onMediaUploaded(op.noteId, media)
                    }

                    is PendingOp.DeleteMedia -> client.api.deleteNoteMedia(op.noteId, op.mediaId)

                    is PendingOp.CreateList -> {
                        val created = client.api.createList(op.dto)
                        outbox.remapListId(op.tempId, created.id)
                        updater.onListIdRemapped(op.tempId, created.id)
                    }

                    is PendingOp.UpdateList -> client.api.updateList(op.listId, op.dto)
                    is PendingOp.DeleteList -> client.api.deleteList(op.listId)
                }
                outbox.removeFirst(op.opId)
                connectivity.markOnline()
            } catch (t: Throwable) {
                when {
                    t is HttpException && t.code() == 401 -> {
                        onUnauthorized?.invoke()
                        return false
                    }

                    t is HttpException && t.code() in 400..499 && t.code() != 429 -> {
                        // Clean-up before the op leaves the queue: a crash in between then leaves
                        // an op whose file is gone (dropped on the next run), never an orphan file.
                        var rescued = false
                        when (op) {
                            // The op is going away for good, so its staged bytes go with it —
                            // otherwise every rejected image leaks a file into staging forever.
                            // To the gallery first, though: for a photo taken offline or in
                            // standalone mode, the staged file is the only copy there is.
                            is PendingOp.AttachMedia -> {
                                rescued = staging.rescueToGallery(op.stagedPath)
                                staging.delete(op.stagedPath)
                            }
                            // Everything still naming the list would be refused in turn.
                            is PendingOp.CreateList -> {
                                outbox.forgetList(op.tempId)
                                updater.onListDropped(op.tempId)
                            }
                            else -> Unit
                        }
                        outbox.removeFirst(op.opId)
                        syncErrors.tryEmit(permanentFailureMessage(op, t.code(), rescued))
                    }

                    else -> {
                        connectivity.markOffline()
                        return false
                    }
                }
            }
        }
    }

    /**
     * Pulls the complete dataset — all three views plus lists, in parallel — and hands it to the
     * repository with a snapshot of anything still queued so local edits overlay the server truth.
     */
    private suspend fun fetchAll(): Boolean = try {
        coroutineScope {
            val active = async { client.api.notes() }
            val archived = async { client.api.notes(archived = true) }
            val trashed = async { client.api.notes(trashed = true) }
            val lists = async { client.api.lists() }
            updater.onFetched(
                notes = active.await() + archived.await() + trashed.await(),
                lists = lists.await(),
                stillPending = outbox.snapshot(),
            )
        }
        connectivity.markOnline()
        true
    } catch (t: Throwable) {
        if (t is HttpException && t.code() == 401) onUnauthorized?.invoke() else connectivity.markOffline()
        false
    }

    private fun permanentFailureMessage(op: PendingOp, code: Int, rescued: Boolean): String {
        val what = when (op) {
            is PendingOp.Create -> "creating a note"
            is PendingOp.Update -> "an edit"
            is PendingOp.SetState -> "a note change"
            is PendingOp.SetLists -> "a list change"
            is PendingOp.SetReminder -> "a reminder"
            is PendingOp.ClearReminder -> "a reminder change"
            is PendingOp.Delete -> "a deletion"
            is PendingOp.AttachMedia -> "an image"
            is PendingOp.DeleteMedia -> "removing an image"
            is PendingOp.CreateList -> "creating a list"
            is PendingOp.UpdateList -> "renaming a list"
            is PendingOp.DeleteList -> "deleting a list"
        }
        val isListOp = op is PendingOp.CreateList || op is PendingOp.UpdateList || op is PendingOp.DeleteList
        val why = when (code) {
            404 -> if (isListOp) "the list no longer exists" else "the note no longer exists"
            403 -> "you no longer have access"
            // The media endpoint's own limits — worth naming, since "the server refused it"
            // tells someone nothing about a photo that was simply too big.
            409 -> "the note already has the maximum number of images"
            413 -> "the image is too large (max 10 MB)"
            400 -> if (op is PendingOp.AttachMedia) "the file isn't a supported image" else "the server refused it"
            else -> "the server refused it"
        }
        val kept = if (rescued) " It was saved to Pictures/keepIT instead." else ""
        return "Couldn't sync $what — $why.$kept"
    }
}

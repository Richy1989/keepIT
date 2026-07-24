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
import org.hyperstarit.keepitapp.data.ApiClient
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteDto
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
 */
class SyncEngine(
    private val client: ApiClient,
    private val outbox: Outbox,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val updater: CacheUpdater,
) {
    /** How synced data lands in the repository's cache — implemented by NotesRepository. */
    interface CacheUpdater {
        suspend fun onFetched(notes: List<NoteDto>, lists: List<ListDto>, stillPending: List<PendingOp>)
        suspend fun onIdRemapped(tempId: String, realId: String)
    }

    var onUnauthorized: (() -> Unit)? = null

    val status = MutableStateFlow(SyncStatus.IDLE)

    /** Human-readable messages for ops dropped as permanently failed. */
    val syncErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val syncMutex = Mutex()
    private val kickPending = AtomicBoolean(false)

    /** Fire-and-forget sync request; safe to call from anywhere, collapses concurrent calls. */
    fun kick() {
        if (kickPending.getAndSet(true)) return // an already-scheduled pass will cover this
        scope.launch { sync() }
    }

    /** Replays the outbox, then refetches notes + lists. Suspends until this pass completes. */
    suspend fun sync() {
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
                        outbox.removeFirst(op.opId)
                        syncErrors.tryEmit(permanentFailureMessage(op, t.code()))
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

    private fun permanentFailureMessage(op: PendingOp, code: Int): String {
        val what = when (op) {
            is PendingOp.Create -> "creating a note"
            is PendingOp.Update -> "an edit"
            is PendingOp.SetState -> "a note change"
            is PendingOp.SetLists -> "a list change"
            is PendingOp.SetReminder -> "a reminder"
            is PendingOp.ClearReminder -> "a reminder change"
            is PendingOp.Delete -> "a deletion"
        }
        val why = when (code) {
            404 -> "the note no longer exists"
            403 -> "you no longer have access"
            else -> "the server refused it"
        }
        return "Couldn't sync $what — $why."
    }
}

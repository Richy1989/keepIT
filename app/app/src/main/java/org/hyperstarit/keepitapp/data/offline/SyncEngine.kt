package org.hyperstarit.keepitapp.data.offline

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.hyperstarit.keepitapp.data.ApiClient
import org.hyperstarit.keepitapp.data.EmptyTrashDto
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.RefreshFailedException
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.ServerFeatures
import org.hyperstarit.keepitapp.data.SetNoteListsDto
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicBoolean

/** What the sync engine is doing right now, for the notes screen's status strip. */
enum class SyncStatus { IDLE, SYNCING, OFFLINE }

/**
 * Drains the [Outbox] against the REST API and then refetches everything — the counterpart of the
 * web's "mutate then refetch", batched up for reconnection. Triggered ([kick]) on sign-in, on
 * connectivity return, on every enqueue, on SignalR pushes, and by pull-to-refresh; runs are
 * single-flight, and a kick during a run gets a follow-up pass only if the run's fetch didn't
 * already cover it — which is what keeps the server's echo of this device's own writes from
 * costing a second full refetch ([SyncGate] has the reasoning).
 *
 * Every request counts against the server's rate limit, so a pass is kept to what it needs: the
 * server's version is read once per connection rather than every pass ([ServerFeatures.refreshIfStale]),
 * and a 429 makes the engine wait as long as the server's `Retry-After` says, then try again by
 * itself — rather than retrying on the next trigger, straight back into the limit.
 *
 * Failure policy per op: network/5xx stops the run (the queue keeps its head and a later trigger
 * retries); a 401 defers to the session (queue retained — re-login resumes replay); any other 4xx
 * is permanent for that op, which is dropped with a message on [syncErrors] so the user learns a
 * change didn't stick (e.g. the note was deleted on another device). Nothing the user made is
 * dropped with it: a refused image goes to the gallery, a refused note's text to Documents
 * ([NoteTextRescue]). What stopped a run is handed
 * to [ConnectivityMonitor.markOffline] as a [SyncProblem], so "offline" can say why.
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
    private val textRescue: NoteTextRescue,
    private val serverFeatures: ServerFeatures,
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
    private val gate = SyncGate(System::currentTimeMillis)

    /** Whether a [kick] loop is running; one at a time, and it picks up every kick made meanwhile. */
    private val loopRunning = AtomicBoolean(false)

    /** Fire-and-forget sync request; safe to call from anywhere, collapses concurrent calls. */
    fun kick() {
        if (isStandalone()) return
        gate.request()
        if (loopRunning.compareAndSet(false, true)) scope.launch { runLoop() }
        // Otherwise the running loop sees the request: its next check finds it uncovered.
    }

    /**
     * Passes until every kick is covered. Waits out a 429 first, and goes round again after one,
     * which is the retry a rate-limited device needs and nothing else would schedule. Any other
     * failure ends the loop, as before: the next trigger (connectivity, a push, an edit) restarts it.
     */
    private suspend fun runLoop() {
        var seen = 0L
        try {
            while (true) {
                gate.waitMs().takeIf { it > 0 }?.let { delay(it) }
                if (isStandalone()) break
                val ok = pass()
                val rateLimited = gate.waitMs() > 0
                if (!(ok || rateLimited) || !gate.hasUncovered) break
            }
        } finally {
            seen = gate.snapshot()
            loopRunning.set(false)
        }
        // A kick between the last check and the flag going down found the flag still up and left
        // its request to this loop, which had already decided to stop. Start again for it.
        if (gate.snapshot() != seen) kick()
    }

    /**
     * Replays the outbox, then refetches notes + lists. Suspends until this pass completes. While
     * the server's rate-limit wait lasts it makes no request at all: the [kick] loop syncs as soon as
     * the wait is over.
     */
    suspend fun sync() {
        if (isStandalone()) return
        if (gate.waitMs() > 0) return
        pass()
    }

    /** One replay-and-refetch; true when both went through. */
    private suspend fun pass(): Boolean = syncMutex.withLock {
        status.value = SyncStatus.SYNCING
        // Before the replay: what the queued ops may say depends on the server's version.
        serverFeatures.refreshIfStale()
        val replay = replay()
        if (!replay.drained) {
            status.value = SyncStatus.OFFLINE
            return@withLock false
        }
        // The server pushes `Changed` for each write before it answers, so the echo of what was just
        // sent is usually here already. A moment's grace lets the last one land before the snapshot
        // below, where it counts as covered — the writes are on the server, so the fetch holds them.
        if (replay.sent > 0) delay(ECHO_GRACE_MS)
        val snapshot = gate.snapshot()
        val fetched = fetchAll()
        if (fetched) gate.covered(snapshot)
        status.value = if (fetched) SyncStatus.IDLE else SyncStatus.OFFLINE
        fetched
    }

    /** How a replay went: whether the queue fully drained, and how many ops reached the server. */
    private data class Replay(val drained: Boolean, val sent: Int)

    /** Drains the queue (permanent 4xx drops count as drained). */
    private suspend fun replay(): Replay {
        var sent = 0
        while (true) {
            val op = outbox.peek() ?: return Replay(drained = true, sent = sent)
            try {
                when (op) {
                    is PendingOp.Create -> {
                        // Marked first: if the answer is lost, the server may hold the note already.
                        outbox.markSent(op.opId)
                        val created = client.api.createNote(op.dto.copy(id = PendingOp.clientIdOf(op.tempId)))
                        outbox.remapId(op.tempId, created.id)
                        updater.onIdRemapped(op.tempId, created.id)
                    }

                    is PendingOp.Update -> client.api.updateNote(
                        op.noteId,
                        op.dto.forServer(storesBackgrounds = serverFeatures.backgrounds == true),
                    )
                    is PendingOp.SetState -> client.api.setNoteState(op.noteId, op.state)
                    is PendingOp.SetLists -> client.api.setNoteLists(op.noteId, SetNoteListsDto(op.listIds))
                    is PendingOp.SetReminder -> client.api.setReminder(op.noteId, op.dto)
                    is PendingOp.ClearReminder -> client.api.clearReminder(op.noteId)
                    is PendingOp.Delete -> client.api.deleteNote(op.noteId)
                    is PendingOp.EmptyTrash -> client.api.emptyTrash(EmptyTrashDto(op.noteIds))

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
                            // So a resend whose first answer was lost gets that attachment back, not a copy.
                            PendingOp.clientIdOf(op.tempMediaId)?.toRequestBody(),
                        )
                        staging.delete(op.stagedPath)
                        // Into the cache before the op leaves the outbox, so the image hands over
                        // from its pending preview to the real thing instead of vanishing until the
                        // refetch below.
                        updater.onMediaUploaded(op.noteId, media)
                    }

                    is PendingOp.DeleteMedia -> client.api.deleteNoteMedia(op.noteId, op.mediaId)

                    is PendingOp.CreateList -> {
                        outbox.markSent(op.opId)
                        val created = client.api.createList(op.dto.copy(id = PendingOp.clientIdOf(op.tempId)))
                        outbox.remapListId(op.tempId, created.id)
                        updater.onListIdRemapped(op.tempId, created.id)
                    }

                    is PendingOp.UpdateList -> client.api.updateList(op.listId, op.dto)
                    is PendingOp.DeleteList -> client.api.deleteList(op.listId)
                }
                outbox.removeFirst(op.opId)
                sent++
                connectivity.markOnline()
            } catch (t: Throwable) {
                when {
                    t is HttpException && t.code() == 401 -> {
                        onUnauthorized?.invoke()
                        return Replay(drained = false, sent = sent)
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
                            // The same for text: a refused create or edit is dropped, and the
                            // refetch that follows replaces the phone's copy with the server's. For
                            // a note written offline or in standalone mode, this op was the only copy.
                            is PendingOp.Create, is PendingOp.Update -> rescued = textRescue.rescue(op)
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
                        stopped(t)
                        return Replay(drained = false, sent = sent)
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
        if (t is HttpException && t.code() == 401) onUnauthorized?.invoke() else stopped(t)
        false
    }

    /**
     * Records what stopped a run, for the status strip and for `adb logcat`. A cancelled pass (the
     * widget's broadcast window closing) is left out: it says nothing about the server, and would
     * otherwise leave the app announcing a failure that never happened.
     */
    private fun stopped(t: Throwable) {
        if (t is CancellationException) return
        rateLimitWaitMs(t)?.let { wait ->
            gate.rateLimited(wait)
            // A pass that wasn't started by a kick (the widget's) has no loop to retry it.
            kick()
        }
        val problem = syncProblemOf(t, client.baseUrl?.toHttpUrlOrNull()?.host)
        Log.w(TAG, "Sync stopped: ${problem.message}", t)
        connectivity.markOffline(problem)
    }

    /**
     * How long the server asked us to wait, when [t] is its rate limiter's 429 — from the sync's
     * own request, or from the token refresh it needed first (which says nothing about how long).
     */
    private fun rateLimitWaitMs(t: Throwable): Long? = when {
        t is HttpException && t.code() == 429 ->
            retryAfterMillis(t.response()?.headers()?.get("Retry-After"), System.currentTimeMillis())
        t is RefreshFailedException && t.httpCode == 429 -> DEFAULT_RETRY_AFTER_MS
        else -> null
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
            is PendingOp.EmptyTrash -> "emptying the trash"
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
            413 -> "the image is too large (max 10 MB and 100 megapixels)"
            400 -> if (op is PendingOp.AttachMedia) "the file isn't a supported image" else "the server refused it"
            else -> "the server refused it"
        }
        val kept = when {
            !rescued -> ""
            op is PendingOp.AttachMedia -> " It was saved to Pictures/keepIT instead."
            else -> " Its text was saved to Documents/keepIT."
        }
        return "Couldn't sync $what — $why.$kept"
    }

    private companion object {
        const val TAG = "SyncEngine"

        /** Long enough for a push sent just before the write's answer to arrive; short enough not to notice. */
        const val ECHO_GRACE_MS = 300L
    }
}

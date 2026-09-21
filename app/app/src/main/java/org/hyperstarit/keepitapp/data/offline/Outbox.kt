package org.hyperstarit.keepitapp.data.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hyperstarit.keepitapp.data.ReminderRecurrences
import org.hyperstarit.keepitapp.data.UpdateListDto
import java.time.Instant

/**
 * The persisted FIFO queue of offline mutations. Enqueueing coalesces per note (see [coalesce]) so
 * the queue stays tiny and replay sends the minimum number of requests; the sync engine drains it
 * front-to-back, which preserves per-note causality (a note's Create is always ahead of ops that
 * reference it). All access is serialized on one mutex so replay and enqueue never interleave
 * mid-step.
 */
class Outbox(private val store: LocalStore) {

    private val mutex = Mutex()
    private var ops = mutableListOf<PendingOp>()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private val _pendingOps = MutableStateFlow<List<PendingOp>>(emptyList())

    /**
     * The queue as a flow, for UI that renders queued work — the editor shows a picked image from
     * its staged file while the upload is still waiting to go out.
     */
    val pendingOps: StateFlow<List<PendingOp>> = _pendingOps

    /** Restores the queue from disk — call once at startup before any enqueue or replay. */
    suspend fun load() = mutex.withLock {
        ops = store.loadOutbox().toMutableList()
        _pendingCount.value = ops.size
        _pendingOps.value = ops.toList()
    }

    /**
     * Merges an op into the queue and returns the ops coalescing discarded, so a caller can release
     * resources they owned — a dropped [PendingOp.AttachMedia] still has a staged file on disk.
     */
    suspend fun enqueue(op: PendingOp): List<PendingOp> = mutex.withLock {
        val before = ops.toList()
        ops = coalesce(ops, op).toMutableList()
        persist()
        before.filter { old -> ops.none { it.opId == old.opId } }
    }

    suspend fun peek(): PendingOp? = mutex.withLock { ops.firstOrNull() }

    /** Removes the head only if it is still [opId] — a concurrent coalesce may have replaced it. */
    suspend fun removeFirst(opId: String) = mutex.withLock {
        if (ops.firstOrNull()?.opId == opId) {
            ops.removeAt(0)
            persist()
        }
    }

    /**
     * Takes one op out of the queue wherever it sits — withdrawing an attachment that will never be
     * sent, in standalone mode.
     *
     * @return the removed op, or null when it was no longer queued.
     */
    suspend fun remove(opId: String): PendingOp? = mutex.withLock {
        val op = ops.firstOrNull { it.opId == opId } ?: return@withLock null
        ops.remove(op)
        persist()
        op
    }

    /** Rewrites every queued op that references a temp note id to the server-assigned id. */
    suspend fun remapId(tempId: String, realId: String) = mutex.withLock {
        ops = ops.map { op ->
            when (op) {
                is PendingOp.Update -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                is PendingOp.SetState -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                is PendingOp.SetLists -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                is PendingOp.SetReminder -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                is PendingOp.ClearReminder -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                is PendingOp.Delete -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                // The case that matters most: a photo attached to a note that was itself created
                // offline would otherwise upload against an id the server has never seen.
                is PendingOp.AttachMedia -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                is PendingOp.DeleteMedia -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                is PendingOp.Create -> op
                // List ops reference lists, not notes — see [remapListId].
                is PendingOp.CreateList, is PendingOp.UpdateList, is PendingOp.DeleteList -> op
            }
        }.toMutableList()
        persist()
    }

    /** Rewrites every queued reference to a temp list id — memberships included — to the server's. */
    suspend fun remapListId(tempId: String, realId: String) = mutex.withLock {
        ops = remapListIds(ops, tempId, realId).toMutableList()
        persist()
    }

    /**
     * Drops a list the server refused to create, and every queued reference to it. Anything left
     * pointing at its temp id would be refused in turn, taking whole notes down with it.
     */
    suspend fun forgetList(listId: String) = mutex.withLock {
        ops = withoutListReferences(ops.filterNot { it.targetId == listId }, listId).toMutableList()
        persist()
    }

    /** Readies a standalone device's queue for its first replay into an account; see [readiedForUpload]. */
    suspend fun prepareForUpload(nowMs: Long) = mutex.withLock {
        ops = readiedForUpload(ops, nowMs).toMutableList()
        persist()
    }

    suspend fun snapshot(): List<PendingOp> = mutex.withLock { ops.toList() }

    suspend fun clear() = mutex.withLock {
        ops.clear()
        persist()
    }

    private suspend fun persist() {
        store.saveOutbox(ops)
        _pendingCount.value = ops.size
        _pendingOps.value = ops.toList()
    }
}

/**
 * Merges a new op into the queue, collapsing redundant work per note:
 * - **Update / SetLists** onto a queued [PendingOp.Create] fold into the create's DTO — the note
 *   doesn't exist server-side yet, so one POST carries the final content. Otherwise they replace
 *   any earlier op of the same kind for the note (absolute payloads: only the last matters).
 * - **SetState** merges field-wise into an earlier queued SetState (non-null flags overwrite).
 * - **SetReminder / ClearReminder** are a last-wins pair: either replaces any earlier reminder op
 *   for the note. A Clear against a note that only exists locally queues nothing (the server never
 *   had a reminder to clear); a Set stays queued behind the Create and is remapped with it.
 * - **Delete** of a queued Create annihilates every op for that note — nothing is ever sent.
 *   Deleting an existing note drops its queued edits (the server purge makes them moot).
 * - **AttachMedia / DeleteMedia** never coalesce with anything: two photos are two uploads, and an
 *   Update touches a different resource entirely. A note-level Delete still removes them.
 * - **CreateList / UpdateList / DeleteList** mirror the note rules: a rename folds into a queued
 *   CreateList or merges field-wise into an earlier rename; deleting a list that only exists
 *   locally annihilates its ops. Deleting any list also takes it out of every queued membership.
 *
 * One ordering constraint runs across both: replay is FIFO, so no op may reference a temp list id
 * whose CreateList sits behind it — the server would refuse the request outright. That is why a
 * SetLists only folds into a note's Create when every list it names was created first.
 *
 * Pure so the rules are unit-testable; the [Outbox] applies the result under its lock.
 */
fun coalesce(ops: List<PendingOp>, incoming: PendingOp): List<PendingOp> {
    val id = incoming.targetId
    val pendingCreate = ops.filterIsInstance<PendingOp.Create>().firstOrNull { it.tempId == id }
    val pendingListCreate = ops.filterIsInstance<PendingOp.CreateList>().firstOrNull { it.tempId == id }

    return when (incoming) {
        is PendingOp.Create -> ops + incoming

        is PendingOp.Update ->
            if (pendingCreate != null) {
                ops.map { op ->
                    if (op !== pendingCreate) op else op.copy(
                        dto = op.dto.copy(
                            type = incoming.dto.type,
                            title = incoming.dto.title,
                            body = incoming.dto.body,
                            color = incoming.dto.color,
                            checklistItems = incoming.dto.checklistItems,
                        ),
                    )
                }
            } else {
                ops.filterNot { it is PendingOp.Update && it.noteId == id } + incoming
            }

        is PendingOp.SetLists -> {
            // Only the latest membership matters, so an earlier queued one goes either way.
            val rest = ops.filterNot { it is PendingOp.SetLists && it.noteId == id }
            if (pendingCreate != null && !namesListCreatedAfter(rest, pendingCreate, incoming.listIds)) {
                rest.map { op ->
                    if (op !== pendingCreate) op
                    else op.copy(dto = op.dto.copy(listIds = incoming.listIds.ifEmpty { null }))
                }
            } else {
                rest + incoming
            }
        }

        is PendingOp.SetState -> {
            val earlier = ops.filterIsInstance<PendingOp.SetState>().firstOrNull { it.noteId == id }
            if (earlier != null) {
                ops.map { op ->
                    if (op !== earlier) op else earlier.copy(
                        state = earlier.state.copy(
                            isPinned = incoming.state.isPinned ?: earlier.state.isPinned,
                            isArchived = incoming.state.isArchived ?: earlier.state.isArchived,
                            isTrashed = incoming.state.isTrashed ?: earlier.state.isTrashed,
                        ),
                    )
                }
            } else {
                ops + incoming
            }
        }

        is PendingOp.SetReminder, is PendingOp.ClearReminder -> {
            val remaining = ops.filterNot {
                (it is PendingOp.SetReminder || it is PendingOp.ClearReminder) && it.targetId == id
            }
            if (incoming is PendingOp.ClearReminder && pendingCreate != null) remaining
            else remaining + incoming
        }

        is PendingOp.Delete -> {
            val remaining = ops.filterNot { it.targetId == id }
            if (pendingCreate != null) remaining else remaining + incoming
        }

        // Media ops never coalesce, from either side: two photos are two independent uploads, and
        // an Update must not swallow an attachment (they touch different resources). A note-level
        // Delete still annihilates them — that branch filters by targetId, above.
        is PendingOp.AttachMedia, is PendingOp.DeleteMedia -> ops + incoming

        is PendingOp.CreateList -> ops + incoming

        is PendingOp.UpdateList -> {
            // Fold into whatever already carries this list's name and color — its queued create,
            // else an earlier rename. Only a list with neither gets an op of its own.
            val earlier = pendingListCreate
                ?: ops.filterIsInstance<PendingOp.UpdateList>().firstOrNull { it.listId == id }
            if (earlier == null) ops + incoming
            else ops.map { op -> if (op !== earlier) op else op.withListChanges(incoming.dto) }
        }

        is PendingOp.DeleteList -> {
            val remaining = withoutListReferences(ops.filterNot { it.targetId == id }, id)
            if (pendingListCreate != null) remaining else remaining + incoming
        }
    }
}

/** A queued list create or rename with [changes] merged in; null fields in [changes] keep the old value. */
private fun PendingOp.withListChanges(changes: UpdateListDto): PendingOp = when (this) {
    is PendingOp.CreateList -> copy(
        dto = dto.copy(name = changes.name ?: dto.name, color = changes.color ?: dto.color),
    )
    is PendingOp.UpdateList -> copy(
        dto = dto.copy(name = changes.name ?: dto.name, color = changes.color ?: dto.color),
    )
    else -> this
}

/**
 * True when [listIds] names a list whose [PendingOp.CreateList] sits *behind* [create] in [ops].
 * Folding that membership into the create would have the note's POST carry a temp list id the
 * server has not been told about yet — it would refuse the whole request, and the note with it.
 */
private fun namesListCreatedAfter(ops: List<PendingOp>, create: PendingOp.Create, listIds: List<String>): Boolean {
    val createAt = ops.indexOfFirst { it === create }
    return ops.withIndex().any { (index, op) ->
        index > createAt && op is PendingOp.CreateList && op.tempId in listIds
    }
}

/**
 * [ops] with [listId] taken out of every queued membership: a note create's initial lists and every
 * set-lists. For a list that never reached the server this is required, not tidy — a temp id left
 * in a payload gets the whole request refused rather than just the list skipped.
 */
fun withoutListReferences(ops: List<PendingOp>, listId: String): List<PendingOp> = ops.map { op ->
    when (op) {
        is PendingOp.Create -> {
            val ids = op.dto.listIds
            if (ids == null || listId !in ids) op
            else op.copy(dto = op.dto.copy(listIds = (ids - listId).ifEmpty { null }))
        }
        is PendingOp.SetLists -> if (listId !in op.listIds) op else op.copy(listIds = op.listIds - listId)
        else -> op
    }
}

/** [ops] with every reference to the temp list [tempId] — memberships included — moved to [realId]. */
fun remapListIds(ops: List<PendingOp>, tempId: String, realId: String): List<PendingOp> {
    fun List<String>.remapped() = map { if (it == tempId) realId else it }
    return ops.map { op ->
        when (op) {
            is PendingOp.Create -> {
                val ids = op.dto.listIds
                if (ids == null || tempId !in ids) op else op.copy(dto = op.dto.copy(listIds = ids.remapped()))
            }
            is PendingOp.SetLists -> if (tempId !in op.listIds) op else op.copy(listIds = op.listIds.remapped())
            is PendingOp.UpdateList -> if (op.listId == tempId) op.copy(listId = realId) else op
            is PendingOp.DeleteList -> if (op.listId == tempId) op.copy(listId = realId) else op
            else -> op
        }
    }
}

/**
 * A standalone device's queue, readied for its first replay into an account.
 *
 * Everything replays as it is except reminders that are already due. The server fires any reminder
 * it is handed in the past, so a one-time reminder that went off on this device would go off again
 * — it is dropped, as a reminder that is over. A recurring one would get a catch-up for an
 * occurrence this device already showed — it moves on to its next occurrence after [nowMs] instead.
 */
fun readiedForUpload(ops: List<PendingOp>, nowMs: Long): List<PendingOp> = ops.mapNotNull { op ->
    if (op !is PendingOp.SetReminder) return@mapNotNull op
    val atMs = epochMsOrNull(op.dto.remindAtUtc) ?: return@mapNotNull op
    when {
        atMs > nowMs -> op
        op.dto.recurrence == ReminderRecurrences.NONE -> null
        else -> op.copy(
            dto = op.dto.copy(
                remindAtUtc = Instant.ofEpochMilli(nextOccurrenceAfter(atMs, op.dto.recurrence, nowMs)).toString(),
            ),
        )
    }
}

package org.spaceelephant.keepitapp.data.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Restores the queue from disk — call once at startup before any enqueue or replay. */
    suspend fun load() = mutex.withLock {
        ops = store.loadOutbox().toMutableList()
        _pendingCount.value = ops.size
    }

    suspend fun enqueue(op: PendingOp) = mutex.withLock {
        ops = coalesce(ops, op).toMutableList()
        persist()
    }

    suspend fun peek(): PendingOp? = mutex.withLock { ops.firstOrNull() }

    /** Removes the head only if it is still [opId] — a concurrent coalesce may have replaced it. */
    suspend fun removeFirst(opId: String) = mutex.withLock {
        if (ops.firstOrNull()?.opId == opId) {
            ops.removeAt(0)
            persist()
        }
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
                is PendingOp.Create -> op
            }
        }.toMutableList()
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
 *
 * Pure so the rules are unit-testable; the [Outbox] applies the result under its lock.
 */
fun coalesce(ops: List<PendingOp>, incoming: PendingOp): List<PendingOp> {
    val id = incoming.targetId
    val pendingCreate = ops.filterIsInstance<PendingOp.Create>().firstOrNull { it.tempId == id }

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

        is PendingOp.SetLists ->
            if (pendingCreate != null) {
                ops.map { op ->
                    if (op !== pendingCreate) op
                    else op.copy(dto = op.dto.copy(listIds = incoming.listIds.ifEmpty { null }))
                }
            } else {
                ops.filterNot { it is PendingOp.SetLists && it.noteId == id } + incoming
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
    }
}

package org.hyperstarit.keepitapp.data.offline

import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.UpdateNoteDto

/**
 * The ops behind acting on several notes at once — the note list's multi-select. There is no batch
 * endpoint and no batch op: each function yields the op a single-note action already queues, once
 * per note it actually changes, so the outbox coalesces them per note as usual and replay sends
 * exactly what tapping each card in turn would have. That also keeps a new app working against an
 * older server. A note already in the requested state queues nothing.
 *
 * Pure (no Android types), like [applyOp], so the rules are JVM-tested.
 */

/**
 * Sets [state]'s non-null flags on each of [notes] that doesn't have them yet. Pin, archive and
 * trash are per-user state, so every note the user can see qualifies, shared or not.
 */
fun stateOps(notes: List<NoteDto>, state: NoteStateDto, enqueuedAtUtc: String = ""): List<PendingOp> =
    notes
        .filter { n ->
            (state.isPinned != null && state.isPinned != n.isPinned) ||
                (state.isArchived != null && state.isArchived != n.isArchived) ||
                (state.isTrashed != null && state.isTrashed != n.isTrashed)
        }
        .map { PendingOp.SetState(it.id, state, enqueuedAtUtc = enqueuedAtUtc) }

/**
 * Recolours each of [notes] the user can edit. A colour is the note's content, not per-user state,
 * and the server only sets it through the full content update — so this sends the note as cached
 * with the new colour, as the web card's colour picker does. A view-only note is skipped: the server
 * would refuse it, and that refusal would surface as a sync error about a note nobody edited.
 */
fun colorOps(notes: List<NoteDto>, color: String?, enqueuedAtUtc: String = ""): List<PendingOp> =
    notes
        .filter { it.canEdit && it.color != color }
        .map { n -> PendingOp.Update(n.id, n.toUpdateDto().copy(color = color), enqueuedAtUtc = enqueuedAtUtc) }

/**
 * Files each of [notes] into [listId] when [member], or takes it out. Membership is per-user, like
 * pin and archive, so every note qualifies; the rest of each note's lists are left as they are.
 */
fun listMembershipOps(
    notes: List<NoteDto>,
    listId: String,
    member: Boolean,
    enqueuedAtUtc: String = "",
): List<PendingOp> =
    notes
        .filter { (listId in it.listIds) != member }
        .map { n ->
            PendingOp.SetLists(
                n.id,
                if (member) n.listIds + listId else n.listIds - listId,
                enqueuedAtUtc = enqueuedAtUtc,
            )
        }

/**
 * Whether [notes] are filed in [listId]: true when all of them are, false when none is, and null
 * when only some are — the list picker's checked, unchecked and indeterminate boxes.
 */
fun membershipOf(notes: List<NoteDto>, listId: String): Boolean? {
    val filed = notes.count { listId in it.listIds }
    return when (filed) {
        0 -> false
        notes.size -> true
        else -> null
    }
}

/** The content half of a note, as the full update sends it. */
private fun NoteDto.toUpdateDto() = UpdateNoteDto(
    type = type,
    title = title,
    body = body,
    color = color,
    checklistItems = checklistItems,
)

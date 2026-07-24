package org.hyperstarit.keepitapp.data.offline

import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NotesFilter
import org.hyperstarit.keepitapp.data.NotesView
import org.hyperstarit.keepitapp.data.ensureUtc
import java.time.Instant

/**
 * Pure functions over the merged note cache — the single source of truth for how a [PendingOp]
 * changes the local view and how the cache is filtered for display. Kept side-effect-free (no
 * Android types) so the offline semantics are unit-testable on the JVM.
 */

/** Applies one queued op to the cached notes, exactly as the server eventually will. */
fun applyOp(notes: List<NoteDto>, op: PendingOp): List<NoteDto> = when (op) {
    is PendingOp.Create -> listOf(tempNote(op)) + notes

    is PendingOp.Update -> notes.map { n ->
        if (n.id != op.noteId) n else n.copy(
            type = op.dto.type,
            title = op.dto.title,
            body = op.dto.body,
            color = op.dto.color,
            checklistItems = op.dto.checklistItems ?: emptyList(),
            updatedAtUtc = op.enqueuedAtUtc,
        )
    }

    is PendingOp.SetState -> notes.map { n ->
        if (n.id != op.noteId) n else n.copy(
            isPinned = op.state.isPinned ?: n.isPinned,
            isArchived = op.state.isArchived ?: n.isArchived,
            isTrashed = op.state.isTrashed ?: n.isTrashed,
        )
    }

    is PendingOp.SetLists -> notes.map { n -> if (n.id != op.noteId) n else n.copy(listIds = op.listIds) }

    // Setting always resets the fired state, mirroring the server's SetReminder.
    is PendingOp.SetReminder -> notes.map { n ->
        if (n.id != op.noteId) n else n.copy(
            remindAtUtc = op.dto.remindAtUtc,
            reminderRecurrence = op.dto.recurrence,
            reminderFired = false,
        )
    }

    is PendingOp.ClearReminder -> notes.map { n ->
        if (n.id != op.noteId) n else n.copy(remindAtUtc = null, reminderRecurrence = null, reminderFired = false)
    }

    is PendingOp.Delete -> notes.filter { it.id != op.noteId }
}

/**
 * Overlays every still-queued op onto a fresh server fetch, so notes created/edited offline don't
 * flicker away while their ops are waiting to replay.
 */
fun applyPending(notes: List<NoteDto>, ops: List<PendingOp>): List<NoteDto> =
    ops.fold(notes, ::applyOp)

/** The local NoteDto for a note created offline, alive until the POST returns the real one. */
private fun tempNote(op: PendingOp.Create): NoteDto = NoteDto(
    id = op.tempId,
    type = op.dto.type,
    title = op.dto.title,
    body = op.dto.body,
    color = op.dto.color,
    checklistItems = op.dto.checklistItems ?: emptyList(),
    listIds = op.dto.listIds ?: emptyList(),
    createdAtUtc = op.enqueuedAtUtc,
    updatedAtUtc = op.enqueuedAtUtc,
)

/**
 * The grid slice for a filter, mirroring the server's `GetNotes` semantics now that filtering is
 * local: view from the per-user flags, list filter as a union, pinned first then last-updated.
 */
fun visibleNotes(notes: List<NoteDto>, filter: NotesFilter): List<NoteDto> = notes
    .filter { n ->
        when (filter.view) {
            NotesView.TRASHED -> n.isTrashed
            NotesView.ARCHIVED -> n.isArchived && !n.isTrashed
            NotesView.ACTIVE -> !n.isArchived && !n.isTrashed
        }
    }
    .filter { n -> filter.listIds.isEmpty() || n.listIds.any { it in filter.listIds } }
    .sortedWith(compareByDescending<NoteDto> { it.isPinned }.thenByDescending { epochMs(it.updatedAtUtc) })

/** Per-list active note counts for the drawer, replacing the server-computed `ListDto.noteCount`. */
fun activeListCounts(notes: List<NoteDto>): Map<String, Int> = notes
    .filter { !it.isArchived && !it.isTrashed }
    .flatMap { it.listIds }
    .groupingBy { it }
    .eachCount()

private fun epochMs(iso: String): Long =
    runCatching { Instant.parse(ensureUtc(iso)).toEpochMilli() }.getOrDefault(0L)

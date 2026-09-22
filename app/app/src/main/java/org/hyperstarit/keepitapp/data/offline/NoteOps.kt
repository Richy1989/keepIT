package org.hyperstarit.keepitapp.data.offline

import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.NotesFilter
import org.hyperstarit.keepitapp.data.NotesView
import org.hyperstarit.keepitapp.data.ReminderRecurrences
import org.hyperstarit.keepitapp.data.ensureUtc
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

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

    // Deleted or left, the note is gone from this user's notes either way.
    is PendingOp.EmptyTrash -> op.noteIds.toSet().let { gone -> notes.filter { it.id !in gone } }

    // Attaching can't be represented on a NoteDto: there is no server id, width or height yet, and
    // inventing a client-only field on the DTO is exactly the drift the hand-sync rule forbids.
    // Pending attachments are projected separately by [pendingMedia] and rendered from their staged
    // file instead.
    is PendingOp.AttachMedia -> notes

    is PendingOp.DeleteMedia -> notes.map { n ->
        if (n.id != op.noteId) n else n.copy(media = n.media.filterNot { it.id == op.mediaId })
    }

    // A deleted list takes its memberships with it; the notes themselves stay, as on the server.
    is PendingOp.DeleteList -> notes.map { n ->
        if (op.listId !in n.listIds) n else n.copy(listIds = n.listIds - op.listId)
    }

    // The lists themselves live beside the notes, not on them — see [applyListOp].
    is PendingOp.CreateList, is PendingOp.UpdateList -> notes
}

/**
 * Applies one queued op to the cached lists — the list-side twin of [applyOp]. Only list ops change
 * anything here. The result keeps the drawer's alphabetical order, so a list created offline lands
 * where the next fetch will put it.
 */
fun applyListOp(lists: List<ListDto>, op: PendingOp): List<ListDto> = when (op) {
    is PendingOp.CreateList -> sortedLists(
        lists + ListDto(
            id = op.tempId,
            name = op.dto.name,
            color = op.dto.color,
            createdAtUtc = op.enqueuedAtUtc,
        ),
    )

    // Null fields are left unchanged, mirroring the server's PATCH.
    is PendingOp.UpdateList -> sortedLists(
        lists.map { l ->
            if (l.id != op.listId) l else l.copy(name = op.dto.name ?: l.name, color = op.dto.color ?: l.color)
        },
    )

    is PendingOp.DeleteList -> lists.filter { it.id != op.listId }

    is PendingOp.Create, is PendingOp.Update, is PendingOp.SetState, is PendingOp.SetLists,
    is PendingOp.SetReminder, is PendingOp.ClearReminder, is PendingOp.Delete,
    is PendingOp.AttachMedia, is PendingOp.DeleteMedia, is PendingOp.EmptyTrash -> lists
}

/** Overlays every still-queued list op onto a fresh server fetch — [applyPending] for lists. */
fun applyPendingLists(lists: List<ListDto>, ops: List<PendingOp>): List<ListDto> =
    ops.fold(lists, ::applyListOp)

/** The drawer's order: alphabetical, ignoring case. */
private fun sortedLists(lists: List<ListDto>): List<ListDto> = lists.sortedBy { it.name.lowercase() }

/** The still-queued attachments for one note, in the order they were picked. */
fun pendingMedia(ops: List<PendingOp>, noteId: String): List<PendingOp.AttachMedia> =
    ops.filterIsInstance<PendingOp.AttachMedia>().filter { it.noteId == noteId }

/**
 * Adds a just-uploaded image to its cached note, straight from the upload's response.
 *
 * Without this the image had nowhere to render between the upload and the next full fetch: its
 * queued attachment leaves the outbox as the upload succeeds, while the cached note doesn't list it
 * until the fetch lands — so an open editor's image row blanked, and the text beneath it jumped, for
 * a whole round trip. Idempotent, because that fetch then reports the very same media id.
 */
fun withUploadedMedia(notes: List<NoteDto>, noteId: String, media: NoteMediaDto): List<NoteDto> =
    notes.map { n ->
        if (n.id != noteId || n.media.any { it.id == media.id }) n
        else n.copy(media = (n.media + media).sortedBy { it.order })
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
 * local: view from the per-user flags, list filter as a union, then [sortFor] the view.
 */
fun visibleNotes(notes: List<NoteDto>, filter: NotesFilter): List<NoteDto> = notes
    .filter { n ->
        when (filter.view) {
            NotesView.TRASHED -> n.isTrashed
            NotesView.ARCHIVED -> n.isArchived && !n.isTrashed
            NotesView.ACTIVE -> !n.isArchived && !n.isTrashed
            // Reminders span active *and* archived (like Keep, the web, and the server's
            // `?reminders=true`), but never trash — a trashed note must not nag.
            NotesView.REMINDERS -> n.remindAtUtc != null && !n.isTrashed
        }
    }
    .filter { n -> filter.listIds.isEmpty() || n.listIds.any { it in filter.listIds } }
    .sortedWith(sortFor(filter.view))

/**
 * The comparator for a view, matching the server's `GetNotes` and the web's `sortNotesFor`: the
 * reminders view is soonest-due first and ignores pins; every other view is pinned first, then
 * most recently updated.
 */
private fun sortFor(view: NotesView): Comparator<NoteDto> =
    if (view == NotesView.REMINDERS) compareBy { epochMs(it.remindAtUtc ?: "") }
    else compareByDescending<NoteDto> { it.isPinned }.thenByDescending { epochMs(it.updatedAtUtc) }

/** Per-list active note counts for the drawer, replacing the server-computed `ListDto.noteCount`. */
fun activeListCounts(notes: List<NoteDto>): Map<String, Int> = notes
    .filter { !it.isArchived && !it.isTrashed }
    .flatMap { it.listIds }
    .groupingBy { it }
    .eachCount()

/**
 * Moves reminders that have come due on, as the server's `ReminderDispatcherService` does — for
 * standalone mode, where there is no server to do it. A one-time reminder is marked fired; a
 * recurring one advances to its first occurrence after [nowMs], skipping any it missed (one
 * catch-up, like the server). A trashed note's reminder is left alone, also like the server: it is
 * still pending, and fires once the note is restored.
 *
 * Posting the notification is not this function's job — that belongs to
 * [org.hyperstarit.keepitapp.notifications.ReminderScheduler], which must see an occurrence before
 * it is advanced past. This only keeps the cache truthful: without it a fired one-time reminder
 * would stay pending forever, and a recurring one would keep showing the time it was first set for.
 */
fun settleDueReminders(notes: List<NoteDto>, nowMs: Long): List<NoteDto> = notes.map { n ->
    val at = n.remindAtUtc ?: return@map n
    if (n.reminderFired || n.isTrashed) return@map n
    val atMs = epochMsOrNull(at) ?: return@map n
    if (atMs > nowMs) return@map n

    val recurrence = n.reminderRecurrence ?: ReminderRecurrences.NONE
    if (recurrence == ReminderRecurrences.NONE) {
        n.copy(reminderFired = true)
    } else {
        n.copy(remindAtUtc = Instant.ofEpochMilli(nextOccurrenceAfter(atMs, recurrence, nowMs)).toString())
    }
}

/**
 * The next occurrence after [fromMs] — the same UTC arithmetic as the server's `Advance`, so an
 * occurrence computed on the device and one computed by the server agree.
 */
fun advanceOccurrence(fromMs: Long, recurrence: String): Long {
    val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromMs), ZoneOffset.UTC)
    val next = when (recurrence) {
        ReminderRecurrences.DAILY -> from.plusDays(1)
        ReminderRecurrences.WEEKLY -> from.plusWeeks(1)
        ReminderRecurrences.MONTHLY -> from.plusMonths(1)
        ReminderRecurrences.YEARLY -> from.plusYears(1)
        else -> from.plusDays(1) // unknown cadence: fail safe, never loop forever
    }
    return next.toInstant().toEpochMilli()
}

/** The first occurrence of a recurring reminder strictly after [nowMs], starting from [fromMs]. */
internal fun nextOccurrenceAfter(fromMs: Long, recurrence: String, nowMs: Long): Long {
    var next = fromMs
    while (next <= nowMs) next = advanceOccurrence(next, recurrence)
    return next
}

private fun epochMs(iso: String): Long = epochMsOrNull(iso) ?: 0L

internal fun epochMsOrNull(iso: String): Long? =
    runCatching { Instant.parse(ensureUtc(iso)).toEpochMilli() }.getOrNull()

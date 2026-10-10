package org.hyperstarit.keepitapp.data.offline

import org.hyperstarit.keepitapp.data.ChecklistItemDto
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteFields
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.NotesFilter
import org.hyperstarit.keepitapp.data.NotesView
import org.hyperstarit.keepitapp.data.ReminderRecurrences
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.data.SetNoteReminderDto
import org.hyperstarit.keepitapp.data.ensureUtc
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Pure functions over the merged note cache — the single source of truth for how a [PendingOp]
 * changes the local view and how the cache is filtered for display. Kept side-effect-free (no
 * Android types) so the offline semantics are unit-testable on the JVM.
 */

/** Applies one queued op to the cached notes, exactly as the server eventually will. */
fun applyOp(notes: List<NoteDto>, op: PendingOp): List<NoteDto> = when (op) {
    is PendingOp.Create -> listOf(tempNote(op)) + notes

    is PendingOp.Update -> notes.map { n ->
        // Only the fields the edit names, as the server will apply it: laid over a fresher fetch,
        // a pending edit to the text mustn't show back a title the server has since moved past.
        fun sets(field: String) = op.dto.fields?.contains(field) ?: true
        if (n.id != op.noteId) n else n.copy(
            type = if (sets(NoteFields.TYPE)) op.dto.type else n.type,
            title = if (sets(NoteFields.TITLE)) op.dto.title else n.title,
            body = if (sets(NoteFields.BODY)) op.dto.body else n.body,
            color = if (sets(NoteFields.COLOR)) op.dto.color else n.color,
            checklistItems = if (sets(NoteFields.CHECKLIST_ITEMS)) op.dto.checklistItems ?: emptyList() else n.checklistItems,
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
            reminderTimeZone = op.dto.timeZone,
            reminderFirstAtUtc = firstAtOf(op.dto),
            reminderFired = false,
        )
    }

    is PendingOp.ClearReminder -> notes.map { n ->
        if (n.id != op.noteId) n else n.copy(
            remindAtUtc = null,
            reminderRecurrence = null,
            reminderTimeZone = null,
            reminderFirstAtUtc = null,
            reminderFired = false,
        )
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
            icon = op.dto.icon?.ifEmpty { null },
            createdAtUtc = op.enqueuedAtUtc,
        ),
    )

    // Null fields are left unchanged, mirroring the server's PATCH; an empty icon removes it.
    is PendingOp.UpdateList -> sortedLists(
        lists.map { l ->
            if (l.id != op.listId) l else l.copy(
                name = op.dto.name ?: l.name,
                color = op.dto.color ?: l.color,
                icon = op.dto.icon.let { if (it == null) l.icon else it.ifEmpty { null } },
            )
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
        // As the server does, a reminder set before the first occurrence was kept counts from this one.
        val first = n.reminderFirstAtUtc ?: at
        val next = nextOccurrenceAfter(epochMsOrNull(first) ?: atMs, reminderZone(n.reminderTimeZone), recurrence, nowMs)
        n.copy(remindAtUtc = Instant.ofEpochMilli(next).toString(), reminderFirstAtUtc = first)
    }
}

/**
 * The reminder changes that give each repeating reminder without a time zone [zone]: what a
 * standalone phone does once with reminders set before reminders kept a zone (see
 * `NotesRepository.adoptPhoneZone`). Fired and one-time reminders have no repeats to keep a clock
 * for, and are left alone.
 */
fun zoneAdoptions(notes: List<NoteDto>, zone: String): List<Pair<String, SetNoteReminderDto>> =
    notes.mapNotNull { n ->
        val at = n.remindAtUtc ?: return@mapNotNull null
        val recurrence = n.reminderRecurrence ?: ReminderRecurrences.NONE
        if (n.reminderTimeZone != null || n.reminderFired || recurrence == ReminderRecurrences.NONE) {
            return@mapNotNull null
        }
        n.id to SetNoteReminderDto(at, recurrence, zone, n.reminderFirstAtUtc)
    }

/**
 * When a recurring reminder goes off next: the first occurrence strictly after [afterMs] of one
 * first set for [firstAtMs], repeating every [recurrence] on [zone]'s wall clock.
 *
 * This is the server's `ReminderSchedule.NextAfter`, rule for rule, and the two must agree to the
 * millisecond: the phone moves reminders on by itself while it is offline or has no server, and a
 * phone and a server that disagree post one reminder twice, at two different times. Both are held
 * to the same cases, `keepIT/keepITCore.Tests/ReminderOccurrences.json`. The rules:
 * - Repeats keep the wall-clock time, so 08:00 stays 08:00 when the clocks change.
 * - Each occurrence is counted from the first, never from the one before it: a monthly reminder on
 *   the 31st falls on February's 28th, then comes back to March's 31st.
 * - A time the clocks skip moves on by the gap (02:30 becomes 03:30), and a time they repeat goes
 *   off the first time round: `LocalDateTime.atZone`'s own rules, which the server copies.
 */
fun nextOccurrenceAfter(firstAtMs: Long, zone: ZoneId, recurrence: String, afterMs: Long): Long {
    val first = LocalDateTime.ofInstant(Instant.ofEpochMilli(firstAtMs), zone)
    val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMs), zone)

    // Jump to just before `after` rather than step from the first occurrence: a daily reminder set
    // three years ago is a thousand steps otherwise. Two short of the estimate, because an
    // occurrence moved on by a gap in the clock can land later than its date says.
    var n = maxOf(0L, elapsed(first, after, recurrence) - 2)
    while (true) {
        val next = occurrence(first, recurrence, n).atZone(zone).toInstant().toEpochMilli()
        if (next > afterMs) return next
        n++
    }
}

/** The [n]th occurrence after [first], on the wall clock. `plusMonths` clamps as .NET's `AddMonths` does. */
private fun occurrence(first: LocalDateTime, recurrence: String, n: Long): LocalDateTime = when (recurrence) {
    ReminderRecurrences.DAILY -> first.plusDays(n)
    ReminderRecurrences.WEEKLY -> first.plusWeeks(n)
    ReminderRecurrences.MONTHLY -> first.plusMonths(n)
    ReminderRecurrences.YEARLY -> first.plusYears(n)
    else -> first.plusDays(n) // unknown cadence: fail safe, never loop forever
}

/** Roughly how many repeats lie between two wall-clock times; never more than there are. */
private fun elapsed(first: LocalDateTime, after: LocalDateTime, recurrence: String): Long = when (recurrence) {
    ReminderRecurrences.WEEKLY -> ChronoUnit.DAYS.between(first.toLocalDate(), after.toLocalDate()) / 7
    ReminderRecurrences.MONTHLY -> (after.year - first.year) * 12L + after.monthValue - first.monthValue
    ReminderRecurrences.YEARLY -> (after.year - first.year).toLong()
    else -> ChronoUnit.DAYS.between(first.toLocalDate(), after.toLocalDate())
}

/**
 * The zone a reminder's repeats keep. A server reports one for every reminder; null means one from
 * before reminders carried a zone, and such a server counted repeats in UTC, so the phone must too
 * or the two would go off an hour apart. An id this phone can't resolve is treated the same way.
 */
fun reminderZone(id: String?): ZoneId =
    id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

/**
 * Where a reminder being set starts its series, as the server works it out: [SetNoteReminderDto.firstAtUtc]
 * when it is no later than the occurrence being set, else that occurrence.
 */
internal fun firstAtOf(dto: SetNoteReminderDto): String {
    val first = dto.firstAtUtc ?: return dto.remindAtUtc
    val firstMs = epochMsOrNull(first) ?: return dto.remindAtUtc
    val atMs = epochMsOrNull(dto.remindAtUtc) ?: return dto.remindAtUtc
    return if (firstMs <= atMs) first else dto.remindAtUtc
}

private fun epochMs(iso: String): Long = epochMsOrNull(iso) ?: 0L

internal fun epochMsOrNull(iso: String): Long? =
    runCatching { Instant.parse(ensureUtc(iso)).toEpochMilli() }.getOrNull()

/**
 * The [NoteFields] in which [after] differs from [before]: what an edit actually changed, and so all
 * it sends ([UpdateNoteDto.fields]). The checklist counts as changed when its rows differ in id, text
 * or tick, in their stored order — the order a row is shown in is derived from those.
 *
 * [before] has to be what the edit started from. The note as cached now is not that while an editor
 * is open: a sync can bring in a collaborator's change, which would then look like this edit's and
 * be sent back over theirs.
 */
fun changedFields(before: UpdateNoteDto, after: UpdateNoteDto): List<String> = buildList {
    if (after.type != before.type) add(NoteFields.TYPE)
    if (after.title != before.title) add(NoteFields.TITLE)
    if (after.body != before.body) add(NoteFields.BODY)
    if (after.color != before.color) add(NoteFields.COLOR)
    if (after.checklistItems.orEmpty().rows() != before.checklistItems.orEmpty().rows()) add(NoteFields.CHECKLIST_ITEMS)
}

/** [changedFields] against [note] as it stands — for an edit made from the cached note itself. */
fun changedFields(note: NoteDto, dto: UpdateNoteDto): List<String> = changedFields(
    UpdateNoteDto(type = note.type, title = note.title, body = note.body, color = note.color, checklistItems = note.checklistItems),
    dto,
)

private fun List<ChecklistItemDto>.rows() = sortedBy { it.order }.map { Triple(it.id, it.text, it.isChecked) }

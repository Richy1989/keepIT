package org.spaceelephant.keepitapp.offline

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.spaceelephant.keepitapp.data.ChecklistItemDto
import org.spaceelephant.keepitapp.data.CreateNoteDto
import org.spaceelephant.keepitapp.data.NoteDto
import org.spaceelephant.keepitapp.data.NoteStateDto
import org.spaceelephant.keepitapp.data.NoteTypes
import org.spaceelephant.keepitapp.data.NotesFilter
import org.spaceelephant.keepitapp.data.NotesView
import org.spaceelephant.keepitapp.data.ReminderRecurrences
import org.spaceelephant.keepitapp.data.SetNoteReminderDto
import org.spaceelephant.keepitapp.data.UpdateNoteDto
import org.spaceelephant.keepitapp.data.offline.PendingOp
import org.spaceelephant.keepitapp.data.offline.applyOp
import org.spaceelephant.keepitapp.data.offline.applyPending
import org.spaceelephant.keepitapp.data.offline.coalesce
import org.spaceelephant.keepitapp.data.offline.visibleNotes

/** JVM tests for the offline semantics: local op application, outbox coalescing, local filtering. */
class OfflineOpsTest {

    private fun note(
        id: String,
        title: String? = null,
        pinned: Boolean = false,
        archived: Boolean = false,
        trashed: Boolean = false,
        updatedAtUtc: String = "2026-07-08T10:00:00Z",
        listIds: List<String> = emptyList(),
    ) = NoteDto(
        id = id, title = title, isPinned = pinned, isArchived = archived, isTrashed = trashed,
        updatedAtUtc = updatedAtUtc, listIds = listIds,
    )

    // ---- applyOp ----

    @Test
    fun `create prepends a temp note carrying the dto's content`() {
        val op = PendingOp.Create(
            tempId = "local-1",
            dto = CreateNoteDto(
                type = NoteTypes.CHECKLIST,
                title = "Groceries",
                checklistItems = listOf(ChecklistItemDto(text = "Milk", order = 0)),
                listIds = listOf("list-a"),
            ),
            enqueuedAtUtc = "2026-07-08T12:00:00Z",
        )
        val result = applyOp(listOf(note("n1")), op)

        assertEquals(2, result.size)
        val temp = result.first()
        assertEquals("local-1", temp.id)
        assertEquals("Groceries", temp.title)
        assertEquals(NoteTypes.CHECKLIST, temp.type)
        assertEquals(listOf("list-a"), temp.listIds)
        assertEquals("Milk", temp.checklistItems.single().text)
        assertEquals("2026-07-08T12:00:00Z", temp.updatedAtUtc)
    }

    @Test
    fun `update replaces content and bumps the local timestamp`() {
        val op = PendingOp.Update(
            noteId = "n1",
            dto = UpdateNoteDto(type = NoteTypes.TEXT, title = "New", body = "Body"),
            enqueuedAtUtc = "2026-07-08T12:00:00Z",
        )
        val result = applyOp(listOf(note("n1", title = "Old"), note("n2", title = "Other")), op)

        assertEquals("New", result.first { it.id == "n1" }.title)
        assertEquals("2026-07-08T12:00:00Z", result.first { it.id == "n1" }.updatedAtUtc)
        assertEquals("Other", result.first { it.id == "n2" }.title)
    }

    @Test
    fun `setState merges only the non-null flags`() {
        val op = PendingOp.SetState("n1", NoteStateDto(isArchived = true))
        val result = applyOp(listOf(note("n1", pinned = true)), op)

        assertTrue(result.single().isPinned) // untouched
        assertTrue(result.single().isArchived)
        assertFalse(result.single().isTrashed)
    }

    @Test
    fun `delete removes the note`() {
        val result = applyOp(listOf(note("n1"), note("n2")), PendingOp.Delete("n1"))
        assertEquals(listOf("n2"), result.map { it.id })
    }

    @Test
    fun `setReminder stores the reminder and resets the fired state`() {
        val fired = note("n1").copy(remindAtUtc = "2026-07-01T08:00:00Z", reminderFired = true)
        val op = PendingOp.SetReminder(
            "n1",
            SetNoteReminderDto(remindAtUtc = "2026-07-20T08:00:00Z", recurrence = ReminderRecurrences.WEEKLY),
        )
        val result = applyOp(listOf(fired), op).single()

        assertEquals("2026-07-20T08:00:00Z", result.remindAtUtc)
        assertEquals(ReminderRecurrences.WEEKLY, result.reminderRecurrence)
        assertFalse(result.reminderFired)
    }

    @Test
    fun `clearReminder wipes all reminder fields`() {
        val withReminder = note("n1").copy(
            remindAtUtc = "2026-07-20T08:00:00Z",
            reminderRecurrence = ReminderRecurrences.DAILY,
            reminderFired = true,
        )
        val result = applyOp(listOf(withReminder), PendingOp.ClearReminder("n1")).single()

        assertNull(result.remindAtUtc)
        assertNull(result.reminderRecurrence)
        assertFalse(result.reminderFired)
    }

    @Test
    fun `applyPending overlays queued ops onto a fresh fetch`() {
        val fetched = listOf(note("n1", title = "Server"))
        val ops = listOf(
            PendingOp.Update("n1", UpdateNoteDto(type = NoteTypes.TEXT, title = "Local"), enqueuedAtUtc = "2026-07-08T12:00:00Z"),
            PendingOp.Create("local-1", CreateNoteDto(title = "Offline note"), enqueuedAtUtc = "2026-07-08T12:01:00Z"),
        )
        val result = applyPending(fetched, ops)

        assertEquals("Local", result.first { it.id == "n1" }.title)
        assertEquals("Offline note", result.first { it.id == "local-1" }.title)
    }

    // ---- coalesce ----

    @Test
    fun `update folds into a pending create - one POST carries the final content`() {
        val create = PendingOp.Create("local-1", CreateNoteDto(title = "v1", listIds = listOf("list-a")))
        val ops = coalesce(
            listOf(create),
            PendingOp.Update("local-1", UpdateNoteDto(type = NoteTypes.TEXT, title = "v2", body = "b")),
        )

        val folded = ops.single() as PendingOp.Create
        assertEquals("v2", folded.dto.title)
        assertEquals("b", folded.dto.body)
        assertEquals(listOf("list-a"), folded.dto.listIds) // list filing survives the fold
    }

    @Test
    fun `delete of a pending create annihilates every op for that note`() {
        val ops = listOf(
            PendingOp.Create("local-1", CreateNoteDto(title = "doomed")),
            PendingOp.SetState("local-1", NoteStateDto(isPinned = true)),
            PendingOp.Update("n-other", UpdateNoteDto(type = NoteTypes.TEXT)),
        )
        val result = coalesce(ops, PendingOp.Delete("local-1"))

        assertEquals(1, result.size) // only the unrelated op survives; no Delete is queued
        assertTrue(result.single() is PendingOp.Update)
    }

    @Test
    fun `delete of an existing note drops its queued edits and queues one delete`() {
        val ops = listOf(
            PendingOp.Update("n1", UpdateNoteDto(type = NoteTypes.TEXT, title = "x")),
            PendingOp.SetState("n1", NoteStateDto(isPinned = true)),
        )
        val result = coalesce(ops, PendingOp.Delete("n1"))

        assertEquals(1, result.size)
        assertTrue(result.single() is PendingOp.Delete)
    }

    @Test
    fun `a second update replaces the first - only the last matters`() {
        val ops = coalesce(
            listOf(PendingOp.Update("n1", UpdateNoteDto(type = NoteTypes.TEXT, title = "v1"))),
            PendingOp.Update("n1", UpdateNoteDto(type = NoteTypes.TEXT, title = "v2")),
        )
        assertEquals("v2", (ops.single() as PendingOp.Update).dto.title)
    }

    @Test
    fun `setState merges field-wise into an earlier queued setState`() {
        val ops = coalesce(
            listOf(PendingOp.SetState("n1", NoteStateDto(isPinned = true))),
            PendingOp.SetState("n1", NoteStateDto(isArchived = true)),
        )
        val merged = (ops.single() as PendingOp.SetState).state
        assertEquals(true, merged.isPinned)
        assertEquals(true, merged.isArchived)
        assertNull(merged.isTrashed)
    }

    @Test
    fun `reminder ops are a last-wins pair`() {
        val ops = coalesce(
            coalesce(
                listOf(PendingOp.SetReminder("n1", SetNoteReminderDto("2026-07-20T08:00:00Z"))),
                PendingOp.ClearReminder("n1"),
            ),
            PendingOp.SetReminder("n1", SetNoteReminderDto("2026-07-21T09:00:00Z")),
        )

        val only = ops.single() as PendingOp.SetReminder
        assertEquals("2026-07-21T09:00:00Z", only.dto.remindAtUtc)
    }

    @Test
    fun `clearReminder against a pending create queues nothing`() {
        val ops = coalesce(
            listOf(
                PendingOp.Create("local-1", CreateNoteDto(title = "t")),
                PendingOp.SetReminder("local-1", SetNoteReminderDto("2026-07-20T08:00:00Z")),
            ),
            PendingOp.ClearReminder("local-1"),
        )

        assertTrue(ops.single() is PendingOp.Create) // the set is dropped, no clear is queued
    }

    // ---- visibleNotes ----

    @Test
    fun `views partition on the per-user flags`() {
        val all = listOf(
            note("active"),
            note("archived", archived = true),
            note("trashed", trashed = true),
            note("both", archived = true, trashed = true), // trash wins over archive
        )
        assertEquals(listOf("active"), visibleNotes(all, NotesFilter(NotesView.ACTIVE)).map { it.id })
        assertEquals(listOf("archived"), visibleNotes(all, NotesFilter(NotesView.ARCHIVED)).map { it.id })
        assertEquals(
            setOf("trashed", "both"),
            visibleNotes(all, NotesFilter(NotesView.TRASHED)).map { it.id }.toSet(),
        )
    }

    @Test
    fun `list filter is a union and ordering is pinned first then last-updated`() {
        val all = listOf(
            note("old", updatedAtUtc = "2026-07-01T00:00:00Z", listIds = listOf("a")),
            note("new", updatedAtUtc = "2026-07-08T00:00:00Z", listIds = listOf("b")),
            note("pinned", pinned = true, updatedAtUtc = "2026-06-01T00:00:00Z", listIds = listOf("a")),
            note("unfiled", updatedAtUtc = "2026-07-09T00:00:00Z"),
        )
        val filtered = visibleNotes(all, NotesFilter(NotesView.ACTIVE, listIds = setOf("a", "b")))
        assertEquals(listOf("pinned", "new", "old"), filtered.map { it.id })
    }

    // ---- outbox persistence format ----

    @Test
    fun `the pending-op queue survives a JSON round-trip`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val ops: List<PendingOp> = listOf(
            PendingOp.Create("local-1", CreateNoteDto(title = "t", checklistItems = listOf(ChecklistItemDto(text = "i")))),
            PendingOp.Update("n1", UpdateNoteDto(type = NoteTypes.TEXT, body = "b")),
            PendingOp.SetState("n1", NoteStateDto(isTrashed = true)),
            PendingOp.SetLists("n1", listOf("a", "b")),
            PendingOp.SetReminder("n1", SetNoteReminderDto("2026-07-20T08:00:00Z", ReminderRecurrences.DAILY)),
            PendingOp.ClearReminder("n3"),
            PendingOp.Delete("n2"),
        )
        val decoded = json.decodeFromString<List<PendingOp>>(json.encodeToString(ops))
        assertEquals(ops, decoded)
    }
}

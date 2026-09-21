package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.ReminderRecurrences
import org.hyperstarit.keepitapp.data.SetNoteReminderDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.readiedForUpload
import org.hyperstarit.keepitapp.data.offline.settleDueReminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Standalone mode's two jobs that a server would otherwise do: moving reminders on as they come
 * due, and — when a server is finally connected — readying the queue so that nothing this device
 * already showed goes off a second time.
 */
class StandaloneTest {

    private val now = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli()

    private fun reminding(
        id: String,
        at: String,
        recurrence: String = ReminderRecurrences.NONE,
        fired: Boolean = false,
        trashed: Boolean = false,
    ) = NoteDto(
        id = id,
        remindAtUtc = at,
        reminderRecurrence = recurrence,
        reminderFired = fired,
        isTrashed = trashed,
    )

    // ---- settling reminders ----

    @Test
    fun `a due one-time reminder is marked fired and keeps its time`() {
        val settled = settleDueReminders(listOf(reminding("n1", "2026-09-21T08:00:00Z")), now).single()

        assertTrue(settled.reminderFired)
        assertEquals("2026-09-21T08:00:00Z", settled.remindAtUtc)
    }

    @Test
    fun `a due recurring reminder moves to its first occurrence after now, skipping missed ones`() {
        // Due three days ago: one catch-up, then tomorrow's 08:00 — not each missed day in turn.
        val settled = settleDueReminders(
            listOf(reminding("n1", "2026-09-18T08:00:00Z", ReminderRecurrences.DAILY)),
            now,
        ).single()

        assertEquals("2026-09-22T08:00:00Z", settled.remindAtUtc)
        assertFalse(settled.reminderFired)
    }

    @Test
    fun `future, already fired and trashed reminders are left alone`() {
        val notes = listOf(
            reminding("future", "2026-09-22T08:00:00Z"),
            reminding("fired", "2026-09-20T08:00:00Z", fired = true),
            // Like the server: still pending, so it fires once the note is restored.
            reminding("trashed", "2026-09-20T08:00:00Z", ReminderRecurrences.DAILY, trashed = true),
            NoteDto(id = "none"),
        )

        assertEquals(notes, settleDueReminders(notes, now))
    }

    // ---- readying the queue for a server ----

    @Test
    fun `a one-time reminder already past is not uploaded`() {
        val ops = readiedForUpload(
            listOf(
                PendingOp.Create("local-1", CreateNoteDto(title = "t")),
                PendingOp.SetReminder("local-1", SetNoteReminderDto("2026-09-20T08:00:00Z")),
            ),
            now,
        )

        assertTrue(ops.single() is PendingOp.Create)
    }

    @Test
    fun `a recurring reminder already past is uploaded at its next occurrence`() {
        val op = readiedForUpload(
            listOf(PendingOp.SetReminder("local-1", SetNoteReminderDto("2026-09-01T08:00:00Z", ReminderRecurrences.WEEKLY))),
            now,
        ).single() as PendingOp.SetReminder

        assertEquals("2026-09-22T08:00:00Z", op.dto.remindAtUtc)
        assertEquals(ReminderRecurrences.WEEKLY, op.dto.recurrence)
    }

    @Test
    fun `everything else goes up exactly as it was queued`() {
        val ops = listOf(
            PendingOp.Create("local-1", CreateNoteDto(title = "t")),
            PendingOp.SetState("local-1", NoteStateDto(isPinned = true)),
            PendingOp.SetReminder("local-1", SetNoteReminderDto("2026-10-01T08:00:00Z")),
        )

        assertEquals(ops, readiedForUpload(ops, now))
    }
}

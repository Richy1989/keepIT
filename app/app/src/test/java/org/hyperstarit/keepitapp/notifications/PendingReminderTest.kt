package org.hyperstarit.keepitapp.notifications

import org.hyperstarit.keepitapp.data.NoteDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which notes have a reminder still to go off: what the scheduler arms an alarm for, and what makes
 * the notes screen ask for the notification permission when it's off.
 */
class PendingReminderTest {

    private val at = "2030-01-01T08:00:00Z"

    @Test
    fun `a set reminder that hasn't fired is pending`() {
        assertTrue(NoteDto(id = "a", remindAtUtc = at).hasPendingReminder())
    }

    @Test
    fun `no reminder, a fired one, or a trashed note is not`() {
        assertFalse(NoteDto(id = "a").hasPendingReminder())
        assertFalse(NoteDto(id = "a", remindAtUtc = at, reminderFired = true).hasPendingReminder())
        assertFalse(NoteDto(id = "a", remindAtUtc = at, isTrashed = true).hasPendingReminder())
    }
}

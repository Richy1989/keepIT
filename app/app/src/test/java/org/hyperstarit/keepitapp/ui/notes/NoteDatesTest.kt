package org.hyperstarit.keepitapp.ui.notes

import org.hyperstarit.keepitapp.data.ReminderRecurrences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The card's dates say what the web's say (`cardDate.test.ts`). */
class NoteDatesTest {
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun `the card date is Today, Yesterday, or the date`() {
        assertEquals("Today", cardDateLabel(today, today))
        assertEquals("Yesterday", cardDateLabel(today.minusDays(1), today))
        assertEquals("Yesterday", cardDateLabel(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)))
        assertEquals("Nov 17", cardDateLabel(LocalDate.of(2026, 11, 17), today))
        assertEquals("Nov 17, 2025", cardDateLabel(LocalDate.of(2025, 11, 17), today))
    }

    @Test
    fun `the editor says when the note was last edited`() {
        assertEquals("Edited today", editedLabel(today, today))
        assertEquals("Edited yesterday", editedLabel(today.minusDays(1), today))
        assertEquals("Edited Oct 22", editedLabel(LocalDate.of(2026, 10, 22), today))
        assertEquals("Edited Oct 22, 2025", editedLabel(LocalDate.of(2025, 10, 22), today))
    }

    @Test
    fun `the reminder chip names the repeat after the time`() {
        val at = LocalDateTime.of(2026, 11, 29, 9, 0)
        assertEquals("Sun, Nov 29, 09:00 · Monthly", reminderChipLabel(at, ReminderRecurrences.MONTHLY, today))
        assertEquals("Sun, Nov 29, 09:00", reminderChipLabel(at, ReminderRecurrences.NONE, today))
        assertEquals("Tomorrow, 08:00", reminderChipLabel(LocalDateTime.of(2026, 11, 20, 8, 0), null, today))
    }
}

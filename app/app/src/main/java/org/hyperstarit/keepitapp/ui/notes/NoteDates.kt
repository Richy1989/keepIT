package org.hyperstarit.keepitapp.ui.notes

import org.hyperstarit.keepitapp.data.ReminderRecurrences
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The dates a note shows, worded as the web words them (`features/notes/cardDate.ts` and
 * `reminderTime.ts`). Free of Android types, for the JVM tests; [today] is a parameter for them too.
 */

/** What each repeat is called, in the picker and on the chip. */
internal val RECURRENCE_LABELS = listOf(
    ReminderRecurrences.NONE to "Does not repeat",
    ReminderRecurrences.DAILY to "Daily",
    ReminderRecurrences.WEEKLY to "Weekly",
    ReminderRecurrences.MONTHLY to "Monthly",
    ReminderRecurrences.YEARLY to "Yearly",
)

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** A reminder's local time: "Today, 14:00" / "Tomorrow, 08:00" / "Mon, Jul 21, 09:00", the year only outside this one. */
fun reminderLabel(at: LocalDateTime, today: LocalDate = LocalDate.now()): String {
    val day = when (at.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> DateTimeFormatter.ofPattern(
            if (at.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy",
        ).format(at.toLocalDate())
    }
    return "$day, ${TIME.format(at.toLocalTime())}"
}

/** The card chip's text: the time, then how it repeats ("Sun, Nov 29, 09:00 · Monthly"). */
fun reminderChipLabel(at: LocalDateTime, recurrence: String?, today: LocalDate = LocalDate.now()): String {
    val repeat = RECURRENCE_LABELS.firstOrNull { it.first == recurrence && it.first != ReminderRecurrences.NONE }?.second
    val time = reminderLabel(at, today)
    return if (repeat == null) time else "$time · $repeat"
}

/**
 * The date at the bottom of a card, which is when the note was created: "Today", "Yesterday",
 * then month and day ("Nov 17"), with the year only outside this one.
 */
fun cardDateLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> DateTimeFormatter.ofPattern(if (date.year == today.year) "MMM d" else "MMM d, yyyy").format(date)
}

/**
 * The editor's line under the title, from when the note last changed: "Edited today", "Edited
 * yesterday", then "Edited Nov 17", with the year only outside this one.
 */
fun editedLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Edited today"
    today.minusDays(1) -> "Edited yesterday"
    else -> "Edited " + DateTimeFormatter.ofPattern(if (date.year == today.year) "MMM d" else "MMM d, yyyy").format(date)
}

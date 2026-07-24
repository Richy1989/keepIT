package org.hyperstarit.keepitapp.ui.notes

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.ReminderRecurrences
import org.hyperstarit.keepitapp.data.SetNoteReminderDto
import org.hyperstarit.keepitapp.data.ensureUtc
import org.hyperstarit.keepitapp.notifications.AppNotifications
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val RECURRENCE_LABELS = listOf(
    ReminderRecurrences.NONE to "Does not repeat",
    ReminderRecurrences.DAILY to "Daily",
    ReminderRecurrences.WEEKLY to "Weekly",
    ReminderRecurrences.MONTHLY to "Monthly",
    ReminderRecurrences.YEARLY to "Yearly",
)

/** Parses a backend reminder timestamp; null when unset or unparsable. */
fun reminderInstant(note: NoteDto): Instant? =
    note.remindAtUtc?.let { runCatching { Instant.parse(ensureUtc(it)) }.getOrNull() }

/** Compact local rendering for the chip — the web `formatReminderTime`: time only today, no year within the year. */
fun formatReminderTime(at: Instant): String {
    val local = at.atZone(ZoneId.systemDefault())
    val now = LocalDateTime.now()
    val pattern = when {
        local.toLocalDate() == now.toLocalDate() -> "HH:mm"
        local.year == now.year -> "MMM d, HH:mm"
        else -> "MMM d yyyy, HH:mm"
    }
    return DateTimeFormatter.ofPattern(pattern).format(local)
}

/**
 * The always-visible reminder indicator on a card/editor: clock + compact local time, plus a
 * repeat glyph when recurring — the Compose twin of the web `ReminderChip`. Turns amber once the
 * time has passed (or a one-time reminder has fired); renders nothing when no reminder is set.
 */
@Composable
fun ReminderChip(note: NoteDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val at = reminderInstant(note) ?: return
    val past = note.reminderFired || at.isBefore(Instant.now())
    val recurring = note.reminderRecurrence != null && note.reminderRecurrence != ReminderRecurrences.NONE
    val tint = if (past) KeepItColors.Accent else KeepItColors.TextFaint

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .border(
                border = BorderStroke(1.dp, if (past) KeepItColors.Accent.copy(alpha = 0.4f) else KeepItColors.BorderStrong),
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(Icons.Filled.Alarm, contentDescription = "Reminder", tint = tint, modifier = Modifier.size(13.dp))
        Text(text = formatReminderTime(at), color = tint, fontSize = 11.sp)
        if (recurring) {
            Icon(Icons.Filled.Repeat, contentDescription = "Repeats", tint = tint, modifier = Modifier.size(11.dp))
        }
    }
}

/**
 * The reminder picker, redesigned as a bottom sheet. Its hero is the *answer*: the resolved fire
 * time in words ("Tomorrow, 08:00") over a live caption ("in 18 hours · repeats weekly") that
 * follows every pick — quick chips, the date/time/repeat cards, everything feeds that one line.
 * Reminders are per-user — read access suffices, so this is never gated on `canEdit`. Saving asks
 * for the notification permission when it's missing (the reminder is stored either way; the
 * Settings screen can grant it later).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(
    note: NoteDto,
    onSave: (SetNoteReminderDto) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Prefill with the existing reminder, else tomorrow morning (like the web).
    val initial = remember {
        reminderInstant(note)?.atZone(zone)?.toLocalDateTime()
            ?: LocalDate.now().plusDays(1).atTime(8, 0)
    }
    var date by remember { mutableStateOf(initial.toLocalDate()) }
    var time by remember { mutableStateOf(initial.toLocalTime()) }
    var recurrence by remember { mutableStateOf(note.reminderRecurrence ?: ReminderRecurrences.NONE) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var recurrenceOpen by remember { mutableStateOf(false) }

    // Quick picks are pinned at open so the chips don't drift while the sheet sits there.
    val presets = remember {
        listOf(
            "Later today" to LocalDateTime.now().plusHours(3).withMinute(0).withSecond(0).withNano(0),
            "Tomorrow 8:00" to LocalDate.now().plusDays(1).atTime(8, 0),
            "Next week" to LocalDate.now().plusWeeks(1).atTime(8, 0),
        )
    }

    // Setting a reminder is the natural moment to ask for the permission its notification needs.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* stored either way; Settings surfaces the state */ }

    fun closeAnimated() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun save() {
        if (!AppNotifications.canPost(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onSave(
            SetNoteReminderDto(
                remindAtUtc = LocalDateTime.of(date, time).atZone(zone).toInstant().toString(),
                recurrence = recurrence,
            ),
        )
        closeAnimated()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = KeepItColors.Surface,
    ) {
        val selected = LocalDateTime.of(date, time)

        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
        ) {
            // Hero: the resolved fire time, in words — every control below feeds this line.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Filled.Alarm,
                    contentDescription = null,
                    tint = KeepItColors.Accent,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(
                        text = heroLabel(selected),
                        color = KeepItColors.Text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = captionLabel(selected, recurrence),
                        color = if (selected.isBefore(LocalDateTime.now())) KeepItColors.Accent else KeepItColors.TextFaint,
                        fontSize = 12.sp,
                    )
                }
            }

            // Quick picks — selecting updates the hero; the button below confirms.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (label, at) ->
                    ChoiceChip(
                        label = label,
                        selected = selected == at,
                        onClick = {
                            date = at.toLocalDate()
                            time = at.toLocalTime()
                        },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ValueCard(
                    icon = Icons.Filled.CalendarMonth,
                    label = "DATE",
                    value = DateTimeFormatter.ofPattern(
                        if (date.year == LocalDate.now().year) "EEE, MMM d" else "EEE, MMM d, yyyy",
                    ).format(date),
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f),
                )
                ValueCard(
                    icon = Icons.Filled.Schedule,
                    label = "TIME",
                    value = DateTimeFormatter.ofPattern("HH:mm").format(time),
                    onClick = { showTimePicker = true },
                    modifier = Modifier.weight(1f),
                )
            }

            Box {
                ValueCard(
                    icon = Icons.Filled.Repeat,
                    label = "REPEATS",
                    value = RECURRENCE_LABELS.first { it.first == recurrence }.second,
                    onClick = { recurrenceOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(expanded = recurrenceOpen, onDismissRequest = { recurrenceOpen = false }) {
                    RECURRENCE_LABELS.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                recurrence = value
                                recurrenceOpen = false
                            },
                        )
                    }
                }
            }

            // Without exact-alarm access, Doze batches the alarm — say so where it matters.
            val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
            if (!alarmManager.canScheduleExactAlarms()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Exact timing is off — reminders can arrive a few minutes late.",
                        color = KeepItColors.TextFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }) {
                        Text("Enable", color = KeepItColors.Accent, fontSize = 12.sp)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.remindAtUtc != null) {
                    TextButton(onClick = {
                        onClear()
                        closeAnimated()
                    }) {
                        Text("Clear reminder", color = KeepItColors.TextMuted, fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = ::save,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KeepItColors.Accent,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        text = if (note.remindAtUtc != null) "Update reminder" else "Set reminder",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        // The picker reports UTC midnight of the picked calendar day.
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Pick time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(timeState.hour, timeState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        )
    }
}

/** The hero line: "Today, 14:00" / "Tomorrow, 08:00" / "Mon, Jul 21, 09:00". */
private fun heroLabel(at: LocalDateTime): String {
    val today = LocalDate.now()
    val day = when (at.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> DateTimeFormatter.ofPattern(
            if (at.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy",
        ).format(at.toLocalDate())
    }
    return "$day, ${DateTimeFormatter.ofPattern("HH:mm").format(at.toLocalTime())}"
}

/** The live caption under the hero: how far away it is, plus the repeat cadence when set. */
private fun captionLabel(at: LocalDateTime, recurrence: String): String {
    val minutes = Duration.between(LocalDateTime.now(), at).toMinutes()
    val distance = when {
        minutes < 0 -> "already passed — fires right away"
        minutes < 1 -> "in under a minute"
        minutes < 60 -> "in $minutes min"
        minutes < 48 * 60 -> (minutes / 60).let { "in $it ${if (it == 1L) "hour" else "hours"}" }
        else -> "in ${minutes / (60 * 24)} days"
    }
    val repeat = RECURRENCE_LABELS.first { it.first == recurrence }.second
    return if (recurrence == ReminderRecurrences.NONE) distance else "$distance · repeats ${repeat.lowercase()}"
}

/** A quick-pick pill: quiet outline at rest, amber fill + text when it matches the selection. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) KeepItColors.Accent.copy(alpha = 0.15f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) KeepItColors.Accent else KeepItColors.BorderStrong),
    ) {
        Text(
            text = label,
            color = if (selected) KeepItColors.Accent else KeepItColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** One tappable setting card: eyebrow label over the current value, on the elevated surface. */
@Composable
private fun ValueCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = KeepItColors.Elevated,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = KeepItColors.TextFaint, modifier = Modifier.size(18.dp))
            Column {
                Text(
                    text = label,
                    color = KeepItColors.TextFaint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Text(text = value, color = KeepItColors.Text, fontSize = 14.sp)
            }
        }
    }
}

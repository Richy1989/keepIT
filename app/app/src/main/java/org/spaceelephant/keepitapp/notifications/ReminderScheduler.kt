package org.spaceelephant.keepitapp.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.spaceelephant.keepitapp.data.NoteDto
import org.spaceelephant.keepitapp.data.NoteTypes
import org.spaceelephant.keepitapp.data.ReminderRecurrences
import org.spaceelephant.keepitapp.data.ensureUtc
import org.spaceelephant.keepitapp.ui.markdown.stripMarkdown
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Fires note reminders as **native notifications while the app is closed** — the client-side twin
 * of the backend's `ReminderDispatcherService`. SignalR only lives while the process runs, so
 * relying on the server push would miss every reminder in between; instead the pending reminders
 * from the offline note cache are mirrored into a small SharedPreferences snapshot and the next due
 * one gets an [AlarmManager] alarm. The receiver (alarm or boot) works purely off that snapshot —
 * no repository, network, or async loading in a receiver's 10-second budget.
 *
 * Alarms must fire with the screen locked and the device dozing: with the `SCHEDULE_EXACT_ALARM`
 * special access (surfaced in the in-app Settings screen) they're exact-and-allow-while-idle;
 * without it the fallback is `setAndAllowWhileIdle` — inexact (Doze batches it, typically up to
 * ~15 minutes late) but still guaranteed to wake the app. Never `setWindow`, which Doze parks
 * until the next maintenance window.
 *
 * Duplicate suppression is two-layered: a posted-keys set stops the *local* path re-posting an
 * occurrence, and the shared `note-<id>` tag (see [AppNotifications.postReminder]) folds the
 * server's own `ReminderNotification` for the same note into the already-shown entry.
 *
 * Snoozes are purely local: a snoozed entry lives in its own list (so cache rebuilds don't wipe
 * it), fires once, and is gone — the server-side reminder row is untouched.
 */
class ReminderScheduler(private val context: Context) {

    /** One pending occurrence, denormalized so the receiver needs nothing but this snapshot. */
    @Serializable
    data class PendingReminder(
        val noteId: String,
        val title: String? = null,
        val atUtcMs: Long,
        val recurrence: String = ReminderRecurrences.NONE,
        /** A plain-text content excerpt for the notification's big-text body. */
        val preview: String = "",
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * Rebuilds the snapshot from the note cache (call on every cache change), then delivers due
     * occurrences and re-arms the alarm. Fired one-time reminders and trashed notes never nag.
     */
    fun syncFrom(notes: List<NoteDto>) {
        val pending = notes.mapNotNull { n ->
            val at = n.remindAtUtc ?: return@mapNotNull null
            if (n.reminderFired || n.isTrashed) return@mapNotNull null
            val ms = runCatching { Instant.parse(ensureUtc(at)).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            PendingReminder(
                noteId = n.id,
                title = n.title,
                atUtcMs = ms,
                recurrence = n.reminderRecurrence ?: ReminderRecurrences.NONE,
                preview = previewOf(n),
            )
        }
        synchronized(this) {
            saveList(KEY_PENDING, pending)
            deliverAndArmLocked()
        }
    }

    /** Alarm / boot / update entry point: post whatever is due and arm the next alarm. */
    fun deliverDue() {
        synchronized(this) { deliverAndArmLocked() }
    }

    /** Re-queues one reminder locally (the notification's snooze action), replacing a prior snooze. */
    fun snooze(noteId: String, title: String?, preview: String, delayMs: Long = SNOOZE_MS) {
        synchronized(this) {
            val snoozed = loadList(KEY_SNOOZED).filterNot { it.noteId == noteId } +
                PendingReminder(noteId, title, System.currentTimeMillis() + delayMs, preview = preview)
            saveList(KEY_SNOOZED, snoozed)
            deliverAndArmLocked()
        }
        AppNotifications.cancelReminder(context, noteId)
    }

    /** Sign-out: no alarms, no snapshot, no tray entries left behind. */
    fun clear() {
        synchronized(this) {
            prefs.edit().clear().apply()
            alarmManager.cancel(alarmIntent())
        }
        AppNotifications.cancelAll(context)
    }

    // ---- core (callers hold the monitor) ----

    private fun deliverAndArmLocked() {
        val now = System.currentTimeMillis()
        val posted = postedKeys()

        // Cache-mirrored reminders: recurring ones advance locally past every missed occurrence
        // (one catch-up notification, like the server); the next sync overwrites with server truth.
        val remainingPending = mutableListOf<PendingReminder>()
        for (entry in loadList(KEY_PENDING)) {
            if (entry.atUtcMs > now) {
                remainingPending.add(entry)
                continue
            }
            postOnce(entry, posted)
            if (entry.recurrence != ReminderRecurrences.NONE) {
                var next = entry.atUtcMs
                while (next <= now) next = advance(next, entry.recurrence)
                remainingPending.add(entry.copy(atUtcMs = next))
            }
        }

        // Snoozes: always one-shot, owned purely by this device.
        val remainingSnoozed = mutableListOf<PendingReminder>()
        for (entry in loadList(KEY_SNOOZED)) {
            if (entry.atUtcMs > now) remainingSnoozed.add(entry) else postOnce(entry, posted)
        }

        savePostedKeys(posted)
        saveList(KEY_PENDING, remainingPending)
        saveList(KEY_SNOOZED, remainingSnoozed)
        armLocked((remainingPending + remainingSnoozed).minOfOrNull { it.atUtcMs })
    }

    private fun postOnce(entry: PendingReminder, posted: MutableSet<String>) {
        val key = "${entry.noteId}|${entry.atUtcMs}"
        if (key in posted) return
        AppNotifications.postReminder(context, entry.noteId, entry.title, entry.preview, entry.atUtcMs)
        posted.add(key)
    }

    /** Arms one alarm for the earliest pending occurrence — always allow-while-idle, exact when permitted. */
    private fun armLocked(nextAtMs: Long?) {
        val intent = alarmIntent()
        if (nextAtMs == null) {
            alarmManager.cancel(intent)
            return
        }
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAtMs, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAtMs, intent)
        }
    }

    private fun alarmIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // ---- persistence ----

    private fun loadList(key: String): List<PendingReminder> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { SnapshotJson.decodeFromString<List<PendingReminder>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun saveList(key: String, entries: List<PendingReminder>) {
        prefs.edit().putString(key, SnapshotJson.encodeToString(entries)).apply()
    }

    private fun postedKeys(): MutableSet<String> =
        (prefs.getStringSet(KEY_POSTED, emptySet()) ?: emptySet()).toMutableSet()

    private fun savePostedKeys(keys: Set<String>) {
        // Keys embed the occurrence time, so dropping the oldest ones is safe once they're history.
        val capped = if (keys.size <= POSTED_KEYS_CAP) keys
        else keys.sortedBy { it.substringAfterLast('|').toLongOrNull() ?: 0L }.takeLast(POSTED_KEYS_CAP).toSet()
        prefs.edit().putStringSet(KEY_POSTED, capped).apply()
    }

    companion object {
        private const val PREFS_NAME = "keepit_reminders"
        private const val KEY_PENDING = "pending_json"
        private const val KEY_SNOOZED = "snoozed_json"
        private const val KEY_POSTED = "posted_keys"
        private const val POSTED_KEYS_CAP = 100
        private const val SNOOZE_MS = 60 * 60_000L
        private const val PREVIEW_CHARS = 160
        private const val PREVIEW_ITEMS = 3
        private val SnapshotJson = Json { ignoreUnknownKeys = true }

        /** The next occurrence after [fromMs] — same UTC arithmetic as the server's `Advance`. */
        fun advance(fromMs: Long, recurrence: String): Long {
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

        /** A plain-text excerpt for the notification body: open checklist items, or the stripped body. */
        fun previewOf(n: NoteDto): String =
            if (n.type == NoteTypes.CHECKLIST) {
                n.checklistItems.asSequence()
                    .sortedBy { it.order }
                    .filter { !it.isChecked && it.text.isNotBlank() }
                    .take(PREVIEW_ITEMS)
                    .joinToString("\n") { "• ${it.text}" }
            } else {
                stripMarkdown(n.body ?: "").replace('\n', ' ').trim().take(PREVIEW_CHARS)
            }
    }
}

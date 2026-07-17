package org.spaceelephant.keepitapp.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import org.spaceelephant.keepitapp.MainActivity
import org.spaceelephant.keepitapp.R

/**
 * The app's native notification surface — channels, permission checks, and posting. **Every**
 * user-facing notification goes through here (reminders fired by the local alarm, and server-created
 * inbox items like share invites); there is deliberately no in-app notification UI on Android, the
 * system tray is it. Channel setup is idempotent and runs at app start, so the user can manage
 * per-channel behavior in system settings from day one.
 */
object AppNotifications {

    /** Note reminders — high importance so they heads-up like an alarm clock app's would. */
    const val CHANNEL_REMINDERS = "reminders"

    /** Everything else from the server inbox (share invites, system messages). */
    const val CHANNEL_GENERAL = "general"

    /** One shared notification id; the per-note/per-item tag is what distinguishes entries. */
    private const val NOTIFICATION_ID = 1

    /** keepIT accent (the web default) — tints the small icon and action text. */
    private const val ACCENT = 0xFFFBBF24.toInt()

    /** Registers both channels (no-op when they already exist). */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminders_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_reminders_description)
                // A reminder is the user's own note — show it in full on the lock screen.
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GENERAL,
                context.getString(R.string.channel_general_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_general_description) },
        )
    }

    /** Whether the app may post at all (the POST_NOTIFICATIONS runtime permission gate). */
    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Posts a reminder for a note: note title as the headline, a content [preview] expandable via
     * big-text, the due time as the timestamp, and a local snooze action. Tagged `note-<noteId>` so
     * the locally-fired alarm and the server's catch-up `ReminderNotification` for the same note
     * replace each other instead of stacking; [alertOnce] keeps the server echo from buzzing twice.
     */
    fun postReminder(
        context: Context,
        noteId: String,
        title: String?,
        preview: String = "",
        whenMs: Long = System.currentTimeMillis(),
        alertOnce: Boolean = false,
    ) {
        val text = preview.ifBlank { context.getString(R.string.reminder_notification_text) }
        val notification = builder(context, CHANNEL_REMINDERS)
            .setContentTitle(title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.untitled_note))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(context.getString(R.string.channel_reminders_name))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(whenMs)
            .setShowWhen(true)
            .setContentIntent(openNoteIntent(context, noteId))
            .setOnlyAlertOnce(alertOnce)
            .addAction(0, context.getString(R.string.snooze_one_hour), snoozeIntent(context, noteId, title, preview))
            .build()
        notify(context, reminderTag(noteId), notification)
    }

    /**
     * Posts a non-reminder server notification (share invite / system message). Tapping it lands on
     * the in-app inbox, where an invite can actually be answered.
     */
    fun postGeneral(context: Context, notificationId: String, text: String) {
        val notification = builder(context, CHANNEL_GENERAL)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(openInboxIntent(context))
            .setOnlyAlertOnce(true)
            .build()
        notify(context, "inbox-$notificationId", notification)
    }

    /** Removes one inbox item's tray copy (answered or dismissed in-app). */
    fun cancelGeneral(context: Context, notificationId: String) =
        NotificationManagerCompat.from(context).cancel("inbox-$notificationId", NOTIFICATION_ID)

    /** Removes one note's reminder from the tray (snooze pressed — the snoozed copy comes back later). */
    fun cancelReminder(context: Context, noteId: String) =
        NotificationManagerCompat.from(context).cancel(reminderTag(noteId), NOTIFICATION_ID)

    /** Removes every posted notification (sign-out). */
    fun cancelAll(context: Context) = NotificationManagerCompat.from(context).cancelAll()

    private fun reminderTag(noteId: String) = "note-$noteId"

    /** The shared look: small bell, accent tint, visible on the lock screen, dismissed on tap. */
    private fun builder(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

    private fun notify(context: Context, tag: String, notification: Notification) {
        if (!canPost(context)) return
        // The permission can be revoked between the check and the post — never crash for a toast.
        runCatching { NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification) }
    }

    /** Tapping a reminder lands in the note's editor, like a widget row tap. */
    private fun openNoteIntent(context: Context, noteId: String): PendingIntent =
        activityIntent(
            context,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                // Distinct data URI per note so PendingIntents don't collapse into one (see widget).
                data = "keepit://note/$noteId".toUri()
                putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
            },
        )

    private fun openInboxIntent(context: Context): PendingIntent =
        activityIntent(
            context,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = "keepit://notifications".toUri()
                putExtra(MainActivity.EXTRA_INBOX, true)
            },
        )

    /** "Snooze" re-queues the reminder locally through the scheduler — no app launch, no network. */
    private fun snoozeIntent(context: Context, noteId: String, title: String?, preview: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            noteId.hashCode(),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ReminderAlarmReceiver.ACTION_SNOOZE
                data = "keepit://snooze/$noteId".toUri()
                putExtra(ReminderAlarmReceiver.EXTRA_NOTE_ID, noteId)
                putExtra(ReminderAlarmReceiver.EXTRA_TITLE, title)
                putExtra(ReminderAlarmReceiver.EXTRA_PREVIEW, preview)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun activityIntent(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

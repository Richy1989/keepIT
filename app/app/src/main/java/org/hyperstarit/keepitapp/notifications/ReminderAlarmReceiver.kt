package org.hyperstarit.keepitapp.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by the [ReminderScheduler]'s alarm (post whatever is due, arm the next one) and by the
 * notification's snooze action (re-queue locally). Works entirely off the scheduler's persisted
 * snapshot — fast enough for a receiver, and correct even when this broadcast is the only reason
 * the process exists.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = ReminderScheduler(context.applicationContext)
        when (intent.action) {
            ACTION_SNOOZE -> {
                val noteId = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
                scheduler.snooze(
                    noteId = noteId,
                    title = intent.getStringExtra(EXTRA_TITLE),
                    preview = intent.getStringExtra(EXTRA_PREVIEW) ?: "",
                )
            }

            else -> scheduler.deliverDue()
        }
    }

    companion object {
        const val ACTION_SNOOZE = "org.hyperstarit.keepitapp.SNOOZE_REMINDER"
        const val EXTRA_NOTE_ID = "keepit.reminder.noteId"
        const val EXTRA_TITLE = "keepit.reminder.title"
        const val EXTRA_PREVIEW = "keepit.reminder.preview"
    }
}

package org.spaceelephant.keepitapp.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the reminder alarm from the persisted snapshot whenever the system silently dropped or
 * downgraded it (delivering anything that came due in between):
 * - **boot** — AlarmManager alarms don't survive a reboot;
 * - **app update** — pending alarms are cleared when the package is replaced;
 * - **exact-alarm access granted** — the standing alarm was armed inexactly and should be re-set
 *   exactly (revocation needs no handling: the system stops the app and boot-style re-arming
 *   applies on next launch).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> ReminderScheduler(context.applicationContext).deliverDue()
        }
    }
}

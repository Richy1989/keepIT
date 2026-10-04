package org.hyperstarit.keepitapp.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hyperstarit.keepitapp.notifications.AppNotifications
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * Where the two permissions reminders depend on stand: POST_NOTIFICATIONS (whether anything shows
 * at all) and SCHEDULE_EXACT_ALARM (whether a reminder fires on the minute or when the system
 * next batches alarms).
 */
data class NotificationAccess(val notifications: Boolean, val exactAlarms: Boolean) {
    val allGood: Boolean get() = notifications && exactAlarms
}

/**
 * The current [NotificationAccess], read live from the system and re-read on every resume: both
 * permissions change in the system's settings, not in this process, so the screen is right the
 * moment the user comes back. [bump] re-reads it too, for a dialog that returns without a resume.
 */
@Composable
fun rememberNotificationAccess(bump: Int = 0): NotificationAccess {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    return remember(tick, bump) { readNotificationAccess(context) }
}

private fun readNotificationAccess(context: Context) = NotificationAccess(
    notifications = AppNotifications.canPost(context),
    exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
)

/**
 * Notification permissions: whether keepIT may post at all, and whether reminders may fire on the
 * minute — each with its state and, while off, the way to turn it on — then the system's own page
 * for per-category sound and importance. Reminders and the server inbox surface as native
 * notifications, so these two decide how well both work.
 */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // A denied request without a system dialog means "ask in settings instead" — re-read either way.
    var asked by remember { mutableIntStateOf(0) }
    val access = rememberNotificationAccess(bump = asked)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { asked++ }

    SettingsPage(title = "Notifications", onBack = onBack) {
        SettingsGroup {
            PermissionRow(
                icon = Icons.Outlined.Notifications,
                title = "Allow notifications",
                description = "Reminders and shared-note updates appear as system notifications.",
                granted = access.notifications,
                grantedLabel = "Allowed",
                deniedLabel = "Blocked",
                deniedIsError = true,
                actionLabel = "Allow",
                onAction = {
                    // First ask via the runtime dialog; once permanently denied Android skips the
                    // dialog, and the categories row below is the way to the system's switch.
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            SettingsDivider()
            PermissionRow(
                icon = Icons.Outlined.Alarm,
                title = "Exact reminder timing",
                description = "With alarms & reminders access, reminders fire on the minute, even " +
                    "when the screen is locked. Without it the system batches them, so they can " +
                    "arrive several minutes late.",
                granted = access.exactAlarms,
                grantedLabel = "Exact",
                deniedLabel = "Approximate",
                actionLabel = "Grant access",
                onAction = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            )
        }

        // Per-channel tuning (sound, vibration, importance) lives in the system UI.
        SettingsGroup {
            SettingsRow(
                icon = Icons.Outlined.Tune,
                title = "Notification categories",
                summary = "Sound and importance for Reminders and General, in system settings",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = "Opens system settings",
                        tint = KeepItColors.TextFaint,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
        }
    }
}

/**
 * One permission: name, live state and explanation, and — only while it's off — the action that
 * turns it on. [deniedIsError] marks the off state in the error colour — a blocked notification
 * permission, since nothing shows at all; approximate timing is a lesser loss and stays muted.
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    grantedLabel: String,
    deniedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
    deniedIsError: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        SettingsIcon(icon, KeepItColors.AccentInk)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = KeepItColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    text = if (granted) grantedLabel else deniedLabel,
                    color = when {
                        granted -> KeepItColors.AccentInk
                        deniedIsError -> MaterialTheme.colorScheme.error
                        else -> KeepItColors.TextMuted
                    },
                    fontSize = 13.sp,
                )
            }
            Text(
                text = description,
                color = KeepItColors.TextFaint,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (!granted) {
                OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                    Text(actionLabel, fontSize = 13.sp)
                }
            }
        }
    }
}

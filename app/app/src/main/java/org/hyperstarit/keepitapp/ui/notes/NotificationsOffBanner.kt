package org.hyperstarit.keepitapp.ui.notes

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.notifications.AppNotifications
import org.hyperstarit.keepitapp.notifications.hasPendingReminder
import org.hyperstarit.keepitapp.ui.settings.rememberNotificationAccess
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * Says so when a reminder is set but keepIT may not post notifications — the one case where the
 * permission being off costs something.
 *
 * Android asks for that permission only when an app requests it, and the app used to request it only
 * when a reminder was set *on the phone* (or from Settings → Notifications). Someone who sets their
 * reminders on the web never saw the question: the alarm went off on time and nothing appeared. So
 * the question comes here instead, while a reminder is pending, and only then. "Not now" hides it
 * until the app is next opened.
 *
 * [notes] is every note in the cache, whatever the grid is showing.
 */
@Composable
fun NotificationsOffBanner(notes: List<NoteDto>) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var asked by remember { mutableIntStateOf(0) }
    val access = rememberNotificationAccess(bump = asked)

    val openSystemSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asked++
        // Once the permission is denied for good, Android answers without showing anything; and
        // with it granted, notifications can still be switched off in the system's page. Either
        // way, that page is where the switch is.
        val dialogStillOffered = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        if (!AppNotifications.canPost(context) && (granted || !dialogStillOffered)) openSystemSettings()
    }

    if (dismissed || access.notifications || notes.none { it.hasPendingReminder() }) return

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(KeepItColors.Surface)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
    ) {
        // Level with the heading, not centred on the whole block (buttons included).
        Icon(
            Icons.Outlined.NotificationsOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 1.dp, end = 10.dp).size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("Reminders can't notify you", color = KeepItColors.Text, fontSize = 13.sp)
            Text(
                "Notifications are off for keepIT, so a reminder that comes due won't show.",
                color = KeepItColors.TextMuted,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { dismissed = true }) { Text("Not now", color = KeepItColors.TextMuted) }
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Allow", color = KeepItColors.AccentInk)
                }
            }
        }
    }
}

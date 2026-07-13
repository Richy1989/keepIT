package org.spaceelephant.keepitapp.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.spaceelephant.keepitapp.notifications.AppNotifications
import org.spaceelephant.keepitapp.ui.theme.KeepItColors

/**
 * App settings, currently one concern: **notification permission management**. Reminders and the
 * server inbox surface exclusively as native notifications, and two grants decide how well that
 * works — POST_NOTIFICATIONS (may we post at all) and SCHEDULE_EXACT_ALARM (do reminders fire on
 * the minute or in a ~10-minute window). Both are read live from the system and re-read when the
 * user returns from the system settings screens this page links to, so the rows always tell the
 * truth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }

    // Bumped on every resume: permission state changes in system settings, not in this process.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationsAllowed = remember(refresh) { AppNotifications.canPost(context) }
    val exactAlarmsAllowed = remember(refresh) { alarmManager.canScheduleExactAlarms() }

    // A denied request without a system dialog means "ask in settings instead" — refresh either way.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }

    fun openAppNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = KeepItColors.Text,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = KeepItColors.TextMuted,
                        )
                    }
                },
                title = { Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionLabel("NOTIFICATIONS")

            PermissionRow(
                title = "Allow notifications",
                description = "Reminders and shared-note updates appear as system notifications.",
                granted = notificationsAllowed,
                grantedLabel = "Allowed",
                deniedLabel = "Blocked",
                actionLabel = "Allow",
                onAction = {
                    // First ask via the runtime dialog; once permanently denied Android skips the
                    // dialog, so the settings deep link below is the fallback path.
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )

            HorizontalDivider(color = KeepItColors.BorderSubtle)

            PermissionRow(
                title = "Exact reminder timing",
                description = "With alarms & reminders access, reminders fire on the minute — even " +
                    "when the screen is locked. Without it the system batches them, so they can " +
                    "arrive several minutes late.",
                granted = exactAlarmsAllowed,
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

            HorizontalDivider(color = KeepItColors.BorderSubtle)

            // Per-channel tuning (sound, vibration, importance) lives in the system UI.
            Column(modifier = Modifier.padding(vertical = 14.dp)) {
                Text("Notification categories", color = KeepItColors.Text, fontSize = 15.sp)
                Text(
                    text = "Adjust sound and importance per category (Reminders, General) in system settings.",
                    color = KeepItColors.TextFaint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                OutlinedButton(
                    onClick = ::openAppNotificationSettings,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Open system settings", fontSize = 13.sp)
                }
            }
        }
    }
}

/** One permission's row: name + explanation, live state, and the action that flips it. */
@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    grantedLabel: String,
    deniedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, color = KeepItColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                text = if (granted) grantedLabel else deniedLabel,
                color = if (granted) KeepItColors.Accent else KeepItColors.TextFaint,
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

@Composable
private fun SectionLabel(text: String) {
    Spacer(modifier = Modifier.size(8.dp))
    Text(
        text = text,
        color = KeepItColors.TextFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

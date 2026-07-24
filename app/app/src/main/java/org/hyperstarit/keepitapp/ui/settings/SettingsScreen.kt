package org.hyperstarit.keepitapp.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.apiErrorMessage
import org.hyperstarit.keepitapp.notifications.AppNotifications
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * App settings: **notification permission management** (reminders and the server inbox surface as
 * native notifications, so POST_NOTIFICATIONS and SCHEDULE_EXACT_ALARM decide how well that works —
 * both read live from the system and re-read on resume, so the rows always tell the truth) and the
 * **account section** (change password, mirroring the web Settings page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
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

            HorizontalDivider(color = KeepItColors.BorderSubtle)

            SectionLabel("ACCOUNT")
            ChangePasswordSection(container)

            HorizontalDivider(color = KeepItColors.BorderSubtle)

            SectionLabel("ABOUT")
            AboutSection(container)
        }
    }
}

/** App + server versions, so a self-hoster can spot an outdated APK or container at a glance. */
@Composable
private fun AboutSection(container: AppContainer) {
    val context = LocalContext.current
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
    var serverVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        serverVersion = runCatching { container.apiClient.api.meta().version }.getOrNull()
    }

    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        VersionRow("App version", appVersion)
        VersionRow("Server version", serverVersion ?: "unavailable (offline?)")
    }
}

/** One label/value line in the About section. */
@Composable
private fun VersionRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = KeepItColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = KeepItColors.TextMuted, fontSize = 14.sp)
    }
}

/**
 * Change-password form, the phone twin of the web's: current/new/confirm with inline validation.
 * A successful change signs out every other device; this one stays in (the server returns fresh
 * tokens, stored by the session repository).
 */
@Composable
private fun ChangePasswordSection(container: AppContainer) {
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        error = null
        if (next.length < 8) {
            error = "New password must be at least 8 characters."
            return
        }
        if (next != confirm) {
            error = "New passwords do not match."
            return
        }
        busy = true
        scope.launch {
            container.session.changePassword(current, next)
                .onSuccess {
                    done = true
                    current = ""; next = ""; confirm = ""
                }
                .onFailure { error = apiErrorMessage(it, "Could not change the password.") }
            busy = false
        }
    }

    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        Text("Change password", color = KeepItColors.Text, fontSize = 15.sp)
        Text(
            text = "Updating your password signs you out of your other devices.",
            color = KeepItColors.TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        if (done) {
            Text(
                text = "Password changed. Your other devices have been signed out.",
                color = KeepItColors.Accent,
                fontSize = 13.sp,
            )
            OutlinedButton(onClick = { done = false }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Change again", fontSize = 13.sp)
            }
            return@Column
        }

        PasswordField(value = current, onChange = { current = it }, label = "Current password")
        PasswordField(value = next, onChange = { next = it }, label = "New password")
        PasswordField(value = confirm, onChange = { confirm = it }, label = "Confirm new password")

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Button(
            onClick = ::submit,
            enabled = !busy && current.isNotBlank() && next.isNotBlank() && confirm.isNotBlank(),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Update password")
            }
        }
    }
}

/** A labelled, masked password input. */
@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
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

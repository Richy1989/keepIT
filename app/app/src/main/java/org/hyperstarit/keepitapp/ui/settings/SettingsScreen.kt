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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.SessionState
import org.hyperstarit.keepitapp.data.ThemePref
import org.hyperstarit.keepitapp.data.apiErrorMessage
import org.hyperstarit.keepitapp.notifications.AppNotifications
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.accentButtonColors

/**
 * App settings: the **theme** (this device's own, see [ThemeSection]), **notification permission
 * management** (reminders and the server inbox surface as native notifications, so
 * POST_NOTIFICATIONS and SCHEDULE_EXACT_ALARM decide how well that works — both read live from the
 * system and re-read on resume, so the rows always tell the truth) and the **account section**
 * (display name and change password, mirroring the web Settings page).
 *
 * In standalone mode there is no account: that section becomes the device's own — connect a server
 * ([onConnectServer]) to upload the notes, or erase them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onConnectServer: () -> Unit) {
    val context = LocalContext.current
    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    val standalone by container.appMode.standalone.collectAsState()

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
            SectionLabel("APPEARANCE")
            ThemeSection(container)

            HorizontalDivider(color = KeepItColors.BorderSubtle)

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

            if (standalone) {
                SectionLabel("THIS DEVICE")
                StandaloneSection(container, onConnectServer)
            } else {
                SectionLabel("ACCOUNT")
                DisplayNameSection(container)
                HorizontalDivider(color = KeepItColors.BorderSubtle)
                ChangePasswordSection(container)
            }

            HorizontalDivider(color = KeepItColors.BorderSubtle)

            SectionLabel("YOUR DATA")
            DataSection(container)

            HorizontalDivider(color = KeepItColors.BorderSubtle)

            SectionLabel("ABOUT")
            AboutSection(container, showServer = !standalone)
        }
    }
}

/**
 * The theme picker: the web's four choices, labelled as its Appearance menu labels them. The
 * choice is this phone's — it isn't sent to the server, so the web app keeps its own and neither
 * follows the other (see [org.hyperstarit.keepitapp.data.Appearance]). It applies at once, here
 * and on the home-screen widget.
 */
@Composable
private fun ThemeSection(container: AppContainer) {
    val theme by container.appearance.theme.collectAsState()
    val options = ThemePref.entries

    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        Text("Theme", color = KeepItColors.Text, fontSize = 15.sp)
        Text(
            text = "Auto follows your phone between light and dark. Set on this phone only — the " +
                "web app keeps its own.",
            color = KeepItColors.TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, pref ->
                SegmentedButton(
                    selected = theme == pref,
                    onClick = { container.appearance.setTheme(pref) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(pref.label, fontSize = 13.sp) },
                )
            }
        }
    }
}

/**
 * Standalone mode's stand-in for the account section: where the notes live, the way to a server,
 * and the way out. Erasing goes through the sign-out path — in standalone mode that is what it
 * means — behind a confirmation, since there is no server copy to come back to.
 */
@Composable
private fun StandaloneSection(container: AppContainer, onConnectServer: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notes by container.notesRepo.allNotes.collectAsState()
    var confirmErase by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        Text("Standalone mode", color = KeepItColors.Text, fontSize = 15.sp)
        Text(
            text = "Your notes are stored only on this phone. Save a copy under Your data below, " +
                "or connect a server to upload them into an account and sync them with your other devices.",
            color = KeepItColors.TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Button(onClick = onConnectServer, colors = accentButtonColors()) {
            Text("Connect to a server")
        }

        Text(
            "Erase this device",
            color = KeepItColors.Text,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = "Deletes every note, list and image on this phone and returns to the sign-in screen.",
            color = KeepItColors.TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedButton(onClick = { confirmErase = true }, enabled = !erasing) {
            Text("Erase notes", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmErase) {
        val what = when (val count = notes.size) {
            0 -> "Everything in keepIT"
            1 -> "Your note, with its lists, reminders and images,"
            else -> "All $count notes, with their lists, reminders and images,"
        }
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            containerColor = KeepItColors.Surface,
            title = { Text("Erase this device?") },
            text = {
                Text(
                    text = "$what will be deleted from this phone. There is no server copy, so this " +
                        "can't be undone — save a copy under Your data first if you might want them back.",
                    color = KeepItColors.TextMuted,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmErase = false
                        erasing = true
                        scope.launch { container.session.logout() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Erase")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) {
                    Text("Cancel", color = KeepItColors.TextMuted)
                }
            },
        )
    }
}

/**
 * App + server versions, so a self-hoster can spot an outdated APK or container at a glance. The
 * server row goes when there is no server ([showServer] false, standalone mode).
 */
@Composable
private fun AboutSection(container: AppContainer, showServer: Boolean) {
    val context = LocalContext.current
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
    var serverVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showServer) {
        if (showServer) serverVersion = runCatching { container.apiClient.api.meta().version }.getOrNull()
    }

    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        VersionRow("App version", appVersion)
        if (showServer) VersionRow("Server version", serverVersion ?: "unavailable (offline?)")
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
 * Display-name form, the phone twin of the web's: edit the name shown in the drawer, or clear it to
 * fall back to the email. Until the field is touched it shows the signed-in user's name — including
 * one just changed on another device, which the realtime `account` push brings into the session.
 * Online only, like the password: a failure (offline included) says so and keeps the edit.
 */
@Composable
private fun DisplayNameSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val session by container.session.state.collectAsState()
    val user = (session as? SessionState.SignedIn)?.user ?: return

    // null while untouched, so the field follows the session's name rather than a stale copy of it.
    var draft by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    val current = user.displayName.orEmpty()
    val value = draft ?: current
    val changed = value.trim() != current

    fun submit() {
        if (busy || !changed) return
        error = null
        busy = true
        scope.launch {
            container.session.updateDisplayName(value)
                .onSuccess {
                    draft = null
                    saved = true
                }
                .onFailure { error = apiErrorMessage(it, "Could not save the display name.") }
            busy = false
        }
    }

    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        Text("Display name", color = KeepItColors.Text, fontSize = 15.sp)
        Text(
            text = "Shown with your account in the menu. Leave it empty to use your email.",
            color = KeepItColors.TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        OutlinedTextField(
            value = value,
            onValueChange = {
                draft = it.take(DISPLAY_NAME_MAX_LENGTH)
                saved = false
            },
            label = { Text("Display name") },
            placeholder = { Text(user.email) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Button(onClick = ::submit, enabled = !busy && changed, colors = accentButtonColors()) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Text("Save name")
                }
            }
            if (saved && !changed) {
                Text(
                    text = "Saved",
                    color = KeepItColors.AccentInk,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** Matches the server's limit on `UpdateProfileRequestDto.DisplayName`. */
private const val DISPLAY_NAME_MAX_LENGTH = 100

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
                color = KeepItColors.AccentInk,
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
            colors = accentButtonColors(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
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
                color = if (granted) KeepItColors.AccentInk else KeepItColors.TextFaint,
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

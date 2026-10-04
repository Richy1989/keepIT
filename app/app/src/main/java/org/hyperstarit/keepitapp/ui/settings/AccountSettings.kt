package org.hyperstarit.keepitapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.SessionState
import org.hyperstarit.keepitapp.data.apiErrorMessage
import org.hyperstarit.keepitapp.ui.auth.UnsyncedSignOutDialog
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.accentButtonColors

/** Matches the server's limit on `UpdateProfileRequestDto.DisplayName`. */
private const val DISPLAY_NAME_MAX_LENGTH = 100

/**
 * The account, mirroring the web Settings page's General and Security sections: the profile
 * (display name, edited in a dialog, and the email it falls back to), the way to change the
 * password, the server this phone talks to, and Sign out — the drawer's, with the same warning
 * when changes are still queued.
 *
 * Server mode only: standalone has no account, and its top-level card opens
 * [DeviceSettingsScreen] instead.
 */
@Composable
fun AccountSettingsScreen(container: AppContainer, onBack: () -> Unit, onChangePassword: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session by container.session.state.collectAsState()
    val pending by container.pendingChanges.collectAsState()
    val user = (session as? SessionState.SignedIn)?.user

    var editingName by remember { mutableStateOf(false) }
    // Set once Sign out is tapped, so a second tap can't start a second sign-out.
    var signingOut by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    fun signOut() {
        if (signingOut) return
        signingOut = true
        scope.launch { container.session.logout() }
    }

    SettingsPage(title = "Account", onBack = onBack) {
        // Null only in the moment the session is changing (a sign-out under way): nothing to show.
        if (user == null) return@SettingsPage
        val name = user.displayName?.takeIf { it.isNotBlank() }

        SettingsGroup(label = "Profile") {
            SettingsRow(
                icon = Icons.Outlined.Badge,
                title = "Display name",
                summary = name ?: "Not set, so your email is shown",
                summaryColor = if (name != null) KeepItColors.TextMuted else KeepItColors.TextFaint,
                onClick = { editingName = true },
            )
            SettingsDivider()
            SettingsRow(icon = Icons.Outlined.Email, title = "Email", summary = user.email)
        }

        SettingsGroup(label = "Security") {
            SettingsRow(
                icon = Icons.Outlined.Lock,
                title = "Change password",
                summary = "Signs you out of your other devices",
                chevron = true,
                onClick = onChangePassword,
            )
        }

        SettingsGroup(label = "Server") {
            SettingsRow(
                icon = Icons.Outlined.Dns,
                title = "Address",
                summary = container.session.serverUrl?.trimEnd('/') ?: "Unknown",
            )
        }

        SettingsGroup {
            val error = MaterialTheme.colorScheme.error
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = "Sign out",
                titleColor = error,
                tint = error,
                onClick = { if (pending > 0) confirmSignOut = true else signOut() },
            )
        }
    }

    if (editingName && user != null) {
        DisplayNameDialog(
            container = container,
            current = user.displayName.orEmpty(),
            email = user.email,
            onDismiss = { editingName = false },
        )
    }

    if (confirmSignOut) {
        UnsyncedSignOutDialog(
            pending = pending,
            onConfirm = {
                confirmSignOut = false
                signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/**
 * Edits the display name, or clears it so the app shows [email] instead. Online only, like the
 * password: a failure (offline included) is shown in the dialog and the edit is kept. On success
 * the session takes the server's answer, so the account card and the drawer follow at once, and
 * the server's `account` push brings the user's other devices along.
 */
@Composable
private fun DisplayNameDialog(container: AppContainer, current: String, email: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (busy) return
        if (value.trim() == current) {
            onDismiss()
            return
        }
        error = null
        busy = true
        scope.launch {
            container.session.updateDisplayName(value)
                .onSuccess { onDismiss() }
                .onFailure { error = apiErrorMessage(it, "Could not save the display name.") }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = KeepItColors.Surface,
        title = { Text("Display name") },
        text = {
            Column {
                Text(
                    text = "Shown with your account. Leave it empty to use your email.",
                    color = KeepItColors.TextMuted,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(DISPLAY_NAME_MAX_LENGTH) },
                    placeholder = { Text(email) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = ::save, enabled = !busy, colors = accentButtonColors()) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel", color = KeepItColors.TextMuted)
            }
        },
    )
}

/**
 * Change-password form, the phone twin of the web's: current/new/confirm with inline validation.
 * A successful change signs out every other device; this one stays in (the server returns fresh
 * tokens, stored by the session repository) and the page says so, with the way back.
 */
@Composable
fun ChangePasswordScreen(container: AppContainer, onBack: () -> Unit) {
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

    SettingsPage(title = "Change password", onBack = onBack) {
        SettingsGroup {
            Column(modifier = SettingsContentPadding) {
                if (done) {
                    Text(
                        text = "Password changed. Your other devices have been signed out; this one " +
                            "stays signed in.",
                        color = KeepItColors.Text,
                        fontSize = 14.sp,
                    )
                    OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Back to account", fontSize = 13.sp)
                    }
                    return@Column
                }

                Text(
                    text = "Updating your password signs you out of your other devices. This one " +
                        "stays signed in.",
                    color = KeepItColors.TextMuted,
                    fontSize = 13.sp,
                )
                PasswordField(value = current, onChange = { current = it }, label = "Current password")
                PasswordField(value = next, onChange = { next = it }, label = "New password")
                PasswordField(value = confirm, onChange = { confirm = it }, label = "Confirm new password")

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Button(
                    onClick = ::submit,
                    enabled = !busy && current.isNotBlank() && next.isNotBlank() && confirm.isNotBlank(),
                    modifier = Modifier.padding(top = 14.dp),
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

package org.hyperstarit.keepitapp.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.SessionState
import org.hyperstarit.keepitapp.data.ThemePref
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * The settings pages' routes, registered in `AppRoot`'s `MainNav`. The top level ([MAIN]) is a short
 * list where every row says where it stands; a row with something to fill in or read through
 * opens a page of its own.
 */
object SettingsRoutes {
    const val MAIN = "settings"
    const val ACCOUNT = "settings/account"
    const val PASSWORD = "settings/account/password"
    const val DEVICE = "settings/device"
    const val NOTIFICATIONS = "settings/notifications"
    const val DATA = "settings/data"
    const val ABOUT = "settings/about"
}

/**
 * Settings, top level: the account card (or, in standalone mode, the device's), then the **theme**
 * — picked in a dialog, being one choice of four — and rows into **notifications**, **your data**
 * and **about**, each summarising its page so most visits end here.
 *
 * The notifications row re-reads both permissions on every resume, like the page behind it, and is
 * marked when either is off: a blocked permission otherwise goes unnoticed until a reminder fails
 * to appear.
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpen: (route: String) -> Unit) {
    val standalone by container.appMode.standalone.collectAsState()
    val session by container.session.state.collectAsState()
    val theme by container.appearance.theme.collectAsState()
    val picture by container.profileImage.file.collectAsState()
    val access = rememberNotificationAccess()
    val appVersion = rememberAppVersion()
    var choosingTheme by remember { mutableStateOf(false) }

    SettingsPage(title = "Settings", onBack = onBack) {
        val user = (session as? SessionState.SignedIn)?.user
        if (standalone) {
            SettingsAccountCard(
                title = "This phone only",
                subtitle = "Standalone: no account, and your notes aren't synced anywhere",
                icon = Icons.Outlined.PhoneAndroid,
                onClick = { onOpen(SettingsRoutes.DEVICE) },
            )
        } else if (user != null) {
            val name = user.displayName?.takeIf { it.isNotBlank() }
            val host = container.session.serverUrl?.let { Uri.parse(it).authority }
            SettingsAccountCard(
                title = name ?: user.email,
                subtitle = listOfNotNull(if (name != null) user.email else null, host)
                    .joinToString(" · ")
                    .ifEmpty { "Your account" },
                initial = (name ?: user.email).take(1).uppercase().ifEmpty { "?" },
                picture = picture,
                onClick = { onOpen(SettingsRoutes.ACCOUNT) },
            )
        }

        SettingsGroup {
            SettingsRow(
                icon = Icons.Outlined.Palette,
                title = "Theme",
                onClick = { choosingTheme = true },
                trailing = { SettingsValue(theme.label) },
            )
            SettingsDivider()
            NotificationsRow(access, onClick = { onOpen(SettingsRoutes.NOTIFICATIONS) })
        }

        SettingsGroup {
            SettingsRow(
                icon = Icons.Outlined.SaveAlt,
                title = "Your data",
                summary = "Save a copy of your notes, or restore one",
                chevron = true,
                onClick = { onOpen(SettingsRoutes.DATA) },
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "About",
                chevron = true,
                onClick = { onOpen(SettingsRoutes.ABOUT) },
                trailing = { SettingsValue(appVersion) },
            )
        }
    }

    if (choosingTheme) {
        ThemeDialog(
            current = theme,
            onPick = {
                container.appearance.setTheme(it)
                choosingTheme = false
            },
            onDismiss = { choosingTheme = false },
        )
    }
}

/** The notifications row: where both permissions stand, marked when either is off. */
@Composable
private fun NotificationsRow(access: NotificationAccess, onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    val summary = when {
        !access.notifications -> "Blocked: reminders won't appear"
        !access.exactAlarms -> "Allowed · reminders may arrive late"
        else -> "Allowed · reminders on the minute"
    }
    SettingsRow(
        icon = Icons.Outlined.Notifications,
        title = "Notifications",
        summary = summary,
        summaryColor = if (access.notifications) KeepItColors.TextMuted else error,
        chevron = true,
        onClick = onClick,
        trailing = if (access.allGood) {
            null
        } else {
            { Icon(Icons.Outlined.ErrorOutline, contentDescription = "Needs attention", tint = error) }
        },
    )
}

/**
 * The theme picker: the web's four choices, labelled as its Appearance menu labels them. The choice
 * is this phone's — it isn't sent to the server, so the web app keeps its own and neither follows
 * the other (see [org.hyperstarit.keepitapp.data.Appearance]). Picking one applies it at once, here
 * and on the home-screen widget, and closes the dialog.
 */
@Composable
private fun ThemeDialog(current: ThemePref, onPick: (ThemePref) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KeepItColors.Surface,
        title = { Text("Theme") },
        text = {
            Column {
                Text(
                    text = "Set on this phone only. The web app keeps its own.",
                    color = KeepItColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ThemePref.entries.forEach { pref ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(selected = pref == current, role = Role.RadioButton, onClick = { onPick(pref) })
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = pref == current,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = KeepItColors.AccentInk),
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(pref.label, color = KeepItColors.Text, fontSize = 15.sp)
                            if (pref == ThemePref.System) {
                                Text(
                                    text = "Follows your phone between light and dark",
                                    color = KeepItColors.TextFaint,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KeepItColors.TextMuted)
            }
        },
    )
}

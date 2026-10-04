package org.hyperstarit.keepitapp.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * Standalone mode's stand-in for the Account page: where the notes live, the way to a server, and
 * the way out. Erasing goes through the sign-out path — in standalone mode that is what it means —
 * behind a confirmation, since there is no server copy to come back to.
 */
@Composable
fun DeviceSettingsScreen(container: AppContainer, onBack: () -> Unit, onConnectServer: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notes by container.notesRepo.allNotes.collectAsState()
    var confirmErase by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }

    SettingsPage(title = "This device", onBack = onBack) {
        SettingsGroup {
            Text(
                text = "Your notes are stored only on this phone, with no account and no server " +
                    "behind them. Save a copy now and then under Settings → Your data: it is the " +
                    "only backup this phone has.",
                color = KeepItColors.TextMuted,
                fontSize = 14.sp,
                modifier = SettingsContentPadding,
            )
        }

        SettingsGroup {
            SettingsRow(
                icon = Icons.Outlined.CloudUpload,
                title = "Connect to a server",
                summary = "Upload your notes into an account and sync them with your other devices",
                chevron = true,
                onClick = onConnectServer,
            )
        }

        SettingsGroup {
            val error = MaterialTheme.colorScheme.error
            SettingsRow(
                icon = Icons.Outlined.DeleteForever,
                title = "Erase notes",
                summary = "Deletes every note, list and image on this phone and returns to the " +
                    "sign-in screen",
                titleColor = error,
                tint = error,
                onClick = if (erasing) null else ({ confirmErase = true }),
            )
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

package org.hyperstarit.keepitapp.ui.auth

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * Asks before a sign-out that would lose work: [pending] changes are still queued for the server.
 * Signing out tries one last sync, then wipes the queue whether or not that got through — so the
 * dialog says what is at stake. Shared by the drawer and the Account settings page, the two places
 * Sign out lives.
 */
@Composable
fun UnsyncedSignOutDialog(pending: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val changes = if (pending == 1) "1 change hasn't" else "$pending changes haven't"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KeepItColors.Surface,
        title = { Text("Sign out with unsynced changes?") },
        text = {
            Text(
                text = "$changes reached the server yet. keepIT tries to send them before signing " +
                    "out, but anything it can't send — edits, photos and voice notes made on this " +
                    "phone — is lost.",
                color = KeepItColors.TextMuted,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Sign out")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KeepItColors.TextMuted)
            }
        },
    )
}

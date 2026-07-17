package org.spaceelephant.keepitapp.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.spaceelephant.keepitapp.AppContainer
import org.spaceelephant.keepitapp.data.CreateShareDto
import org.spaceelephant.keepitapp.data.NoteDto
import org.spaceelephant.keepitapp.data.NoteRoles
import org.spaceelephant.keepitapp.data.NoteShareDto
import org.spaceelephant.keepitapp.data.SessionState
import org.spaceelephant.keepitapp.data.UpdateShareRoleDto
import org.spaceelephant.keepitapp.data.apiErrorMessage
import org.spaceelephant.keepitapp.ui.theme.KeepItColors

/**
 * Share management for a note, the phone twin of the web ShareDialog: the owner invites people by
 * email at a role (access is granted when they accept), sees pending invites, changes roles, and
 * revokes; a collaborator sees who's on the note and can leave it. Online-only, like the web —
 * failures surface inline and nothing is queued.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    container: AppContainer,
    note: NoteDto,
    onDismiss: () -> Unit,
    /** Called after the caller removed themselves — the editor should close (access is gone). */
    onLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val api = container.apiClient.api
    val sessionState by container.session.state.collectAsState()
    val myId = (sessionState as? SessionState.SignedIn)?.user?.id

    var shares by remember { mutableStateOf<List<NoteShareDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf(NoteRoles.VIEWER) }

    suspend fun reload() {
        runCatching { api.shares(note.id) }
            .onSuccess { shares = it; error = null }
            .onFailure { error = apiErrorMessage(it, "Could not load who this note is shared with.") }
        loading = false
    }

    LaunchedEffect(note.id) { reload() }

    /** Runs a share mutation with the shared busy/error handling, then reloads the list. */
    fun mutate(fallback: String, refreshNotes: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    if (refreshNotes) container.syncEngine.kick()
                    reload()
                }
                .onFailure { error = apiErrorMessage(it, fallback) }
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KeepItColors.Surface) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                text = "Share note",
                color = KeepItColors.Text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Text(
                text = if (note.isOwner) {
                    "Invite someone by email — they get access once they accept."
                } else {
                    "This note is shared with you."
                },
                color = KeepItColors.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            if (note.isOwner) {
                OutlinedTextField(
                    value = inviteEmail,
                    onValueChange = { inviteEmail = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    FilterChip(
                        selected = inviteRole == NoteRoles.VIEWER,
                        onClick = { inviteRole = NoteRoles.VIEWER },
                        label = { Text("Can view") },
                    )
                    FilterChip(
                        selected = inviteRole == NoteRoles.EDITOR,
                        onClick = { inviteRole = NoteRoles.EDITOR },
                        label = { Text("Can edit") },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = !busy && inviteEmail.isNotBlank(),
                        onClick = {
                            mutate("Could not send the invite.") {
                                api.createShare(note.id, CreateShareDto(inviteEmail.trim(), inviteRole))
                                inviteEmail = ""
                            }
                        },
                    ) {
                        Text("Invite", color = KeepItColors.Accent)
                    }
                }
                HorizontalDivider(color = KeepItColors.BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
            }

            error?.let {
                Text(
                    text = it,
                    color = androidx.compose.ui.graphics.Color(0xFFF87171),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = KeepItColors.Accent, modifier = Modifier.size(24.dp))
                }

                shares.isEmpty() -> Text(
                    text = "Not shared with anyone yet.",
                    color = KeepItColors.TextFaint,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                else -> shares.forEach { share ->
                    ShareRow(
                        share = share,
                        isOwner = note.isOwner,
                        isSelf = share.granteeId == myId,
                        busy = busy,
                        onRoleChange = { role ->
                            mutate("Could not change the role.") {
                                api.updateShareRole(note.id, share.granteeId, UpdateShareRoleDto(role))
                            }
                        },
                        onRemove = {
                            val leaving = !note.isOwner && share.granteeId == myId
                            mutate(
                                fallback = "Could not remove access.",
                                refreshNotes = true,
                            ) {
                                api.revokeShare(note.id, share.granteeId)
                                if (leaving) onLeft()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One collaborator row: email + role (owner: editable, with a pending badge), and remove/leave. */
@Composable
private fun ShareRow(
    share: NoteShareDto,
    isOwner: Boolean,
    isSelf: Boolean,
    busy: Boolean,
    onRoleChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var roleMenuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(share.email, color = KeepItColors.Text, fontSize = 14.sp)
            Text(
                text = if (share.pending) "Invited — waiting for an answer" else roleLabel(share.role),
                color = KeepItColors.TextFaint,
                fontSize = 12.sp,
            )
        }

        // Only the owner may change roles, and only on accepted shares (an invite's role is fixed).
        if (isOwner && !share.pending) {
            Box {
                TextButton(enabled = !busy, onClick = { roleMenuOpen = true }) {
                    Text(roleLabel(share.role), color = KeepItColors.TextMuted, fontSize = 13.sp)
                }
                DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Can view") },
                        onClick = {
                            roleMenuOpen = false
                            if (share.role != NoteRoles.VIEWER) onRoleChange(NoteRoles.VIEWER)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Can edit") },
                        onClick = {
                            roleMenuOpen = false
                            if (share.role != NoteRoles.EDITOR) onRoleChange(NoteRoles.EDITOR)
                        },
                    )
                }
            }
        }

        if (isOwner || isSelf) {
            if (isSelf && !isOwner) {
                TextButton(enabled = !busy, onClick = onRemove) {
                    Text("Leave", color = KeepItColors.TextMuted, fontSize = 13.sp)
                }
            } else {
                IconButton(enabled = !busy, onClick = onRemove) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = if (share.pending) "Cancel invite" else "Remove access",
                        tint = KeepItColors.TextFaint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun roleLabel(role: String): String =
    if (role == NoteRoles.EDITOR) "Can edit" else "Can view"

package org.hyperstarit.keepitapp.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.NotificationTypes
import org.hyperstarit.keepitapp.data.ShareResponseDto
import org.hyperstarit.keepitapp.data.UserNotificationDto
import org.hyperstarit.keepitapp.data.apiErrorMessage
import org.hyperstarit.keepitapp.notifications.AppNotifications
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * The in-app inbox, the phone twin of the web's notification bell: share invites are answerable
 * here (accept grants access, either answer consumes the invite), everything else is dismissable.
 * The tray keeps alerting (that's the watcher's job) — this screen is where invites get *acted on*,
 * which a native notification tap can't do. Online-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = container.apiClient.api

    var items by remember { mutableStateOf<List<UserNotificationDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun reload() {
        runCatching { api.notifications() }
            .onSuccess { items = it }
            .onFailure { snackbarHostState.showSnackbar(apiErrorMessage(it, "Could not load notifications.")) }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    /** Shared flow for answer/dismiss: call, drop the tray copy, reload; errors go to the snackbar. */
    fun act(item: UserNotificationDto, fallback: String, refreshNotes: Boolean, block: suspend (String) -> Unit) {
        val id = item.id ?: return
        if (busyId != null) return
        busyId = id
        scope.launch {
            runCatching { block(id) }
                .onSuccess {
                    AppNotifications.cancelGeneral(context, id)
                    if (refreshNotes) container.syncEngine.kick()
                    reload()
                }
                .onFailure { snackbarHostState.showSnackbar(apiErrorMessage(it, fallback)) }
            busyId = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                title = { Text("Notifications", fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
            )
        },
    ) { padding ->
        when {
            loading -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = KeepItColors.Accent)
            }

            items.isEmpty() -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("You're all caught up.", color = KeepItColors.TextMuted)
            }

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
                ),
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(items.filter { it.id != null }, key = { it.id!! }) { item ->
                    NotificationCard(
                        item = item,
                        busy = busyId == item.id,
                        onAccept = {
                            act(item, "Could not accept the invite.", refreshNotes = true) {
                                api.respondToInvite(it, ShareResponseDto(accept = true))
                            }
                        },
                        onDecline = {
                            act(item, "Could not decline the invite.", refreshNotes = false) {
                                api.respondToInvite(it, ShareResponseDto(accept = false))
                            }
                        },
                        onDismiss = {
                            act(item, "Could not dismiss the notification.", refreshNotes = false) {
                                api.deleteNotification(it)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One inbox row: a share invite gets Accept/Decline, everything else a dismiss X. */
@Composable
private fun NotificationCard(
    item: UserNotificationDto,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isInvite = item.type == NotificationTypes.SHARE_INVITE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeepItColors.Surface, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = if (isInvite) 4.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                if (isInvite) {
                    Text(
                        text = item.sharedByUserEmail ?: "Someone",
                        color = KeepItColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "wants to share \"${item.sharedNoteTitle?.takeIf { it.isNotBlank() } ?: "Untitled note"}\" " +
                            "with you (${if (item.role == "Editor") "can edit" else "can view"}).",
                        color = KeepItColors.TextMuted,
                        fontSize = 13.sp,
                    )
                } else {
                    Text(item.notificationText, color = KeepItColors.Text, fontSize = 14.sp)
                }
            }
            if (!isInvite) {
                IconButton(enabled = !busy, onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "Dismiss",
                        tint = KeepItColors.TextFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (isInvite) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(
                        color = KeepItColors.Accent,
                        modifier = Modifier.padding(12.dp).size(18.dp),
                    )
                } else {
                    TextButton(onClick = onDecline) {
                        Text("Decline", color = KeepItColors.TextMuted, fontSize = 13.sp)
                    }
                    TextButton(onClick = onAccept) {
                        Text("Accept", color = KeepItColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

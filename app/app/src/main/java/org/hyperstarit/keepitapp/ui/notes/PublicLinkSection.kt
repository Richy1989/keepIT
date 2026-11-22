package org.hyperstarit.keepitapp.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.PublicLinkDto
import org.hyperstarit.keepitapp.data.ServerFeatures
import org.hyperstarit.keepitapp.data.ServerVersions
import org.hyperstarit.keepitapp.data.UpsertPublicLinkDto
import org.hyperstarit.keepitapp.data.apiErrorMessage
import org.hyperstarit.keepitapp.data.ensureUtc
import org.hyperstarit.keepitapp.data.publicLinkUrl
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/** How long a link stays up, as the owner picks it. [KEEP] leaves an existing expiry date alone. */
private enum class Expiry(val label: String, val days: Long?) {
    KEEP("Keep date", null),
    NEVER("Never", null),
    DAY("1 day", 1),
    WEEK("7 days", 7),
    MONTH("30 days", 30),
}

/**
 * The owner's "Publish to web" controls in the share sheet, the phone twin of the web's
 * `PublicLinkSection` (ARCHITECTURE.md "Public links"): make a link anyone can open without an
 * account, read-only or editable, with an optional password and expiry; copy or send it; replace
 * it; take it down. Online-only like the rest of sharing: nothing is queued.
 *
 * Shown to the owner only. A server older than [ServerFeatures.PUBLIC_LINKS] has no such endpoint,
 * so the section says the server needs updating instead (never found out by catching a 404). The
 * share sheet itself isn't offered in standalone mode, where there is no server to publish on.
 */
@Composable
fun PublicLinkSection(container: AppContainer, note: NoteDto, serverVersion: String?) {
    val supported = ServerVersions.atLeast(serverVersion, ServerFeatures.PUBLIC_LINKS)
    if (!note.isOwner || supported == null) return

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Public, contentDescription = null, tint = KeepItColors.TextFaint, modifier = Modifier.size(16.dp))
            Text("Publish to web", color = KeepItColors.TextFaint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        if (!supported) {
            Text(
                text = "Publishing a note to the web needs keepIT server 1.6 or newer. Ask whoever runs your server to update it.",
                color = KeepItColors.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            return@Column
        }
        PublicLinkControls(container, note)
    }
}

@Composable
private fun PublicLinkControls(container: AppContainer, note: NoteDto) {
    val api = container.apiClient.api
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var link by remember { mutableStateOf<PublicLinkDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(note.id) {
        runCatching { api.publicLink(note.id) }
            .onSuccess { response ->
                link = if (response.code() == 204) null else response.body()
                if (!response.isSuccessful) error = "Could not load the public link."
            }
            .onFailure { error = apiErrorMessage(it, "Could not load the public link.") }
        loading = false
    }

    if (loading) {
        Text("Loading…", color = KeepItColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }

    /** Runs a link change with shared busy/error handling; the notes refresh for the card's globe. */
    fun change(fallback: String, block: suspend () -> PublicLinkDto?) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    link = it
                    container.syncEngine.kick()
                }
                .onFailure { error = apiErrorMessage(it, fallback) }
            busy = false
        }
    }

    // The form starts again from what the server has after every save.
    key(link?.token, link?.allowEdit, link?.hasPassword, link?.expiresAtUtc) {
        LinkForm(
            link = link,
            serverUrl = container.apiClient.baseUrl.orEmpty(),
            busy = busy,
            error = error,
            onSave = { body -> change("Could not publish the note.") { api.upsertPublicLink(note.id, body) } },
            onRemove = {
                change("Could not stop sharing the link.") {
                    api.deletePublicLink(note.id)
                    null
                }
            },
            onCopy = { url -> copyLink(context, url) },
            onSend = { url -> sendLink(context, note, url) },
        )
    }
}

@Composable
private fun LinkForm(
    link: PublicLinkDto?,
    serverUrl: String,
    busy: Boolean,
    error: String?,
    onSave: (UpsertPublicLinkDto) -> Unit,
    onRemove: () -> Unit,
    onCopy: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    var allowEdit by remember { mutableStateOf(link?.allowEdit ?: false) }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var expiry by remember {
        mutableStateOf(
            when {
                link?.expiresAtUtc != null -> Expiry.KEEP
                link != null -> Expiry.NEVER
                else -> Expiry.WEEK
            },
        )
    }
    var formError by remember { mutableStateOf<String?>(null) }
    val willHavePassword = password.isNotEmpty() || (link?.hasPassword == true && !clearPassword)
    val url = link?.takeIf { it.token.isNotEmpty() }?.let { publicLinkUrl(serverUrl, it) }

    fun save(regenerate: Boolean) {
        formError = null
        if (password.isNotEmpty() && password.length < 4) {
            formError = "The password needs at least 4 characters."
            return
        }
        val expiresAtUtc = when (expiry) {
            Expiry.KEEP -> link?.expiresAtUtc
            Expiry.NEVER -> null
            else -> Instant.now().plus(Duration.ofDays(expiry.days!!)).toString()
        }
        onSave(
            UpsertPublicLinkDto(
                password = password.ifEmpty { null },
                clearPassword = password.isEmpty() && clearPassword,
                expiresAtUtc = expiresAtUtc,
                allowEdit = allowEdit,
                regenerate = regenerate,
            ),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        if (link == null) {
            Text("Anyone with the link can open this note without an account.", color = KeepItColors.TextMuted, fontSize = 13.sp)
        } else if (url == null) {
            // The server's keys changed since the link was made: it can't show it, so make a new one.
            Text("This link can't be shown again. Make a new link to share it.", color = KeepItColors.TextMuted, fontSize = 13.sp)
        } else {
            SelectionContainer {
                Text(url, color = KeepItColors.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onCopy(url) }) { Text("Copy link", color = KeepItColors.AccentInk) }
                TextButton(onClick = { onSend(url) }) { Text("Share…", color = KeepItColors.AccentInk) }
            }
            if (link.isExpired) {
                Text("This link has expired. Choose a new expiry.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = allowEdit,
                onCheckedChange = { allowEdit = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = KeepItColors.Accent,
                    checkmarkColor = Color.Black,
                    uncheckedColor = KeepItColors.BorderControl,
                ),
            )
            Text("Anyone with the link can edit", color = KeepItColors.Text, fontSize = 14.sp)
        }
        if (allowEdit) {
            Text(
                "They can change the title, the text and the checklist, not the colour or the attachments.",
                color = KeepItColors.TextMuted,
                fontSize = 12.sp,
            )
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(128) },
            label = {
                Text(if (link?.hasPassword == true && !clearPassword) "New password (one is set)" else "Password (optional)")
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (link?.hasPassword == true) {
            TextButton(onClick = { clearPassword = !clearPassword; password = "" }) {
                Text(if (clearPassword) "Keep the password" else "Remove the password", color = KeepItColors.TextMuted, fontSize = 13.sp)
            }
        }

        Text("Link expires", color = KeepItColors.TextMuted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Expiry.entries
                .filter { it != Expiry.KEEP || link?.expiresAtUtc != null }
                .forEach { option ->
                    FilterChip(
                        selected = expiry == option,
                        onClick = { expiry = option },
                        label = {
                            Text(if (option == Expiry.KEEP) "On ${formatExpiry(link?.expiresAtUtc)}" else option.label)
                        },
                    )
                }
        }

        if (allowEdit && !willHavePassword) {
            Text(
                "Anyone with the link can change this note. Consider a password.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }
        (formError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !busy, onClick = { save(false) }) {
                Text(if (link == null) "Create public link" else "Save", color = KeepItColors.AccentInk)
            }
            if (link != null) {
                TextButton(enabled = !busy, onClick = { save(true) }) { Text("New link", color = KeepItColors.TextMuted) }
                TextButton(enabled = !busy, onClick = onRemove) { Text("Stop sharing", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** An expiry instant in the device's own words, or "" when there is none. */
private fun formatExpiry(iso: String?): String = runCatching {
    Instant.parse(ensureUtc(iso ?: return ""))
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
}.getOrDefault("")

/** Copies the link. Not marked sensitive: it is made to be passed on; the system confirms the copy. */
private fun copyLink(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("keepIT link", url))
}

/** Hands the link to the OS share sheet (a chat, an email…). */
private fun sendLink(context: Context, note: NoteDto, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        note.title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    context.startActivity(Intent.createChooser(send, "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

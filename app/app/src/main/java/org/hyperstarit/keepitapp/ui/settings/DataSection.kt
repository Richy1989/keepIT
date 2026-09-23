package org.hyperstarit.keepitapp.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.ImportResultDto
import org.hyperstarit.keepitapp.data.portability.ExportOutcome
import org.hyperstarit.keepitapp.data.portability.ImportOutcome
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * Export and import, for both modes.
 *
 * Standalone is the reason this screen has the section at all: the mode's own copy a few rows up
 * says the notes live only on this phone with no backup, and until now offered nothing about it.
 * The wording differs by mode for the same reason — a server user is taking a copy of an account,
 * a standalone user is making the only copy that exists.
 *
 * Both halves go through the system file picker rather than writing somewhere chosen for them:
 * an export the app owns would be erased with the app, which is most of what a backup is for.
 */
@Composable
fun DataSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val standalone by container.appMode.standalone.collectAsState()
    val portability = container.portability

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<ExportOutcome.Done?>(null) }
    var imported by remember { mutableStateOf<ImportResultDto?>(null) }

    val saveTo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        exported = null
        imported = null
        scope.launch {
            when (val outcome = portability.export(uri)) {
                is ExportOutcome.Done -> exported = outcome
                is ExportOutcome.Failed -> error = outcome.message
            }
            busy = false
        }
    }

    // Some file providers hand a .zip back as application/octet-stream, so both are accepted and
    // the archive is identified by its contents rather than the type the picker claims.
    val openFrom = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        exported = null
        imported = null
        scope.launch {
            when (val outcome = portability.import(uri)) {
                is ImportOutcome.Done -> imported = outcome.result
                is ImportOutcome.Failed -> error = outcome.message
            }
            busy = false
        }
    }

    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        Text("Save a copy", color = KeepItColors.Text, fontSize = 15.sp)
        Text(
            text = if (standalone) {
                "Writes every note on this phone — text, checklists, lists, reminders and photos — " +
                    "to a single .zip you choose the location for. This is the backup this phone " +
                    "otherwise doesn't have."
            } else {
                "Downloads every note you own — text, checklists, lists, reminders and photos — as a " +
                    "single .zip. Notes other people shared with you stay theirs and aren't included."
            },
            color = KeepItColors.TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Button(
            onClick = { saveTo.launch(portability.suggestedFileName()) },
            enabled = !busy,
        ) {
            Text(if (busy) "Working…" else "Save my notes")
        }

        Text(
            "Restore from a copy",
            color = KeepItColors.Text,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = "Adds the notes from a keepIT export to " +
                (if (standalone) "this phone" else "this account") +
                ". Nothing already here is changed or replaced — everything arrives as new notes, " +
                "so importing the same file twice gives you two of each.",
            color = KeepItColors.TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = { openFrom.launch(arrayOf("application/zip", "application/octet-stream")) },
            enabled = !busy,
        ) {
            Text("Choose an export file…")
        }

        error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        exported?.let { done ->
            Text(
                text = "Saved ${done.notes} ${plural(done.notes, "note")}" +
                    (if (done.images > 0) " and ${done.images} ${plural(done.images, "image")}" else "") +
                    " (${formatBytes(done.bytes)}). Keep it somewhere safe — anyone who opens it " +
                    "can read your notes.",
                color = KeepItColors.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        imported?.let { result -> ImportSummary(result) }
    }
}

/** What the import did, and anything it had to leave out. */
@Composable
private fun ImportSummary(result: ImportResultDto) {
    Text(
        text = buildString {
            append("Imported ${result.notesImported} ${plural(result.notesImported, "note")}")
            if (result.listsCreated > 0) {
                append(", ${result.listsCreated} new ${plural(result.listsCreated, "list")}")
            }
            if (result.imagesImported > 0) {
                append(", ${result.imagesImported} ${plural(result.imagesImported, "image")}")
            }
            append(".")
            if (result.listsReused > 0) {
                append(" Filed into ${result.listsReused} ${plural(result.listsReused, "list")} you already had.")
            }
        },
        color = KeepItColors.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 12.dp),
    )

    // Capped: a big archive can produce one line per image, and a wall of them in a settings
    // screen tells the user less than the first few plus a count.
    if (result.warnings.isNotEmpty()) {
        Text(
            text = result.warnings.take(3).joinToString("\n") { "• $it" } +
                if (result.warnings.size > 3) "\n• …and ${result.warnings.size - 3} more." else "",
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun plural(n: Int, noun: String): String = if (n == 1) noun else "${noun}s"

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

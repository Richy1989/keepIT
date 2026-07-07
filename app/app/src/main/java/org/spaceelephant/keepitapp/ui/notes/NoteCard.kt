package org.spaceelephant.keepitapp.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.spaceelephant.keepitapp.data.NoteDto
import org.spaceelephant.keepitapp.data.NoteStateDto
import org.spaceelephant.keepitapp.data.NoteTypes
import org.spaceelephant.keepitapp.data.NotesRepository
import org.spaceelephant.keepitapp.data.ensureUtc
import org.spaceelephant.keepitapp.ui.markdown.MarkdownText
import org.spaceelephant.keepitapp.ui.theme.CardShape
import org.spaceelephant.keepitapp.ui.theme.KeepItColors
import org.spaceelephant.keepitapp.ui.theme.noteSwatch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_PREVIEW_ITEMS = 6

/**
 * One card in the masonry grid, styled like the web NoteCard: palette background + border, title,
 * body/checklist preview, and a footer with pin (trash view: restore / owner-only delete-forever),
 * share badge, and timestamp. Tapping the card opens the editor.
 */
@Composable
fun NoteCard(note: NoteDto, repo: NotesRepository, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val swatch = noteSwatch(note.color)

    Surface(
        color = swatch.bg,
        shape = CardShape,
        border = BorderStroke(1.dp, swatch.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (!note.title.isNullOrBlank()) {
                Text(
                    text = note.title,
                    color = KeepItColors.Text,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(6.dp))
            }

            if (note.type == NoteTypes.CHECKLIST) {
                ChecklistPreview(note)
            } else if (!note.body.isNullOrBlank()) {
                MarkdownText(
                    source = note.body,
                    color = KeepItColors.Text.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 8,
                )
            }

            if (note.title.isNullOrBlank() && note.body.isNullOrBlank() && note.checklistItems.isEmpty()) {
                Text(text = "Empty note", color = KeepItColors.TextFaint, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.size(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (note.isTrashed) {
                    IconButton(
                        onClick = { scope.launch { repo.setState(note.id, NoteStateDto(isTrashed = false)) } },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Restore",
                            tint = KeepItColors.TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // Purging is the owner's call; a collaborator's trash only hides their own view.
                    if (note.isOwner) {
                        IconButton(
                            onClick = { scope.launch { repo.delete(note.id) } },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete forever",
                                tint = KeepItColors.TextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    IconButton(
                        onClick = { scope.launch { repo.setState(note.id, NoteStateDto(isPinned = !note.isPinned)) } },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (note.isPinned) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = if (note.isPinned) "Unpin" else "Pin",
                            tint = if (note.isPinned) KeepItColors.Accent else KeepItColors.TextFaint,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                ShareBadge(note)

                Text(
                    text = formatDate(note.createdAtUtc),
                    color = KeepItColors.TextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ChecklistPreview(note: NoteDto) {
    val items = note.checklistItems.sortedBy { it.order }
    val done = items.count { it.isChecked }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.take(MAX_PREVIEW_ITEMS).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (item.isChecked) KeepItColors.Accent else KeepItColors.BorderStrong,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = item.text,
                    color = if (item.isChecked) KeepItColors.TextFaint else KeepItColors.Text,
                    textDecoration = if (item.isChecked) TextDecoration.LineThrough else null,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (items.size > MAX_PREVIEW_ITEMS) {
            Text(
                text = "+ ${items.size - MAX_PREVIEW_ITEMS} more",
                color = KeepItColors.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        if (items.isNotEmpty()) {
            Text(text = "$done/${items.size} done", color = KeepItColors.TextFaint, fontSize = 11.sp)
        }
    }
}

/** Share status like the web: owner sees "Shared"; a collaborator sees their access level. */
@Composable
private fun ShareBadge(note: NoteDto) {
    val label = when {
        note.isOwner && note.isShared -> "Shared"
        !note.isOwner && note.canEdit -> "Can edit"
        !note.isOwner -> "View only"
        else -> null
    } ?: return
    Text(text = label, color = KeepItColors.TextFaint, fontSize = 11.sp)
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d")

/** Renders a backend UTC timestamp in the device's local zone (SQLite dev values may lack 'Z'). */
fun formatDate(iso: String): String = runCatching {
    DATE_FORMAT.format(Instant.parse(ensureUtc(iso)).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

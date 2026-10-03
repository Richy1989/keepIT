package org.hyperstarit.keepitapp.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.NotesRepository
import org.hyperstarit.keepitapp.data.ensureUtc
import org.hyperstarit.keepitapp.data.inDisplayOrder
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.ui.markdown.MarkdownText
import org.hyperstarit.keepitapp.ui.theme.CardShape
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.noteSwatch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_PREVIEW_ITEMS = 6

/**
 * One full-width row in the note list, styled like the web NoteCard: palette background + border,
 * title, body/checklist preview, and a footer with pin (trash view: restore / owner-only
 * delete-forever), share badge, and timestamp. Tapping the card opens the editor.
 *
 * [pendingMedia] is the note's queued attachments: the hero falls back to the first of them when
 * the note has no stored image yet — a photo picked offline shows at once, and in standalone mode,
 * where nothing is ever uploaded, it is the only way the card shows images at all.
 *
 * [audio] is the list's one player. It is passed in rather than remembered here because a card
 * scrolling out of the `LazyColumn` must not take the recording it is playing with it — see
 * [CardAudioPlayer].
 */
@Composable
fun NoteCard(
    note: NoteDto,
    repo: NotesRepository,
    audio: CardAudioPlayer,
    pendingMedia: List<PendingOp.AttachMedia> = emptyList(),
    onOpen: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val swatch = noteSwatch(note.color)
    var showReminder by remember { mutableStateOf(false) }

    Surface(
        color = swatch.bg,
        shape = CardShape,
        border = BorderStroke(1.dp, swatch.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column {
            // Full-bleed hero (layout C1): the photo owns the top of the card and carries the
            // title on a scrim, but body and checklist rows stay below on the note's own colour,
            // where contrast is a known quantity rather than whatever the user photographed.
            // The first *picture*: a voice note has no thumbnail, and asking for one would leave
            // the hero an empty grey block.
            val hero = note.media.firstOrNull { !it.isAudio }
            val stagedHero = if (hero == null) pendingMedia.firstOrNull { !it.isAudio } else null
            val imageCount = note.media.count { !it.isAudio } + pendingMedia.count { !it.isAudio }
            val storedRecordings = note.media.filter { it.isAudio }
            val pendingRecordings = pendingMedia.filter { it.isAudio }
            val recordings = storedRecordings.size + pendingRecordings.size
            if (hero != null || stagedHero != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (hero != null) {
                        val ratio =
                            if (hero.width > 0 && hero.height > 0) hero.width.toFloat() / hero.height else 1f
                        NoteMediaImage(
                            cache = repo.mediaCache,
                            noteId = note.id,
                            mediaId = hero.id,
                            size = MediaSizes.PREVIEW,
                            // What the background prefetch keeps on disk for offline use.
                            fallbackSize = MediaSizes.THUMB,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Reserve the real ratio, but never let one tall photo eat the card.
                                .aspectRatio(ratio.coerceAtLeast(0.72f)),
                        )
                    } else if (stagedHero != null) {
                        StagedMediaImage(path = stagedHero.stagedPath, modifier = Modifier.fillMaxWidth())
                    }

                    if (imageCount > 1) {
                        Text(
                            text = "+${imageCount - 1}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }

                    if (!note.title.isNullOrBlank()) {
                        Text(
                            text = note.title,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                    ),
                                )
                                .padding(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 8.dp),
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(14.dp)) {
                // The title already rendered on the scrim when there's a hero.
                if (!note.title.isNullOrBlank() && hero == null && stagedHero == null) {
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

                // A voice note plays from the card itself: it has nothing to show, and making
                // someone open the note to hear a twelve-second recording is a tap for nothing.
                // Still capped — past the cap the note itself is the place to go.
                if (recordings > 0) {
                    val storedShown = storedRecordings.take(MAX_CARD_RECORDINGS)
                    val stagedShown = pendingRecordings.take(MAX_CARD_RECORDINGS - storedShown.size)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        storedShown.forEach { rec ->
                            CardVoiceNoteRow(
                                audio = audio,
                                mediaKey = "${note.id}:${rec.id}",
                                durationMs = rec.durationMs,
                                openFile = {
                                    repo.mediaCache.file(note.id, rec.id, MediaSizes.FULL)
                                },
                            )
                        }
                        // A staged recording plays straight from the file it was recorded into,
                        // which is what makes this work offline and in standalone mode, where
                        // nothing is ever uploaded and the outbox holds the only copy.
                        stagedShown.forEach { op ->
                            CardVoiceNoteRow(
                                audio = audio,
                                mediaKey = "staged:${op.opId}",
                                durationMs = null,
                                openFile = { File(op.stagedPath).takeIf { it.isFile } },
                            )
                        }
                        val hidden = recordings - storedShown.size - stagedShown.size
                        if (hidden > 0) {
                            Text(
                                text = "+$hidden more in the note",
                                color = KeepItColors.TextFaint,
                                fontSize = 12.sp,
                            )
                        }
                    }
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

                // A note that is nothing but photos is not empty.
                if (note.title.isNullOrBlank() && note.body.isNullOrBlank() &&
                    note.checklistItems.isEmpty() && imageCount == 0
                ) {
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
                                tint = if (note.isPinned) KeepItColors.AccentInk else KeepItColors.TextFaint,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Reminders are per-user, so viewers get this too — hidden only in the trash.
                    if (!note.isTrashed) {
                        ReminderChip(
                            note = note,
                            onClick = { showReminder = true },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }

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

    if (showReminder) {
        ReminderDialog(
            note = note,
            onSave = { dto -> scope.launch { repo.setReminder(note.id, dto) } },
            onClear = { scope.launch { repo.clearReminder(note.id) } },
            onDismiss = { showReminder = false },
        )
    }
}

@Composable
private fun ChecklistPreview(note: NoteDto) {
    val items = note.checklistItems.inDisplayOrder()
    val done = items.count { it.isChecked }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.take(MAX_PREVIEW_ITEMS).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (item.isChecked) KeepItColors.AccentInk else KeepItColors.BorderStrong,
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

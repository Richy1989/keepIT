package org.hyperstarit.keepitapp.ui.notes

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.offline.MediaCache
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import java.io.File

/** Formats a running time as m:ss - the shape every voice note in the wild is. */
fun formatDuration(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * A stored voice note. The bytes arrive through the same authenticated [MediaCache] images use, so
 * the player is handed a local file and the token never goes near a URL.
 */
@Composable
fun AudioAttachmentRow(
    cache: MediaCache,
    noteId: String,
    media: NoteMediaDto,
    canEdit: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var file by remember(media.id) { mutableStateOf<File?>(null) }
    var failed by remember(media.id) { mutableStateOf(false) }

    LaunchedEffect(noteId, media.id) {
        val fetched = cache.file(noteId, media.id, MediaSizes.FULL)
        file = fetched
        failed = fetched == null
    }

    AudioPlayerRow(
        file = file,
        durationMs = media.durationMs,
        // Offline with nothing cached is not a failure worth shouting about: the recording is on
        // the server and will play once there is a connection.
        message = if (failed) "Not downloaded yet" else null,
        canEdit = canEdit,
        onRemove = onRemove,
        modifier = modifier,
    )
}

/**
 * A voice note recorded on this device that has not reached a server yet - the only kind there is
 * in standalone mode, and the state every recording passes through when made offline. It plays
 * from its staged file, so it works before any upload.
 */
@Composable
fun PendingAudioRow(
    op: PendingOp.AttachMedia,
    canEdit: Boolean,
    uploading: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = remember(op.opId) { File(op.stagedPath).takeIf { it.isFile } }

    AudioPlayerRow(
        file = file,
        durationMs = null,
        message = if (uploading) "Uploading…" else null,
        canEdit = canEdit,
        onRemove = onRemove,
        modifier = modifier,
    )
}

/**
 * The transport itself: play/pause, a progress bar and a running time.
 *
 * [MediaPlayer] rather than a media3 player, because this plays one short local file with no
 * background playback, no notification and no queue - everything ExoPlayer would be brought in
 * for. It is released when the row leaves the composition, which is what stops a scrolled-away
 * note from still talking.
 */
@Composable
private fun AudioPlayerRow(
    file: File?,
    durationMs: Int?,
    message: String?,
    canEdit: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var positionMs by remember { mutableStateOf(0) }

    DisposableEffect(file) {
        onDispose {
            runCatching { player?.release() }
            player = null
            playing = false
        }
    }

    // Poll while playing: MediaPlayer has no position callback, and a progress bar that only moves
    // on play/pause is worse than none.
    LaunchedEffect(playing) {
        while (playing) {
            val active = player
            if (active == null) break
            positionMs = runCatching { active.currentPosition }.getOrDefault(0)
            val total = runCatching { active.duration }.getOrDefault(0)
            progress = if (total > 0) (positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
            delay(200)
        }
    }

    fun toggle() {
        val target = file ?: return
        val active = player
        if (active != null) {
            if (active.isPlaying) {
                active.pause()
                playing = false
            } else {
                active.start()
                playing = true
            }
            return
        }

        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(target.absolutePath)
                setOnCompletionListener {
                    playing = false
                    progress = 0f
                    positionMs = 0
                    runCatching { seekTo(0) }
                }
                prepare()
                start()
            }
        }.getOrNull() ?: return

        player = created
        playing = true
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KeepItColors.Surface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (file == null && message == null) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = ::toggle, enabled = file != null, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play recording",
                    tint = if (file != null) KeepItColors.Accent else KeepItColors.TextFaint,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message ?: "Voice note",
                color = KeepItColors.Text,
                fontSize = 13.sp,
            )
            if (file != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp),
                )
            }
        }

        val shown = when {
            playing || positionMs > 0 -> positionMs
            durationMs != null -> durationMs
            else -> null
        }
        if (shown != null) {
            Text(
                text = formatDuration(shown),
                color = KeepItColors.TextFaint,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (canEdit) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove recording",
                    tint = KeepItColors.TextMuted,
                )
            }
        }
    }
}

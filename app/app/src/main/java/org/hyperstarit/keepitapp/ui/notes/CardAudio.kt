package org.hyperstarit.keepitapp.ui.notes

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import java.io.File

/**
 * How many voice notes one card gives a player before collapsing the rest into a "+N more" line.
 * Mirrors the web grid's `MAX_CARD_RECORDINGS`, and for the same reason: a card is still a summary.
 */
const val MAX_CARD_RECORDINGS = 3

/**
 * One voice note playing at a time, for a whole list of note cards.
 *
 * Deliberately **not** owned by the card the way [AudioAttachmentRow] owns its player in the
 * editor. Two things fall out of that, and both are the point:
 *
 *  * **Scrolling doesn't stop the audio.** A card in a `LazyColumn` leaves the composition as soon
 *    as it scrolls off, which would release a player the listener is still listening to. The editor
 *    wants exactly that behaviour — one note, one recording, gone when you leave — and a list
 *    wants the opposite.
 *  * **Two recordings can't talk over each other.** Starting one releases whatever was playing,
 *    without any card needing to know its siblings exist.
 *
 * The file is opened lazily, through the `open` lambda handed to [toggle]: a stored recording has
 * to come down from the server ([org.hyperstarit.keepitapp.data.offline.MediaCache] downloads on
 * miss), and doing that for every card that scrolls past would pull the whole library over the
 * wire to show a play button nobody pressed. Nothing is fetched until a press asks for it.
 *
 * [MediaPlayer] rather than media3 for the same reason the editor uses it: one short local file, no
 * queue, no background playback, no notification. The owner must call [release].
 */
@Stable
class CardAudioPlayer(private val scope: CoroutineScope) {
    /** Which recording the player holds, as the caller's opaque key, or null when idle. */
    var activeKey by mutableStateOf<String?>(null)
        private set

    /** The recording whose bytes are being fetched right now, if any. */
    var loadingKey by mutableStateOf<String?>(null)
        private set

    var playing by mutableStateOf(false)
        private set

    var positionMs by mutableIntStateOf(0)
        private set

    /** The length [MediaPlayer] reports once prepared; 0 until then. */
    var trackMs by mutableIntStateOf(0)
        private set

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /**
     * Play [key], pause it if it is already playing, resume it if it is paused.
     *
     * [open] is only called when the recording actually has to be loaded, and may return null when
     * the bytes can't be had (offline, nothing cached) — in which case nothing happens, which is
     * the honest outcome: the recording is on the server and will play once there is a connection.
     */
    fun toggle(key: String, open: suspend () -> File?) {
        val current = player
        if (key == activeKey && current != null) {
            if (playing) pause() else resume()
            return
        }
        // A second press while the first is still downloading would leave two players running.
        if (loadingKey != null) return

        release()
        loadingKey = key
        scope.launch {
            val file = open()
            loadingKey = null
            val started = file?.let {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(it.absolutePath)
                        setOnCompletionListener { onCompleted() }
                        prepare()
                        start()
                    }
                }.getOrNull()
            } ?: return@launch

            player = started
            activeKey = key
            trackMs = runCatching { started.duration }.getOrDefault(0)
            positionMs = 0
            playing = true
            startTicker()
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        playing = false
        ticker?.cancel()
    }

    private fun resume() {
        runCatching { player?.start() }
        playing = true
        startTicker()
    }

    private fun onCompleted() {
        playing = false
        positionMs = 0
        ticker?.cancel()
        runCatching { player?.seekTo(0) }
    }

    // MediaPlayer has no position callback, and a progress bar that only moves on play/pause is
    // worse than none.
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (playing) {
                positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
                delay(200)
            }
        }
    }

    /** Stops and frees the player. The screen that created this must call it on dispose. */
    fun release() {
        ticker?.cancel()
        ticker = null
        runCatching { player?.release() }
        player = null
        activeKey = null
        playing = false
        positionMs = 0
        trackMs = 0
    }
}

/**
 * A voice note on a note card: play, pause, how far in — no opening the note.
 *
 * [mediaKey] identifies the recording to [audio] and must be stable across recompositions and
 * unique in the list; [openFile] is the lazy source, either a cached download or a staged file that
 * has not been uploaded yet.
 *
 * The button carries its own `clickable`, so the press never reaches the card's own one and the
 * editor doesn't open underneath the playback.
 */
@Composable
fun CardVoiceNoteRow(
    audio: CardAudioPlayer,
    mediaKey: String,
    durationMs: Int?,
    openFile: suspend () -> File?,
    modifier: Modifier = Modifier,
) {
    val active = audio.activeKey == mediaKey
    val loading = audio.loadingKey == mediaKey
    val isPlaying = active && audio.playing
    val total = if (active && audio.trackMs > 0) audio.trackMs else durationMs ?: 0
    val progress = if (active && total > 0) (audio.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shownMs = if (active && audio.positionMs > 0) audio.positionMs else durationMs

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KeepItColors.Canvas.copy(alpha = 0.35f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(KeepItColors.Accent)
                .clickable { audio.toggle(mediaKey, openFile) },
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.Black,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause voice note" else "Play voice note",
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f),
        )

        if (shownMs != null) {
            Text(
                text = formatDuration(shownMs),
                color = KeepItColors.TextFaint,
                fontSize = 12.sp,
            )
        }
    }
}

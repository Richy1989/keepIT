package org.hyperstarit.keepitapp.ui.notes

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.SaveImageResult
import org.hyperstarit.keepitapp.data.offline.MediaCache

/**
 * Full-screen image viewer, swipeable across a note's attachments.
 *
 * Uses [ContentScale.Fit] rather than the grid's Crop: the card caps how much room an image may
 * claim, but here the whole picture is the point.
 *
 * [onSave] stores the image currently shown in the device's gallery. Its outcome is reported as a
 * toast, since a snackbar would sit behind this dialog.
 */
@Composable
fun MediaViewer(
    cache: MediaCache,
    noteId: String,
    media: List<NoteMediaDto>,
    startIndex: Int,
    onClose: () -> Unit,
    onSave: suspend (mediaId: String) -> SaveImageResult,
) {
    if (media.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // One save at a time: a second tap mid-save would just write a duplicate into the gallery.
    var saving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pager = rememberPagerState(
            initialPage = startIndex.coerceIn(0, media.lastIndex),
            pageCount = { media.size },
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                NoteMediaImage(
                    cache = cache,
                    noteId = noteId,
                    mediaId = media[page].id,
                    size = MediaSizes.FULL,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                IconButton(
                    onClick = {
                        val mediaId = media[pager.currentPage].id
                        saving = true
                        scope.launch {
                            val message = when (onSave(mediaId)) {
                                SaveImageResult.SAVED -> "Saved to Pictures/keepIT"
                                SaveImageResult.UNAVAILABLE -> "Image not available offline"
                                SaveImageResult.FAILED -> "Couldn't save the image"
                            }
                            saving = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !saving,
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Save image",
                        tint = if (saving) Color.White.copy(alpha = 0.4f) else Color.White,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }

            if (media.size > 1) {
                Text(
                    text = "${pager.currentPage + 1} / ${media.size}",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 28.dp),
                )
            }
        }
    }
}

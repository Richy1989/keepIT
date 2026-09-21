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
import coil3.compose.AsyncImage
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.SaveImageResult
import org.hyperstarit.keepitapp.data.offline.MediaCache
import org.hyperstarit.keepitapp.data.offline.PendingOp
import java.io.File

/** One page of the viewer: an image the server stores, or one kept only on this device. */
sealed interface ViewerImage {
    data class Stored(val media: NoteMediaDto) : ViewerImage

    /** A queued attachment shown from its staged file — how standalone mode keeps every image. */
    data class OnDevice(val attachment: PendingOp.AttachMedia) : ViewerImage
}

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
    images: List<ViewerImage>,
    startIndex: Int,
    onClose: () -> Unit,
    onSave: suspend (ViewerImage) -> SaveImageResult,
) {
    if (images.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // One save at a time: a second tap mid-save would just write a duplicate into the gallery.
    var saving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pager = rememberPagerState(
            initialPage = startIndex.coerceIn(0, images.lastIndex),
            pageCount = { images.size },
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (val image = images[page]) {
                    is ViewerImage.Stored -> NoteMediaImage(
                        cache = cache,
                        noteId = noteId,
                        mediaId = image.media.id,
                        size = MediaSizes.FULL,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is ViewerImage.OnDevice -> AsyncImage(
                        model = File(image.attachment.stagedPath),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                IconButton(
                    onClick = {
                        val image = images[pager.currentPage]
                        saving = true
                        scope.launch {
                            val message = when (onSave(image)) {
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

            if (images.size > 1) {
                Text(
                    text = "${pager.currentPage + 1} / ${images.size}",
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

package org.hyperstarit.keepitapp.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.offline.MediaCache

/**
 * Full-screen image viewer, swipeable across a note's attachments.
 *
 * Uses [ContentScale.Fit] rather than the grid's Crop: the card caps how much room an image may
 * claim, but here the whole picture is the point.
 */
@Composable
fun MediaViewer(
    cache: MediaCache,
    noteId: String,
    media: List<NoteMediaDto>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    if (media.isEmpty()) return

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

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
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

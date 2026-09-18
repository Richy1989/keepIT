package org.hyperstarit.keepitapp.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.offline.MediaCache
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import java.io.File

/** Rendition names the media endpoint understands. */
object MediaSizes {
    const val THUMB = "thumb"
    const val FULL = "full"
}

/**
 * How many images one note may hold. Mirrors the server's `App:Media:MaxImagesPerNote`; the server
 * remains the authority and answers 409 past it, this just stops offering a doomed action.
 */
const val MAX_IMAGES_PER_NOTE = 10

/**
 * One note image, resolved through [MediaCache] and rendered by Coil from the resulting file.
 *
 * The cache performs the authenticated fetch, so nothing here needs a token, a custom Coil fetcher
 * or an interceptor — Coil only decodes, downsamples and memory-caches a plain [File].
 */
@Composable
fun NoteMediaImage(
    cache: MediaCache,
    noteId: String,
    mediaId: String,
    size: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var file by remember(noteId, mediaId, size) { mutableStateOf<File?>(null) }

    LaunchedEffect(noteId, mediaId, size) {
        file = cache.file(noteId, mediaId, size)
    }

    val current = file
    if (current != null) {
        AsyncImage(
            model = current,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        // Loading, or offline with nothing cached. A neutral block, never a broken-image glyph.
        Box(modifier.background(KeepItColors.Elevated))
    }
}

/**
 * The editor's image row: stored images plus anything still queued.
 *
 * A pending attachment renders straight from its staged file, so a photo picked with no signal
 * appears instantly and keeps showing across restarts — the staged copy is ours, not a borrowed
 * `content://` grant.
 */
@Composable
fun MediaRow(
    cache: MediaCache,
    noteId: String,
    media: List<NoteMediaDto>,
    pending: List<PendingOp.AttachMedia>,
    canEdit: Boolean,
    onRemove: (String) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media.isEmpty() && pending.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        itemsIndexed(media, key = { _, m -> m.id }) { index, m ->
            Box {
                NoteMediaImage(
                    cache = cache,
                    noteId = noteId,
                    mediaId = m.id,
                    size = MediaSizes.THUMB,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpen(index) },
                )
                if (canEdit) {
                    IconButton(
                        onClick = { onRemove(m.id) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove image",
                            tint = Color.White,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                .padding(4.dp),
                        )
                    }
                }
            }
        }

        items(pending, key = { it.tempMediaId }) { op ->
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(KeepItColors.Elevated),
            ) {
                AsyncImage(
                    model = File(op.stagedPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .alpha(0.5f),
                )
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

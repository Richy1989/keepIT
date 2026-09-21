package org.hyperstarit.keepitapp.data.offline

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hyperstarit.keepitapp.data.GallerySaver
import java.io.File

/**
 * App-private copies of images the user picked, made *before* the attach op is queued.
 *
 * A picked `content://` URI is a temporary, revocable permission grant that does not survive a
 * reboot — and the outbox routinely does. Copying the bytes up front is what makes "attach a photo
 * with no signal, close the app, fly home, and have it upload" work at all.
 *
 * In standalone mode the staged file is not a stopover but the image itself, for as long as the
 * device stays unconnected — which is why this lives under `filesDir`, never the cache directory
 * the system may clear.
 *
 * Staged files are deleted by [SyncEngine] once their op lands or fails permanently, and by
 * [org.hyperstarit.keepitapp.data.NotesRepository] when coalescing discards an op that owned one
 * or the user removes a standalone image.
 */
class MediaStaging(private val context: Context) {

    private val dir = File(context.filesDir, "offline/media-staging").apply { mkdirs() }

    /**
     * Copies the content behind [uri] into staging.
     *
     * @return the staged file, or null when the content could not be read.
     */
    suspend fun stage(context: Context, uri: Uri, tempMediaId: String): File? =
        withContext(Dispatchers.IO) {
            val target = File(dir, "$tempMediaId.img")
            runCatching {
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (copied == null) null else target
            }.getOrNull()
        }

    /** Best-effort delete of a staged file once its op has landed or failed for good. */
    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    /**
     * Copies a staged image the server refused into the device's gallery, before it is deleted. A
     * photo taken with the camera offline, or anything attached in standalone mode, exists nowhere
     * else — refusing it must not quietly destroy it.
     *
     * @return true once it is in the gallery.
     */
    suspend fun rescueToGallery(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return GallerySaver.save(context, file, "keepIT_${file.nameWithoutExtension.take(8)}")
    }

    /** Removes any staged file with no op still referencing it (startup housekeeping). */
    fun pruneExcept(livePaths: Set<String>) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.absolutePath !in livePaths) f.delete()
            }
        }
    }
}

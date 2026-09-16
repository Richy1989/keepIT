package org.hyperstarit.keepitapp.data.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File

/**
 * Downloaded note images on disk. Bytes are fetched through the app's own authenticated client and
 * then handed to the image loader as plain files, which keeps auth out of the rendering path
 * entirely — no custom Coil fetcher, no token in a URL.
 *
 * A media id's bytes never change, so this cache has no invalidation logic at all: only a size cap.
 * It takes a [File] root rather than a Context so the rules are unit-testable on the JVM.
 */
class MediaCache(
    private val root: File,
    private val download: suspend (noteId: String, mediaId: String, size: String) -> ResponseBody?,
) {
    init {
        root.mkdirs()
    }

    /**
     * The cached file for one rendition, downloading it first when missing.
     *
     * @return the file, or null when it isn't cached and can't be fetched (offline).
     */
    suspend fun file(noteId: String, mediaId: String, size: String): File? =
        withContext(Dispatchers.IO) {
            val target = File(root, "${noteId}_${mediaId}_$size.img")
            if (target.exists() && target.length() > 0) return@withContext target

            val body = runCatching { download(noteId, mediaId, size) }.getOrNull()
                ?: return@withContext null

            runCatching {
                // Write to a temp file and rename, so an interrupted download is never served as
                // though it were complete.
                val tmp = File(root, "${target.name}.tmp")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return@runCatching null
                }
                target
            }.getOrNull()
        }

    /** Drops least-recently-modified files until the cache fits in [maxBytes]. */
    fun evict(maxBytes: Long) {
        val files = root.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var kept = 0L
        for (file in files) {
            kept += file.length()
            if (kept > maxBytes) file.delete()
        }
    }

    /** Forgets everything (sign-out): another account must not see these images. */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }
}

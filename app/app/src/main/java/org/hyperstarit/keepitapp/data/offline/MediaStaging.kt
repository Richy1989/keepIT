package org.hyperstarit.keepitapp.data.offline

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App-private copies of images the user picked, made *before* the attach op is queued.
 *
 * A picked `content://` URI is a temporary, revocable permission grant that does not survive a
 * reboot — and the outbox routinely does. Copying the bytes up front is what makes "attach a photo
 * with no signal, close the app, fly home, and have it upload" work at all.
 *
 * Staged files are deleted by [SyncEngine] once their op lands or fails permanently, and by
 * [org.hyperstarit.keepitapp.data.NotesRepository] when coalescing discards an op that owned one.
 */
class MediaStaging(context: Context) {

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

    /** Removes any staged file with no op still referencing it (startup housekeeping). */
    fun pruneExcept(livePaths: Set<String>) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.absolutePath !in livePaths) f.delete()
            }
        }
    }
}

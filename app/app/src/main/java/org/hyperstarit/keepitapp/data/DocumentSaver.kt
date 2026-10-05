package org.hyperstarit.keepitapp.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes text files into the device's shared Documents folder, where a file manager (and every other
 * app) can find them — the text counterpart of [GallerySaver], and like it permission-free: through
 * MediaStore, an app may add files to the shared collections since Android 10, and minSdk is 34.
 * Files land in Documents/keepIT.
 */
object DocumentSaver {

    private val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/keepIT"

    /**
     * Saves [text] as [fileName] (which carries its extension) with the MIME type [mime].
     *
     * @return true once the file is there.
     */
    suspend fun save(context: Context, fileName: String, text: String, mime: String = "text/markdown"): Boolean =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, mime)
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                // Hidden from other apps until the text is all there.
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
                ?: return@withContext false

            runCatching {
                val out = checkNotNull(resolver.openOutputStream(uri))
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }.onFailure {
                // Never leave a pending, half-written file behind in the user's Documents.
                runCatching { resolver.delete(uri, null, null) }
            }.isSuccess
        }
}

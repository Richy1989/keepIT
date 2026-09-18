package org.hyperstarit.keepitapp.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How saving an image to the gallery went. */
enum class SaveImageResult {
    SAVED,

    /** The full-size image isn't on the device and couldn't be fetched (offline). */
    UNAVAILABLE,
    FAILED,
}

/**
 * Copies note images into the device's shared Pictures collection, where the gallery and every
 * other app can find them.
 *
 * Goes through MediaStore rather than a file path: since Android 10 an app may add to the shared
 * collections without any storage permission, and this app's minSdk is 34 — so saving never has to
 * ask for anything. Images land in Pictures/keepIT.
 */
object GallerySaver {

    private val relativePath = "${Environment.DIRECTORY_PICTURES}/keepIT"

    /**
     * Saves [source] as [baseName] plus the extension its content calls for.
     *
     * @return true once the image is in the gallery.
     */
    suspend fun save(context: Context, source: File, baseName: String): Boolean =
        withContext(Dispatchers.IO) {
            val isGif = isGif(source)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, baseName + if (isGif) ".gif" else ".jpg")
                put(MediaStore.Images.Media.MIME_TYPE, if (isGif) "image/gif" else "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                // Hidden from other apps until the bytes are all there.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
                ?: return@withContext false

            runCatching {
                val out = checkNotNull(resolver.openOutputStream(uri))
                out.use { output -> source.inputStream().use { it.copyTo(output) } }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }.onFailure {
                // Never leave a pending, half-written entry behind in the user's gallery.
                runCatching { resolver.delete(uri, null, null) }
            }.isSuccess
        }

    /**
     * The server stores originals as JPEG, or as GIF so an animation survives — and the media cache
     * keeps no extension — so the type is read off the file's signature.
     */
    private fun isGif(file: File): Boolean = runCatching {
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) } == 4 && String(header, Charsets.US_ASCII) == "GIF8"
    }.getOrDefault(false)
}

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
            val type = typeOf(source)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, baseName + type.extension)
                put(MediaStore.Images.Media.MIME_TYPE, type.mime)
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

    /** An image type the gallery is told about, with the extension its file name gets. */
    private enum class ImageType(val mime: String, val extension: String) {
        JPEG("image/jpeg", ".jpg"),
        PNG("image/png", ".png"),
        GIF("image/gif", ".gif"),
        WEBP("image/webp", ".webp"),
        HEIC("image/heic", ".heic"),
    }

    /** ISO-BMFF brands that mark an `ftyp` box as HEIC/HEIF. */
    private val heicBrands = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")

    /**
     * The server stores originals as JPEG, or as GIF so an animation survives. An image kept only
     * on this device (standalone mode, or an upload the server refused) is whatever was picked. The
     * media cache and staging keep no extension, so the type is read off the file's signature;
     * anything unrecognised is labelled JPEG, as before.
     */
    private fun typeOf(file: File): ImageType = runCatching {
        val header = ByteArray(12)
        val read = file.inputStream().use { it.read(header) }
        fun ascii(from: Int, length: Int) = String(header, from, length, Charsets.US_ASCII)
        when {
            read >= 4 && ascii(0, 4) == "GIF8" -> ImageType.GIF
            read >= 4 && header[0] == 0x89.toByte() && ascii(1, 3) == "PNG" -> ImageType.PNG
            read >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> ImageType.WEBP
            read >= 12 && ascii(4, 4) == "ftyp" && ascii(8, 4) in heicBrands -> ImageType.HEIC
            else -> ImageType.JPEG
        }
    }.getOrDefault(ImageType.JPEG)
}

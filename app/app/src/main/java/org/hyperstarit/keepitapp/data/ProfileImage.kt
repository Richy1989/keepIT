package org.hyperstarit.keepitapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File

/**
 * The signed-in user's profile picture — uploaded in the web app's Settings — for the avatar
 * circles in the drawer and at the top of Settings, which show the user's initial without one.
 *
 * The endpoint is authenticated, so the bytes are fetched through the app's own client and handed
 * to Coil as a plain [File], as [org.hyperstarit.keepitapp.data.offline.MediaCache] does for note
 * images. The file is kept on disk, so the picture shows offline and from the first frame of a cold
 * start; [refresh] then asks the server for the current one.
 *
 * The server's answer decides what the file becomes: a picture replaces it, a 404 ("no picture")
 * deletes it, and anything else — offline, a 5xx — leaves it be, so a bad connection never turns a
 * picture back into an initial. Each new picture gets a new file name, since Coil caches decoded
 * images by path; one identical to the cached file keeps the old name, so a refresh that changes
 * nothing doesn't make the avatar flicker.
 *
 * Takes a [File] root and the download as a function, so the rules are unit-testable on the JVM.
 */
class ProfileImage(
    private val root: File,
    private val download: suspend (userId: String) -> ResponseBody,
) {
    private val lock = Mutex()
    private var userId: String? = null

    private val _file = MutableStateFlow<File?>(null)

    /** The signed-in user's picture on disk, or null when they have none (or none is known yet). */
    val file: StateFlow<File?> = _file

    /**
     * Shows [userId]'s picture from disk at once — no network — and forgets any other account's.
     * Call it on sign-in; [refresh] fetches the current picture.
     */
    suspend fun show(userId: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            root.mkdirs()
            this@ProfileImage.userId = userId
            root.listFiles()?.filterNot { it.name.startsWith(prefix(userId)) }?.forEach { it.delete() }
            _file.value = cached(userId)
        }
    }

    /** Fetches the signed-in user's current picture; a no-op before [show]. */
    suspend fun refresh() = lock.withLock {
        val id = userId ?: return@withLock
        withContext(Dispatchers.IO) {
            val body = try {
                download(id)
            } catch (e: HttpException) {
                if (e.code() == 404) forget(id)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext
            }

            root.mkdirs()
            val tmp = File(root, "${prefix(id)}download.tmp")
            val written = runCatching {
                body.use { it.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } } }
            }.isSuccess
            if (!written || tmp.length() == 0L) {
                tmp.delete()
                return@withContext
            }

            val current = cached(id)
            if (current != null && current.length() == tmp.length() && current.readBytes().contentEquals(tmp.readBytes())) {
                tmp.delete()
                return@withContext
            }
            // A name of its own (see the class doc), written via a temp file so an interrupted
            // download is never shown as though it were complete.
            // Always after the current one's, even within the same millisecond: an equal name would
            // overwrite it and then be deleted as the old file.
            val stamp = maxOf(System.currentTimeMillis(), (current?.let { stampOf(id, it) } ?: 0L) + 1)
            val target = File(root, "${prefix(id)}$stamp.img")
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext
            }
            current?.delete()
            _file.value = target
        }
    }

    /** Forgets the picture and whose it was (sign-out): the next account must not see it. */
    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) {
            userId = null
            root.listFiles()?.forEach { it.delete() }
            _file.value = null
        }
    }

    private fun forget(userId: String) {
        root.listFiles()?.filter { it.name.startsWith(prefix(userId)) }?.forEach { it.delete() }
        _file.value = null
    }

    /** The newest complete picture on disk for [userId]. */
    private fun cached(userId: String): File? =
        root.listFiles()
            ?.filter { it.name.startsWith(prefix(userId)) && it.name.endsWith(".img") && it.length() > 0 }
            ?.maxByOrNull { stampOf(userId, it) ?: 0L }

    private fun stampOf(userId: String, file: File): Long? =
        file.name.removePrefix(prefix(userId)).removeSuffix(".img").toLongOrNull()

    private fun prefix(userId: String) = "${userId}_"
}

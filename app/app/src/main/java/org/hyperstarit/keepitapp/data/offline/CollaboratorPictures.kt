package org.hyperstarit.keepitapp.data.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File

/**
 * The profile pictures of the people on shared notes — the faces on their cards — kept on disk so
 * they show offline, like the note images in [MediaCache].
 *
 * Keyed by user and [org.hyperstarit.keepitapp.data.NoteCollaboratorDto.profileImageVersion], which
 * the server changes with every new picture: a file here is never stale, only replaced by the next
 * version, and a person without a picture (a null version) is never asked for one. A picture that
 * can't be fetched now (offline, a 404 from a race with a removal) is simply not there, and the
 * face shows the person's initial. [retain] drops everyone who is no longer on any note.
 *
 * Takes a [File] root and the download as a function, so the rules are unit-testable on the JVM.
 */
class CollaboratorPictures(
    private val root: File,
    private val download: suspend (userId: String) -> ResponseBody,
) {
    private val lock = Mutex()

    /**
     * [userId]'s picture at [version], from disk or else downloaded first; null when it can't be had
     * now. A server-sent id or version that isn't a plain token is never used as a file name.
     */
    suspend fun file(userId: String, version: String): File? = withContext(Dispatchers.IO) {
        val name = nameOf(userId, version) ?: return@withContext null
        val target = File(root, name)
        if (target.length() > 0) return@withContext target

        lock.withLock {
            // Another face of the same person may have fetched it while this one waited.
            if (target.length() > 0) return@withLock target
            val body = try {
                download(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock null
            }

            root.mkdirs()
            // Via a temp file, so an interrupted download is never shown as though it were complete.
            val tmp = File(root, "$name.tmp")
            val written = runCatching {
                body.use { it.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } } }
            }.isSuccess
            if (!written || tmp.length() == 0L || !tmp.renameTo(target)) {
                tmp.delete()
                return@withLock null
            }
            // The new version replaces every older one of the same person.
            root.listFiles()?.filter { it.name.startsWith("${userId}_") && it.name != name }?.forEach { it.delete() }
            target
        }
    }

    /** Keeps the pictures of [keep] (user id to current version) and deletes every other file. */
    suspend fun retain(keep: Map<String, String>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val names = keep.mapNotNullTo(HashSet()) { (userId, version) -> nameOf(userId, version) }
            root.listFiles()?.filterNot { it.name in names }?.forEach { it.delete() }
        }
    }

    /** Deletes every picture (sign-out): the next account must not find them. */
    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) { root.listFiles()?.forEach { it.delete() } }
    }

    private fun nameOf(userId: String, version: String): String? =
        if (TOKEN.matches(userId) && TOKEN.matches(version)) "${userId}_$version.img" else null

    private companion object {
        /** A user id is a GUID and a version a file name stem: letters, digits and dashes. */
        val TOKEN = Regex("[A-Za-z0-9-]{1,64}")
    }
}

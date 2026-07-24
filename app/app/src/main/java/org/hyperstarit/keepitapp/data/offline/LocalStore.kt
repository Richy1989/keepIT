package org.hyperstarit.keepitapp.data.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteDto
import java.io.File

/** Everything the app persists for offline use, in one snapshot keyed by the signed-in user. */
@Serializable
data class CacheSnapshot(
    val userId: String = "",
    val notes: List<NoteDto> = emptyList(),
    val lists: List<ListDto> = emptyList(),
    val lastSyncUtc: String = "",
)

/**
 * Disk persistence for the offline cache and the mutation outbox: two JSON files under
 * `filesDir/offline/`, written atomically (temp file + rename) so a crash mid-write can never
 * corrupt the previous good copy. Deliberately not Room — the whole dataset already lives in
 * memory as `StateFlow<List<NoteDto>>` and is personal-note-scale, so indexed queries buy nothing,
 * while kotlinx-serialization is already on the classpath. If the store ever outgrows this, swap
 * the implementation behind these five methods for a database without touching callers.
 */
class LocalStore(context: Context) {

    private val dir = File(context.filesDir, "offline")
    private val cacheFile = File(dir, "cache.json")
    private val outboxFile = File(dir, "outbox.json")
    private val mutex = Mutex()

    /** Lenient like the API's Json so cached data from an older app version still decodes. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    suspend fun loadCache(): CacheSnapshot? = read(cacheFile) { json.decodeFromString<CacheSnapshot>(it) }

    suspend fun saveCache(snapshot: CacheSnapshot) = write(cacheFile, json.encodeToString(snapshot))

    suspend fun loadOutbox(): List<PendingOp> =
        read(outboxFile) { json.decodeFromString<List<PendingOp>>(it) } ?: emptyList()

    suspend fun saveOutbox(ops: List<PendingOp>) = write(outboxFile, json.encodeToString(ops))

    /** Wipes both files — sign-out or a different user signing in. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            cacheFile.delete()
            outboxFile.delete()
        }
    }

    private suspend fun <T> read(file: File, decode: (String) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock null
            // A corrupt file (e.g. incompatible old schema) is treated as absent, never fatal.
            runCatching { decode(file.readText()) }.getOrNull()
        }
    }

    private suspend fun write(file: File, content: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                // Rename over an existing file can fail on some filesystems — replace explicitly.
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

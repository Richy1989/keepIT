package org.hyperstarit.keepitapp.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the connected server can do, read from its version (`GET api/meta`). The app can be newer
 * than its server — F-Droid updates the app by itself, operators update servers by hand — so a
 * feature that needs a newer server than 1.0 asks here, and stays hidden on an older one saying the
 * server needs updating. Never by catching a 404: a 404 from a bug would then blame the server.
 *
 * [SyncEngine][org.hyperstarit.keepitapp.data.offline.SyncEngine] [refresh]es it at the start of
 * every sync, before it replays the outbox, so a queued op is shaped for the server it is about to
 * reach. The version is remembered per server address, so the app knows it offline and after a
 * restart, and forgets it when another server is connected.
 */
class ServerFeatures(context: Context, private val client: ApiClient) {
    private val prefs = context.getSharedPreferences("server_features", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(storedVersion())

    /** The server's version as `/api/meta` gave it (`1.3.0+abc1234`); null until it has been read. */
    val version: StateFlow<String?> = _version.asStateFlow()

    /**
     * Whether the server stores note backgrounds: true, false, or null while its version is unknown
     * (never synced with it yet).
     */
    val backgrounds: Boolean? get() = ServerVersions.atLeast(currentVersion(), BACKGROUNDS)

    /** Reads the version from the server; keeps the one it had when the server can't be reached. */
    suspend fun refresh() {
        val url = client.baseUrl ?: return
        val version = try {
            client.api.meta().version.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        prefs.edit().putString(KEY_URL, url).putString(KEY_VERSION, version).apply()
        _version.value = version
    }

    /** The stored version, if it is the current server's. */
    private fun currentVersion(): String? = storedVersion().also { if (it != _version.value) _version.value = it }

    private fun storedVersion(): String? =
        prefs.getString(KEY_VERSION, null)?.takeIf { prefs.getString(KEY_URL, null) == client.baseUrl }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_VERSION = "version"

        /** The first release that stores a note's background ([NoteDto.background]). */
        val BACKGROUNDS = Triple(1, 3, 0)

        /**
         * The first release that stores a checklist row's indent ([ChecklistItemDto.indent]). An
         * older server reads every row as top-level and drops the nesting on the next save.
         */
        val SUBITEMS = Triple(1, 4, 0)
    }
}

/** Server version arithmetic, free of Android types for the JVM tests. */
object ServerVersions {
    /**
     * Whether [version] is at least [min]; null when [version] is null or can't be read. A beta
     * (`1.3.0-beta.2`) counts as its release, since betas are built from `main` with everything the
     * release will hold; a development build (`0.0.0-dev+sha`) counts as newer than any release.
     */
    fun atLeast(version: String?, min: Triple<Int, Int, Int>): Boolean? {
        val core = version?.substringBefore('+')?.substringBefore('-') ?: return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 3) return null
        val (major, minor, patch) = parts
        if (major == 0 && minor == 0 && patch == 0) return true
        return compareValuesBy(Triple(major, minor, patch), min, { it.first }, { it.second }, { it.third }) >= 0
    }
}

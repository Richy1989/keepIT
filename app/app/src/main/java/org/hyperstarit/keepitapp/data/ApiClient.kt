package org.hyperstarit.keepitapp.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.time.Instant

/**
 * Outcome of a refresh-token call. The distinction matters offline: only a server that actually
 * *rejected* the cookie may end the session — an unreachable server is a connectivity problem and
 * must never sign the user out (or destroy the refresh cookie).
 */
enum class RefreshResult { SUCCESS, REJECTED, NETWORK_ERROR }

/**
 * In-memory holder for the short-lived JWT access token — the Android twin of the web's
 * `tokenStore.ts`. Deliberately never persisted; on process restart the session is restored via
 * the refresh cookie (see [PersistentCookieJar]).
 */
class TokenStore {
    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    private var expiresAtMs: Long = 0

    fun set(token: String, expiresAtUtc: String) {
        accessToken = token
        expiresAtMs = runCatching { Instant.parse(ensureUtc(expiresAtUtc)).toEpochMilli() }.getOrDefault(0)
    }

    fun clear() {
        accessToken = null
        expiresAtMs = 0
    }

    /** True when there's no token or it expires within [skewMs] — refresh proactively. */
    fun isExpiringSoon(skewMs: Long = 30_000): Boolean =
        accessToken == null || System.currentTimeMillis() > expiresAtMs - skewMs
}

/**
 * Backend UTC timestamps from the SQLite dev provider can lack a zone designator; Postgres emits a
 * trailing `Z`. Normalize so `Instant.parse` (and display formatting) always treat them as UTC —
 * the same fix the web client applies in `NoteCard.formatDate`.
 */
fun ensureUtc(iso: String): String =
    if (Regex("[zZ]$|[+-]\\d\\d:?\\d\\d$").containsMatchIn(iso)) iso else iso + "Z"

/**
 * Persists the API's cookies (the httpOnly refresh token) across process restarts in app-private
 * SharedPreferences, so a signed-in session survives app restarts — the mobile analogue of the
 * browser holding the refresh cookie. The access token itself stays memory-only.
 */
class PersistentCookieJar(private val prefs: SharedPreferences) : CookieJar {

    private val lock = Any()
    private var cookies: MutableList<Cookie> = load()

    private fun load(): MutableList<Cookie> {
        val entries = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        return entries.mapNotNull { entry ->
            // Stored as "<scheme>://<host>|<Set-Cookie string>"; parse against the origin URL.
            val sep = entry.indexOf('|')
            if (sep <= 0) return@mapNotNull null
            val url = entry.substring(0, sep).toHttpUrlOrNull() ?: return@mapNotNull null
            Cookie.parse(url, entry.substring(sep + 1))
        }.filter { it.expiresAt > now }.toMutableList()
    }

    private fun persist() {
        val entries = cookies.map { c ->
            val scheme = if (c.secure) "https" else "http"
            "$scheme://${c.domain}|$c"
        }.toSet()
        prefs.edit().putStringSet(KEY, entries).apply()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            for (incoming in cookies) {
                this.cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt > System.currentTimeMillis()) this.cookies.add(incoming)
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        cookies.filter { it.matches(url) }
    }

    fun clear() = synchronized(lock) {
        cookies.clear()
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "cookies"
    }
}

/**
 * Owns the HTTP stack for the currently configured server: the OkHttp client (cookie jar + bearer
 * attachment + 401 retry), the Retrofit [KeepItApi], and the single-flight token refresh. The base
 * URL is user-entered at login and persisted; [configure] rebuilds the stack when it changes.
 */
class ApiClient(context: Context) {

    private val prefs = context.getSharedPreferences("keepit_net", Context.MODE_PRIVATE)
    val tokenStore = TokenStore()
    private val cookieJar = PersistentCookieJar(prefs)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Volatile
    var baseUrl: String? = prefs.getString("server_url", null)
        private set

    @Volatile
    private var apiInstance: KeepItApi? = null

    /** The typed API for the configured server. Only valid after [configure] (or a restored URL). */
    val api: KeepItApi
        get() = apiInstance ?: buildApi(baseUrl ?: error("Server URL not configured"))

    /** Refresh must not run concurrently — the cookie rotates on every call. */
    private val refreshLock = Any()

    /** Bare client for the refresh call itself: shares the cookie jar, has no auth interceptor. */
    private val bareClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .build()

    /** Normalizes, persists, and switches to a server URL, rebuilding the Retrofit stack. */
    fun configure(serverUrl: String): String {
        val normalized = normalizeServerUrl(serverUrl)
        prefs.edit().putString("server_url", normalized).apply()
        baseUrl = normalized
        buildApi(normalized)
        return normalized
    }

    private fun buildApi(base: String): KeepItApi {
        val client = bareClient.newBuilder()
            .addInterceptor(AuthInterceptor())
            .authenticator(TokenAuthenticator())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(KeepItApi::class.java).also { apiInstance = it }
    }

    /**
     * Calls `POST /api/auth/refresh` on the bare client (cookie-authenticated, rotating the cookie)
     * and stores the fresh access token. Single-flight: concurrent callers serialize on the lock and
     * the second caller returns immediately if the first already produced a fresh token. Only a
     * **401** counts as [RefreshResult.REJECTED] — the server saying the cookie itself is
     * missing/expired/revoked. Anything else (429, 5xx, unreachable server) is
     * [RefreshResult.NETWORK_ERROR]: the refresh cookie is still valid, so the session must
     * survive the blip exactly as it survives being offline.
     */
    fun refreshBlocking(): RefreshResult {
        val base = baseUrl ?: return RefreshResult.REJECTED
        synchronized(refreshLock) {
            if (!tokenStore.isExpiringSoon()) return RefreshResult.SUCCESS
            val request = Request.Builder()
                .url(base + "api/auth/refresh")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            return try {
                bareClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> {
                            tokenStore.clear()
                            RefreshResult.REJECTED
                        }

                        !response.isSuccessful -> RefreshResult.NETWORK_ERROR

                        else -> {
                            val body = response.body?.string() ?: return RefreshResult.NETWORK_ERROR
                            val auth = json.decodeFromString<AuthResponseDto>(body)
                            tokenStore.set(auth.accessToken, auth.accessTokenExpiresAtUtc)
                            RefreshResult.SUCCESS
                        }
                    }
                }
            } catch (_: Exception) {
                RefreshResult.NETWORK_ERROR
            }
        }
    }

    // ---- last-user snapshot (offline session restore) ----

    /** Remembers who is signed in, so an offline app start can restore the session locally. */
    fun saveLastUser(user: UserDto) {
        prefs.edit().putString("last_user", json.encodeToString(UserDto.serializer(), user)).apply()
    }

    fun loadLastUser(): UserDto? {
        val raw = prefs.getString("last_user", null) ?: return null
        return runCatching { json.decodeFromString<UserDto>(raw) }.getOrNull()
    }

    /** Explicit sign-out only — session *expiry* keeps it so offline restore still works. */
    fun clearLastUser() {
        prefs.edit().remove("last_user").apply()
    }

    /** Drops the in-memory token and the persisted refresh cookie (sign-out / session expiry). */
    fun clearSession() {
        tokenStore.clear()
        cookieJar.clear()
    }

    /** Routes that establish the session — never carry a bearer token or trigger a refresh. */
    private fun isAuthFree(url: HttpUrl): Boolean {
        val p = url.encodedPath
        return p.endsWith("/api/auth/login") || p.endsWith("/api/auth/register") || p.endsWith("/api/auth/refresh")
    }

    /** Attaches the bearer token, refreshing proactively when it's about to expire. */
    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (isAuthFree(request.url)) return chain.proceed(request)
            if (tokenStore.isExpiringSoon()) refreshBlocking()
            val token = tokenStore.accessToken ?: return chain.proceed(request)
            return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        }
    }

    /**
     * On a 401, refreshes once and retries; a second 401 (or a *rejected* refresh) gives up. A
     * refresh that failed only because the server was unreachable throws instead — the call then
     * fails as an IOException (transient, retried by the sync engine later), not as a 401 that
     * would wrongly end the session.
     */
    private inner class TokenAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (isAuthFree(response.request.url)) return null
            if (response.priorResponse != null) return null // already retried once
            when (refreshBlocking()) {
                RefreshResult.SUCCESS -> Unit
                RefreshResult.REJECTED -> return null
                RefreshResult.NETWORK_ERROR -> throw IOException("Token refresh failed: server unreachable")
            }
            val token = tokenStore.accessToken ?: return null
            return response.request.newBuilder().header("Authorization", "Bearer $token").build()
        }
    }

    companion object {
        /**
         * Makes a user-entered address usable as a Retrofit base URL: adds a scheme when missing
         * (http, since self-hosted LAN/emulator setups are the common bare-address case) and the
         * required trailing slash.
         */
        fun normalizeServerUrl(input: String): String {
            var url = input.trim()
            if (!url.contains("://")) url = "http://$url"
            if (!url.endsWith("/")) url += "/"
            return url
        }
    }
}

/**
 * A human-readable message from an API error, mirroring the web's `apiError.ts`: prefers the
 * `{error}` shape, then ProblemDetails (`detail`/`title`), then validation `errors` values.
 */
fun apiErrorMessage(t: Throwable, fallback: String): String {
    if (t !is HttpException) return t.message ?: fallback
    return try {
        val body = t.response()?.errorBody()?.string() ?: return fallback
        val obj = Json.parseToJsonElement(body).jsonObject
        obj["error"]?.jsonPrimitive?.content
            ?: obj["detail"]?.jsonPrimitive?.content
            ?: obj["title"]?.jsonPrimitive?.content
            ?: fallback
    } catch (_: Exception) {
        fallback
    }
}

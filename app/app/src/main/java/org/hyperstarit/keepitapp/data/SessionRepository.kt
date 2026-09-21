package org.hyperstarit.keepitapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** The app's sign-in state. `Loading` only during the initial cookie-restore on launch. */
sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: UserDto) : SessionState

    /** No server and no account: everything stays on this device (see [AppMode]). */
    data object Standalone : SessionState
}

/**
 * Owns the session, mirroring the web's `AuthProvider`: restores it from the persisted refresh
 * cookie on launch, exposes login/register/logout, and drops to SignedOut when a refresh becomes
 * impossible. The access token lives in [ApiClient]'s in-memory [TokenStore], never on disk.
 *
 * Offline-aware: when the server is *unreachable* (as opposed to rejecting the cookie), [restore]
 * signs in on the last-known user so the offline cache is usable; the first successful network
 * call then acquires a real token through the normal refresh path.
 *
 * Also owns the way in and out of **standalone** mode ([AppMode]): [startStandalone] enters it with
 * no server at all; a later [login] or [register] leaves it by connecting one, readying the local
 * notes for upload first ([onLeavingStandalone]); [logout] from standalone erases the device.
 */
class SessionRepository(private val client: ApiClient, private val mode: AppMode) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    /** The last-used server URL, for prefilling the login form. */
    val serverUrl: String? get() = client.baseUrl

    /** Wired by the app container: flushes pending offline changes + clears the local store. */
    var onLogout: (suspend () -> Unit)? = null

    /**
     * Wired by the app container: readies the standalone notes for upload into the account being
     * connected. Runs once an account has accepted the credentials but *before* the mode flips, so
     * the sync engine never sees the queue in its standalone form.
     */
    var onLeavingStandalone: (suspend () -> Unit)? = null

    /**
     * Bootstrap: a persisted refresh cookie silently restores the session (survives restarts).
     *
     * Runs from the composition, so an activity recreation — a rotation, the system switching to
     * dark mode, the app being backgrounded — can cancel it mid-call. That cancellation is left to
     * propagate ([orNullUnlessCancelled]) instead of being read as a failed call: the session stays
     * as it was and the next composition restores it, rather than a disposed bootstrap dropping a
     * signed-in user on the sign-in screen.
     */
    suspend fun restore() {
        if (mode.isStandalone) {
            _state.value = SessionState.Standalone
            return
        }
        val base = client.baseUrl
        if (base == null) {
            _state.value = SessionState.SignedOut
            return
        }
        client.configure(base)

        val refresh = withContext(Dispatchers.IO) { client.refreshBlocking() }
        val user = if (refresh == RefreshResult.SUCCESS) {
            orNullUnlessCancelled { client.api.me() }?.also { client.saveLastUser(it) }
        } else {
            null
        }
        // The last-known user is only worth reading when there's no fresh one to prefer.
        val cached = if (user == null) client.loadLastUser() else null
        _state.value = restoredState(refresh, user, cached)
    }

    suspend fun login(serverUrl: String, email: String, password: String): Result<Unit> =
        authenticate(serverUrl) { client.api.login(LoginRequestDto(email, password)) }

    suspend fun register(serverUrl: String, email: String, password: String, displayName: String?): Result<Unit> =
        authenticate(serverUrl) {
            client.api.register(RegisterRequestDto(email, password, displayName?.takeIf { it.isNotBlank() }))
        }

    /**
     * Requests a password-reset link for [email]. No session is involved — the server always
     * answers 204 (it never reveals whether the address is registered), and the reset itself is
     * completed in the browser via the emailed link. The user then signs in here with the new
     * password.
     */
    suspend fun requestPasswordReset(serverUrl: String, email: String): Result<Unit> =
        runCatching {
            client.configure(serverUrl)
            client.api.forgotPassword(ForgotPasswordRequestDto(email))
        }

    /**
     * Changes the signed-in user's password. On success the server revokes every other device's
     * session and returns fresh tokens for this one — store them so this device stays signed in.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> =
        runCatching {
            val response = client.api.changePassword(ChangePasswordRequestDto(currentPassword, newPassword))
            client.tokenStore.set(response.accessToken, response.accessTokenExpiresAtUtc)
            client.saveLastUser(response.user)
        }

    /** Enters standalone mode: no server, no account — the notes screen opens straight away. */
    fun startStandalone() {
        mode.setStandalone(true)
        _state.value = SessionState.Standalone
    }

    private suspend fun authenticate(serverUrl: String, call: suspend () -> AuthResponseDto): Result<Unit> =
        runCatching {
            client.configure(serverUrl)
            val response = call()
            client.tokenStore.set(response.accessToken, response.accessTokenExpiresAtUtc)
            client.saveLastUser(response.user)
            // Connecting a server from standalone mode: the local notes go to this account.
            if (mode.isStandalone) {
                onLeavingStandalone?.invoke()
                mode.setStandalone(false)
            }
            _state.value = SessionState.SignedIn(response.user)
        }

    /**
     * Signs out: best-effort flush of pending offline changes, revoke the refresh token
     * server-side, then clear every local trace (token, cookie, cached user, offline store).
     *
     * From standalone mode this *erases the device's notes* — there is no server holding a copy —
     * and returns to the sign-in screen. The mode is left only after the wipe, so the flush inside
     * [onLogout] stays a no-op instead of replaying the standalone queue at some old server URL.
     */
    suspend fun logout() {
        val wasStandalone = mode.isStandalone
        runCatching { onLogout?.invoke() }
        if (!wasStandalone) runCatching { client.api.logout() }
        client.clearSession()
        client.clearLastUser()
        if (wasStandalone) mode.setStandalone(false)
        _state.value = SessionState.SignedOut
    }

    /**
     * Called when a request ends 401 with no recoverable refresh — the session is gone. The
     * offline store and cached user are deliberately *kept*: signing back in as the same user
     * resumes the queued changes (a different user wipes them on sign-in).
     */
    fun sessionExpired() {
        client.clearSession()
        _state.value = SessionState.SignedOut
    }
}

/**
 * Runs [block], reporting an ordinary failure as "no answer" (`null`) — but never a cancellation,
 * which is rethrown.
 *
 * `runCatching` is the obvious shorthand here and is the bug this replaces: it catches
 * [CancellationException] like any other failure, so a bootstrap cancelled by an activity
 * recreation came back looking like a failed `me` call and signed a perfectly valid session out.
 */
internal suspend fun <T> orNullUnlessCancelled(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/**
 * The session a bootstrap lands in. A refresh the server *answered* has proved the session, so a
 * blip fetching the profile afterwards falls back to the last-known user — the same fallback an
 * unreachable server gets. Only a rejected cookie ends the session: it is the one answer that means
 * the refresh token itself is gone.
 */
internal fun restoredState(refresh: RefreshResult, user: UserDto?, cached: UserDto?): SessionState =
    when (refresh) {
        RefreshResult.SUCCESS, RefreshResult.NETWORK_ERROR ->
            (user ?: cached)?.let { SessionState.SignedIn(it) } ?: SessionState.SignedOut

        RefreshResult.REJECTED -> SessionState.SignedOut
    }

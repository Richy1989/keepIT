package org.spaceelephant.keepitapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/** The app's sign-in state. `Loading` only during the initial cookie-restore on launch. */
sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: UserDto) : SessionState
}

/**
 * Owns the session, mirroring the web's `AuthProvider`: restores it from the persisted refresh
 * cookie on launch, exposes login/register/logout, and drops to SignedOut when a refresh becomes
 * impossible. The access token lives in [ApiClient]'s in-memory [TokenStore], never on disk.
 *
 * Offline-aware: when the server is *unreachable* (as opposed to rejecting the cookie), [restore]
 * signs in on the last-known user so the offline cache is usable; the first successful network
 * call then acquires a real token through the normal refresh path.
 */
class SessionRepository(private val client: ApiClient) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    /** The last-used server URL, for prefilling the login form. */
    val serverUrl: String? get() = client.baseUrl

    /** Wired by the app container: flushes pending offline changes + clears the local store. */
    var onLogout: (suspend () -> Unit)? = null

    /** Bootstrap: a persisted refresh cookie silently restores the session (survives restarts). */
    suspend fun restore() {
        val base = client.baseUrl
        if (base == null) {
            _state.value = SessionState.SignedOut
            return
        }
        client.configure(base)
        when (withContext(Dispatchers.IO) { client.refreshBlocking() }) {
            RefreshResult.SUCCESS ->
                runCatching { client.api.me() }
                    .onSuccess { user ->
                        client.saveLastUser(user)
                        _state.value = SessionState.SignedIn(user)
                    }
                    .onFailure { t ->
                        // The refresh worked, so only a network blip can justify falling back.
                        val cached = if (t is IOException) client.loadLastUser() else null
                        _state.value = cached?.let { SessionState.SignedIn(it) } ?: SessionState.SignedOut
                    }

            RefreshResult.REJECTED -> _state.value = SessionState.SignedOut

            RefreshResult.NETWORK_ERROR -> {
                val cached = client.loadLastUser()
                _state.value = cached?.let { SessionState.SignedIn(it) } ?: SessionState.SignedOut
            }
        }
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

    private suspend fun authenticate(serverUrl: String, call: suspend () -> AuthResponseDto): Result<Unit> =
        runCatching {
            client.configure(serverUrl)
            val response = call()
            client.tokenStore.set(response.accessToken, response.accessTokenExpiresAtUtc)
            client.saveLastUser(response.user)
            _state.value = SessionState.SignedIn(response.user)
        }

    /**
     * Signs out: best-effort flush of pending offline changes, revoke the refresh token
     * server-side, then clear every local trace (token, cookie, cached user, offline store).
     */
    suspend fun logout() {
        runCatching { onLogout?.invoke() }
        runCatching { client.api.logout() }
        client.clearSession()
        client.clearLastUser()
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

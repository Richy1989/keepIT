package org.spaceelephant.keepitapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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
 */
class SessionRepository(private val client: ApiClient) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    /** The last-used server URL, for prefilling the login form. */
    val serverUrl: String? get() = client.baseUrl

    /** Bootstrap: a persisted refresh cookie silently restores the session (survives restarts). */
    suspend fun restore() {
        val base = client.baseUrl
        if (base == null) {
            _state.value = SessionState.SignedOut
            return
        }
        client.configure(base)
        val refreshed = withContext(Dispatchers.IO) { client.refreshBlocking() }
        if (!refreshed) {
            _state.value = SessionState.SignedOut
            return
        }
        runCatching { client.api.me() }
            .onSuccess { _state.value = SessionState.SignedIn(it) }
            .onFailure { _state.value = SessionState.SignedOut }
    }

    suspend fun login(serverUrl: String, email: String, password: String): Result<Unit> =
        authenticate(serverUrl) { client.api.login(LoginRequestDto(email, password)) }

    suspend fun register(serverUrl: String, email: String, password: String, displayName: String?): Result<Unit> =
        authenticate(serverUrl) {
            client.api.register(RegisterRequestDto(email, password, displayName?.takeIf { it.isNotBlank() }))
        }

    private suspend fun authenticate(serverUrl: String, call: suspend () -> AuthResponseDto): Result<Unit> =
        runCatching {
            client.configure(serverUrl)
            val response = call()
            client.tokenStore.set(response.accessToken, response.accessTokenExpiresAtUtc)
            _state.value = SessionState.SignedIn(response.user)
        }

    /** Revokes the refresh token server-side (best effort) and clears all local session state. */
    suspend fun logout() {
        runCatching { client.api.logout() }
        client.clearSession()
        _state.value = SessionState.SignedOut
    }

    /** Called when a request ends 401 with no recoverable refresh — the session is gone. */
    fun sessionExpired() {
        client.clearSession()
        _state.value = SessionState.SignedOut
    }
}

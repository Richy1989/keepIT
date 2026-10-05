package org.hyperstarit.keepitapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.HttpException

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

    /** Wipes this phone's copy of an account the server has just deleted; see [deleteAccount]. */
    var onAccountDeleted: (suspend () -> Unit)? = null

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

    /**
     * Signs in. For an account with two-factor authentication on, the right password alone fails
     * with a [LoginRefusedException] whose [LoginRefusedException.twoFactorRequired] is set: ask for
     * the code and call again with [twoFactorCode]. Nothing is held between the two calls.
     */
    suspend fun login(serverUrl: String, email: String, password: String, twoFactorCode: String? = null): Result<Unit> =
        authenticate(serverUrl) {
            try {
                client.api.login(LoginRequestDto(email, password, twoFactorCode?.trim()?.takeIf { it.isNotEmpty() }))
            } catch (e: HttpException) {
                throw loginRefusal(e) ?: e
            }
        }

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
        resultUnlessCancelled {
            client.configure(serverUrl)
            client.api.forgotPassword(ForgotPasswordRequestDto(email))
        }

    /**
     * Changes the signed-in user's password. On success the server revokes every other device's
     * session and returns fresh tokens for this one — store them so this device stays signed in.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> =
        resultUnlessCancelled {
            val response = client.api.changePassword(ChangePasswordRequestDto(currentPassword, newPassword))
            client.tokenStore.set(response.accessToken, response.accessTokenExpiresAtUtc)
            client.saveLastUser(response.user)
        }

    /**
     * Deletes the account on the server, then everything of it on this phone. Online only, like
     * [changePassword]; nothing changes anywhere unless the server has deleted the account.
     *
     * The local wipe is [logout]'s without its flush: queued changes would only be refused by a
     * server that no longer has the account, and they are the account's data, which the user has
     * just asked to have deleted. [onAccountDeleted] clears the widget as well, since the notes it
     * shows no longer exist anywhere.
     */
    suspend fun deleteAccount(password: String): Result<Unit> {
        val result = resultUnlessCancelled { client.api.deleteAccount(DeleteAccountRequestDto(password)) }
        if (result.isSuccess) {
            withContext(NonCancellable) {
                runCatching { onAccountDeleted?.invoke() }
                client.clearSession()
                client.clearLastUser()
                _state.value = SessionState.SignedOut
            }
        }
        return result
    }

    /** Whether the account asks for an authenticator code at sign-in. Online only, like the rest of these. */
    suspend fun twoFactorStatus(): Result<TwoFactorStatusDto> =
        resultUnlessCancelled { client.api.twoFactorStatus() }

    /**
     * Starts setting up an authenticator app: a new key, to scan or type. Sign-in is unchanged until
     * [enableTwoFactor] confirms a code made from it.
     */
    suspend fun startTwoFactorSetup(password: String): Result<TwoFactorSetupDto> =
        resultUnlessCancelled { client.api.twoFactorSetup(TwoFactorSetupRequestDto(password)) }

    /** Turns two-factor on with a code from the app just set up; the answer is the first recovery codes. */
    suspend fun enableTwoFactor(code: String): Result<List<String>> =
        resultUnlessCancelled { client.api.twoFactorEnable(TwoFactorEnableRequestDto(code.trim())).codes }

    /** Turns two-factor off, with the password and a code from the app or a recovery code. */
    suspend fun disableTwoFactor(password: String, code: String): Result<Unit> =
        resultUnlessCancelled { client.api.twoFactorDisable(TwoFactorConfirmRequestDto(password, code.trim())) }

    /** A new set of recovery codes, replacing the old one; the password and a code are asked for again. */
    suspend fun newRecoveryCodes(password: String, code: String): Result<List<String>> =
        resultUnlessCancelled {
            client.api.twoFactorRecoveryCodes(TwoFactorConfirmRequestDto(password, code.trim())).codes
        }

    /**
     * Renames the signed-in user, or removes the name with blank text (the app then shows the
     * email). The server's answer becomes the signed-in user, so the drawer follows at once, and the
     * last-known user, so an offline launch shows the new name too. Online only, like
     * [changePassword]: an account setting rather than note data, so it has no place in the outbox.
     */
    suspend fun updateDisplayName(displayName: String): Result<Unit> =
        resultUnlessCancelled {
            applyUser(client.api.updateMe(UpdateProfileRequestDto(displayName.trim().ifEmpty { null })))
        }

    /**
     * Refetches the signed-in user after another device changed the account: the realtime
     * `account` push, or a reconnect that may have missed one. A failure keeps the user already
     * shown until the next try.
     */
    suspend fun refreshUser() {
        if (_state.value !is SessionState.SignedIn) return
        val result = resultUnlessCancelled { client.api.me() }
        result.onSuccess(::applyUser)
        // A 401 gets this far only once the refresh it set off was refused as well: the session is
        // over. Typically the account was deleted on another device, which is what sent the push.
        if ((result.exceptionOrNull() as? HttpException)?.code() == 401) sessionExpired()
    }

    /**
     * Makes [user] the signed-in one, but only while that same account is still signed in: an
     * answer landing after a sign-out, or a switch to another account, must not bring the old
     * session back. The new [SessionState.SignedIn] re-runs AppRoot's sign-in effect, which is
     * idempotent for the same user (and an unchanged user emits nothing at all).
     */
    private fun applyUser(user: UserDto) {
        val current = _state.value as? SessionState.SignedIn ?: return
        if (current.user.id != user.id) return
        client.saveLastUser(user)
        _state.value = SessionState.SignedIn(user)
    }

    /** Enters standalone mode: no server, no account — the notes screen opens straight away. */
    fun startStandalone() {
        mode.setStandalone(true)
        _state.value = SessionState.Standalone
    }

    /**
     * The shared tail of [login] and [register]: hand the credentials over, then make the answer
     * this device's session.
     *
     * The sign-in screen launches this from the composition, so an activity recreation can cancel
     * it. Until the server answers that is harmless and the cancellation simply propagates — a
     * sign-in that never happened, not a failed one. Past that point the session is real, so
     * applying it runs [NonCancellable]: a cancellation between [onLeavingStandalone] and the mode
     * flip would otherwise leave the device standalone while holding an account it had already
     * readied its notes for.
     */
    private suspend fun authenticate(serverUrl: String, call: suspend () -> AuthResponseDto): Result<Unit> {
        val response = try {
            client.configure(serverUrl)
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return Result.failure(t)
        }

        withContext(NonCancellable) {
            client.tokenStore.set(response.accessToken, response.accessTokenExpiresAtUtc)
            client.saveLastUser(response.user)
            // Connecting a server from standalone mode: the local notes go to this account.
            if (mode.isStandalone) {
                onLeavingStandalone?.invoke()
                mode.setStandalone(false)
            }
            _state.value = SessionState.SignedIn(response.user)
        }
        return Result.success(Unit)
    }

    /**
     * Signs out: best-effort flush of pending offline changes, revoke the refresh token
     * server-side, then clear every local trace (token, cookie, cached user, offline store).
     *
     * From standalone mode this *erases the device's notes* — there is no server holding a copy —
     * and returns to the sign-in screen. The mode is left only after the wipe, so the flush inside
     * [onLogout] stays a no-op instead of replaying the standalone queue at some old server URL.
     *
     * [NonCancellable] because this is launched from the settings screen: once a sign-out has begun
     * it has to run to the end, rather than an activity recreation leaving the token revoked
     * server-side with the local store still full, or the reverse. The `runCatching`s inside are
     * about a *failing* flush or revoke, which must not stop the wipe either.
     */
    suspend fun logout() {
        withContext(NonCancellable) {
            val wasStandalone = mode.isStandalone
            runCatching { onLogout?.invoke() }
            if (!wasStandalone) runCatching { client.api.logout() }
            client.clearSession()
            client.clearLastUser()
            if (wasStandalone) mode.setStandalone(false)
            _state.value = SessionState.SignedOut
        }
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
 * A sign-in the server refused (401), with its message. [twoFactorRequired] means the password was
 * right and the account also wants its authenticator code: the sign-in screen asks for it and tries
 * again. Not an [HttpException], since its body has been read here; [apiErrorMessage] shows the
 * message as it is.
 */
class LoginRefusedException(message: String, val twoFactorRequired: Boolean) : Exception(message)

private val loginJson = Json { ignoreUnknownKeys = true }

/** A sign-in's 401 as a [LoginRefusedException]; null for any other failure, which stays as it was. */
private fun loginRefusal(e: HttpException): LoginRefusedException? {
    if (e.code() != 401) return null
    return loginRefusal(runCatching { e.response()?.errorBody()?.string() }.getOrNull())
}

/**
 * Reads a sign-in's 401 body ([LoginFailureDto]). A body without one — an older server's, or none
 * at all — is a plain refusal of the credentials.
 */
internal fun loginRefusal(body: String?): LoginRefusedException {
    val failure = body?.let { runCatching { loginJson.decodeFromString<LoginFailureDto>(it) }.getOrNull() }
    return LoginRefusedException(
        failure?.error?.takeIf { it.isNotBlank() } ?: "Invalid email or password.",
        failure?.twoFactorRequired == true,
    )
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
 * [runCatching] for suspend work: an ordinary failure becomes a failed [Result], a cancellation is
 * rethrown. The same trap as in [orNullUnlessCancelled] — a screen torn down mid-call would
 * otherwise hand the caller "Job was cancelled" to show as if the server had refused.
 */
internal suspend fun <T> resultUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
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

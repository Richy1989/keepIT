package org.hyperstarit.keepitapp.data.offline

import kotlinx.serialization.SerializationException
import org.hyperstarit.keepitapp.data.RefreshFailedException
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Why the last sync could not get through, while the phone itself has a network — the line the
 * notes screen's status strip shows in place of a bare "Offline".
 *
 * Every one of these used to read "Offline", which sent people looking at the server when the
 * phone was the problem, or the other way round: a hostname the phone's DNS would not resolve, a
 * blocked app (Android reports that as a failed lookup too), a certificate the phone does not
 * trust, and a server answering 500 all looked exactly like airplane mode. A phone with no network
 * at all still says "Offline"; [ConnectivityMonitor.markOffline] drops the problem in that case,
 * since every request failing then is a symptom rather than a cause.
 */
sealed interface SyncProblem {
    /** One line for the status strip. */
    val message: String

    /**
     * The server's name did not resolve. The phone's DNS, Private DNS, a firewall app, or the app's
     * network access being switched off — Android reports a blocked app as a failed lookup.
     */
    data class HostNotFound(val host: String?) : SyncProblem {
        override val message get() = "Can't find ${host ?: "the server"} on this network"
    }

    /** The name resolved but nothing accepted the connection: the server is down, or the port is wrong. */
    data object Unreachable : SyncProblem {
        override val message = "Can't connect to the server"
    }

    /** Connected, but no answer in time. */
    data object TimedOut : SyncProblem {
        override val message = "The server isn't responding"
    }

    /** TLS failed — almost always a certificate the phone does not trust, or one for another name. */
    data object Certificate : SyncProblem {
        override val message = "The server's certificate isn't trusted"
    }

    /** 429: the server's rate limiter turned this device away for now. */
    data object RateLimited : SyncProblem {
        override val message = "The server is limiting requests (HTTP 429)"
    }

    /**
     * The server answered, with a status that is neither success nor one of the refusals the engine
     * handles per op. A 5xx is the server's own error; anything else usually means the address
     * reaches something that is not keepIT's API.
     */
    data class HttpError(val code: Int) : SyncProblem {
        override val message
            get() = if (code >= 500) "Server error (HTTP $code)" else "Unexpected answer from the server (HTTP $code)"
    }

    /**
     * Renewing the sign-in got an answer that was neither a new token nor a refusal. Kept apart from
     * [HttpError] because the request that failed is the refresh, not the sync — and a 401 there
     * would have signed the user out instead.
     */
    data class SignInFailed(val code: Int) : SyncProblem {
        override val message = "Couldn't renew the sign-in (HTTP $code)"
    }

    /**
     * A successful status with a body that is not keepIT's JSON — a captive portal's login page, or
     * an address that reaches the web app's HTML instead of the API.
     */
    data object UnreadableResponse : SyncProblem {
        override val message = "Unexpected answer from the server"
    }

    /** Anything else: not the network and not the server, so most likely a bug. */
    data object Unexpected : SyncProblem {
        override val message = "Sync failed unexpectedly"
    }
}

/**
 * Classifies what stopped a sync. [host] is the configured server's hostname, named in
 * [SyncProblem.HostNotFound] so a mistyped or stale address shows up for what it is.
 *
 * The cause chain is searched rather than the top exception alone: a failed lookup or a certificate
 * error can arrive wrapped (a TLS handshake exception around the certificate one, a failed token
 * refresh around whatever stopped it).
 */
fun syncProblemOf(t: Throwable, host: String?): SyncProblem {
    if (t is RefreshFailedException) {
        t.httpCode?.let { return SyncProblem.SignInFailed(it) }
        return t.cause?.let { syncProblemOf(it, host) } ?: SyncProblem.Unexpected
    }
    if (t is HttpException) {
        return if (t.code() == 429) SyncProblem.RateLimited else SyncProblem.HttpError(t.code())
    }
    // Bounded: a cause chain can loop, and a real one is never this deep.
    val chain = generateSequence(t) { it.cause }.take(16).toList()
    return when {
        chain.any { it is UnknownHostException } -> SyncProblem.HostNotFound(host)
        chain.any { it is SSLException || it is CertificateException } -> SyncProblem.Certificate
        // SocketTimeoutException is one, and so is OkHttp's whole-call timeout.
        chain.any { it is InterruptedIOException } -> SyncProblem.TimedOut
        chain.any { it is SerializationException } -> SyncProblem.UnreadableResponse
        t is IOException -> SyncProblem.Unreachable
        else -> SyncProblem.Unexpected
    }
}

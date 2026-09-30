package org.hyperstarit.keepitapp.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Tracks whether the server is likely reachable. The OS network callback gives the fast signal
 * (airplane mode, Wi-Fi drop) — but a validated network can still fail to reach a self-hosted LAN
 * server, so request outcomes are authoritative: the sync engine calls [markOnline]/[markOffline]
 * from actual successes and IOExceptions, and the flag flips on whichever signal arrives first.
 *
 * Offline with a network means something specific went wrong, and [problem] says what — a name
 * that won't resolve, a certificate, a server error — so the status strip names it rather than
 * blaming the network.
 */
class ConnectivityMonitor(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(hasInternet())
    val isOnline: StateFlow<Boolean> = _isOnline

    private val _problem = MutableStateFlow<SyncProblem?>(null)

    /**
     * Why the server is out of reach although the phone has a network; null while online, and while
     * the phone has no network at all, where "offline" is the whole story.
     */
    val problem: StateFlow<SyncProblem?> = _problem

    /** Fired on an offline→online transition — the container wires this to the sync engine. */
    var onOnline: (() -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = markOnline()
        override fun onLost(network: Network) {
            if (!hasInternet()) {
                _problem.value = null
                _isOnline.value = false
            }
        }
    }

    fun start() {
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
    }

    fun markOnline() {
        _problem.value = null
        if (!_isOnline.getAndUpdate { true }) onOnline?.invoke()
    }

    /**
     * A request failed without reaching the server's data. [problem] is kept only while the phone
     * has a network: with none, every request fails — a lookup first, so it would read as
     * [SyncProblem.HostNotFound] — and that is a symptom of being offline, not a separate fault.
     */
    fun markOffline(problem: SyncProblem) {
        _problem.value = if (hasInternet()) problem else null
        _isOnline.value = false
    }

    private fun hasInternet(): Boolean {
        val network = manager?.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

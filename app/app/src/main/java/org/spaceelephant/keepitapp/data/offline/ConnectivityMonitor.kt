package org.spaceelephant.keepitapp.data.offline

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
 */
class ConnectivityMonitor(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(hasInternet())
    val isOnline: StateFlow<Boolean> = _isOnline

    /** Fired on an offline→online transition — the container wires this to the sync engine. */
    var onOnline: (() -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = markOnline()
        override fun onLost(network: Network) {
            if (!hasInternet()) _isOnline.value = false
        }
    }

    fun start() {
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
    }

    fun markOnline() {
        if (!_isOnline.getAndUpdate { true }) onOnline?.invoke()
    }

    fun markOffline() {
        _isOnline.value = false
    }

    private fun hasInternet(): Boolean {
        val network = manager?.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

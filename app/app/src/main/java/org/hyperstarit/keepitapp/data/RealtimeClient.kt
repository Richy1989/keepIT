package org.hyperstarit.keepitapp.data

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bridges the API's SignalR `RealTimeHub` to the repositories — the Android twin of the web's
 * `RealtimeSync.tsx`. While signed in it holds one connection to `/api/realtime` (JWT via the
 * access-token provider, refreshed when stale) and forwards each `Changed(resources)` push to
 * [onChanged] so the matching caches refetch. The Java client has no automatic reconnect, so a
 * dropped connection is retried on a delay for as long as [start]ed.
 */
class RealtimeClient(
    private val client: ApiClient,
    private val scope: CoroutineScope,
    private val onChanged: (List<String>) -> Unit,
) {
    @Volatile
    private var hub: HubConnection? = null

    @Volatile
    private var shouldRun = false

    /** Fires after every successful (re)connect — wired to a sync so missed pushes are covered. */
    var onConnected: (() -> Unit)? = null

    fun start() {
        if (shouldRun) return
        shouldRun = true
        connect()
    }

    fun stop() {
        shouldRun = false
        val current = hub
        hub = null
        scope.launch(Dispatchers.IO) { runCatching { current?.stop()?.blockingAwait() } }
    }

    private fun connect() {
        val base = client.baseUrl ?: return
        val connection = HubConnectionBuilder.create(base + "api/realtime")
            .withAccessTokenProvider(Single.defer {
                // Browsers can't set WS headers and neither does this transport config — the token
                // rides the query string, so hand over a fresh one (refresh first if it's stale).
                if (client.tokenStore.isExpiringSoon()) client.refreshBlocking()
                Single.just(client.tokenStore.accessToken ?: "")
            })
            .build()

        connection.on("Changed", { resources: Array<String> ->
            onChanged(resources.toList())
        }, Array<String>::class.java)

        connection.onClosed {
            if (shouldRun) scope.launch {
                delay(RETRY_DELAY_MS)
                if (shouldRun) connect()
            }
        }

        hub = connection
        scope.launch(Dispatchers.IO) {
            val started = runCatching { connection.start().blockingAwait() }.isSuccess
            if (started) {
                // (Re)connected — anything that changed while the socket was down was missed.
                onConnected?.invoke()
            } else if (shouldRun) {
                delay(RETRY_DELAY_MS)
                if (shouldRun) connect()
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 4_000L
    }
}

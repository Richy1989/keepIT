package org.hyperstarit.keepitapp.data.offline

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides when [SyncEngine] needs another pass and when it may make one. Free of Android types, with
 * the clock passed in, so the JVM tests can drive it.
 *
 * **Another pass?** Every [request] (a kick: an edit, a SignalR push, a reconnect) bumps a counter.
 * Just before a pass fetches, it takes a [snapshot]. When the fetch succeeds, everything asked for
 * up to then is [covered], and another pass is due only if something was asked for since
 * ([hasUncovered]). This is what absorbs the echo of the device's own writes: the server pushes
 * `Changed` to *all* of the user's devices, this one included, and it sends that push before it
 * answers the write. So the echo arrives before the pass's fetch starts, the fetch already
 * holds the write, and no second pass follows. A push that arrives after the snapshot always gets a
 * pass, since nothing tells a late echo from another device's change, and skipping that change is
 * worse than an extra fetch.
 *
 * **May it?** After a 429, [rateLimited] records how long the server asked the device to wait;
 * [waitMs] is what is left of that. Passes don't start until it is over.
 */
class SyncGate(private val now: () -> Long) {
    private val requested = AtomicLong(0)

    @Volatile
    private var coveredUpTo = 0L

    @Volatile
    private var notBeforeMs = 0L

    /** Something wants a sync. */
    fun request() {
        requested.incrementAndGet()
    }

    /** Taken just before a fetch: what that fetch will cover if it succeeds. */
    fun snapshot(): Long = requested.get()

    /** The fetch that started at [snapshot] succeeded. */
    @Synchronized
    fun covered(snapshot: Long) {
        if (snapshot > coveredUpTo) coveredUpTo = snapshot
    }

    /** Whether anything was asked for that no successful fetch has covered yet. */
    val hasUncovered: Boolean get() = requested.get() > coveredUpTo

    /** The server answered 429 and asked for [waitMs]; no pass until that has passed. */
    @Synchronized
    fun rateLimited(waitMs: Long) {
        notBeforeMs = maxOf(notBeforeMs, now() + waitMs)
    }

    /** How much of the server's wait is left; 0 when a pass may start. */
    fun waitMs(): Long = (notBeforeMs - now()).coerceAtLeast(0)
}

/** The wait when a 429 doesn't say how long, or says something unreadable. */
const val DEFAULT_RETRY_AFTER_MS = 30_000L

private const val MIN_RETRY_AFTER_MS = 1_000L

/** Ten minutes: a longer wait would only be a misconfigured server, and edits would sit unsent. */
private const val MAX_RETRY_AFTER_MS = 600_000L

/**
 * Reads a `Retry-After` header: delay seconds (`30`) or an HTTP date. Missing or unreadable →
 * [DEFAULT_RETRY_AFTER_MS]; always held to between a second and ten minutes.
 */
fun retryAfterMillis(header: String?, nowMs: Long): Long {
    val value = header?.trim().orEmpty()
    val millis = value.toLongOrNull()?.let { it * 1000 }
        ?: runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs
        }.getOrNull()
        ?: DEFAULT_RETRY_AFTER_MS
    return millis.coerceIn(MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)
}

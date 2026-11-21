package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.offline.DEFAULT_RETRY_AFTER_MS
import org.hyperstarit.keepitapp.data.offline.SyncGate
import org.hyperstarit.keepitapp.data.offline.retryAfterMillis
import org.hyperstarit.keepitapp.data.reconnectDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the sync engine goes round again, and how long it waits after a 429. Every request counts
 * against the server's rate limit: a pass too many is what used to run devices into it, and one too
 * few would leave another device's change unseen, so both directions are pinned here.
 */
class SyncGateTest {

    private var clock = 1_000_000L
    private val gate = SyncGate { clock }

    @Test
    fun `a request before the fetch starts is covered by it`() {
        gate.request() // the edit's kick
        gate.request() // the server's echo of it, which arrives before the fetch
        val snapshot = gate.snapshot()
        gate.covered(snapshot)
        assertFalse(gate.hasUncovered)
    }

    @Test
    fun `a request after the fetch starts gets another pass`() {
        gate.request()
        val snapshot = gate.snapshot()
        gate.request() // another device's change, pushed while the fetch was out
        gate.covered(snapshot)
        assertTrue(gate.hasUncovered)
    }

    @Test
    fun `a failed fetch covers nothing`() {
        gate.request()
        gate.snapshot() // taken, but the fetch failed, so covered() is never called
        assertTrue(gate.hasUncovered)
    }

    @Test
    fun `an older fetch finishing late does not uncover a newer one`() {
        gate.request()
        val first = gate.snapshot()
        gate.request()
        val second = gate.snapshot()
        gate.covered(second)
        gate.covered(first)
        assertFalse(gate.hasUncovered)
    }

    @Test
    fun `a 429 holds passes back until its wait is over`() {
        assertEquals(0, gate.waitMs())
        gate.rateLimited(10_000)
        assertEquals(10_000, gate.waitMs())
        clock += 4_000
        assertEquals(6_000, gate.waitMs())
        clock += 6_000
        assertEquals(0, gate.waitMs())
    }

    @Test
    fun `a shorter wait does not cut a longer one short`() {
        gate.rateLimited(30_000)
        gate.rateLimited(5_000)
        assertEquals(30_000, gate.waitMs())
    }

    @Test
    fun `Retry-After in seconds`() {
        assertEquals(10_000, retryAfterMillis("10", clock))
        assertEquals(10_000, retryAfterMillis(" 10 ", clock))
    }

    @Test
    fun `Retry-After as an HTTP date`() {
        val now = 1_700_000_000_000L // Tue, 14 Nov 2023 22:13:20 GMT
        assertEquals(40_000, retryAfterMillis("Tue, 14 Nov 2023 22:14:00 GMT", now))
    }

    @Test
    fun `a missing or unreadable Retry-After waits the default`() {
        assertEquals(DEFAULT_RETRY_AFTER_MS, retryAfterMillis(null, clock))
        assertEquals(DEFAULT_RETRY_AFTER_MS, retryAfterMillis("", clock))
        assertEquals(DEFAULT_RETRY_AFTER_MS, retryAfterMillis("soon", clock))
    }

    @Test
    fun `Retry-After is held between a second and ten minutes`() {
        assertEquals(1_000, retryAfterMillis("0", clock))
        assertEquals(1_000, retryAfterMillis("-5", clock))
        assertEquals(1_000, retryAfterMillis("Tue, 14 Nov 2023 22:14:00 GMT", 1_800_000_000_000L)) // in the past
        assertEquals(600_000, retryAfterMillis("86400", clock))
    }

    @Test
    fun `reconnects back off from a second to half a minute`() {
        val middle = 0.5 // no jitter
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map { reconnectDelayMs(it, middle) })
        assertEquals(750, reconnectDelayMs(0, 0.0))
        assertEquals(37_499, reconnectDelayMs(20, 0.99999))
    }
}

package org.hyperstarit.keepitapp.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * The two rules the session bootstrap lives by. `restore()` itself needs a `Context` (prefs, the
 * HTTP stack), so the parts that decide *whether the user stays signed in* are split out and
 * pinned here instead.
 *
 * Both rules exist because the bootstrap runs from the composition: an activity recreation — a
 * rotation, the system switching to dark mode, the app going to the background — disposes it
 * mid-flight, and anything that reads a disposed bootstrap as "the session is gone" drops a
 * signed-in user on the sign-in screen.
 */
class SessionRestoreTest {

    private val fresh = UserDto(id = "u1", email = "user@example.com", displayName = "Fresh")
    private val cached = UserDto(id = "u1", email = "user@example.com", displayName = "Cached")

    // ---- cancellation is not a failure ----

    @Test
    fun `a call cancelled mid-flight never returns`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var returned = false

        val job = launch(Dispatchers.Default) {
            orNullUnlessCancelled {
                entered.complete(Unit)
                awaitCancellation()
            }
            returned = true
        }
        entered.await()
        job.cancelAndJoin()

        assertFalse(
            "a bootstrap disposed mid-flight must not read as a failed `me` call",
            returned,
        )
    }

    @Test
    fun `an ordinary failure is reported as no answer`() = runBlocking {
        assertNull(orNullUnlessCancelled<UserDto> { throw IOException("offline") })
        assertNull(orNullUnlessCancelled<UserDto> { error("HTTP 429") })
        assertEquals(fresh, orNullUnlessCancelled { fresh })
    }

    // ---- what the bootstrap's outcome means for the session ----

    @Test
    fun `a rejected cookie is the only thing that signs out`() {
        assertEquals(
            SessionState.SignedOut,
            restoredState(RefreshResult.REJECTED, user = null, cached = cached),
        )
    }

    @Test
    fun `a blip fetching the profile keeps the session the refresh just proved`() {
        assertEquals(
            SessionState.SignedIn(cached),
            restoredState(RefreshResult.SUCCESS, user = null, cached = cached),
        )
    }

    @Test
    fun `the fresh profile wins over the cached one`() {
        assertEquals(
            SessionState.SignedIn(fresh),
            restoredState(RefreshResult.SUCCESS, user = fresh, cached = cached),
        )
    }

    @Test
    fun `an unreachable server falls back to the last known user`() {
        assertEquals(
            SessionState.SignedIn(cached),
            restoredState(RefreshResult.NETWORK_ERROR, user = null, cached = cached),
        )
    }

    @Test
    fun `with nothing to fall back on the app asks for a sign-in`() {
        assertEquals(
            SessionState.SignedOut,
            restoredState(RefreshResult.NETWORK_ERROR, user = null, cached = null),
        )
    }
}

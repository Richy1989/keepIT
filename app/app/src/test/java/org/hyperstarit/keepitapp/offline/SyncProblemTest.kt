package org.hyperstarit.keepitapp.offline

import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.hyperstarit.keepitapp.data.RefreshFailedException
import org.hyperstarit.keepitapp.data.offline.SyncProblem
import org.hyperstarit.keepitapp.data.offline.syncProblemOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * What the status strip says when a sync can't get through. All of these used to read "Offline",
 * including a phone whose DNS had stopped resolving a server that was up the whole time — which
 * sent the search to the server.
 */
class SyncProblemTest {

    private val host = "keepit.example.com"

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    @Test
    fun `a name the phone can't resolve is named, not called offline`() {
        val problem = syncProblemOf(UnknownHostException("Unable to resolve host \"$host\""), host)
        assertEquals(SyncProblem.HostNotFound(host), problem)
        assertTrue(problem.message, host in problem.message)
    }

    @Test
    fun `a lookup failure is found under whatever wraps it`() {
        val wrapped = IOException("call failed", UnknownHostException(host))
        assertEquals(SyncProblem.HostNotFound(host), syncProblemOf(wrapped, host))
    }

    @Test
    fun `no server address still gives a sentence`() {
        assertEquals("Can't find the server on this network", syncProblemOf(UnknownHostException(), null).message)
    }

    @Test
    fun `transport failures are told apart`() {
        assertEquals(SyncProblem.Unreachable, syncProblemOf(ConnectException("Failed to connect"), host))
        assertEquals(SyncProblem.TimedOut, syncProblemOf(SocketTimeoutException("timeout"), host))
        // OkHttp's whole-call timeout is a bare InterruptedIOException.
        assertEquals(SyncProblem.TimedOut, syncProblemOf(InterruptedIOException("timeout"), host))
        assertEquals(SyncProblem.Unreachable, syncProblemOf(IOException("unexpected end of stream"), host))
    }

    @Test
    fun `an untrusted certificate is a certificate problem`() {
        val handshake = SSLHandshakeException("handshake failed").apply {
            initCause(CertificateException("Trust anchor for certification path not found."))
        }
        assertEquals(SyncProblem.Certificate, syncProblemOf(handshake, host))
    }

    @Test
    fun `the server's own answers keep their status`() {
        assertEquals(SyncProblem.HttpError(500), syncProblemOf(http(500), host))
        assertEquals(SyncProblem.RateLimited, syncProblemOf(http(429), host))
        assertEquals("Server error (HTTP 502)", syncProblemOf(http(502), host).message)
        // An address that reaches something other than the API: a proxy's 404, say.
        assertEquals("Unexpected answer from the server (HTTP 404)", syncProblemOf(http(404), host).message)
    }

    @Test
    fun `a body that isn't the API's JSON is an unexpected answer`() {
        // A captive portal's login page, or the web app's HTML served for an API path.
        assertEquals(
            SyncProblem.UnreadableResponse,
            syncProblemOf(SerializationException("Unexpected JSON token at offset 0"), host),
        )
    }

    @Test
    fun `a failed sign-in renewal says so, with the status it got`() {
        assertEquals(SyncProblem.SignInFailed(500), syncProblemOf(RefreshFailedException(httpCode = 500), host))
        assertEquals(SyncProblem.SignInFailed(429), syncProblemOf(RefreshFailedException(httpCode = 429), host))
    }

    @Test
    fun `a renewal that never reached the server is classified by its cause`() {
        assertEquals(
            SyncProblem.HostNotFound(host),
            syncProblemOf(RefreshFailedException(cause = UnknownHostException(host)), host),
        )
        // Not "can't connect": the refresh did connect, and got back something it couldn't read.
        assertEquals(
            SyncProblem.UnreadableResponse,
            syncProblemOf(RefreshFailedException(cause = SerializationException("bad body")), host),
        )
        assertEquals(SyncProblem.Unexpected, syncProblemOf(RefreshFailedException(), host))
    }

    @Test
    fun `anything that isn't the network or the server is unexpected`() {
        assertEquals(SyncProblem.Unexpected, syncProblemOf(IllegalStateException("Server URL not configured"), host))
    }
}

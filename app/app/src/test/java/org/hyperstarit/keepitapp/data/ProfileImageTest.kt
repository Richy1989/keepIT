package org.hyperstarit.keepitapp.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * The profile picture on disk. What matters is what each answer from the server does to it: a
 * picture replaces it, a 404 removes it, and a failure leaves it — a flaky connection must never
 * turn someone's picture back into their initial.
 */
class ProfileImageTest {

    // @get:Rule without @JvmField, as in MediaCacheTest: the rule needs the getter.
    @get:Rule
    val temp = TemporaryFolder()

    private fun body(vararg bytes: Byte) = bytes.toResponseBody("image/png".toMediaType())

    private fun notFound(): Nothing =
        throw HttpException(Response.error<ResponseBody>(404, "".toResponseBody("text/plain".toMediaType())))

    @Test
    fun `a downloaded picture is shown and kept on disk`() = runBlocking {
        val root = temp.newFolder("profile")
        val image = ProfileImage(root) { body(1, 2, 3) }

        image.show("u1")
        assertNull(image.file.value)
        image.refresh()

        assertArrayEquals(byteArrayOf(1, 2, 3), image.file.value!!.readBytes())
        // A fresh instance (a cold start) finds it without the network.
        val restarted = ProfileImage(root) { throw IOException("offline") }
        restarted.show("u1")
        assertArrayEquals(byteArrayOf(1, 2, 3), restarted.file.value!!.readBytes())
    }

    @Test
    fun `a failed refresh keeps the picture`() = runBlocking {
        val root = temp.newFolder("profile")
        var fail = false
        val image = ProfileImage(root) { if (fail) throw IOException("offline") else body(1) }
        image.show("u1")
        image.refresh()

        fail = true
        image.refresh()

        assertNotNull(image.file.value)
    }

    @Test
    fun `a 404 removes the picture`() = runBlocking {
        val root = temp.newFolder("profile")
        var gone = false
        val image = ProfileImage(root) { if (gone) notFound() else body(1) }
        image.show("u1")
        image.refresh()

        gone = true
        image.refresh()

        assertNull(image.file.value)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun `a new picture takes a new file and the old one goes`() = runBlocking {
        val root = temp.newFolder("profile")
        var bytes = byteArrayOf(1)
        val image = ProfileImage(root) { body(*bytes) }
        image.show("u1")
        image.refresh()
        val first = image.file.value!!

        bytes = byteArrayOf(2)
        image.refresh()

        val second = image.file.value!!
        assertNotEquals(first.name, second.name)
        assertArrayEquals(byteArrayOf(2), second.readBytes())
        assertEquals(listOf(second.name), root.listFiles()!!.map { it.name })
    }

    @Test
    fun `the same picture again keeps its file, so the avatar doesn't reload`() = runBlocking {
        val root = temp.newFolder("profile")
        val image = ProfileImage(root) { body(7, 7) }
        image.show("u1")
        image.refresh()
        val first = image.file.value!!

        image.refresh()

        assertEquals(first, image.file.value)
    }

    @Test
    fun `another account's picture is forgotten on sign-in, and everything on clear`() = runBlocking {
        val root = temp.newFolder("profile")
        val image = ProfileImage(root) { body(1) }
        image.show("u1")
        image.refresh()

        image.show("u2")
        assertNull(image.file.value)
        assertEquals(0, root.listFiles()!!.size)

        image.refresh()
        image.clear()
        assertNull(image.file.value)
        assertEquals(0, root.listFiles()!!.size)
        // Signed out, a refresh has no one to fetch for.
        image.refresh()
        assertNull(image.file.value)
    }
}

package org.hyperstarit.keepitapp.offline

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.hyperstarit.keepitapp.data.offline.MediaCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The on-disk image cache. Media ids are immutable, so there is nothing to invalidate — only a size
 * cap to honour and a download to avoid repeating.
 */
class MediaCacheTest {

    // @get:Rule without @JvmField: JUnit needs the annotation on a public field or getter, and
    // @JvmField removes the getter this targets, so the rule silently never runs.
    @get:Rule
    val temp = TemporaryFolder()

    private fun body(bytes: ByteArray) = bytes.toResponseBody("image/jpeg".toMediaType())

    @Test
    fun `evict keeps the newest files within the cap`() {
        val root = temp.newFolder("media")
        val cache = MediaCache(root) { _, _, _ -> null }

        listOf("a", "b", "c").forEachIndexed { i, name ->
            File(root, "$name.img").apply {
                writeBytes(ByteArray(100))
                setLastModified(1_000_000L + i * 10_000L)
            }
        }

        cache.evict(maxBytes = 250)

        assertEquals(listOf("b.img", "c.img"), root.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `evict is a no-op under the cap`() {
        val root = temp.newFolder("media")
        val cache = MediaCache(root) { _, _, _ -> null }
        File(root, "a.img").writeBytes(ByteArray(10))

        cache.evict(maxBytes = 1_000)

        assertTrue(File(root, "a.img").exists())
    }

    @Test
    fun `a cached file is served without downloading again`() = runBlocking {
        val root = temp.newFolder("media")
        var downloads = 0
        val cache = MediaCache(root) { _, _, _ -> downloads++; body(ByteArray(5)) }

        val first = cache.file("n1", "m1", "thumb")
        val second = cache.file("n1", "m1", "thumb")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals("downloaded more than once for an immutable id", 1, downloads)
    }

    @Test
    fun `a failed download yields null and leaves nothing behind`() = runBlocking {
        val root = temp.newFolder("media")
        val cache = MediaCache(root) { _, _, _ -> throw java.io.IOException("offline") }

        val file = cache.file("n1", "m1", "thumb")

        assertNull(file)
        // A half-written or empty file would be served forever afterwards.
        assertTrue(root.listFiles().orEmpty().none { it.length() == 0L && !it.name.endsWith(".tmp") })
    }

    @Test
    fun `thumb and full are cached separately`() = runBlocking {
        val root = temp.newFolder("media")
        var downloads = 0
        val cache = MediaCache(root) { _, _, size ->
            downloads++
            body(ByteArray(if (size == "thumb") 3 else 9))
        }

        val thumb = cache.file("n1", "m1", "thumb")
        val full = cache.file("n1", "m1", "full")

        assertEquals(2, downloads)
        assertEquals(3L, thumb!!.length())
        assertEquals(9L, full!!.length())
    }
}

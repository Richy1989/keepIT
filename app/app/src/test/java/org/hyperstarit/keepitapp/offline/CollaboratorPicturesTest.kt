package org.hyperstarit.keepitapp.offline

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.hyperstarit.keepitapp.data.offline.CollaboratorPictures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The faces of the people on shared notes, on disk. A picture is fetched once per version and kept
 * for offline; a new version replaces the old; a failed fetch leaves nothing behind (the face shows
 * the initial); and whoever is on no note any more is forgotten.
 */
class CollaboratorPicturesTest {

    // @get:Rule without @JvmField, as in MediaCacheTest: the rule needs the getter.
    @get:Rule
    val temp = TemporaryFolder()

    private val ben = "0b6b3a52-8c2e-4c43-9d55-7a0c4a1f2e10"
    private val cleo = "6f1c7e0e-1d55-4b1f-8a51-2b5d9e3c4a77"

    private fun body(vararg bytes: Byte) = bytes.toResponseBody("image/png".toMediaType())

    @Test
    fun `a picture is fetched once per version and then read from disk`() = runBlocking {
        val root = temp.newFolder("people")
        var downloads = 0
        val pictures = CollaboratorPictures(root) { downloads++; body(1, 2, 3) }

        val first = pictures.file(ben, "v1")!!
        val again = pictures.file(ben, "v1")!!

        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertEquals(first, again)
        assertEquals(1, downloads)
        // A cold start, offline, still has it.
        val offline = CollaboratorPictures(root) { throw IOException("offline") }
        assertArrayEquals(byteArrayOf(1, 2, 3), offline.file(ben, "v1")!!.readBytes())
    }

    @Test
    fun `a new version replaces the old one`() = runBlocking {
        val root = temp.newFolder("people")
        var bytes = byteArrayOf(1)
        val pictures = CollaboratorPictures(root) { body(*bytes) }
        val old = pictures.file(ben, "v1")!!

        bytes = byteArrayOf(2)
        val new = pictures.file(ben, "v2")!!

        assertArrayEquals(byteArrayOf(2), new.readBytes())
        assertTrue(!old.exists())
        assertEquals(listOf(new.name), root.list()!!.toList())
    }

    @Test
    fun `a failed fetch leaves nothing behind`() = runBlocking {
        val root = temp.newFolder("people")
        val pictures = CollaboratorPictures(root) { throw IOException("offline") }

        assertNull(pictures.file(ben, "v1"))
        assertTrue(root.list().isNullOrEmpty())
    }

    @Test
    fun `retain forgets everyone on no note and every old version`() = runBlocking {
        val root = temp.newFolder("people")
        val pictures = CollaboratorPictures(root) { body(1) }
        pictures.file(ben, "v1")
        val kept = pictures.file(cleo, "v1")!!

        pictures.retain(mapOf(cleo to "v1"))

        assertEquals(listOf(kept.name), root.list()!!.toList())
    }

    @Test
    fun `an id or version that isn't a plain token never becomes a path`() = runBlocking {
        val root = temp.newFolder("people")
        var downloads = 0
        val pictures = CollaboratorPictures(root) { downloads++; body(1) }

        assertNull(pictures.file(ben, "../../escape"))
        assertNull(pictures.file("../$ben", "v1"))
        assertEquals(0, downloads)
        assertTrue(root.list().isNullOrEmpty())
    }
}

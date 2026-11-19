package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The background name rules — the server's (`NoteBackground.cs`) and the web's (`backgrounds.ts`) —
 * and the repository's `backgrounds/` folder held to them, since this app ships it as it is.
 */
class NoteBackgroundNamesTest {

    @Test
    fun `names are lowercase words joined by single hyphens`() {
        listOf("lined", "squared-paper", "dots-2").forEach { assertTrue(it, NoteBackgroundNames.isValid(it)) }
        listOf("", "Lined", "lined.png", "-lined", "lined-", "two--hyphens", "under_score", "../x", "a".repeat(65))
            .forEach { assertFalse(it, NoteBackgroundNames.isValid(it)) }
    }

    @Test
    fun `each PNG or WebP makes a background, and the rest are named`() {
        val (found, rejected) = NoteBackgroundNames.parse(
            listOf("lined.png", "grid-dots.webp", "Bad.png", "pattern.svg", "lined.dark.png", "photo.jpg"),
        )
        assertEquals(
            listOf(NoteBackgroundNames.Entry("grid-dots", "grid-dots.webp"), NoteBackgroundNames.Entry("lined", "lined.png")),
            found,
        )
        assertEquals(listOf("Bad.png", "lined.dark.png", "pattern.svg", "photo.jpg"), rejected)
        assertEquals("Grid dots", found.first().label)
    }

    @Test
    fun `the shipped backgrounds folder holds only background images, each small`() {
        val folder = File("../../backgrounds")
        assertTrue("backgrounds/ not found from ${File(".").absolutePath}", folder.isDirectory)
        val files = folder.listFiles().orEmpty().filter { it.isFile && it.name != "README.md" }
        val (_, rejected) = NoteBackgroundNames.parse(files.map { it.name })
        assertEquals("not backgrounds (see backgrounds/README.md)", emptyList<String>(), rejected)
        assertEquals("over 64 KB", emptyList<String>(), files.filter { it.length() > 64 * 1024 }.map { it.name })
    }

    /**
     * Each image is a stencil: the app uses its alpha and paints it in the note's ink at the theme's
     * strength, so a faint file comes out fainter still. Its strokes must be opaque (anti-aliased
     * edges aside), and colour is ignored.
     */
    @Test
    fun `the shipped backgrounds are opaque stencils`() {
        val faint = File("../../backgrounds").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".png") }
            .filter { file ->
                val image = ImageIO.read(file) ?: return@filter true
                var max = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    max = maxOf(max, image.getRGB(x, y) ushr 24)
                }
                max < 240
            }
            .map { it.name }
        assertEquals("strokes not opaque (see backgrounds/README.md)", emptyList<String>(), faint)
    }
}

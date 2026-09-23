package org.hyperstarit.keepitapp.portability

import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteArchiveDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.portability.Archive
import org.hyperstarit.keepitapp.data.portability.ArchiveError
import org.hyperstarit.keepitapp.data.portability.ArchiveReader
import org.hyperstarit.keepitapp.data.portability.ArchiveWriter
import org.hyperstarit.keepitapp.data.portability.ImageInfo
import org.hyperstarit.keepitapp.data.portability.OpenArchiveResult
import org.hyperstarit.keepitapp.data.portability.buildStandaloneArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The archive format, from both ends: what a standalone device writes, and what it reads back.
 *
 * This is the Android half of the round trip the API tests pin — the same file has to be written
 * by either side and read by the other, so these tests are what stop the phone quietly drifting
 * from the server's idea of the format. They run on the JVM because everything in `Archive.kt` is
 * deliberately plain `java.util.zip`, with the Android-only parts (Uri, image decoding) kept out in
 * `PortabilityRepository`.
 */
class ArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun stagedImage(name: String, bytes: ByteArray = ByteArray(32) { it.toByte() }): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    private val probe: (File) -> ImageInfo? = { ImageInfo(width = 40, height = 30, extension = ".jpg") }

    // ---- the manifest a standalone device builds ----

    @Test
    fun `a standalone note's queued attachments become media rows in the manifest`() {
        val staged = stagedImage("a.img")
        val notes = listOf(NoteDto(id = "n1", title = "with a photo"))
        val ops = listOf(
            PendingOp.AttachMedia(
                noteId = "n1",
                stagedPath = staged.absolutePath,
                tempMediaId = "m1",
                enqueuedAtUtc = "2026-01-02T03:04:05Z",
            ),
        )

        val content = buildStandaloneArchive(notes, emptyList(), ops, "0.7.6", "now", probe)

        val note = content.manifest.notes.single()
        val media = note.media.single()
        assertEquals("m1", media.id)
        assertEquals(40, media.width)
        assertEquals(30, media.height)
        assertEquals(staged.length(), media.byteSize)
        assertEquals(1, content.images.size)
        assertEquals(staged, content.images.single().file)
    }

    /**
     * Standalone notes carry no server media rows, but a note that somehow has one has no bytes
     * here to back it. Promising an image the archive doesn't contain is worse than leaving it out:
     * the importer would report it missing on every restore.
     */
    @Test
    fun `a media row with no staged bytes is dropped rather than promised`() {
        val notes = listOf(
            NoteDto(id = "n1", title = "stale", media = listOf(NoteMediaDto(id = "ghost"))),
        )

        val content = buildStandaloneArchive(notes, emptyList(), emptyList(), "0.7.6", "now", probe)

        assertTrue(content.manifest.notes.single().media.isEmpty())
        assertTrue(content.images.isEmpty())
    }

    @Test
    fun `an unreadable staged file is skipped, not exported as a broken entry`() {
        val staged = stagedImage("bad.img")
        val notes = listOf(NoteDto(id = "n1"))
        val ops = listOf(PendingOp.AttachMedia("n1", staged.absolutePath, "m1"))

        val content = buildStandaloneArchive(notes, emptyList(), ops, "0.7.6", "now") { null }

        assertTrue(content.manifest.notes.single().media.isEmpty())
        assertTrue(content.images.isEmpty())
    }

    @Test
    fun `lists and the envelope come along`() {
        val lists = listOf(ListDto(id = "l1", name = "Trip", color = "teal"))

        val content = buildStandaloneArchive(emptyList(), lists, emptyList(), "0.7.6", "yesterday", probe)

        assertEquals(NoteArchiveDto.CURRENT_SCHEMA_VERSION, content.manifest.schemaVersion)
        assertEquals("0.7.6", content.manifest.appVersion)
        assertEquals("yesterday", content.manifest.exportedAtUtc)
        assertEquals("Trip", content.manifest.lists.single().name)
    }

    // ---- write, then read back ----

    @Test
    fun `an archive this device writes is one it reads back whole`() {
        val bytes = ByteArray(64) { (it * 3).toByte() }
        val staged = stagedImage("p.img", bytes)
        val notes = listOf(
            NoteDto(
                id = "n1",
                title = "packing",
                body = "passport",
                color = "amber",
                isPinned = true,
                checklistItems = emptyList(),
                listIds = listOf("l1"),
            ),
        )
        val ops = listOf(PendingOp.AttachMedia("n1", staged.absolutePath, "m1"))
        val content = buildStandaloneArchive(
            notes, listOf(ListDto(id = "l1", name = "Trip")), ops, "0.7.6", "now", probe,
        )

        val zipFile = temp.newFile("out.zip")
        val written = zipFile.outputStream().use { out -> ArchiveWriter.write(out, content) }
        assertEquals(1, written)

        val opened = ArchiveReader.open(zipFile)
        assertTrue(opened is OpenArchiveResult.Opened)
        (opened as OpenArchiveResult.Opened).archive.use { archive ->
            val note = archive.manifest.notes.single()
            assertEquals("packing", note.title)
            assertEquals("passport", note.body)
            assertEquals("amber", note.color)
            assertTrue(note.isPinned)
            assertEquals(listOf("l1"), note.listIds)
            assertEquals("Trip", archive.manifest.lists.single().name)

            val restored = archive.openImage("n1", "m1")!!.use { it.readBytes() }
            assertTrue(bytes.contentEquals(restored))
        }
    }

    @Test
    fun `an image that vanishes before it is written does not fail the export`() {
        val staged = stagedImage("gone.img")
        val ops = listOf(PendingOp.AttachMedia("n1", staged.absolutePath, "m1"))
        val content = buildStandaloneArchive(listOf(NoteDto(id = "n1")), emptyList(), ops, "v", "now", probe)
        staged.delete()

        val zipFile = temp.newFile("out.zip")
        val written = zipFile.outputStream().use { out -> ArchiveWriter.write(out, content) }

        assertEquals(0, written)
        val opened = ArchiveReader.open(zipFile) as OpenArchiveResult.Opened
        opened.archive.use { assertNull(it.openImage("n1", "m1")) }
    }

    // ---- what the reader refuses ----

    @Test
    fun `a file that is not a zip is refused`() {
        val notZip = temp.newFile("notes.txt").apply { writeText("definitely not a zip") }

        val opened = ArchiveReader.open(notZip)

        assertEquals(ArchiveError.NOT_A_ZIP, (opened as OpenArchiveResult.Failed).error)
    }

    @Test
    fun `a zip from another app is refused by name`() {
        val zipFile = temp.newFile("other.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Takeout/Keep/note.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val opened = ArchiveReader.open(zipFile)

        assertEquals(ArchiveError.NOT_A_KEEPIT_ARCHIVE, (opened as OpenArchiveResult.Failed).error)
    }

    @Test
    fun `an archive from a newer keepIT is refused rather than half-read`() {
        val zipFile = writeManifest("""{"schemaVersion":999,"notes":[],"lists":[]}""")

        val opened = ArchiveReader.open(zipFile)

        assertEquals(ArchiveError.FROM_A_NEWER_VERSION, (opened as OpenArchiveResult.Failed).error)
    }

    @Test
    fun `an unreadable manifest is refused`() {
        val zipFile = writeManifest("{ this is not json")

        val opened = ArchiveReader.open(zipFile)

        assertEquals(ArchiveError.UNREADABLE_MANIFEST, (opened as OpenArchiveResult.Failed).error)
    }

    /** People re-zip a folder they unpacked, and the manifest ends up one level down. */
    @Test
    fun `a manifest inside a wrapping folder is still found`() {
        val zipFile = temp.newFile("wrapped.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("keepit-export/${Archive.MANIFEST}"))
            zip.write("""{"schemaVersion":1,"notes":[{"id":"n1","title":"deep"}],"lists":[]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("keepit-export/media/n1/m1.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        val opened = ArchiveReader.open(zipFile)

        (opened as OpenArchiveResult.Opened).archive.use { archive ->
            assertEquals("deep", archive.manifest.notes.single().title)
            // The media entries are found through the same wrapping folder.
            assertEquals(3, archive.openImage("n1", "m1")!!.use { it.readBytes().size })
        }
    }

    // ---- media paths ----

    @Test
    fun `a media path round-trips through its ids`() {
        val path = Archive.mediaPath("n1", "m1", ".png")

        assertEquals("media/n1/m1.png", path)
        assertEquals("n1" to "m1", Archive.parseMediaPath(path))
    }

    @Test
    fun `entries that are not media are not read as media`() {
        assertNull(Archive.parseMediaPath(Archive.MANIFEST))
        assertNull(Archive.parseMediaPath("media/n1"))
        assertNull(Archive.parseMediaPath("media/n1/deeper/m1.jpg"))
        assertNull(Archive.parseMediaPath("notmedia/n1/m1.jpg"))
    }

    private fun writeManifest(body: String): File {
        val zipFile = temp.newFile("m-${body.hashCode()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(Archive.MANIFEST))
            zip.write(body.toByteArray())
            zip.closeEntry()
        }
        return zipFile
    }
}

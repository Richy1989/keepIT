package org.hyperstarit.keepitapp.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hyperstarit.keepitapp.data.portability.Archive
import org.hyperstarit.keepitapp.data.portability.ArchiveReader
import org.hyperstarit.keepitapp.data.portability.OpenArchiveResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reads and re-writes an export archive off the real (minified) dex.
 *
 * The failure this exists for is the house speciality: kotlinx.serialization resolves a type's
 * generated `$$serializer` **by name at runtime**, so R8 renaming or dropping it turns every export
 * and import into an exception in release builds only — nothing at build time, nothing in a unit
 * test, and a backup button that silently does nothing. `NoteArchiveDto` is the type at risk: the
 * newest `@Serializable` in the app, and the only one never sent through Retrofit, so it reaches
 * the serializer by a path no other test covers.
 *
 * Everything here is asserted **as JSON text**, never by reading a decoded object's properties.
 * That is deliberate twice over. The JSON is the actual contract — the C# API writes this shape and
 * has to keep reading what the phone writes — so comparing text checks the thing that matters
 * rather than an in-memory copy of it. And it keeps the test off the app's data classes entirely:
 * R8 inlines their trivial getters and drops the synthetic default-argument constructors, so a test
 * that touched either would fail for two-pass linking reasons (see `proguard-rules-minified.pro`)
 * that say nothing about whether export works.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveSmokeTest {

    /** A manifest in exactly the shape the server's export writes. */
    private val manifestJson = """
        {
          "schemaVersion": 1,
          "exportedAtUtc": "2026-09-23T12:00:00Z",
          "appVersion": "smoke",
          "lists": [ { "id": "l1", "name": "Trip", "color": "teal", "noteCount": 1 } ],
          "notes": [
            {
              "id": "n1",
              "type": "Checklist",
              "title": "packing",
              "body": "passport",
              "color": "amber",
              "isPinned": true,
              "isArchived": false,
              "isTrashed": false,
              "remindAtUtc": "2026-12-01T09:00:00Z",
              "reminderRecurrence": "Weekly",
              "checklistItems": [ { "id": "c1", "text": "tickets", "isChecked": true, "order": 0 } ],
              "media": [ { "id": "m1", "width": 40, "height": 30, "byteSize": 5, "order": 0 } ],
              "listIds": [ "l1" ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun aServerWrittenManifest_parsesOffTheMinifiedDex() {
        assertNotNull(
            "the manifest serializer did not resolve after R8 — export and import are both dead",
            Archive.decodeManifest(manifestJson),
        )
    }

    @Test
    fun everyFieldSurvivesBeingReadAndWrittenAgain() {
        val manifest = Archive.decodeManifest(manifestJson)!!

        // The encode is the half that throws if the serializer was renamed away.
        val out = Archive.encodeManifest(manifest)

        for (value in listOf("packing", "passport", "amber", "Checklist", "Weekly", "tickets", "Trip", "teal")) {
            assertTrue("the re-written manifest lost \"$value\":\n$out", out.contains("\"$value\""))
        }
        for (id in listOf("n1", "l1", "c1", "m1")) {
            assertTrue("the re-written manifest lost the id \"$id\":\n$out", out.contains("\"$id\""))
        }
        assertTrue("isPinned did not survive as true:\n$out", Regex("\"isPinned\":\\s*true").containsMatchIn(out))
        assertTrue("isChecked did not survive as true:\n$out", Regex("\"isChecked\":\\s*true").containsMatchIn(out))
        // Easy to lose and expensive to lose: without it an importer gets an archive with no format
        // version on it and cannot tell whether it is allowed to read it.
        assertTrue(
            "the archive was written without its schemaVersion:\n$out",
            Regex("\"schemaVersion\":\\s*1").containsMatchIn(out),
        )
        // Re-reading what we just wrote is what a restore actually does.
        assertNotNull("the manifest this app wrote could not be read back:\n$out", Archive.decodeManifest(out))
    }

    @Test
    fun anArchiveOnDisk_opensAndYieldsItsImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "smoke-archive.zip")
        val imageBytes = byteArrayOf(9, 8, 7, 6, 5)

        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(Archive.MANIFEST))
            zip.write(manifestJson.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(Archive.mediaPath("n1", "m1", ".jpg")))
            zip.write(imageBytes)
            zip.closeEntry()
        }

        try {
            val opened = ArchiveReader.open(file)

            assertTrue("the archive did not open: $opened", opened is OpenArchiveResult.Opened)
            (opened as OpenArchiveResult.Opened).archive.use { archive ->
                val read = archive.openImage("n1", "m1")?.use { it.readBytes() }
                assertNotNull("the archive did not yield its image", read)
                assertTrue("the image bytes did not survive", imageBytes.contentEquals(read!!))
            }
        } finally {
            file.delete()
        }
    }
}

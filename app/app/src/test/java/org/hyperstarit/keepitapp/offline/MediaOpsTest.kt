package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.hyperstarit.keepitapp.data.offline.pendingMedia
import org.hyperstarit.keepitapp.data.offline.withUploadedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline rules specific to image attachments. Media ops are the first outbox entries that
 * carry a file rather than a JSON payload, and the first that must survive coalescing untouched.
 */
class MediaOpsTest {

    private fun attach(noteId: String, temp: String = "t1") =
        PendingOp.AttachMedia(noteId = noteId, stagedPath = "/tmp/$temp.jpg", tempMediaId = temp)

    @Test
    fun `two attachments on one note both survive coalescing`() {
        val ops = coalesce(coalesce(emptyList(), attach("n1", "a")), attach("n1", "b"))

        assertEquals(2, ops.filterIsInstance<PendingOp.AttachMedia>().size)
    }

    @Test
    fun `an update does not swallow a queued attachment`() {
        val withAttach = coalesce(emptyList(), attach("n1"))
        val ops = coalesce(withAttach, PendingOp.Update("n1", UpdateNoteDto(type = "Text", title = "hi")))

        assertEquals(1, ops.filterIsInstance<PendingOp.AttachMedia>().size)
        assertEquals(1, ops.filterIsInstance<PendingOp.Update>().size)
    }

    @Test
    fun `an attachment does not replace an earlier update`() {
        val withUpdate = coalesce(emptyList(), PendingOp.Update("n1", UpdateNoteDto(type = "Text", title = "hi")))
        val ops = coalesce(withUpdate, attach("n1"))

        assertEquals(1, ops.filterIsInstance<PendingOp.Update>().size)
        assertEquals(1, ops.filterIsInstance<PendingOp.AttachMedia>().size)
    }

    @Test
    fun `deleting the note drops its queued media ops`() {
        val withAttach = coalesce(emptyList(), attach("n1"))
        val ops = coalesce(withAttach, PendingOp.Delete("n1"))

        assertTrue(ops.filterIsInstance<PendingOp.AttachMedia>().isEmpty())
    }

    @Test
    fun `deleting media removes it from the cached note`() {
        val note = NoteDto(id = "n1", media = listOf(NoteMediaDto(id = "m1"), NoteMediaDto(id = "m2")))

        val result = applyOp(listOf(note), PendingOp.DeleteMedia("n1", "m1"))

        assertEquals(listOf("m2"), result.single().media.map { it.id })
    }

    @Test
    fun `attaching does not alter the cached note`() {
        val note = NoteDto(id = "n1", media = listOf(NoteMediaDto(id = "m1")))

        val result = applyOp(listOf(note), attach("n1"))

        assertEquals(listOf("m1"), result.single().media.map { it.id })
    }

    @Test
    fun `pending attachments are projected per note`() {
        val ops = listOf(attach("n1", "a"), attach("n2", "b"), attach("n1", "c"))

        assertEquals(listOf("a", "c"), pendingMedia(ops, "n1").map { it.tempMediaId })
    }

    @Test
    fun `an attachment queued against an offline note is remapped with it`() {
        // The case that would otherwise upload to a note id the server has never seen.
        val ops = listOf(attach("temp-1", "a"), PendingOp.DeleteMedia("temp-1", "m9"))

        val remapped = ops.map { op ->
            when (op) {
                is PendingOp.AttachMedia ->
                    if (op.noteId == "temp-1") op.copy(noteId = "real-1") else op
                is PendingOp.DeleteMedia ->
                    if (op.noteId == "temp-1") op.copy(noteId = "real-1") else op
                else -> op
            }
        }

        assertTrue(remapped.all { it.targetId == "real-1" })
    }

    @Test
    fun `an uploaded image joins its note straight away`() {
        // Between the upload and the next fetch this is the only place the image exists, so an
        // open editor keeps showing it instead of blanking the row until the refetch lands.
        val notes = listOf(NoteDto(id = "n1"), NoteDto(id = "n2"))

        val result = withUploadedMedia(notes, "n1", NoteMediaDto(id = "m1"))

        assertEquals(listOf("m1"), result.first { it.id == "n1" }.media.map { it.id })
        assertTrue(result.first { it.id == "n2" }.media.isEmpty())
    }

    @Test
    fun `an uploaded image lands in its server order`() {
        val note = NoteDto(id = "n1", media = listOf(NoteMediaDto(id = "m0", order = 0), NoteMediaDto(id = "m2", order = 2)))

        val result = withUploadedMedia(listOf(note), "n1", NoteMediaDto(id = "m1", order = 1))

        assertEquals(listOf("m0", "m1", "m2"), result.single().media.map { it.id })
    }

    @Test
    fun `an image the note already lists is not added twice`() {
        // The refetch that follows an upload reports the same id; applying the upload again after
        // it must not duplicate the thumbnail.
        val note = NoteDto(id = "n1", media = listOf(NoteMediaDto(id = "m1")))

        val result = withUploadedMedia(listOf(note), "n1", NoteMediaDto(id = "m1"))

        assertEquals(listOf("m1"), result.single().media.map { it.id })
    }
}

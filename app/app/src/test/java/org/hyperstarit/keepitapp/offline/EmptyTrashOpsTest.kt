package org.hyperstarit.keepitapp.offline

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.applyPending
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Delete all" in the trash, offline: what it does to the cache, and how it folds into the queue.
 * The server refuses the whole request over one temp id, and only empties what is in the trash
 * there, so both of those have to be right before the op ever replays.
 */
class EmptyTrashOpsTest {

    private fun note(id: String, trashed: Boolean = true) = NoteDto(id = id, isTrashed = trashed)

    private fun attach(noteId: String) = PendingOp.AttachMedia(noteId, "/staged/$noteId.jpg", "m-$noteId")

    @Test
    fun `the listed notes leave the cache and the rest stay`() {
        val notes = listOf(note("n1"), note("n2"), note("n3", trashed = false))

        val result = applyOp(notes, PendingOp.EmptyTrash(listOf("n1", "n2")))

        assertEquals(listOf("n3"), result.map { it.id })
    }

    @Test
    fun `a note that only exists locally leaves the op and every op for it goes`() {
        val ops = listOf(
            PendingOp.Create("local-1", CreateNoteDto(title = "made offline")),
            attach("local-1"),
            PendingOp.SetState("local-1", NoteStateDto(isTrashed = true)),
            PendingOp.Update("n-other", UpdateNoteDto(type = NoteTypes.TEXT)),
        )

        val result = coalesce(ops, PendingOp.EmptyTrash(listOf("local-1", "n1")))

        assertEquals(2, result.size)
        assertEquals("n-other", (result[0] as PendingOp.Update).noteId)
        assertEquals(listOf("n1"), (result[1] as PendingOp.EmptyTrash).noteIds)
    }

    @Test
    fun `only local notes queue nothing at all`() {
        val ops = listOf(PendingOp.Create("local-1", CreateNoteDto(title = "made offline")))

        assertEquals(emptyList<PendingOp>(), coalesce(ops, PendingOp.EmptyTrash(listOf("local-1"))))
    }

    @Test
    fun `a server note's queued edits go but the change that trashed it stays ahead of the op`() {
        val ops = listOf(
            PendingOp.Update("n1", UpdateNoteDto(type = NoteTypes.TEXT, title = "edited")),
            PendingOp.SetState("n1", NoteStateDto(isTrashed = true)),
            PendingOp.SetLists("n1", listOf("list-a")),
            attach("n1"),
        )

        val result = coalesce(ops, PendingOp.EmptyTrash(listOf("n1")))

        assertEquals(2, result.size)
        assertEquals(NoteStateDto(isTrashed = true), (result[0] as PendingOp.SetState).state)
        assertEquals(listOf("n1"), (result[1] as PendingOp.EmptyTrash).noteIds)
    }

    @Test
    fun `a queued op keeps the notes gone when the next fetch still has them`() {
        val fetched = listOf(note("n1"), note("n2"))
        val queued = listOf(PendingOp.EmptyTrash(listOf("n1")))

        assertEquals(listOf("n2"), applyPending(fetched, queued).map { it.id })
    }

    @Test
    fun `the op survives the outbox's JSON round-trip`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val ops: List<PendingOp> = listOf(PendingOp.EmptyTrash(listOf("n1", "n2")))

        assertEquals(ops, json.decodeFromString<List<PendingOp>>(json.encodeToString(ops)))
    }
}

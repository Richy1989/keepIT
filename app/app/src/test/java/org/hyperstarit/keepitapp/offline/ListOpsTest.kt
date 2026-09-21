package org.hyperstarit.keepitapp.offline

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.hyperstarit.keepitapp.data.CreateListDto
import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.UpdateListDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.applyListOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.applyPendingLists
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.hyperstarit.keepitapp.data.offline.remapListIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline rules for lists. Lists used to be online-only; queueing them like notes brings one new
 * hazard — a note's queued payload naming a list the server hasn't been told about yet, which gets
 * the whole request refused — and most of these tests pin the rules that keep that from happening.
 */
class ListOpsTest {

    private fun list(id: String, name: String) = ListDto(id = id, name = name)

    private fun createList(tempId: String, name: String) = PendingOp.CreateList(tempId, CreateListDto(name))

    // ---- applying to the cache ----

    @Test
    fun `a created list appears at once, in alphabetical order`() {
        val lists = listOf(list("a", "Alpha"), list("z", "zulu"))
        val result = applyListOp(lists, createList("local-1", "Middle"))

        assertEquals(listOf("Alpha", "Middle", "zulu"), result.map { it.name })
        assertEquals("local-1", result[1].id)
    }

    @Test
    fun `a rename changes only the fields it carries and keeps the order`() {
        val lists = listOf(list("a", "Alpha").copy(color = "rose"), list("b", "Beta"))
        val result = applyListOp(lists, PendingOp.UpdateList("a", UpdateListDto(name = "Zeta")))

        assertEquals(listOf("Beta", "Zeta"), result.map { it.name })
        assertEquals("rose", result.last().color) // null color in the rename: unchanged
    }

    @Test
    fun `deleting a list removes it and every note's membership, but not the notes`() {
        val op = PendingOp.DeleteList("a")
        val notes = listOf(
            NoteDto(id = "n1", listIds = listOf("a", "b")),
            NoteDto(id = "n2", listIds = listOf("b")),
        )

        assertEquals(listOf("b"), applyListOp(listOf(list("a", "A"), list("b", "B")), op).map { it.id })
        val after = applyOp(notes, op)
        assertEquals(listOf("n1", "n2"), after.map { it.id })
        assertEquals(listOf("b"), after.first().listIds)
    }

    @Test
    fun `queued list ops overlay a fresh fetch`() {
        val fetched = listOf(list("a", "Alpha"), list("b", "Beta"))
        val ops = listOf(createList("local-1", "Gamma"), PendingOp.DeleteList("b"))

        assertEquals(listOf("Alpha", "Gamma"), applyPendingLists(fetched, ops).map { it.name })
    }

    // ---- coalescing ----

    @Test
    fun `a rename folds into the list's queued create`() {
        val ops = coalesce(listOf(createList("local-1", "Draft")), PendingOp.UpdateList("local-1", UpdateListDto(name = "Final")))

        assertEquals("Final", (ops.single() as PendingOp.CreateList).dto.name)
    }

    @Test
    fun `a second rename merges field-wise into the first`() {
        val ops = coalesce(
            listOf(PendingOp.UpdateList("a", UpdateListDto(name = "New name"))),
            PendingOp.UpdateList("a", UpdateListDto(color = "sky")),
        )

        val merged = (ops.single() as PendingOp.UpdateList).dto
        assertEquals("New name", merged.name)
        assertEquals("sky", merged.color)
    }

    @Test
    fun `deleting a list created offline sends nothing and forgets every membership in it`() {
        val ops = listOf(
            createList("local-L", "Doomed"),
            PendingOp.UpdateList("local-L", UpdateListDto(name = "Still doomed")),
            PendingOp.Create("local-1", CreateNoteDto(title = "t", listIds = listOf("local-L"))),
            PendingOp.SetLists("n1", listOf("keep", "local-L")),
        )
        val result = coalesce(ops, PendingOp.DeleteList("local-L"))

        assertEquals(2, result.size) // no list ops left, and no delete queued
        assertNull((result[0] as PendingOp.Create).dto.listIds)
        assertEquals(listOf("keep"), (result[1] as PendingOp.SetLists).listIds)
    }

    @Test
    fun `deleting a server list queues the delete and drops its pending rename`() {
        val ops = coalesce(
            listOf(
                PendingOp.UpdateList("a", UpdateListDto(name = "Renamed")),
                PendingOp.SetLists("n1", listOf("a")),
            ),
            PendingOp.DeleteList("a"),
        )

        assertEquals(2, ops.size)
        assertEquals(emptyList<String>(), (ops[0] as PendingOp.SetLists).listIds)
        assertTrue(ops[1] is PendingOp.DeleteList)
    }

    @Test
    fun `filing a new note into a list that already existed folds into the note's create`() {
        val ops = coalesce(
            listOf(createList("local-L", "Groceries"), PendingOp.Create("local-1", CreateNoteDto(title = "t"))),
            PendingOp.SetLists("local-1", listOf("local-L")),
        )

        assertEquals(2, ops.size)
        assertEquals(listOf("local-L"), (ops[1] as PendingOp.Create).dto.listIds)
    }

    @Test
    fun `filing a new note into a list created after it stays behind that list's create`() {
        // Replay is FIFO: folded into the note's create, the POST would name a list the server has
        // not seen yet and be refused — taking the note with it.
        val ops = coalesce(
            listOf(PendingOp.Create("local-1", CreateNoteDto(title = "t")), createList("local-L", "Later")),
            PendingOp.SetLists("local-1", listOf("local-L")),
        )

        assertEquals(3, ops.size)
        assertNull((ops[0] as PendingOp.Create).dto.listIds)
        assertTrue(ops[1] is PendingOp.CreateList)
        assertEquals(listOf("local-L"), (ops[2] as PendingOp.SetLists).listIds)
    }

    @Test
    fun `a later membership that can fold replaces one queued separately`() {
        // Otherwise the stale separate op would replay after the create and win.
        val ops = coalesce(
            listOf(
                PendingOp.Create("local-1", CreateNoteDto(title = "t")),
                createList("local-L", "Later"),
                PendingOp.SetLists("local-1", listOf("local-L")),
            ),
            PendingOp.SetLists("local-1", emptyList()),
        )

        assertEquals(2, ops.size)
        assertTrue(ops.none { it is PendingOp.SetLists })
    }

    // ---- remapping ----

    @Test
    fun `a list's server id replaces its temp id everywhere it is queued`() {
        val ops = listOf(
            PendingOp.Create("local-1", CreateNoteDto(title = "t", listIds = listOf("local-L", "other"))),
            PendingOp.SetLists("n1", listOf("local-L")),
            PendingOp.UpdateList("local-L", UpdateListDto(name = "x")),
            PendingOp.DeleteList("local-L"),
        )
        val result = remapListIds(ops, "local-L", "real-L")

        assertEquals(listOf("real-L", "other"), (result[0] as PendingOp.Create).dto.listIds)
        assertEquals(listOf("real-L"), (result[1] as PendingOp.SetLists).listIds)
        assertEquals("real-L", (result[2] as PendingOp.UpdateList).listId)
        assertEquals("real-L", (result[3] as PendingOp.DeleteList).listId)
    }

    @Test
    fun `list ops survive the outbox's JSON round-trip`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val ops: List<PendingOp> = listOf(
            createList("local-L", "Groceries"),
            PendingOp.UpdateList("a", UpdateListDto(name = "Renamed", color = "sky")),
            PendingOp.DeleteList("b"),
        )

        assertEquals(ops, json.decodeFromString<List<PendingOp>>(json.encodeToString(ops)))
    }
}

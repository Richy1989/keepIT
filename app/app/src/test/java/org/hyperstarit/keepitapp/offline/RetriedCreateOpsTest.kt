package org.hyperstarit.keepitapp.offline

import kotlinx.serialization.json.Json
import org.hyperstarit.keepitapp.data.CreateListDto
import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.UpdateListDto
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A create whose answer was lost. Each queued create sends the id it was queued under, so the server
 * answers a resend with what the first one made rather than making a copy (see the server's
 * `RetriedCreateTests`). The server answers a resend with what it already has and ignores the body,
 * so once a create has gone out ([PendingOp.Create.sent]) nothing may fold into it any more: a later
 * edit, filing, deletion or rename queues as an op of its own behind it, which [Outbox.remapId] moves
 * to the real id like any other.
 */
class RetriedCreateOpsTest {

    private val create = PendingOp.Create(tempId = "local-1", dto = CreateNoteDto(title = "Groceries"))
    private val sentCreate = create.copy(sent = true)

    private val createList = PendingOp.CreateList(tempId = "local-list", dto = CreateListDto(name = "Shop"))
    private val sentCreateList = createList.copy(sent = true)

    @Test
    fun `a create sends the id it was queued under, without the temp prefix`() {
        val uuid = "0b8f4d2e-6c1a-4f3b-9e2d-7a5c3b1e9f00"

        assertEquals(uuid, PendingOp.clientIdOf("local-$uuid"))
        assertNull(PendingOp.clientIdOf("local-not-a-uuid"))
    }

    @Test
    fun `an edit to a note whose create has gone out queues behind it, leaving the create as sent`() {
        val edit = PendingOp.Update("local-1", UpdateNoteDto(type = "Text", title = "Groceries and more"))

        val ops = coalesce(listOf(sentCreate), edit)

        assertEquals(listOf(sentCreate, edit), ops)
    }

    @Test
    fun `filing a note whose create has gone out queues behind it`() {
        val filing = PendingOp.SetLists("local-1", listOf("list-a"))

        val ops = coalesce(listOf(sentCreate), filing)

        assertEquals(listOf(sentCreate, filing), ops)
    }

    @Test
    fun `deleting a note whose create has gone out keeps the create and deletes after it`() {
        val edit = PendingOp.Update("local-1", UpdateNoteDto(type = "Text", title = "x"))
        val delete = PendingOp.Delete("local-1")

        val ops = coalesce(listOf(sentCreate, edit), delete)

        assertEquals(listOf(sentCreate, delete), ops)
    }

    @Test
    fun `emptying the trash of a note whose create has gone out deletes it after the create`() {
        val ops = coalesce(listOf(sentCreate), PendingOp.EmptyTrash(listOf("local-1", "local-2")))

        assertEquals(2, ops.size)
        assertEquals(sentCreate, ops[0])
        assertEquals("local-1", (ops[1] as PendingOp.Delete).noteId)
    }

    @Test
    fun `a create not yet sent still takes in later edits and goes with its note`() {
        val edit = PendingOp.Update("local-1", UpdateNoteDto(type = "Text", title = "Groceries and more"))

        val folded = coalesce(listOf(create), edit)
        assertEquals("Groceries and more", (folded.single() as PendingOp.Create).dto.title)

        assertEquals(emptyList<PendingOp>(), coalesce(folded, PendingOp.Delete("local-1")))
    }

    @Test
    fun `renaming a list whose create has gone out queues the rename behind it`() {
        val rename = PendingOp.UpdateList("local-list", UpdateListDto(name = "Supermarket"))

        val ops = coalesce(listOf(sentCreateList), rename)

        assertEquals(listOf(sentCreateList, rename), ops)
    }

    @Test
    fun `deleting a list whose create has gone out keeps the create and deletes after it`() {
        val delete = PendingOp.DeleteList("local-list")

        val ops = coalesce(listOf(sentCreateList), delete)

        assertEquals(listOf(sentCreateList, delete), ops)
    }

    @Test
    fun `a queue written before creates were marked reads them as not yet sent`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val old = """{"type":"create","tempId":"local-1","dto":{"title":"Groceries"},"opId":"op-1"}"""

        val op = json.decodeFromString(PendingOp.serializer(), old) as PendingOp.Create

        assertFalse(op.sent)
    }
}

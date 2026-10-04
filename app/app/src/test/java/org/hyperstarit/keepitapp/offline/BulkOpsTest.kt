package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.ChecklistItemDto
import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.hyperstarit.keepitapp.data.offline.colorOps
import org.hyperstarit.keepitapp.data.offline.listMembershipOps
import org.hyperstarit.keepitapp.data.offline.membershipOf
import org.hyperstarit.keepitapp.data.offline.stateOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-select's ops: one single-note op per note that actually changes, so the outbox and the
 * server see exactly what tapping each card would have sent. What matters most is what they leave
 * out — a view-only note's color, a note already in the state asked for — and that a color change,
 * which travels as the whole note, carries the note's content unchanged.
 */
class BulkOpsTest {

    private fun note(
        id: String,
        pinned: Boolean = false,
        archived: Boolean = false,
        color: String? = null,
        canEdit: Boolean = true,
        lists: List<String> = emptyList(),
    ) = NoteDto(
        id = id,
        isPinned = pinned,
        isArchived = archived,
        color = color,
        canEdit = canEdit,
        isOwner = canEdit,
        listIds = lists,
    )

    @Test
    fun `a state change skips notes already in that state`() {
        val notes = listOf(note("n1", pinned = true), note("n2"), note("n3"))

        val ops = stateOps(notes, NoteStateDto(isPinned = true))

        assertEquals(listOf("n2", "n3"), ops.map { it.targetId })
        assertTrue(ops.all { (it as PendingOp.SetState).state == NoteStateDto(isPinned = true) })
    }

    @Test
    fun `a state change reaches shared notes too, since it is per-user`() {
        val ops = stateOps(listOf(note("viewer", canEdit = false)), NoteStateDto(isTrashed = true))

        assertEquals(listOf("viewer"), ops.map { it.targetId })
    }

    @Test
    fun `a recolor skips view-only notes and ones already that color`() {
        val notes = listOf(note("n1", color = "red"), note("n2", color = "blue"), note("viewer", canEdit = false))

        val ops = colorOps(notes, "red")

        assertEquals(listOf("n2"), ops.map { it.targetId })
    }

    @Test
    fun `a recolor sends the note's content as it is, with only the color changed`() {
        val items = listOf(ChecklistItemDto(id = "c1", text = "milk", isChecked = true, order = 0))
        val original = NoteDto(
            id = "n1",
            type = NoteTypes.CHECKLIST,
            title = "Shopping",
            body = null,
            color = "yellow",
            checklistItems = items,
        )

        val op = colorOps(listOf(original), null).single() as PendingOp.Update

        assertEquals(NoteTypes.CHECKLIST, op.dto.type)
        assertEquals("Shopping", op.dto.title)
        assertNull(op.dto.body)
        assertEquals(items, op.dto.checklistItems)
        assertNull(op.dto.color)
        // Applied locally, the note differs from what it was in its color alone.
        val applied = applyOp(listOf(original), op).single()
        assertEquals(original.copy(color = null, updatedAtUtc = applied.updatedAtUtc), applied)
    }

    @Test
    fun `filing into a list keeps each note's other lists`() {
        val notes = listOf(note("n1", lists = listOf("a")), note("n2", lists = listOf("a", "b")), note("n3"))

        val ops = listMembershipOps(notes, "b", member = true).map { it as PendingOp.SetLists }

        assertEquals(listOf("n1", "n3"), ops.map { it.noteId })
        assertEquals(listOf("a", "b"), ops[0].listIds)
        assertEquals(listOf("b"), ops[1].listIds)
    }

    @Test
    fun `taking notes out of a list touches only the ones in it`() {
        val notes = listOf(note("n1", lists = listOf("a", "b")), note("n2", lists = listOf("a")))

        val ops = listMembershipOps(notes, "b", member = false).map { it as PendingOp.SetLists }

        assertEquals(listOf("n1"), ops.map { it.noteId })
        assertEquals(listOf("a"), ops.single().listIds)
    }

    @Test
    fun `membership is all, none, or some`() {
        val notes = listOf(note("n1", lists = listOf("a", "b")), note("n2", lists = listOf("a")))

        assertEquals(true, membershipOf(notes, "a"))
        assertNull(membershipOf(notes, "b"))
        assertEquals(false, membershipOf(notes, "c"))
        assertEquals(false, membershipOf(emptyList(), "a"))
    }

    @Test
    fun `a batch folds into the queue exactly as its ops would one by one`() {
        val queued = listOf<PendingOp>(
            PendingOp.Create("local-1", CreateNoteDto(title = "made offline")),
            PendingOp.SetState("n1", NoteStateDto(isPinned = true)),
        )
        val notes = listOf(note("local-1"), note("n1", pinned = true), note("n2"))

        val batch = stateOps(notes, NoteStateDto(isArchived = true)) +
            colorOps(notes, "green") +
            listMembershipOps(notes, "a", member = true)
        val result = batch.fold(queued, ::coalesce)

        // The note made offline takes its color and list in its create — one POST, as ever.
        val create = result.filterIsInstance<PendingOp.Create>().single()
        assertEquals("green", create.dto.color)
        assertEquals(listOf("a"), create.dto.listIds)
        // n1's archive merges into its queued pin rather than queueing a second state change.
        val n1State = result.filterIsInstance<PendingOp.SetState>().single { it.noteId == "n1" }
        assertEquals(NoteStateDto(isPinned = true, isArchived = true), n1State.state)
        assertEquals(1, result.count { it.targetId == "n2" && it is PendingOp.Update })
    }
}

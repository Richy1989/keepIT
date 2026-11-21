package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.ChecklistItemDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteFields
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.applyPending
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A queued checklist edit that carries the rows it started from ([UpdateNoteDto.checklistBase]) is
 * laid over the cache the way the server will merge it — so a sync that brings in a collaborator's
 * ticks while the edit is still waiting shows them, instead of the edit's whole list hiding them.
 */
class ChecklistMergeOpsTest {

    private fun row(id: String, checked: Boolean = false, order: Int = 0) =
        ChecklistItemDto(id = id, text = id, isChecked = checked, order = order)

    private val opened = listOf(row("milk", order = 0), row("bread", order = 1))

    private val note = NoteDto(id = "n1", type = NoteTypes.CHECKLIST, checklistItems = opened)

    private fun tick(rows: List<ChecklistItemDto>, id: String) =
        rows.map { if (it.id == id) it.copy(isChecked = true) else it }

    private fun update(items: List<ChecklistItemDto>, base: List<ChecklistItemDto>?, type: String = NoteTypes.CHECKLIST) =
        PendingOp.Update(
            "n1",
            UpdateNoteDto(type = type, checklistItems = items, checklistBase = base, fields = listOf(NoteFields.CHECKLIST_ITEMS)),
        )

    @Test
    fun `a queued tick over a fetch keeps the collaborator's tick`() {
        val fetched = note.copy(checklistItems = tick(opened, "bread"))
        val mine = update(tick(opened, "milk"), base = opened)

        val shown = applyPending(listOf(fetched), listOf(mine)).single()

        assertEquals(listOf(true, true), shown.checklistItems.map { it.isChecked })
    }

    @Test
    fun `a queued edit without a base replaces the list as before`() {
        val fetched = note.copy(checklistItems = tick(opened, "bread"))

        val shown = applyOp(listOf(fetched), update(tick(opened, "milk"), base = null)).single()

        assertEquals(listOf(true, false), shown.checklistItems.map { it.isChecked })
    }

    @Test
    fun `switching to text replaces the list whatever the base`() {
        val op = PendingOp.Update(
            "n1",
            UpdateNoteDto(
                type = NoteTypes.TEXT,
                body = "milk\nbread",
                checklistItems = emptyList(),
                checklistBase = opened,
                fields = listOf(NoteFields.TYPE, NoteFields.BODY, NoteFields.CHECKLIST_ITEMS),
            ),
        )
        val withSomeoneElsesRow = note.copy(checklistItems = opened + row("jam", order = 2))

        assertEquals(emptyList<ChecklistItemDto>(), applyOp(listOf(withSomeoneElsesRow), op).single().checklistItems)
    }

    @Test
    fun `two queued edits merge into one that starts from the first one's rows`() {
        val first = update(tick(opened, "milk"), base = opened)
        val second = update(tick(tick(opened, "milk"), "bread"), base = tick(opened, "milk"))

        val merged = coalesce(listOf(first), second).single() as PendingOp.Update

        assertEquals(opened, merged.dto.checklistBase)
        assertEquals(listOf(true, true), merged.dto.checklistItems!!.map { it.isChecked })
    }

    @Test
    fun `a checklist edit queued behind a title edit keeps its own base`() {
        val title = PendingOp.Update("n1", UpdateNoteDto(type = NoteTypes.CHECKLIST, title = "Shop", fields = listOf(NoteFields.TITLE)))
        val ticks = update(tick(opened, "milk"), base = opened)

        val merged = coalesce(listOf(title), ticks).single() as PendingOp.Update

        assertEquals(opened, merged.dto.checklistBase)
    }
}

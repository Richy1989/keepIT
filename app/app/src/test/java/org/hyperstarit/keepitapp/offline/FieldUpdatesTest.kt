package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.ChecklistItemDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteFields
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.changedFields
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.hyperstarit.keepitapp.data.offline.colorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An edit sends only the fields it changed. Queued offline, an edit replays long after it was made,
 * and sending the whole note then reverted whatever had changed meanwhile in the parts it never
 * touched — a collaborator's new title undone by an old edit to the text.
 */
class FieldUpdatesTest {

    private val note = NoteDto(
        id = "n1",
        type = NoteTypes.TEXT,
        title = "Trip",
        body = "tent",
        color = "teal",
        checklistItems = listOf(ChecklistItemDto(id = "i1", text = "milk", order = 0)),
    )

    private fun edit(
        title: String? = note.title,
        body: String? = note.body,
        color: String? = note.color,
        items: List<ChecklistItemDto>? = note.checklistItems,
    ) = UpdateNoteDto(type = note.type, title = title, body = body, color = color, checklistItems = items)

    @Test
    fun `only what differs from the note is named`() {
        assertEquals(listOf(NoteFields.BODY), changedFields(note, edit(body = "tent and stove")))
        assertEquals(listOf(NoteFields.TITLE, NoteFields.COLOR), changedFields(note, edit(title = "Lake", color = null)))
        assertEquals(emptyList<String>(), changedFields(note, edit()))
    }

    @Test
    fun `an editor compares with what it opened, not with what has synced in since`() {
        // Opened as "Trip"; a collaborator's rename then synced into the cache, but not the editor.
        val opened = edit()
        val saved = edit(body = "tent and stove")

        assertEquals(listOf(NoteFields.BODY), changedFields(opened, saved))
        // Against the cache, the editor's stale title would have looked like its own change.
        assertEquals(listOf(NoteFields.TITLE, NoteFields.BODY), changedFields(note.copy(title = "Trip to the lake"), saved))
    }

    @Test
    fun `a checklist row ticked, renamed or added changes the checklist`() {
        val ticked = listOf(ChecklistItemDto(id = "i1", text = "milk", isChecked = true, order = 0))
        val added = note.checklistItems + ChecklistItemDto(text = "eggs", order = 1)

        assertEquals(listOf(NoteFields.CHECKLIST_ITEMS), changedFields(note, edit(items = ticked)))
        assertEquals(listOf(NoteFields.CHECKLIST_ITEMS), changedFields(note, edit(items = added)))
    }

    @Test
    fun `a queued edit applies only its fields to the cache`() {
        val fresher = note.copy(title = "Trip to the lake")
        val op = PendingOp.Update("n1", edit(body = "tent and stove").copy(fields = listOf(NoteFields.BODY)))

        val shown = applyOp(listOf(fresher), op).single()

        assertEquals("Trip to the lake", shown.title)
        assertEquals("tent and stove", shown.body)
    }

    @Test
    fun `an edit without fields still applies all of it`() {
        val op = PendingOp.Update("n1", edit(title = "Lake", body = "stove"))

        val shown = applyOp(listOf(note), op).single()

        assertEquals("Lake", shown.title)
        assertEquals("stove", shown.body)
    }

    @Test
    fun `two queued edits merge into one naming both edits' fields`() {
        val first = PendingOp.Update("n1", edit(title = "Lake").copy(fields = listOf(NoteFields.TITLE)))
        val second = PendingOp.Update("n1", edit(title = "Lake", body = "stove").copy(fields = listOf(NoteFields.BODY)))

        val merged = coalesce(listOf(first), second).single() as PendingOp.Update

        assertEquals(listOf(NoteFields.TITLE, NoteFields.BODY), merged.dto.fields)
        assertEquals("stove", merged.dto.body)
    }

    @Test
    fun `an edit naming every field absorbs a narrower one`() {
        val old = PendingOp.Update("n1", edit(title = "Lake"))
        val narrow = PendingOp.Update("n1", edit(body = "stove").copy(fields = listOf(NoteFields.BODY)))

        assertNull((coalesce(listOf(old), narrow).single() as PendingOp.Update).dto.fields)
    }

    @Test
    fun `recolouring names only the colour`() {
        val op = colorOps(listOf(note.copy(canEdit = true)), "rose").single() as PendingOp.Update

        assertEquals(listOf(NoteFields.COLOR), op.dto.fields)
    }
}

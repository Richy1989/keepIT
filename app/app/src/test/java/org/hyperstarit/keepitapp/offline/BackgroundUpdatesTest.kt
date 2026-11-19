package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteFields
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.changedFields
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.hyperstarit.keepitapp.data.offline.colorOps
import org.hyperstarit.keepitapp.data.offline.forServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A note's background is set only by an edit naming it, as the server sets it: an edit without
 * fields — all an app older than backgrounds can send — leaves it alone. And a server older than
 * 1.3 refuses an edit naming it, so the outbox leaves it out when sending to one.
 */
class BackgroundUpdatesTest {

    private val note = NoteDto(id = "n1", type = NoteTypes.TEXT, title = "Budget", color = "sky", background = "lined")

    private fun edit(title: String? = note.title, background: String? = note.background, fields: List<String>? = null) =
        UpdateNoteDto(type = note.type, title = title, color = note.color, background = background, fields = fields)

    @Test
    fun `a changed background is named`() {
        assertEquals(listOf(NoteFields.BACKGROUND), changedFields(note, edit(background = "dots")))
        assertEquals(listOf(NoteFields.BACKGROUND), changedFields(note, edit(background = null)))
        assertEquals(emptyList<String>(), changedFields(note, edit()))
    }

    @Test
    fun `an edit without fields leaves the background as it is`() {
        val shown = applyOp(listOf(note), PendingOp.Update("n1", edit(title = "October", background = null))).single()

        assertEquals("October", shown.title)
        assertEquals("lined", shown.background)
    }

    @Test
    fun `an edit naming the background sets it`() {
        val op = PendingOp.Update("n1", edit(background = null, fields = listOf(NoteFields.BACKGROUND)))

        assertNull(applyOp(listOf(note), op).single().background)
    }

    @Test
    fun `a background edit merged with one naming nothing names everything`() {
        val whole = PendingOp.Update("n1", edit(title = "October", background = null))
        val bg = PendingOp.Update("n1", edit(title = "October", background = "dots", fields = listOf(NoteFields.BACKGROUND)))

        val merged = coalesce(listOf(whole), bg).single() as PendingOp.Update

        assertEquals(NoteFields.SET_BY_DEFAULT + NoteFields.BACKGROUND, merged.dto.fields)
        assertEquals("dots", merged.dto.background)
    }

    @Test
    fun `a later edit keeps the background an earlier one set`() {
        val bg = PendingOp.Update("n1", edit(background = "dots", fields = listOf(NoteFields.BACKGROUND)))
        // Built from the cache before the first edit applied to it: it still carries the old one.
        val title = PendingOp.Update("n1", edit(title = "October", fields = listOf(NoteFields.TITLE)))

        val merged = coalesce(listOf(bg), title).single() as PendingOp.Update

        assertEquals(listOf(NoteFields.BACKGROUND, NoteFields.TITLE), merged.dto.fields)
        assertEquals("dots", merged.dto.background)
        assertEquals("October", merged.dto.title)
    }

    @Test
    fun `an edit folded into a queued create sets the background only when it names it`() {
        val create = PendingOp.Create("temp-1", CreateNoteDto(title = "New", background = "lined"))

        val untouched = coalesce(listOf(create), PendingOp.Update("temp-1", edit(title = "Newer", background = null)))
        assertEquals("lined", (untouched.single() as PendingOp.Create).dto.background)

        val changed = coalesce(listOf(create), PendingOp.Update("temp-1", edit(background = "dots", fields = listOf(NoteFields.BACKGROUND))))
        assertEquals("dots", (changed.single() as PendingOp.Create).dto.background)
    }

    @Test
    fun `an older server gets the edit without its background`() {
        val dto = edit(title = "October", background = "dots", fields = listOf(NoteFields.TITLE, NoteFields.BACKGROUND))

        assertEquals(dto, dto.forServer(storesBackgrounds = true))
        val old = dto.forServer(storesBackgrounds = false)
        assertEquals(listOf(NoteFields.TITLE), old.fields)
        assertNull(old.background)

        // An edit of nothing but the background becomes an edit of nothing, which the server ignores.
        assertEquals(emptyList<String>(), edit(fields = listOf(NoteFields.BACKGROUND)).forServer(false).fields)
        // An edit without fields never set the background anyway.
        val whole = edit(title = "October")
        assertEquals(whole, whole.forServer(false))
    }

    @Test
    fun `recolouring keeps the background`() {
        val op = colorOps(listOf(note.copy(canEdit = true)), "rose").single() as PendingOp.Update

        assertEquals("lined", op.dto.background)
        assertEquals(listOf(NoteFields.COLOR), op.dto.fields)
    }
}

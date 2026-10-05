package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The server's limits, applied on the phone before anything is queued. A note over a limit used to be
 * queued, refused on upload and then lost: these pin that whatever leaves the repository is
 * something the server accepts.
 */
class NoteLimitsTest {

    @Test
    fun `content within the limits is left alone`() {
        val dto = CreateNoteDto(
            type = NoteTypes.CHECKLIST,
            title = "t".repeat(NoteLimits.TITLE),
            body = "b".repeat(NoteLimits.BODY),
            color = "teal",
            checklistItems = List(NoteLimits.CHECKLIST_ITEMS) { ChecklistItemDto(text = "i".repeat(NoteLimits.CHECKLIST_ITEM_TEXT), order = it) },
        )

        assertEquals(dto, NoteLimits.clamp(dto))
    }

    @Test
    fun `everything over a limit is cut to it`() {
        val clamped = NoteLimits.clamp(
            CreateNoteDto(
                type = NoteTypes.CHECKLIST,
                title = "t".repeat(NoteLimits.TITLE + 500),
                body = "b".repeat(NoteLimits.BODY + 1),
                checklistItems = List(NoteLimits.CHECKLIST_ITEMS + 100) {
                    ChecklistItemDto(text = "i".repeat(NoteLimits.CHECKLIST_ITEM_TEXT + 1), order = it)
                },
            ),
        )

        assertEquals(NoteLimits.TITLE, clamped.title!!.length)
        assertEquals(NoteLimits.BODY, clamped.body!!.length)
        assertEquals(NoteLimits.CHECKLIST_ITEMS, clamped.checklistItems!!.size)
        assertEquals(NoteLimits.CHECKLIST_ITEM_TEXT, clamped.checklistItems!!.maxOf { it.text.length })
        // The first rows are the ones kept, in their order.
        assertEquals((0 until NoteLimits.CHECKLIST_ITEMS).toList(), clamped.checklistItems!!.map { it.order })
    }

    @Test
    fun `an edit is held to the same limits`() {
        val clamped = NoteLimits.clamp(UpdateNoteDto(type = NoteTypes.TEXT, title = "t".repeat(2_000), body = "b".repeat(200_000)))

        assertEquals(NoteLimits.TITLE, clamped.title!!.length)
        assertEquals(NoteLimits.BODY, clamped.body!!.length)
    }

    @Test
    fun `a cut never leaves half an emoji behind`() {
        // "😀" is two UTF-16 units; the limit falls between them.
        val text = "a".repeat(NoteLimits.TITLE - 1) + "😀"

        val cut = NoteLimits.cut(text, NoteLimits.TITLE)

        assertEquals("a".repeat(NoteLimits.TITLE - 1), cut)
    }

    @Test
    fun `a colour the server would refuse becomes no colour`() {
        assertNull(NoteLimits.clamp(CreateNoteDto(color = "c".repeat(NoteLimits.COLOR + 1))).color)
        assertEquals("teal", NoteLimits.clamp(CreateNoteDto(color = "teal")).color)
    }

    @Test
    fun `list names are cut to the list limit`() {
        assertEquals(NoteLimits.LIST_NAME, NoteLimits.clampListName("l".repeat(300)).length)
        val short = "Groceries"
        assertSame(short, NoteLimits.clampListName(short))
    }
}

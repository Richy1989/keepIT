package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.ChecklistItemDto
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.offline.rescueFileName
import org.hyperstarit.keepitapp.data.offline.rescuedMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * What a refused note change leaves in Documents/keepIT: the text the server turned down, as the
 * Markdown it was written in, under a name that says what it was.
 */
class NoteTextRescueTest {

    @Test
    fun `a text note keeps its title and body`() {
        assertEquals(
            "# Trip plan\n\nPack the **tent**\n",
            rescuedMarkdown(NoteTypes.TEXT, "Trip plan", "Pack the **tent**", null),
        )
    }

    @Test
    fun `a checklist keeps its rows in order with their ticks`() {
        val items = listOf(
            ChecklistItemDto(text = "Eggs", isChecked = true, order = 1),
            ChecklistItemDto(text = "Milk", order = 0),
            ChecklistItemDto(text = "  ", order = 2),
        )

        assertEquals(
            "# Groceries\n\n- [ ] Milk\n- [x] Eggs\n",
            rescuedMarkdown(NoteTypes.CHECKLIST, "Groceries", null, items),
        )
    }

    @Test
    fun `a note with both sides keeps both, the active one first`() {
        val items = listOf(ChecklistItemDto(text = "Milk", order = 0))

        assertEquals("- [ ] Milk\n\nnotes\n", rescuedMarkdown(NoteTypes.CHECKLIST, null, "notes", items))
        assertEquals("notes\n\n- [ ] Milk\n", rescuedMarkdown(NoteTypes.TEXT, null, "notes", items))
    }

    @Test
    fun `a note with no text has nothing to rescue`() {
        assertNull(rescuedMarkdown(NoteTypes.TEXT, " ", "", emptyList()))
    }

    @Test
    fun `the file is named after the note and stamped`() {
        val at = Instant.parse("2026-10-05T07:16:06Z").toEpochMilli()

        assertEquals("Trip plan (2026-10-05 07-16-06).md", rescueFileName("Trip plan", at, ZoneOffset.UTC))
        assertEquals("a b c (2026-10-05 07-16-06).md", rescueFileName("a/b:c\n", at, ZoneOffset.UTC))
        assertEquals("Note (2026-10-05 07-16-06).md", rescueFileName("  ", at, ZoneOffset.UTC))
        assertEquals(60 + " (2026-10-05 07-16-06).md".length, rescueFileName("T".repeat(1_500), at, ZoneOffset.UTC).length)
    }
}

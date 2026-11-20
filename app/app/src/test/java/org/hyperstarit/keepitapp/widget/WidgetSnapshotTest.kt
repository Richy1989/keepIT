package org.hyperstarit.keepitapp.widget

import org.hyperstarit.keepitapp.data.ChecklistItemDto
import org.hyperstarit.keepitapp.data.NoteBackgroundNames
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.NotesRepository
import org.hyperstarit.keepitapp.data.WidgetNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests for the widget's data contract — the snapshot the app writes to SharedPrefs and the
 * widget process reads back on its next render.
 *
 * Why this is worth pinning: the two halves run in *different processes at different times*. A
 * snapshot written by the build the user had yesterday is decoded by the build they installed
 * today, and a decode that throws takes the whole Glance composition down — the widget then sits on
 * its loading layout with nothing in our own logs to say why. So the projection's shape and the
 * codec's tolerance are pinned here rather than discovered on someone's home screen.
 */
class WidgetSnapshotTest {

    private fun text(
        id: String,
        title: String? = null,
        body: String? = null,
        color: String? = null,
        background: String? = null,
    ) = NoteDto(id = id, type = NoteTypes.TEXT, title = title, body = body, color = color, background = background)

    private fun checklist(id: String, vararg rows: Pair<String, Boolean>) = NoteDto(
        id = id,
        type = NoteTypes.CHECKLIST,
        title = "Checklist",
        checklistItems = rows.mapIndexed { i, (label, checked) ->
            ChecklistItemDto(id = "$id-$i", text = label, isChecked = checked, order = i)
        },
    )

    // --- projection -----------------------------------------------------------------------------

    @Test
    fun `only the first six notes reach the widget`() {
        val projected = NotesRepository.widgetNotesFrom((1..10).map { text("n$it", title = "Note $it") })

        assertEquals(6, projected.size)
        assertEquals(listOf("n1", "n2", "n3", "n4", "n5", "n6"), projected.map { it.id })
    }

    @Test
    fun `the note's colour key rides along so the widget can tint the row`() {
        // The widget resolves this key through KeepItPalette.swatch(); dropping it here would
        // silently repaint every row the default grey.
        val projected = NotesRepository.widgetNotesFrom(listOf(text("n1", title = "Tinted", color = "sky")))

        assertEquals("sky", projected.single().color)
    }

    @Test
    fun `an uncoloured note keeps a null key rather than inventing a default`() {
        assertEquals(null, NotesRepository.widgetNotesFrom(listOf(text("n1"))).single().color)
    }

    @Test
    fun `the note's background rides along so the widget can tile it over the colour`() {
        val projected = NotesRepository.widgetNotesFrom(
            listOf(text("n1", color = "sky", background = "lined"), text("n2")),
        )

        assertEquals(listOf("lined", null), projected.map { it.background })
    }

    @Test
    fun `every background this build ships has a widget drawable`() {
        // The generated map (noteBackgroundDrawables in app/build.gradle.kts) and the folder the
        // picker offers must agree, or a background chosen in the app is blank on the home screen.
        val files = File("../../backgrounds").listFiles().orEmpty().map { it.name }
        val (shipped) = NoteBackgroundNames.parse(files)
        assertTrue("backgrounds/ not found from ${File(".").absolutePath}", shipped.isNotEmpty())

        assertEquals(emptyList<String>(), shipped.map { it.name }.filter { WidgetBackgroundDrawables.of(it) == null })
        assertNull(WidgetBackgroundDrawables.of(null))
        assertNull("a name from a newer build draws nothing", WidgetBackgroundDrawables.of("from-the-future"))
    }

    @Test
    fun `a text note's preview is stripped of Markdown and flattened to one line`() {
        val projected = NotesRepository.widgetNotesFrom(
            listOf(text("n1", body = "# Heading\n**bold** and `code`"))
        ).single()

        assertEquals("Heading bold and code", projected.preview)
        assertTrue("checklist lines belong to checklist notes", projected.checklist.isEmpty())
    }

    @Test
    fun `a long body is capped so one note can't crowd out the rest`() {
        val projected = NotesRepository.widgetNotesFrom(listOf(text("n1", body = "x".repeat(500)))).single()

        assertEquals(100, projected.preview.length)
    }

    @Test
    fun `a blank title normalises to empty, so the widget's isNotBlank check is enough`() {
        assertEquals("", NotesRepository.widgetNotesFrom(listOf(text("n1", title = "   "))).single().title)
        assertEquals("", NotesRepository.widgetNotesFrom(listOf(text("n1", title = null))).single().title)
    }

    @Test
    fun `a checklist note becomes the lines still to do, capped at four, and a count of the ticked`() {
        val projected = NotesRepository.widgetNotesFrom(
            listOf(
                checklist(
                    "n1",
                    "Milk" to true, "Eggs" to false, "Bread" to false, "Jam" to false, "Tea" to false, "Rice" to false,
                ),
            )
        ).single()

        assertEquals(listOf("☐ Eggs", "☐ Bread", "☐ Jam", "☐ Tea"), projected.checklist)
        assertEquals(1, projected.ticked)
        assertEquals("", projected.preview)
    }

    @Test
    fun `a ticked row never takes a line, even when there is room for it`() {
        // The note's card leaves ticked items off too: the widget says how many, not which.
        val projected = NotesRepository.widgetNotesFrom(
            listOf(checklist("n1", "Done" to true, "Left" to false, "Also done" to true))
        ).single()

        assertEquals(listOf("☐ Left"), projected.checklist)
        assertEquals(2, projected.ticked)
    }

    @Test
    fun `a checklist with everything ticked leaves only the count`() {
        val projected = NotesRepository.widgetNotesFrom(listOf(checklist("n1", "Done" to true))).single()

        assertTrue(projected.checklist.isEmpty())
        assertEquals(1, projected.ticked)
    }

    @Test
    fun `a text note counts no ticked items`() {
        assertEquals(0, NotesRepository.widgetNotesFrom(listOf(text("n1", body = "- [x] done"))).single().ticked)
    }

    // --- codec ----------------------------------------------------------------------------------

    @Test
    fun `a snapshot survives the round trip through the prefs blob`() {
        val snapshot = NotesRepository.widgetNotesFrom(
            listOf(
                text("n1", title = "Hi", body = "there", color = "amber"),
                checklist("n2", "Milk" to false, "Eggs" to true),
            )
        )

        val decoded = NotesRepository.decodeWidgetSnapshot(NotesRepository.encodeWidgetSnapshot(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `a snapshot from an older build decodes, with the fields it never wrote defaulted`() {
        // WidgetNote gained `checklist` after the first release, `ticked` in 1.1 and `background`
        // in 1.4.2; a blob
        // written before either must still decode rather than wedge the widget on its loading layout.
        val legacy = """[{"id":"n1","title":"Old","preview":"body","color":null}]"""

        val decoded = NotesRepository.decodeWidgetSnapshot(legacy)

        assertEquals(listOf(WidgetNote("n1", "Old", "body", null, emptyList())), decoded)
        assertNull(decoded.single().background)
    }

    @Test
    fun `a snapshot from a newer build decodes, ignoring fields this build doesn't know`() {
        val futureField = """[{"id":"n1","title":"New","preview":"","color":"sage","checklist":[],"pinned":true}]"""

        assertEquals("sage", NotesRepository.decodeWidgetSnapshot(futureField).single().color)
    }

    @Test
    fun `a corrupt or absent blob reads as no notes instead of throwing`() {
        assertEquals(emptyList<WidgetNote>(), NotesRepository.decodeWidgetSnapshot(null))
        assertEquals(emptyList<WidgetNote>(), NotesRepository.decodeWidgetSnapshot(""))
        assertEquals(emptyList<WidgetNote>(), NotesRepository.decodeWidgetSnapshot("{not json"))
        assertEquals(emptyList<WidgetNote>(), NotesRepository.decodeWidgetSnapshot("""[{"no":"id"}]"""))
    }
}

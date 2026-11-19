package org.hyperstarit.keepitapp.ui.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hyperstarit.keepitapp.ui.theme.KeepItPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Highlighted text reads and edits as on the web: both are held to the web's `highlight.json`, so
 * a note highlighted on either client looks the same on the other.
 */
class HighlightMarksTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val cases: JsonObject by lazy {
        val file = File("../../web/src/features/notes/highlight.json")
        assertTrue("highlight.json not found at ${file.absolutePath}", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `highlight json - rendering`() {
        val palette = KeepItPalette.Dim
        val names = palette.highlights.entries.associate { (name, fill) -> fill to name }
        for (case in cases.getValue("render").jsonArray.map { it.jsonObject }) {
            val source = case.getValue("source").jsonPrimitive.content
            val out = markdownToAnnotated(source, palette)
            assertEquals(source, case.getValue("text").jsonPrimitive.content, out.text)
            val marks = out.spanStyles
                .filter { names.containsKey(it.item.background) }
                .sortedWith(compareBy({ it.start }, { -it.end }))
                .map { names.getValue(it.item.background) to out.text.substring(it.start, it.end) }
            val expected = case.getValue("marks").jsonArray.map {
                it.jsonObject.getValue("color").jsonPrimitive.content to it.jsonObject.getValue("text").jsonPrimitive.content
            }
            assertEquals(source, expected, marks)
        }
    }

    @Test
    fun `highlight json - the highlighter`() {
        for (case in cases.getValue("edit").jsonArray.map { it.jsonObject }) {
            val value = case.getValue("value").jsonPrimitive.content
            val start = value.indexOf('«')
            val end = value.indexOf('»') - 1
            val plain = value.replaceFirst("«", "").replaceFirst("»", "")
            val color = case.getValue("color").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val edit = HighlightMarks.apply(plain, start, end, color)
            val shown = edit.value.substring(0, edit.selectionStart) + "«" +
                edit.value.substring(edit.selectionStart, edit.selectionEnd) + "»" +
                edit.value.substring(edit.selectionEnd)
            assertEquals("$value with $color", case.getValue("result").jsonPrimitive.content, shown)
        }
    }

    @Test
    fun `highlighted list items stay list items, each with its own highlight`() {
        val edit = HighlightMarks.apply("- one\n- [ ] two", 0, 15, "green")
        val out = markdownToAnnotated(edit.value, KeepItPalette.Dim)
        val fill = KeepItPalette.Dim.highlight("green")
        val marked = out.spanStyles.filter { it.item.background == fill }.map { out.text.substring(it.start, it.end) }
        assertEquals(listOf("one", "two"), marked)
        assertTrue(out.text, "- " !in out.text && "<mark" !in out.text)
    }

    @Test
    fun `the editor dims the tags and fills the highlight`() {
        val source = "a <mark data-color=\"green\">b</mark> c"
        val styled = markdownHighlight(source, KeepItPalette.Light)
        val fill = styled.spanStyles.single { it.item.background == KeepItPalette.Light.highlight("green") }
        assertEquals("<mark data-color=\"green\">b</mark>", source.substring(fill.start, fill.end))
        val dimmed = styled.spanStyles.filter { it.item.color == KeepItPalette.Light.textFaint }.map { source.substring(it.start, it.end) }
        assertEquals(listOf("<mark data-color=\"green\">", "</mark>"), dimmed)
    }

    @Test
    fun `a preview and search read the words, not the tags`() {
        assertEquals("a b c", stripMarkdown("a <mark data-color=\"pink\">b</mark> c"))
        assertEquals("a b c", HighlightMarks.withoutTags("a <mark data-color=\"pink\">b</mark> c"))
    }
}

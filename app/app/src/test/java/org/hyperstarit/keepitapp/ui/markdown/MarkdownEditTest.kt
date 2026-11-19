package org.hyperstarit.keepitapp.ui.markdown

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The formatting toolbar and Enter in a note body, held to the web's `markdownEdit.json` (which its
 * own `markdownEdit.test.ts` runs too), so a note edited on either client gets the same Markdown.
 * « and » mark the selection, so each case reads as what the user sees before and after.
 */
class MarkdownEditTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val cases: JsonObject by lazy {
        val file = File("../../web/src/features/notes/markdownEdit.json")
        assertTrue("markdownEdit.json not found at ${file.absolutePath}", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun cases(name: String) = cases.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    /** "a «bold» b" → a selection over "bold"; "he«»llo" → a caret at 2. */
    private fun field(marked: String): TextFieldValue {
        val start = marked.indexOf('«')
        val end = marked.indexOf('»') - 1
        return TextFieldValue(marked.replaceFirst("«", "").replaceFirst("»", ""), TextRange(start, end))
    }

    private fun show(value: TextFieldValue): String {
        val s = value.selection
        return value.text.substring(0, s.min) + "«" + value.text.substring(s.min, s.max) + "»" + value.text.substring(s.max)
    }

    @Test
    fun `markdownEdit json - the toolbar`() {
        for (case in cases("apply")) {
            val action = MarkdownAction.valueOf(case.text("action").uppercase())
            val value = case.text("value")
            assertEquals("$action on $value", case.text("result"), show(applyMarkdown(field(value), action)))
        }
    }

    @Test
    fun `markdownEdit json - Enter`() {
        for (case in cases("enter")) {
            val value = case.text("value")
            val old = field(value)
            val pos = old.selection.start
            // What the keyboard hands the field: the same text with one newline typed at the caret.
            val typed = TextFieldValue(old.text.take(pos) + "\n" + old.text.substring(pos), TextRange(pos + 1))
            assertEquals(value, case.text("result"), show(continueListOnEnter(old, typed)))
        }
    }

    @Test
    fun `only a single typed newline is ever rewritten`() {
        val old = TextFieldValue("- a", TextRange(3))
        val pasted = TextFieldValue("- a\nb\nc", TextRange(7))
        assertEquals(pasted, continueListOnEnter(old, pasted))
        val selected = TextFieldValue("- abc", TextRange(3, 5))
        val replaced = TextFieldValue("- a\n", TextRange(4))
        assertEquals(replaced, continueListOnEnter(selected, replaced))
    }

    @Test
    fun `a fence counts only from the line below it`() {
        assertEquals(false, insideCodeFence("```\n", 0))
        assertEquals(true, insideCodeFence("```\n", 4))
        assertEquals(false, insideCodeFence("```\nx\n```\n", 10))
    }
}

package org.hyperstarit.keepitapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What pasting several lines into a checklist row makes of them. The cases are the web's
 * (`checklistPaste.json`, which its own `checklist.test.ts` runs too), so a list pasted on the
 * phone comes out as it does in the browser: the same arrangement as ListIconsParityTest, with the
 * web as the canonical side.
 */
class ChecklistPasteTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val cases by lazy {
        val file = File("../../web/src/features/notes/checklistPaste.json")
        assertTrue("checklistPaste.json not found at ${file.absolutePath} - run from the repository checkout", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
    }

    @Test
    fun `a paste comes out as it does on the web`() {
        assertTrue("checklistPaste.json holds no cases", cases.isNotEmpty())
        for (case in cases) {
            fun text(key: String) = case.getValue(key).jsonPrimitive.content
            val expected = PastedRows(
                rows = case.getValue("rows").jsonArray.map { it.jsonPrimitive.content },
                caret = case.getValue("caret").jsonPrimitive.int,
            )

            assertEquals(
                text("name"),
                expected,
                splitPastedRow(text("head"), text("tail"), case.getValue("room").jsonPrimitive.int),
            )
        }
    }

    @Test
    fun `a row is cut to what the server accepts, without splitting an emoji`() {
        val long = "b".repeat(NoteLimits.CHECKLIST_ITEM_TEXT - 1) + "\uD83D\uDE00 and more"

        val pasted = splitPastedRow("a\n$long", "", 499)

        assertEquals(listOf("a", "b".repeat(NoteLimits.CHECKLIST_ITEM_TEXT - 1)), pasted.rows)
        assertEquals(NoteLimits.CHECKLIST_ITEM_TEXT - 1, pasted.caret)
    }

    @Test
    fun `there are never more rows than there is room for`() {
        val lines = (0 until 600).joinToString("\n") { "line $it" }

        assertEquals(11, splitPastedRow(lines, "", 10).rows.size)
        assertEquals("line 10kept", splitPastedRow(lines, "kept", 10).rows.last())
    }
}

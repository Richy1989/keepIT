package org.hyperstarit.keepitapp.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What switching a note between text and checklist makes of it. The cases are the web's
 * (`checklistConvert.json`, which its own `checklist.test.ts` runs too), so a note converts on the
 * phone as it does in the browser: the same arrangement as [ChecklistPasteTest].
 */
class ChecklistConvertTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val cases by lazy {
        val file = File("../../web/src/features/notes/checklistConvert.json")
        assertTrue("checklistConvert.json not found at ${file.absolutePath} - run from the repository checkout", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun group(name: String): List<JsonObject> =
        cases.getValue(name).jsonArray.map { it.jsonObject }.also { assertTrue("no $name cases", it.isNotEmpty()) }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `a text becomes a checklist as it does on the web`() {
        for (case in group("toChecklist")) {
            val expected = case.getValue("items").jsonArray.map {
                val (text, isChecked) = it.jsonArray
                TextItem(text.jsonPrimitive.content, isChecked.jsonPrimitive.boolean)
            }

            assertEquals(case.text("name"), expected, checklistFromText(case.text("text")))
        }
    }

    @Test
    fun `a checklist becomes a text as it does on the web`() {
        for (case in group("toText")) {
            val items = case.getValue("items").jsonArray.map { it.jsonPrimitive.content }

            assertEquals(case.text("name"), case.text("text"), textFromChecklist(items))
        }
    }

    @Test
    fun `a text with more lines than a checklist holds is turned down`() {
        fun lines(count: Int) = (0 until count).joinToString("\n") { "line $it" }

        assertEquals(NoteLimits.CHECKLIST_ITEMS, checklistFromText(lines(NoteLimits.CHECKLIST_ITEMS))?.size)
        assertNull(checklistFromText(lines(NoteLimits.CHECKLIST_ITEMS + 1)))
        // Blank lines make no items, so they don't count against the limit.
        assertEquals(NoteLimits.CHECKLIST_ITEMS, checklistFromText(lines(NoteLimits.CHECKLIST_ITEMS) + "\n\n\n")?.size)
    }

    @Test
    fun `a text with a line longer than an item holds is turned down`() {
        assertEquals(1, checklistFromText("- " + "a".repeat(NoteLimits.CHECKLIST_ITEM_TEXT))?.size)
        assertNull(checklistFromText("a".repeat(NoteLimits.CHECKLIST_ITEM_TEXT + 1)))
    }

    @Test
    fun `a list made from a text gives that text back`() {
        val text = "Milk\nBread\nEggs"

        assertEquals(text, textFromChecklist(checklistFromText(text).orEmpty().map { it.text }))
    }
}

package org.hyperstarit.keepitapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which ticked row is offered while a new checklist row is typed. The cases are the web's
 * (`checklistSuggest.json`, which its own `checklist.test.ts` runs too), so the phone offers the
 * row the browser would: the same arrangement as ChecklistPasteTest, with the web as the canonical
 * side.
 */
class ChecklistSuggestTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val cases by lazy {
        val file = File("../../web/src/features/notes/checklistSuggest.json")
        assertTrue("checklistSuggest.json not found at ${file.absolutePath} - run from the repository checkout", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
    }

    @Test
    fun `the offer is the one the web makes`() {
        assertTrue("checklistSuggest.json holds no cases", cases.isNotEmpty())
        for (case in cases) {
            val items = case.getValue("items").jsonArray.mapIndexed { order, it ->
                val item = it.jsonObject
                ChecklistItemDto(
                    text = item.getValue("text").jsonPrimitive.content,
                    isChecked = item.getValue("isChecked").jsonPrimitive.boolean,
                    order = order,
                )
            }

            assertEquals(
                case.getValue("name").jsonPrimitive.content,
                case.getValue("offer").jsonPrimitive.intOrNull,
                suggestFromChecked(items, case.getValue("typing").jsonPrimitive.int),
            )
        }
    }
}

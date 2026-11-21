package org.hyperstarit.keepitapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's copy of the checklist merge ([mergeChecklist]): two people working one list at once,
 * each saving the rows they started from ([UpdateNoteDto.checklistBase]) with their own. The cases
 * are the web's (`checklistMerge.json`), which the web's `checklistMerge.test.ts` and the server's
 * `ChecklistMergeCasesTests` run too, so a save merges the same on the phone, in the browser's cache
 * and on the server.
 */
class ChecklistMergeTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val cases by lazy {
        val file = File("../../web/src/features/notes/checklistMerge.json")
        assertTrue("checklistMerge.json not found at ${file.absolutePath} - run from the repository checkout", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
    }

    /** Rows as the cases write them: an id (or null), the text, and only what isn't the default. */
    private fun rows(json: JsonArray) = json.mapIndexed { order, element ->
        val row = element.jsonObject
        ChecklistItemDto(
            id = row["id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
            text = row.getValue("text").jsonPrimitive.content,
            isChecked = row["isChecked"]?.jsonPrimitive?.boolean ?: false,
            indent = row["indent"]?.jsonPrimitive?.int ?: 0,
            order = order,
        )
    }

    @Test
    fun `a save merges as it does on the server and on the web`() {
        assertTrue("checklistMerge.json holds no cases", cases.isNotEmpty())
        for (case in cases) {
            fun list(key: String) = rows(case.getValue(key).jsonArray)

            val merged = mergeChecklist(list("base"), list("edit"), list("current"))

            assertEquals(case.getValue("name").jsonPrimitive.content, list("merged"), merged)
        }
    }
}

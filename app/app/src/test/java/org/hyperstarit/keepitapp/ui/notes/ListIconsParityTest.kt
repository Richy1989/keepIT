package org.hyperstarit.keepitapp.ui.notes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.hyperstarit.keepitapp.data.NoteLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds [ListIcons] to the web's `listIcons.json`, so both pickers offer the same emoji in the same
 * order — the same arrangement as the About page (AboutContentParityTest), where the web is the
 * canonical side and this app keeps a hand copy.
 */
class ListIconsParityTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val web: List<String> by lazy {
        val file = File("../../web/src/features/lists/listIcons.json")
        assertTrue("listIcons.json not found at ${file.absolutePath} - run from the repository checkout", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun `the picker offers the web's emoji, in the same order`() {
        assertEquals(web, ListIcons.all)
    }

    @Test
    fun `every emoji is an icon the server accepts, and fills a row of the grid`() {
        for (icon in ListIcons.all) assertEquals(icon, NoteLimits.listIconOrNull(icon))
        assertEquals(ListIcons.all.size, ListIcons.all.toSet().size)
        assertEquals(0, ListIcons.all.size % ListIcons.COLUMNS)
    }
}

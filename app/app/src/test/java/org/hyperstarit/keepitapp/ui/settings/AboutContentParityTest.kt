package org.hyperstarit.keepitapp.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

/**
 * Holds [AboutContent] to the web's `about.json`, so the two About pages say the same thing.
 *
 * The About page is one page in two clients, and AboutContent is a hand copy of the web's — the
 * same arrangement as the colour tokens (WebTokenParityTest), where the web is the canonical side.
 * Everything both pages show is compared: name, tagline, description, links, thanks, copyright and
 * the server's credits. Each client's own credits are its own business, and only checked for
 * being complete.
 */
class AboutContentParityTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val web: JsonObject by lazy {
        val file = File("../../web/src/features/about/about.json")
        assertTrue("about.json not found at ${file.absolutePath} - run from the repository checkout", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `the name, tagline, thanks and copyright are the web's`() {
        assertEquals(web.text("name"), AboutContent.NAME)
        assertEquals(web.text("tagline"), AboutContent.TAGLINE)
        assertEquals(web.text("thanks"), AboutContent.THANKS)
        assertEquals(web.text("copyright"), AboutContent.COPYRIGHT)
    }

    @Test
    fun `the description is the web's, paragraph by paragraph`() {
        val paragraphs = web.getValue("description").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(paragraphs, AboutContent.description)
    }

    @Test
    fun `the links are the web's, in the same order`() {
        val links = web.getValue("links").jsonArray.map {
            val link = it.jsonObject
            AboutLink(link.text("id"), link.text("label"), link.text("detail"), link.text("url"))
        }
        assertEquals(links, AboutContent.links)
    }

    @Test
    fun `the server's credits are the web's, in the same order`() {
        val credits = web.getValue("credits").jsonObject.getValue("server").jsonArray.map {
            val credit = it.jsonObject
            Credit(credit.text("name"), credit.text("role"), credit.text("license"), credit.text("url"))
        }
        assertEquals(credits, AboutContent.serverCredits)
    }

    @Test
    fun `every credit is complete, listed once and links to https`() {
        for (group in listOf(AboutContent.androidCredits, AboutContent.serverCredits)) {
            assertEquals("a project is listed twice", group.size, group.map { it.name }.toSet().size)
            for (credit in group) {
                assertTrue("${credit.name} has no role", credit.role.isNotBlank())
                assertTrue("${credit.name} has no licence", credit.license.isNotBlank())
                assertEquals("${credit.name}: ${credit.url}", "https", URI(credit.url).scheme)
            }
        }
    }
}

package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What Android copies off the device in a backup or a transfer to a new phone. The rules are XML
 * the build never checks against the code, and both ways of getting them wrong are silent:
 *
 * - **Too little excluded.** The refresh cookie travels, and a restored copy is either a working
 *   sign-in sitting in a backup or a replay the server answers by ending every session on the
 *   account. Renaming [ApiClient.PREFS_NAME] is enough to get here.
 * - **Too much excluded.** For a standalone user the offline store and staged images are the only
 *   copy of their notes. An `<include>` anywhere flips a section to allow-list, which drops them.
 *
 * Paths are matched the way the backup framework matches them: by path component, so excluding
 * `offline/media` does not reach `offline/media-staging`.
 */
class BackupRulesTest {

    /** Gradle runs unit tests from the module directory. */
    private val res = File("src/main/res/xml")

    private data class Rule(val domain: String, val path: String)

    /** Each section's rules: cloud backup and device transfer, then the pre-12 form. */
    private val sections: Map<String, List<Element>> by lazy {
        val extraction = parse("data_extraction_rules.xml")
        val legacy = parse("backup_rules.xml")
        mapOf(
            "cloud-backup" to children(extraction, "cloud-backup"),
            "device-transfer" to children(extraction, "device-transfer"),
            "full-backup-content" to legacy.childElements(),
        )
    }

    private fun parse(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, name)).documentElement

    private fun children(root: Element, section: String): List<Element> {
        val matches = root.childElements().filter { it.tagName == section }
        assertEquals("data_extraction_rules.xml needs exactly one <$section>", 1, matches.size)
        return matches.single().childElements()
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun excludes(rules: List<Element>): List<Rule> =
        rules.filter { it.tagName == "exclude" }.map { Rule(it.getAttribute("domain"), it.getAttribute("path")) }

    private fun covers(rule: Rule, domain: String, path: String): Boolean =
        rule.domain == domain && Paths.get(path).normalize().startsWith(Paths.get(rule.path).normalize())

    @Test
    fun `the refresh cookie never leaves the device`() {
        for ((section, rules) in sections) {
            assertTrue(
                "<$section> must exclude the prefs file holding the refresh cookie",
                excludes(rules).any { covers(it, "sharedpref", "${ApiClient.PREFS_NAME}.xml") },
            )
        }
    }

    @Test
    fun `downloaded images stay behind`() {
        for ((section, rules) in sections) {
            assertTrue(
                "<$section> must exclude the media cache, or it can push the app past the backup quota",
                excludes(rules).any { covers(it, "file", "offline/media/n_m_full.img") },
            )
        }
    }

    @Test
    fun `the notes themselves always travel`() {
        val data = listOf("offline/cache.json", "offline/outbox.json", "offline/media-staging/photo.jpg")
        for ((section, rules) in sections) {
            assertFalse(
                "<$section> must stay exclusions-only: an <include> drops everything it does not name",
                rules.any { it.tagName == "include" },
            )
            for (path in data) {
                assertFalse(
                    "<$section> excludes $path, which is a standalone user's only copy",
                    excludes(rules).any { covers(it, "file", path) },
                )
            }
        }
    }
}

package org.hyperstarit.keepitapp.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.hyperstarit.keepitapp.ui.theme.KeepItPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a note body renders on cards, in the viewer and in previews. The expectations are what the
 * web's react-markdown + remark-gfm + remark-breaks produce for the same source: the two clients
 * read one note the same way, and every case here is one where the old hand-rolled parser did not.
 */
class MarkdownRenderTest {

    private fun render(source: String) = markdownToAnnotated(source, KeepItPalette.Dim)

    /** The substrings covered by spans matching [predicate]. */
    private fun AnnotatedString.styled(predicate: (SpanStyle) -> Boolean): List<String> =
        spanStyles.filter { predicate(it.item) }.map { text.substring(it.start, it.end) }

    private fun AnnotatedString.italic() = styled { it.fontStyle == FontStyle.Italic }
    private fun AnnotatedString.bold() = styled { it.fontWeight == FontWeight.Bold }
    private fun AnnotatedString.links(): List<Pair<String, String>> =
        getLinkAnnotations(0, length).map { text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url }

    @Test
    fun `an asterisk with spaces around it is arithmetic, not emphasis`() {
        val out = render("2 * 3 * 4")
        assertEquals("2 * 3 * 4", out.text)
        assertTrue(out.italic().isEmpty())
    }

    @Test
    fun `underscores emphasise, but not inside a word`() {
        val out = render("_it_ __b__ snake_case_name")
        assertEquals("it b snake_case_name", out.text)
        assertEquals(listOf("it"), out.italic())
        assertEquals(listOf("b"), out.bold())
    }

    @Test
    fun `three asterisks are bold and italic`() {
        val out = render("***both***")
        assertEquals("both", out.text)
        assertEquals(listOf("both"), out.italic())
        assertEquals(listOf("both"), out.bold())
    }

    @Test
    fun `bare URLs are links, without the sentence's full stop`() {
        val out = render("see https://example.com/page. and www.example.org")
        assertEquals(
            listOf("https://example.com/page" to "https://example.com/page", "www.example.org" to "http://www.example.org"),
            out.links(),
        )
    }

    @Test
    fun `a link keeps parentheses inside its address`() {
        val out = render("[wiki](https://en.wikipedia.org/wiki/Foo_(bar))")
        assertEquals("wiki", out.text)
        assertEquals(listOf("wiki" to "https://en.wikipedia.org/wiki/Foo_(bar)"), out.links())
    }

    @Test
    fun `only web, mail and phone links are clickable`() {
        // file:// crashed the app on tap (FileUriExposedException); "url" is the toolbar placeholder.
        val out = render("[a](url) [b](file:///sdcard/x) [c](javascript:alert(1)) [d](mailto:x@y.z) [e](tel:123)")
        assertEquals("a b c d e", out.text)
        assertEquals(listOf("d" to "mailto:x@y.z", "e" to "tel:123"), out.links())
    }

    @Test
    fun `nested lists indent, and every bullet and number style is a list`() {
        assertEquals("• top\n  • nested", render("- top\n  - nested").text)
        assertEquals("• plus", render("+ plus").text)
        assertEquals("3) three\n4) four", render("3) three\n4) four").text)
    }

    @Test
    fun `task items show their box, and a done one is struck through`() {
        val out = render("- [ ] todo\n- [x] done")
        assertEquals("☐ todo\n☑ done", out.text)
        assertEquals(listOf("done"), out.styled { it.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `nothing inside a code block is Markdown`() {
        val out = render("```\n# not heading\n*x* here\n```")
        assertEquals("# not heading\n*x* here", out.text)
        assertTrue(out.italic().isEmpty())
        assertEquals(listOf("# not heading\n*x* here"), out.styled { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `escapes and entities read as the characters they stand for`() {
        assertEquals("*lit*", render("\\*lit\\*").text)
        assertEquals("© & A", render("&copy; &amp; &#x41;").text)
    }

    @Test
    fun `spacing follows the author, with runs of blank lines collapsed`() {
        assertEquals("a\nb", render("a\nb").text)
        assertEquals("a\n\nb", render("a\n\n\n\nb").text)
        assertEquals("Title\ntext", render("# Title\ntext").text)
    }

    @Test
    fun `raw HTML is shown as the text it is`() {
        assertEquals("<b>x</b> and", render("<b>x</b> and").text)
    }

    @Test
    fun `tables, quotes and setext headings`() {
        assertEquals("a  │  b\n1  │  2", render("| a | b |\n|---|---|\n| 1 | 2 |").text)
        assertEquals("▎ quoted\n▎ more", render("> quoted\n> more").text)
        val heading = render("Title\n---")
        assertEquals("Title", heading.text)
        assertEquals(listOf("Title"), heading.styled { it.fontWeight == FontWeight.SemiBold })
    }

    @Test
    fun `previews are the rendered text, so they read like the card`() {
        assertEquals("2 * 3 = 6", stripMarkdown("2 * 3 = 6"))
        assertEquals("• item\n☑ done", stripMarkdown("* item\n- [x] done"))
        assertEquals("code()", stripMarkdown("```js\ncode()\n```"))
        assertEquals("Heading\nbold and code", stripMarkdown("# Heading\n**bold** and `code`"))
    }

    @Test
    fun `an empty or blank body renders as nothing`() {
        assertEquals("", render("").text)
        assertFalse(render("   \n\n ").text.isNotBlank())
    }
}

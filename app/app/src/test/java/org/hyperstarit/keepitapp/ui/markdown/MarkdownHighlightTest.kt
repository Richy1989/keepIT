package org.hyperstarit.keepitapp.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.hyperstarit.keepitapp.ui.theme.KeepItPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's live styling. Its one hard rule is that the text comes back unchanged — the field
 * maps offsets one to one, so a single character added or lost would put the cursor in the wrong
 * place — and within that, the syntax is dimmed and the content styled.
 */
class MarkdownHighlightTest {

    private fun AnnotatedString.styled(predicate: (SpanStyle) -> Boolean): List<String> =
        spanStyles.filter { predicate(it.item) }.map { text.substring(it.start, it.end) }

    private val dim = KeepItPalette.Dim

    private fun markdownHighlight(source: String) = markdownHighlight(source, dim)

    private fun AnnotatedString.dimmed() = styled { it.color == dim.textFaint }

    @Test
    fun `the text is never changed, whatever it holds`() {
        val samples = listOf(
            "", "plain", "**bold** and *it* and ~~gone~~ and `code`", "# Title\ntext", "Title\n===",
            "- a\n  - b\n1. c\n- [x] d", "> quote\n> more", "```kotlin\nval x = 1\n```", "    indented",
            "[label](https://x.y) https://bare.example", "| a | b |\n|---|---|\n| 1 | 2 |", "\\*esc\\* &amp; <b>",
            "**unclosed", "- ", "***", "\t- tab\r\nwindows", "emoji 🎉 **bö**",
        )
        for (s in samples) assertEquals(s, markdownHighlight(s).text)
    }

    @Test
    fun `emphasis is styled with its markers dimmed`() {
        val out = markdownHighlight("**bold** text")
        assertEquals(listOf("**bold**"), out.styled { it.fontWeight == FontWeight.Bold })
        assertEquals(listOf("**", "**"), out.dimmed())
    }

    @Test
    fun `a heading is larger, its hashes dimmed`() {
        val out = markdownHighlight("# Title")
        assertEquals(listOf("# Title"), out.styled { it.fontWeight == FontWeight.SemiBold })
        assertEquals(listOf("# "), out.dimmed())
    }

    @Test
    fun `list markers are dimmed`() {
        assertEquals(listOf("- ", "1. "), markdownHighlight("- a\n\n1. b").dimmed())
    }

    @Test
    fun `a link's label is coloured and its syntax dimmed`() {
        val out = markdownHighlight("[label](https://x.y)")
        assertEquals(listOf("label"), out.styled { it.color == dim.accentInk })
        assertEquals(listOf("[", "](https://x.y)"), out.dimmed())
    }

    @Test
    fun `a bare URL is coloured whole`() {
        assertEquals(listOf("https://x.y"), markdownHighlight("go https://x.y").styled { it.color == dim.accentInk })
    }

    @Test
    fun `a code block is monospaced, its fences dimmed`() {
        val out = markdownHighlight("```\ncode\n```")
        assertTrue(out.styled { it.fontFamily == FontFamily.Monospace }.contains("```\ncode\n```"))
        assertEquals(listOf("```", "```"), out.dimmed())
    }

    @Test
    fun `the styling is drawn in the theme it was made for`() {
        // The field re-filters when the theme changes; a transformation that ignored its palette
        // would keep Dim's pale syntax and bright links on a white page.
        val light = KeepItPalette.Light
        val out = markdownHighlight("**b** [label](https://x.y) `c`", light)
        assertEquals(listOf("**", "**", "[", "](https://x.y)", "`", "`"), out.styled { it.color == light.textFaint })
        assertEquals(listOf("label"), out.styled { it.color == light.accentInk })
        assertEquals(listOf("`c`"), out.styled { it.background == light.overlayHover })
    }

    @Test
    fun `transformations are equal per theme, so the field re-filters only when it changes`() {
        assertEquals(MarkdownVisualTransformation(dim), MarkdownVisualTransformation(dim))
        assertTrue(MarkdownVisualTransformation(dim) != MarkdownVisualTransformation(KeepItPalette.Light))
    }
}

package org.hyperstarit.keepitapp.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
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

    private fun AnnotatedString.dimmed() = styled { it.color == KeepItColors.TextFaint }

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
        assertEquals(listOf("label"), out.styled { it.color == KeepItColors.Accent })
        assertEquals(listOf("[", "](https://x.y)"), out.dimmed())
    }

    @Test
    fun `a bare URL is coloured whole`() {
        assertEquals(listOf("https://x.y"), markdownHighlight("go https://x.y").styled { it.color == KeepItColors.Accent })
    }

    @Test
    fun `a code block is monospaced, its fences dimmed`() {
        val out = markdownHighlight("```\ncode\n```")
        assertTrue(out.styled { it.fontFamily == FontFamily.Monospace }.contains("```\ncode\n```"))
        assertEquals(listOf("```", "```"), out.dimmed())
    }
}

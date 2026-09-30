package org.hyperstarit.keepitapp.ui.markdown

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The formatting toolbar and Enter-in-a-list. Values are written with `|` for a caret and `[...]`
 * for a selection, so each case reads as what the user sees before and after.
 */
class MarkdownEditTest {

    /** "he|llo" → caret at 2; "a [bold] b" → selection over "bold". */
    private fun field(marked: String): TextFieldValue {
        val caret = marked.indexOf('|')
        if (caret >= 0) return TextFieldValue(marked.removeRange(caret, caret + 1), TextRange(caret))
        val open = marked.indexOf('[')
        val close = marked.lastIndexOf(']')
        val text = marked.removeRange(close, close + 1).removeRange(open, open + 1)
        return TextFieldValue(text, TextRange(open, close - 1))
    }

    private fun show(value: TextFieldValue): String {
        val s = value.selection
        return if (s.collapsed) {
            value.text.substring(0, s.start) + "|" + value.text.substring(s.start)
        } else {
            value.text.substring(0, s.min) + "[" + value.text.substring(s.min, s.max) + "]" + value.text.substring(s.max)
        }
    }

    private fun apply(marked: String, action: MarkdownAction) = show(applyMarkdown(field(marked), action))

    // ---- inline ----

    @Test
    fun `italic on a bold word adds italic instead of replacing bold`() {
        assertEquals("***[bold]***", apply("**[bold]**", MarkdownAction.ITALIC))
        assertEquals("*[**bold**]*", apply("[**bold**]", MarkdownAction.ITALIC))
    }

    @Test
    fun `bold and italic come off a bold-italic word one at a time`() {
        assertEquals("*[x]*", apply("***[x]***", MarkdownAction.BOLD))
        assertEquals("**[x]**", apply("***[x]***", MarkdownAction.ITALIC))
    }

    @Test
    fun `styles toggle off, whichever emphasis character was used`() {
        assertEquals("[bold]", apply("**[bold]**", MarkdownAction.BOLD))
        assertEquals("[bold]", apply("[**bold**]", MarkdownAction.BOLD))
        assertEquals("[it]", apply("_[it]_", MarkdownAction.ITALIC))
        assertEquals("[b]", apply("__[b]__", MarkdownAction.BOLD))
        assertEquals("[x]", apply("~~[x]~~", MarkdownAction.STRIKE))
        assertEquals("[x]", apply("`[x]`", MarkdownAction.CODE))
    }

    @Test
    fun `a caret gets an empty pair to type into, and a second tap takes it away`() {
        assertEquals("a **|** b", apply("a | b", MarkdownAction.BOLD))
        assertEquals("a | b", apply("a **|** b", MarkdownAction.BOLD))
    }

    @Test
    fun `spaces caught in a selection stay outside the markers`() {
        // "**bold **" is literal asterisks in CommonMark; the web showed them, the phone did not.
        assertEquals("**[bold]** then", apply("[bold ]then", MarkdownAction.BOLD))
    }

    @Test
    fun `a selection over several lines styles each line, around its list markers`() {
        assertEquals("[**a**\n**b**]", apply("[a\nb]", MarkdownAction.BOLD))
        assertEquals("[- **a**\n- **b**]", apply("[- a\n- b]", MarkdownAction.BOLD))
        assertEquals("[- a\n- b]", apply("[- **a**\n- **b**]", MarkdownAction.BOLD))
    }

    // ---- line prefixes ----

    @Test
    fun `a line button leaves a caret as a caret`() {
        // It used to select the whole line, so the next keystroke replaced it.
        assertEquals("- Buy milk|", apply("Buy milk|", MarkdownAction.BULLET))
        assertEquals("Buy milk|", apply("- Buy milk|", MarkdownAction.BULLET))
        assertEquals("# Ti|tle", apply("Ti|tle", MarkdownAction.HEADING))
    }

    @Test
    fun `switching list type replaces the marker instead of stacking it`() {
        assertEquals("[1. a\n2. b]", apply("[- a\n- b]", MarkdownAction.ORDERED))
        assertEquals("[- a\n- b]", apply("[1. a\n2. b]", MarkdownAction.BULLET))
        assertEquals("# a|", apply("- a|", MarkdownAction.HEADING))
    }

    @Test
    fun `bullet on a task line removes the task, not just its dash`() {
        assertEquals("task|", apply("- [ ] task|", MarkdownAction.BULLET))
    }

    @Test
    fun `numbering skips blank lines and carries on from the list above`() {
        assertEquals("[1. a\n\n2. c]", apply("[a\n\nc]", MarkdownAction.ORDERED))
        assertEquals("1. a\n2. b|", apply("1. a\nb|", MarkdownAction.ORDERED))
    }

    @Test
    fun `a link wraps the selection and selects the address to type over`() {
        // Written out by hand: the link's own brackets would collide with the selection notation.
        val out = applyMarkdown(TextFieldValue("site", TextRange(0, 4)), MarkdownAction.LINK)
        assertEquals("[site](url)", out.text)
        assertEquals("url", out.text.substring(out.selection.min, out.selection.max))
    }

    // ---- Enter ----

    private fun enter(marked: String): String {
        val old = field(marked)
        val pos = old.selection.start
        val typed = TextFieldValue(old.text.take(pos) + "\n" + old.text.substring(pos), TextRange(pos + 1))
        return show(continueListOnEnter(old, typed))
    }

    @Test
    fun `enter continues a list with the next marker`() {
        assertEquals("- milk\n- |", enter("- milk|"))
        assertEquals("* milk\n* |", enter("* milk|"))
        assertEquals("3. a\n4. |", enter("3. a|"))
        assertEquals("9) a\n10) |", enter("9) a|"))
        assertEquals("  - nested\n  - |", enter("  - nested|"))
    }

    @Test
    fun `a new task starts unticked`() {
        assertEquals("- [x] done\n- [ ] |", enter("- [x] done|"))
    }

    @Test
    fun `enter in the middle of an item splits it into two`() {
        assertEquals("- buy\n- |milk", enter("- buy| milk"))
    }

    @Test
    fun `enter on an empty item ends the list`() {
        assertEquals("- a\n|", enter("- a\n- |"))
        assertEquals("1. a\n|", enter("1. a\n2. |"))
    }

    @Test
    fun `enter anywhere else is an ordinary newline`() {
        assertEquals("plain\n|", enter("plain|"))
        assertEquals("\n|- a", enter("|- a"))
    }

    @Test
    fun `only a single typed newline is ever rewritten`() {
        val old = TextFieldValue("- a", TextRange(3))
        val pasted = TextFieldValue("- a\nb\nc", TextRange(7))
        assertEquals(pasted, continueListOnEnter(old, pasted))
        val selected = TextFieldValue("- abc", TextRange(3, 5))
        val replaced = TextFieldValue("- a\n", TextRange(4))
        assertEquals(replaced, continueListOnEnter(selected, replaced))
    }
}

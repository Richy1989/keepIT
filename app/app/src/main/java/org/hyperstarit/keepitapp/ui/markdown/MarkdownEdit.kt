package org.hyperstarit.keepitapp.ui.markdown

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Editing a note body's Markdown: the formatting toolbar's actions, and Enter continuing a list.
 * Pure functions from one [TextFieldValue] to the next, so each rule is a unit test — the web's
 * `markdownEdit.ts` implements the same rules for its textarea.
 */

enum class MarkdownAction { BOLD, ITALIC, STRIKE, CODE, HEADING, BULLET, ORDERED, LINK }

/**
 * Applies a toolbar action to the field's current selection and returns the new value with the
 * selection where writing naturally continues. Inline actions toggle; line actions toggle their
 * prefix on every selected line.
 */
fun applyMarkdown(value: TextFieldValue, action: MarkdownAction): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    return when (action) {
        MarkdownAction.BOLD -> toggleInline(value.text, start, end, "**")
        MarkdownAction.ITALIC -> toggleInline(value.text, start, end, "*")
        MarkdownAction.STRIKE -> toggleInline(value.text, start, end, "~~")
        MarkdownAction.CODE -> toggleInline(value.text, start, end, "`")
        MarkdownAction.LINK -> insertLink(value.text, start, end)
        MarkdownAction.HEADING, MarkdownAction.BULLET, MarkdownAction.ORDERED ->
            toggleLinePrefix(value.text, start, end, action)
    }
}

// ---- inline: bold, italic, strike, code ----

private fun toggleInline(text: String, start: Int, end: Int, marker: String): TextFieldValue {
    // Emphasis cannot cross a line break in the renderer's eyes, so a multi-line selection is
    // styled line by line: "**a\nb**" is bold on the web but raw asterisks everywhere else.
    if (text.substring(start, end).contains('\n')) return toggleInlinePerLine(text, start, end, marker)

    // Emphasis cannot start or end on a space: "**bold **" is literal asterisks in CommonMark.
    // A drag-selection that caught a space wraps the word and leaves the space outside.
    var s = start
    var e = end
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    if (s == e && start != end) { s = start; e = start }

    unwrapRange(text, s, e, marker)?.let { (from, to, width) ->
        val inner = text.substring(from + width, to - width)
        val next = text.take(from) + inner + text.substring(to)
        // Keep the same characters selected, now without their markers.
        val selStart = (s - width).coerceAtLeast(from)
        return TextFieldValue(next, TextRange(selStart, selStart + (e - s).coerceAtMost(inner.length)))
    }
    // Wrap; with no selection the cursor lands between the markers, ready to type.
    val m = marker.length
    return TextFieldValue(
        text.take(s) + marker + text.substring(s, e) + marker + text.substring(e),
        TextRange(s + m, e + m),
    )
}

/**
 * The span to strip when [s]..[e] is already styled with [marker]: the markers either selected
 * along with the text or sitting just outside it. Returns (from, to, markerWidth), or null to wrap.
 *
 * `*` and `_` are counted as runs, because one character serves two styles: `***x***` is bold and
 * italic, so italic must see an odd run and bold a run of at least two. Matching the marker text
 * alone read the inner `*` of `**bold**` as italic and turned bold into italic.
 */
private fun unwrapRange(text: String, s: Int, e: Int, marker: String): Triple<Int, Int, Int>? {
    val m = marker.length
    val char = marker[0]
    if (char != '*' && char != '_') {
        val selected = text.substring(s, e)
        if (selected.length >= 2 * m && selected.startsWith(marker) && selected.endsWith(marker)) {
            return Triple(s, e, m)
        }
        if (s >= m && text.regionMatches(s - m, marker, 0, m) && text.regionMatches(e, marker, 0, m)) {
            return Triple(s - m, e + m, m)
        }
        return null
    }
    // Emphasis: accept either character, so `_x_` and `__x__` toggle off as well.
    for (c in charArrayOf('*', '_')) {
        // Markers inside the selection.
        val leadIn = runLength(text, s, e, c, forward = true)
        val trailIn = runLength(text, s, e, c, forward = false)
        if (leadIn < e - s && fits(leadIn, trailIn, m)) return Triple(s, e, m)
        // Markers just outside it.
        val before = runLength(text, 0, s, c, forward = false)
        val after = runLength(text, e, text.length, c, forward = true)
        if (fits(before, after, m)) return Triple(s - m, e + m, m)
    }
    return null
}

/** Italic (width 1) needs an odd run on both sides; bold (width 2) needs at least two. */
private fun fits(lead: Int, trail: Int, width: Int): Boolean = when (width) {
    1 -> lead % 2 == 1 && trail % 2 == 1
    else -> lead >= 2 && trail >= 2
}

/** How many [c] run from [from] forward, or back from [to], within from..to (at most 3). */
private fun runLength(text: String, from: Int, to: Int, c: Char, forward: Boolean): Int {
    var n = 0
    while (n < 3 && from + n < to) {
        val i = if (forward) from + n else to - 1 - n
        if (text[i] != c) break
        n++
    }
    return n
}

private fun toggleInlinePerLine(text: String, start: Int, end: Int, marker: String): TextFieldValue {
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val blockEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
    // Each line splits into its prefix (indent, list marker, heading hashes, quote), the text to
    // style, and trailing space: wrapping "- a" whole would give "**- a**" and break the list.
    fun split(line: String): Triple<String, String, String> {
        val p = prefixLength(line).let { it + line.substring(it).takeWhile(Char::isWhitespace).length }
        val rest = line.substring(p)
        val core = rest.trimEnd()
        return Triple(line.substring(0, p), core, rest.substring(core.length))
    }
    val lines = text.substring(lineStart, blockEnd).split('\n')
    // One decision for the whole selection: if every line with text is already styled, unstyle.
    val styled = lines.map(::split).filter { it.second.isNotEmpty() }.all { (_, core, _) ->
        unwrapRange(core, 0, core.length, marker) != null
    }
    val block = lines.joinToString("\n") { line ->
        val (head, core, trail) = split(line)
        if (core.isEmpty()) return@joinToString line
        val range = unwrapRange(core, 0, core.length, marker)
        when {
            styled && range != null -> head + core.substring(range.third, core.length - range.third) + trail
            styled || range != null -> line
            else -> head + marker + core + marker + trail
        }
    }
    return TextFieldValue(text.take(lineStart) + block + text.substring(blockEnd), TextRange(lineStart, lineStart + block.length))
}

// ---- link ----

private fun insertLink(text: String, start: Int, end: Int): TextFieldValue {
    val selected = text.substring(start, end)
    val label = selected.ifEmpty { "text" }
    val next = text.take(start) + "[" + label + "](url)" + text.substring(end)
    // Select the url placeholder so typing replaces it; with placeholder text, select that instead.
    val urlStart = start + label.length + 3 // "[" + label + "]("
    return if (selected.isNotEmpty()) {
        TextFieldValue(next, TextRange(urlStart, urlStart + 3))
    } else {
        TextFieldValue(next, TextRange(start + 1, start + 1 + label.length))
    }
}

// ---- line prefixes: heading, bullet, numbered ----

private val HEADING_PREFIX = Regex("""^#{1,6}\s+""")

/** A list item's marker: indent, bullet or number, spacing, and a task box if there is one. */
private val LIST_ITEM = Regex("""^(\s*)(?:([-*+])|(\d{1,9})([.)]))(\s+)(\[[ xX]]\s+)?""")

private fun toggleLinePrefix(text: String, start: Int, end: Int, action: MarkdownAction): TextFieldValue {
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val blockEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
    val lines = text.substring(lineStart, blockEnd).split('\n')

    fun hasOwnPrefix(line: String): Boolean = when (action) {
        MarkdownAction.HEADING -> HEADING_PREFIX.containsMatchIn(line)
        MarkdownAction.BULLET -> LIST_ITEM.find(line)?.groups?.get(2) != null
        else -> LIST_ITEM.find(line)?.groups?.get(3) != null
    }

    // Numbering continues a numbered list directly above the selection rather than restarting.
    var number = 1
    if (action == MarkdownAction.ORDERED && lineStart > 0) {
        val above = text.substring(text.lastIndexOf('\n', lineStart - 2) + 1, lineStart - 1)
        LIST_ITEM.find(above)?.groups?.get(3)?.value?.toIntOrNull()?.let { number = it + 1 }
    }

    val allPrefixed = lines.all { it.isBlank() || hasOwnPrefix(it) }
    val block = lines.map { line ->
        when {
            line.isBlank() -> line
            allPrefixed -> removePrefix(line)
            hasOwnPrefix(line) -> line.also { if (action == MarkdownAction.ORDERED) number++ }
            // Another kind of prefix is replaced, not stacked: bullet → numbered is "1. a", not "1. - a".
            else -> {
                val indent = LIST_ITEM.find(line)?.groupValues?.get(1) ?: ""
                val body = removePrefix(line).trimStart()
                when (action) {
                    MarkdownAction.HEADING -> "# $body"
                    MarkdownAction.BULLET -> "$indent- $body"
                    else -> "$indent${number++}. $body"
                }
            }
        }
    }.joinToString("\n")

    val next = text.take(lineStart) + block + text.substring(blockEnd)
    // A caret stays a caret. Selecting the rewritten line meant the next keystroke replaced it.
    if (start == end) {
        val oldPrefix = prefixLength(lines.first())
        val newFirst = block.substringBefore('\n')
        val newPrefix = prefixLength(newFirst)
        val caret = (start + newPrefix - oldPrefix).coerceIn(lineStart + newPrefix, lineStart + newFirst.length)
        return TextFieldValue(next, TextRange(caret))
    }
    return TextFieldValue(next, TextRange(lineStart, lineStart + block.length))
}

/** Strips a heading or list prefix — the list's indent included, so toggling off is clean. */
private fun removePrefix(line: String): String {
    HEADING_PREFIX.find(line)?.let { return line.substring(it.range.last + 1) }
    LIST_ITEM.find(line)?.let { return line.substring(it.range.last + 1) }
    return line
}

private val QUOTE_PREFIX = Regex("""^\s*>\s?""")

private fun prefixLength(line: String): Int =
    HEADING_PREFIX.find(line)?.let { it.range.last + 1 }
        ?: LIST_ITEM.find(line)?.let { it.range.last + 1 }
        ?: QUOTE_PREFIX.find(line)?.let { it.range.last + 1 }
        ?: 0

// ---- Enter in a list ----

/**
 * Continues a list when Enter is pressed in it, as GitHub and most Markdown editors do: after
 * "- milk" the new line starts "- ", after "3. milk" it starts "4. ", after a task "- [ ] ". Enter on
 * an item with nothing after its marker ends the list instead, leaving an empty line. Anything
 * other than a single newline typed at a caret passes through untouched, so pasting, IME
 * composition and deleting are never rewritten.
 */
fun continueListOnEnter(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (!old.selection.collapsed || !new.selection.collapsed) return new
    val pos = old.selection.start
    if (new.text.length != old.text.length + 1 || new.selection.start != pos + 1) return new
    if (new.text[pos] != '\n' || !new.text.startsWith(old.text.substring(0, pos)) ||
        !new.text.endsWith(old.text.substring(pos))
    ) {
        return new
    }

    val lineStart = old.text.lastIndexOf('\n', pos - 1) + 1
    val lineEnd = old.text.indexOf('\n', pos).let { if (it == -1) old.text.length else it }
    val line = old.text.substring(lineStart, lineEnd)
    val match = LIST_ITEM.find(line) ?: return new
    val markerEnd = lineStart + match.range.last + 1
    // Enter typed within the indent or the marker: an ordinary newline.
    if (pos < markerEnd) return new

    if (line.substring(match.range.last + 1).isBlank()) {
        // An empty item: Enter ends the list. The marker goes and the newline is not added.
        return TextFieldValue(old.text.take(lineStart) + old.text.substring(lineEnd), TextRange(lineStart))
    }

    val g = match.groups
    val indent = g[1]?.value.orEmpty()
    val marker = g[2]?.value ?: "${(g[3]!!.value.toLong() + 1)}${g[4]!!.value}"
    val prefix = indent + marker + " " + if (g[6] != null) "[ ] " else ""
    // Splitting "- buy| milk" moves " milk" down: its leading space would double the marker's.
    val moved = new.text.substring(pos + 1).dropWhile { it == ' ' }
    return TextFieldValue(new.text.take(pos + 1) + prefix + moved, TextRange(pos + 1 + prefix.length))
}

package org.hyperstarit.keepitapp.ui.markdown

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * Minimal Markdown support for note bodies — the same "basic text editing" dialect the web client
 * renders with react-markdown: bold/italic/strikethrough/inline code, links, headings, bullet and
 * numbered lists, task lines, quotes, and rules. Hand-rolled to an [AnnotatedString] (no library):
 * the subset is small, and one Text per note keeps the masonry grid cheap. Single newlines stay
 * line breaks, exactly like the web's remark-breaks, so pre-Markdown notes render as written.
 */

// ---- rendering ----

private val HR = Regex("""^ {0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val TASK = Regex("""^[-*]\s+\[([ xX])]\s+(.*)$""")
private val BULLET = Regex("""^[-*]\s+(.*)$""")
private val ORDERED = Regex("""^(\d+)\.\s+(.*)$""")
private val QUOTE = Regex("""^>\s?(.*)$""")

/** First-match-wins inline tokens: `code`, **bold**, *italic*, ~~strike~~, [label](url). */
private val INLINE =
    Regex("""`([^`\n]+)`|\*\*(.+?)\*\*|\*([^*\n]+)\*|~~(.+?)~~|\[([^\]\n]+)]\(([^)\s]+)\)""")

private val CodeBackground = Color(0x33000000)

/** Renders a note body's Markdown. The one place both the grid card and the editor read from. */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    color: Color = KeepItColors.Text,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 21.sp,
    maxLines: Int = Int.MAX_VALUE,
) {
    val rendered = remember(source) { markdownToAnnotated(source) }
    Text(
        text = rendered,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

fun markdownToAnnotated(source: String): AnnotatedString = buildAnnotatedString {
    source.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        appendBlock(line)
    }
}

private fun AnnotatedString.Builder.appendBlock(line: String) {
    if (HR.matches(line)) {
        withStyle(SpanStyle(color = KeepItColors.TextFaint)) { append("― ― ―") }
        return
    }
    HEADING.matchEntire(line)?.let { m ->
        val size = when (m.groupValues[1].length) {
            1 -> 1.3.em
            2 -> 1.15.em
            else -> 1.05.em
        }
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = size)) {
            appendInline(m.groupValues[2])
        }
        return
    }
    TASK.matchEntire(line)?.let { m ->
        val checked = m.groupValues[1].isNotBlank()
        if (checked) {
            withStyle(
                SpanStyle(color = KeepItColors.TextFaint, textDecoration = TextDecoration.LineThrough),
            ) {
                append("☑ ")
                appendInline(m.groupValues[2])
            }
        } else {
            append("☐ ")
            appendInline(m.groupValues[2])
        }
        return
    }
    BULLET.matchEntire(line)?.let { m ->
        append("• ")
        appendInline(m.groupValues[1])
        return
    }
    ORDERED.matchEntire(line)?.let { m ->
        append("${m.groupValues[1]}. ")
        appendInline(m.groupValues[2])
        return
    }
    QUOTE.matchEntire(line)?.let { m ->
        withStyle(SpanStyle(color = KeepItColors.TextMuted, fontStyle = FontStyle.Italic)) {
            append("▎ ")
            appendInline(m.groupValues[1])
        }
        return
    }
    appendInline(line)
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    var index = 0
    while (index < text.length) {
        val match = INLINE.find(text, index) ?: break
        append(text.substring(index, match.range.first))
        val g = match.groups
        when {
            g[1] != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = CodeBackground),
            ) { append(g[1]!!.value) }

            g[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(g[2]!!.value) }

            g[3] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g[3]!!.value) }

            g[4] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInline(g[4]!!.value)
            }

            else -> withLink(
                LinkAnnotation.Url(
                    g[6]!!.value,
                    TextLinkStyles(
                        style = SpanStyle(
                            color = KeepItColors.Accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) { append(g[5]!!.value) }
        }
        index = match.range.last + 1
    }
    append(text.substring(index))
}

/** Flattens Markdown to plain display text — the widget's one-line previews. */
fun stripMarkdown(source: String): String = source
    .replace(Regex("""\[([^\]\n]+)]\([^)\n]*\)"""), "$1")
    .replace(Regex("""\*\*|\*|~~|`"""), "")
    .lines()
    .joinToString("\n") { line ->
        line
            .replace(Regex("""^#{1,6}\s+"""), "")
            .replace(Regex("""^[-]\s+\[[ xX]]\s+"""), "")
            .replace(Regex("""^[-]\s+"""), "• ")
            .replace(Regex("""^>\s?"""), "")
    }

// ---- editing (the formatting toolbar's actions) ----

enum class MarkdownAction { BOLD, ITALIC, STRIKE, CODE, HEADING, BULLET, ORDERED, LINK }

/**
 * Applies a toolbar action to the field's current selection and returns the new value with the
 * selection where writing naturally continues — the Compose twin of the web's `applyMarkdown`.
 * Inline actions toggle; line actions toggle their prefix on every selected line.
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
        else -> toggleLinePrefix(value.text, start, end, action)
    }
}

private fun toggleInline(text: String, start: Int, end: Int, marker: String): TextFieldValue {
    val m = marker.length
    val selected = text.substring(start, end)

    // Selection includes the markers ("**bold**" selected) → strip them.
    if (selected.length >= 2 * m && selected.startsWith(marker) && selected.endsWith(marker)) {
        val inner = selected.substring(m, selected.length - m)
        return TextFieldValue(
            text.take(start) + inner + text.substring(end),
            TextRange(start, start + inner.length),
        )
    }
    // Markers sit just outside the selection ("bold" selected inside "**bold**") → strip them.
    if (start >= m && text.regionMatches(start - m, marker, 0, m) &&
        end + m <= text.length && text.regionMatches(end, marker, 0, m)
    ) {
        return TextFieldValue(
            text.take(start - m) + selected + text.substring(end + m),
            TextRange(start - m, end - m),
        )
    }
    // Wrap; with no selection the cursor lands between the markers, ready to type.
    return TextFieldValue(
        text.take(start) + marker + selected + marker + text.substring(end),
        TextRange(start + m, end + m),
    )
}

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

private fun toggleLinePrefix(text: String, start: Int, end: Int, action: MarkdownAction): TextFieldValue {
    // A negative fromIndex makes lastIndexOf return -1, so line 0 falls out naturally.
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val lineEndIdx = text.indexOf('\n', end)
    val blockEnd = if (lineEndIdx == -1) text.length else lineEndIdx
    val lines = text.substring(lineStart, blockEnd).split("\n")

    val test = when (action) {
        MarkdownAction.HEADING -> Regex("""^#{1,6} """)
        MarkdownAction.BULLET -> Regex("""^[-*] """)
        else -> Regex("""^\d+\. """)
    }
    val allPrefixed = lines.all { it.isEmpty() || test.containsMatchIn(it) }

    val block = lines.mapIndexed { i, line ->
        when {
            line.isEmpty() -> line
            allPrefixed -> line.replaceFirst(test, "")
            test.containsMatchIn(line) -> line // partially prefixed selection: fill in the rest
            action == MarkdownAction.HEADING -> "# $line"
            action == MarkdownAction.BULLET -> "- $line"
            else -> "${i + 1}. $line"
        }
    }.joinToString("\n")

    return TextFieldValue(
        text.take(lineStart) + block + text.substring(blockEnd),
        TextRange(lineStart, lineStart + block.length),
    )
}

package org.hyperstarit.keepitapp.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * Live styling for the note editor. The field still holds — and shows — the raw Markdown exactly
 * as typed, but styled in place: bold reads bold, headings are larger, code is monospaced, links
 * are coloured, and the syntax itself (`**`, `#`, `- `, `](url)`) is dimmed so the words stand out.
 *
 * Only styles are added, never characters, so [OffsetMapping.Identity] holds: the cursor, the
 * selection, the keyboard's composition and the formatting toolbar all work on the source text,
 * exactly as without it. A preview that rewrote the text would have to map every offset back.
 */
class MarkdownVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(markdownHighlight(text.text), OffsetMapping.Identity)

    // Stateless: every instance styles alike, so the field need not re-filter on recomposition.
    override fun equals(other: Any?): Boolean = other is MarkdownVisualTransformation
    override fun hashCode(): Int = MarkdownVisualTransformation::class.hashCode()
}

private val Syntax = SpanStyle(color = KeepItColors.TextFaint)
private val LinkText = SpanStyle(color = KeepItColors.Accent)
private val Done = SpanStyle(color = KeepItColors.TextFaint, textDecoration = TextDecoration.LineThrough)

/** [source] with styles laid over its own characters. The text itself is returned unchanged. */
fun markdownHighlight(source: String): AnnotatedString {
    val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    fun add(style: SpanStyle, start: Int?, end: Int?) {
        if (start == null || end == null) return
        val s = start.coerceIn(0, source.length)
        val e = end.coerceIn(0, source.length)
        if (s < e) spans += AnnotatedString.Range(style, s, e)
    }

    fun visit(node: Node) {
        val s = node.start()
        val e = node.end()
        if (s != null && e != null) {
            when (node) {
                is Heading -> {
                    add(headingStyle(node.level), s, e)
                    // "# " before the text; for a setext heading, the underline after it.
                    add(Syntax, s, node.firstChild?.start() ?: e)
                    add(Syntax, node.lastChild?.end(), e)
                }
                is StrongEmphasis -> delimited(s, e, 2, SpanStyle(fontWeight = FontWeight.Bold), ::add)
                is Emphasis -> delimited(s, e, 1, SpanStyle(fontStyle = FontStyle.Italic), ::add)
                is Strikethrough -> delimited(
                    s, e, node.openingDelimiter?.length ?: 2,
                    SpanStyle(textDecoration = TextDecoration.LineThrough), ::add,
                )
                is Code -> {
                    val ticks = source.run(s, '`')
                    delimited(s, e, ticks, CodeStyle, ::add)
                }
                is Link, is Image -> {
                    val first = node.firstChild?.start()
                    val last = node.lastChild?.end()
                    if (first != null && last != null && (first > s || last < e)) {
                        add(Syntax, s, first)
                        add(LinkText, first, last)
                        add(Syntax, last, e)
                    } else {
                        // A bare URL is its own label.
                        add(LinkText, s, e)
                    }
                }
                is ListItem -> {
                    // The marker: everything from the item's start up to its content.
                    add(Syntax, s, node.firstChild?.start())
                    if (taskMarkerOf(node)?.isChecked == true) add(Done, node.firstChild?.end(), e)
                }
                is TaskListItemMarker -> add(Syntax, s, e)
                is BlockQuote -> {
                    add(SpanStyle(color = KeepItColors.TextMuted), s, e)
                    // Every line of a quote carries its own ">".
                    for (span in node.sourceSpans) {
                        val at = source.indexOf('>', span.inputIndex)
                        if (at != -1 && at < span.inputIndex + span.length) add(Syntax, at, at + 1)
                    }
                }
                is FencedCodeBlock -> {
                    add(CodeStyle, s, e)
                    node.sourceSpans.firstOrNull()?.let { add(Syntax, it.inputIndex, it.inputIndex + it.length) }
                    if (node.closingFenceLength != null && node.sourceSpans.size > 1) {
                        node.sourceSpans.last().let { add(Syntax, it.inputIndex, it.inputIndex + it.length) }
                    }
                }
                is IndentedCodeBlock -> add(CodeStyle, s, e)
                is ThematicBreak -> add(Syntax, s, e)
            }
        }
        // Nothing inside code is Markdown, so its children (there are none) and its text stay as is.
        for (child in node.children()) visit(child)
    }

    visit(markdownParser.parse(source))
    return AnnotatedString(source, spans)
}

/** Styles a delimited inline: the whole of it, then its [width]-character markers dimmed. */
private inline fun delimited(
    s: Int,
    e: Int,
    width: Int,
    style: SpanStyle,
    add: (SpanStyle, Int?, Int?) -> Unit,
) {
    add(style, s, e)
    if (e - s > 2 * width) {
        add(Syntax, s, s + width)
        add(Syntax, e - width, e)
    }
}

/** How many [c] start at [index] — the width of a code span's backtick fence. */
private fun String.run(index: Int, c: Char): Int {
    var n = 0
    while (index + n < length && this[index + n] == c) n++
    return n.coerceAtLeast(1)
}

private fun Node.start(): Int? = sourceSpans.firstOrNull()?.inputIndex

private fun Node.end(): Int? = sourceSpans.lastOrNull()?.let { it.inputIndex + it.length }

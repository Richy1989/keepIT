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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as TextNode
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.KeepItPalette
import org.hyperstarit.keepitapp.ui.theme.LocalKeepItPalette

/**
 * Note bodies are Markdown: CommonMark plus the GitHub extensions the web client's remark-gfm
 * enables (strikethrough, tables, task lists, bare-URL autolinks), with a single newline kept as a
 * line break like the web's remark-breaks, so pre-Markdown notes render as written.
 *
 * Parsed by commonmark-java — the spec the web's react-markdown implements — rather than by hand.
 * The hand-rolled subset this replaced rendered `2 * 3 * 4` in italics, parsed the inside of code
 * blocks, ignored `_emphasis_` and bare URLs, and cut links at the first `)`: each a note that read
 * one way on the web and another here. One parse now feeds three things: the rendered text (cards
 * and the read-only viewer), its plain text (widget and reminder previews, via [stripMarkdown]),
 * and the editor's live styling ([MarkdownVisualTransformation]).
 */

/**
 * The one parser. Thread-safe and immutable, so it is shared. Source spans are kept for the
 * editor's styling, which works on the raw text's own offsets, and for blank-line spacing here.
 */
internal val markdownParser: Parser = Parser.builder()
    .extensions(
        listOf(
            StrikethroughExtension.create(),
            TablesExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create(),
        ),
    )
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .build()

/** Code spans and blocks: monospace on the theme's overlay wash (the web's `bg-overlay-hover`). */
internal fun codeStyle(p: KeepItPalette) =
    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = p.overlayHover)

private fun linkStyles(p: KeepItPalette) = TextLinkStyles(
    style = SpanStyle(color = p.accentInk, textDecoration = TextDecoration.Underline),
)

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
    val palette = LocalKeepItPalette.current
    val rendered = remember(source, palette) { markdownToAnnotated(source, palette) }
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

/**
 * A note body as styled text: one [AnnotatedString] per note, so a card stays a single Text. The
 * colours are baked in, so the theme's [palette] is an input: a string rendered for one theme is
 * stale in another.
 *
 * Blocks are separated the way the author spaced them — adjacent lines stay adjacent, and any run
 * of blank lines becomes one, as it does on the web — rather than by a fixed gap that would push
 * every heading a line away from the text under it.
 */
fun markdownToAnnotated(source: String, palette: KeepItPalette): AnnotatedString =
    renderBlocks(markdownParser.parse(source), palette)

/**
 * Flattens Markdown to plain display text — the widget's and reminder notifications' one-line
 * previews. It is the rendered text without its styling, so a preview reads exactly like the card:
 * bullets as "•", tasks as "☐"/"☑", code and arithmetic untouched.
 */
fun stripMarkdown(source: String): String =
    // Any palette: only the text is kept.
    markdownToAnnotated(source, KeepItPalette.Dim).text

// ---- blocks ----

private fun renderBlocks(parent: Node, p: KeepItPalette): AnnotatedString = buildAnnotatedString {
    var previous: Node? = null
    for (child in parent.children()) {
        val block = renderBlock(child, p) ?: continue
        if (previous != null) append(if (blankLineBetween(previous, child)) "\n\n" else "\n")
        append(block)
        previous = child
    }
}

/** Whether the author left at least one blank line between two sibling blocks. */
private fun blankLineBetween(above: Node, below: Node): Boolean {
    val end = above.sourceSpans.lastOrNull()?.lineIndex ?: return false
    val start = below.sourceSpans.firstOrNull()?.lineIndex ?: return false
    return start - end > 1
}

private fun renderBlock(node: Node, p: KeepItPalette): AnnotatedString? = when (node) {
    is Paragraph -> renderInlines(node, p)
    is Heading -> styled(renderInlines(node, p), headingStyle(node.level))
    is ListBlock -> renderList(node, p)
    is BlockQuote -> styled(
        prefixLines(renderBlocks(node, p), first = "▎ ", rest = "▎ "),
        SpanStyle(color = p.textMuted, fontStyle = FontStyle.Italic),
    )
    // Code is shown exactly as written: nothing inside a fence is Markdown.
    is FencedCodeBlock -> styled(AnnotatedString(node.literal.trimEnd('\n')), codeStyle(p))
    is IndentedCodeBlock -> styled(AnnotatedString(node.literal.trimEnd('\n')), codeStyle(p))
    is ThematicBreak -> styled(AnnotatedString("― ― ―"), SpanStyle(color = p.textFaint))
    // Raw HTML is shown as the text it is, as on the web: never interpreted, never dropped.
    is HtmlBlock -> AnnotatedString(node.literal.trimEnd('\n'))
    is TableBlock -> renderTable(node, p)
    else -> renderBlocks(node, p).takeIf { it.isNotEmpty() }
}

internal fun headingStyle(level: Int) = SpanStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = when (level) {
        1 -> 1.3.em
        2 -> 1.15.em
        else -> 1.05.em
    },
)

private fun renderList(list: ListBlock, p: KeepItPalette): AnnotatedString = buildAnnotatedString {
    val ordered = list as? OrderedList
    var number = ordered?.markerStartNumber ?: 1
    val delimiter = ordered?.markerDelimiter ?: "."
    var previous: Node? = null
    for (item in list.children()) {
        if (item !is ListItem) continue
        if (previous != null) append(if (blankLineBetween(previous, item)) "\n\n" else "\n")
        val task = taskMarkerOf(item)
        val marker = when {
            task != null -> if (task.isChecked) "☑ " else "☐ "
            ordered != null -> "$number$delimiter "
            else -> "• "
        }
        var content = renderBlocks(item, p)
        if (task?.isChecked == true) {
            content = styled(content, SpanStyle(color = p.textFaint, textDecoration = TextDecoration.LineThrough))
        }
        // Continuation lines (a line break inside the item, a nested list) sit under the text.
        append(prefixLines(content, first = marker, rest = " ".repeat(marker.length)))
        number++
        previous = item
    }
}

/** A task item's marker, wherever the extension placed it: first in the item, or in its paragraph. */
internal fun taskMarkerOf(item: ListItem): TaskListItemMarker? =
    item.firstChild as? TaskListItemMarker ?: item.firstChild?.firstChild as? TaskListItemMarker

/** Tables as aligned-enough text rows: a card is too narrow for a grid, and a Text holds no cells. */
private fun renderTable(table: TableBlock, p: KeepItPalette): AnnotatedString = buildAnnotatedString {
    var firstRow = true
    for (section in table.children()) {
        for (row in section.children()) {
            if (!firstRow) append('\n')
            firstRow = false
            var firstCell = true
            for (cell in row.children()) {
                if (!firstCell) withStyle(SpanStyle(color = p.textFaint)) { append("  │  ") }
                firstCell = false
                val content = renderInlines(cell, p)
                if ((cell as? TableCell)?.isHeader == true) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(content) }
                } else {
                    append(content)
                }
            }
        }
    }
}

/** Puts [first] before the first line of [content] and [rest] before every line after it. */
private fun prefixLines(content: AnnotatedString, first: String, rest: String): AnnotatedString =
    buildAnnotatedString {
        var start = 0
        var prefix = first
        while (true) {
            val newline = content.text.indexOf('\n', start)
            val end = if (newline == -1) content.length else newline
            append(prefix)
            append(content.subSequence(start, end))
            if (newline == -1) break
            append('\n')
            start = newline + 1
            prefix = rest
        }
    }

private fun styled(content: AnnotatedString, style: SpanStyle): AnnotatedString =
    buildAnnotatedString { withStyle(style) { append(content) } }

// ---- inlines ----

private fun renderInlines(parent: Node, p: KeepItPalette): AnnotatedString =
    buildAnnotatedString { appendInlines(parent, p) }

private fun AnnotatedString.Builder.appendInlines(parent: Node, p: KeepItPalette) {
    for (child in parent.children()) appendInline(child, p)
}

private fun AnnotatedString.Builder.appendInline(node: Node, p: KeepItPalette) {
    when (node) {
        is TextNode -> append(node.literal)
        is Code -> withStyle(codeStyle(p)) { append(node.literal) }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node, p) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(node, p) }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(node, p) }
        is Link -> {
            val url = clickableUrl(node.destination)
            if (url == null) {
                appendInlines(node, p)
            } else {
                withLink(LinkAnnotation.Url(url, linkStyles(p))) { appendInlines(node, p) }
            }
        }
        // remark-breaks: every newline in a paragraph is a line break, soft or not.
        is SoftLineBreak, is HardLineBreak -> append('\n')
        is HtmlInline -> append(node.literal)
        is TaskListItemMarker -> Unit
        // Images and anything an extension adds: their text (an image's alt text) is what shows.
        else -> appendInlines(node, p)
    }
}

private val ClickableSchemes = setOf("http", "https", "mailto", "tel")

/**
 * The URL a link may open, or null to show its text unlinked. Only schemes that hand off to a
 * browser, mail or the dialer: anything else reaches Android's ACTION_VIEW, where a `file://` link
 * in a note someone shared with you threw FileUriExposedException and took the app down, and a
 * scheme-less one — the toolbar's own `url` placeholder — silently did nothing.
 */
internal fun clickableUrl(destination: String): String? {
    val colon = destination.indexOf(':')
    if (colon <= 0 || colon == destination.lastIndex) return null
    return destination.takeIf { destination.substring(0, colon).lowercase() in ClickableSchemes }
}

internal fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

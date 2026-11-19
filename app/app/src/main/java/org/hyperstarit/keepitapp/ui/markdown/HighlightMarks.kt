package org.hyperstarit.keepitapp.ui.markdown

import org.commonmark.node.HtmlInline
import org.commonmark.node.Node

/**
 * Highlighted text in a note body: `<mark data-color="green">text</mark>`, the one piece of HTML a
 * note may hold — the twin of the web's `features/notes/highlight.ts`, and held to the same cases
 * (`web/src/features/notes/highlight.json`). Every other piece of raw HTML still shows as the text
 * it is; this tag alone becomes a highlight, in one of five colours. `<mark>` without a colour, or
 * with one this build doesn't know, is yellow, and the toolbar always writes the colour out.
 *
 * Recognised only as written here — `<mark>` or `<mark data-color="…">`, case and spacing aside —
 * and only when its closing tag is in the same paragraph, in the same run of formatting (the same
 * parent node); otherwise both tags show as text.
 */
object HighlightMarks {
    /** The colours, in the order the toolbar offers them. */
    val COLORS = listOf("yellow", "green", "blue", "pink", "purple")

    /** For people: "Yellow", "Green", … */
    fun label(color: String): String = color.replaceFirstChar { it.uppercaseChar() }

    private val OPEN = Regex(
        """^<mark(?:\s+data-color\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?\s*>$""",
        RegexOption.IGNORE_CASE,
    )
    private val CLOSE = Regex("""^</mark\s*>$""", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("""</?mark\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** What a piece of inline HTML is. */
    sealed interface Tag {
        data class Open(val color: String) : Tag
        data object Close : Tag
    }

    /** [html] as a highlight's opening tag (with its colour), its closing tag, or null for neither. */
    fun tagOf(html: String): Tag? {
        if (CLOSE.matches(html)) return Tag.Close
        val m = OPEN.matchEntire(html) ?: return null
        val named = (m.groups[1] ?: m.groups[2] ?: m.groups[3])?.value?.lowercase()
        return Tag.Open(if (named in COLORS) named!! else "yellow")
    }

    /** A run of sibling inlines: plain nodes, and highlights holding their own runs. */
    sealed interface Piece {
        data class Plain(val node: Node) : Piece
        data class Highlight(val color: String, val open: Node, val close: Node, val inside: List<Piece>) : Piece
    }

    /**
     * [nodes] (siblings) with their `<mark>` tags paired, innermost first. An unpaired tag stays a
     * plain node, which the renderer shows as text.
     */
    fun pair(nodes: List<Node>): List<Piece> {
        val out = mutableListOf<Piece>()
        val open = ArrayDeque<Int>()
        for (node in nodes) {
            val tag = (node as? HtmlInline)?.literal?.let(::tagOf)
            if (tag == Tag.Close && open.isNotEmpty()) {
                val at = open.removeLast()
                val opener = (out[at] as Piece.Plain).node
                val color = (tagOf((opener as HtmlInline).literal) as Tag.Open).color
                val inside = out.subList(at + 1, out.size).toList()
                while (out.size > at) out.removeAt(out.size - 1)
                out += Piece.Highlight(color, opener, node, inside)
                continue
            }
            if (tag is Tag.Open) open.addLast(out.size)
            out += Piece.Plain(node)
        }
        return out
    }

    /** A note body without its highlight tags: what search looks through. */
    fun withoutTags(text: String): String = text.replace(ANY_TAG, "")

    // ---- editing: the toolbar's highlighter ----

    /** The text after an edit, and where the selection lands. */
    data class Edit(val value: String, val selectionStart: Int, val selectionEnd: Int)

    private fun openTag(color: String) = "<mark data-color=\"$color\">"
    private const val CLOSE_TAG = "</mark>"

    /**
     * Highlights the selection in [color], or with null takes the highlight away — the web's
     * `applyHighlight`, rule for rule. A selection inside (or exactly around) a highlight on its
     * line changes that highlight instead: the same colour or null removes it, another colour
     * recolours it. Otherwise the selection is wrapped, line by line when it spans several and
     * inside each line's list marker (see [lineSegments]) so an item stays an item, and an empty
     * one gets an empty highlight with the caret inside.
     */
    fun apply(value: String, start: Int, end: Int, color: String?): Edit {
        // On one line, the selection less its spaces and list marker: "- <mark>a</mark>", never
        // "<mark>- a</mark>", which is no longer a list item.
        val segments = if (start == end) emptyList() else lineSegments(value, start, end)
        val single = segments.size == 1
        val s = if (single) segments[0].first else start
        val e = if (single) segments[0].last + 1 else end
        val around = enclosing(value, s, e)
        if (around != null) {
            val inner = value.substring(around.innerStart, around.innerEnd)
            val remove = color == null || color == around.color
            val open = if (remove) "" else openTag(color!!)
            val close = if (remove) "" else CLOSE_TAG
            val shift = open.length - (around.innerStart - around.openStart)
            return Edit(
                value.substring(0, around.openStart) + open + inner + close + value.substring(around.closeEnd),
                maxOf(around.openStart + open.length, s + shift),
                minOf(around.openStart + open.length + inner.length, e + shift),
            )
        }
        if (color == null) return Edit(value, start, end)

        val open = openTag(color)
        if (start == end) {
            return Edit(value.substring(0, start) + open + CLOSE_TAG + value.substring(end), start + open.length, start + open.length)
        }
        if (segments.isEmpty()) return Edit(value, start, end)
        if (single) {
            return Edit(value.substring(0, s) + open + value.substring(s, e) + CLOSE_TAG + value.substring(e), s + open.length, e + open.length)
        }
        val next = segments.asReversed().fold(value) { acc, seg ->
            acc.substring(0, seg.first) + open + acc.substring(seg.first, seg.last + 1) + CLOSE_TAG + acc.substring(seg.last + 1)
        }
        // Over several lines, the selection covers every highlight, tags included.
        return Edit(
            next,
            minOf(start, segments.first().first),
            maxOf(end, segments.last().last + 1) + next.length - value.length,
        )
    }

    private data class Around(val openStart: Int, val innerStart: Int, val innerEnd: Int, val closeEnd: Int, val color: String)

    /** The innermost highlight on the selection's line that holds the selection, tags included or not. */
    private fun enclosing(value: String, start: Int, end: Int): Around? {
        if ('\n' in value.substring(start, end)) return null
        val lineStart = if (start == 0) 0 else value.lastIndexOf('\n', start - 1) + 1
        val lineEnd = value.indexOf('\n', end).let { if (it == -1) value.length else it }
        val line = value.substring(lineStart, lineEnd)

        var best: Around? = null
        val stack = ArrayDeque<Triple<Int, Int, String>>()
        for (m in ANY_TAG.findAll(line)) {
            when (val tag = tagOf(m.value)) {
                null -> continue
                is Tag.Open -> stack.addLast(Triple(m.range.first, m.value.length, tag.color))
                Tag.Close -> {
                    val (at, length, color) = stack.removeLastOrNull() ?: continue
                    val pairAt = Around(
                        lineStart + at,
                        lineStart + at + length,
                        lineStart + m.range.first,
                        lineStart + m.range.last + 1,
                        color,
                    )
                    if (pairAt.openStart <= start && end <= pairAt.closeEnd && (best == null || pairAt.openStart >= best.openStart)) {
                        best = pairAt
                    }
                }
            }
        }
        return best
    }
}

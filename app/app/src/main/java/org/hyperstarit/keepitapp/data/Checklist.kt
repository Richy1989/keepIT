package org.hyperstarit.keepitapp.data

import java.text.Normalizer

/**
 * Display order for a checklist: **unchecked rows first, checked rows at the bottom**, each group
 * keeping its stored order.
 *
 * The stored `order` is the row's *home* position — it changes only when rows are added, removed or
 * dragged, never when a box is ticked (the editor's save renumbers `order` from the home list, and
 * the server renumbers `Order` from the incoming array index). So ticking a row sinks it, unticking
 * puts it back in exactly the slot it came from, and a freshly added row lands at the end of the
 * unchecked block — all derived from persisted state, which is why it holds after a reload and
 * matches the web client, which applies the same rule in `web/src/features/notes/checklist.ts`.
 *
 * `sortedWith` is stable (TimSort), so the `order` tiebreak only matters for the rows the server
 * hasn't renumbered yet (an optimistic note still sitting in the outbox).
 *
 * Every read-only checklist surface goes through this: the note card preview, the widget preview.
 * The editor holds `EditableItem`s rather than DTOs and applies the same partition over its home
 * list — see `displayRows` in `ui/notes/EditorScreen.kt`.
 */
fun List<ChecklistItemDto>.inDisplayOrder(): List<ChecklistItemDto> =
    sortedWith(compareBy({ it.isChecked }, { it.order }))

/** What [splitPastedRow] makes of a paste: the rows' texts, and where the caret goes in the last. */
data class PastedRows(val rows: List<String>, val caret: Int)

/**
 * What text with line breaks makes of the checklist row it is pasted into: **each line a row of
 * its own**, as the same text pasted into a document would read. A row's field shows one line, so
 * the lot would otherwise sit in that row with the line breaks inside it.
 *
 * [head] is the row's text up to the caret with the pasted text added, [tail] is what followed the
 * caret, and [room] is how many more rows the checklist can take. The first of the rows replaces
 * the row pasted into and the others go below it; the caret is where the pasted text ends in the
 * last one. Blank lines make no rows and each row is trimmed, since the note is saved that way.
 *
 * Nothing the server would refuse comes out: lines past [room] are left out (what followed the
 * caret then stays behind the last line kept, never dropped with them), and a row is cut to
 * [NoteLimits.CHECKLIST_ITEM_TEXT].
 *
 * The web client's `splitPastedRow` (`web/src/features/notes/checklist.ts`) is the same rule;
 * `checklistPaste.json` beside it holds the cases, and `ChecklistPasteTest` runs them against this.
 */
fun splitPastedRow(head: String, tail: String, room: Int): PastedRows {
    val lines = head.split("\r\n", "\r", "\n")
    fun filled(texts: List<String>) = texts.map { it.trim() }.filter { it.isNotEmpty() }

    var rows = filled(lines.dropLast(1) + (lines.last() + tail))
    if (rows.size > room + 1) {
        val kept = filled(lines).take(room + 1)
        rows = kept.dropLast(1) + (kept.last() + tail).trimEnd()
    }
    if (rows.isEmpty()) rows = listOf("")
    rows = rows.map { NoteLimits.cut(it, NoteLimits.CHECKLIST_ITEM_TEXT) }

    return PastedRows(rows, (rows.last().length - tail.trimEnd().length).coerceAtLeast(0))
}

/** A checklist item as [checklistFromText] makes it from a line of text. */
data class TextItem(val text: String, val isChecked: Boolean)

/** A Markdown list item's marker (`-`, `*`, `+`, `1.`, `1)`), then a task box (`[ ]`, `[x]`). */
private val LIST_MARKER = Regex("^(?:[-*+]|\\d{1,9}[.)])(?:\\s+|$)")
private val TASK_BOX = Regex("^\\[([ xX])\\](?:\\s+|$)")

/**
 * What switching a text note to a checklist makes of its text: **each line an item**, so a note
 * holds one text whichever way it is shown. Blank lines make no items and each item is trimmed.
 * A Markdown list's markers are dropped, since a checklist row is already one, and a Markdown task
 * keeps its tick: `- [x] Bread` is a ticked "Bread". Nothing else is read as Markdown.
 *
 * Returns null when the text doesn't fit a checklist: more lines than
 * [NoteLimits.CHECKLIST_ITEMS], or a line longer than [NoteLimits.CHECKLIST_ITEM_TEXT]. The note
 * then stays text rather than lose the rest, which the server refusing the save would cost too.
 *
 * The web client's `checklistFromText` (`web/src/features/notes/checklist.ts`) is the same rule;
 * `checklistConvert.json` beside it holds the cases, and `ChecklistConvertTest` runs them against
 * this.
 */
fun checklistFromText(text: String): List<TextItem>? {
    val items = mutableListOf<TextItem>()
    for (line in text.split("\r\n", "\r", "\n")) {
        var rest = line.trim()
        var isChecked = false
        LIST_MARKER.find(rest)?.let { marker ->
            rest = rest.substring(marker.value.length)
            TASK_BOX.find(rest)?.let { box ->
                rest = rest.substring(box.value.length)
                isChecked = box.groupValues[1] != " "
            }
        }
        rest = rest.trim()
        if (rest.isEmpty()) continue
        if (rest.length > NoteLimits.CHECKLIST_ITEM_TEXT || items.size == NoteLimits.CHECKLIST_ITEMS) return null
        items += TextItem(rest, isChecked)
    }
    return items
}

/**
 * What switching a checklist to a text note makes of its items: **each item a line**, in the
 * list's own (home) order, so text that became a list comes back as it was written. The ticks are
 * not kept: a ticked item is a plain line where it stands. Empty items make no lines.
 *
 * The web client's `textFromChecklist` is the same rule, held to the same `checklistConvert.json`.
 */
fun textFromChecklist(texts: List<String>): String =
    texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/** Text as [suggestFromChecked] compares it: no accents, no case, no space around it. */
private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase().trim()

private val MARKS = Regex("\\p{M}")

/** Whether [typed] starts a word of [text] other than its first: it follows a non-letter, non-digit. */
private fun startsAWord(text: String, typed: String): Boolean {
    var at = text.indexOf(typed, 1)
    while (at != -1) {
        if (!text[at - 1].isLetterOrDigit()) return true
        at = text.indexOf(typed, at + 1)
    }
    return false
}

/**
 * The ticked row to offer while a new row is being typed. A list used again and again — the weekly
 * shop — holds last time's items ticked at the bottom, and typing one of them again should bring
 * that row back rather than make a second one.
 *
 * [typing] is the index in [items] (home order) of the row being typed. What is offered is a ticked
 * row whose text starts with what has been typed or, failing that, has a word that does ("gar"
 * finds "2 cloves garlic"), ignoring case and accents. Of several, the shortest wins, so "Milk"
 * comes before "Milk chocolate" and typing on reaches the longer one; of equally long ones, the
 * first shown. A ticked row whose text is on the list unticked as well is never offered: taking it
 * would make the very duplicate this is here to avoid.
 *
 * Returns the offered row's index, or null when nothing fits or nothing has been typed. The web
 * client's `suggestFromChecked` (`web/src/features/notes/checklist.ts`) is the same rule;
 * `checklistSuggest.json` beside it holds the cases, and `ChecklistSuggestTest` runs them against
 * this.
 */
fun suggestFromChecked(items: List<ChecklistItemDto>, typing: Int): Int? {
    val row = items.getOrNull(typing)
    val typed = if (row != null && !row.isChecked) fold(row.text) else ""
    if (typed.isEmpty()) return null

    val unticked = items.filterIndexed { index, it -> !it.isChecked && index != typing }.mapTo(HashSet()) { fold(it.text) }
    var best: Int? = null
    var bestTier = 0
    var bestLength = 0
    items.forEachIndexed { index, item ->
        val text = if (item.isChecked) fold(item.text) else ""
        if (text.isEmpty() || text in unticked) return@forEachIndexed
        val tier = when {
            text.startsWith(typed) -> 0
            startsAWord(text, typed) -> 1
            else -> return@forEachIndexed
        }
        if (best == null || tier < bestTier || (tier == bestTier && text.length < bestLength)) {
            best = index
            bestTier = tier
            bestLength = text.length
        }
    }
    return best
}

/** The only indentation levels a checklist holds: top-level (0) and sub-item (1). */
const val CHECKLIST_MAX_INDENT = 1

/** The row's level, treating older data as top-level and clamping anything deeper. */
fun ChecklistItemDto.indentLevel(): Int = indent.coerceIn(0, CHECKLIST_MAX_INDENT)

/** Stored index of the top-level row above [index], or -1 when there is none. */
fun List<ChecklistItemDto>.parentIndexOf(index: Int): Int {
    if (index <= 0 || index >= size) return -1

    for (at in index - 1 downTo 0) {
        if (this[at].indentLevel() == 0) return at
    }

    return -1
}

/** Stored indices of the sub-items immediately below the top-level row at [parentIndex]. */
fun List<ChecklistItemDto>.childIndicesOf(parentIndex: Int): List<Int> {
    if (parentIndex !in indices || this[parentIndex].indentLevel() != 0) return emptyList()

    val children = mutableListOf<Int>()
    var at = parentIndex + 1
    while (at < size && this[at].indentLevel() == 1) {
        children += at
        at++
    }

    return children
}

/** Whether the row may become a sub-item: not first, currently top-level, with a top-level above. */
fun List<ChecklistItemDto>.canIndentAt(index: Int): Boolean =
    index in 1 until size && this[index].indentLevel() == 0 && parentIndexOf(index) != -1

/** Whether the row is a sub-item that can return to the top level. */
fun List<ChecklistItemDto>.canOutdentAt(index: Int): Boolean =
    index in indices && this[index].indentLevel() == 1

/** The list with one row's level changed when that move is allowed, or itself unchanged. */
fun List<ChecklistItemDto>.withIndentAt(index: Int, level: Int): List<ChecklistItemDto> {
    if (level !in 0..1 || index !in indices) return this

    val allowed = if (level == 1) canIndentAt(index) else canOutdentAt(index)
    if (!allowed || this[index].indentLevel() == level) return this

    return mapIndexed { at, item -> if (at == index) item.copy(indent = level) else item }
}

/**
 * The list with one box toggled, applying the parent/child rule: a top-level row with children
 * carries the whole group, while a child only changes itself and then recomputes its parent.
 */
fun List<ChecklistItemDto>.withCheckedAt(index: Int, checked: Boolean): List<ChecklistItemDto> {
    if (index !in indices) return this

    val next = toMutableList()
    next[index] = next[index].copy(isChecked = checked)
    for (child in childIndicesOf(index)) next[child] = next[child].copy(isChecked = checked)

    if (this[index].indentLevel() == 1) {
        val parent = parentIndexOf(index)
        if (parent != -1) {
            val allChecked = childIndicesOf(parent).all { next[it].isChecked }
            next[parent] = next[parent].copy(isChecked = allChecked)
        }
    }

    return next
}

/** The list put back into the single-level model: first/orphan rows top-level, deeper values 1. */
fun List<ChecklistItemDto>.normalizedIndents(): List<ChecklistItemDto> {
    var seenTopLevel = false

    return map { item ->
        val level = if (item.indentLevel() > 0 && seenTopLevel) 1 else 0
        if (level == 0) seenTopLevel = true

        if (item.indent == level) item else item.copy(indent = level)
    }
}

/** Where a row added by Enter goes: after the row, or after its whole group when it is a parent. */
fun List<ChecklistItemDto>.insertionIndexAfter(index: Int): Int {
    if (index !in indices) return size

    return index + 1 + childIndicesOf(index).size
}

/** The level of a row added after [index]: another sub-item below a sub-item, else top-level. */
fun List<ChecklistItemDto>.newItemIndentAfter(index: Int): Int =
    if (index in indices && this[index].indentLevel() == 1) 1 else 0

/** A row in a checklist's completed section: a real checked row, or a greyed context heading. */
sealed interface ChecklistDisplayRow {
    data class Item(val item: ChecklistItemDto, val index: Int) : ChecklistDisplayRow
    data class Heading(val text: String, val parentIndex: Int) : ChecklistDisplayRow
}

/** The active and completed halves of a checklist, each in the stored (home) order. */
data class ChecklistSections(
    val active: List<ChecklistDisplayRow.Item>,
    val completed: List<ChecklistDisplayRow>,
)

/**
 * Splits a checklist for display. A partial group appears twice: the parent stays active with its
 * unchecked children, and the completed half repeats its text as a non-interactive heading above
 * the checked children. A fully checked group has no heading because its parent is checked too.
 */
fun List<ChecklistItemDto>.sections(): ChecklistSections {
    val active = mutableListOf<ChecklistDisplayRow.Item>()
    val completed = mutableListOf<ChecklistDisplayRow>()
    val headed = mutableSetOf<Int>()

    forEachIndexed { index, item ->
        if (!item.isChecked) {
            active += ChecklistDisplayRow.Item(item, index)
            return@forEachIndexed
        }

        if (item.indentLevel() == 1) {
            val parent = parentIndexOf(index)
            if (parent != -1 && !this[parent].isChecked && headed.add(parent)) {
                completed += ChecklistDisplayRow.Heading(this[parent].text, parent)
            }
        }
        completed += ChecklistDisplayRow.Item(item, index)
    }

    return ChecklistSections(active, completed)
}

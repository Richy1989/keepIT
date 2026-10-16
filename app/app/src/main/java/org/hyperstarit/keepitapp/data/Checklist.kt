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

package org.hyperstarit.keepitapp.data

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

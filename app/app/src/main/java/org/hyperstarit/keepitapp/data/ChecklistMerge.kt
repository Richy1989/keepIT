package org.hyperstarit.keepitapp.data

import java.util.UUID

/**
 * Merges one edit of a checklist into the rows a note holds now, row by row — the app's copy of the
 * server's `Notes/ChecklistMerge.cs` and the web's `checklistMerge.ts`, all three held to the cases
 * in `web/src/features/notes/checklistMerge.json` (`ChecklistMergeTest`).
 *
 * A shared checklist is edited by several people at once: two of them in a shop, both ticking, one
 * adding the thing they forgot. Every save sends the whole list, and replacing the note's list with
 * it undid whatever anyone else had changed meanwhile. So an edit also carries the rows it started
 * from ([UpdateNoteDto.checklistBase]), and only the difference is applied: what it ticked, renamed,
 * indented, added, deleted or moved. A row both changed keeps each one's change to a different
 * property and the later one's to the same property; a row this edit changed that someone else
 * deleted stays deleted.
 *
 * The app runs it twice over:
 * - on the cache, for an edit still in the outbox ([org.hyperstarit.keepitapp.data.offline.applyOp]),
 *   so it shows as the server will apply it rather than hiding what a sync just brought in;
 * - in the open editor, to bring in what a sync brought while it is open, with the editor's own
 *   unsaved rows as the edit (`EditorScreen`).
 *
 * Rows are matched by id, which is why a new row is given one here ([newChecklistRowId]) rather
 * than left for the server to assign: a row without an id can't be told apart from a second copy.
 */
fun mergeChecklist(
    base: List<ChecklistItemDto>,
    incoming: List<ChecklistItemDto>,
    current: List<ChecklistItemDto>,
): List<ChecklistItemDto> {
    // A row the edit started from without an id can't be matched, and merging would keep the note's
    // copy of each row as well as the edit's: a list twice over. The edit replaces the list instead.
    if (base.any { it.id == null }) return incoming.mapIndexed { index, row -> row.copy(order = index) }

    val baseById = base.byId()
    val incomingById = incoming.byId()
    val currentIds = current.mapNotNullTo(HashSet()) { it.id }

    // What survives of the rows the note has now, with this edit's changes laid over them.
    val merged = current.mapNotNull { row ->
        val id = row.id ?: return@mapNotNull null
        val before = baseById[id]
        val after = incomingById[id]
        when {
            before != null && after == null -> null // this edit deleted it
            before != null -> row.changedBy(before, after!!)
            after != null -> after.copy(id = id) // this edit's own new row, sent again
            else -> row // someone else's new row
        }
    }

    // This edit's new rows: no id, or one neither its base nor the note knows. Rows it started from
    // that are gone from the note were deleted by someone else, and stay gone.
    fun isAddition(row: ChecklistItemDto) =
        row.id == null || (row.id !in baseById && row.id !in currentIds)

    val seen = HashSet<String>()
    val edit = incoming.filter { it.id == null || seen.add(it.id) }

    val result = if (!moved(base, edit, baseById)) {
        // The note's order, with this edit's new rows each after the row it followed in the edit.
        insertAfterNeighbours(merged, edit, ::isAddition)
    } else {
        // The edit moved rows: its order wins, and everyone else's new rows go in after the row
        // they followed in the note.
        val mergedById = merged.associateBy { it.id!! }
        val inEditOrder = edit.mapNotNull { row ->
            when {
                isAddition(row) -> row
                else -> mergedById[row.id]
            }
        }
        val editIds = inEditOrder.mapNotNullTo(HashSet()) { it.id }
        insertAfterNeighbours(inEditOrder, merged) { it.id !in editIds }
    }
    return result.mapIndexed { index, row -> row.copy(order = index) }
}

/** A fresh id for a row made on this device; see [mergeChecklist] for why rows get one here. */
fun newChecklistRowId(): String = UUID.randomUUID().toString()

/**
 * [sequence] with the rows of [source] that [isExtra] picks inserted, each straight after the row it
 * follows in [source] (at the start when nothing it follows is there).
 */
private fun insertAfterNeighbours(
    sequence: List<ChecklistItemDto>,
    source: List<ChecklistItemDto>,
    isExtra: (ChecklistItemDto) -> Boolean,
): List<ChecklistItemDto> {
    val result = sequence.toMutableList()
    var cursor = 0
    for (row in source) {
        if (isExtra(row)) {
            result.add(cursor++, row)
            continue
        }
        val at = if (row.id == null) -1 else result.indexOfFirst { it.id == row.id }
        if (at >= 0) cursor = at + 1
    }
    return result
}

/** True when the rows the edit kept are not in the order they had in its base. */
private fun moved(
    base: List<ChecklistItemDto>,
    edit: List<ChecklistItemDto>,
    baseById: Map<String, ChecklistItemDto>,
): Boolean {
    val kept = edit.mapNotNullTo(HashSet()) { row -> row.id?.takeIf { it in baseById } }
    val inBaseOrder = base.mapNotNull { row -> row.id?.takeIf { it in kept } }.distinct()
    val inEditOrder = edit.mapNotNull { row -> row.id?.takeIf { it in kept } }
    return inBaseOrder != inEditOrder
}

/** This row with whatever the edit changed from [before] to [after]. */
private fun ChecklistItemDto.changedBy(before: ChecklistItemDto, after: ChecklistItemDto) = copy(
    text = if (after.text != before.text) after.text else text,
    isChecked = if (after.isChecked != before.isChecked) after.isChecked else isChecked,
    indent = if (after.indent != before.indent) after.indent else indent,
)

/** The rows that have an id, by id; a repeated id counts once, as its first row. */
private fun List<ChecklistItemDto>.byId(): Map<String, ChecklistItemDto> {
    val byId = LinkedHashMap<String, ChecklistItemDto>()
    for (row in this) row.id?.let { byId.putIfAbsent(it, row) }
    return byId
}

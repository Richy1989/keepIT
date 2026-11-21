import type { ChecklistItemDto } from '../../api/types';

/**
 * Merges one edit of a checklist into the rows a note holds now, row by row: the web's copy of the
 * server's `Notes/ChecklistMerge.cs` (and the Android app's `ChecklistMerge.kt`), all three held to
 * the cases in `checklistMerge.json`.
 *
 * A shared checklist is edited by several people at once — two of them in a shop, both ticking, one
 * adding the thing they forgot. Every save sends the whole list, and replacing the note's list with
 * it undid whatever anyone else changed meanwhile. So a save also sends the rows it started from
 * (`UpdateNoteDto.checklistBase`), and only the difference is applied: what it ticked, renamed,
 * indented, added, deleted or moved. A row both changed keeps each one's change to a different
 * property and the later one's to the same property; a row this edit changed that someone else
 * deleted stays deleted. A base with a row that has no id (a note shown before its first refetch)
 * can't be matched, so that edit replaces the list, as every edit used to.
 *
 * The web runs it on the cache, so a save shows at once as the server will apply it.
 */
export function mergeChecklist(
  base: readonly ChecklistItemDto[],
  incoming: readonly ChecklistItemDto[],
  current: readonly ChecklistItemDto[],
): ChecklistItemDto[] {
  const numbered = (rows: ChecklistItemDto[]) => rows.map((row, order) => ({ ...row, order }));
  if (base.some((r) => r.id == null)) return numbered([...incoming]);

  const baseById = byId(base);
  const incomingById = byId(incoming);
  const currentIds = new Set(current.flatMap((r) => (r.id == null ? [] : [r.id])));

  // What survives of the rows the note has now, with this edit's changes laid over them.
  const merged: ChecklistItemDto[] = [];
  for (const row of current) {
    if (row.id == null) continue;
    const before = baseById.get(row.id);
    const after = incomingById.get(row.id);
    if (before && !after) continue; // this edit deleted it
    if (before && after) merged.push(changedBy(row, before, after));
    else if (after) merged.push({ ...after, id: row.id }); // this edit's own new row, sent again
    else merged.push(row); // someone else's new row
  }

  // This edit's new rows: no id, or one neither its base nor the note knows. Rows it started from
  // that are gone from the note were deleted by someone else, and stay gone.
  const isAddition = (row: ChecklistItemDto) =>
    row.id == null || (!baseById.has(row.id) && !currentIds.has(row.id));

  const seen = new Set<string>();
  const edit = incoming.filter((r) => r.id == null || (!seen.has(r.id) && !!seen.add(r.id)));

  if (!moved(base, edit, baseById)) {
    // The note's order, with this edit's new rows each after the row it followed in the edit.
    return numbered(insertAfterNeighbours(merged, edit, isAddition));
  }

  // The edit moved rows: its order wins, and everyone else's new rows go in after the row they
  // followed in the note.
  const mergedById = new Map(merged.map((r) => [r.id!, r]));
  const inEditOrder = edit.flatMap((row) => {
    if (isAddition(row)) return [row];
    const kept = mergedById.get(row.id!);
    return kept ? [kept] : [];
  });
  const editIds = new Set(inEditOrder.flatMap((r) => (r.id == null ? [] : [r.id])));
  return numbered(insertAfterNeighbours(inEditOrder, merged, (r) => !editIds.has(r.id!)));
}

/**
 * `sequence` with the rows of `source` that `isExtra` picks inserted, each straight after the row it
 * follows in `source` (at the start when nothing it follows is there).
 */
function insertAfterNeighbours(
  sequence: readonly ChecklistItemDto[],
  source: readonly ChecklistItemDto[],
  isExtra: (row: ChecklistItemDto) => boolean,
): ChecklistItemDto[] {
  const result = [...sequence];
  let cursor = 0;
  for (const row of source) {
    if (isExtra(row)) {
      result.splice(cursor++, 0, row);
      continue;
    }
    const at = row.id == null ? -1 : result.findIndex((r) => r.id === row.id);
    if (at >= 0) cursor = at + 1;
  }
  return result;
}

/** True when the rows the edit kept are not in the order they had in its base. */
function moved(
  base: readonly ChecklistItemDto[],
  edit: readonly ChecklistItemDto[],
  baseById: ReadonlyMap<string, ChecklistItemDto>,
): boolean {
  const kept = new Set(edit.flatMap((r) => (r.id != null && baseById.has(r.id) ? [r.id] : [])));
  const inBaseOrder = [...new Set(base.flatMap((r) => (r.id != null && kept.has(r.id) ? [r.id] : [])))];
  const inEditOrder = edit.flatMap((r) => (r.id != null && kept.has(r.id) ? [r.id] : []));
  return inBaseOrder.length !== inEditOrder.length || inBaseOrder.some((id, i) => id !== inEditOrder[i]);
}

/** `now` with whatever the edit changed from `before` to `after`. */
function changedBy(now: ChecklistItemDto, before: ChecklistItemDto, after: ChecklistItemDto): ChecklistItemDto {
  return {
    ...now,
    text: after.text !== before.text ? after.text : now.text,
    isChecked: after.isChecked !== before.isChecked ? after.isChecked : now.isChecked,
    // An edit that doesn't say (null) leaves the indent alone.
    indent: after.indent != null && after.indent !== (before.indent ?? now.indent) ? after.indent : now.indent,
  };
}

/** The rows that have an id, by id; a repeated id counts once, as its first row. */
function byId(rows: readonly ChecklistItemDto[]): Map<string, ChecklistItemDto> {
  const map = new Map<string, ChecklistItemDto>();
  for (const row of rows) if (row.id != null && !map.has(row.id)) map.set(row.id, row);
  return map;
}

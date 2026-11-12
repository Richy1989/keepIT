import type { ChecklistItemDto, NoteType } from '../../api/types';

/** A checklist row paired with its index in the stored (unsorted) array, so edits address the right item. */
export type ChecklistRow = { item: ChecklistItemDto; index: number };

/**
 * A row in the completed section. A real checked item is an `item` row; a `heading` is the greyed,
 * non-interactive copy of an unchecked parent shown above its checked children, so a partially
 * completed group reads twice without the copy counting as an item of its own.
 */
export type CompletedRow =
  | { kind: 'item'; item: ChecklistItemDto; index: number }
  | { kind: 'heading'; text: string; parentIndex: number };

/** The active and completed halves of a checklist, each in the stored (home) order. */
export type ChecklistSections = { active: ChecklistRow[]; completed: CompletedRow[] };

/**
 * A row's nesting depth: 0 for a top-level row, 1 for a sub-item. `indent` is part of the DTO now,
 * but notes cached before it existed may not carry it, so a missing value reads as top-level rather
 * than turning every old row into a sub-item (or crashing on `undefined`).
 */
export function indentOf(item: ChecklistItemDto): number {
  return item.indent ?? 0;
}

/** Whether `item` is a sub-item. A row deeper than one level still counts, so old bad data is visible. */
export function isSubItem(item: ChecklistItemDto): boolean {
  return indentOf(item) > 0;
}

/**
 * Stored index of the top-level row above `index`, or -1 when there is none. That row is the parent
 * of a sub-item; the indent-1 rows between the two are its siblings. Called on a top-level row this
 * is simply the previous top-level row (not its parent), so callers that need a parent check
 * [isSubItem] first.
 */
export function parentIndexOf(items: ChecklistItemDto[], index: number): number {
  if (index <= 0 || index >= items.length) return -1;
  for (let at = index - 1; at >= 0; at -= 1) {
    if (!isSubItem(items[at])) return at;
  }
  return -1;
}

/**
 * Stored indices of the sub-items of the top-level row at `parentIndex`: the contiguous indent-1
 * rows immediately below it, up to the next top-level row. A sub-item is never itself a parent, so
 * asking one returns no children.
 */
export function childIndicesOf(items: ChecklistItemDto[], parentIndex: number): number[] {
  if (parentIndex < 0 || parentIndex >= items.length || isSubItem(items[parentIndex])) return [];
  const children: number[] = [];
  for (let at = parentIndex + 1; at < items.length && isSubItem(items[at]); at += 1) {
    children.push(at);
  }
  return children;
}

/**
 * Whether the row at `index` may become a sub-item. The first row never can, a row that already is
 * one cannot go deeper, and the row must have a top-level row above it to belong to.
 *
 * A parent with sub-items can be indented: its children already sit at the sub-item level, so
 * indenting the parent makes the whole group siblings under the previous top-level row. That is
 * how a completed block is moved under another one without creating a second nesting level.
 */
export function canIndent(items: ChecklistItemDto[], index: number): boolean {
  if (index <= 0 || index >= items.length) return false;
  return indentOf(items[index]) === 0 && parentIndexOf(items, index) !== -1;
}

/** Whether the row at `index` may become top-level again: any sub-item can always be outdented. */
export function canOutdent(items: ChecklistItemDto[], index: number): boolean {
  return index >= 0 && index < items.length && isSubItem(items[index]);
}

/**
 * The checklist with the row at `index` set to `indent`, or `items` unchanged when the move is not
 * allowed (see [canIndent] / [canOutdent]) or the depth is not one of the two the model holds.
 * Returns a new array, so callers can treat the checklist as immutable.
 */
export function setItemIndent(items: ChecklistItemDto[], index: number, indent: number): ChecklistItemDto[] {
  if (indent !== 0 && indent !== 1) return items;
  const allowed = indent === 1 ? canIndent(items, index) : canOutdent(items, index);
  if (!allowed || indentOf(items[index]) === indent) return items;
  return items.map((item, at) => (at === index ? { ...item, indent } : item));
}

/**
 * The checklist with the row at `index` checked or unchecked, applying the parent/child rule:
 *
 * - a top-level row with children carries the whole group: checking or unchecking it does the same
 *   to every child;
 * - a sub-item only changes its own box, and its parent is then recomputed from all of its children
 *   (checked if and only if every one is checked).
 *
 * A parent without children keeps its own state, and an orphaned sub-item simply changes alone.
 * The stored order is untouched; ticking a row only changes where the display sections put it.
 */
export function setItemChecked(items: ChecklistItemDto[], index: number, checked: boolean): ChecklistItemDto[] {
  if (index < 0 || index >= items.length) return items;

  const next = items.slice();
  const setChecked = (at: number, value: boolean) => {
    if (next[at].isChecked !== value) next[at] = { ...next[at], isChecked: value };
  };

  setChecked(index, checked);
  const children = childIndicesOf(items, index);
  for (const child of children) setChecked(child, checked);

  if (isSubItem(items[index])) {
    const parent = parentIndexOf(items, index);
    if (parent !== -1) {
      const allChecked = childIndicesOf(items, parent).every((child) => next[child].isChecked);
      setChecked(parent, allChecked);
    }
  }
  return next;
}

/**
 * The checklist put back into the single-level model after a row is removed or inserted: the first
 * row (and any row with no top-level row before it) becomes top-level, anything deeper collapses to
 * 1. This is the same pass the server runs on save, so what the editor shows survives a round trip.
 */
export function normalizeIndents(items: ChecklistItemDto[]): ChecklistItemDto[] {
  let seenTopLevel = false;
  return items.map((item) => {
    const indent = indentOf(item) > 0 && seenTopLevel ? 1 : 0;
    if (indent === 0) seenTopLevel = true;
    return item.indent === indent ? item : { ...item, indent };
  });
}

/**
 * Where a row added by Enter goes: after the row itself, or after the whole group when that row is
 * a parent, so a new top-level row never lands between a parent and its children. A sub-item has no
 * children, so it inserts directly below itself.
 */
export function insertionIndexAfter(items: ChecklistItemDto[], index: number): number {
  if (index < 0 || index >= items.length) return items.length;
  return index + 1 + childIndicesOf(items, index).length;
}

/** The depth of a row added by Enter: another sub-item under a sub-item, otherwise top-level. */
export function newItemIndentAfter(items: ChecklistItemDto[], index: number): number {
  return index >= 0 && index < items.length && isSubItem(items[index]) ? 1 : 0;
}

/**
 * Display order for a checklist: **unchecked rows first, checked rows at the bottom**, each group
 * keeping the stored order.
 *
 * The stored array is the *home* order — it changes only when rows are added, removed, or dragged,
 * never when a box is ticked. So checking a row drops it to the bottom, unchecking puts it back in
 * exactly the slot it came from, and a freshly added row lands at the end of the unchecked block
 * (above the checked ones). Because that's derived from the persisted `order` + `isChecked` rather
 * than remembered in the client, it holds after a reload and on every device.
 *
 * The sort is stable (`Array.prototype.sort` is, per spec), so equal-checked rows keep their order.
 */
export function checklistRows(items: ChecklistItemDto[]): ChecklistRow[] {
  return items
    .map((item, index) => ({ item, index }))
    .sort((a, b) => (a.item.isChecked ? 1 : 0) - (b.item.isChecked ? 1 : 0));
}

/** [checklistRows] for read-only views that don't need the stored index. */
export function checklistForDisplay(items: ChecklistItemDto[]): ChecklistItemDto[] {
  return checklistRows(items).map((r) => r.item);
}

/**
 * The active/completed split used by the editor and the read-only views, **both in home order**.
 *
 * The active section holds every unchecked row: a parent stays there with the children still
 * unticked, as if the group had never left. The completed section holds every checked row, and for
 * a partially completed group it first repeats the unchecked parent's text as a `heading`, so the
 * checked children keep the context of what they belong to. The heading is presentation only: it
 * is not an item, is not interactive, and must not be counted in progress totals.
 *
 * A fully checked group needs no heading: its parent is checked too (the parent/child rule keeps it
 * that way), so the parent itself already appears in the completed section above its children.
 */
export function checklistSections(items: ChecklistItemDto[]): ChecklistSections {
  const active: ChecklistRow[] = [];
  const completed: CompletedRow[] = [];
  const headed = new Set<number>();

  items.forEach((item, index) => {
    if (!item.isChecked) {
      active.push({ item, index });
      return;
    }

    if (isSubItem(item)) {
      const parentIndex = parentIndexOf(items, index);
      if (parentIndex !== -1 && !items[parentIndex].isChecked && !headed.has(parentIndex)) {
        headed.add(parentIndex);
        completed.push({ kind: 'heading', text: items[parentIndex].text, parentIndex });
      }
    }
    completed.push({ kind: 'item', item, index });
  });

  return { active, completed };
}

/** Text as [suggestFromChecked] compares it: no accents, no case, no space around it. */
function fold(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim();
}

/** Whether `typed` starts a word of `text` other than its first: it follows a non-letter, non-digit. */
function startsAWord(text: string, typed: string): boolean {
  for (let at = text.indexOf(typed, 1); at !== -1; at = text.indexOf(typed, at + 1)) {
    if (!/[\p{L}\p{Nd}]/u.test(text[at - 1])) return true;
  }
  return false;
}

/**
 * The ticked row to offer while a new row is being typed. A list used again and again — the weekly
 * shop — holds last time's items ticked at the bottom, and typing one of them again should bring
 * that row back rather than make a second one.
 *
 * `typing` is the stored index of the row being typed. What is offered is a ticked row whose text
 * starts with what has been typed or, failing that, has a word that does ("gar" finds "2 cloves
 * garlic"), ignoring case and accents. Of several, the shortest wins, so "Milk" comes before "Milk
 * chocolate" and typing on reaches the longer one; of equally long ones, the first shown. A ticked
 * row whose text is on the list unticked as well is never offered: taking it would make the very
 * duplicate this is here to avoid.
 *
 * Returns the offered row's stored index, or null when nothing fits or nothing has been typed.
 * `checklistSuggest.json` holds the cases; the Android app's `suggestFromChecked`
 * (`data/Checklist.kt`) is held to the same file, so both offer the same row.
 */
export function suggestFromChecked(items: ChecklistItemDto[], typing: number): number | null {
  const row = items[typing];
  const typed = row && !row.isChecked ? fold(row.text) : '';
  if (!typed) return null;

  const unticked = new Set(items.filter((it, idx) => !it.isChecked && idx !== typing).map((it) => fold(it.text)));
  let best: { index: number; tier: number; length: number } | null = null;
  for (const [index, it] of items.entries()) {
    const text = it.isChecked ? fold(it.text) : '';
    if (!text || unticked.has(text)) continue;
    const tier = text.startsWith(typed) ? 0 : startsAWord(text, typed) ? 1 : -1;
    if (tier === -1) continue;
    if (!best || tier < best.tier || (tier === best.tier && text.length < best.length)) {
      best = { index, tier, length: text.length };
    }
  }
  return best?.index ?? null;
}

/** Rows in one checklist: the server's `NoteLimits.ChecklistItems`, which a save past it is refused for. */
export const MAX_CHECKLIST_ITEMS = 500;

/** One row's text, in UTF-16 code units: the server's `NoteLimits.ChecklistItemText`. */
export const MAX_CHECKLIST_ITEM_TEXT = 2000;

/** `text` cut to at most `max` code units, never ending on half a surrogate pair (`NoteLimits.Cut`). */
function cut(text: string, max: number): string {
  if (text.length <= max) return text;
  const last = text.charCodeAt(max - 1);
  return text.slice(0, last >= 0xd800 && last <= 0xdbff ? max - 1 : max);
}

/**
 * What a paste with line breaks makes of the checklist row it lands in: **each line a row of its
 * own**, as the same text pasted into a document would read. A row's `<input>` holds one line, so
 * the browser would otherwise flatten the lot into that row.
 *
 * `head` is the row's text up to the caret with the pasted text added, `tail` is what followed the
 * caret, and `room` is how many more rows the checklist can take. The first of `rows` replaces the
 * row pasted into and the others go below it; `caret` is where the pasted text ends in the last
 * one. Blank lines make no rows and each row is trimmed, since the note is saved that way anyway.
 *
 * Nothing the server would refuse comes out: lines past `room` are left out (what followed the
 * caret then stays behind the last line kept, never dropped with them), and a row is cut to what a
 * row holds. A refused save is rolled back, so one row too many would cost the whole edit.
 *
 * `checklistPaste.json` holds the cases; the Android app's `splitPastedRow` (`data/Checklist.kt`)
 * is held to the same file, so a paste reads the same on both.
 */
export function splitPastedRow(head: string, tail: string, room: number): { rows: string[]; caret: number } {
  const lines = head.split(/\r\n|\r|\n/);
  const filled = (texts: string[]) => texts.map((text) => text.trim()).filter(Boolean);

  let rows = filled([...lines.slice(0, -1), lines[lines.length - 1] + tail]);
  if (rows.length > room + 1) {
    rows = filled(lines).slice(0, room + 1);
    rows[rows.length - 1] = (rows[rows.length - 1] + tail).trimEnd();
  }
  if (rows.length === 0) rows = [''];
  rows = rows.map((row) => cut(row, MAX_CHECKLIST_ITEM_TEXT));

  return { rows, caret: Math.max(0, rows[rows.length - 1].length - tail.trimEnd().length) };
}

/** A Markdown list item's marker (`-`, `*`, `+`, `1.`, `1)`), then a task box (`[ ]`, `[x]`). */
const LIST_MARKER = /^(?:[-*+]|\d{1,9}[.)])(?:\s+|$)/;
const TASK_BOX = /^\[([ xX])\](?:\s+|$)/;

/**
 * What switching a text note to a checklist makes of its text: **each line an item**, so a note
 * holds one text whichever way it is shown. Blank lines make no items and each item is trimmed.
 * A Markdown list's markers are dropped, since a checklist row is already one, and a Markdown task
 * keeps its tick: `- [x] Bread` is a ticked "Bread". Nothing else is read as Markdown.
 *
 * Returns null when the text doesn't fit a checklist: more lines than `MAX_CHECKLIST_ITEMS`, or a
 * line longer than `MAX_CHECKLIST_ITEM_TEXT`. The note then stays text rather than lose the rest,
 * which a refused save would cost too.
 *
 * `checklistConvert.json` holds the cases; the Android app's `checklistFromText`
 * (`data/Checklist.kt`) is held to the same file, so a note converts the same on both.
 */
export function checklistFromText(text: string): { text: string; isChecked: boolean }[] | null {
  const items: { text: string; isChecked: boolean }[] = [];
  for (const line of text.split(/\r\n|\r|\n/)) {
    let rest = line.trim();
    let isChecked = false;
    const marker = LIST_MARKER.exec(rest);
    if (marker) {
      rest = rest.slice(marker[0].length);
      const box = TASK_BOX.exec(rest);
      if (box) {
        rest = rest.slice(box[0].length);
        isChecked = box[1] !== ' ';
      }
    }
    rest = rest.trim();
    if (!rest) continue;
    if (rest.length > MAX_CHECKLIST_ITEM_TEXT || items.length === MAX_CHECKLIST_ITEMS) return null;
    items.push({ text: rest, isChecked });
  }
  return items;
}

/**
 * What switching a checklist to a text note makes of its items: **each item a line**, in the
 * list's own (stored) order, so text that became a list comes back as it was written. The ticks
 * are not kept: a ticked item is a plain line where it stands. Empty items make no lines.
 *
 * `checklistConvert.json` holds the cases, shared with the Android app's `textFromChecklist`.
 */
export function textFromChecklist(texts: string[]): string {
  return texts
    .map((text) => text.trim())
    .filter(Boolean)
    .join('\n');
}

/**
 * A note's content after the Text ↔ Checklist switch: the text made into items, or the items into
 * text, and the side switched away from emptied — so a note holds one text whichever way it is
 * shown, and nothing is left behind the switch to come back later. A text with nothing in it
 * becomes a checklist with one empty item to type into.
 *
 * Null when the text doesn't fit a checklist (`checklistFromText`); the switch then stays off.
 */
export function switchNoteType(
  type: NoteType,
  body: string,
  items: ChecklistItemDto[],
): { type: NoteType; body: string; items: ChecklistItemDto[] } | null {
  if (type === 'Checklist') {
    return { type: 'Text', body: textFromChecklist(items.map((item) => item.text)), items: [] };
  }
  const converted = checklistFromText(body);
  if (!converted) return null;
  const rows = converted.length > 0 ? converted : [{ text: '', isChecked: false }];
  return { type: 'Checklist', body: '', items: rows.map((row, order) => ({ id: null, ...row, indent: 0, order })) };
}

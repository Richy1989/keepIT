import { describe, expect, it } from 'vitest';
import {
  MAX_CHECKLIST_ITEM_TEXT,
  MAX_CHECKLIST_ITEMS,
  canIndent,
  canOutdent,
  checklistFromText,
  checklistSections,
  childIndicesOf,
  indentOf,
  insertionIndexAfter,
  isSubItem,
  newItemIndentAfter,
  normalizeIndents,
  parentIndexOf,
  setItemChecked,
  setItemIndent,
  splitPastedRow,
  suggestFromChecked,
  switchNoteType,
  textFromChecklist,
} from './checklist';
import type { ChecklistItemDto } from '../../api/types';
import convertCases from './checklistConvert.json';
import cases from './checklistPaste.json';
import suggestCases from './checklistSuggest.json';

/** Checklist rows from `[text, indent, isChecked?]`, in the stored (home) order. */
function rows(...spec: [text: string, indent: number, isChecked?: boolean][]): ChecklistItemDto[] {
  return spec.map(([text, indent, isChecked = false], order) => ({
    id: `i${order}`,
    text,
    indent,
    isChecked,
    order,
  }));
}

/**
 * What pasting several lines into a checklist row makes of them. The cases live in
 * `checklistPaste.json` because the Android app's ChecklistPasteTest runs the same ones against
 * its own `splitPastedRow`: a list pasted on the phone must come out as it does here.
 */
describe('splitPastedRow', () => {
  it.each(cases)('$name', ({ head, tail, room, rows, caret }) => {
    expect(splitPastedRow(head, tail, room)).toEqual({ rows, caret });
  });

  it('cuts a row to what the server accepts, without splitting an emoji', () => {
    const long = 'b'.repeat(MAX_CHECKLIST_ITEM_TEXT - 1) + '😀 and more';
    const { rows, caret } = splitPastedRow(`a\n${long}`, '', 499);

    expect(rows).toEqual(['a', 'b'.repeat(MAX_CHECKLIST_ITEM_TEXT - 1)]);
    expect(caret).toBe(MAX_CHECKLIST_ITEM_TEXT - 1);
  });

  it('never makes more rows than there is room for', () => {
    const lines = Array.from({ length: 600 }, (_, i) => `line ${i}`).join('\n');

    expect(splitPastedRow(lines, '', 10).rows).toHaveLength(11);
    expect(splitPastedRow(lines, 'kept', 10).rows.at(-1)).toBe('line 10kept');
  });
});

/**
 * Which ticked row is offered while a new row is typed. The cases live in `checklistSuggest.json`
 * for the same reason as the paste's: the Android app's ChecklistSuggestTest runs them too.
 */
describe('suggestFromChecked', () => {
  it.each(suggestCases)('$name', ({ items, typing, offer }) => {
    const dtos = items.map((it, order) => ({ id: null, ...it, indent: 0, order }));
    expect(suggestFromChecked(dtos, typing)).toBe(offer);
  });
});

/**
 * The single-level indentation model: one level only, the first row never indented, and a row
 * belongs to the nearest top-level row above it. These are the pure moves the editor will call,
 * kept here so the same rules are exercised without React.
 */
describe('checklist indentation', () => {
  it('never indents the first row, and always outdents a sub-item', () => {
    const list = rows(['A', 0], ['B', 0]);

    expect(canIndent(list, 0)).toBe(false);
    expect(setItemIndent(list, 0, 1)).toBe(list);

    const sub = rows(['A', 0], ['B', 1]);
    expect(canOutdent(sub, 1)).toBe(true);
    expect(setItemIndent(sub, 1, 0).map(indentOf)).toEqual([0, 0]);
  });

  it('leaves a parent and an orphaned row out of reach of indentation', () => {
    const parent = rows(['A', 0], ['B', 1], ['C', 0]);
    expect(canIndent(parent, 0)).toBe(false);
    expect(setItemIndent(parent, 0, 1)).toBe(parent);

    // No top-level row precedes B, so indenting it would make an orphan.
    const orphan = rows(['A', 1], ['B', 0]);
    expect(canIndent(orphan, 1)).toBe(false);
  });

  it('indents a whole parent block, flattening its children under the same parent', () => {
    const list = rows(['A', 0], ['B', 0], ['C', 1], ['D', 1], ['E', 0]);

    expect(canIndent(list, 1)).toBe(true);
    const next = setItemIndent(list, 1, 1);
    expect(next.map(indentOf)).toEqual([0, 1, 1, 1, 0]);
    // B and its former children are now siblings under A, not a second nesting level.
    expect(parentIndexOf(next, 1)).toBe(0);
    expect(parentIndexOf(next, 2)).toBe(0);
    expect(parentIndexOf(next, 3)).toBe(0);
  });

  it('collapses deeper indents and brings a first indented row back to the top level', () => {
    expect(normalizeIndents(rows(['A', 0], ['B', 2], ['C', 0], ['D', 7])).map(indentOf)).toEqual([0, 1, 0, 1]);
    expect(normalizeIndents(rows(['A', 1])).map(indentOf)).toEqual([0]);
    expect(normalizeIndents(rows(['A', 1], ['B', 1])).map(indentOf)).toEqual([0, 1]);
  });

  it('finds the nearest top-level parent and its contiguous children', () => {
    const list = rows(['A', 0], ['B', 1], ['C', 1], ['D', 0], ['E', 1]);

    expect(parentIndexOf(list, 0)).toBe(-1);
    expect(parentIndexOf(list, 1)).toBe(0);
    expect(parentIndexOf(list, 2)).toBe(0);
    expect(parentIndexOf(list, 4)).toBe(3);
    expect(childIndicesOf(list, 0)).toEqual([1, 2]);
    expect(childIndicesOf(list, 3)).toEqual([4]);
    // A sub-item is never a parent.
    expect(childIndicesOf(list, 1)).toEqual([]);
  });

  it('reads older rows without an indent as top-level', () => {
    const legacy = { id: 'old', text: 'Legacy', isChecked: false, order: 0 } as ChecklistItemDto;

    expect(indentOf(legacy)).toBe(0);
    expect(isSubItem(legacy)).toBe(false);
  });

  it('indents a free row, returning new arrays without changing the input', () => {
    const list = rows(['A', 0], ['B', 0], ['C', 0]);

    expect(canIndent(list, 1)).toBe(true);
    expect(setItemIndent(list, 1, 2)).toBe(list);

    const indented = setItemIndent(list, 1, 1);
    expect(indented.map(indentOf)).toEqual([0, 1, 0]);
    expect(indented).not.toBe(list);
    expect(indented[1]).not.toBe(list[1]);
    expect(list.map(indentOf)).toEqual([0, 0, 0]);
  });

  it('inserts after a whole group, and gives the new row the right depth', () => {
    const list = rows(['A', 0], ['B', 1], ['C', 1], ['D', 0]);

    expect(insertionIndexAfter(list, 0)).toBe(3);
    expect(newItemIndentAfter(list, 0)).toBe(0);
    expect(insertionIndexAfter(list, 1)).toBe(2);
    expect(newItemIndentAfter(list, 1)).toBe(1);
    expect(insertionIndexAfter(list, 3)).toBe(4);
    expect(newItemIndentAfter(list, 3)).toBe(0);
  });
});

/**
 * The parent/child checked rule: a parent carries its whole group, while a child only changes
 * itself and lets its parent be derived from all of its children.
 */
describe('setItemChecked', () => {
  it('checks a parent together with all of its children', () => {
    const list = rows(['A', 0], ['B', 1], ['C', 1], ['D', 0]);

    const next = setItemChecked(list, 0, true);
    expect(next.map((item) => item.isChecked)).toEqual([true, true, true, false]);
    expect(list.map((item) => item.isChecked)).toEqual([false, false, false, false]);
  });

  it('unchecks a parent together with all of its children', () => {
    const list = rows(['A', 0, true], ['B', 1, true], ['C', 1, true]);

    expect(setItemChecked(list, 0, false).map((item) => item.isChecked)).toEqual([false, false, false]);
  });

  it('recomputes the parent when a child is checked or unchecked', () => {
    const partial = rows(['A', 0], ['B', 1, true], ['C', 1]);

    const all = setItemChecked(partial, 2, true);
    expect(all.map((item) => item.isChecked)).toEqual([true, true, true]);

    const reopened = setItemChecked(all, 2, false);
    expect(reopened.map((item) => item.isChecked)).toEqual([false, true, false]);
  });
});

/**
 * The active/completed split. A partial group intentionally appears twice, with a greyed parent
 * heading in the completed half; a full one appears once because its parent is checked too.
 */
describe('checklistSections', () => {
  it('shows a partial group twice, with the heading before its checked children', () => {
    const list = rows(['A', 0], ['B', 1, true], ['C', 1], ['D', 1]);

    expect(checklistSections(list)).toEqual({
      active: [
        { item: list[0], index: 0 },
        { item: list[2], index: 2 },
        { item: list[3], index: 3 },
      ],
      completed: [
        { kind: 'heading', text: 'A', parentIndex: 0 },
        { kind: 'item', item: list[1], index: 1 },
      ],
    });
  });

  it('adds no heading for a fully checked group', () => {
    const list = rows(['A', 0, true], ['B', 1, true], ['C', 1, true]);

    const sections = checklistSections(list);
    expect(sections.active).toEqual([]);
    expect(sections.completed).toEqual([
      { kind: 'item', item: list[0], index: 0 },
      { kind: 'item', item: list[1], index: 1 },
      { kind: 'item', item: list[2], index: 2 },
    ]);
  });

  it('gives a checked child of an unchecked parent a heading, once per parent', () => {
    const list = rows(['A', 0], ['B', 1, true], ['C', 1, true]);

    expect(checklistSections(list).completed).toEqual([
      { kind: 'heading', text: 'A', parentIndex: 0 },
      { kind: 'item', item: list[1], index: 1 },
      { kind: 'item', item: list[2], index: 2 },
    ]);
  });

  it('keeps home order in both sections, and counts only real items', () => {
    const list = rows(['A', 0], ['B', 1, true], ['C', 0], ['D', 1], ['E', 1, true], ['F', 0, true]);

    const sections = checklistSections(list);
    expect(sections.active.map((row) => row.index)).toEqual([0, 2, 3]);
    expect(sections.completed).toEqual([
      { kind: 'heading', text: 'A', parentIndex: 0 },
      { kind: 'item', item: list[1], index: 1 },
      { kind: 'heading', text: 'C', parentIndex: 2 },
      { kind: 'item', item: list[4], index: 4 },
      { kind: 'item', item: list[5], index: 5 },
    ]);
    expect(sections.completed.filter((row) => row.kind === 'item')).toHaveLength(
      list.filter((item) => item.isChecked).length,
    );
  });
});

/**
 * What switching a note between text and checklist makes of it. The cases live in
 * `checklistConvert.json` for the same reason as the paste's: the Android app's
 * ChecklistConvertTest runs them too, so a note converts the same on both.
 */
describe('checklistFromText', () => {
  it.each(convertCases.toChecklist)('$name', ({ text, items }) => {
    expect(checklistFromText(text)).toEqual(items.map(([text, isChecked]) => ({ text, isChecked })));
  });

  it('turns down a text with more lines than a checklist holds', () => {
    const lines = (count: number) => Array.from({ length: count }, (_, i) => `line ${i}`).join('\n');

    expect(checklistFromText(lines(MAX_CHECKLIST_ITEMS))).toHaveLength(MAX_CHECKLIST_ITEMS);
    expect(checklistFromText(lines(MAX_CHECKLIST_ITEMS + 1))).toBeNull();
    // Blank lines make no items, so they don't count against the limit.
    expect(checklistFromText(lines(MAX_CHECKLIST_ITEMS) + '\n\n\n')).toHaveLength(MAX_CHECKLIST_ITEMS);
  });

  it('turns down a text with a line longer than an item holds', () => {
    expect(checklistFromText('- ' + 'a'.repeat(MAX_CHECKLIST_ITEM_TEXT))).toHaveLength(1);
    expect(checklistFromText('a'.repeat(MAX_CHECKLIST_ITEM_TEXT + 1))).toBeNull();
  });
});

describe('textFromChecklist', () => {
  it.each(convertCases.toText)('$name', ({ items, text }) => {
    expect(textFromChecklist(items)).toBe(text);
  });

  it('gives back the text a list was made from', () => {
    const text = 'Milk\nBread\nEggs';
    const items = checklistFromText(text) ?? [];

    expect(textFromChecklist(items.map((item) => item.text))).toBe(text);
  });
});

describe('switchNoteType', () => {
  const item = (text: string, isChecked: boolean, order: number) => ({
    id: 'i' + order,
    text,
    isChecked,
    indent: 0,
    order,
  });

  it('makes the text into items and empties the text', () => {
    expect(switchNoteType('Text', '- [x] Milk\nBread', [item('old', false, 0)])).toEqual({
      type: 'Checklist',
      body: '',
      items: [
        { id: null, text: 'Milk', isChecked: true, indent: 0, order: 0 },
        { id: null, text: 'Bread', isChecked: false, indent: 0, order: 1 },
      ],
    });
  });

  it('gives an empty text one empty item to type into', () => {
    expect(switchNoteType('Text', '  ', [])?.items).toEqual([
      { id: null, text: '', isChecked: false, indent: 0, order: 0 },
    ]);
  });

  it('makes the items into text, ticks dropped, and empties the list', () => {
    expect(switchNoteType('Checklist', 'old', [item('Milk', true, 0), item('Bread', false, 1)])).toEqual({
      type: 'Text',
      body: 'Milk\nBread',
      items: [],
    });
  });

  it('stays off for a text that does not fit a checklist', () => {
    expect(switchNoteType('Text', 'a'.repeat(MAX_CHECKLIST_ITEM_TEXT + 1), [])).toBeNull();
  });
});

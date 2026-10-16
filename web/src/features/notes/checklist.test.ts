import { describe, expect, it } from 'vitest';
import { MAX_CHECKLIST_ITEM_TEXT, splitPastedRow, suggestFromChecked } from './checklist';
import cases from './checklistPaste.json';
import suggestCases from './checklistSuggest.json';

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
    const dtos = items.map((it, order) => ({ id: null, ...it, order }));
    expect(suggestFromChecked(dtos, typing)).toBe(offer);
  });
});

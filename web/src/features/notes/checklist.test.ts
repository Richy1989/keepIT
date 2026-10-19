import { describe, expect, it } from 'vitest';
import {
  MAX_CHECKLIST_ITEM_TEXT,
  MAX_CHECKLIST_ITEMS,
  checklistFromText,
  splitPastedRow,
  suggestFromChecked,
  switchNoteType,
  textFromChecklist,
} from './checklist';
import convertCases from './checklistConvert.json';
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
  const item = (text: string, isChecked: boolean, order: number) => ({ id: 'i' + order, text, isChecked, order });

  it('makes the text into items and empties the text', () => {
    expect(switchNoteType('Text', '- [x] Milk\nBread', [item('old', false, 0)])).toEqual({
      type: 'Checklist',
      body: '',
      items: [
        { id: null, text: 'Milk', isChecked: true, order: 0 },
        { id: null, text: 'Bread', isChecked: false, order: 1 },
      ],
    });
  });

  it('gives an empty text one empty item to type into', () => {
    expect(switchNoteType('Text', '  ', [])?.items).toEqual([{ id: null, text: '', isChecked: false, order: 0 }]);
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

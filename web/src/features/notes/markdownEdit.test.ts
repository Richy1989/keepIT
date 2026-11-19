import { describe, expect, it } from 'vitest';
import { applyMarkdown, continueList, insideCodeFence, type MarkdownAction } from './markdownEdit';
import cases from './markdownEdit.json';

/**
 * The formatting toolbar and Enter in a note body. The cases are `markdownEdit.json`, which the
 * Android app's MarkdownEditTest runs too, so the two clients edit a note alike. « and » mark the
 * selection, so each case reads as what the user sees before and after.
 */

function field(marked: string): [string, number, number] {
  const start = marked.indexOf('«');
  const end = marked.indexOf('»') - 1;
  return [marked.replace('«', '').replace('»', ''), start, end];
}

function show(value: string, start: number, end: number): string {
  return `${value.slice(0, start)}«${value.slice(start, end)}»${value.slice(end)}`;
}

describe('markdownEdit.json: the toolbar', () => {
  for (const c of cases.apply) {
    it(`${c.action} on ${JSON.stringify(c.value)}`, () => {
      const edit = applyMarkdown(...field(c.value), c.action as MarkdownAction);
      expect(show(edit.value, edit.selectionStart, edit.selectionEnd)).toBe(c.result);
    });
  }
});

describe('markdownEdit.json: Enter', () => {
  for (const c of cases.enter) {
    it(JSON.stringify(c.value), () => {
      const [value, start, end] = field(c.value);
      // null means the browser inserts an ordinary newline.
      const edit = continueList(value, start, end) ?? {
        value: `${value.slice(0, start)}\n${value.slice(end)}`,
        selectionStart: start + 1,
        selectionEnd: start + 1,
      };
      expect(show(edit.value, edit.selectionStart, edit.selectionEnd)).toBe(c.result);
    });
  }
});

describe('Enter', () => {
  it('leaves a selection to the browser, which replaces it with the newline', () => {
    expect(continueList('- abc', 3, 5)).toBeNull();
  });
});

describe('insideCodeFence', () => {
  it('counts only the lines above, so the fence line itself is outside', () => {
    expect(insideCodeFence('```\n', 0)).toBe(false);
    expect(insideCodeFence('```\n', 4)).toBe(true);
    expect(insideCodeFence('```\nx\n```\n', 10)).toBe(false);
  });
});

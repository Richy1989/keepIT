import { describe, expect, it } from 'vitest';
import { applyMarkdown, continueList, type MarkdownAction } from './markdownEdit';

/**
 * The formatting toolbar and Enter-in-a-list — the same cases as the Android app's
 * MarkdownEditTest, so the two clients edit a note alike. Values are written with `|` for a caret
 * and `[...]` for a selection, so each case reads as what the user sees before and after.
 */

function field(marked: string): [string, number, number] {
  const caret = marked.indexOf('|');
  if (caret >= 0) return [marked.slice(0, caret) + marked.slice(caret + 1), caret, caret];
  const open = marked.indexOf('[');
  const close = marked.lastIndexOf(']');
  const text = marked.slice(0, open) + marked.slice(open + 1, close) + marked.slice(close + 1);
  return [text, open, close - 1];
}

function show(value: string, start: number, end: number): string {
  return start === end
    ? `${value.slice(0, start)}|${value.slice(start)}`
    : `${value.slice(0, start)}[${value.slice(start, end)}]${value.slice(end)}`;
}

function apply(marked: string, action: MarkdownAction): string {
  const edit = applyMarkdown(...field(marked), action);
  return show(edit.value, edit.selectionStart, edit.selectionEnd);
}

function enter(marked: string): string {
  const [value, start, end] = field(marked);
  // null means the browser inserts an ordinary newline.
  const edit = continueList(value, start, end) ?? {
    value: `${value.slice(0, start)}\n${value.slice(end)}`,
    selectionStart: start + 1,
    selectionEnd: start + 1,
  };
  return show(edit.value, edit.selectionStart, edit.selectionEnd);
}

describe('inline styles', () => {
  it('italic on a bold word adds italic instead of replacing bold', () => {
    expect(apply('**[bold]**', 'italic')).toBe('***[bold]***');
    expect(apply('[**bold**]', 'italic')).toBe('*[**bold**]*');
  });

  it('bold and italic come off a bold-italic word one at a time', () => {
    expect(apply('***[x]***', 'bold')).toBe('*[x]*');
    expect(apply('***[x]***', 'italic')).toBe('**[x]**');
  });

  it('styles toggle off, whichever emphasis character was used', () => {
    expect(apply('**[bold]**', 'bold')).toBe('[bold]');
    expect(apply('[**bold**]', 'bold')).toBe('[bold]');
    expect(apply('_[it]_', 'italic')).toBe('[it]');
    expect(apply('__[b]__', 'bold')).toBe('[b]');
    expect(apply('~~[x]~~', 'strike')).toBe('[x]');
    expect(apply('`[x]`', 'code')).toBe('[x]');
  });

  it('a caret gets an empty pair to type into, and a second tap takes it away', () => {
    expect(apply('a | b', 'bold')).toBe('a **|** b');
    expect(apply('a **|** b', 'bold')).toBe('a | b');
  });

  it('spaces caught in a selection stay outside the markers', () => {
    // "**bold **" is literal asterisks in CommonMark.
    expect(apply('[bold ]then', 'bold')).toBe('**[bold]** then');
  });

  it('a selection over several lines styles each line, around its list markers', () => {
    expect(apply('[a\nb]', 'bold')).toBe('[**a**\n**b**]');
    expect(apply('[- a\n- b]', 'bold')).toBe('[- **a**\n- **b**]');
    expect(apply('[- **a**\n- **b**]', 'bold')).toBe('[- a\n- b]');
  });
});

describe('line prefixes', () => {
  it('a line button leaves a caret as a caret', () => {
    // It used to select the whole line, so the next keystroke replaced it.
    expect(apply('Buy milk|', 'bullet')).toBe('- Buy milk|');
    expect(apply('- Buy milk|', 'bullet')).toBe('Buy milk|');
    expect(apply('Ti|tle', 'heading')).toBe('# Ti|tle');
  });

  it('switching list type replaces the marker instead of stacking it', () => {
    expect(apply('[- a\n- b]', 'ordered')).toBe('[1. a\n2. b]');
    expect(apply('[1. a\n2. b]', 'bullet')).toBe('[- a\n- b]');
    expect(apply('- a|', 'heading')).toBe('# a|');
  });

  it('bullet on a task line removes the task, not just its dash', () => {
    expect(apply('- [ ] task|', 'bullet')).toBe('task|');
  });

  it('numbering skips blank lines and carries on from the list above', () => {
    expect(apply('[a\n\nc]', 'ordered')).toBe('[1. a\n\n2. c]');
    expect(apply('1. a\nb|', 'ordered')).toBe('1. a\n2. b|');
  });

  it('works on a first line that is empty', () => {
    // lastIndexOf('\n', -1) is lastIndexOf('\n', 0): the old code put line 0 at index 1 here.
    expect(apply('|\nb', 'bullet')).toBe('|\nb');
    expect(apply('\n|b', 'bullet')).toBe('\n- |b');
  });

  it('a link wraps the selection and selects the address to type over', () => {
    const edit = applyMarkdown('site', 0, 4, 'link');
    expect(edit.value).toBe('[site](url)');
    expect(edit.value.slice(edit.selectionStart, edit.selectionEnd)).toBe('url');
  });
});

describe('Enter', () => {
  it('continues a list with the next marker', () => {
    expect(enter('- milk|')).toBe('- milk\n- |');
    expect(enter('* milk|')).toBe('* milk\n* |');
    expect(enter('3. a|')).toBe('3. a\n4. |');
    expect(enter('9) a|')).toBe('9) a\n10) |');
    expect(enter('  - nested|')).toBe('  - nested\n  - |');
  });

  it('starts a new task unticked', () => {
    expect(enter('- [x] done|')).toBe('- [x] done\n- [ ] |');
  });

  it('splits an item in two when pressed in its middle', () => {
    expect(enter('- buy| milk')).toBe('- buy\n- |milk');
  });

  it('ends the list on an empty item', () => {
    expect(enter('- a\n- |')).toBe('- a\n|');
    expect(enter('1. a\n2. |')).toBe('1. a\n|');
  });

  it('is an ordinary newline anywhere else', () => {
    expect(enter('plain|')).toBe('plain\n|');
    expect(enter('|- a')).toBe('\n|- a');
    expect(continueList('- abc', 3, 5)).toBeNull();
  });
});

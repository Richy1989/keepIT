/**
 * Selection-based Markdown editing for the note body textarea: each action rewrites the value
 * around the current selection and returns where the selection should land, so the toolbar stays
 * a dumb list of buttons. Inline actions toggle (applying bold to bold text removes it); line
 * actions toggle their prefix on every selected line. {@link continueList} is Enter inside a list.
 *
 * The Android app's `ui/markdown/MarkdownEdit.kt` implements the same rules; both are unit-tested
 * against the same cases, so a note edited on either client gets the same Markdown.
 */

export type MarkdownAction =
  | 'bold'
  | 'italic'
  | 'strike'
  | 'code'
  | 'heading'
  | 'bullet'
  | 'ordered'
  | 'link';

export interface MarkdownEdit {
  value: string;
  selectionStart: number;
  selectionEnd: number;
}

const INLINE_MARKERS: Partial<Record<MarkdownAction, string>> = {
  bold: '**',
  italic: '*',
  strike: '~~',
  code: '`',
};

export function applyMarkdown(
  value: string,
  start: number,
  end: number,
  action: MarkdownAction,
): MarkdownEdit {
  const inline = INLINE_MARKERS[action];
  if (inline) return toggleInline(value, start, end, inline);
  if (action === 'link') return insertLink(value, start, end);
  return toggleLinePrefix(value, start, end, action as 'heading' | 'bullet' | 'ordered');
}

/** Where the line holding [pos] starts. (`lastIndexOf` treats a negative index as 0, so not that.) */
function lineStartOf(text: string, pos: number): number {
  return pos === 0 ? 0 : text.lastIndexOf('\n', pos - 1) + 1;
}

function lineEndOf(text: string, pos: number): number {
  const i = text.indexOf('\n', pos);
  return i === -1 ? text.length : i;
}

// ---- inline: bold, italic, strike, code ----

function toggleInline(text: string, start: number, end: number, marker: string): MarkdownEdit {
  // Emphasis cannot cross a line break in the Android renderer, and a selection over several
  // lines reads best styled line by line anyway: "**a\nb**" became "**a**\n**b**".
  if (text.slice(start, end).includes('\n')) return toggleInlinePerLine(text, start, end, marker);

  // Emphasis cannot start or end on a space: "**bold **" is literal asterisks in CommonMark. A
  // drag-selection that caught a space wraps the word and leaves the space outside.
  let s = start;
  let e = end;
  while (s < e && /\s/.test(text[s])) s++;
  while (e > s && /\s/.test(text[e - 1])) e--;
  if (s === e && start !== end) e = s = start;

  const range = unwrapRange(text, s, e, marker);
  if (range) {
    const [from, to, width] = range;
    const inner = text.slice(from + width, to - width);
    // Keep the same characters selected, now without their markers.
    const selectionStart = Math.max(s - width, from);
    return {
      value: text.slice(0, from) + inner + text.slice(to),
      selectionStart,
      selectionEnd: selectionStart + Math.min(e - s, inner.length),
    };
  }
  // Wrap; with no selection the cursor lands between the markers, ready to type.
  const m = marker.length;
  return {
    value: text.slice(0, s) + marker + text.slice(s, e) + marker + text.slice(e),
    selectionStart: s + m,
    selectionEnd: e + m,
  };
}

/**
 * The span to strip when s..e is already styled with `marker`: the markers either selected along
 * with the text or sitting just outside it. Returns [from, to, markerWidth], or null to wrap.
 *
 * `*` and `_` are counted as runs, because one character serves two styles: `***x***` is bold and
 * italic, so italic must see an odd run and bold a run of at least two. Matching the marker text
 * alone read the inner `*` of `**bold**` as italic and turned bold into italic.
 */
function unwrapRange(
  text: string,
  s: number,
  e: number,
  marker: string,
): [number, number, number] | null {
  const m = marker.length;
  if (marker[0] !== '*' && marker[0] !== '_') {
    const selected = text.slice(s, e);
    if (selected.length >= 2 * m && selected.startsWith(marker) && selected.endsWith(marker)) {
      return [s, e, m];
    }
    if (s >= m && text.slice(s - m, s) === marker && text.slice(e, e + m) === marker) {
      return [s - m, e + m, m];
    }
    return null;
  }
  // Emphasis: accept either character, so `_x_` and `__x__` toggle off as well.
  for (const c of ['*', '_']) {
    const leadIn = runLength(text, s, e, c, true);
    const trailIn = runLength(text, s, e, c, false);
    if (leadIn < e - s && fits(leadIn, trailIn, m)) return [s, e, m];
    const before = runLength(text, 0, s, c, false);
    const after = runLength(text, e, text.length, c, true);
    if (fits(before, after, m)) return [s - m, e + m, m];
  }
  return null;
}

/** Italic (width 1) needs an odd run on both sides; bold (width 2) needs at least two. */
function fits(lead: number, trail: number, width: number): boolean {
  return width === 1 ? lead % 2 === 1 && trail % 2 === 1 : lead >= 2 && trail >= 2;
}

/** How many `c` run from `from` forward, or back from `to`, within from..to (at most 3). */
function runLength(text: string, from: number, to: number, c: string, forward: boolean): number {
  let n = 0;
  while (n < 3 && from + n < to) {
    if (text[forward ? from + n : to - 1 - n] !== c) break;
    n++;
  }
  return n;
}

function toggleInlinePerLine(text: string, start: number, end: number, marker: string): MarkdownEdit {
  const lineStart = lineStartOf(text, start);
  const blockEnd = lineEndOf(text, end);
  // Each line splits into its prefix (indent, list marker, heading hashes, quote), the text to
  // style, and trailing space: wrapping "- a" whole would give "**- a**" and break the list.
  const split = (line: string): [string, string, string] => {
    let p = prefixLength(line);
    while (p < line.length && /\s/.test(line[p])) p++;
    const rest = line.slice(p);
    const core = rest.trimEnd();
    return [line.slice(0, p), core, rest.slice(core.length)];
  };
  const lines = text.slice(lineStart, blockEnd).split('\n');
  // One decision for the whole selection: if every line with text is already styled, unstyle.
  const styled = lines
    .map(split)
    .filter(([, core]) => core.length > 0)
    .every(([, core]) => unwrapRange(core, 0, core.length, marker) !== null);
  const block = lines
    .map((line) => {
      const [head, core, trail] = split(line);
      if (core.length === 0) return line;
      const range = unwrapRange(core, 0, core.length, marker);
      if (styled && range) return head + core.slice(range[2], core.length - range[2]) + trail;
      if (styled || range) return line;
      return head + marker + core + marker + trail;
    })
    .join('\n');
  return {
    value: text.slice(0, lineStart) + block + text.slice(blockEnd),
    selectionStart: lineStart,
    selectionEnd: lineStart + block.length,
  };
}

// ---- link ----

function insertLink(value: string, start: number, end: number): MarkdownEdit {
  const selected = value.slice(start, end);
  const text = selected || 'text';
  const next = `${value.slice(0, start)}[${text}](url)${value.slice(end)}`;
  // Select the url placeholder so typing replaces it; with placeholder text, select that instead.
  const urlStart = start + text.length + 3; // "[" + text + "]("
  return selected
    ? { value: next, selectionStart: urlStart, selectionEnd: urlStart + 3 }
    : { value: next, selectionStart: start + 1, selectionEnd: start + 1 + text.length };
}

// ---- line prefixes: heading, bullet, numbered ----

const HEADING_PREFIX = /^#{1,6}\s+/;
/** A list item's marker: indent, bullet or number, spacing, and a task box if there is one. */
const LIST_ITEM = /^(\s*)(?:([-*+])|(\d{1,9})([.)]))(\s+)(\[[ xX]\]\s+)?/;
const QUOTE_PREFIX = /^\s*>\s?/;

function prefixLength(line: string): number {
  return (
    HEADING_PREFIX.exec(line)?.[0].length ??
    LIST_ITEM.exec(line)?.[0].length ??
    QUOTE_PREFIX.exec(line)?.[0].length ??
    0
  );
}

/** Strips a heading or list prefix — the list's indent included, so toggling off is clean. */
function removePrefix(line: string): string {
  const heading = HEADING_PREFIX.exec(line);
  if (heading) return line.slice(heading[0].length);
  const item = LIST_ITEM.exec(line);
  if (item) return line.slice(item[0].length);
  return line;
}

function toggleLinePrefix(
  value: string,
  start: number,
  end: number,
  action: 'heading' | 'bullet' | 'ordered',
): MarkdownEdit {
  const lineStart = lineStartOf(value, start);
  const blockEnd = lineEndOf(value, end);
  const lines = value.slice(lineStart, blockEnd).split('\n');

  const hasOwnPrefix = (line: string): boolean => {
    if (action === 'heading') return HEADING_PREFIX.test(line);
    const item = LIST_ITEM.exec(line);
    return action === 'bullet' ? item?.[2] !== undefined : item?.[3] !== undefined;
  };

  // Numbering continues a numbered list directly above the selection rather than restarting.
  let number = 1;
  if (action === 'ordered' && lineStart > 0) {
    const above = value.slice(lineStartOf(value, lineStart - 1), lineStart - 1);
    const n = LIST_ITEM.exec(above)?.[3];
    if (n !== undefined) number = Number(n) + 1;
  }

  const allPrefixed = lines.every((l) => l.trim().length === 0 || hasOwnPrefix(l));
  const block = lines
    .map((line) => {
      if (line.trim().length === 0) return line;
      if (allPrefixed) return removePrefix(line);
      if (hasOwnPrefix(line)) {
        if (action === 'ordered') number++;
        return line;
      }
      // Another kind of prefix is replaced, not stacked: bullet → numbered is "1. a", not "1. - a".
      const indent = LIST_ITEM.exec(line)?.[1] ?? '';
      const body = removePrefix(line).trimStart();
      if (action === 'heading') return `# ${body}`;
      if (action === 'bullet') return `${indent}- ${body}`;
      return `${indent}${number++}. ${body}`;
    })
    .join('\n');

  const next = value.slice(0, lineStart) + block + value.slice(blockEnd);
  // A caret stays a caret. Selecting the rewritten line meant the next keystroke replaced it.
  if (start === end) {
    const newFirst = block.split('\n')[0];
    const newPrefix = prefixLength(newFirst);
    const caret = Math.min(
      Math.max(start + newPrefix - prefixLength(lines[0]), lineStart + newPrefix),
      lineStart + newFirst.length,
    );
    return { value: next, selectionStart: caret, selectionEnd: caret };
  }
  return { value: next, selectionStart: lineStart, selectionEnd: lineStart + block.length };
}

// ---- Enter in a list ----

/**
 * What Enter does at the caret inside a list, as GitHub and most Markdown editors do: after
 * "- milk" the new line starts "- ", after "3. milk" it starts "4. ", after a task "- [ ] ". Enter on
 * an item with nothing after its marker ends the list instead, leaving an empty line. Returns null
 * where Enter is an ordinary newline, so the textarea handles it natively.
 */
export function continueList(value: string, start: number, end: number): MarkdownEdit | null {
  if (start !== end) return null;
  const lineStart = lineStartOf(value, start);
  const lineEnd = lineEndOf(value, start);
  const line = value.slice(lineStart, lineEnd);
  const match = LIST_ITEM.exec(line);
  // Enter typed within the indent or the marker: an ordinary newline.
  if (!match || start < lineStart + match[0].length) return null;

  if (line.slice(match[0].length).trim().length === 0) {
    // An empty item: Enter ends the list. The marker goes and no newline is added.
    return {
      value: value.slice(0, lineStart) + value.slice(lineEnd),
      selectionStart: lineStart,
      selectionEnd: lineStart,
    };
  }

  const [, indent, bullet, num, delimiter, , task] = match;
  const marker = bullet ?? `${Number(num) + 1}${delimiter}`;
  const prefix = `${indent}${marker} ${task ? '[ ] ' : ''}`;
  // Splitting "- buy| milk" moves " milk" down: its leading space would double the marker's.
  const moved = value.slice(start).replace(/^ +/, '');
  const caret = start + 1 + prefix.length;
  return {
    value: `${value.slice(0, start)}\n${prefix}${moved}`,
    selectionStart: caret,
    selectionEnd: caret,
  };
}

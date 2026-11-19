/**
 * Selection-based Markdown editing for the note body textarea: each action rewrites the value
 * around the current selection and returns where the selection should land, so the toolbar stays
 * a dumb list of buttons. Inline actions toggle (applying bold to bold text removes it); line
 * actions toggle their prefix on every selected line. {@link continueList} is Enter inside a list.
 *
 * The Android app's `ui/markdown/MarkdownEdit.kt` implements the same rules; both are held to the
 * same cases, `markdownEdit.json`, so a note edited on either client gets the same Markdown.
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
  // lines reads best styled line by line anyway: "**a\nb**" became "**a**\n**b**". A selection
  // that only reaches the start of the next line styles one line.
  const segments = start === end ? [] : lineSegments(text, start, end);
  if (segments.length > 1) return toggleInlinePerLine(text, start, end, segments, marker);

  // The selection without its spaces or the line's list marker (see lineSegments). With nothing
  // left, the markers go in at the selection's start: an empty pair to type into.
  const s = segments[0]?.from ?? start;
  const e = segments[0]?.to ?? start;

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

/**
 * The part of a selection that inline formatting applies to, line by line: each line's share of
 * the selection without the line's prefix (indent, list marker, task box, heading hashes, quote)
 * and without spaces at either end. "**- a**" would break the list, and "**a **" is literal
 * asterisks. Lines left with nothing are skipped. Positions are into [text]. The highlighter
 * (highlight.ts) uses the same split.
 */
export function lineSegments(text: string, start: number, end: number): { from: number; to: number }[] {
  const out: { from: number; to: number }[] = [];
  for (let lineStart = lineStartOf(text, start); lineStart <= end; ) {
    const lineEnd = lineEndOf(text, lineStart);
    let from = Math.max(start, lineStart + prefixLength(text.slice(lineStart, lineEnd)));
    let to = Math.min(end, lineEnd);
    while (from < to && /\s/.test(text[from])) from++;
    while (to > from && /\s/.test(text[to - 1])) to--;
    if (from < to) out.push({ from, to });
    if (lineEnd >= text.length) break;
    lineStart = lineEnd + 1;
  }
  return out;
}

function toggleInlinePerLine(
  text: string,
  start: number,
  end: number,
  segments: { from: number; to: number }[],
  marker: string,
): MarkdownEdit {
  // One decision for the whole selection: if every part is already styled, unstyle.
  const ranges = segments.map(({ from, to }) => unwrapRange(text, from, to, marker));
  const styled = ranges.every((r) => r !== null);
  // Each edit replaces text[a..b); they don't overlap, and they run in order.
  const edits: { a: number; b: number; with: string }[] = [];
  segments.forEach(({ from, to }, i) => {
    const range = ranges[i];
    if (styled && range) {
      const [a, b, width] = range;
      edits.push({ a, b, with: text.slice(a + width, b - width) });
    } else if (!styled && !range) {
      edits.push({ a: from, b: to, with: marker + text.slice(from, to) + marker });
    }
  });
  let value = text;
  for (const { a, b, with: w } of [...edits].reverse()) value = value.slice(0, a) + w + value.slice(b);
  if (edits.length === 0) return { value, selectionStart: start, selectionEnd: end };
  // The selection covers what was styled, its new markers included, so a second tap finds them
  // inside it and takes them away again: "one [two\nthree] four" ⇄ "one [**two**\n**three**] four".
  return {
    value,
    selectionStart: Math.min(start, edits[0].a),
    selectionEnd: Math.max(end, edits[edits.length - 1].b) + value.length - text.length,
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

  // A caret on an empty line starts the item, heading or list right there, ready to type.
  if (start === end && lines[0].trim().length === 0) {
    const prefix = action === 'heading' ? '# ' : action === 'bullet' ? '- ' : `${number}. `;
    const caret = lineStart + lines[0].length + prefix.length;
    return {
      value: value.slice(0, lineStart) + lines[0] + prefix + value.slice(blockEnd),
      selectionStart: caret,
      selectionEnd: caret,
    };
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

/** A line that opens or closes a fenced code block: its fence (``` or ~~~, or longer) and the rest. */
const FENCE = /^(?:\s*>)*\s*(`{3,}|~{3,})(.*)$/;

/**
 * Whether the line starting at [lineStart] is inside a fenced code block: one opened above it and
 * not yet closed, so a block still being typed counts. A block closes on a fence of the same
 * character, at least as long, with nothing after it, as in CommonMark. Any indent and quote markers
 * may come before a fence, so one inside a list item or a quote counts too: reading too much as
 * code only means Enter is an ordinary newline.
 */
export function insideCodeFence(text: string, lineStart: number): boolean {
  return openCodeFence(text, lineStart) !== null;
}

/** The fence of the code block the line at [lineStart] is inside (see insideCodeFence), or null. */
export function openCodeFence(text: string, lineStart: number): string | null {
  let open: string | null = null;
  for (const line of text.slice(0, lineStart).split('\n')) {
    const m = FENCE.exec(line);
    if (!m) continue;
    const [, fence, rest] = m;
    if (open === null) {
      // A backtick fence's info string can't hold a backtick: that line is inline code instead.
      if (fence[0] !== '`' || !rest.includes('`')) open = fence;
    } else if (fence[0] === open[0] && fence.length >= open.length && rest.trim() === '') {
      open = null;
    }
  }
  return open;
}

/**
 * What Enter does at the caret inside a list, as GitHub and most Markdown editors do: after
 * "- milk" the new line starts "- ", after "3. milk" it starts "4. ", after a task "- [ ] ". Enter on
 * an item with nothing after its marker ends the list instead, leaving an empty line. Returns null
 * where Enter is an ordinary newline (in a fenced code block too), so the textarea handles it.
 */
export function continueList(value: string, start: number, end: number): MarkdownEdit | null {
  if (start !== end) return null;
  const lineStart = lineStartOf(value, start);
  const lineEnd = lineEndOf(value, start);
  const line = value.slice(lineStart, lineEnd);
  const match = LIST_ITEM.exec(line);
  // Enter typed within the indent or the marker: an ordinary newline.
  if (!match || start < lineStart + match[0].length) return null;
  // "- example" inside a code block is code, not a list.
  if (insideCodeFence(value, lineStart)) return null;

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

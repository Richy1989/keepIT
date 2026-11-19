/**
 * Highlighted text in a note body: `<mark data-color="green">text</mark>`, the one piece of HTML a
 * note may hold. Everything else in raw HTML is still shown as the text it is (Markdown.tsx); this
 * tag alone becomes a highlight, in one of five colours. `<mark>` without a colour, or with one this
 * build doesn't know, is yellow. The toolbar always writes the colour out.
 *
 * The tag was chosen over a syntax of our own (`==text==`) because other Markdown apps show it as
 * a highlight too. It is recognised only as written here — `<mark>` or `<mark data-color="…">`,
 * case and spacing aside — and only when its closing tag is in the same paragraph, in the same
 * run of formatting; otherwise both tags show as text, as any other HTML does.
 *
 * The Android app's `ui/markdown/HighlightMarks.kt` implements the same rules. Both are held to
 * `highlight.json`, which also specifies {@link applyHighlight}.
 */

export const HIGHLIGHT_COLORS = ['yellow', 'green', 'blue', 'pink', 'purple'] as const;
export type HighlightColor = (typeof HIGHLIGHT_COLORS)[number];

export const HIGHLIGHT_LABELS: Record<HighlightColor, string> = {
  yellow: 'Yellow',
  green: 'Green',
  blue: 'Blue',
  pink: 'Pink',
  purple: 'Purple',
};

const OPEN = /^<mark(?:\s+data-color\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?\s*>$/i;
const CLOSE = /^<\/mark\s*>$/i;

/** What a piece of inline HTML is: a highlight's opening tag (with its colour), its closing tag, or neither. */
export function highlightTag(html: string): { open: HighlightColor } | 'close' | null {
  if (CLOSE.test(html)) return 'close';
  const m = OPEN.exec(html);
  if (!m) return null;
  const named = (m[1] ?? m[2] ?? m[3] ?? '').toLowerCase();
  return { open: (HIGHLIGHT_COLORS as readonly string[]).includes(named) ? (named as HighlightColor) : 'yellow' };
}

// ---- rendering: a remark plugin ----

interface MdNode {
  type: string;
  value?: string;
  children?: MdNode[];
  data?: Record<string, unknown>;
}

/**
 * Pairs `<mark>` tags among each node's children, innermost first, and wraps what lies between in
 * a node that renders as `<mark data-color>`. An unpaired tag is left as raw HTML, which
 * react-markdown shows as text.
 */
function pair(nodes: MdNode[]): MdNode[] {
  const out: MdNode[] = [];
  // Indexes into `out` of the opening tags still waiting for their close.
  const open: number[] = [];
  for (const node of nodes) {
    if (node.children) node.children = pair(node.children);
    const tag = node.type === 'html' ? highlightTag(node.value ?? '') : null;
    if (tag === 'close' && open.length > 0) {
      const at = open.pop()!;
      const color = highlightTag(out[at].value ?? '') as { open: HighlightColor };
      const inner = out.splice(at + 1);
      out.splice(at, 1, {
        type: 'highlight',
        children: inner,
        data: { hName: 'mark', hProperties: { dataColor: color.open, className: ['note-mark'] } },
      });
      // Indexes after `at` are gone; anything still open is before it.
      continue;
    }
    if (tag && tag !== 'close') open.push(out.length);
    out.push(node);
  }
  return out;
}

/** The remark plugin that turns paired `<mark>` tags into highlights (see the module doc). */
export function remarkHighlight() {
  return (tree: MdNode) => {
    if (tree.children) tree.children = pair(tree.children);
  };
}

/** A note body without its highlight tags: what search looks through, and what a preview shows. */
export function withoutHighlightTags(text: string): string {
  return text.replace(/<\/?mark\b[^>]*>/gi, '');
}

// ---- editing: the toolbar's highlighter ----

export interface HighlightEdit {
  value: string;
  selectionStart: number;
  selectionEnd: number;
}

const openTag = (color: HighlightColor) => `<mark data-color="${color}">`;
const CLOSE_TAG = '</mark>';

/**
 * Highlights the selection in [color], or with null takes the highlight away. A selection inside
 * (or exactly around) a highlight on its line changes that highlight instead: the same colour or
 * null removes it, another colour recolours it. Otherwise the selection is wrapped — line by line
 * when it spans several, since a highlight can't cross a line into the next paragraph or list
 * item — and an empty one gets an empty highlight with the caret inside, to type into.
 */
export function applyHighlight(
  value: string,
  start: number,
  end: number,
  color: HighlightColor | null,
): HighlightEdit {
  const around = enclosing(value, start, end);
  if (around) {
    const inner = value.slice(around.innerStart, around.innerEnd);
    const remove = color === null || color === around.color;
    const before = value.slice(0, around.openStart);
    const after = value.slice(around.closeEnd);
    const open = remove ? '' : openTag(color);
    const close = remove ? '' : CLOSE_TAG;
    const shift = open.length - (around.innerStart - around.openStart);
    return {
      value: before + open + inner + close + after,
      selectionStart: Math.max(around.openStart + open.length, start + shift),
      selectionEnd: Math.min(around.openStart + open.length + inner.length, end + shift),
    };
  }
  if (color === null) return { value, selectionStart: start, selectionEnd: end };

  const open = openTag(color);
  if (start === end) {
    return {
      value: value.slice(0, start) + open + CLOSE_TAG + value.slice(end),
      selectionStart: start + open.length,
      selectionEnd: start + open.length,
    };
  }
  const selected = value.slice(start, end);
  const wrapped = selected
    .split('\n')
    .map((line) => (line.trim() ? open + line + CLOSE_TAG : line))
    .join('\n');
  const single = !selected.includes('\n');
  return {
    value: value.slice(0, start) + wrapped + value.slice(end),
    selectionStart: single ? start + open.length : start,
    selectionEnd: single ? end + open.length : start + wrapped.length,
  };
}

/** The highlight on the selection's line that holds the selection, tags included or not. */
function enclosing(
  value: string,
  start: number,
  end: number,
): { openStart: number; innerStart: number; innerEnd: number; closeEnd: number; color: HighlightColor } | null {
  // Not lastIndexOf(…, -1), which JavaScript reads as 0: a note starting with a newline.
  const lineStart = start === 0 ? 0 : value.lastIndexOf('\n', start - 1) + 1;
  const lineEndAt = value.indexOf('\n', end);
  const lineEnd = lineEndAt === -1 ? value.length : lineEndAt;
  if (value.slice(start, end).includes('\n')) return null;
  const line = value.slice(lineStart, lineEnd);

  // Every well-formed pair on the line, innermost last, so the innermost holding the selection wins.
  let best: ReturnType<typeof enclosing> = null;
  const stack: { at: number; length: number; color: HighlightColor }[] = [];
  const tags = /<\/?mark\b[^>]*>/gi;
  for (let m = tags.exec(line); m; m = tags.exec(line)) {
    const tag = highlightTag(m[0]);
    if (tag === null) continue;
    if (tag !== 'close') {
      stack.push({ at: m.index, length: m[0].length, color: tag.open });
      continue;
    }
    const opened = stack.pop();
    if (!opened) continue;
    const pairAt = {
      openStart: lineStart + opened.at,
      innerStart: lineStart + opened.at + opened.length,
      innerEnd: lineStart + m.index,
      closeEnd: lineStart + m.index + m[0].length,
      color: opened.color,
    };
    if (pairAt.openStart <= start && end <= pairAt.closeEnd) {
      if (!best || pairAt.openStart >= best.openStart) best = pairAt;
    }
  }
  return best;
}

/**
 * Selection-based Markdown editing for the note body textarea: each action rewrites the value
 * around the current selection and returns where the selection should land, so the toolbar stays
 * a dumb list of buttons. Inline actions toggle (applying bold to bold text removes it); line
 * actions toggle their prefix on every selected line.
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

function toggleInline(value: string, start: number, end: number, marker: string): MarkdownEdit {
  const m = marker.length;
  const selected = value.slice(start, end);

  // Selection includes the markers ("**bold**" selected) → strip them.
  if (selected.length >= 2 * m && selected.startsWith(marker) && selected.endsWith(marker)) {
    const inner = selected.slice(m, selected.length - m);
    return {
      value: value.slice(0, start) + inner + value.slice(end),
      selectionStart: start,
      selectionEnd: start + inner.length,
    };
  }
  // Markers sit just outside the selection ("bold" selected inside "**bold**") → strip them.
  if (value.slice(start - m, start) === marker && value.slice(end, end + m) === marker) {
    return {
      value: value.slice(0, start - m) + selected + value.slice(end + m),
      selectionStart: start - m,
      selectionEnd: end - m,
    };
  }
  // Wrap; with no selection the cursor lands between the markers, ready to type.
  return {
    value: value.slice(0, start) + marker + selected + marker + value.slice(end),
    selectionStart: start + m,
    selectionEnd: end + m,
  };
}

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

function toggleLinePrefix(
  value: string,
  start: number,
  end: number,
  action: 'heading' | 'bullet' | 'ordered',
): MarkdownEdit {
  const lineStart = value.lastIndexOf('\n', start - 1) + 1;
  const lineEndIdx = value.indexOf('\n', end);
  const blockEnd = lineEndIdx === -1 ? value.length : lineEndIdx;
  const lines = value.slice(lineStart, blockEnd).split('\n');

  const test: RegExp =
    action === 'heading' ? /^#{1,6} / : action === 'bullet' ? /^[-*] / : /^\d+\. /;
  const allPrefixed = lines.every((l) => l.length === 0 || test.test(l));

  const next = lines.map((line, i) => {
    if (line.length === 0) return line;
    if (allPrefixed) return line.replace(test, '');
    if (test.test(line)) return line; // partially prefixed selection: fill in the rest
    if (action === 'heading') return `# ${line}`;
    if (action === 'bullet') return `- ${line}`;
    return `${i + 1}. ${line}`;
  });

  const block = next.join('\n');
  return {
    value: value.slice(0, lineStart) + block + value.slice(blockEnd),
    selectionStart: lineStart,
    selectionEnd: lineStart + block.length,
  };
}

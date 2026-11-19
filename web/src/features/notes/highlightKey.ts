import type { KeyboardEvent } from 'react';
import { applyHighlight, type HighlightColor } from './highlight';

/** Applying a toolbar edit to the body textarea, and the highlighter's shortcut and last colour. */

type Edit = { value: string; selectionStart: number; selectionEnd: number };

export function commit(el: HTMLTextAreaElement, edit: Edit, onChange: (value: string) => void) {
  onChange(edit.value);
  requestAnimationFrame(() => {
    el.focus();
    el.setSelectionRange(edit.selectionStart, edit.selectionEnd);
  });
}

/** The colour the shortcut uses: the last one picked in this tab, yellow to begin with. */
let lastHighlight: HighlightColor = 'yellow';

export function highlight(el: HTMLTextAreaElement, color: HighlightColor | null, onChange: (value: string) => void) {
  if (color) lastHighlight = color;
  commit(el, applyHighlight(el.value, el.selectionStart, el.selectionEnd, color), onChange);
}

/**
 * Ctrl/⌘+Shift+H in the body textarea: highlights the selection in the last colour used, or takes
 * that highlight away again. For the textarea's onKeyDown; returns whether it handled the key.
 */
export function onHighlightKey(
  e: KeyboardEvent<HTMLTextAreaElement>,
  onChange: (value: string) => void,
): boolean {
  if (!(e.ctrlKey || e.metaKey) || !e.shiftKey || e.key.toLowerCase() !== 'h') return false;
  e.preventDefault();
  highlight(e.currentTarget, lastHighlight, onChange);
  return true;
}

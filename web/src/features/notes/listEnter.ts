import type { KeyboardEvent } from 'react';
import { continueList } from './markdownEdit';

/**
 * A note body textarea's Enter key: inside a list it starts the next item, or ends the list on an
 * empty one (see {@link continueList}); anywhere else it is left to the browser. Shift+Enter and
 * a keyboard mid-composition (IME) always keep their native behaviour.
 */
export function onListEnter(onChange: (value: string) => void) {
  return (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== 'Enter' || e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.nativeEvent.isComposing) return;
    const el = e.currentTarget;
    const edit = continueList(el.value, el.selectionStart, el.selectionEnd);
    if (!edit) return;
    e.preventDefault();
    onChange(edit.value);
    // After React has written the new value, which would otherwise leave the caret at the end.
    requestAnimationFrame(() => el.setSelectionRange(edit.selectionStart, edit.selectionEnd));
  };
}

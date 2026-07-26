import { useEffect, type RefObject } from 'react';

/** Elements that can hold focus, in DOM order — the tab ring of a trapped container. */
const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Confines Tab / Shift+Tab to `ref`'s subtree while `active`, and returns focus to whatever was
 * focused before on teardown.
 *
 * Without this, a modal is only *visually* modal: Tab walks straight out of it into the grid behind
 * the overlay, and dismissing it drops focus on `<body>`, so the next Tab restarts from the top of
 * the document. Skips elements that are hidden (`offsetParent === null`) so a collapsed popover
 * inside the dialog doesn't create a dead stop in the ring.
 */
export function useFocusTrap(ref: RefObject<HTMLElement | null>, active = true): void {
  useEffect(() => {
    if (!active) return;
    const node = ref.current;
    if (!node) return;

    const restoreTo = document.activeElement as HTMLElement | null;

    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Tab' || !node) return;
      const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
        (el) => el.offsetParent !== null,
      );
      if (items.length === 0) return;

      const first = items[0];
      const last = items[items.length - 1];
      const current = document.activeElement;

      // Wrap at the ends, and pull focus back in if it somehow escaped the container.
      if (e.shiftKey && (current === first || !node.contains(current))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (current === last || !node.contains(current))) {
        e.preventDefault();
        first.focus();
      }
    }

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      // The trigger may have unmounted with the dialog; only restore if it's still in the document.
      if (restoreTo?.isConnected) restoreTo.focus();
    };
  }, [ref, active]);
}

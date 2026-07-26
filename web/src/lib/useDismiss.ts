import { useEffect, type RefObject } from 'react';

/**
 * Closes a popover/menu when the user clicks (or taps) outside `ref`, or presses Escape — the
 * standard dismiss behavior. Uses a document-level `pointerdown` listener rather than an overlay
 * div, so it's immune to z-index/stacking-context surprises and doesn't swallow the click from the
 * element underneath. Only active while `active` is true.
 *
 * On an Escape dismiss, focus is returned to whatever opened the popover. Without that the focused
 * node is unmounted along with the menu, focus falls to `<body>`, and the user's next Tab restarts
 * from the top of the document.
 */
export function useDismiss(
  ref: RefObject<HTMLElement | null>,
  active: boolean,
  onDismiss: () => void,
) {
  useEffect(() => {
    if (!active) return;

    // Captured while the popover is open — by then the trigger has been clicked or focused.
    const opener = document.activeElement as HTMLElement | null;

    const onPointerDown = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onDismiss();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // Only reclaim focus if it's still inside the popover; the user may have moved on already.
      if (opener?.isConnected && ref.current?.contains(document.activeElement)) opener.focus();
      onDismiss();
    };

    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [ref, active, onDismiss]);
}

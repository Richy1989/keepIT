import { useEffect, type RefObject } from 'react';

/**
 * Closes a popover/menu when the user clicks (or taps) outside `ref`, or presses Escape — the
 * standard dismiss behavior. Uses a document-level `pointerdown` listener rather than an overlay
 * div, so it's immune to z-index/stacking-context surprises and doesn't swallow the click from the
 * element underneath. Only active while `active` is true.
 */
export function useDismiss(
  ref: RefObject<HTMLElement | null>,
  active: boolean,
  onDismiss: () => void,
) {
  useEffect(() => {
    if (!active) return;

    const onPointerDown = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onDismiss();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onDismiss();
    };

    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [ref, active, onDismiss]);
}

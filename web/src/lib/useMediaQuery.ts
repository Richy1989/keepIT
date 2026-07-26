import { useSyncExternalStore } from 'react';

/**
 * Subscribes to a CSS media query and re-renders when it flips.
 *
 * `useSyncExternalStore` rather than an effect: the value is read during render straight from the
 * browser, so there's no first-paint frame with a stale answer.
 */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const mq = window.matchMedia(query);
      mq.addEventListener('change', onChange);
      return () => mq.removeEventListener('change', onChange);
    },
    () => window.matchMedia(query).matches,
    () => false, // SSR/prerender fallback — assume the narrow layout
  );
}

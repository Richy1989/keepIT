/**
 * Formats a recording's length as `m:ss`, the shape every voice note in the wild is.
 *
 * Its own module rather than a second export from the player, so the card can show a duration
 * without importing a component.
 */
export function formatDuration(ms: number): string {
  const total = Math.round(ms / 1000);
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

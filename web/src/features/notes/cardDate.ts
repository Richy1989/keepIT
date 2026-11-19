/**
 * The date at the bottom of a note card, which is when the note was created: "Today",
 * "Yesterday", then month and day ("Nov 17"), with the year only outside this one. Sized for a
 * narrow masonry column; the card's tooltip holds the full timestamp.
 */
export function cardDateLabel(at: Date, now: Date = new Date()): string {
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (at.toDateString() === now.toDateString()) return 'Today';
  if (at.toDateString() === yesterday.toDateString()) return 'Yesterday';
  const opts: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric' };
  if (at.getFullYear() !== now.getFullYear()) opts.year = 'numeric';
  return at.toLocaleDateString(undefined, opts);
}

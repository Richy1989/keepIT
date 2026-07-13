/**
 * Date helpers shared by the reminder chip and picker. Backend timestamps are UTC; Postgres emits
 * a trailing 'Z' but the SQLite dev provider can return DateTimes with no zone designator, which
 * the browser would otherwise parse as local time — so we append 'Z' when it's missing (the same
 * guard as the card's timestamp formatting).
 */
export function parseUtc(iso: string): Date {
  return new Date(/[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`);
}

/** Whether a reminder timestamp is already in the past. */
export function isOverdue(iso: string): boolean {
  return parseUtc(iso).getTime() < Date.now();
}

/** Compact local rendering for the chip: time only today, no year within the year. */
export function formatReminderTime(iso: string): string {
  const d = parseUtc(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }
  const opts: Intl.DateTimeFormatOptions = {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  };
  if (d.getFullYear() !== now.getFullYear()) opts.year = 'numeric';
  return d.toLocaleString(undefined, opts);
}

/** Formats a Date as a `datetime-local` input value (local time, minute precision). */
export function toLocalInputValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

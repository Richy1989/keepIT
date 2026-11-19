import type { ReminderRecurrence } from '../../api/types';

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

/**
 * A reminder's time for people, in local time: "Today, 14:00" / "Tomorrow, 08:00" /
 * "Mon, Jul 21, 09:00", with the year only outside this one. The picker's headline and the card's
 * chip both say it this way.
 */
export function reminderLabel(at: Date, now: Date = new Date()): string {
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  const time = at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  if (at.toDateString() === now.toDateString()) return `Today, ${time}`;
  if (at.toDateString() === tomorrow.toDateString()) return `Tomorrow, ${time}`;
  const opts: Intl.DateTimeFormatOptions = { weekday: 'short', month: 'short', day: 'numeric' };
  if (at.getFullYear() !== now.getFullYear()) opts.year = 'numeric';
  return `${at.toLocaleDateString(undefined, opts)}, ${time}`;
}

/** What each repeat is called, on the chip and in the picker. */
export const RECURRENCE_LABELS: Record<Exclude<ReminderRecurrence, null | undefined>, string> = {
  None: 'Does not repeat',
  Daily: 'Daily',
  Weekly: 'Weekly',
  Monthly: 'Monthly',
  Yearly: 'Yearly',
};

/** The card chip's text: the time, then how it repeats ("Sun, Nov 29, 09:00 · Monthly"). */
export function reminderChipLabel(
  iso: string,
  recurrence: ReminderRecurrence | undefined,
  now: Date = new Date(),
): string {
  const when = reminderLabel(parseUtc(iso), now);
  return recurrence && recurrence !== 'None' ? `${when} · ${RECURRENCE_LABELS[recurrence]}` : when;
}

/**
 * This browser's IANA time zone (`Europe/Vienna`), sent with a reminder: a repeating reminder keeps
 * its clock time there, so 08:00 stays 08:00 when the clocks change. Undefined when the browser
 * won't say, and the server then counts in its own zone.
 */
export function browserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
  } catch {
    return undefined;
  }
}

/** Formats a Date as a `datetime-local` input value (local time, minute precision). */
export function toLocalInputValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

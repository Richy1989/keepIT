import { describe, expect, it } from 'vitest';
import { cardDateLabel } from './cardDate';
import { reminderChipLabel, reminderLabel } from './reminderTime';

// Local times, so the day boundaries are this machine's own, as they are in a browser.
const now = new Date(2026, 10, 19, 13, 0);

describe('cardDateLabel', () => {
  it('says Today and Yesterday by the calendar, not by 24 hours', () => {
    expect(cardDateLabel(new Date(2026, 10, 19, 0, 5), now)).toBe('Today');
    expect(cardDateLabel(new Date(2026, 10, 18, 23, 59), now)).toBe('Yesterday');
    expect(cardDateLabel(new Date(2026, 10, 18, 0, 1), now)).toBe('Yesterday');
  });

  it('gives older dates a month and day, and a year only outside this one', () => {
    const thisYear = cardDateLabel(new Date(2026, 10, 17), now);
    expect(thisYear).not.toMatch(/2026/);
    expect(thisYear).toMatch(/17/);
    expect(cardDateLabel(new Date(2025, 10, 17), now)).toMatch(/2025/);
  });

  it('knows yesterday across a month boundary', () => {
    expect(cardDateLabel(new Date(2026, 9, 31, 20), new Date(2026, 10, 1, 8))).toBe('Yesterday');
  });
});

describe('reminderChipLabel', () => {
  it('names the repeat after the time', () => {
    const iso = new Date(2026, 10, 29, 9, 0).toISOString();
    const label = reminderChipLabel(iso, 'Monthly', now);
    expect(label.endsWith(' · Monthly')).toBe(true);
    expect(label.startsWith(reminderLabel(new Date(2026, 10, 29, 9, 0), now))).toBe(true);
  });

  it('says nothing more for a reminder that does not repeat', () => {
    const iso = new Date(2026, 10, 20, 8, 0).toISOString();
    expect(reminderChipLabel(iso, 'None', now)).toMatch(/^Tomorrow, /);
    expect(reminderChipLabel(iso, null, now)).not.toMatch(/·/);
  });
});

import { describe, expect, it } from 'vitest';
import { expiryFromPreset, publicLinkUrl, tokenFromHash } from './publicLink';

const TOKEN = 'k3J9xQ2mZr8vT1pLw0aYcN5eHf7uGd4sBi6oRjEtUq_';

describe('publicLinkUrl', () => {
  it('puts the token in the fragment, where no server ever sees it', () => {
    expect(publicLinkUrl('https://notes.example.com', TOKEN)).toBe(`https://notes.example.com/p#${TOKEN}`);
    expect(publicLinkUrl('https://notes.example.com/', TOKEN)).toBe(`https://notes.example.com/p#${TOKEN}`);
  });

  it("prefers the server's link, built on its configured public address", () => {
    expect(publicLinkUrl('http://192.168.1.5', TOKEN, `https://notes.example.com/p#${TOKEN}`)).toBe(
      `https://notes.example.com/p#${TOKEN}`,
    );
  });
});

describe('tokenFromHash', () => {
  it('reads the token back out of the fragment', () => {
    expect(tokenFromHash(`#${TOKEN}`)).toBe(TOKEN);
    expect(tokenFromHash(TOKEN)).toBe(TOKEN);
  });

  it('opens nothing for an empty or mangled fragment', () => {
    expect(tokenFromHash('')).toBeNull();
    expect(tokenFromHash('#')).toBeNull();
    expect(tokenFromHash('#top')).toBeNull();
    expect(tokenFromHash('#abc def ghi jkl mno')).toBeNull();
  });
});

describe('expiryFromPreset', () => {
  const now = new Date('2026-11-22T10:00:00Z');

  it('gives no expiry for never, and a fixed span otherwise', () => {
    expect(expiryFromPreset('never', now)).toBeNull();
    expect(expiryFromPreset('1d', now)).toBe('2026-11-23T10:00:00.000Z');
    expect(expiryFromPreset('7d', now)).toBe('2026-11-29T10:00:00.000Z');
    expect(expiryFromPreset('30d', now)).toBe('2026-12-22T10:00:00.000Z');
  });

  it('runs a picked date to the end of that day, local time', () => {
    const iso = expiryFromPreset('custom', now, '2026-12-01');
    const end = new Date(iso!);
    expect([end.getFullYear(), end.getMonth(), end.getDate(), end.getHours(), end.getMinutes()]).toEqual([
      2026, 11, 1, 23, 59,
    ]);
  });

  it('refuses a missing or past date', () => {
    expect(expiryFromPreset('custom', now)).toBeUndefined();
    expect(expiryFromPreset('custom', now, 'soon')).toBeUndefined();
    expect(expiryFromPreset('custom', now, '2026-11-01')).toBeUndefined();
  });
});

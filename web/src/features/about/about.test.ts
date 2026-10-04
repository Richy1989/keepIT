import { describe, expect, it } from 'vitest';
import { ABOUT } from './content';

/**
 * The About page's content (`about.json`), which both clients show: the Android app's
 * AboutContentParityTest holds it to the same file. These are the checks that don't need the
 * other client — every link and credit complete, on https, and listed once.
 */
describe('about.json', () => {
  const credits = [...ABOUT.credits.web, ...ABOUT.credits.server];

  it('links only to https addresses', () => {
    for (const url of [...ABOUT.links.map((l) => l.url), ...credits.map((c) => c.url)]) {
      expect(new URL(url).protocol, url).toBe('https:');
    }
  });

  it('gives every link a label and a line of detail, under a unique id', () => {
    for (const link of ABOUT.links) {
      expect(link.label.trim(), link.id).not.toBe('');
      expect(link.detail.trim(), link.id).not.toBe('');
    }
    expect(new Set(ABOUT.links.map((l) => l.id)).size).toBe(ABOUT.links.length);
  });

  it('names what each credited project does and its licence, once per group', () => {
    for (const credit of credits) {
      expect(credit.role.trim(), credit.name).not.toBe('');
      expect(credit.license.trim(), credit.name).not.toBe('');
    }
    for (const group of [ABOUT.credits.web, ABOUT.credits.server]) {
      expect(new Set(group.map((c) => c.name)).size).toBe(group.length);
    }
  });
});

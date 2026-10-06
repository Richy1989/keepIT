import { describe, expect, it } from 'vitest';
import { LIST_ICONS } from './listIcons';

/**
 * The picker's emoji (`listIcons.json`), which the Android app's ListIconsParityTest holds its own
 * grid to. These are the checks that don't need the other client: every entry is an icon the API
 * accepts, and the grid fills its rows.
 */
describe('listIcons.json', () => {
  const graphemes = new Intl.Segmenter(undefined, { granularity: 'grapheme' });

  it('holds only single symbols the API accepts', () => {
    for (const icon of LIST_ICONS) {
      expect([...graphemes.segment(icon)], icon).toHaveLength(1);
      // NoteLimits.ListIcon, in UTF-16 code units like String.length.
      expect(icon.length, icon).toBeLessThanOrEqual(16);
    }
  });

  it('lists each emoji once, in full rows of eight', () => {
    expect(new Set(LIST_ICONS).size).toBe(LIST_ICONS.length);
    expect(LIST_ICONS.length % 8).toBe(0);
  });
});

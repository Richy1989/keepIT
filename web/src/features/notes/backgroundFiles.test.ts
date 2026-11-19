import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { backgroundsFromFiles } from './backgrounds';

/**
 * Holds the repository's `backgrounds/` folder to the rules in its README, so an image that breaks
 * one fails here instead of silently not showing up in the picker.
 */
const FOLDER = fileURLToPath(new URL('../../../../backgrounds', import.meta.url));
const files = readdirSync(FOLDER).filter((f) => f !== 'README.md');

describe('the backgrounds folder', () => {
  it('holds only background images', () => {
    const found = backgroundsFromFiles(Object.fromEntries(files.map((f) => [f, f])));
    const used = found.map((b) => b.url);
    expect(files.filter((f) => !used.includes(f))).toEqual([]);
  });

  it('keeps each image small, since every app ships it', () => {
    const large = files.filter((f) => statSync(join(FOLDER, f)).size > 64 * 1024);
    expect(large).toEqual([]);
  });
});

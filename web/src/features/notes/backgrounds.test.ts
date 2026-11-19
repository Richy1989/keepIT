import { describe, expect, it, vi } from 'vitest';
import { backgroundLabel, backgroundVars, backgroundsFromFiles } from './backgrounds';

describe('backgroundsFromFiles', () => {
  it('makes one background per image, sorted by name', () => {
    const got = backgroundsFromFiles({
      '../../../../backgrounds/lined.png': '/assets/lined-1.png',
      '../../../../backgrounds/grid-dots.webp': '/assets/grid-dots-2.webp',
    });
    expect(got).toEqual([
      { name: 'grid-dots', label: 'Grid dots', url: '/assets/grid-dots-2.webp' },
      { name: 'lined', label: 'Lined', url: '/assets/lined-1.png' },
    ]);
  });

  it('leaves out names the server would refuse, and images without transparency', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const got = backgroundsFromFiles({
      'backgrounds/Lined.png': 'a',
      'backgrounds/two--hyphens.png': 'b',
      'backgrounds/under_score.png': 'c',
      'backgrounds/lined.dark.png': 'd',
      'backgrounds/photo.jpg': 'e',
      'backgrounds/ok.png': 'f',
    });
    expect(got.map((b) => b.name)).toEqual(['ok']);
    expect(warn).toHaveBeenCalledTimes(5);
    warn.mockRestore();
  });
});

describe('backgroundVars', () => {
  const bgs = backgroundsFromFiles({ 'backgrounds/lined.png': '/l.png' });

  it('gives the stencil and the ink it is painted in', () => {
    expect(backgroundVars('lined', 'var(--note-sky-border)', bgs)).toEqual({
      '--tile': 'image-set(url("/l.png") 2x)',
      '--tile-ink': 'var(--note-sky-border)',
    });
  });

  it('draws nothing for no background, or one this build does not have', () => {
    expect(backgroundVars(null, 'x', bgs)).toBeUndefined();
    expect(backgroundVars('from-a-newer-app', 'x', bgs)).toBeUndefined();
  });
});

describe('backgroundLabel', () => {
  it('reads as words', () => expect(backgroundLabel('squared-paper')).toBe('Squared paper'));
});

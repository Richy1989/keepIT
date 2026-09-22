import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Contrast guard for the design tokens in index.css.
 *
 * Every theme × accent combination is resolved the way the browser would resolve it, and the text
 * tokens are measured against the surfaces they actually land on. This exists because the failure
 * it catches is invisible: nothing throws, nothing logs, a colour is simply unreadable for whoever
 * picked that theme. The light theme shipped for a long time with the bright accent as its link
 * and active-nav colour (1.7:1 with the default yellow), the two image-upload error banners as
 * `text-red-200` on white (1.2:1), and `--color-text-faint` at 2.8:1.
 *
 * It is a token test, not a screenshot test: it says nothing about whether a component uses the
 * right token, only that no token pair in the system is unreadable. Using `text-red-200` instead
 * of `text-danger` would still sail past it — a component reaching outside the token set is what
 * review and the "no raw palette classes" grep are for.
 */

const CSS = readFileSync(fileURLToPath(new URL('./index.css', import.meta.url)), 'utf8');

/** WCAG 2.1 AA for body text. */
const AA_TEXT = 4.5;
/**
 * WCAG 2.1 AA for large text and UI components. Applied to the *secondary* tokens on the ten
 * per-note background colours: holding 4.5:1 for `--color-text-faint` across 10 colours × 3 themes
 * would force the faint token dark enough to stop being faint, and what it carries on a coloured
 * card is a timestamp, a "2/3 done" counter and a reminder chip, never note content. The note's
 * own title and body use `--color-text` / `--color-text-muted`, which are held to AA_TEXT below.
 */
const AA_LARGE = 3;

const THEMES = ['dark', 'light', 'dim'] as const;
const ACCENTS = ['yellow', 'orange', 'red', 'pink', 'purple', 'blue', 'teal', 'green'] as const;
const NOTE_COLORS = [
  'rose', 'coral', 'amber', 'sage', 'teal', 'sky', 'indigo', 'violet', 'mauve',
] as const;

type Tokens = Record<string, string>;

/** Declarations of the first block matching `selector`. None of the blocks read here nest. */
function block(selector: string): Tokens {
  const withoutComments = CSS.replace(/\/\*[\s\S]*?\*\//g, '');
  const start = withoutComments.indexOf(selector);
  if (start === -1) throw new Error(`no such block in index.css: ${selector}`);
  const open = withoutComments.indexOf('{', start);
  const close = withoutComments.indexOf('}', open);
  const out: Tokens = {};
  for (const decl of withoutComments.slice(open + 1, close).split(';')) {
    const [name, ...rest] = decl.split(':');
    const key = name.trim();
    if (key.startsWith('--')) out[key] = rest.join(':').trim();
  }
  return out;
}

/**
 * The tokens in force for one theme + accent, in cascade order. `@theme` and `:root` are the
 * baseline (specificity 0,1,0); the theme and accent blocks are both `html[…]` (0,2,1), so between
 * those two the *later* one in the file wins — which is the accent block. That ordering is the
 * whole reason the accent blocks must not set `--color-accent-ink`; see the comment in index.css.
 */
function resolveTheme(theme: string, accent: string): Tokens {
  const merged: Tokens = {
    ...block('@theme'),
    ...block(':root'),
    ...(theme === 'dark' ? {} : block(`html[data-theme='${theme}']`)),
    ...block(`html[data-accent='${accent}']`),
  };
  const deref = (value: string, depth = 0): string => {
    const m = /^var\((--[\w-]+)\)$/.exec(value.trim());
    if (!m) return value.trim();
    if (depth > 10) throw new Error(`circular var(): ${value}`);
    const next = merged[m[1]];
    if (next === undefined) throw new Error(`unresolved var(): ${m[1]}`);
    return deref(next, depth + 1);
  };
  return Object.fromEntries(Object.entries(merged).map(([k, v]) => [k, deref(v)]));
}

/** #rgb / #rrggbb / rgb(r g b[ / a]) → [r, g, b]. Alpha is rejected: it can't be measured alone. */
function rgb(value: string): [number, number, number] {
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value);
  if (hex) {
    const h = hex[1].length === 3 ? [...hex[1]].map((c) => c + c).join('') : hex[1];
    return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)) as [number, number, number];
  }
  const fn = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)\s*(?:[/,]\s*([\d.]+)\s*)?\)$/.exec(value);
  if (fn && fn[4] === undefined) return [+fn[1], +fn[2], +fn[3]];
  throw new Error(`not an opaque colour: ${value}`);
}

/** WCAG 2.1 relative luminance. */
function luminance(value: string): number {
  const [r, g, b] = rgb(value).map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.1 contrast ratio, 1–21. */
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** The chrome a token is read against: the page, a panel, a menu, and an uncoloured note. */
const CHROME = ['--color-canvas', '--color-surface', '--color-elevated', '--note-default-bg'];

describe.each(THEMES)('theme: %s', (theme) => {
  describe.each(ACCENTS)('accent: %s', (accent) => {
    const t = resolveTheme(theme, accent);

    it.each(CHROME)(`text tokens reach AA on %s`, (surface) => {
      for (const token of ['--color-text', '--color-text-muted', '--color-text-faint']) {
        expect(
          contrast(t[token], t[surface]),
          `${token} on ${surface} (${theme}/${accent})`,
        ).toBeGreaterThanOrEqual(AA_TEXT);
      }
    });

    it.each(CHROME)('the accent reads as content on %s', (surface) => {
      // --color-accent-ink, not --color-accent: the bright accent is a fill. This is the assertion
      // the light theme failed at 1.67:1 with the default yellow.
      expect(
        contrast(t['--color-accent-ink'], t[surface]),
        `--color-accent-ink on ${surface} (${theme}/${accent})`,
      ).toBeGreaterThanOrEqual(AA_TEXT);
    });

    it('status colours read on their own panels', () => {
      expect(contrast(t['--color-danger'], t['--color-danger-bg'])).toBeGreaterThanOrEqual(AA_TEXT);
      expect(contrast(t['--color-warning'], t['--color-warning-bg'])).toBeGreaterThanOrEqual(AA_TEXT);
    });

    it.each(NOTE_COLORS)('note content reads on a %s card', (color) => {
      const bg = t[`--note-${color}-bg`];
      for (const token of ['--color-text', '--color-text-muted']) {
        expect(contrast(t[token], bg), `${token} on ${color} (${theme})`).toBeGreaterThanOrEqual(
          AA_TEXT,
        );
      }
      for (const token of ['--color-text-faint', '--color-accent-ink']) {
        expect(contrast(t[token], bg), `${token} on ${color} (${theme})`).toBeGreaterThanOrEqual(
          AA_LARGE,
        );
      }
    });

    it('a note border is visible against its own fill', () => {
      for (const color of NOTE_COLORS) {
        const ratio = contrast(t[`--note-${color}-bg`], t[`--note-${color}-border`]);
        expect(ratio, `${color} border (${theme})`).toBeGreaterThan(1.05);
      }
    });
  });
});

describe('token structure', () => {
  it('accent blocks never set --color-accent-ink', () => {
    // They come after the theme blocks at equal specificity, so setting it there would beat the
    // light theme's remap and put the unreadable bright shade back everywhere.
    for (const accent of ACCENTS) {
      expect(
        block(`html[data-accent='${accent}']`),
        `html[data-accent='${accent}']`,
      ).not.toHaveProperty('--color-accent-ink');
      expect(block(`html[data-accent='${accent}']`)).toHaveProperty('--accent-ink');
    }
  });

  it('every accent is offered by the picker and defined in CSS', async () => {
    const { ACCENTS: offered } = await import('./features/settings/accent');
    expect(offered.map((a) => a.key).sort()).toEqual([...ACCENTS].sort());
  });

  it('the light theme remaps the accent and the dark baseline tracks it', () => {
    expect(block(`html[data-theme='light']`)['--color-accent-ink']).toBe('var(--accent-ink)');
    expect(block('@theme')['--color-accent-ink']).toBe('var(--color-accent)');
  });

  it('elevation stays out of @theme so a theme can override it', () => {
    // Tailwind inlines a --shadow-* theme value into the utility it generates instead of emitting
    // a var() reference, so a per-theme override silently never applies. They live in :root and
    // are applied through the .elev-* classes.
    for (const name of ['card', 'card-hover', 'panel', 'raised', 'overlay']) {
      expect(block('@theme'), '@theme').not.toHaveProperty(`--shadow-${name}`);
      expect(block(':root'), ':root').toHaveProperty(`--shadow-${name}`);
    }
  });
});

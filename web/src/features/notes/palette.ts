/**
 * Per-note background palette. The actual colors are theme tokens (`--note-<key>-bg/-border`,
 * defined and themed in src/index.css), so a card automatically restyles when the theme changes.
 * `bg`/`border` here are `var(...)` references to those tokens, not raw hex. Stored on the note as
 * the `color` key (e.g. "rose"); "default" means the plain surface.
 */
export interface NoteColor {
  key: string;
  label: string;
  /** Card background — a CSS var reference, themed in index.css. */
  bg: string;
  /** Card border — a CSS var reference, themed in index.css. */
  border: string;
}

const KEYS: { key: string; label: string }[] = [
  { key: 'default', label: 'Default' },
  { key: 'rose', label: 'Rose' },
  { key: 'coral', label: 'Coral' },
  { key: 'amber', label: 'Amber' },
  { key: 'sage', label: 'Sage' },
  { key: 'teal', label: 'Teal' },
  { key: 'sky', label: 'Sky' },
  { key: 'indigo', label: 'Indigo' },
  { key: 'violet', label: 'Violet' },
  { key: 'mauve', label: 'Mauve' },
];

export const NOTE_COLORS: NoteColor[] = KEYS.map(({ key, label }) => ({
  key,
  label,
  bg: `var(--note-${key}-bg)`,
  border: `var(--note-${key}-border)`,
}));

const BY_KEY = new Map(NOTE_COLORS.map((c) => [c.key, c]));

/** Resolves a stored color key to its swatch, falling back to the default surface. */
export function noteColor(key: string | null | undefined): NoteColor {
  return (key ? BY_KEY.get(key) : undefined) ?? NOTE_COLORS[0];
}

/**
 * Per-note background palette, tuned for the dark canvas (ARCHITECTURE.md "Look & feel"): muted,
 * desaturated tones rather than Keep's bright pastels, each dark enough to keep light text legible.
 * Stored on the note as the `color` key (e.g. "rose"); "default" means the plain surface.
 */
export interface NoteColor {
  key: string;
  label: string;
  /** Card background. */
  bg: string;
  /** Card border (slightly lighter than bg). */
  border: string;
}

export const NOTE_COLORS: NoteColor[] = [
  { key: 'default', label: 'Default', bg: '#18181b', border: '#27272a' },
  { key: 'rose', label: 'Rose', bg: '#3b2327', border: '#532f35' },
  { key: 'coral', label: 'Coral', bg: '#3c2a21', border: '#543b2e' },
  { key: 'amber', label: 'Amber', bg: '#39311d', border: '#4f4228' },
  { key: 'sage', label: 'Sage', bg: '#26342a', border: '#34493b' },
  { key: 'teal', label: 'Teal', bg: '#1e3535', border: '#294a4a' },
  { key: 'sky', label: 'Sky', bg: '#22323f', border: '#2f4557' },
  { key: 'indigo', label: 'Indigo', bg: '#272c44', border: '#373e5d' },
  { key: 'violet', label: 'Violet', bg: '#322844', border: '#47385e' },
  { key: 'mauve', label: 'Mauve', bg: '#39263a', border: '#503651' },
];

const BY_KEY = new Map(NOTE_COLORS.map((c) => [c.key, c]));

/** Resolves a stored color key to its swatch, falling back to the default surface. */
export function noteColor(key: string | null | undefined): NoteColor {
  return (key ? BY_KEY.get(key) : undefined) ?? NOTE_COLORS[0];
}

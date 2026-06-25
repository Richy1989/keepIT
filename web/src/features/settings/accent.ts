/**
 * Selectable UI accent colors. The `key` is what's stored (UserSettings.GlobalAccentColor) and
 * written to <html data-accent="…">, which drives the --color-accent token (see index.css). `color`
 * is just the swatch shown in the picker. Keep keys in sync with the backend's allowed set and the
 * data-accent rules in index.css.
 */
export interface AccentOption {
  key: string;
  label: string;
  /** Swatch color for the picker (matches the data-accent fill in index.css). */
  color: string;
}

export const ACCENTS: AccentOption[] = [
  { key: 'yellow', label: 'Yellow', color: '#fbbf24' },
  { key: 'orange', label: 'Orange', color: '#fb923c' },
  { key: 'red', label: 'Red', color: '#f87171' },
  { key: 'pink', label: 'Pink', color: '#f472b6' },
  { key: 'purple', label: 'Purple', color: '#c084fc' },
  { key: 'blue', label: 'Blue', color: '#60a5fa' },
  { key: 'teal', label: 'Teal', color: '#2dd4bf' },
  { key: 'green', label: 'Green', color: '#4ade80' },
];

export const DEFAULT_ACCENT = 'yellow';
export const ACCENT_KEYS = new Set(ACCENTS.map((a) => a.key));

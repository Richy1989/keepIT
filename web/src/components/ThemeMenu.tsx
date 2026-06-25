import { useState } from 'react';
import { useSettings, type ThemePref } from '../features/settings/SettingsContext';
import { ACCENTS } from '../features/settings/accent';
import { PaletteIcon } from './icons';
import { cn } from '../lib/cn';

const THEMES: { key: ThemePref; label: string }[] = [
  { key: 'light', label: 'Light' },
  { key: 'dim', label: 'Dim' },
  { key: 'dark', label: 'Dark' },
  { key: 'system', label: 'Auto' },
];

/** Appearance menu: pick the theme (light / dim / dark / auto) and the accent color. */
export function ThemeMenu() {
  const { theme, accent, setTheme, setAccent } = useSettings();
  const [open, setOpen] = useState(false);

  return (
    <div className="relative">
      <button
        type="button"
        title="Appearance"
        aria-label="Appearance"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="focus-ring grid size-8 place-items-center rounded-full text-text-muted transition hover:bg-surface-hover hover:text-text"
      >
        <PaletteIcon className="text-lg" />
      </button>

      {open && (
        <>
          {/* Click-away backdrop. */}
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-40 mt-2 w-56 rounded-xl border border-border-subtle bg-elevated p-3 shadow-lg shadow-black/30">
            <p className="mb-1.5 text-xs font-medium text-text-muted">Theme</p>
            <div className="grid grid-cols-4 gap-1">
              {THEMES.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTheme(t.key)}
                  aria-pressed={theme === t.key}
                  className={cn(
                    'focus-ring rounded-lg px-2 py-1.5 text-xs transition',
                    theme === t.key
                      ? 'bg-accent text-black'
                      : 'bg-surface text-text-muted hover:bg-surface-hover hover:text-text',
                  )}
                >
                  {t.label}
                </button>
              ))}
            </div>

            <p className="mb-1.5 mt-3 text-xs font-medium text-text-muted">Accent</p>
            <div className="flex flex-wrap gap-1.5">
              {ACCENTS.map((a) => (
                <button
                  key={a.key}
                  type="button"
                  title={a.label}
                  aria-label={a.label}
                  aria-pressed={accent === a.key}
                  onClick={() => setAccent(a.key)}
                  className={cn(
                    'focus-ring size-6 rounded-full border transition hover:scale-110',
                    accent === a.key ? 'border-text' : 'border-transparent',
                  )}
                  style={{ backgroundColor: a.color }}
                />
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

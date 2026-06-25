import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { ACCENT_KEYS, DEFAULT_ACCENT } from './accent';
import { useUpdateSettings, useUserSettings } from './queries';
import { SettingsContext, type ThemePref } from './SettingsContext';

const THEME_STORAGE = 'keepit:theme';
const ACCENT_STORAGE = 'keepit:accent';
const THEME_PREFS: ThemePref[] = ['light', 'dim', 'dark', 'system'];
const DEFAULT_THEME: ThemePref = 'dark';

const asTheme = (v: unknown): ThemePref => (THEME_PREFS.includes(v as ThemePref) ? (v as ThemePref) : DEFAULT_THEME);
const asAccent = (v: unknown): string => (typeof v === 'string' && ACCENT_KEYS.has(v) ? v : DEFAULT_ACCENT);

/** Resolves a preference to a concrete theme, mapping "system" via the OS color scheme. */
function resolveTheme(pref: ThemePref): 'light' | 'dim' | 'dark' {
  if (pref !== 'system') return pref;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** Writes the resolved theme + accent to <html> (where the CSS token overrides hang). */
function applyToDom(pref: ThemePref, accent: string) {
  const resolved = resolveTheme(pref);
  const root = document.documentElement;
  root.dataset.theme = resolved;
  root.dataset.accent = accent;
  root.style.colorScheme = resolved === 'light' ? 'light' : 'dark';
}

/**
 * Owns UI settings (theme + accent). When signed in the backend is the source of truth — read via
 * the settings query, written (optimistically) via the mutation — so we derive the active values
 * straight from that cache. When signed out (e.g. the login screen) we fall back to local state
 * seeded from a localStorage cache, which is also read pre-paint in index.html to avoid a flash.
 */
export function SettingsProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const authed = status === 'authenticated';
  const settings = useUserSettings(authed);
  const persist = useUpdateSettings();

  // Fallback store for the signed-out case (and before the server settings load).
  const [localTheme, setLocalTheme] = useState<ThemePref>(() => asTheme(localStorage.getItem(THEME_STORAGE)));
  const [localAccent, setLocalAccent] = useState<string>(() => asAccent(localStorage.getItem(ACCENT_STORAGE)));

  // Active values: server when available, otherwise the local cache.
  const server = authed ? settings.data : undefined;
  const theme = server ? asTheme(server.theme) : localTheme;
  const accent = server ? asAccent(server.globalAccentColor) : localAccent;

  // Apply to the DOM + refresh the localStorage cache whenever the active choice changes.
  useEffect(() => {
    applyToDom(theme, accent);
    localStorage.setItem(THEME_STORAGE, theme);
    localStorage.setItem(ACCENT_STORAGE, accent);
  }, [theme, accent]);

  // Follow OS changes live while on "system".
  useEffect(() => {
    if (theme !== 'system') return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => applyToDom('system', accent);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [theme, accent]);

  const setTheme = useCallback(
    (next: ThemePref) => {
      if (authed) persist.mutate({ theme: next, globalAccentColor: accent });
      else setLocalTheme(next);
    },
    [authed, accent, persist],
  );

  const setAccent = useCallback(
    (next: string) => {
      if (authed) persist.mutate({ theme, globalAccentColor: next });
      else setLocalAccent(next);
    },
    [authed, theme, persist],
  );

  return (
    <SettingsContext.Provider value={{ theme, accent, setTheme, setAccent }}>
      {children}
    </SettingsContext.Provider>
  );
}

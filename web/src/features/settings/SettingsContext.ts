import { createContext, useContext } from 'react';

/** User's theme preference. "system" follows the OS; the others are explicit. */
export type ThemePref = 'light' | 'dim' | 'dark' | 'system';

export interface SettingsState {
  /** The stored preference (may be "system"). */
  theme: ThemePref;
  /** Accent color key (e.g. "yellow"). */
  accent: string;
  /** Set the theme preference (persists to the backend when signed in). */
  setTheme: (theme: ThemePref) => void;
  /** Set the accent color (persists to the backend when signed in). */
  setAccent: (accent: string) => void;
}

export const SettingsContext = createContext<SettingsState | undefined>(undefined);

/** Access the current theme/accent settings. Must be used within {@link SettingsProvider}. */
export function useSettings(): SettingsState {
  const ctx = useContext(SettingsContext);
  if (!ctx) throw new Error('useSettings must be used within a SettingsProvider');
  return ctx;
}

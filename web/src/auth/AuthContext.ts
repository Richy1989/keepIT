import { createContext, useContext } from 'react';
import type { UserDto } from '../api/types';

/** Auth state + actions exposed to the app via <AuthProvider>. */
export interface AuthState {
  /** The signed-in user, or null. */
  user: UserDto | null;
  /** `loading` while we attempt to restore a session on first paint. */
  status: 'loading' | 'authenticated' | 'unauthenticated';
  /** Exchange credentials for a session. Throws with a user-facing message on failure. */
  login: (email: string, password: string) => Promise<void>;
  /** Create an account and sign in. Throws with a user-facing message on failure. */
  register: (email: string, password: string, displayName?: string) => Promise<void>;
  /** Revoke the refresh token and clear local session state. */
  logout: () => Promise<void>;
}

export const AuthContext = createContext<AuthState | undefined>(undefined);

/** Access the auth state. Must be called inside <AuthProvider>. */
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within <AuthProvider>');
  return ctx;
}

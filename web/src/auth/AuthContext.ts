import { createContext, useContext } from 'react';
import type { UserDto } from '../api/types';

/** Auth state + actions exposed to the app via <AuthProvider>. */
export interface AuthState {
  /** The signed-in user, or null. */
  user: UserDto | null;
  /** `loading` while we attempt to restore a session on first paint. */
  status: 'loading' | 'authenticated' | 'unauthenticated';
  /**
   * Exchange credentials for a session. Throws with a user-facing message on failure, and with a
   * {@link TwoFactorRequiredError} when the password was right and the account also needs a code:
   * call again with the code from the authenticator app (or a recovery code).
   */
  login: (email: string, password: string, twoFactorCode?: string) => Promise<void>;
  /** Create an account and sign in. Throws with a user-facing message on failure. */
  register: (email: string, password: string, displayName?: string) => Promise<void>;
  /** Revoke the refresh token and clear local session state. */
  logout: () => Promise<void>;
  /** Replace the signed-in user with the server's answer to a change made here (a rename). */
  updateUser: (user: UserDto) => void;
  /** Refetch the signed-in user, after another device changed the account. Never throws. */
  refreshUser: () => Promise<void>;
}

/**
 * The sign-in was refused only for want of a second factor: the password was right, and the
 * account asks for a code from its authenticator app. Its message says what to enter.
 */
export class TwoFactorRequiredError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TwoFactorRequiredError';
  }
}

export const AuthContext = createContext<AuthState | undefined>(undefined);

/** Access the auth state. Must be called inside <AuthProvider>. */
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within <AuthProvider>');
  return ctx;
}

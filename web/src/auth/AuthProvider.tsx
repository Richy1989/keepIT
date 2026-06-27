import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, refreshAccessToken, UNAUTHORIZED_EVENT } from '../api/client';
import { tokenStore } from './tokenStore';
import type { AuthResponseDto, UserDto } from '../api/types';
import { AuthContext, type AuthState } from './AuthContext';

/** Pulls a human-readable message out of an API error body (409 `{error}` or a ProblemDetails). */
function errorMessage(error: unknown, fallback: string): string {
  if (error && typeof error === 'object') {
    const e = error as {
      error?: string;
      detail?: string;
      title?: string;
      errors?: Record<string, string[]>;
    };
    // ValidationProblemDetails carries the real messages in `errors` (keyed by field/Identity code).
    // Surface those instead of the generic "One or more validation errors occurred." title.
    if (e.errors && typeof e.errors === 'object') {
      const messages = Object.values(e.errors).flat().filter(Boolean);
      if (messages.length > 0) return messages.join(' ');
    }
    return e.error ?? e.detail ?? e.title ?? fallback;
  }
  return fallback;
}

/**
 * Owns the session: restores it from the httpOnly refresh cookie on load, exposes login/register/
 * logout, and reacts to a global `keepit:unauthorized` event by signing out. The access token
 * itself lives in {@link tokenStore} (memory only), never in React state or storage.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserDto | null>(null);
  const [status, setStatus] = useState<AuthState['status']>('loading');
  const queryClient = useQueryClient();

  // Bootstrap: a valid refresh cookie silently restores the session (survives reloads).
  useEffect(() => {
    let active = true;
    void (async () => {
      const ok = await refreshAccessToken();
      if (!active) return;
      if (ok) {
        const { data } = await api.GET('/api/auth/me');
        if (active && data) {
          setUser(data);
          setStatus('authenticated');
          return;
        }
      }
      if (active) setStatus('unauthenticated');
    })();
    return () => {
      active = false;
    };
  }, []);

  // An irrecoverable 401 from any request bubbles up here as a sign-out.
  useEffect(() => {
    const onUnauthorized = () => {
      setUser(null);
      setStatus('unauthenticated');
      queryClient.clear();
    };
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [queryClient]);

  const applyAuth = useCallback((res: AuthResponseDto) => {
    tokenStore.set(res.accessToken, res.accessTokenExpiresAtUtc);
    setUser(res.user);
    setStatus('authenticated');
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const { data, error } = await api.POST('/api/auth/login', { body: { email, password } });
      if (error || !data) throw new Error(errorMessage(error, 'Invalid email or password.'));
      applyAuth(data);
    },
    [applyAuth],
  );

  const register = useCallback(
    async (email: string, password: string, displayName?: string) => {
      const { data, error } = await api.POST('/api/auth/register', {
        body: { email, password, displayName: displayName || null },
      });
      if (error || !data) throw new Error(errorMessage(error, 'Could not create the account.'));
      applyAuth(data);
    },
    [applyAuth],
  );

  const logout = useCallback(async () => {
    await api.POST('/api/auth/logout').catch(() => undefined);
    tokenStore.clear();
    setUser(null);
    setStatus('unauthenticated');
    queryClient.clear();
  }, [queryClient]);

  return (
    <AuthContext.Provider value={{ user, status, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

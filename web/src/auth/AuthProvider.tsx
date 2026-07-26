import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, refreshAccessToken, UNAUTHORIZED_EVENT } from '../api/client';
import { tokenStore } from './tokenStore';
import { apiErrorMessageFor } from '../lib/apiError';
import type { AuthResponseDto, UserDto } from '../api/types';
import { AuthContext, type AuthState } from './AuthContext';

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
      try {
        if ((await refreshAccessToken()) !== 'ok') return;
        const { data } = await api.GET('/api/auth/me');
        if (active && data) {
          setUser(data);
          setStatus('authenticated');
        }
      } catch {
        // The API going away mid-bootstrap (restart, proxy hiccup) makes `me` reject. Fall through
        // to the sign-in screen rather than leaving the app pinned on the loading spinner forever.
      } finally {
        // Only ever resolves the initial 'loading' state — never demotes an established session.
        if (active) setStatus((s) => (s === 'loading' ? 'unauthenticated' : s));
      }
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
    if (!res.accessToken || !tokenStore.set(res.accessToken, res.accessTokenExpiresAtUtc ?? '')) {
      throw new Error('The server returned an unusable session. Please try again.');
    }
    setUser(res.user);
    setStatus('authenticated');
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const { data, error, response } = await api.POST('/api/auth/login', {
        body: { email, password },
      });
      if (error || !data) {
        throw new Error(apiErrorMessageFor(response, error, 'Invalid email or password.'));
      }
      applyAuth(data);
    },
    [applyAuth],
  );

  const register = useCallback(
    async (email: string, password: string, displayName?: string) => {
      const { data, error, response } = await api.POST('/api/auth/register', {
        body: { email, password, displayName: displayName || null },
      });
      if (error || !data) {
        throw new Error(apiErrorMessageFor(response, error, 'Could not create the account.'));
      }
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

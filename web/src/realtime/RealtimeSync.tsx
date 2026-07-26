import { useEffect } from 'react';
import { HubConnectionBuilder, LogLevel } from '@microsoft/signalr';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../auth/AuthContext';
import { tokenStore } from '../auth/tokenStore';
import { refreshAccessToken } from '../api/client';
import { NOTES_KEY } from '../features/notes/queries';
import { LISTS_KEY } from '../features/lists/queries';
import { NOTIFICATIONS_KEY } from '../features/notifications/queries';
import { SETTINGS_KEY } from '../features/settings/queries';

/**
 * Resource names the server sends on `Changed`. Must mirror `RealtimeResources` in the backend
 * (keepITCore/SignalR/RealtimeNotifier.cs) — and they're mapped to TanStack Query keys below.
 */
const RESOURCE_QUERY_KEY: Record<string, string> = {
  notes: NOTES_KEY,
  lists: LISTS_KEY,
  notification: NOTIFICATIONS_KEY,
  settings: SETTINGS_KEY,
};

/**
 * Bridges the SignalR realtime hub to TanStack Query. While signed in, holds one authenticated
 * WebSocket to `/api/realtime` and, when the server reports the user's data changed on another
 * device, invalidates the matching queries so this device refetches. Renders nothing.
 *
 * Mutations still go through REST + optimistic updates; this only keeps *other* open devices in
 * sync — and the device that made the change harmlessly re-validates (TanStack dedupes in-flight).
 */
/** Exponential backoff with jitter, capped at 30s — used for both reconnects and restarts. */
function backoffMs(attempt: number): number {
  return Math.min(30_000, 1_000 * 2 ** Math.min(attempt, 5)) * (0.75 + Math.random() * 0.5);
}

export function RealtimeSync() {
  const { status } = useAuth();
  const qc = useQueryClient();

  useEffect(() => {
    if (status !== 'authenticated') return;

    const connection = new HubConnectionBuilder()
      .withUrl('/api/realtime', {
        // The access token rides as a query-string param (browsers can't set WS headers); refresh
        // it first if it's about to expire so the negotiate/connect carries a valid token.
        accessTokenFactory: async () => {
          if (tokenStore.isExpiringSoon()) await refreshAccessToken();
          return tokenStore.token ?? '';
        },
      })
      // Never stop trying. The default schedule gives up after ~45s, which permanently kills
      // realtime for this tab — and with `refetchOnWindowFocus: false` there is no fallback, so a
      // laptop resumed from sleep would show stale notes until the next mutation.
      .withAutomaticReconnect({ nextRetryDelayInMilliseconds: (ctx) => backoffMs(ctx.previousRetryCount) })
      .configureLogging(LogLevel.Warning)
      .build();

    const invalidate = (keys: Iterable<string>) => {
      for (const key of new Set(keys)) {
        void qc.invalidateQueries({ queryKey: [key] });
      }
    };
    /** Any gap in the connection may have dropped pushes — refetch everything to catch up. */
    const resync = () => invalidate([NOTES_KEY, LISTS_KEY, NOTIFICATIONS_KEY, SETTINGS_KEY]);

    connection.on('Changed', (resources: string[]) => {
      invalidate(resources.map((r) => RESOURCE_QUERY_KEY[r]).filter(Boolean));
    });
    connection.onreconnected(resync);

    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempt = 0;
    let everConnected = false;

    // The initial start() is retried too: a single failure at mount (API restarting behind nginx,
    // negotiate briefly rate-limited) used to mean no realtime for the entire session.
    const start = async () => {
      if (stopped) return;
      try {
        await connection.start();
        attempt = 0;
        if (everConnected) resync();
        everConnected = true;
      } catch (err) {
        if (stopped) return;
        console.warn('Realtime connection failed, retrying:', err);
        timer = setTimeout(() => void start(), backoffMs(attempt++));
      }
    };

    // Fires when the connection drops outright, or when automatic reconnect finally gives up.
    connection.onclose(() => {
      if (!stopped) timer = setTimeout(() => void start(), backoffMs(attempt++));
    });

    void start();

    return () => {
      stopped = true;
      clearTimeout(timer);
      void connection.stop();
    };
  }, [status, qc]);

  return null;
}

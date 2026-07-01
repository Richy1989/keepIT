import { useEffect } from 'react';
import { HubConnectionBuilder, LogLevel } from '@microsoft/signalr';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../auth/AuthContext';
import { tokenStore } from '../auth/tokenStore';
import { refreshAccessToken } from '../api/client';
import { NOTES_KEY } from '../features/notes/queries';
import { LISTS_KEY } from '../features/lists/queries';
import { NOTIFICATIONS_KEY } from '../features/notifications/queries';

/**
 * Resource names the server sends on `Changed`. Must mirror `RealtimeResources` in the backend
 * (keepITCore/SignalR/RealtimeNotifier.cs) — and they're mapped to TanStack Query keys below.
 */
const RESOURCE_QUERY_KEY: Record<string, string> = {
  notes: NOTES_KEY,
  lists: LISTS_KEY,
  notification: NOTIFICATIONS_KEY,
};

/**
 * Bridges the SignalR realtime hub to TanStack Query. While signed in, holds one authenticated
 * WebSocket to `/api/realtime` and, when the server reports the user's data changed on another
 * device, invalidates the matching queries so this device refetches. Renders nothing.
 *
 * Mutations still go through REST + optimistic updates; this only keeps *other* open devices in
 * sync — and the device that made the change harmlessly re-validates (TanStack dedupes in-flight).
 */
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
      .withAutomaticReconnect()
      .configureLogging(LogLevel.Warning)
      .build();

    const invalidate = (keys: Iterable<string>) => {
      for (const key of new Set(keys)) {
        void qc.invalidateQueries({ queryKey: [key] });
      }
    };

    connection.on('Changed', (resources: string[]) => {
      invalidate(resources.map((r) => RESOURCE_QUERY_KEY[r]).filter(Boolean));
    });

    // A reconnect means we were offline and may have missed pushes — refetch everything to resync.
    connection.onreconnected(() => invalidate([NOTES_KEY, LISTS_KEY, NOTIFICATIONS_KEY]));

    let stopped = false;
    connection.start().catch((err) => {
      if (!stopped) console.error('Realtime connection failed:', err);
    });

    return () => {
      stopped = true;
      void connection.stop();
    };
  }, [status, qc]);

  return null;
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { NOTES_KEY } from '../notes/queries';
import { LISTS_KEY } from '../lists/queries';

/** TanStack Query key for the caller's notifications. Mirrors the backend realtime resource name. */
export const NOTIFICATIONS_KEY = 'notifications';

/** Loads the caller's notifications, newest first (share invites + system messages). */
export function useNotifications() {
  return useQuery({
    queryKey: [NOTIFICATIONS_KEY],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/notifications');
      if (error) throw new Error('Failed to load notifications.');
      return data ?? [];
    },
  });
}

/**
 * Answers a share invite: accept (grant access — the note appears in your grid) or decline. Either
 * way the invite is consumed, so we refresh notifications and, on accept, the notes/lists caches.
 */
export function useRespondToShare() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, accept }: { id: string; accept: boolean }) => {
      const { error } = await api.POST('/api/notifications/{id}/respond', {
        params: { path: { id } },
        body: { accept },
      });
      if (error) throw new Error('Failed to respond to the invite.');
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: [NOTIFICATIONS_KEY] });
      void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
      void qc.invalidateQueries({ queryKey: [LISTS_KEY] });
    },
  });
}

/**
 * Marks all notifications as read (clears the unread badge). The rows stay listed — and a share
 * invite stays answerable — until dismissed; only `isActive` flips.
 */
export function useMarkNotificationsRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const { error } = await api.POST('/api/notifications/mark-read');
      if (error) throw new Error('Failed to mark notifications read.');
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: [NOTIFICATIONS_KEY] }),
  });
}

/** Dismisses (permanently deletes) a notification. */
export function useDismissNotification() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await api.DELETE('/api/notifications/{id}', { params: { path: { id } } });
      if (error) throw new Error('Failed to dismiss the notification.');
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: [NOTIFICATIONS_KEY] }),
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { apiErrorMessage } from '../../lib/apiError';
import { NOTES_KEY } from './queries';
import type { PublicLinkDto, UpsertPublicLinkDto } from '../../api/types';

/**
 * Prefix of every note's public-link key. Not under the notes key, whose entries are all note
 * lists; realtime invalidates it on a `notes` push instead, which publishing sends, so the owner's
 * other devices see the change too.
 */
export const PUBLIC_LINK_KEY = 'note-public-link';

/** TanStack Query key for one note's public link (ARCHITECTURE.md "Public links"). */
export const publicLinkKey = (noteId: string) => [PUBLIC_LINK_KEY, noteId] as const;

/** Loads the note's public link; `null` when the note isn't published. Owner-only. */
export function usePublicLink(noteId: string, enabled = true) {
  return useQuery({
    queryKey: publicLinkKey(noteId),
    enabled,
    queryFn: async (): Promise<PublicLinkDto | null> => {
      const { data, error, response } = await api.GET('/api/notes/{noteId}/public-link', {
        params: { path: { noteId } },
      });
      if (error) throw new Error('Failed to load the public link.');
      return response.status === 204 ? null : (data ?? null);
    },
  });
}

/** Publishes the note, or changes how it is published. */
export function useUpsertPublicLink(noteId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpsertPublicLinkDto) => {
      const { data, error } = await api.PUT('/api/notes/{noteId}/public-link', {
        params: { path: { noteId } },
        body,
      });
      if (error || !data) throw new Error(apiErrorMessage(error, 'Failed to publish the note.'));
      return data;
    },
    onSuccess: (link) => {
      qc.setQueryData(publicLinkKey(noteId), link);
      void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
    },
  });
}

/** Takes the note off the internet. */
export function useDeletePublicLink(noteId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const { error } = await api.DELETE('/api/notes/{noteId}/public-link', {
        params: { path: { noteId } },
      });
      if (error) throw new Error('Failed to stop sharing the link.');
    },
    onSuccess: () => {
      qc.setQueryData(publicLinkKey(noteId), null);
      void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
    },
  });
}

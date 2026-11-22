import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { apiErrorMessage } from '../../lib/apiError';
import type { PublicNoteDto, UpdateNoteDto } from '../../api/types';
import type { MediaSize } from './media/queries';

/**
 * A visitor's view of a public link (ARCHITECTURE.md "Public links"), without an account. The token
 * travels in the request body only, never in a URL; opening returns the note and a view ticket,
 * which reading attachments and saving edits carry in the `X-View-Ticket` header.
 */
export const PUBLIC_NOTE_KEY = 'public-note';

/** Why a link opened nothing: it needs a password (or that one was wrong), or it opens nothing at all. */
export class PublicLinkError extends Error {
  readonly reason: 'password' | 'gone';

  constructor(reason: 'password' | 'gone') {
    super(reason === 'password' ? 'This note needs a password.' : "This link doesn't open anything.");
    this.reason = reason;
  }
}

export const publicNoteKey = (token: string, password: string | null) =>
  [PUBLIC_NOTE_KEY, token, password ?? ''] as const;

/**
 * Opens the link. Re-opened every so often (and when the tab comes back into view) so a visitor
 * sees the owner's changes and always holds a fresh ticket; `paused` stops that while an edit is
 * in flight, so a refetch can't put back what the visitor just typed.
 */
export function usePublicNote(token: string, password: string | null, paused: boolean) {
  return useQuery({
    queryKey: publicNoteKey(token, password),
    queryFn: async (): Promise<PublicNoteDto> => {
      const { data, response } = await api.POST('/api/public/open', {
        body: { token, password },
      });
      if (response.status === 401) throw new PublicLinkError('password');
      if (response.status === 404) throw new PublicLinkError('gone');
      if (!response.ok || !data) throw new Error('The note could not be loaded.');
      return data;
    },
    retry: (count, error) => !(error instanceof PublicLinkError) && count < 2,
    refetchInterval: paused ? false : 30_000,
    refetchOnWindowFocus: !paused,
    staleTime: 10_000,
  });
}

/**
 * One attachment of the opened note, as a Blob. Keyed without the ticket, which changes on every
 * re-open while the bytes never do, so a re-open costs no download.
 */
export function usePublicMediaBlob(token: string, ticket: string, mediaId: string, size: MediaSize) {
  return useQuery({
    queryKey: [PUBLIC_NOTE_KEY, token, 'media', mediaId, size],
    staleTime: Infinity,
    gcTime: 30 * 60 * 1000,
    queryFn: async () => {
      const { data, response } = await api.GET('/api/public/media/{mediaId}', {
        params: { path: { mediaId }, query: { size } },
        headers: { 'X-View-Ticket': ticket },
        parseAs: 'blob',
      });
      if (!response.ok) throw new Error('Failed to load the attachment.');
      return (data as Blob) ?? null;
    },
  });
}

/**
 * Saves a visitor's edit through an editable link: optimistic, rolled back if the server refuses.
 * The answer is the note as it now is, with a fresh ticket, and replaces the cached view.
 */
export function useSavePublicNote(token: string, password: string | null) {
  const qc = useQueryClient();
  const key = publicNoteKey(token, password);
  return useMutation({
    mutationFn: async ({ ticket, body }: { ticket: string; body: UpdateNoteDto }) => {
      const { data, error, response } = await api.PUT('/api/public/note', {
        body,
        headers: { 'X-View-Ticket': ticket },
      });
      if (response.status === 403 || response.status === 404)
        throw new Error('This link no longer lets you edit. Reload the page to see the note as it is.');
      if (error || !data) throw new Error(apiErrorMessage(error, 'Your change could not be saved.'));
      return data;
    },
    onMutate: async ({ body }) => {
      await qc.cancelQueries({ queryKey: key });
      const previous = qc.getQueryData<PublicNoteDto>(key);
      if (previous) {
        const fields = new Set(body.fields ?? []);
        qc.setQueryData<PublicNoteDto>(key, {
          ...previous,
          title: fields.has('Title') ? body.title : previous.title,
          body: fields.has('Body') ? body.body : previous.body,
          checklistItems: fields.has('ChecklistItems') ? (body.checklistItems ?? []) : previous.checklistItems,
        });
      }
      return { previous };
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) qc.setQueryData(key, context.previous);
    },
    onSuccess: (note) => qc.setQueryData(key, note),
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { apiErrorMessage } from '../../lib/apiError';
import type { NoteRole } from '../../api/types';

/** TanStack Query key for one note's collaborator list. */
export const noteSharesKey = (noteId: string) => ['note-shares', noteId] as const;

/** Loads the collaborators on a note (owner or any collaborator may read this). */
export function useNoteShares(noteId: string, enabled = true) {
  return useQuery({
    queryKey: noteSharesKey(noteId),
    enabled,
    queryFn: async () => {
      const { data, error } = await api.GET('/api/notes/{noteId}/shares', {
        params: { path: { noteId } },
      });
      if (error) throw new Error('Failed to load collaborators.');
      return data ?? [];
    },
  });
}

/** Invites a user (by email) to collaborate on a note. Surfaces the server's message on failure. */
export function useCreateShare(noteId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ email, role }: { email: string; role: NoteRole }) => {
      const { error } = await api.POST('/api/notes/{noteId}/shares', {
        params: { path: { noteId } },
        body: { email, role },
      });
      if (error) throw new Error(apiErrorMessage(error, 'Failed to send the invite.'));
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: noteSharesKey(noteId) }),
  });
}

/** Changes a collaborator's role on a note. */
export function useUpdateShareRole(noteId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ granteeId, role }: { granteeId: string; role: NoteRole }) => {
      const { error } = await api.PATCH('/api/notes/{noteId}/shares/{granteeId}', {
        params: { path: { noteId, granteeId } },
        body: { role },
      });
      if (error) throw new Error('Failed to change the role.');
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: noteSharesKey(noteId) }),
  });
}

/** Revokes a collaborator's access to a note. */
export function useRevokeShare(noteId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (granteeId: string) => {
      const { error } = await api.DELETE('/api/notes/{noteId}/shares/{granteeId}', {
        params: { path: { noteId, granteeId } },
      });
      if (error) throw new Error('Failed to remove the collaborator.');
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: noteSharesKey(noteId) }),
  });
}

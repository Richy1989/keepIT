import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
import { api } from '../../api/client';
import { LISTS_KEY } from '../lists/queries';
import type { CreateNoteDto, NoteDto, NoteStateDto, UpdateNoteDto } from '../../api/types';

/** Which slice of notes the grid is showing. */
export type NotesView = 'active' | 'archived' | 'trashed';

/** The grid's current filter — a view plus an optional set of list ids (union). */
export interface NotesFilter {
  view: NotesView;
  listIds: string[];
}

export const NOTES_KEY = 'notes';

/** React Query key for a given filter. List ids are sorted so key identity is order-independent. */
export const notesQueryKey = (f: NotesFilter) =>
  [NOTES_KEY, f.view, [...f.listIds].sort()] as const;

/** Pinned first, then most recently updated — matches the server ordering. */
function sortNotes(a: NoteDto, b: NoteDto): number {
  if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1;
  return b.updatedAtUtc.localeCompare(a.updatedAtUtc);
}

/** Whether a note belongs in a cache identified by (view, listIds). Drives optimistic placement. */
function belongsToView(n: NoteDto, view: NotesView, listIds: string[]): boolean {
  if (view === 'trashed') return n.isTrashed;
  if (n.isTrashed) return false;
  if (view === 'archived') return n.isArchived;
  if (n.isArchived) return false; // active view
  if (listIds.length && !listIds.some((id) => n.listIds.includes(id))) return false;
  return true;
}

/**
 * Reconciles one note across every cached notes list: removes it everywhere, then re-inserts the
 * patched version into the caches whose view it now matches (and re-sorts). Pass `null` to delete.
 */
function reconcileNote(qc: QueryClient, noteId: string, patched: NoteDto | null): void {
  for (const [key, data] of qc.getQueriesData<NoteDto[]>({ queryKey: [NOTES_KEY] })) {
    if (!data) continue;
    const view = key[1] as NotesView;
    const listIds = (key[2] as string[]) ?? [];
    let next = data.filter((n) => n.id !== noteId);
    if (patched && belongsToView(patched, view, listIds)) {
      next = [...next, patched].sort(sortNotes);
    }
    qc.setQueryData(key, next);
  }
}

/** Snapshots all notes caches for rollback. */
function snapshotNotes(qc: QueryClient) {
  return qc.getQueriesData<NoteDto[]>({ queryKey: [NOTES_KEY] });
}

/** Restores a snapshot taken by {@link snapshotNotes}. */
function restoreNotes(qc: QueryClient, snap: ReturnType<typeof snapshotNotes>): void {
  for (const [key, data] of snap) qc.setQueryData(key, data);
}

/** Invalidates notes (and lists, whose counts may have changed) after a mutation settles. */
function invalidateAfter(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
  void qc.invalidateQueries({ queryKey: [LISTS_KEY] });
}

/** Loads the notes for a filter. */
export function useNotes(filter: NotesFilter) {
  return useQuery({
    queryKey: notesQueryKey(filter),
    queryFn: async () => {
      const { data, error } = await api.GET('/api/notes', {
        params: {
          query: {
            archived: filter.view === 'archived',
            trashed: filter.view === 'trashed',
            listId: filter.listIds.length ? filter.listIds : undefined,
          },
        },
      });
      if (error) throw new Error('Failed to load notes.');
      return data ?? [];
    },
  });
}

/**
 * A throwaway client-side id for the optimistic note. `crypto.randomUUID()` only exists in secure
 * contexts (https or localhost), so over plain-http LAN (e.g. a self-hosted box on its IP) it's
 * undefined and would throw in onMutate — before the POST is ever sent. This falls back to a
 * non-crypto id, which is fine for a temporary placeholder that the server's real id replaces.
 */
function makeTempId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/** Creates a note, shown optimistically in the active view until the server confirms. */
export function useCreateNote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateNoteDto) => {
      const { data, error } = await api.POST('/api/notes', { body: input });
      if (error || !data) throw new Error('Failed to create note.');
      return data;
    },
    onMutate: async (input) => {
      await qc.cancelQueries({ queryKey: [NOTES_KEY] });
      const snapshot = snapshotNotes(qc);
      const tempId = `temp-${makeTempId()}`;
      const now = new Date().toISOString();
      const optimistic: NoteDto = {
        id: tempId,
        type: input.type ?? 'Text',
        title: input.title ?? null,
        body: input.body ?? null,
        color: input.color ?? null,
        isPinned: false,
        isArchived: false,
        isTrashed: false,
        createdAtUtc: now,
        updatedAtUtc: now,
        checklistItems: input.checklistItems ?? [],
        listIds: input.listIds ?? [],
      };
      reconcileNote(qc, tempId, optimistic);
      return { snapshot, tempId };
    },
    onError: (_e, _v, ctx) => ctx && restoreNotes(qc, ctx.snapshot),
    onSuccess: (created, _v, ctx) => {
      if (ctx) reconcileNote(qc, ctx.tempId, null); // drop the temp; real note arrives on invalidate
      reconcileNote(qc, created.id, created);
    },
    onSettled: () => invalidateAfter(qc),
  });
}

/** Replaces a note's editable content (title/body/color/type/checklist), updated optimistically. */
export function useUpdateNote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: UpdateNoteDto }) => {
      const { data, error } = await api.PUT('/api/notes/{id}', {
        params: { path: { id } },
        body,
      });
      if (error || !data) throw new Error('Failed to save note.');
      return data;
    },
    onMutate: async ({ id, body }) => {
      await qc.cancelQueries({ queryKey: [NOTES_KEY] });
      const snapshot = snapshotNotes(qc);
      const current = qc
        .getQueriesData<NoteDto[]>({ queryKey: [NOTES_KEY] })
        .flatMap(([, data]) => data ?? [])
        .find((n) => n.id === id);
      if (current) {
        reconcileNote(qc, id, {
          ...current,
          type: body.type,
          title: body.title ?? null,
          body: body.body ?? null,
          color: body.color ?? null,
          checklistItems: body.checklistItems ?? [],
          updatedAtUtc: new Date().toISOString(),
        });
      }
      return { snapshot };
    },
    onError: (_e, _v, ctx) => ctx && restoreNotes(qc, ctx.snapshot),
    onSettled: () => invalidateAfter(qc),
  });
}

/** Toggles pin / archive / trash, reflected instantly across the affected views. */
export function useSetNoteState() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, state }: { id: string; state: NoteStateDto }) => {
      const { data, error } = await api.PATCH('/api/notes/{id}/state', {
        params: { path: { id } },
        body: state,
      });
      if (error || !data) throw new Error('Failed to update note.');
      return data;
    },
    onMutate: async ({ id, state }) => {
      await qc.cancelQueries({ queryKey: [NOTES_KEY] });
      const snapshot = snapshotNotes(qc);
      // Find the current note in any cache to build the optimistic next version.
      const current = qc
        .getQueriesData<NoteDto[]>({ queryKey: [NOTES_KEY] })
        .flatMap(([, data]) => data ?? [])
        .find((n) => n.id === id);
      if (current) {
        reconcileNote(qc, id, {
          ...current,
          isPinned: state.isPinned ?? current.isPinned,
          isArchived: state.isArchived ?? current.isArchived,
          isTrashed: state.isTrashed ?? current.isTrashed,
          updatedAtUtc: new Date().toISOString(),
        });
      }
      return { snapshot };
    },
    onError: (_e, _v, ctx) => ctx && restoreNotes(qc, ctx.snapshot),
    onSettled: () => invalidateAfter(qc),
  });
}

/** Replaces the set of lists a note belongs to (for the current user). */
export function useSetNoteLists() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, listIds }: { id: string; listIds: string[] }) => {
      const { data, error } = await api.PUT('/api/notes/{id}/lists', {
        params: { path: { id } },
        body: { listIds },
      });
      if (error || !data) throw new Error('Failed to update lists.');
      return data;
    },
    onSettled: () => invalidateAfter(qc),
  });
}

/** Permanently deletes a note (used to purge from trash). */
export function useDeleteNote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await api.DELETE('/api/notes/{id}', { params: { path: { id } } });
      if (error) throw new Error('Failed to delete note.');
    },
    onMutate: async (id) => {
      await qc.cancelQueries({ queryKey: [NOTES_KEY] });
      const snapshot = snapshotNotes(qc);
      reconcileNote(qc, id, null);
      return { snapshot };
    },
    onError: (_e, _v, ctx) => ctx && restoreNotes(qc, ctx.snapshot),
    onSettled: () => invalidateAfter(qc),
  });
}

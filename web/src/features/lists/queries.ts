import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { CreateListDto, ListDto, UpdateListDto } from '../../api/types';

export const LISTS_KEY = 'lists';

/** Loads the caller's lists (with note counts) for the sidebar. */
export function useLists() {
  return useQuery({
    queryKey: [LISTS_KEY],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/lists');
      if (error) throw new Error('Failed to load lists.');
      return data ?? [];
    },
  });
}

/** Creates a list. */
export function useCreateList() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateListDto) => {
      const { data, error } = await api.POST('/api/lists', { body });
      if (error || !data) throw new Error('Failed to create list.');
      return data;
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: [LISTS_KEY] }),
  });
}

/** Renames / recolors a list. */
export function useUpdateList() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: UpdateListDto }) => {
      const { data, error } = await api.PATCH('/api/lists/{id}', {
        params: { path: { id } },
        body,
      });
      if (error || !data) throw new Error('Failed to update list.');
      return data;
    },
    onMutate: async ({ id, body }) => {
      await qc.cancelQueries({ queryKey: [LISTS_KEY] });
      const prev = qc.getQueryData<ListDto[]>([LISTS_KEY]);
      if (prev) {
        qc.setQueryData<ListDto[]>(
          [LISTS_KEY],
          prev.map((l) =>
            l.id === id ? { ...l, name: body.name ?? l.name, color: body.color ?? l.color } : l,
          ),
        );
      }
      return { prev };
    },
    onError: (_e, _v, ctx) => ctx?.prev && qc.setQueryData([LISTS_KEY], ctx.prev),
    onSettled: () => void qc.invalidateQueries({ queryKey: [LISTS_KEY] }),
  });
}

/** Deletes a list (notes survive; only the membership rows are dropped). */
export function useDeleteList() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await api.DELETE('/api/lists/{id}', { params: { path: { id } } });
      if (error) throw new Error('Failed to delete list.');
    },
    onMutate: async (id) => {
      await qc.cancelQueries({ queryKey: [LISTS_KEY] });
      const prev = qc.getQueryData<ListDto[]>([LISTS_KEY]);
      if (prev) qc.setQueryData<ListDto[]>([LISTS_KEY], prev.filter((l) => l.id !== id));
      return { prev };
    },
    onError: (_e, _v, ctx) => ctx?.prev && qc.setQueryData([LISTS_KEY], ctx.prev),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: [LISTS_KEY] });
      void qc.invalidateQueries({ queryKey: ['notes'] }); // note.listIds may have changed
    },
  });
}

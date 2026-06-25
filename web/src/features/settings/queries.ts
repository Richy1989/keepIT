import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { UserSettingsDto } from '../../api/types';

export const SETTINGS_KEY = 'settings';

/** Loads the caller's UI settings (theme, accent). Only runs once authenticated. */
export function useUserSettings(enabled: boolean) {
  return useQuery({
    queryKey: [SETTINGS_KEY],
    enabled,
    staleTime: Infinity, // settings change only via our own mutation
    queryFn: async () => {
      const { data, error } = await api.GET('/api/settings');
      if (error || !data) throw new Error('Failed to load settings.');
      return data;
    },
  });
}

/** Persists the caller's settings (theme + accent), with an optimistic cache update. */
export function useUpdateSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UserSettingsDto) => {
      const { data, error } = await api.PUT('/api/settings', { body });
      if (error || !data) throw new Error('Failed to save settings.');
      return data;
    },
    onMutate: async (body) => {
      await qc.cancelQueries({ queryKey: [SETTINGS_KEY] });
      const prev = qc.getQueryData<UserSettingsDto>([SETTINGS_KEY]);
      qc.setQueryData<UserSettingsDto>([SETTINGS_KEY], (old) => ({ ...old, ...body }));
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData([SETTINGS_KEY], ctx.prev);
    },
    onSuccess: (data) => qc.setQueryData([SETTINGS_KEY], data),
  });
}

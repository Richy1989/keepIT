import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { tokenStore } from '../../auth/tokenStore';

export const PROFILE_IMAGE_KEY = 'profileImage';

/**
 * Changes the signed-in user's password. On success the backend revokes the user's other refresh
 * tokens and returns a fresh access token for this device, which we store so subsequent requests
 * keep working seamlessly. The raw error body is thrown so the caller can surface server messages.
 */
export function useChangePassword() {
  return useMutation({
    mutationFn: async (body: { currentPassword: string; newPassword: string }) => {
      const { data, error } = await api.POST('/api/auth/changepassword', { body });
      if (error || !data) throw error ?? new Error('Failed to change password.');
      return data;
    },
    onSuccess: (data) => {
      if (data.accessToken && data.accessTokenExpiresAtUtc) {
        tokenStore.set(data.accessToken, data.accessTokenExpiresAtUtc);
      }
    },
  });
}

/**
 * Fetches the user's profile image as a Blob (the endpoint is authenticated, so it can't be used
 * directly as an <img src>). Returns null when the user has no image (404). The caller turns the
 * Blob into an object URL.
 */
export function useProfileImage(userId: string | undefined) {
  return useQuery({
    queryKey: [PROFILE_IMAGE_KEY, userId],
    enabled: !!userId,
    staleTime: Infinity, // only changes via our own upload, which invalidates this
    queryFn: async () => {
      const { data, response } = await api.GET('/api/settings/getProfileImage/{userId}', {
        params: { path: { userId: userId! } },
        parseAs: 'blob',
      });
      if (!response.ok) return null; // 404 → no image set
      return (data as Blob) ?? null;
    },
  });
}

/** Uploads a new profile image (multipart) and refreshes the cached image on success. */
export function useUploadProfileImage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (file: File) => {
      const { data, error } = await api.POST('/api/settings/uploadProfileImage', {
        body: { file: file as unknown as string },
        bodySerializer: () => {
          const form = new FormData();
          form.append('file', file);
          return form;
        },
      });
      if (error) throw error;
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: [PROFILE_IMAGE_KEY] }),
  });
}

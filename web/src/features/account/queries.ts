import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/client';
import { tokenStore } from '../../auth/tokenStore';

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

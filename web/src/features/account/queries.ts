import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { tokenStore } from '../../auth/tokenStore';
import { useAuth } from '../../auth/AuthContext';
import { apiErrorMessageFor } from '../../lib/apiError';

export const PROFILE_IMAGE_KEY = 'profileImage';
/** The signed-in user's two-factor status; the server's `account` push invalidates it. */
export const TWO_FACTOR_KEY = 'twoFactor';

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
 * Deletes the signed-in user's account and everything they own. The password goes with it; a
 * wrong one comes back as a 400 whose message the caller shows. Once it succeeds the server has
 * ended every session and cleared the refresh cookie, so the caller only drops the local session.
 */
export function useDeleteAccount() {
  return useMutation({
    mutationFn: async (password: string) => {
      const { error, response } = await api.POST('/api/auth/delete-account', { body: { password } });
      if (error || !response.ok) throw error ?? new Error('Could not delete the account.');
    },
  });
}

/**
 * Changes (or, with blank text, removes) the signed-in user's display name. The answer is the
 * updated user, applied straight to the auth context; the server's `account` push brings the
 * user's other devices along. The raw error body is thrown so the caller can surface server messages.
 */
export function useUpdateDisplayName() {
  const { updateUser } = useAuth();
  return useMutation({
    mutationFn: async (displayName: string) => {
      const { data, error } = await api.PUT('/api/auth/me', {
        body: { displayName: displayName.trim() || null },
      });
      if (error || !data) throw error ?? new Error('Failed to save the display name.');
      return data;
    },
    onSuccess: updateUser,
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
      // Only a 404 means "no image". Swallowing 4xx/5xx here would cache `null` under
      // staleTime: Infinity, silently pinning the avatar to the initial for the whole session.
      if (response.status === 404) return null;
      if (!response.ok) throw new Error('Failed to load the profile image.');
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

/**
 * The two-factor hooks fail with an `Error` whose message is ready to show: the server's own words,
 * or, for an answer without a body (the sign-in rate limit's 429), one made from the status —
 * stepping through a setup with a couple of mistyped codes can reach that limit.
 */
function failure(response: Response, error: unknown, fallback: string): Error {
  return new Error(apiErrorMessageFor(response, error, fallback));
}

/** Whether the signed-in user's account asks for an authenticator code, and how many recovery codes are left. */
export function useTwoFactorStatus() {
  return useQuery({
    queryKey: [TWO_FACTOR_KEY],
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/auth/two-factor');
      if (error || !data) throw failure(response, error, 'Could not load the two-factor status.');
      return data;
    },
  });
}

/**
 * Starts setting up an authenticator app: the password goes in, a new key comes back as a QR
 * code and as text. Sign-in is unchanged until {@link useEnableTwoFactor} confirms a code.
 */
export function useStartTwoFactorSetup() {
  return useMutation({
    mutationFn: async (password: string) => {
      const { data, error, response } = await api.POST('/api/auth/two-factor/setup', { body: { password } });
      if (error || !data) throw failure(response, error, 'Could not start the setup.');
      return data;
    },
  });
}

/** Turns two-factor on with a code from the app just set up; the answer is the first recovery codes. */
export function useEnableTwoFactor() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (code: string) => {
      const { data, error, response } = await api.POST('/api/auth/two-factor/enable', { body: { code } });
      if (error || !data) throw failure(response, error, 'Could not turn on two-factor authentication.');
      return data.codes;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: [TWO_FACTOR_KEY] }),
  });
}

/** Turns two-factor off, with the password and a code from the app or a recovery code. */
export function useDisableTwoFactor() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { password: string; code: string }) => {
      const { error, response } = await api.POST('/api/auth/two-factor/disable', { body });
      if (error || !response.ok) throw failure(response, error, 'Could not turn off two-factor authentication.');
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: [TWO_FACTOR_KEY] }),
  });
}

/** Replaces the recovery codes with a new set, shown once; the old ones stop working. */
export function useNewRecoveryCodes() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { password: string; code: string }) => {
      const { data, error, response } = await api.POST('/api/auth/two-factor/recovery-codes', { body });
      if (error || !data) throw failure(response, error, 'Could not make new recovery codes.');
      return data.codes;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: [TWO_FACTOR_KEY] }),
  });
}

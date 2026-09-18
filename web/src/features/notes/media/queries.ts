import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../api/client';
import { NOTES_KEY } from '../queries';
import type { NoteMediaDto } from '../../../api/types';

export const MEDIA_KEY = 'note-media';

/**
 * Which rendition to fetch: the 400 px thumbnail for small tiles, the card-sized preview (at most
 * 1280 px) for a note card's hero, or the stored original.
 */
export type MediaSize = 'thumb' | 'preview' | 'full';

/**
 * The server's own cap (App:Media:MaxImageBytes). Checked client-side before sending because the
 * API answers an over-sized upload before draining the request body — which is correct, but means a
 * large file can surface as a connection reset rather than the 413's message. Checking here turns
 * that into an instant, specific error and saves the upload entirely.
 */
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

/** How many images one note may hold; mirrors App:Media:MaxImagesPerNote. */
export const MAX_IMAGES_PER_NOTE = 10;

/** The formats the API accepts — HEIC is deliberately absent and is rejected by name server-side. */
export const ACCEPTED_IMAGE_TYPES = 'image/jpeg,image/png,image/webp,image/gif';

/**
 * Fetches one image as a Blob. The endpoint is authenticated, so it can't be used directly as an
 * <img src>; the caller turns the Blob into an object URL with `useObjectUrl`.
 *
 * `staleTime: Infinity` is safe in a way it isn't for avatars: a media id's bytes never change, so
 * there is no version of this image the cache could be wrong about.
 */
export function useNoteMediaBlob(noteId: string, mediaId: string, size: MediaSize) {
  return useQuery({
    queryKey: [MEDIA_KEY, noteId, mediaId, size],
    staleTime: Infinity,
    gcTime: 30 * 60 * 1000,
    queryFn: async () => {
      const { data, response } = await api.GET('/api/notes/{noteId}/media/{mediaId}', {
        params: { path: { noteId, mediaId }, query: { size } },
        parseAs: 'blob',
      });
      // Throw on any non-OK: caching `null` under staleTime: Infinity would pin this image to a
      // broken state for the rest of the session.
      if (!response.ok) throw new Error('Failed to load the image.');
      return (data as Blob) ?? null;
    },
  });
}

/** Attaches one image to a note, then refreshes the notes cache so the new media id appears. */
export function useUploadNoteMedia() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ noteId, file }: { noteId: string; file: File }) => {
      if (file.size > MAX_IMAGE_BYTES) {
        throw new Error(
          `${file.name} is too large (max ${MAX_IMAGE_BYTES / (1024 * 1024)} MB).`,
        );
      }
      const { data, error } = await api.POST('/api/notes/{noteId}/media', {
        params: { path: { noteId } },
        body: { file: file as unknown as string },
        bodySerializer: () => {
          const form = new FormData();
          form.append('file', file);
          return form;
        },
      });
      if (error) throw new Error(typeof error === 'string' ? error : 'Failed to attach the image.');
      return data as NoteMediaDto;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
    },
  });
}

/** Removes one image from a note. */
export function useDeleteNoteMedia() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ noteId, mediaId }: { noteId: string; mediaId: string }) => {
      const { error } = await api.DELETE('/api/notes/{noteId}/media/{mediaId}', {
        params: { path: { noteId, mediaId } },
      });
      if (error) throw new Error('Failed to remove the image.');
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
    },
  });
}

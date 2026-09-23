import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { NOTES_KEY } from '../notes/queries';
import { LISTS_KEY } from '../lists/queries';
import { apiErrorMessageFor } from '../../lib/apiError';
import type { ImportResultDto } from '../../api/types';

/** The server's cap on an uploaded archive; mirrors `ImportController.MaxArchiveBytes`. */
export const MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;

/** Fallback when the response carries no usable Content-Disposition. */
const FALLBACK_FILE_NAME = 'keepit-export.zip';

/**
 * Reads the download's file name out of `Content-Disposition`. Same-origin, so the header is
 * readable; a proxy that strips or rewrites it just means the browser saves under the fallback
 * name rather than getting something wrong. Exported for its unit test — the header has enough
 * shapes (quoted, bare, `filename*=UTF-8''…`) that a regex for it deserves one.
 */
export function fileNameFrom(response: Response): string {
  const header = response.headers.get('content-disposition') ?? '';
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  return match?.[1] ? decodeURIComponent(match[1]) : FALLBACK_FILE_NAME;
}

/** Hands the blob to the browser as a download. */
function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoked on a delay: some browsers read the blob asynchronously after the click, and pulling
  // the URL out from under them saves a zero-byte file.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

/**
 * Downloads the caller's export archive.
 *
 * It has to go through `fetch` rather than a plain link: the access token lives in memory, not in
 * a cookie, so an `<a href>` would arrive unauthenticated. The client middleware attaches the
 * bearer token and refreshes it if needed, exactly as for any other request.
 */
export function useExportArchive() {
  return useMutation({
    mutationFn: async () => {
      const { data, error, response } = await api.GET('/api/export', { parseAs: 'blob' });
      if (error || !response.ok || !data) {
        throw new Error(
          apiErrorMessageFor(response, error, "Your export couldn't be prepared. Please try again."),
        );
      }

      const blob = data as Blob;
      saveBlob(blob, fileNameFrom(response));
      return blob.size;
    },
  });
}

/**
 * Uploads an archive and adds its contents to the caller's account.
 *
 * Import only ever adds, so there is nothing to roll back and nothing to update optimistically —
 * the caches are simply invalidated once the server reports what it created.
 */
export function useImportArchive() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (file: File) => {
      // Checked here because the API answers an over-sized upload before draining the body, which
      // can surface as a connection reset rather than the 413 it really is.
      if (file.size > MAX_ARCHIVE_BYTES) {
        throw new Error(
          `That file is too large (max ${MAX_ARCHIVE_BYTES / (1024 * 1024)} MB).`,
        );
      }

      const { data, error, response } = await api.POST('/api/import', {
        body: { file: file as unknown as string },
        bodySerializer: () => {
          const form = new FormData();
          form.append('file', file);
          return form;
        },
      });

      // A refused archive answers with a plain-text explanation naming what was wrong with it;
      // that is far more use than a generic message, so it is shown as-is.
      if (error || !data) {
        if (typeof error === 'string' && error.trim()) throw new Error(error);
        throw new Error(
          apiErrorMessageFor(response, error, "That archive couldn't be imported."),
        );
      }

      return data as ImportResultDto;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
      void qc.invalidateQueries({ queryKey: [LISTS_KEY] });
    },
  });
}

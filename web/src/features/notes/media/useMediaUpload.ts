import { useCallback, useEffect, useRef, useState } from 'react';
import { useUploadNoteMedia, ACCEPTED_IMAGE_TYPES } from './queries';

/** One locally-previewed file that hasn't finished uploading. */
export interface PendingUpload {
  id: string;
  url: string;
}

/** True for a file the API will accept — HEIC and non-images are filtered before we bother. */
export function isAcceptedImage(file: File): boolean {
  return ACCEPTED_IMAGE_TYPES.split(',').includes(file.type);
}

/**
 * Uploads files one at a time for a note, exposing local previews while they're in flight.
 *
 * Each file uploads independently so one rejected image (too large, wrong type) doesn't take the
 * rest of a multi-select down with it.
 */
export function useMediaUpload(noteId: string | null) {
  const upload = useUploadNoteMedia();
  const [pending, setPending] = useState<PendingUpload[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const urls = useRef<string[]>([]);

  // Previews are object URLs; revoke whatever is still outstanding when the editor closes.
  useEffect(() => {
    const outstanding = urls.current;
    return () => outstanding.forEach((u) => URL.revokeObjectURL(u));
  }, []);

  const addFiles = useCallback(
    async (files: File[]) => {
      if (!noteId) return;

      const rejected = files.filter((f) => !isAcceptedImage(f));
      if (rejected.length) {
        setErrors((prev) => [
          ...prev,
          `${rejected.map((f) => f.name).join(', ')}: only JPEG, PNG, WebP and GIF images can be attached.`,
        ]);
      }

      for (const file of files.filter(isAcceptedImage)) {
        const url = URL.createObjectURL(file);
        const id = crypto.randomUUID();
        urls.current.push(url);
        setPending((p) => [...p, { id, url }]);

        try {
          await upload.mutateAsync({ noteId, file });
        } catch (e) {
          const message = (e as { message?: string })?.message ?? `Couldn't upload ${file.name}.`;
          setErrors((prev) => [...prev, message]);
        } finally {
          setPending((p) => p.filter((x) => x.id !== id));
          URL.revokeObjectURL(url);
          urls.current = urls.current.filter((u) => u !== url);
        }
      }
    },
    [noteId, upload],
  );

  const dismissError = useCallback(
    (index: number) => setErrors((prev) => prev.filter((_, i) => i !== index)),
    [],
  );

  return { pending, errors, addFiles, dismissError };
}

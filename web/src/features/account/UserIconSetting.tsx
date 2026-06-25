import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import { Avatar } from '../../components/Avatar';
import { CameraIcon } from '../../components/icons';
import { apiErrorMessage } from '../../lib/apiError';
import { useUploadProfileImage } from './queries';

const MAX_SIZE = 2 * 1024 * 1024; // matches the backend limit
const ACCEPT = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];

/** Profile-picture control: choose an image, preview it locally, then upload to the backend. */
export function UserIconSetting() {
  const upload = useUploadProfileImage();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Local preview of the pending file (revoked when it changes / unmounts).
  const preview = useMemo(() => (file ? URL.createObjectURL(file) : null), [file]);
  useEffect(() => {
    return () => {
      if (preview) URL.revokeObjectURL(preview);
    };
  }, [preview]);

  function onPick(e: ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    e.target.value = ''; // allow re-picking the same file
    setError(null);
    if (!f) return;
    if (!ACCEPT.includes(f.type)) {
      setError('Unsupported file type. Use JPG, PNG, WEBP, or GIF.');
      return;
    }
    if (f.size > MAX_SIZE) {
      setError('Image is too large (max 2 MB).');
      return;
    }
    setFile(f);
  }

  async function save() {
    if (!file) return;
    setError(null);
    try {
      await upload.mutateAsync(file);
      setFile(null); // cleared → Avatar falls back to the freshly fetched server image
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not upload the image.'));
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-5">
      <div className="relative">
        <Avatar className="size-20 text-2xl" previewUrl={preview} />
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          title="Choose image"
          aria-label="Choose image"
          className="focus-ring absolute -bottom-1 -right-1 grid size-7 place-items-center rounded-full bg-accent text-black shadow-md shadow-black/30 transition hover:bg-accent-strong"
        >
          <CameraIcon className="text-sm" />
        </button>
        <input ref={inputRef} type="file" accept="image/*" className="hidden" onChange={onPick} />
      </div>

      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            className="focus-ring rounded-lg bg-surface px-3 py-1.5 text-sm text-text-muted transition hover:bg-surface-hover hover:text-text"
          >
            Choose image
          </button>
          {file && (
            <button
              type="button"
              onClick={() => setFile(null)}
              className="focus-ring rounded-lg px-3 py-1.5 text-sm text-text-muted transition hover:text-text"
            >
              Cancel
            </button>
          )}
          <button
            type="button"
            onClick={save}
            disabled={!file || upload.isPending}
            className="focus-ring rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-50"
          >
            {upload.isPending ? 'Uploading…' : 'Save'}
          </button>
        </div>
        {error && <p className="mt-2 text-xs text-rose-400">{error}</p>}
        <p className="mt-2 max-w-xs text-xs text-text-faint">JPG, PNG, WEBP, or GIF — up to 2 MB.</p>
      </div>
    </div>
  );
}

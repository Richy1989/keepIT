import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { CameraIcon } from '../../components/icons';

/**
 * Profile-picture ("user icon") control. The backend for storing avatars isn't ready yet, so a
 * chosen image is shown only as a local preview and "Save" is disabled — the UI is in place for
 * when media upload lands.
 */
export function UserIconSetting() {
  const { user } = useAuth();
  const inputRef = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<string | null>(null);

  const initial = (user?.displayName || user?.email || '?').charAt(0).toUpperCase();

  // Release the object URL when it changes or the component unmounts.
  useEffect(() => {
    if (!preview) return;
    return () => URL.revokeObjectURL(preview);
  }, [preview]);

  function onPick(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) setPreview(URL.createObjectURL(file));
    e.target.value = ''; // allow re-picking the same file
  }

  return (
    <div className="flex flex-wrap items-center gap-5">
      <div className="relative">
        <div className="grid size-20 place-items-center overflow-hidden rounded-full bg-elevated text-2xl font-semibold text-text-muted">
          {preview ? (
            <img src={preview} alt="" className="size-full object-cover" />
          ) : (
            initial
          )}
        </div>
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
          {preview && (
            <button
              type="button"
              onClick={() => setPreview(null)}
              className="focus-ring rounded-lg px-3 py-1.5 text-sm text-text-muted transition hover:text-text"
            >
              Remove
            </button>
          )}
          <button
            type="button"
            disabled
            title="Coming soon — avatar upload isn't available yet"
            className="cursor-not-allowed rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-black opacity-50"
          >
            Save
          </button>
        </div>
        <p className="mt-2 max-w-xs text-xs text-text-faint">
          Profile pictures aren't saved yet — this is a local preview while avatar upload is in
          progress.
        </p>
      </div>
    </div>
  );
}

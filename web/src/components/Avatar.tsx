import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { useProfileImage } from '../features/account/queries';
import { cn } from '../lib/cn';

/**
 * The signed-in user's avatar: their uploaded profile image, or their initial as a fallback. Pass
 * `previewUrl` to show a locally-selected image (e.g. before upload) instead of the stored one.
 * Sizing/text size come from `className` (e.g. "size-8 text-sm").
 */
export function Avatar({
  className,
  previewUrl,
}: {
  className?: string;
  previewUrl?: string | null;
}) {
  const { user } = useAuth();
  const { data: blob } = useProfileImage(user?.id);

  // Turn the fetched Blob into an object URL, revoking it when it changes / unmounts. Created and
  // revoked in the *same* effect: with the URL minted in a useMemo, StrictMode's mount→cleanup→mount
  // cycle revoked a URL that nothing then recreated (useMemo doesn't re-run), leaving a dead blob in
  // the <img> and leaking one URL per double-render.
  // The setState-in-effect lint rule is waived here on purpose: an object URL is an external
  // resource whose lifetime must bracket the effect, which is exactly the "synchronize with an
  // external system" case. Deriving it in a useMemo instead is what caused the StrictMode bug.
  /* eslint-disable react-hooks/set-state-in-effect */
  const [fetchedUrl, setFetchedUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!blob) {
      setFetchedUrl(null);
      return;
    }
    const url = URL.createObjectURL(blob);
    setFetchedUrl(url);
    return () => {
      URL.revokeObjectURL(url);
      setFetchedUrl(null);
    };
  }, [blob]);
  /* eslint-enable react-hooks/set-state-in-effect */

  const url = previewUrl ?? fetchedUrl;
  const initial = (user?.displayName || user?.email || '?').charAt(0).toUpperCase();

  return (
    <span
      className={cn(
        'grid shrink-0 place-items-center overflow-hidden rounded-full bg-elevated font-semibold text-text-muted',
        className,
      )}
    >
      {url ? <img src={url} alt="" className="size-full object-cover" /> : initial}
    </span>
  );
}

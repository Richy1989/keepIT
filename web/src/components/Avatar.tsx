import { useEffect, useMemo } from 'react';
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

  // Turn the fetched Blob into an object URL, revoking it when it changes / unmounts.
  const fetchedUrl = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob]);
  useEffect(() => {
    return () => {
      if (fetchedUrl) URL.revokeObjectURL(fetchedUrl);
    };
  }, [fetchedUrl]);

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

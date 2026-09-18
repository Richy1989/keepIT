import { useAuth } from '../auth/AuthContext';
import { useProfileImage } from '../features/account/queries';
import { cn } from '../lib/cn';
import { useObjectUrl } from '../lib/useObjectUrl';

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

  // Shared with the note-image components — see useObjectUrl for why the create/revoke pair has to
  // live in one effect (a useMemo version of this leaked a URL per StrictMode double-render).
  const fetchedUrl = useObjectUrl(blob);

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

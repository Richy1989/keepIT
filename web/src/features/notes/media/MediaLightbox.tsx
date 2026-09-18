import { useEffect } from 'react';
import { NoteImage } from './NoteImage';
import { useNoteMediaBlob } from './queries';
import { DownloadIcon, XIcon } from '../../../components/icons';
import type { NoteMediaDto } from '../../../api/types';

/**
 * Saves the image being viewed. Reads the very blob the viewer is displaying (same query key), so
 * the download is instant and costs no second request — which is also why it lives in its own
 * component: the hook needs a media item, and the lightbox only has one after its early return.
 */
function DownloadButton({ noteId, media }: { noteId: string; media: NoteMediaDto }) {
  const { data: blob } = useNoteMediaBlob(noteId, media.id, 'full');

  const download = () => {
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    // The server keeps originals as JPEG, or GIF so an animation survives.
    link.download = `keepIT-${media.id.slice(0, 8)}${blob.type === 'image/gif' ? '.gif' : '.jpg'}`;
    link.click();
    // Not revoked straight away: some browsers start the download after the click returns, and a
    // URL revoked by then fails it. The blob stays in the query cache regardless.
    setTimeout(() => URL.revokeObjectURL(url), 30_000);
  };

  return (
    <button
      type="button"
      onMouseDown={(e) => e.stopPropagation()}
      onClick={download}
      disabled={!blob}
      aria-label="Download image"
      title="Download"
      className="focus-ring absolute right-16 top-4 grid size-9 place-items-center rounded-full bg-white/10 text-white hover:bg-white/20 disabled:opacity-40"
    >
      <DownloadIcon className="text-lg" />
    </button>
  );
}

/** Full-size overlay for a note's images, with keyboard paging. */
export function MediaLightbox({
  noteId,
  media,
  index,
  onClose,
  onIndexChange,
}: {
  noteId: string;
  media: NoteMediaDto[];
  index: number;
  onClose: () => void;
  onIndexChange: (next: number) => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
      if (e.key === 'ArrowRight') onIndexChange((index + 1) % media.length);
      if (e.key === 'ArrowLeft') onIndexChange((index - 1 + media.length) % media.length);
    };
    // Capture phase: the editor modal also listens for Escape, and without this the first Escape
    // would close the whole note instead of the image on top of it.
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [index, media.length, onClose, onIndexChange]);

  const current = media[index];
  if (!current) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Image viewer"
      className="fixed inset-0 z-[60] grid place-items-center bg-black/90 p-4"
      // The viewer renders inside the editor's overlay, whose own mousedown saves and closes the
      // note. Without stopping propagation here, dismissing an image would close the editor too.
      onMouseDown={(e) => {
        e.stopPropagation();
        onClose();
      }}
    >
      <button
        type="button"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={onClose}
        aria-label="Close image viewer"
        className="focus-ring absolute right-4 top-4 grid size-9 place-items-center rounded-full bg-white/10 text-white hover:bg-white/20"
      >
        <XIcon className="text-lg" />
      </button>

      <DownloadButton noteId={noteId} media={current} />

      <div className="max-h-full w-full max-w-4xl" onMouseDown={(e) => e.stopPropagation()}>
        {/* maxAspect 0: the card caps how tall an image may be, the viewer must not. */}
        <NoteImage noteId={noteId} media={current} size="full" className="rounded-lg" maxAspect={0} />
      </div>

      {media.length > 1 && (
        <p className="absolute bottom-6 text-sm text-white/70">
          {index + 1} / {media.length}
        </p>
      )}
    </div>
  );
}

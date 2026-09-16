import { useNoteMediaBlob, type MediaSize } from './queries';
import { useObjectUrl } from '../../../lib/useObjectUrl';
import { cn } from '../../../lib/cn';
import type { NoteMediaDto } from '../../../api/types';

/**
 * One note image. The box is sized from the stored dimensions *before* the blob arrives — without
 * that, every image landing in the CSS-columns grid reflows the column beneath it.
 *
 * `maxAspect` caps how tall a portrait image may render relative to its width, so one long receipt
 * can't claim a whole column. Pass 0 to disable the cap (the lightbox wants the true ratio).
 */
export function NoteImage({
  noteId,
  media,
  size,
  className,
  maxAspect = 1.4,
  square = false,
}: {
  noteId: string;
  media: NoteMediaDto;
  size: MediaSize;
  className?: string;
  /** Tallest allowed height as a multiple of width. 0 disables the cap. */
  maxAspect?: number;
  /** Force a square box, for gallery strips where a ragged row of mixed ratios reads badly. */
  square?: boolean;
}) {
  const { data: blob, isError } = useNoteMediaBlob(noteId, media.id, size);
  const url = useObjectUrl(blob);

  const width = media.width > 0 ? media.width : 1;
  const height = media.height > 0 ? media.height : 1;
  const cappedHeight = maxAspect > 0 ? Math.min(height, width * maxAspect) : height;

  return (
    <div
      className={cn('relative w-full overflow-hidden bg-elevated', className)}
      style={{ aspectRatio: square ? '1 / 1' : `${width} / ${cappedHeight}` }}
    >
      {url && <img src={url} alt="" className="size-full object-cover" loading="lazy" />}
      {isError && (
        <div className="grid size-full place-items-center text-xs text-text-faint">
          Image unavailable
        </div>
      )}
    </div>
  );
}

import { NoteImage } from './NoteImage';
import type { PendingUpload } from './useMediaUpload';
import { XIcon } from '../../../components/icons';
import type { NoteMediaDto } from '../../../api/types';

/**
 * The editor's image row: stored images plus any still uploading. A pending upload renders from its
 * local object URL, so the image appears the instant it's chosen rather than after a round trip.
 */
export function MediaStrip({
  noteId,
  media,
  pending,
  canEdit,
  onRemove,
  onOpen,
}: {
  noteId: string;
  media: NoteMediaDto[];
  pending: PendingUpload[];
  canEdit: boolean;
  onRemove: (mediaId: string) => void;
  onOpen: (index: number) => void;
}) {
  if (media.length === 0 && pending.length === 0) return null;

  return (
    <div className="mb-3 grid grid-cols-3 gap-2 sm:grid-cols-4">
      {media.map((m, i) => (
        <div key={m.id} className="group/img relative">
          <button
            type="button"
            onClick={() => onOpen(i)}
            aria-label="View image"
            className="focus-ring block w-full overflow-hidden rounded-lg"
          >
            <NoteImage noteId={noteId} media={m} size="thumb" square />
          </button>
          {canEdit && (
            <button
              type="button"
              onClick={() => onRemove(m.id)}
              aria-label="Remove image"
              className="focus-ring absolute right-1 top-1 grid size-6 place-items-center rounded-full bg-black/70 text-white opacity-0 transition-opacity hover:bg-black/85 group-hover/img:opacity-100 focus-visible:opacity-100 touch:opacity-100"
            >
              <XIcon className="text-xs" />
            </button>
          )}
        </div>
      ))}

      {pending.map((p) => (
        <div key={p.id} className="relative aspect-square overflow-hidden rounded-lg bg-elevated">
          <img src={p.url} alt="" className="size-full object-cover opacity-50" />
          <div className="absolute inset-0 grid place-items-center">
            <span className="size-5 animate-spin rounded-full border-2 border-white/30 border-t-white" />
          </div>
        </div>
      ))}
    </div>
  );
}

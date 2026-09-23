import { useNoteMediaBlob } from './queries';
import { formatDuration } from './duration';
import { useObjectUrl } from '../../../lib/useObjectUrl';
import { XIcon } from '../../../components/icons';
import type { NoteMediaDto } from '../../../api/types';

/**
 * A voice note in the editor: the browser's own audio controls over the recording's bytes.
 *
 * The endpoint is authenticated, so the file can't be an `<audio src>` any more than an image can
 * be an `<img src>` — the bytes come down as a Blob through the same cached query images use, and
 * `useObjectUrl` turns it into something the element can play. That also means the whole recording
 * is fetched before it plays rather than streamed; at the server's 10 MB cap that is a short wait,
 * and it buys playback that never puts a token near a URL.
 *
 * Recording lives on Android only — browsers cannot record over plain http, which keepIT supports
 * on a LAN — so this is deliberately playback and delete, with nothing here that makes one.
 */
export function AudioPlayer({
  noteId,
  media,
  canEdit,
  onRemove,
}: {
  noteId: string;
  media: NoteMediaDto;
  canEdit: boolean;
  onRemove: () => void;
}) {
  const { data: blob, isError } = useNoteMediaBlob(noteId, media.id, 'full');
  const url = useObjectUrl(blob);

  return (
    <div className="flex items-center gap-2 rounded-lg border border-border-subtle bg-surface-hover px-3 py-2">
      <div className="min-w-0 flex-1">
        {url ? (
          <audio controls preload="metadata" src={url} className="h-9 w-full">
            <track kind="captions" />
          </audio>
        ) : (
          <p className="py-2 text-sm text-text-muted">
            {isError ? "This recording couldn't be loaded." : 'Loading recording…'}
          </p>
        )}
      </div>

      {/* The container's own duration, when the server could read one. Beside the control rather
          than inside it: the element only knows the length once it has loaded metadata. */}
      {media.durationMs != null && (
        <span className="shrink-0 font-mono text-xs text-text-faint">
          {formatDuration(media.durationMs)}
        </span>
      )}

      {canEdit && (
        <button
          type="button"
          onClick={onRemove}
          aria-label="Remove recording"
          className="focus-ring grid size-7 shrink-0 place-items-center rounded-full text-text-muted transition hover:bg-overlay-hover hover:text-text"
        >
          <XIcon className="text-sm" />
        </button>
      )}
    </div>
  );
}

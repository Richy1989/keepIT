import { useNoteMediaBlob } from './queries';
import { useObjectUrl } from '../../../lib/useObjectUrl';
import type { NoteMediaDto } from '../../../api/types';

/**
 * What a photo card is painted on: a heavily blurred copy of its first photo under the theme's
 * `--photo-scrim`. The hero fades into it, so the note's text sits on the photo's own colours.
 *
 * It asks for the same `preview` blob the hero shows, so it costs no second download. The blur is
 * a plain `filter` on this one layer rather than a `backdrop-filter` on the card: the browser
 * rasterises it once instead of re-sampling what lies behind on every frame of a scroll.
 *
 * It sits at `-z-10`, under the card's content, which works because the card is `isolate`.
 */
export function PhotoBackdrop({ noteId, media }: { noteId: string; media: NoteMediaDto }) {
  const { data: blob } = useNoteMediaBlob(noteId, media.id, 'preview');
  const url = useObjectUrl(blob);

  return (
    <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
      {/* Larger than the card, so the blur's soft edge falls outside it. */}
      {url && (
        <img
          src={url}
          alt=""
          className="absolute -inset-12 size-[calc(100%+6rem)] max-w-none object-cover blur-2xl saturate-150"
        />
      )}
      <div className="absolute inset-0 bg-(--photo-scrim)" />
    </div>
  );
}

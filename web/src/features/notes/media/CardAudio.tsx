import { useCallback, useEffect, useRef, useState } from 'react';
import { useNoteMediaBlob } from './queries';
import { formatDuration } from './duration';
import { useObjectUrl } from '../../../lib/useObjectUrl';
import { PauseIcon, PlayIcon } from '../../../components/icons';
import type { NoteMediaDto } from '../../../api/types';

/**
 * The element playing right now, anywhere in the grid.
 *
 * Module scope rather than context: a card knows nothing about its siblings, and two voice notes
 * talking over each other is the one thing a grid full of players must not do. Whoever starts
 * playing pauses whoever was — the same rule the browser applies to a single `<audio>` page.
 */
let playingElement: HTMLAudioElement | null = null;

/**
 * A voice note played from the notes grid, without opening the note.
 *
 * The bytes are **not** fetched until the first press. `useNoteMediaBlob` downloads the whole
 * recording (the endpoint is authenticated, so it can't be an `<audio src>` any more than an image
 * can be an `<img src>`), and a grid where every card pulled its own recording on mount would
 * spend megabytes showing a play button nobody pressed. `enabled: armed` holds that back; the
 * press that arms the query is also the press that asked for playback, so `wantsPlay` starts it as
 * soon as the blob lands instead of demanding a second tap.
 *
 * The editor keeps its own fuller player ({@link AudioPlayer}) with the browser's native controls;
 * this one is deliberately a transport — play, pause, how far in — sized for a card.
 */
export function CardAudio({ noteId, media }: { noteId: string; media: NoteMediaDto }) {
  const ref = useRef<HTMLAudioElement>(null);
  const wantsPlay = useRef(false);
  const [armed, setArmed] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [positionMs, setPositionMs] = useState(0);
  const [elementMs, setElementMs] = useState<number | null>(null);

  const { data: blob, isError } = useNoteMediaBlob(noteId, media.id, 'full', { enabled: armed });
  const url = useObjectUrl(blob);

  // The container's own duration when the server could read one; otherwise whatever the element
  // reports once it has the bytes. Either way the card shows a length before anything downloads.
  const totalMs = media.durationMs ?? elementMs;

  const start = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    if (playingElement && playingElement !== el) playingElement.pause();
    playingElement = el;
    void el.play().catch(() => setPlaying(false));
  }, []);

  useEffect(() => {
    if (!url || !wantsPlay.current) return;
    wantsPlay.current = false;
    start();
  }, [url, start]);

  // Leaving the grid (or a re-render that drops this card) must not leave a dead element owning
  // the "currently playing" slot, or the next card to start would pause nothing.
  useEffect(
    () => () => {
      if (playingElement === ref.current) playingElement = null;
    },
    [],
  );

  function toggle() {
    if (playing) {
      ref.current?.pause();
      return;
    }
    if (url) {
      start();
      return;
    }
    wantsPlay.current = true;
    setArmed(true);
  }

  const loading = armed && !url && !isError;
  const progress = totalMs && totalMs > 0 ? Math.min(positionMs / totalMs, 1) : 0;

  return (
    // Every press here stays here: the whole card is a button that opens the editor, and playing a
    // recording is not asking to open the note.
    <div
      onClick={(e) => e.stopPropagation()}
      className="flex items-center gap-2 rounded-[10px] bg-overlay-well px-2 py-1.5"
    >
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          toggle();
        }}
        disabled={isError}
        aria-label={playing ? 'Pause voice note' : 'Play voice note'}
        className="focus-ring grid size-7 shrink-0 place-items-center rounded-full bg-accent text-black transition hover:bg-accent-strong disabled:opacity-50"
      >
        {loading ? (
          <span className="size-3 animate-spin rounded-full border-2 border-current border-t-transparent" />
        ) : playing ? (
          <PauseIcon className="text-xs" />
        ) : (
          <PlayIcon className="text-xs" />
        )}
      </button>

      <div className="min-w-0 flex-1">
        {isError ? (
          <p className="text-xs text-text-faint">This recording couldn&apos;t be loaded.</p>
        ) : (
          <div className="h-1 overflow-hidden rounded-full bg-border-subtle">
            <div
              className="h-full rounded-full bg-accent"
              style={{ width: `${progress * 100}%` }}
            />
          </div>
        )}
      </div>

      {/* Counts up while playing, and rests on the recording's full length otherwise. */}
      {totalMs != null && (
        <span className="shrink-0 font-mono text-[11px] tabular-nums text-text-faint">
          {formatDuration(playing || positionMs > 0 ? positionMs : totalMs)}
        </span>
      )}

      <audio
        ref={ref}
        src={url ?? undefined}
        preload="none"
        hidden
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => {
          setPlaying(false);
          setPositionMs(0);
        }}
        onTimeUpdate={(e) => setPositionMs(Math.round(e.currentTarget.currentTime * 1000))}
        onLoadedMetadata={(e) => {
          const seconds = e.currentTarget.duration;
          if (Number.isFinite(seconds)) setElementMs(Math.round(seconds * 1000));
        }}
      >
        <track kind="captions" />
      </audio>
    </div>
  );
}

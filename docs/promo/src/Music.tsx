import { Audio, Sequence, staticFile } from 'remotion';
import { MUSIC } from './music';
import { DURATION, FPS } from './timeline';

/** The music under the video, from its delay on, fading out at the end (see music.ts). */
export const Music: React.FC = () => (
  <Sequence from={Math.round(MUSIC.delay * FPS)}>
    <Audio
      src={staticFile(MUSIC.file)}
      trimBefore={Math.round(MUSIC.start * FPS)}
      volume={(f) => {
        // f counts from the track's own start; the fade is timed on the video's clock.
        const left = DURATION / FPS - (f / FPS + MUSIC.delay);
        return MUSIC.volume * Math.max(0, Math.min(1, left / MUSIC.fadeOut));
      }}
    />
  </Sequence>
);

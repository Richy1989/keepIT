import { AbsoluteFill } from 'remotion';
import { Background } from './Background';
import { Badges } from './Badges';
import { Brand } from './Brand';
import { Callout } from './Callout';
import { Captions } from './Captions';
import { Footage } from './Footage';
import { Music } from './Music';
import { Phone } from './Phone';

/**
 * keepIT's promo video: the real app, recorded on the emulator (scripts/record-tour.py), on a phone
 * hanging in the air, with a caption for each beat, the stores' badges under it, and music. The
 * beats and their timing are in timeline.ts, the places in layout.ts, the music in music.ts.
 */
export const Promo: React.FC = () => (
  <AbsoluteFill>
    <Background />
    <Captions />
    <Brand />
    <Phone>
      <Footage />
    </Phone>
    <Callout />
    <Badges />
    <Music />
  </AbsoluteFill>
);

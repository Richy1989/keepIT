import { Composition } from 'remotion';
import { LAYOUTS } from './layout';
import { Promo } from './Promo';
import { DURATION, FPS } from './timeline';

/** The same video twice: landscape for YouTube, Play and the README, portrait for phones. */
export const Root: React.FC = () => (
  <>
    {(['landscape', 'portrait'] as const).map((shape) => (
      <Composition
        key={shape}
        id={shape === 'landscape' ? 'Promo' : 'PromoPortrait'}
        component={Promo}
        durationInFrames={DURATION}
        fps={FPS}
        width={LAYOUTS[shape].width}
        height={LAYOUTS[shape].height}
      />
    ))}
  </>
);

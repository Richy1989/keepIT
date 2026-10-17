import { Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
import { useLayout } from './layout';
import { OUTRO_START } from './timeline';

/**
 * Where to get keepIT, centred under the phone for the whole video: F-Droid's and Google Play's own
 * badges, unaltered (both stores require that), at the same height, as the README shows them.
 */
export const Badges: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const { top, height } = useLayout().badges;
  const enter = spring({ frame: frame - 20, fps, config: { damping: 18, stiffness: 90 } });
  const outro = interpolate(frame, [OUTRO_START, OUTRO_START + 24], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });

  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        top,
        display: 'flex',
        justifyContent: 'center',
        gap: 10,
        opacity: enter,
        transform: `translateY(${(1 - enter) * 24}px) scale(${1 + outro * 0.06})`,
      }}
    >
      <Img src={staticFile('badge-fdroid.png')} style={{ height }} />
      <Img src={staticFile('badge-google-play.png')} style={{ height }} />
    </div>
  );
};

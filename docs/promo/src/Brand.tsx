import { Img, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
// The app icon as docs/brand renders it, so the video never shows an outdated copy.
import icon from '../../brand/keepit-icon-1024.png';
import { useLayout } from './layout';
import { beats, OUTRO_START } from './timeline';
import { fontFamily, theme } from './theme';

/**
 * The keepIT icon and name: there for the first beat, gone while the phone does the talking, and
 * back, larger, for the last. A column to the phone's right in landscape; a row above the caption
 * in portrait, where there is no room beside the phone.
 */
export const Brand: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const box = useLayout().brand;

  const first = spring({ frame: frame - 16, fps, config: { damping: 18, stiffness: 90 } });
  const firstOut = interpolate(frame, [beats[0][1] - 10, beats[0][1]], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const last = spring({ frame: frame - OUTRO_START - 6, fps, config: { damping: 16, stiffness: 80 } });

  const outro = frame >= OUTRO_START;
  const shown = outro ? last : first * (1 - firstOut);
  if (shown <= 0.001) return null;
  const size = outro ? 1.12 : 1;
  const iconSize = box.row ? 104 : 150;

  return (
    <div
      style={{
        position: 'absolute',
        left: box.left,
        top: box.top,
        width: box.width,
        height: box.height,
        display: 'flex',
        flexDirection: box.row ? 'row' : 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: box.row ? 28 : 0,
        fontFamily,
        opacity: shown,
        transform: `translateY(${(1 - shown) * 30}px) scale(${size})`,
      }}
    >
      <Img
        src={icon}
        style={{ width: iconSize, height: iconSize, borderRadius: iconSize * 0.23, boxShadow: '0 30px 60px -20px rgba(0,0,0,0.8)' }}
      />
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: box.row ? 'flex-start' : 'center' }}>
        <div style={{ color: theme.text, fontSize: box.row ? 60 : 70, fontWeight: 800, letterSpacing: -2, marginTop: box.row ? 0 : 26, lineHeight: 1.05 }}>
          keepIT
        </div>
        <div style={{ color: theme.muted, fontSize: 26, marginTop: 6 }}>A real-time notes app you can run yourself</div>
      </div>
    </div>
  );
};

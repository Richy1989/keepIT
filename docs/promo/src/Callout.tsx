import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { LastFrame } from './Footage';
import { useLayout } from './layout';
import { FACES_BOX, FACES_SETTLED, OUTRO_START } from './timeline';
import { fontFamily, theme } from './theme';

/** The close-up shows the faces this many footage pixels to one, with this much room round them. */
const ZOOM = 1.9;
const PAD_X = 12;
const PAD_Y = 22;

/**
 * The faces on the shared note are a few pixels tall on the phone, so once the tour has scrolled to
 * them a close-up shows them large, with a ring round them on the phone itself and a line between
 * the two. In landscape the close-up stands beside the phone, its words under it; in portrait there
 * is no room there, so it is a card in front of the phone's lower half, just below the faces.
 */
export const Callout: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const layout = useLayout();
  const PHONE = layout.phone;
  if (frame < FACES_SETTLED || frame > OUTRO_START + 12) return null;

  const enter = spring({ frame: frame - FACES_SETTLED, fps, config: { damping: 15, stiffness: 120 } });
  const leave = interpolate(frame, [OUTRO_START - 2, OUTRO_START + 12], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const shown = enter * (1 - leave);

  const [x1, y1, x2, y2] = FACES_BOX;
  const cx = (x1 + x2) / 2;
  const cy = (y1 + y2) / 2;
  const width = (x2 - x1 + PAD_X * 2) * ZOOM;
  const height = (y2 - y1 + PAD_Y * 2) * ZOOM;
  // Where the faces sit in the video: on the screen, inside the bezel. The phone's slight turn moves
  // them by a few pixels, which the ring and the line can live with.
  const faceX = PHONE.left + PHONE.bezel + cx * PHONE.scale;
  const faceY = PHONE.top + PHONE.bezel + cy * PHONE.scale;
  const rx = ((x2 - x1) / 2) * PHONE.scale + 12;
  const ry = ((y2 - y1) / 2) * PHONE.scale + 9;

  const beside = layout.calloutBeside;
  const cardPad = beside ? 0 : 20;
  const cardWidth = width + cardPad * 2;
  const left = beside ? PHONE.left + PHONE.width + 90 : Math.min(layout.width - 40 - cardWidth, faceX - cardWidth / 2);
  const top = beside ? faceY - height / 2 - 20 : faceY + ry + 60;
  const [lx1, ly1, lx2, ly2] = beside ? [faceX + rx, faceY, left, faceY] : [faceX, faceY + ry, faceX, top];

  return (
    <>
      <svg style={{ position: 'absolute', left: 0, top: 0, width: layout.width, height: layout.height, opacity: shown }}>
        <ellipse cx={faceX} cy={faceY} rx={rx} ry={ry} fill="none" stroke={theme.accent} strokeWidth={3} />
        <line
          x1={lx1}
          y1={ly1}
          x2={lx1 + (lx2 - lx1) * enter}
          y2={ly1 + (ly2 - ly1) * enter}
          stroke={theme.accent}
          strokeWidth={3}
          strokeDasharray="2 8"
          strokeLinecap="round"
        />
      </svg>
      <div
        style={{
          position: 'absolute',
          left,
          top,
          width: beside ? 560 : cardWidth,
          boxSizing: 'border-box',
          padding: cardPad,
          borderRadius: beside ? 0 : 36,
          background: beside ? 'transparent' : theme.surface,
          border: beside ? 'none' : '1px solid rgba(255,255,255,0.1)',
          boxShadow: beside ? 'none' : '0 50px 90px -30px rgba(0,0,0,0.95)',
          textAlign: beside ? 'left' : 'center',
          fontFamily,
          opacity: shown,
          transform: `scale(${0.85 + 0.15 * enter})`,
          transformOrigin: beside ? 'left center' : 'center top',
        }}
      >
        <div
          style={{
            position: 'relative',
            width,
            height,
            borderRadius: 28,
            overflow: 'hidden',
            border: `2px solid ${theme.accent}`,
            boxShadow: beside ? '0 40px 80px -30px rgba(0,0,0,0.9)' : 'none',
          }}
        >
          <div
            style={{
              position: 'absolute',
              left: width / 2 - cx * ZOOM,
              top: height / 2 - cy * ZOOM,
              width: 1080,
              height: 2400,
              transform: `scale(${ZOOM})`,
              transformOrigin: 'top left',
            }}
          >
            <LastFrame />
          </div>
        </div>
        <div style={{ color: theme.text, fontSize: 30, fontWeight: 700, marginTop: 22 }}>Jonas, Mia and Luca are in.</div>
        <div style={{ color: theme.muted, fontSize: 24, marginTop: 6 }}>Everyone on a note shows on its card.</div>
      </div>
    </>
  );
};

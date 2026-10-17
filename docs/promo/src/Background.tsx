import { AbsoluteFill, useCurrentFrame } from 'remotion';
import { useLayout } from './layout';
import { theme } from './theme';

type Card = { x: number; y: number; w: number; rows: number; colour: number; depth: number; tilt: number; check?: boolean };

/**
 * Note cards in the app's colours, drifting up at different depths around the edges, away from the
 * captions, the phone and the badges: the notes are in the air too. Deeper cards are smaller,
 * dimmer, blurrier and slower, which is all the depth there is. One set per layout.
 */
const LANDSCAPE: Card[] = [
  { x: 70, y: 70, w: 230, rows: 3, colour: 0, depth: 0.55, tilt: -7 },
  { x: 180, y: 800, w: 260, rows: 4, colour: 1, depth: 0.8, tilt: 5, check: true },
  { x: 30, y: 470, w: 150, rows: 2, colour: 3, depth: 0.3, tilt: 9 },
  { x: 610, y: 880, w: 170, rows: 2, colour: 5, depth: 0.35, tilt: -10 },
  { x: 1560, y: 60, w: 250, rows: 3, colour: 2, depth: 0.75, tilt: 6, check: true },
  { x: 1680, y: 780, w: 220, rows: 3, colour: 6, depth: 0.6, tilt: -6 },
  { x: 1800, y: 430, w: 140, rows: 2, colour: 4, depth: 0.28, tilt: 11 },
  { x: 1240, y: 900, w: 160, rows: 2, colour: 0, depth: 0.32, tilt: 8 },
];

const PORTRAIT: Card[] = [
  { x: -40, y: 50, w: 220, rows: 3, colour: 0, depth: 0.5, tilt: -8 },
  { x: 880, y: 30, w: 210, rows: 2, colour: 2, depth: 0.55, tilt: 7, check: true },
  { x: 10, y: 720, w: 240, rows: 4, colour: 1, depth: 0.75, tilt: 6, check: true },
  { x: 830, y: 880, w: 230, rows: 3, colour: 6, depth: 0.65, tilt: -6 },
  { x: 20, y: 1260, w: 180, rows: 2, colour: 3, depth: 0.35, tilt: 10 },
  { x: 880, y: 1420, w: 170, rows: 2, colour: 4, depth: 0.3, tilt: -9 },
  { x: -20, y: 1720, w: 210, rows: 2, colour: 5, depth: 0.45, tilt: -5 },
  { x: 880, y: 1760, w: 190, rows: 2, colour: 0, depth: 0.4, tilt: 8 },
];

export const Background: React.FC = () => {
  const frame = useCurrentFrame();
  const t = frame / 30;
  const cards = useLayout().portrait ? PORTRAIT : LANDSCAPE;

  return (
    <AbsoluteFill style={{ backgroundColor: theme.canvas, overflow: 'hidden' }}>
      {/* The light the phone floats in: the accent behind it, a cool blue low on the right. */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(ellipse 55% 60% at ${50 + Math.sin(t * 0.3) * 2}% 42%, ${theme.accent}38 0%, transparent 70%),
            radial-gradient(ellipse 45% 50% at 88% 95%, #3b82f62a 0%, transparent 70%),
            radial-gradient(ellipse 40% 45% at 6% 8%, #a855f71c 0%, transparent 70%)`,
        }}
      />
      {cards.map((c, i) => (
        <FloatingCard key={i} card={c} t={t} index={i} />
      ))}
      {/* A vignette keeps the eye in the middle. */}
      <AbsoluteFill
        style={{ background: 'radial-gradient(ellipse 80% 80% at 50% 45%, transparent 55%, rgba(0,0,0,0.55) 100%)' }}
      />
    </AbsoluteFill>
  );
};

const FloatingCard: React.FC<{ card: Card; t: number; index: number }> = ({ card: c, t, index }) => {
  const scale = 0.55 + c.depth * 0.6;
  const rise = t * 9 * c.depth;
  const sway = Math.sin(t * 0.6 + index) * 10 * c.depth;
  const bg = theme.notes[c.colour % theme.notes.length];
  return (
    <div
      style={{
        position: 'absolute',
        left: c.x,
        top: c.y,
        width: c.w,
        padding: 22,
        borderRadius: 22,
        background: `linear-gradient(180deg, ${bg} 0%, ${bg}d0 100%)`,
        border: '1px solid rgba(255,255,255,0.08)',
        boxShadow: '0 24px 48px -18px rgba(0,0,0,0.7)',
        opacity: 0.25 + c.depth * 0.45,
        filter: `blur(${(1 - c.depth) * 5}px)`,
        transform: `translate(${sway}px, ${-rise}px) rotate(${c.tilt + Math.sin(t * 0.4 + index) * 2}deg) scale(${scale})`,
        transformOrigin: 'center',
      }}
    >
      <div style={{ height: 16, width: '55%', borderRadius: 8, background: 'rgba(255,255,255,0.55)', marginBottom: 18 }} />
      {Array.from({ length: c.rows }).map((_, r) => (
        <div key={r} style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: r ? 12 : 0 }}>
          {c.check && (
            <div
              style={{
                width: 16,
                height: 16,
                borderRadius: 4,
                border: '2px solid rgba(255,255,255,0.45)',
                background: r === c.rows - 1 ? theme.accent : 'transparent',
                flexShrink: 0,
              }}
            />
          )}
          <div
            style={{
              height: 11,
              width: `${[88, 70, 80, 60][r % 4]}%`,
              borderRadius: 6,
              background: 'rgba(255,255,255,0.28)',
            }}
          />
        </div>
      ))}
    </div>
  );
};

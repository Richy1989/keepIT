import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { useLayout } from './layout';
import { beats, DURATION } from './timeline';
import { fontFamily, theme } from './theme';

/**
 * What each beat says: to the phone's left in landscape, above it in portrait. Play autoplays a
 * video muted, so the words carry it: one short line and one plain sentence each, in step with
 * what the phone shows.
 */
const CAPTIONS = [
  { title: 'Your notes,\neverywhere.', body: 'On your phone and on the web, always in sync.' },
  { title: 'Tick it off.', body: 'Checklists for groceries, packing and everything in between.' },
  { title: 'Make it yours.', body: 'Colours, photos, voice notes and lists.' },
  { title: 'Plan it together.', body: 'Share a note and see who is on it.' },
  { title: 'Free &\nopen source.', body: 'Run it on your own server, or with no server at all.' },
];

export const Captions: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const box = useLayout().caption;

  return (
    <>
      {CAPTIONS.map((c, i) => {
        const [start, end] = beats[i];
        if (frame < start - 1 || frame >= end) return null;
        const enter = spring({ frame: frame - start - (i === 0 ? 12 : 2), fps, config: { damping: 18, stiffness: 110 } });
        const leave = end >= DURATION ? 0 : interpolate(frame, [end - 9, end], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: box.left,
              top: box.top,
              width: box.width,
              height: box.height,
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'center',
              alignItems: box.centred ? 'center' : 'flex-start',
              textAlign: box.centred ? 'center' : 'left',
              fontFamily,
              opacity: enter * (1 - leave),
              transform: `translateY(${(1 - enter) * 34 - leave * 18}px)`,
            }}
          >
            <div
              style={{
                width: 64 * enter,
                height: 7,
                borderRadius: 4,
                background: theme.accent,
                marginBottom: box.title * 0.36,
              }}
            />
            <div
              style={{
                color: theme.text,
                fontSize: box.title,
                fontWeight: 800,
                letterSpacing: -box.title / 32,
                lineHeight: 1.02,
                whiteSpace: 'pre-line',
              }}
            >
              {c.title}
            </div>
            <div style={{ color: theme.muted, fontSize: box.body, lineHeight: 1.4, marginTop: box.body * 0.8, maxWidth: box.centred ? box.width - 140 : 520 }}>
              {c.body}
            </div>
          </div>
        );
      })}
    </>
  );
};

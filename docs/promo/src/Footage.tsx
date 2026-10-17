import { Freeze, OffthreadVideo, Sequence, staticFile } from 'remotion';
import { clips, HOLD_SOURCE, HOLD_START } from './timeline';

const SRC = staticFile('tour.mp4');
const FULL: React.CSSProperties = { width: 1080, height: 2400 };

/** The recorded tour on the phone's screen: the clips in turn, then its last frame held. */
export const Footage: React.FC = () => (
  <>
    {clips.map((c, i) => (
      <Sequence key={i} from={c.start} durationInFrames={c.frames}>
        <OffthreadVideo src={SRC} trimBefore={c.source} muted style={FULL} />
      </Sequence>
    ))}
    <Sequence from={HOLD_START}>
      <Freeze frame={0}>
        <OffthreadVideo src={SRC} trimBefore={HOLD_SOURCE} muted style={FULL} />
      </Freeze>
    </Sequence>
  </>
);

/** One still of the footage's last frame, for the close-up of the faces. */
export const LastFrame: React.FC<{ style?: React.CSSProperties }> = ({ style }) => (
  <Freeze frame={0}>
    <OffthreadVideo src={SRC} trimBefore={HOLD_SOURCE} muted style={{ ...FULL, ...style }} />
  </Freeze>
);

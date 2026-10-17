import { interpolate, spring, useCurrentFrame, useVideoConfig, Easing } from 'remotion';
import { useLayout } from './layout';
import { OUTRO_START } from './timeline';

/**
 * A phone hanging in the air, where the layout puts it: a frame drawn in CSS around the footage
 * (children, laid out at the footage's own 1080 × 2400), rising in at the start, then hovering — a slow bob and a slight turn
 * in three dimensions — over a shadow that grows as it sinks. For the logo at the end it turns to
 * face the viewer a little more.
 */
export const Phone: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const PHONE = useLayout().phone;
  const t = frame / fps;

  const enter = spring({ frame: frame - 3, fps, config: { damping: 15, stiffness: 60, mass: 1.1 } });
  const settle = interpolate(frame, [OUTRO_START, OUTRO_START + 36], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.inOut(Easing.cubic),
  });

  const bob = Math.sin(t * 1.05) * 9;
  const rotateY = interpolate(enter, [0, 1], [-34, 0]) + (-9 + Math.sin(t * 0.5) * 3) * (1 - settle * 0.65);
  const rotateX = 6 + Math.sin(t * 0.37 + 1) * 1.5 - settle * 2;
  const rotateZ = Math.sin(t * 0.29) * 0.8;
  const rise = interpolate(enter, [0, 1], [170, 0]);
  const opacity = interpolate(enter, [0, 0.35], [0, 1], { extrapolateRight: 'clamp' });

  return (
    <>
      {/* The shadow on the "floor": wider and darker as the phone sinks towards it. */}
      <div
        style={{
          position: 'absolute',
          left: PHONE.left + PHONE.width * 0.08,
          top: PHONE.top + PHONE.height + 6,
          width: PHONE.width * 0.84,
          height: 38,
          borderRadius: '50%',
          background: 'radial-gradient(ellipse at center, rgba(0,0,0,0.75) 0%, rgba(0,0,0,0) 70%)',
          opacity: opacity * (0.55 + bob / 40),
          transform: `scaleX(${1 + bob / 120})`,
          filter: 'blur(6px)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          left: PHONE.left,
          top: PHONE.top,
          width: PHONE.width,
          height: PHONE.height,
          opacity,
          transform: `perspective(2400px) translateY(${rise + bob}px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) rotateZ(${rotateZ}deg)`,
        }}
      >
        {/* Side buttons: volume and power. */}
        <div style={{ ...button, top: 150, height: 64 }} />
        <div style={{ ...button, top: 240, height: 112 }} />
        <div
          style={{
            width: '100%',
            height: '100%',
            boxSizing: 'border-box',
            borderRadius: 58,
            padding: PHONE.bezel,
            background: 'linear-gradient(150deg, #55565c 0%, #1b1c1f 28%, #0c0d0f 62%, #303136 100%)',
            boxShadow:
              'inset 0 0 0 1.5px rgba(255,255,255,0.18), 0 0 0 1px #050506, 0 60px 110px -40px rgba(0,0,0,0.9), 0 30px 60px -30px rgba(65,170,121,0.35)',
          }}
        >
          <div
            style={{
              position: 'relative',
              width: PHONE.screenWidth,
              height: PHONE.screenHeight,
              borderRadius: 46,
              overflow: 'hidden',
              background: '#000',
            }}
          >
            <div
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                width: 1080,
                height: 2400,
                transform: `scale(${PHONE.scale})`,
                transformOrigin: 'top left',
              }}
            >
              {children}
            </div>
            {/* The camera, in the middle of the status bar, which demo mode leaves empty there. */}
            <div
              style={{
                position: 'absolute',
                top: 11,
                left: PHONE.screenWidth / 2 - 7,
                width: 14,
                height: 14,
                borderRadius: 7,
                background: '#050506',
                boxShadow: '0 0 0 2px #1c1d20',
              }}
            />
            {/* Glass: a faint glare across the top left. */}
            <div
              style={{
                position: 'absolute',
                inset: 0,
                background:
                  'linear-gradient(118deg, rgba(255,255,255,0.11) 0%, rgba(255,255,255,0.03) 28%, rgba(255,255,255,0) 44%)',
              }}
            />
          </div>
        </div>
      </div>
    </>
  );
};

const button: React.CSSProperties = {
  position: 'absolute',
  right: -4,
  width: 5,
  borderRadius: 3,
  background: 'linear-gradient(90deg, #1b1c1f, #3a3b40)',
};

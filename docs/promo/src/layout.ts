import { useVideoConfig } from 'remotion';

/**
 * Where everything goes, for each of the two videos: landscape (1920 × 1080) for YouTube, Play and
 * the README, portrait (1080 × 1920) for phones. The beats, the footage and the motion are the same
 * in both; only this differs. Each component asks useLayout() for its place.
 */
export type Layout = {
  portrait: boolean;
  width: number;
  height: number;
  phone: Phone;
  /** The caption's box; the text is centred in it vertically. */
  caption: { left: number; top: number; width: number; height: number; centred: boolean; title: number; body: number };
  /** The logo: a column beside the phone in landscape, a row above the caption in portrait. */
  brand: { row: boolean; left: number; top: number; width: number; height: number };
  badges: { top: number; height: number };
  /** The faces' close-up: beside the phone, or as a card in front of its lower half. */
  calloutBeside: boolean;
};

export type Phone = {
  height: number;
  bezel: number;
  width: number;
  screenWidth: number;
  screenHeight: number;
  left: number;
  top: number;
  /** Footage pixels to video pixels: the screen recording is 1080 × 2400. */
  scale: number;
};

function phone(height: number, top: number, canvasWidth: number): Phone {
  const bezel = 13;
  const screenHeight = height - bezel * 2;
  const screenWidth = (screenHeight * 1080) / 2400;
  const width = screenWidth + bezel * 2;
  return { height, bezel, width, screenWidth, screenHeight, left: (canvasWidth - width) / 2, top, scale: screenWidth / 1080 };
}

const LANDSCAPE: Layout = {
  portrait: false,
  width: 1920,
  height: 1080,
  phone: phone(830, 34, 1920),
  caption: { left: 140, top: 0, width: 560, height: 880, centred: false, title: 80, body: 31 },
  brand: { row: false, left: 1240, top: 0, width: 560, height: 880 },
  badges: { top: 928, height: 92 },
  calloutBeside: true,
};

const PORTRAIT: Layout = {
  portrait: true,
  width: 1080,
  height: 1920,
  phone: phone(1050, 600, 1080),
  caption: { left: 90, top: 215, width: 900, height: 360, centred: true, title: 88, body: 36 },
  brand: { row: true, left: 0, top: 80, width: 1080, height: 120 },
  badges: { top: 1712, height: 100 },
  calloutBeside: false,
};

export const LAYOUTS = { landscape: LANDSCAPE, portrait: PORTRAIT };

export function useLayout(): Layout {
  const { width, height } = useVideoConfig();
  return height > width ? PORTRAIT : LANDSCAPE;
}

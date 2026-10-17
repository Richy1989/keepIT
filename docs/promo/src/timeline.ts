import marks from './tour-marks.json';

/**
 * Which parts of the recorded tour play when. record-tour.py writes the marks: the second into the
 * footage at which each tap was sent. The emulator shows a tap's effect about LAG seconds later,
 * and between taps the screen stands still, so cutting out those still stretches is invisible.
 * The clips below are cut from the marks, never from fixed times, so a new take needs no edit here.
 */

/** The footage is 30 fps and so is the video: a source frame is a video frame. */
export const FPS = 30;
export const DURATION = 25 * FPS;

/** The last beat: the logo, over the phone resting on its final screen. */
export const OUTRO_START = Math.round(21.5 * FPS);

/** Seconds from a mark to when the screen shows its effect. */
const LAG = 0.5;

/** Where on the phone's screen (1080 × 2400) the shared note's faces ended up. */
export const FACES_BOX = marks.facesBox as [number, number, number, number];

type Clip = { beat: number; from: number; to: number };

const scroll2 = marks.scroll2 ?? marks.faces - 1.2;

const CLIPS: Clip[] = [
  // 0: the notes, at rest.
  { beat: 0, from: marks.grid + 0.4, to: marks.grid + 3.9 },
  // 1: Groceries opens, two items are ticked.
  { beat: 1, from: marks.open + 0.4, to: marks.open + 1.7 },
  { beat: 1, from: marks.tick - 0.05, to: marks.tick2 + LAG + 0.6 },
  // 2: the colour sheet opens, Sage is picked, the sheet closes.
  { beat: 2, from: marks.colour + 0.3, to: marks.colour + LAG + 1.3 },
  { beat: 2, from: marks.sage - 0.1, to: marks.sheetClosed + LAG + 0.4 },
  // 3: back to the notes, and down to the shared note.
  { beat: 3, from: marks.close, to: marks.close + 1.3 },
  { beat: 3, from: marks.scroll + 0.3, to: marks.scroll + 1.5 },
  { beat: 3, from: scroll2 + 0.2, to: scroll2 + 1.7 },
];

export type PlacedClip = Clip & { start: number; frames: number; source: number };

/** The clips one after another: where each starts in the video, and in the footage. */
export const clips: PlacedClip[] = (() => {
  let start = 0;
  return CLIPS.map((c) => {
    const frames = Math.round((c.to - c.from) * FPS);
    const placed = { ...c, start, frames, source: Math.round(c.from * FPS) };
    start += frames;
    return placed;
  });
})();

const last = clips[clips.length - 1];

/** After the last clip the phone holds its final screen: this footage frame, from this video frame. */
export const HOLD_START = last.start + last.frames;
export const HOLD_SOURCE = last.source + last.frames - 1;

/** The five beats, each with its caption: [start, end) in video frames. */
export const beats: Array<[number, number]> = [0, 1, 2, 3].map((beat) => {
  const own = clips.filter((c) => c.beat === beat);
  const end = beat === 3 ? OUTRO_START : own[own.length - 1].start + own[own.length - 1].frames;
  return [own[0].start, end] as [number, number];
});
beats.push([OUTRO_START, DURATION]);

/** The faces have stopped moving: the close-up may open. */
export const FACES_SETTLED = HOLD_START - 6;

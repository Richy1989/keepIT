/**
 * The music: "Dirty Thinkin'" from Mixkit (mixkit-dirty-thinkin-989.mp3), under the Mixkit Stock
 * Music Free License: free in a promo video and no credit required, but not to be passed on as a
 * file, so it is not in the repository (README.md says where to get it).
 *
 * It runs at 120 BPM. Its intro is a beat on its own, and the full groove comes in exactly 3 s in,
 * so half a second's delay lands that on "Tick it off", the first thing the phone does. The track
 * runs on past the video and fades out under the logo. It is mastered at -16 LUFS with its peaks
 * at -1.2 dBFS, so it plays at its own level: any louder would clip.
 */
export const MUSIC = {
  file: 'music/dirty-thinkin.mp3',
  /** Seconds into the track at which it starts playing. */
  start: 0,
  /** Seconds of silence at the start of the video before the track comes in. */
  delay: 0.5,
  volume: 1,
  /** Seconds the track fades out over, at the end of the video. */
  fadeOut: 2.5,
};

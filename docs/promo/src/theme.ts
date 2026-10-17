import { loadFont } from './fonts';

/** keepIT's Dim theme, as web/src/index.css has it, so the video looks like the app it shows. */
export const theme = {
  canvas: '#0a0a0b',
  surface: '#18181b',
  text: '#ececee',
  muted: '#a1a1aa',
  /** The default accent ("forest"). A fill: text on it is black. */
  accent: '#41aa79',
  /** Note colours, for the cards drifting in the background. */
  notes: ['#0c3855', '#42330a', '#0c3d21', '#3f2166', '#52260d', '#0c3b3c', '#531a27'],
};

/** Inter, the web app's font, loaded before any frame is drawn. */
export const fontFamily = loadFont();

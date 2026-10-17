import '@fontsource-variable/inter';
import { continueRender, delayRender } from 'remotion';

/**
 * Inter, as the web app ships it (@fontsource-variable/inter). A font loads lazily, the first time
 * text asks for it, so a frame rendered before then would show the fallback font: rendering waits
 * until the weights the video uses are in.
 */
export function loadFont(): string {
  const handle = delayRender('Loading Inter');
  Promise.all(['400', '600', '700', '800'].map((w) => document.fonts.load(`${w} 40px "Inter Variable"`)))
    .then(() => continueRender(handle))
    .catch(() => continueRender(handle));
  return "'Inter Variable', 'Inter', system-ui, sans-serif";
}

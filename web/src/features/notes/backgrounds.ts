/**
 * Note backgrounds: images tiled over a note's colour, chosen by name (`NoteDto.background`).
 *
 * The images live in the repository's `backgrounds/` folder, which the web bundle and the Android
 * app both ship — the server stores only the name (see `keepITCore/Notes/NoteBackground.cs` and
 * `backgrounds/README.md`). Each image, `<name>.<ext>`, is a **stencil**: only its shape (its
 * alpha) is used, and the app paints it in the note's own pattern colour (`--note-pattern` in
 * index.css), so one file suits every colour and theme.
 *
 * Files are drawn at 2×: a 200 px wide file tiles 100 CSS px wide, sharp on a high-density screen.
 * This module is the pure part (names, labels, the CSS); `backgroundImages.ts` is the part that
 * asks Vite for the files.
 */

/** The shape of a background name. The server holds names to the same pattern. */
const NAME = /^[a-z0-9]+(-[a-z0-9]+)*$/;

/** The image formats a background may be in: what every browser and Android decode alike, with alpha. */
const FILE = /^(?<name>[^/.]+)\.(png|webp)$/;

/** One background the user can pick. */
export interface NoteBackgroundImage {
  /** What `NoteDto.background` holds. */
  name: string;
  /** For people: the name with its hyphens as spaces, capitalised ("Grid dots"). */
  label: string;
  /** The image's URL. */
  url: string;
}

/** "grid-dots" → "Grid dots". */
export function backgroundLabel(name: string): string {
  const words = name.replace(/-/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * The backgrounds a set of files makes, sorted by name: `files` maps each file's path to its URL,
 * as Vite's `import.meta.glob` returns them. A file whose name isn't a background name, or isn't a
 * PNG or WebP, is left out (and named on the console, so its author sees why).
 */
export function backgroundsFromFiles(files: Record<string, string>): NoteBackgroundImage[] {
  const found = new Map<string, string>();
  for (const [path, url] of Object.entries(files)) {
    const file = path.split('/').pop() ?? '';
    const name = FILE.exec(file)?.groups?.name;
    if (!name || !NAME.test(name) || name.length > 64) {
      console.warn(`backgrounds/${file} is not a background: see backgrounds/README.md.`);
      continue;
    }
    found.set(name, url);
  }
  return [...found.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, url]) => ({ name, label: backgroundLabel(name), url }));
}

/**
 * The custom properties `.note-tile` (index.css) draws a background from, or undefined for none —
 * a name this build has no image for included: it draws as no background, and the name stays on
 * the note for a client that has it. `ink` is the note colour's border token, which the pattern is
 * painted in (at the theme's `--note-pattern` strength).
 */
export function backgroundVars(
  name: string | null | undefined,
  ink: string,
  backgrounds: NoteBackgroundImage[],
): Record<string, string> | undefined {
  const bg = name ? backgrounds.find((b) => b.name === name) : undefined;
  if (!bg) return undefined;
  return { '--tile': `image-set(url("${bg.url}") 2x)`, '--tile-ink': ink };
}

# Note backgrounds

Every image in this folder is a background a note can have, tiled over the note's colour. The web
app and the Android app both ship this folder: a new image appears in both with the next build,
and the server stores only the name. See ARCHITECTURE.md → Note backgrounds.

## Files

- `<name>.png` (or `.webp`): one file per background, for every colour and theme.

`<name>` is lowercase letters, digits and single hyphens, at most 64 characters: `lined`,
`squared-paper`, `dots-2`. It is what the note stores, so **never rename or delete an image that
has shipped**: notes that use it lose it. The picker shows the name as words ("Squared paper").
A file with any other name or format is left out, with a warning in the browser console.

## Drawing them

- **A stencil.** The apps use only the image's *shape* (its alpha) and paint it in the note's own
  pattern colour: a shade of the note's colour, set per theme (`--note-pattern` in
  `web/src/index.css`). So draw in **opaque black** on a **transparent** background. Anti-aliased
  edges are fine; faint or coloured strokes are not, since the apps decide how strong it looks.
  One file then suits all ten colours and all three themes.
- **Tiles.** The image repeats from the card's top left corner, so it must join up with itself
  across both edges. A pattern meant to run one way only (ruled lines) can be one line high and
  any width.
- **At 2×.** A file is drawn at half its pixel size, so it stays sharp on high-density screens:
  a 128 × 48 px file tiles as 64 × 24. On Android the same file is 64 × 24 dp. Body text has a
  line height of about 20, so ruled lines every 24 (48 px in the file) suit it.
- **Quiet.** Text sits on top of it. Keep strokes thin and leave plenty of empty space, and keep
  each file small: it ships in the app, and a few KB is plenty for a tile (64 KB at most).
- No SVG: Android can't draw it as a tile.

After adding an image, run the web unit tests and the Android unit tests: they check every file
here follows these rules (names, formats, size, opaque strokes).

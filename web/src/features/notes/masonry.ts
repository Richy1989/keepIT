import { MAX_PREVIEW_ITEMS } from './NoteCard';
import type { NoteDto } from '../../api/types';

/**
 * Column assignment for the notes grid.
 *
 * The grid used to be CSS `columns`, which fills **column-major**: notes 1, 2, 3 run *down* the
 * left column before anything reaches the second one. The list arrives newest-first, so the
 * ordering the user actually saw was a vertical snake — "my newest note" could be anywhere down
 * the left edge, and adding one note re-flowed every column after it.
 *
 * So we assign columns ourselves: walk the notes in order and drop each into whichever column is
 * currently shortest, ties going left. Early on every column is empty, so the first row fills
 * left-to-right; after that each note lands wherever it keeps the grid level. Reading order is
 * therefore row-major *and* the columns stay balanced — which is what Keep does, and what CSS
 * columns can't be made to do.
 *
 * Balancing needs a height per note before the browser has laid anything out, so
 * {@link estimateCardHeight} approximates one from the note's content. The estimate only affects
 * how even the column bottoms look; it can't affect ordering, which is fixed by the walk above.
 * Being a little off means a slightly ragged bottom edge, not a wrong grid.
 */

/** Roughly how wide one column renders at each column count, for the image-height estimate. */
const APPROX_COLUMN_WIDTH: Record<number, number> = { 1: 560, 2: 460, 3: 360, 4: 276 };

/** Card chrome: `p-4` top and bottom, plus the footer row of tools and the timestamp. */
const CARD_CHROME = 32 + 34;
const TITLE_HEIGHT = 24;
const TEXT_LINE_HEIGHT = 20;
const CHECKLIST_ROW_HEIGHT = 22;
/** Characters that fit on one line of body text, per 100px of column width. */
const CHARS_PER_LINE_PER_100PX = 14;
/** NoteCard truncates the preview body at 600 characters. */
const MAX_PREVIEW_CHARS = 600;
/** NoteImage caps a portrait hero at this multiple of its width. */
const MAX_HERO_ASPECT = 1.4;

/** Approximates a card's rendered height in px. See the module comment for why it can be loose. */
export function estimateCardHeight(note: NoteDto, columnWidth: number): number {
  let height = CARD_CHROME;

  const hero = note.media[0];
  if (hero) {
    // Mirrors NoteImage: the box is sized from the stored dimensions, portrait capped at 1.4.
    const ratio = hero.width > 0 && hero.height > 0 ? hero.height / hero.width : 1;
    height += columnWidth * Math.min(ratio, MAX_HERO_ASPECT);
    // A title over a hero is an overlay, so it costs nothing extra.
    if (note.title) height += 0;
  } else if (note.title) {
    height += TITLE_HEIGHT;
  }

  if (note.type === 'Checklist') {
    const shown = Math.min(note.checklistItems.length, MAX_PREVIEW_ITEMS);
    height += shown * CHECKLIST_ROW_HEIGHT;
    if (note.checklistItems.length > MAX_PREVIEW_ITEMS) height += CHECKLIST_ROW_HEIGHT; // "+N more"
    if (note.checklistItems.length > 0) height += CHECKLIST_ROW_HEIGHT; // "x/y done"
  } else if (note.body) {
    const chars = Math.min(note.body.length, MAX_PREVIEW_CHARS);
    const perLine = Math.max((columnWidth / 100) * CHARS_PER_LINE_PER_100PX, 1);
    // Hard newlines break a line early, so they each cost at least one line of their own.
    const wrapped = Math.ceil(chars / perLine);
    const explicit = (note.body.match(/\n/g) ?? []).length;
    height += Math.max(wrapped, explicit + 1) * TEXT_LINE_HEIGHT;
  } else if (!note.title) {
    height += TEXT_LINE_HEIGHT; // the "Empty note" placeholder
  }

  if (note.remindAtUtc) height += CHECKLIST_ROW_HEIGHT; // the reminder chip sits above the footer

  return height;
}

/**
 * Splits `notes` into `columnCount` columns, preserving the source order left-to-right then down.
 * Returns exactly `columnCount` arrays, some possibly empty (fewer notes than columns).
 */
export function distributeIntoColumns(notes: NoteDto[], columnCount: number): NoteDto[][] {
  const columns: NoteDto[][] = Array.from({ length: columnCount }, () => []);
  const heights = new Array<number>(columnCount).fill(0);
  const width = APPROX_COLUMN_WIDTH[columnCount] ?? 320;

  for (const note of notes) {
    // Ties go to the leftmost column, which is what makes the first row read left-to-right.
    let target = 0;
    for (let i = 1; i < columnCount; i++) {
      if (heights[i] < heights[target]) target = i;
    }
    columns[target].push(note);
    heights[target] += estimateCardHeight(note, width) + 16; // + the gap below the card
  }

  return columns;
}

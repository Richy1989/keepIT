import { describe, expect, it } from 'vitest';
import { distributeIntoColumns, estimateCardHeight, MAX_PREVIEW_ITEMS } from './masonry';
import type { NoteDto } from '../../api/types';

/**
 * The property that matters here is **order**: the grid moved off CSS `columns` precisely because
 * those fill column-major, so a newest-first list read as a vertical snake down the left edge.
 * The height estimate only decides how even the bottom edge looks, so it's asserted loosely —
 * tightening it to exact pixels would just pin the current heuristic in place.
 */

let seq = 0;
function note(partial: Partial<NoteDto> = {}): NoteDto {
  seq += 1;
  return {
    id: `note-${seq}`,
    type: 'Text',
    title: null,
    body: null,
    color: null,
    isPinned: false,
    isArchived: false,
    isTrashed: false,
    remindAtUtc: null,
    reminderRecurrence: null,
    reminderFired: false,
    createdAtUtc: '2026-09-22T10:00:00Z',
    updatedAtUtc: '2026-09-22T10:00:00Z',
    isOwner: true,
    role: null,
    canEdit: true,
    isShared: false,
    checklistItems: [],
    media: [],
    listIds: [],
    ...partial,
  };
}

function items(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    id: `item-${i}`,
    text: `item ${i}`,
    isChecked: false,
    order: i,
  }));
}

function media(width: number, height: number) {
  return [{ id: 'm1', width, height, byteSize: 1000, order: 0, createdAtUtc: '2026-09-22T10:00:00Z' }];
}

describe('distributeIntoColumns', () => {
  it('returns exactly the requested number of columns, even with fewer notes', () => {
    const columns = distributeIntoColumns([note(), note()], 4);
    expect(columns).toHaveLength(4);
    expect(columns.flat()).toHaveLength(2);
  });

  it('keeps every note exactly once', () => {
    const input = Array.from({ length: 17 }, () => note({ body: 'x'.repeat(40) }));
    const out = distributeIntoColumns(input, 3).flat();
    expect(out).toHaveLength(input.length);
    expect(new Set(out.map((n) => n.id)).size).toBe(input.length);
  });

  it('fills the first row left to right', () => {
    // The regression this whole module exists to prevent: with CSS columns these four went *down*
    // the first column, so the newest note was not where the eye starts.
    const input = [note(), note(), note(), note()];
    const columns = distributeIntoColumns(input, 4);
    expect(columns.map((c) => c[0]?.id)).toEqual(input.map((n) => n.id));
  });

  it('reads row-major down the grid, not column-major', () => {
    const input = Array.from({ length: 8 }, () => note({ body: 'same height everywhere' }));
    const columns = distributeIntoColumns(input, 4);
    // Equal heights, so each column takes one note per pass: 1-2-3-4 then 5-6-7-8.
    expect(columns.map((c) => c.map((n) => n.id))).toEqual([
      [input[0].id, input[4].id],
      [input[1].id, input[5].id],
      [input[2].id, input[6].id],
      [input[3].id, input[7].id],
    ]);
  });

  it('preserves source order within a column', () => {
    const input = Array.from({ length: 12 }, (_, i) => note({ body: `body ${i}`.repeat(i + 1) }));
    const order = new Map(input.map((n, i) => [n.id, i]));
    for (const column of distributeIntoColumns(input, 3)) {
      const positions = column.map((n) => order.get(n.id)!);
      expect(positions).toEqual([...positions].sort((a, b) => a - b));
    }
  });

  it('puts a tall note beside short ones rather than stacking them', () => {
    const tall = note({ type: 'Checklist', title: 'tall', checklistItems: items(8) });
    const shorts = [note({ body: 'a' }), note({ body: 'b' }), note({ body: 'c' })];
    const columns = distributeIntoColumns([tall, ...shorts], 2);
    const tallColumn = columns.findIndex((c) => c.some((n) => n.id === tall.id));
    expect(columns[tallColumn]).toHaveLength(1);
    expect(columns[1 - tallColumn]).toHaveLength(3);
  });

  it('handles an empty list and a single column', () => {
    expect(distributeIntoColumns([], 4)).toEqual([[], [], [], []]);
    const input = [note(), note(), note()];
    expect(distributeIntoColumns(input, 1)).toEqual([input]);
  });
});

describe('estimateCardHeight', () => {
  const W = 300;

  it('gives an empty note a small but non-zero height', () => {
    const h = estimateCardHeight(note(), W);
    expect(h).toBeGreaterThan(0);
    expect(h).toBeLessThan(150);
  });

  it('grows with body length', () => {
    const short = estimateCardHeight(note({ body: 'one line' }), W);
    const long = estimateCardHeight(note({ body: 'word '.repeat(80) }), W);
    expect(long).toBeGreaterThan(short);
  });

  it('counts hard newlines, which wrap-estimation alone would miss', () => {
    const flowing = estimateCardHeight(note({ body: 'abcde abcde abcde' }), W);
    const broken = estimateCardHeight(note({ body: 'a\nb\nc\nd\ne\nf\ng\nh' }), W);
    expect(broken).toBeGreaterThan(flowing);
  });

  it('stops growing once the body passes the card preview limit', () => {
    const at = estimateCardHeight(note({ body: 'x'.repeat(600) }), W);
    const past = estimateCardHeight(note({ body: 'x'.repeat(6000) }), W);
    expect(past).toBe(at);
  });

  it('stops growing past the checklist preview limit, bar the "+N more" row', () => {
    const at = estimateCardHeight(note({ type: 'Checklist', checklistItems: items(MAX_PREVIEW_ITEMS) }), W);
    const past = estimateCardHeight(note({ type: 'Checklist', checklistItems: items(MAX_PREVIEW_ITEMS + 40) }), W);
    expect(past).toBeGreaterThan(at);
    expect(past - at).toBeLessThan(40); // one collapsed row, not forty
  });

  it('scales a hero image by its aspect ratio', () => {
    const wide = estimateCardHeight(note({ media: media(1600, 900) }), W);
    const square = estimateCardHeight(note({ media: media(1000, 1000) }), W);
    expect(square).toBeGreaterThan(wide);
  });

  it('caps a very tall portrait so one receipt cannot claim a whole column', () => {
    const portrait = estimateCardHeight(note({ media: media(1000, 4000) }), W);
    expect(portrait).toBeLessThan(W * 1.4 + 120);
  });

  it('survives media with zero dimensions', () => {
    expect(() => estimateCardHeight(note({ media: media(0, 0) }), W)).not.toThrow();
    expect(estimateCardHeight(note({ media: media(0, 0) }), W)).toBeGreaterThan(0);
  });

  it('adds room for a reminder chip', () => {
    const without = estimateCardHeight(note({ body: 'hi' }), W);
    const with_ = estimateCardHeight(note({ body: 'hi', remindAtUtc: '2026-12-01T09:00:00Z' }), W);
    expect(with_).toBeGreaterThan(without);
  });
});

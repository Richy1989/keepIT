import { useEffect, useRef, useState, type DragEvent } from 'react';
import { cn } from '../../lib/cn';
import { CheckIcon, GripVerticalIcon, PlusIcon, XIcon } from '../../components/icons';
import { checklistRows } from './checklist';
import type { ChecklistItemDto } from '../../api/types';

/**
 * Editable checklist: toggle, edit, add, remove, and **reorder by drag-and-drop**. Controlled via
 * `items` / `onChange`.
 *
 * `items` is the *home* order; rows are rendered in display order — unchecked first, checked at the
 * bottom (see `checklistRows`) — so ticking a box drops the row to the bottom and unticking returns
 * it to the slot it came from. Every edit therefore addresses an item by its **stored** index while
 * drag-and-drop works in **display** positions; the two are kept apart carefully below.
 *
 * Rows are dragged by the grip handle (so the text inputs stay usable); on drop the home order is
 * permuted and each item's `order` renumbered. Dragging across the checked/unchecked boundary is
 * refused (see `move`). The order persists with the rest of the note when the composer/editor saves
 * on close (the server renumbers `Order` from the array index).
 *
 * Keyboard: Enter adds a new row below the current one and focuses it; Backspace on an empty row
 * removes it and moves the caret to the previous row.
 */
export function ChecklistEditor({
  items,
  onChange,
}: {
  items: ChecklistItemDto[];
  onChange: (items: ChecklistItemDto[]) => void;
}) {
  // Display position of the row being dragged, and the row it's hovering over (for the drop line).
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);

  // Refs to each row's text input (by display position), plus a pending one to focus after a row is
  // added. Using a ref (not state) for the pending index keeps the focus side-effect out of React's
  // render/state cycle.
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);
  const pendingFocus = useRef<number | null>(null);

  // After items change, focus a freshly-added row if one is pending (Enter / "Add item").
  useEffect(() => {
    if (pendingFocus.current === null) return;
    inputRefs.current[pendingFocus.current]?.focus();
    pendingFocus.current = null;
  }, [items]);

  const rows = checklistRows(items);

  const update = (stored: number, patch: Partial<ChecklistItemDto>) =>
    onChange(items.map((it, idx) => (idx === stored ? { ...it, ...patch } : it)));
  const remove = (stored: number) => onChange(items.filter((_, idx) => idx !== stored));

  /** Display position of the row stored at `stored`, once `next` is committed. */
  const displayPositionOf = (next: ChecklistItemDto[], stored: number) =>
    checklistRows(next).findIndex((r) => r.index === stored);

  function add() {
    const next = [...items, { id: null, text: '', isChecked: false, order: items.length }];
    pendingFocus.current = displayPositionOf(next, next.length - 1);
    onChange(next);
  }

  /** Inserts a new empty item right after the item stored at `stored`, renumbers `order`, focuses it. */
  function insertAfter(stored: number) {
    const next = [...items];
    next.splice(stored + 1, 0, { id: null, text: '', isChecked: false, order: 0 });
    // The new row is unchecked, so it isn't necessarily displayed directly below the current row.
    pendingFocus.current = displayPositionOf(next, stored + 1);
    onChange(next.map((it, idx) => ({ ...it, order: idx })));
  }

  /** A drop is only meaningful within one group — see [move]. */
  const canDropOn = (from: number, to: number) => rows[from]?.item.isChecked === rows[to]?.item.isChecked;

  /**
   * Moves a row between display positions and renumbers `order` to match.
   *
   * The move is applied to the **stored** array, not the displayed one: rewriting home order from
   * what's on screen would shove every checked row to the end, silently resetting the slots they
   * return to on untick. Dragging *across* the checked/unchecked boundary is refused — the display
   * sort would undo it on the next render, so it can only ever look broken.
   */
  function move(from: number, to: number) {
    if (from === to || !canDropOn(from, to)) return;
    const a = rows[from].index;
    const b = rows[to].index;
    const next = [...items];
    const [moved] = next.splice(a, 1);
    next.splice(a < b ? b - 1 : b, 0, moved);
    onChange(next.map((it, idx) => ({ ...it, order: idx })));
  }

  function onDrop(e: DragEvent, target: number) {
    e.preventDefault();
    if (dragIndex !== null) move(dragIndex, target);
    setDragIndex(null);
    setOverIndex(null);
  }

  return (
    <div className="space-y-1">
      {rows.map(({ item: it, index: stored }, i) => (
        <div
          key={it.id ?? `new-${stored}`}
          onDragOver={(e) => {
            // No drop line for a target we'd refuse — the UI never promises a move it won't make.
            if (dragIndex === null || !canDropOn(dragIndex, i)) return;
            e.preventDefault();
            setOverIndex(i);
          }}
          onDrop={(e) => onDrop(e, i)}
          className={cn(
            'group flex items-center gap-1.5 rounded transition',
            dragIndex === i && 'opacity-40',
            overIndex === i && dragIndex !== null && dragIndex !== i && 'ring-1 ring-accent/60',
          )}
        >
          <span
            draggable
            onDragStart={(e) => {
              setDragIndex(i);
              e.dataTransfer.effectAllowed = 'move';
            }}
            onDragEnd={() => {
              setDragIndex(null);
              setOverIndex(null);
            }}
            aria-label="Drag to reorder"
            className="shrink-0 cursor-grab text-text-faint opacity-0 transition hover:text-text-muted active:cursor-grabbing group-hover:opacity-100 touch:opacity-100"
          >
            <GripVerticalIcon className="text-base" />
          </span>
          <button
            type="button"
            onClick={() => update(stored, { isChecked: !it.isChecked })}
            className={cn(
              'grid size-4 shrink-0 place-items-center rounded border transition',
              it.isChecked ? 'border-accent bg-accent text-black' : 'border-border-strong hover:border-text-muted',
            )}
          >
            {it.isChecked && <CheckIcon className="text-[10px]" />}
          </button>
          <input
            ref={(el) => {
              inputRefs.current[i] = el;
            }}
            value={it.text}
            onChange={(e) => update(stored, { text: e.target.value })}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                insertAfter(stored);
              } else if (e.key === 'Backspace' && it.text === '' && i > 0) {
                // Empty row + Backspace removes it and drops the caret into the previous row.
                e.preventDefault();
                pendingFocus.current = i - 1;
                remove(stored);
              }
            }}
            placeholder="List item"
            className={cn(
              'flex-1 bg-transparent text-sm outline-none placeholder:text-text-faint',
              it.isChecked && 'text-text-faint line-through',
            )}
          />
          <button
            type="button"
            onClick={() => remove(stored)}
            aria-label="Remove item"
            className="text-text-faint opacity-0 transition hover:text-text group-hover:opacity-100 touch:opacity-100"
          >
            <XIcon className="text-sm" />
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={add}
        className="mt-1 flex items-center gap-2 text-sm text-text-muted transition hover:text-text"
      >
        <PlusIcon className="text-base" /> Add item
      </button>
    </div>
  );
}

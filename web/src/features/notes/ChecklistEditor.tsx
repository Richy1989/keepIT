import { useEffect, useRef, useState, type DragEvent } from 'react';
import { cn } from '../../lib/cn';
import { CheckIcon, GripVerticalIcon, PlusIcon, RestoreIcon, XIcon } from '../../components/icons';
import { MAX_CHECKLIST_ITEMS, checklistRows, splitPastedRow, suggestFromChecked } from './checklist';
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
 * "Add item" sits between the two groups, under the last unchecked row: that is where a new row
 * lands, and in a list with ticked items it was otherwise below all of them, away from the rows
 * still being written.
 *
 * Rows are dragged by the grip handle (so the text inputs stay usable); on drop the home order is
 * permuted and each item's `order` renumbered. Dragging across the checked/unchecked boundary is
 * refused (see `move`). The order persists with the rest of the note when the composer/editor saves
 * on close (the server renumbers `Order` from the array index).
 *
 * Keyboard: Enter adds a new row below the current one and focuses it; Backspace on an empty row
 * removes it and moves the caret to the previous row. Pasting several lines makes a row of each
 * (see `splitPastedRow`), where the row's one-line `<input>` would flatten them into itself.
 *
 * Typing a new row offers a ticked row it could be instead (see `suggestFromChecked`), just under
 * it: a click or Tab takes it, and that row comes back unticked where the new one was being typed,
 * rather than the list holding the item twice.
 *
 * A checklist holds `MAX_CHECKLIST_ITEMS` rows, the server's limit: at it "Add item" gives way to a
 * line saying so, and neither Enter nor a paste adds more. A save past it would be refused and
 * rolled back, taking the rest of the edit with it.
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
  // render/state cycle. A paste also says where in that row the caret goes: behind what it pasted.
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);
  const pendingFocus = useRef<number | null>(null);
  const pendingCaret = useRef<number | null>(null);

  // The new row being typed into, by stored index: the one row a ticked one is offered for. Typing
  // sets it, not focus, so the offer comes and goes with the typing; leaving the row, or anything
  // that moves rows about, clears it. New means not saved yet (no id), so editing an existing row
  // offers nothing, and nor does a row that has just taken an offer: it has that row's id.
  const [typing, setTyping] = useState<number | null>(null);

  // After items change, focus a freshly-added row if one is pending (Enter / "Add item" / a paste).
  useEffect(() => {
    if (pendingFocus.current === null) return;
    const input = inputRefs.current[pendingFocus.current];
    input?.focus();
    if (pendingCaret.current !== null) input?.setSelectionRange(pendingCaret.current, pendingCaret.current);
    pendingFocus.current = null;
    pendingCaret.current = null;
  }, [items]);

  const rows = checklistRows(items);
  // Display position of the first checked row: "Add item" goes right before it.
  const firstChecked = rows.findIndex((r) => r.item.isChecked);
  const addAt = firstChecked === -1 ? rows.length : firstChecked;
  const full = items.length >= MAX_CHECKLIST_ITEMS;
  const offer = typing !== null && items[typing] && !items[typing].id ? suggestFromChecked(items, typing) : null;

  const update = (stored: number, patch: Partial<ChecklistItemDto>) =>
    onChange(items.map((it, idx) => (idx === stored ? { ...it, ...patch } : it)));
  const remove = (stored: number) => onChange(items.filter((_, idx) => idx !== stored));

  /**
   * The new row stored at `typed` takes the ticked row stored at `offered`: it becomes that row,
   * unticked, in the new row's place (where the user is typing), and the ticked one is gone. The
   * row keeps its id, so the server sees the item come back rather than a new one.
   */
  function take(typed: number, offered: number) {
    const restored = { ...items[offered], isChecked: false };
    const next = items.map((it, idx) => (idx === typed ? restored : it)).filter((_, idx) => idx !== offered);
    const at = offered < typed ? typed - 1 : typed;
    // The row's key changes with its id, so the caret has to be put back, behind the text.
    pendingFocus.current = displayPositionOf(next, at);
    pendingCaret.current = restored.text.length;
    setTyping(null);
    onChange(next.map((it, idx) => ({ ...it, order: idx })));
  }

  /** Display position of the row stored at `stored`, once `next` is committed. */
  const displayPositionOf = (next: ChecklistItemDto[], stored: number) =>
    checklistRows(next).findIndex((r) => r.index === stored);

  function add() {
    if (full) return;
    setTyping(null);
    const next = [...items, { id: null, text: '', isChecked: false, order: items.length }];
    pendingFocus.current = displayPositionOf(next, next.length - 1);
    onChange(next);
  }

  /** Inserts a new empty item right after the item stored at `stored`, renumbers `order`, focuses it. */
  function insertAfter(stored: number) {
    if (full) return;
    setTyping(null);
    const next = [...items];
    next.splice(stored + 1, 0, { id: null, text: '', isChecked: false, order: 0 });
    // The new row is unchecked, so it isn't necessarily displayed directly below the current row.
    pendingFocus.current = displayPositionOf(next, stored + 1);
    onChange(next.map((it, idx) => ({ ...it, order: idx })));
  }

  /**
   * Pastes text with line breaks into the row stored at `stored`, whose input has the selection
   * `start`–`end`: the selection is replaced, each line becomes a row (see `splitPastedRow`), and
   * the caret ends up behind the last thing pasted.
   */
  function pasteLines(stored: number, start: number, end: number, text: string) {
    const row = items[stored].text;
    setTyping(null);
    const { rows: texts, caret } = splitPastedRow(
      row.slice(0, start) + text,
      row.slice(end),
      MAX_CHECKLIST_ITEMS - items.length,
    );
    const next = [...items];
    next[stored] = { ...next[stored], text: texts[0] };
    next.splice(
      stored + 1,
      0,
      ...texts.slice(1).map((text) => ({ id: null, text, isChecked: false, order: 0 })),
    );
    pendingFocus.current = displayPositionOf(next, stored + texts.length - 1);
    pendingCaret.current = caret;
    onChange(next.map((it, idx) => ({ ...it, order: idx })));
  }

  /**
   * A drop is only meaningful within one group — see [move]. Both positions must exist: two
   * out-of-range indices would otherwise both read `undefined` and compare equal, green-lighting a
   * move that then throws.
   */
  const canDropOn = (from: number, to: number) =>
    from >= 0 &&
    from < rows.length &&
    to >= 0 &&
    to < rows.length &&
    !!rows[from].item.isChecked === !!rows[to].item.isChecked;

  /**
   * Moves a row between display positions and renumbers `order` to match.
   *
   * The move is applied to the **stored** array, not the displayed one: rewriting home order from
   * what's on screen would shove every checked row to the end, silently resetting the slots they
   * return to on untick. Dragging *across* the checked/unchecked boundary is refused — the display
   * sort would undo it on the next render, so it can only ever look broken.
   *
   * Removing the source first shifts everything after it left by one, so the target's slot in the
   * shortened array *is* `b` — no adjustment for the direction of travel.
   */
  function move(from: number, to: number) {
    if (from === to || !canDropOn(from, to)) return;
    setTyping(null);
    const a = rows[from].index;
    const b = rows[to].index;
    const next = [...items];
    const [moved] = next.splice(a, 1);
    next.splice(b, 0, moved);
    onChange(next.map((it, idx) => ({ ...it, order: idx })));
  }

  function onDrop(e: DragEvent, target: number) {
    e.preventDefault();
    if (dragIndex !== null) move(dragIndex, target);
    setDragIndex(null);
    setOverIndex(null);
  }

  const addButton = full ? (
    <p key="add-item" className="py-0.5 text-xs text-text-muted">
      A checklist holds up to {MAX_CHECKLIST_ITEMS} items.
    </p>
  ) : (
    <button
      key="add-item"
      type="button"
      onClick={add}
      className="flex items-center gap-2 py-0.5 text-sm text-text-muted transition hover:text-text"
    >
      <PlusIcon className="text-base" /> Add item
    </button>
  );

  return (
    <div className="space-y-1">
      {rows.flatMap(({ item: it, index: stored }, i) => [
        ...(i === addAt ? [addButton] : []),
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
            overIndex === i && dragIndex !== null && dragIndex !== i && 'ring-1 ring-accent-ink/60',
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
            role="checkbox"
            aria-checked={!!it.isChecked}
            aria-label={it.text || 'List item'}
            onClick={() => update(stored, { isChecked: !it.isChecked })}
            className={cn(
              'grid size-4 shrink-0 place-items-center rounded border transition',
              it.isChecked ? 'border-accent bg-accent text-black' : 'border-border-control hover:border-text-muted',
            )}
          >
            {it.isChecked && <CheckIcon className="text-[10px]" />}
          </button>
          <input
            ref={(el) => {
              inputRefs.current[i] = el;
            }}
            value={it.text}
            onChange={(e) => {
              update(stored, { text: e.target.value });
              setTyping(stored);
            }}
            onBlur={() => setTyping(null)}
            onPaste={(e) => {
              // One line is the browser's to paste, and a copied picture is the note's to attach
              // (the composer and the editor take it from the same event, further up).
              const text = e.clipboardData.getData('text/plain');
              if (!/[\r\n]/.test(text) || e.clipboardData.files.length > 0) return;
              e.preventDefault();
              const input = e.currentTarget;
              pasteLines(stored, input.selectionStart ?? it.text.length, input.selectionEnd ?? it.text.length, text);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Tab' && !e.shiftKey && stored === typing && offer !== null) {
                e.preventDefault();
                take(stored, offer);
              } else if (e.key === 'Enter') {
                e.preventDefault();
                insertAfter(stored);
              } else if (e.key === 'Backspace' && it.text === '' && i > 0) {
                // Empty row + Backspace removes it and drops the caret into the previous row.
                e.preventDefault();
                pendingFocus.current = i - 1;
                setTyping(null);
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
        </div>,
        ...(stored === typing && offer !== null
          ? [
              <button
                key="offer"
                type="button"
                // Pressing it would take the caret out of the row, and leaving the row ends the offer.
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => take(stored, offer)}
                aria-label={`Bring back “${items[offer].text}” from the ticked items`}
                className="ml-11 flex max-w-[calc(100%-2.75rem)] items-center gap-1.5 py-0.5 text-left text-xs text-text-muted transition hover:text-text"
              >
                <RestoreIcon className="shrink-0 text-sm" />
                <span className="truncate">{items[offer].text}</span>
                <span className="shrink-0 text-text-faint">· from ticked</span>
                <kbd className="shrink-0 rounded border border-border-control px-1 font-sans text-[10px] text-text-faint touch:hidden">
                  Tab
                </kbd>
              </button>,
            ]
          : []),
      ])}
      {addAt === rows.length && addButton}
    </div>
  );
}

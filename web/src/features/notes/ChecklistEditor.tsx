import { Fragment, useEffect, useRef, useState, type DragEvent as ReactDragEvent } from 'react';
import { cn } from '../../lib/cn';
import { CheckIcon, GripVerticalIcon, PlusIcon, RestoreIcon, XIcon } from '../../components/icons';
import {
  MAX_CHECKLIST_ITEMS,
  canIndent,
  canOutdent,
  checklistSections,
  childIndicesOf,
  indentOf,
  insertionIndexAfter,
  isSubItem,
  newItemIndentAfter,
  normalizeIndents,
  setItemChecked,
  setItemIndent,
  splitPastedRow,
  suggestFromChecked,
} from './checklist';
import type { ChecklistItemDto } from '../../api/types';

/** Horizontal travel, in pixels, that turns a grip drag into an indent/outdent instead of a reorder. */
const INDENT_DRAG_PX = 28;

/** How far the dragged row may slide sideways as a preview; one indent step, like `ml-6`. */
const INDENT_PREVIEW_PX = 24;

/**
 * Editable checklist: toggle, edit, add, remove, **reorder by drag-and-drop**, and indent/outdent by
 * dragging the same grip horizontally (like Google Keep).
 *
 * `items` is the *home* order; the display is split into an active (unchecked) section and a
 * completed (checked) one by [checklistSections], both keeping the home order. Ticking a box only
 * moves the row between the two sections, never the stored order; unticking returns it to the slot
 * it came from. Every edit therefore addresses an item by its **stored** index, while focus is
 * tracked by **active-section position** because new rows are always unchecked.
 *
 * A partial group appears twice: the parent stays active with its remaining children, while the
 * completed section repeats its text as a greyed, non-interactive heading above the checked
 * children. Headings are presentation only — they are not items, are not focusable, and are not
 * drop targets.
 *
 * "Add item" sits after the last active row, which is where a new row lands; with no active rows it
 * is the first thing in the completed section.
 *
 * Rows are dragged by the grip handle (so the text inputs stay usable). A mostly vertical drag
 * reorders within the row's own section; a mostly horizontal one indents (right) or outdents
 * (left). A top-level parent moves with its whole group vertically, a sub-item only moves among
 * its siblings, and drops that would split a group are refused. Only real rows in the same checked
 * state are vertical drop targets, and the order persists with the rest of the note when the
 * composer/editor saves on close (the server renumbers `Order` from the array index).
 *
 * Keyboard: Enter adds a new row after the current row (after its whole group when it is a parent)
 * and focuses it; Backspace on an empty row removes it and moves the caret to the previous active
 * row. Pasting several lines makes a row of each (see `splitPastedRow`), where the row's one-line
 * `<input>` would flatten them into itself.
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
  // Stored index of the row being dragged, and the stored index of the row it's hovering over (for
  // the drop ring). Stored indices, because the sections are derived and a drop may move a group.
  const [dragStored, setDragStored] = useState<number | null>(null);
  const [overStored, setOverStored] = useState<number | null>(null);
  // Where the grip was first pressed. The horizontal travel is only known on drop.
  const dragStartX = useRef<number | null>(null);
  // Stored index of the valid row currently under the pointer, readable from the window dragover
  // listener (which runs after the row's own handler) to preview the indent the drop will take.
  const overStoredRef = useRef<number | null>(null);
  // How far the dragged row preview has moved sideways, snapped to one indent step. The row follows
  // the drop target's level so the user can see an indent/outdent before releasing it.
  const [dragDx, setDragDx] = useState(0);

  // Refs to each active row's text input (by active-section position), plus a pending one to focus
  // after a row is added. Using a ref (not state) for the pending index keeps the focus side-effect
  // out of React's render/state cycle. A paste also says where in that row the caret goes.
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);
  // The checklist box, used to walk its inputs in display order when the arrow keys move between
  // rows. DOM order is exactly the visual order, so headings and offers need no special handling.
  const containerRef = useRef<HTMLDivElement | null>(null);
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

  const { active, completed } = checklistSections(items);
  const full = items.length >= MAX_CHECKLIST_ITEMS;
  const offer = typing !== null && items[typing] && !items[typing].id ? suggestFromChecked(items, typing) : null;

  /** Active-section position of the row stored at `stored`, once `next` is committed. */
  const activePositionOf = (next: ChecklistItemDto[], stored: number) =>
    checklistSections(next).active.findIndex((row) => row.index === stored);

  /** Renumbers `order` to the array position, as every stored-order change must. */
  const renumbered = (next: ChecklistItemDto[]) => next.map((item, order) => ({ ...item, order }));

  const update = (stored: number, patch: Partial<ChecklistItemDto>) =>
    onChange(items.map((it, idx) => (idx === stored ? { ...it, ...patch } : it)));

  /** Applies the parent/child rule without touching the stored order (see [setItemChecked]). */
  const toggle = (stored: number, checked: boolean) => onChange(setItemChecked(items, stored, checked));

  /** Indents/outdents a row if the model allows it; a refused move leaves the list untouched. */
  function applyIndent(stored: number, indent: number) {
    const next = setItemIndent(items, stored, indent);
    if (next === items) return;
    setTyping(null);
    onChange(renumbered(next));
  }

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
    pendingFocus.current = activePositionOf(next, at);
    pendingCaret.current = restored.text.length;
    setTyping(null);
    onChange(renumbered(next));
  }

  function add() {
    if (full) return;
    setTyping(null);
    const next = [...items, { id: null, text: '', isChecked: false, indent: 0, order: items.length }];
    pendingFocus.current = activePositionOf(next, next.length - 1);
    onChange(next);
  }

  /** Inserts a new row after the row's whole group, at the depth Enter continues, and focuses it. */
  function insertAfter(stored: number) {
    if (full) return;
    setTyping(null);
    // Enter on an empty sub-item lifts it back out instead of nesting forever: once the last
    // sub-item has been typed, another Enter gives the user somewhere to write at the top level.
    if (isSubItem(items[stored]) && items[stored].text === '') {
      applyIndent(stored, 0);
      return;
    }
    const at = insertionIndexAfter(items, stored);
    const indent = newItemIndentAfter(items, stored);
    const next = [...items];
    next.splice(at, 0, { id: null, text: '', isChecked: false, indent, order: 0 });
    // The new row is unchecked, so it isn't necessarily displayed directly below the current row.
    pendingFocus.current = activePositionOf(next, at);
    onChange(renumbered(next));
  }

  /**
   * Pastes text with line breaks into the row stored at `stored`, whose input has the selection
   * `start`–`end`: the selection is replaced, each line becomes a row (see `splitPastedRow`), and
   * the caret ends up behind the last thing pasted. Extra lines join the row's group at the same
   * depth as a row Enter would create there.
   */
  function pasteLines(stored: number, start: number, end: number, text: string) {
    const row = items[stored].text;
    setTyping(null);
    const { rows: texts, caret } = splitPastedRow(
      row.slice(0, start) + text,
      row.slice(end),
      MAX_CHECKLIST_ITEMS - items.length,
    );
    const at = insertionIndexAfter(items, stored);
    const indent = newItemIndentAfter(items, stored);
    const next = [...items];
    next[stored] = { ...next[stored], text: texts[0] };
    next.splice(
      at,
      0,
      ...texts.slice(1).map((text) => ({ id: null, text, isChecked: false, indent, order: 0 })),
    );
    // With one line the row itself is the target; otherwise the last row inserted is.
    const focusStored = texts.length > 1 ? at + texts.length - 2 : stored;
    pendingFocus.current = activePositionOf(next, focusStored);
    pendingCaret.current = caret;
    onChange(renumbered(next));
  }

  /**
   * Whether `to` is a valid vertical drop target for the row/group at `from`.
   *
   * Only real rows in the same checked state may swap sections, as before. A top-level row (with
   * the children it carries) stays top-level and only lands next to another top-level row; a
   * sub-item may be dropped anywhere, and takes the target's indent below (see [move]). Heading
   * rows have no stored index, so they are never considered at all.
   */
  function canMoveVertical(from: number, to: number): boolean {
    if (from === to || from < 0 || from >= items.length || to < 0 || to >= items.length) return false;
    if (!!items[from].isChecked !== !!items[to].isChecked) return false;
    if (isSubItem(items[from])) return true;
    // A top-level row may land beside any other row, including a sub-item (which makes it one too).
    // It must not land inside its own group, where it would fold under itself.
    const groupEnd = insertionIndexAfter(items, from);
    return to < from || to >= groupEnd;
  }

  /**
   * Moves a row — or a top-level row's whole group — to the target's position and indent.
   *
   * The dropped row takes the **target's** indent: over a top-level row it becomes top-level, over
   * a sub-item it becomes that parent's sub-item. A parent moving onto a sub-item therefore
   * flattens its group to one level, its children already sitting there. The move is applied to the
   * **stored** array so the home order is preserved; the display sort only moves ticked rows.
   */
  function move(from: number, to: number) {
    if (!canMoveVertical(from, to)) return;
    setTyping(null);
    const fromIsSub = isSubItem(items[from]);
    const groupEnd = fromIsSub ? from + 1 : insertionIndexAfter(items, from);
    const group = items.slice(from, groupEnd);
    const targetIsSub = isSubItem(items[to]);
    // Beside a sub-item: after it when moving down, before it when moving up. Beside a top-level
    // row: outside its whole group, so a parent never lands between another parent and its children.
    const insertion = targetIsSub
      ? from < to
        ? to + 1
        : to
      : from < to
        ? insertionIndexAfter(items, to)
        : to;
    const next = [...items.slice(0, from), ...items.slice(groupEnd)];
    // Removing the source shifts everything after it, so a later insertion point moves left.
    const at = insertion - (from < insertion ? group.length : 0);
    // Only the moved row changes level; its children already sit at indent 1 (or the group is just
    // one row), which is what flattening a block under another group means.
    const indent = indentOf(items[to]);
    const moved = group.map((item, position) => (position === 0 ? { ...item, indent } : item));
    next.splice(at, 0, ...moved);
    onChange(renumbered(next));
  }

  function onDrop(e: ReactDragEvent, target: number) {
    e.preventDefault();
    const from = dragStored;
    const startX = dragStartX.current;
    const dx = startX === null ? 0 : e.clientX - startX;
    setDragStored(null);
    setOverStored(null);
    overStoredRef.current = null;
    setDragDx(0);
    dragStartX.current = null;
    if (from === null) return;
    // Sideways wins over reordering: the target row doesn't matter for an indent/outdent.
    if (Math.abs(dx) >= INDENT_DRAG_PX) {
      applyIndent(from, dx > 0 ? 1 : 0);
      return;
    }
    move(from, target);
  }

  // A horizontal drag often ends outside every row — outdenting to the left easily leaves the
  // checklist's own box — and a row's drop handler only sees drops landing on it. The window
  // catches those misses: a drop elsewhere still applies the horizontal move, while drops on a
  // row stop propagating and keep their vertical meaning.
  useEffect(() => {
    if (dragStored === null) return;

    const allowDrop = (e: DragEvent) => {
      e.preventDefault();
      const startX = dragStartX.current;
      if (startX === null) return;

      // Snap the preview to the slot it will take: the target's indent for a vertical drop, or one
      // step left/right for a horizontal one. Refused moves stay where they are.
      const dx = e.clientX - startX;
      const target = overStoredRef.current;
      if (dx >= INDENT_DRAG_PX) setDragDx(canIndent(items, dragStored) ? INDENT_PREVIEW_PX : 0);
      else if (dx <= -INDENT_DRAG_PX) setDragDx(canOutdent(items, dragStored) ? -INDENT_PREVIEW_PX : 0);
      else if (target !== null) {
        setDragDx((indentOf(items[target]) - indentOf(items[dragStored])) * INDENT_PREVIEW_PX);
      } else setDragDx(0);
    };
    const dropOutside = (e: DragEvent) => {
      const from = dragStored;
      const startX = dragStartX.current;
      const dx = startX === null ? 0 : e.clientX - startX;
      setDragStored(null);
      setOverStored(null);
      overStoredRef.current = null;
      setDragDx(0);
      dragStartX.current = null;
      if (Math.abs(dx) < INDENT_DRAG_PX) return;

      const next = setItemIndent(items, from, dx > 0 ? 1 : 0);
      if (next === items) return;
      setTyping(null);
      onChange(next.map((item, order) => ({ ...item, order })));
    };

    window.addEventListener('dragover', allowDrop);
    window.addEventListener('drop', dropOutside);
    return () => {
      window.removeEventListener('dragover', allowDrop);
      window.removeEventListener('drop', dropOutside);
    };
  }, [dragStored, items, onChange]);

  /**
   * Active-section position of the row to focus after Backspace removes the row at `stored`: the
   * row just above it in the active section, not the previous stored index (which may be a checked
   * row shown in the completed section).
   */
  function previousActivePosition(stored: number): number | null {
    const at = active.findIndex((row) => row.index === stored);
    if (at > 0) return at - 1;
    if (at === 0) return null;
    // The removed row is in the completed section: take the last active row before it.
    for (let i = active.length - 1; i >= 0; i -= 1) {
      if (active[i].index < stored) return i;
    }
    return null;
  }

  /**
   * Removes a row. A removed top-level parent promotes its direct children to top-level, so they
   * are neither lost nor silently attached to whatever top-level row happens to precede them.
   */
  function remove(stored: number) {
    setTyping(null);
    const promoted = new Set(childIndicesOf(items, stored));
    const next = items
      .map((item, idx) => (promoted.has(idx) ? { ...item, indent: 0 } : item))
      .filter((_, idx) => idx !== stored);
    onChange(renumbered(normalizeIndents(next)));
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

  /** One real checklist row. `activePosition` is null for rows in the completed section. */
  function renderRow(it: ChecklistItemDto, stored: number, activePosition: number | null) {
    return (
      <div
        key={it.id ?? `new-${stored}`}
        onDragOver={(e) => {
          if (dragStored === null) return;
          // Every real row takes the drop so a horizontal drag can indent from anywhere; the ring
          // is only shown for a target a vertical move would actually accept.
          e.preventDefault();
          const valid = canMoveVertical(dragStored, stored);
          overStoredRef.current = valid ? stored : null;
          setOverStored(valid ? stored : null);
        }}
        onDrop={(e) => {
          // The row handles vertical moves; do not let the container handle the same drop again.
          e.stopPropagation();
          onDrop(e, stored);
        }}
        style={
          dragStored === stored
            ? { transform: `translateX(${dragDx}px)`, transition: 'none' }
            : undefined
        }
        className={cn(
          'group flex items-center gap-1.5 rounded transition',
          indentOf(it) === 1 && 'ml-6',
          dragStored === stored && 'relative z-10 opacity-60',
          overStored === stored && dragStored !== null && dragStored !== stored && 'ring-1 ring-accent-ink/60',
        )}
      >
        <span
          draggable
          onDragStart={(e) => {
            setDragStored(stored);
            setOverStored(null);
            overStoredRef.current = null;
            setDragDx(0);
            dragStartX.current = e.clientX;
            e.dataTransfer.effectAllowed = 'move';
          }}
          onDragEnd={() => {
            setDragStored(null);
            setOverStored(null);
            overStoredRef.current = null;
            setDragDx(0);
            dragStartX.current = null;
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
          onClick={() => toggle(stored, !it.isChecked)}
          className={cn(
            'grid size-4 shrink-0 place-items-center rounded border transition',
            it.isChecked ? 'border-accent bg-accent text-black' : 'border-border-control hover:border-text-muted',
          )}
        >
          {it.isChecked && <CheckIcon className="text-[10px]" />}
        </button>
        <input
          ref={
            activePosition === null
              ? undefined
              : (el) => {
                  inputRefs.current[activePosition] = el;
                }
          }
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
            if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
              // A checklist is a set of lines, so the vertical arrows move between rows instead of
              // putting the caret at the start/end of this single-line field or scrolling the note.
              const inputs = Array.from(
                containerRef.current?.querySelectorAll<HTMLInputElement>('input[placeholder="List item"]') ?? [],
              );
              const at = inputs.indexOf(e.currentTarget);
              const next = inputs[at + (e.key === 'ArrowUp' ? -1 : 1)];
              e.preventDefault();
              if (!next) return;
              next.focus();
              const caret = Math.min(e.currentTarget.selectionStart ?? 0, next.value.length);
              next.setSelectionRange(caret, caret);
              return;
            }
            if (e.key === 'Tab') {
              // Tab takes the offered ticked row when there is one; otherwise it indents the row.
              // Shift+Tab is the way back out. When the move is not allowed, the browser's normal
              // focus navigation is left alone.
              if (!e.shiftKey && activePosition !== null && stored === typing && offer !== null) {
                e.preventDefault();
                take(stored, offer);
              } else if (e.shiftKey && canOutdent(items, stored)) {
                e.preventDefault();
                applyIndent(stored, 0);
              } else if (!e.shiftKey && canIndent(items, stored)) {
                e.preventDefault();
                applyIndent(stored, 1);
              }
            } else if (e.key === 'Enter') {
              e.preventDefault();
              insertAfter(stored);
            } else if (e.key === 'Backspace' && it.text === '') {
              // An empty sub-item backs out of the indent first; only a top-level empty row is
              // removed. A second Backspace then removes the now top-level row.
              if (isSubItem(it)) {
                e.preventDefault();
                applyIndent(stored, 0);
              } else {
                // Empty row + Backspace removes it and drops the caret into the previous active row.
                e.preventDefault();
                pendingFocus.current = previousActivePosition(stored);
                setTyping(null);
                remove(stored);
              }
            } else if (
              e.key === 'Backspace' &&
              isSubItem(it) &&
              e.currentTarget.selectionStart === 0 &&
              e.currentTarget.selectionEnd === 0
            ) {
              // Backspace at the very start of a non-empty sub-item outdents it, preserving the text.
              e.preventDefault();
              applyIndent(stored, 0);
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
    );
  }

  return (
    <div className="space-y-1" ref={containerRef}>
      {active.map(({ item: it, index: stored }, i) => (
        <Fragment key={it.id ?? `new-${stored}`}>
          {renderRow(it, stored, i)}
          {stored === typing && offer !== null && (
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
            </button>
          )}
        </Fragment>
      ))}
      {addButton}
      {completed.map((row) =>
        row.kind === 'heading' ? (
          // Context only: the text of an unchecked parent whose checked children are listed below.
          // No button, no input, no drop target, and it never counts as an item.
          <div
            key={`heading-${row.parentIndex}`}
            className="flex items-center gap-1.5 rounded py-0.5 text-sm text-text-faint"
          >
            {/* Spacers keep the heading aligned with the real rows' grip and remove button. */}
            <span aria-hidden="true" className="size-4 shrink-0" />
            <span
              aria-hidden="true"
              className="grid size-4 shrink-0 place-items-center rounded border border-border-control opacity-60"
            />
            <span className="flex-1 truncate">{row.text}</span>
            <span aria-hidden="true" className="size-4 shrink-0" />
          </div>
        ) : (
          renderRow(row.item, row.index, null)
        ),
      )}
    </div>
  );
}

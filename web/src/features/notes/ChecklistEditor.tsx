import { useEffect, useRef, useState, type DragEvent } from 'react';
import { cn } from '../../lib/cn';
import { CheckIcon, GripVerticalIcon, PlusIcon, XIcon } from '../../components/icons';
import type { ChecklistItemDto } from '../../api/types';

/**
 * Editable checklist: toggle, edit, add, remove, and **reorder by drag-and-drop**. Controlled via
 * `items` / `onChange`. Rows are dragged by the grip handle (so the text inputs stay usable); on
 * drop the array is reordered and each item's `order` is renumbered to its new position. The new
 * order persists with the rest of the note when the composer/editor saves on close.
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
  // Index of the row being dragged, and the row it's currently hovering over (for the drop line).
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);

  // Refs to each row's text input, plus a pending index to focus after a row is added. Using a ref
  // (not state) for the pending index keeps the focus side-effect out of React's render/state cycle.
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);
  const pendingFocus = useRef<number | null>(null);

  // After items change, focus a freshly-added row if one is pending (Enter / "Add item").
  useEffect(() => {
    if (pendingFocus.current === null) return;
    inputRefs.current[pendingFocus.current]?.focus();
    pendingFocus.current = null;
  }, [items]);

  const update = (i: number, patch: Partial<ChecklistItemDto>) =>
    onChange(items.map((it, idx) => (idx === i ? { ...it, ...patch } : it)));
  const remove = (i: number) => onChange(items.filter((_, idx) => idx !== i));
  const add = () => {
    pendingFocus.current = items.length; // current length = the new item's index
    onChange([...items, { id: null, text: '', isChecked: false, order: items.length }]);
  };

  /** Inserts a new empty item right after row `i`, renumbers `order`, and focuses it. */
  function insertAfter(i: number) {
    const next = [...items];
    next.splice(i + 1, 0, { id: null, text: '', isChecked: false, order: 0 });
    pendingFocus.current = i + 1;
    onChange(next.map((it, idx) => ({ ...it, order: idx })));
  }

  /** Moves an item and renumbers `order` to match the new positions. */
  function move(from: number, to: number) {
    if (from === to) return;
    const next = [...items];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
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
      {items.map((it, i) => (
        <div
          key={it.id ?? i}
          onDragOver={(e) => {
            if (dragIndex === null) return;
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
            className="shrink-0 cursor-grab text-text-faint opacity-0 transition hover:text-text-muted active:cursor-grabbing group-hover:opacity-100"
          >
            <GripVerticalIcon className="text-base" />
          </span>
          <button
            type="button"
            onClick={() => update(i, { isChecked: !it.isChecked })}
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
            onChange={(e) => update(i, { text: e.target.value })}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                insertAfter(i);
              } else if (e.key === 'Backspace' && it.text === '' && i > 0) {
                // Empty row + Backspace removes it and drops the caret into the previous row.
                e.preventDefault();
                pendingFocus.current = i - 1;
                remove(i);
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
            onClick={() => remove(i)}
            aria-label="Remove item"
            className="text-text-faint opacity-0 transition hover:text-text group-hover:opacity-100"
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

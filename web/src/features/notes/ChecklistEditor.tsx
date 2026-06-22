import { cn } from '../../lib/cn';
import { CheckIcon, PlusIcon, XIcon } from '../../components/icons';
import type { ChecklistItemDto } from '../../api/types';

/** Editable checklist: toggle, edit, add, and remove rows. Controlled via `items` / `onChange`. */
export function ChecklistEditor({
  items,
  onChange,
}: {
  items: ChecklistItemDto[];
  onChange: (items: ChecklistItemDto[]) => void;
}) {
  const update = (i: number, patch: Partial<ChecklistItemDto>) =>
    onChange(items.map((it, idx) => (idx === i ? { ...it, ...patch } : it)));
  const remove = (i: number) => onChange(items.filter((_, idx) => idx !== i));
  const add = () => onChange([...items, { id: null, text: '', isChecked: false, order: items.length }]);

  return (
    <div className="space-y-1">
      {items.map((it, i) => (
        <div key={it.id ?? i} className="group flex items-center gap-2">
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
            value={it.text}
            onChange={(e) => update(i, { text: e.target.value })}
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

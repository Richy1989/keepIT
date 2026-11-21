import { useMemo, useState, type KeyboardEvent } from 'react';
import { useCreateList, useLists } from './queries';
import { ListGlyph } from './ListIconPicker';
import { CheckIcon, PlusIcon } from '../../components/icons';
import { cn } from '../../lib/cn';

/** The server's `NoteLimits.ListName`; a longer name would only be refused. */
const LIST_NAME_MAX = 100;

/**
 * Files a note into lists: one field that both filters the caller's lists and names a new one,
 * over a checkbox row per list. Scales to many lists, which a row of every list as a chip didn't —
 * type a few letters and the rest drop away; type a name no list has and "Create" files the note
 * into it.
 *
 * Rendered inline, like the editor's colour and reminder panels, so the editor's scroll never clips
 * it. Controlled: `value` is the set of list ids the note is in and `onChange` gets the next set —
 * the caller decides when to save (the editor saves list membership on close).
 *
 * Enter toggles the list named exactly as typed, or creates it when there is none. Escape closes
 * the picker without closing the editor behind it.
 */
export function ListPicker({
  value,
  onChange,
  onClose,
}: {
  value: string[];
  onChange: (listIds: string[]) => void;
  onClose: () => void;
}) {
  const { data: lists = [] } = useLists();
  const createList = useCreateList();
  const [query, setQuery] = useState('');

  const name = query.trim();
  const shown = useMemo(() => {
    const needle = name.toLocaleLowerCase();
    return needle ? lists.filter((l) => l.name.toLocaleLowerCase().includes(needle)) : lists;
  }, [lists, name]);
  const exact = lists.find((l) => l.name.toLocaleLowerCase() === name.toLocaleLowerCase());

  function toggle(id: string) {
    onChange(value.includes(id) ? value.filter((x) => x !== id) : [...value, id]);
  }

  function create() {
    if (!name || createList.isPending) return;
    createList.mutate(
      { name, color: null, icon: null },
      {
        onSuccess: (list) => {
          onChange([...value, list.id]);
          setQuery('');
        },
      },
    );
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    if (exact) toggle(exact.id);
    else create();
  }

  return (
    <div
      onKeyDown={(e) => {
        if (e.key !== 'Escape') return;
        // The editor saves and closes on Escape; from inside the picker it only folds it away.
        e.stopPropagation();
        onClose();
      }}
      className="mt-3 rounded-xl border border-border-subtle bg-canvas/70 p-2"
    >
      <input
        autoFocus
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={onKeyDown}
        maxLength={LIST_NAME_MAX}
        placeholder={lists.length > 0 ? 'Find or create a list' : 'Name a new list'}
        aria-label="Find or create a list"
        className="focus-ring w-full rounded-lg border border-border-strong bg-canvas px-2.5 py-1.5 text-sm placeholder:text-text-faint"
      />

      <ul className="mt-1.5 max-h-56 overflow-y-auto" aria-label="Lists">
        {shown.map((l) => {
          const on = value.includes(l.id);
          return (
            <li key={l.id}>
              <button
                type="button"
                role="checkbox"
                aria-checked={on}
                onClick={() => toggle(l.id)}
                className="focus-ring flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-sm transition hover:bg-overlay-hover"
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'grid size-4 shrink-0 place-items-center rounded border',
                    on ? 'border-accent bg-accent text-black' : 'border-border-control',
                  )}
                >
                  {on && <CheckIcon className="text-[0.7rem]" />}
                </span>
                <ListGlyph icon={l.icon} className="shrink-0 text-base text-text-faint" />
                <span className="truncate">{l.name}</span>
              </button>
            </li>
          );
        })}
        {name && !exact && (
          <li>
            <button
              type="button"
              onClick={create}
              disabled={createList.isPending}
              className="focus-ring flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-sm text-text-muted transition hover:bg-overlay-hover hover:text-text disabled:opacity-50"
            >
              <PlusIcon className="shrink-0 text-base" />
              <span className="truncate">
                Create <span className="font-medium text-text">“{name}”</span>
              </span>
            </button>
          </li>
        )}
      </ul>
      {createList.isError && (
        <p className="px-2 pt-1 text-xs text-danger">Couldn’t create the list. Try again.</p>
      )}
    </div>
  );
}

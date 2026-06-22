import { useState, type KeyboardEvent, type ReactNode } from 'react';
import {
  useCreateList,
  useDeleteList,
  useLists,
  useUpdateList,
} from '../features/lists/queries';
import type { NotesView } from '../features/notes/queries';
import { ArchiveIcon, ListIcon, NoteIcon, PlusIcon, TrashIcon, XIcon } from './icons';
import { cn } from '../lib/cn';

/** What the grid is currently showing: a view plus an optional single-list filter. */
export interface Selection {
  view: NotesView;
  listId: string | null;
}

/** Left navigation: Notes / Archive / Trash plus the user's lists (filter, create, rename, delete). */
export function Sidebar({
  selection,
  onSelect,
}: {
  selection: Selection;
  onSelect: (s: Selection) => void;
}) {
  const { data: lists } = useLists();
  const createList = useCreateList();
  const updateList = useUpdateList();
  const deleteList = useDeleteList();

  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState('');

  function commitNew() {
    const name = draft.trim();
    if (name) createList.mutate({ name, color: null });
    setDraft('');
    setAdding(false);
  }

  function commitRename(id: string) {
    const name = editDraft.trim();
    if (name) updateList.mutate({ id, body: { name } });
    setEditingId(null);
  }

  return (
    <nav className="flex w-60 shrink-0 flex-col gap-1 overflow-y-auto border-r border-border-subtle p-3">
      <NavItem
        icon={<NoteIcon className="text-lg" />}
        label="Notes"
        active={selection.view === 'active' && selection.listId === null}
        onClick={() => onSelect({ view: 'active', listId: null })}
      />

      <div className="mt-4 mb-1 flex items-center justify-between px-3">
        <span className="text-xs font-semibold uppercase tracking-wider text-text-faint">Lists</span>
        <button
          type="button"
          onClick={() => setAdding(true)}
          title="Create list"
          className="focus-ring grid size-6 place-items-center rounded text-text-faint transition hover:bg-surface-hover hover:text-text"
        >
          <PlusIcon className="text-base" />
        </button>
      </div>

      {lists?.map((l) =>
        editingId === l.id ? (
          <input
            key={l.id}
            autoFocus
            value={editDraft}
            onChange={(e) => setEditDraft(e.target.value)}
            onBlur={() => commitRename(l.id)}
            onKeyDown={(e: KeyboardEvent) => {
              if (e.key === 'Enter') commitRename(l.id);
              if (e.key === 'Escape') setEditingId(null);
            }}
            className="focus-ring mx-1 rounded-lg border border-border-strong bg-canvas px-2 py-1.5 text-sm"
          />
        ) : (
          <div key={l.id} className="group/list relative">
            <NavItem
              icon={<ListIcon className="text-lg" />}
              label={l.name}
              count={l.noteCount}
              active={selection.view === 'active' && selection.listId === l.id}
              onClick={() => onSelect({ view: 'active', listId: l.id })}
              onDoubleClick={() => {
                setEditingId(l.id);
                setEditDraft(l.name);
              }}
            />
            <button
              type="button"
              title="Delete list"
              onClick={() => {
                if (confirm(`Delete the list “${l.name}”? Your notes are kept.`)) {
                  deleteList.mutate(l.id);
                  if (selection.listId === l.id) onSelect({ view: 'active', listId: null });
                }
              }}
              className="focus-ring absolute right-2 top-1/2 hidden -translate-y-1/2 place-items-center rounded p-1 text-text-faint transition hover:text-rose-300 group-hover/list:grid"
            >
              <XIcon className="text-sm" />
            </button>
          </div>
        ),
      )}

      {adding && (
        <input
          autoFocus
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commitNew}
          onKeyDown={(e: KeyboardEvent) => {
            if (e.key === 'Enter') commitNew();
            if (e.key === 'Escape') {
              setDraft('');
              setAdding(false);
            }
          }}
          placeholder="List name"
          className="focus-ring mx-1 rounded-lg border border-border-strong bg-canvas px-2 py-1.5 text-sm placeholder:text-text-faint"
        />
      )}

      <div className="mt-4 border-t border-border-subtle pt-3">
        <NavItem
          icon={<ArchiveIcon className="text-lg" />}
          label="Archive"
          active={selection.view === 'archived'}
          onClick={() => onSelect({ view: 'archived', listId: null })}
        />
        <NavItem
          icon={<TrashIcon className="text-lg" />}
          label="Trash"
          active={selection.view === 'trashed'}
          onClick={() => onSelect({ view: 'trashed', listId: null })}
        />
      </div>
    </nav>
  );
}

/** A single navigation row. */
function NavItem({
  icon,
  label,
  count,
  active,
  onClick,
  onDoubleClick,
}: {
  icon: ReactNode;
  label: string;
  count?: number;
  active: boolean;
  onClick: () => void;
  onDoubleClick?: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      className={cn(
        'focus-ring flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm transition',
        active
          ? 'bg-accent/15 font-medium text-accent'
          : 'text-text-muted hover:bg-surface-hover hover:text-text',
      )}
    >
      <span className={cn(active ? 'text-accent' : 'text-text-faint')}>{icon}</span>
      <span className="flex-1 truncate text-left">{label}</span>
      {count !== undefined && count > 0 && (
        <span className="text-xs text-text-faint">{count}</span>
      )}
    </button>
  );
}

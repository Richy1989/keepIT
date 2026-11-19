import { useState, type KeyboardEvent, type ReactNode } from 'react';
import type { ListDto, UpdateListDto } from '../api/types';
import { ListEditor } from '../features/lists/ListEditor';
import { ListGlyph } from '../features/lists/ListIconPicker';
import {
  useCreateList,
  useDeleteList,
  useLists,
  useUpdateList,
} from '../features/lists/queries';
import type { NotesView } from '../features/notes/queries';
import { useServerMeta } from '../features/settings/queries';
import {
  ArchiveIcon,
  ClockIcon,
  NoteIcon,
  PanelLeftCloseIcon,
  PanelLeftOpenIcon,
  PencilIcon,
  PlusIcon,
  TrashIcon,
  XIcon,
} from './icons';
import { ConfirmDialog } from './ConfirmDialog';
import { cn } from '../lib/cn';
import { useMediaQuery } from '../lib/useMediaQuery';

const COLLAPSED_STORAGE = 'keepit:sidebar-collapsed';

/** Whether this browser last left the sidebar folded into its icon rail. */
function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_STORAGE) === '1';
  } catch {
    return false;
  }
}

function storeCollapsed(collapsed: boolean) {
  try {
    localStorage.setItem(COLLAPSED_STORAGE, collapsed ? '1' : '0');
  } catch {
    // Not remembered, which costs one click next time.
  }
}

/** What the grid is currently showing: a view plus an optional single-list filter. */
export interface Selection {
  view: NotesView;
  listId: string | null;
}

/**
 * Left navigation: Notes / Archive / Trash plus the user's lists (filter, create, edit, delete). A
 * list's name and icon are edited in place (`ListEditor`).
 * On `md+` it's a static column; on small screens it's an off-canvas drawer toggled via `open`
 * (slides in over the content with a tap-to-dismiss backdrop), so phones get the full width back.
 *
 * On `md+` the column also folds into a rail of icons (the button at its foot), for more room for
 * the notes. The choice is this browser's, kept in localStorage like the editor's size: a layout
 * preference, not account data. The rail navigates only — every row keeps its name as a tooltip —
 * and creating, renaming and deleting lists wait until it is opened again. The drawer never folds.
 */
export function Sidebar({
  selection,
  onSelect,
  open,
  onClose,
}: {
  selection: Selection;
  onSelect: (s: Selection) => void;
  open: boolean;
  onClose: () => void;
}) {
  const { data: lists } = useLists();
  const meta = useServerMeta();
  const createList = useCreateList();
  const updateList = useUpdateList();
  const deleteList = useDeleteList();

  // Matches Tailwind's `md` breakpoint, where the drawer becomes a permanent column.
  const isDesktop = useMediaQuery('(min-width: 768px)');

  const [collapsed, setCollapsed] = useState(readCollapsed);
  // The rail is a desktop layout: the phone drawer always shows the whole menu.
  const rail = collapsed && isDesktop;

  const [adding, setAdding] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  // The list awaiting a delete confirmation. Held by value, not by id: the row can vanish from
  // `lists` the moment the mutation lands, and the dialog still needs the name for its prompt.
  const [pendingDelete, setPendingDelete] = useState<{ id: string; name: string } | null>(null);

  function toggleCollapsed() {
    const next = !collapsed;
    setCollapsed(next);
    storeCollapsed(next);
    // A list being named or renamed has no room in the rail; folding it away abandons the edit.
    if (next) {
      setAdding(false);
      setEditingId(null);
    }
  }

  function commitNew(name: string, icon: string | null) {
    if (name) createList.mutate({ name, color: null, icon });
    setAdding(false);
  }

  /** Sends only what changed. A blank name keeps the old one; "" is how the API removes an icon. */
  function commitEdit(list: ListDto, name: string, icon: string | null) {
    const body: UpdateListDto = {};
    if (name && name !== list.name) body.name = name;
    if (icon !== (list.icon ?? null)) body.icon = icon ?? '';
    if (body.name !== undefined || body.icon !== undefined) updateList.mutate({ id: list.id, body });
    setEditingId(null);
  }

  return (
    <>
      {/* Mobile-only backdrop; tap to dismiss. Sits below the top bar so it stays usable. */}
      {open && (
        <div
          onClick={onClose}
          aria-hidden="true"
          className="fixed inset-x-0 bottom-0 top-14 z-30 bg-scrim md:hidden"
        />
      )}
      <nav
        // Off-canvas but still in the DOM: without `inert` the closed drawer stays tabbable on
        // small screens, so Tab walks a keyboard user into an invisible menu. `inert` is ignored
        // from md+ where the sidebar is a permanent column.
        inert={!open && !isDesktop}
        className={cn(
          'fixed bottom-0 left-0 top-14 z-40 flex w-60 flex-col gap-1 overflow-y-auto overflow-x-hidden border-r border-border-subtle bg-sidebar p-3 transition-transform duration-200 ease-in-out',
          'md:static md:top-auto md:z-auto md:shrink-0 md:translate-x-0 md:transition-[width]',
          rail && 'md:w-16 md:px-2',
          open ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        {/* Mobile-only header with a close button (the drawer has no other affordance to dismiss). */}
        <div className="mb-1 flex items-center justify-between md:hidden">
          <span className="px-2 text-sm font-semibold text-text-muted">Menu</span>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close navigation"
            className="focus-ring grid size-7 place-items-center rounded text-text-faint transition hover:bg-surface-hover hover:text-text"
          >
            <XIcon className="text-base" />
          </button>
        </div>

      <NavItem
        icon={<NoteIcon className="text-lg" />}
        label="Notes"
        rail={rail}
        active={selection.view === 'active' && selection.listId === null}
        onClick={() => onSelect({ view: 'active', listId: null })}
      />
      <NavItem
        icon={<ClockIcon className="text-lg" />}
        label="Reminders"
        rail={rail}
        active={selection.view === 'reminders'}
        onClick={() => onSelect({ view: 'reminders', listId: null })}
      />

      {rail ? (
        // The heading and its "+" need the width; a rule still sets the lists apart.
        <div className="mx-2 mt-3 mb-2 border-t border-border-subtle" />
      ) : (
        <div className="mt-4 mb-1 flex items-center justify-between px-3">
          <span className="text-xs font-semibold uppercase tracking-wider text-text-faint">Lists</span>
          <button
            type="button"
            onClick={() => setAdding(true)}
            title="Create list"
            aria-label="Create list"
            className="focus-ring grid size-6 place-items-center rounded text-text-faint transition hover:bg-surface-hover hover:text-text"
          >
            <PlusIcon className="text-base" />
          </button>
        </div>
      )}

      {lists?.map((l) =>
        editingId === l.id && !rail ? (
          <ListEditor
            key={l.id}
            initialName={l.name}
            initialIcon={l.icon ?? null}
            onCommit={(name, icon) => commitEdit(l, name, icon)}
            onCancel={() => setEditingId(null)}
          />
        ) : (
          <div key={l.id} className="group/list relative">
            <NavItem
              icon={<ListGlyph icon={l.icon} className="text-lg" />}
              label={l.name}
              count={l.noteCount}
              rail={rail}
              active={selection.view === 'active' && selection.listId === l.id}
              onClick={() => onSelect({ view: 'active', listId: l.id })}
              onDoubleClick={rail ? undefined : () => setEditingId(l.id)}
              // F2 is the conventional rename key, and the only one available here — double-click
              // has no keyboard equivalent.
              onKeyDown={(e: KeyboardEvent) => {
                if (e.key === 'F2' && !rail) {
                  e.preventDefault();
                  setEditingId(l.id);
                }
              }}
            />
            {/*
              Revealed on hover *or* keyboard focus anywhere in the row. These used to be `hidden`
              (display:none) until a mouse hovered, which took them out of the tab order entirely —
              a keyboard user could filter by a list but never rename or delete one.
            */}
            {!rail && (
              <div className="absolute right-2 top-1/2 flex -translate-y-1/2 items-center opacity-0 transition focus-within:opacity-100 group-hover/list:opacity-100 touch:opacity-100">
                <button
                  type="button"
                  title="Edit list"
                  aria-label={`Edit list ${l.name}`}
                  onClick={() => setEditingId(l.id)}
                  className="focus-ring grid place-items-center rounded p-1 text-text-faint transition hover:text-text"
                >
                  <PencilIcon className="text-sm" />
                </button>
                <button
                  type="button"
                  title="Delete list"
                  aria-label={`Delete list ${l.name}`}
                  onClick={() => setPendingDelete({ id: l.id, name: l.name })}
                  className="focus-ring grid place-items-center rounded p-1 text-text-faint transition hover:text-danger"
                >
                  <XIcon className="text-sm" />
                </button>
              </div>
            )}
          </div>
        ),
      )}

      {adding && !rail && (
        <ListEditor
          initialName=""
          initialIcon={null}
          placeholder="List name"
          onCommit={commitNew}
          onCancel={() => setAdding(false)}
        />
      )}

      <div className="mt-4 border-t border-border-subtle pt-3">
        <NavItem
          icon={<ArchiveIcon className="text-lg" />}
          label="Archive"
          rail={rail}
          active={selection.view === 'archived'}
          onClick={() => onSelect({ view: 'archived', listId: null })}
        />
        <NavItem
          icon={<TrashIcon className="text-lg" />}
          label="Trash"
          rail={rail}
          active={selection.view === 'trashed'}
          onClick={() => onSelect({ view: 'trashed', listId: null })}
        />
      </div>

      {/* Pinned to the bottom (mt-auto); scrolls with the nav only when it overflows. The fold
          button is desktop-only: the drawer closes instead. */}
      <div className={cn('mt-auto flex items-center gap-2 pt-3', rail ? 'justify-center' : 'pl-3')}>
        {meta.data && !rail && (
          <span className="flex-1 truncate text-xs text-text-faint">keepIT v{meta.data.version}</span>
        )}
        <button
          type="button"
          onClick={toggleCollapsed}
          title={rail ? 'Expand sidebar' : 'Collapse sidebar'}
          aria-label={rail ? 'Expand sidebar' : 'Collapse sidebar'}
          aria-expanded={!rail}
          className="focus-ring hidden size-8 shrink-0 place-items-center rounded-lg text-text-faint transition hover:bg-surface-hover hover:text-text md:grid"
        >
          {rail ? <PanelLeftOpenIcon className="text-lg" /> : <PanelLeftCloseIcon className="text-lg" />}
        </button>
      </div>
      </nav>
      {pendingDelete && (
        <ConfirmDialog
          title={`Delete “${pendingDelete.name}”?`}
          body={<p>The list is removed. The notes filed under it are kept.</p>}
          confirmLabel="Delete list"
          tone="danger"
          busy={deleteList.isPending}
          onCancel={() => setPendingDelete(null)}
          onConfirm={() => {
            deleteList.mutate(pendingDelete.id);
            if (selection.listId === pendingDelete.id) onSelect({ view: 'active', listId: null });
            setPendingDelete(null);
          }}
        />
      )}
    </>
  );
}

/**
 * A single navigation row. In the `rail` it is the icon alone, centred, with the label as its
 * tooltip and accessible name.
 */
function NavItem({
  icon,
  label,
  count,
  rail = false,
  active,
  onClick,
  onDoubleClick,
  onKeyDown,
}: {
  icon: ReactNode;
  label: string;
  count?: number;
  rail?: boolean;
  active: boolean;
  onClick: () => void;
  onDoubleClick?: () => void;
  onKeyDown?: (e: KeyboardEvent) => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      onKeyDown={onKeyDown}
      title={rail ? label : undefined}
      aria-label={rail ? label : undefined}
      className={cn(
        'focus-ring flex w-full items-center rounded-lg py-2 text-sm transition',
        rail ? 'justify-center px-0' : 'gap-3 px-3',
        active
          ? 'bg-accent/15 font-medium text-accent-ink'
          : 'text-text-muted hover:bg-surface-hover hover:text-text',
      )}
    >
      <span className={cn('flex', active ? 'text-accent-ink' : 'text-text-faint')}>{icon}</span>
      {!rail && <span className="flex-1 truncate text-left">{label}</span>}
      {!rail && count !== undefined && count > 0 && (
        // Fade out on hover/focus so the row's action buttons (absolute, same spot) don't overlap.
        <span className="text-xs text-text-faint transition group-focus-within/list:opacity-0 group-hover/list:opacity-0 touch:opacity-0">
          {count}
        </span>
      )}
    </button>
  );
}

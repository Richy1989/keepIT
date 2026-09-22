import { useMemo, useState } from 'react';
import { useEmptyTrash, useNotes, type NotesFilter } from './queries';
import { NoteCard } from './NoteCard';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { distributeIntoColumns } from './masonry';
import { useMediaQuery } from '../../lib/useMediaQuery';
import { TrashIcon, TypewriterIcon } from '../../components/icons';
import type { NoteDto } from '../../api/types';

/** Filters notes client-side by the search query (title, body, or any checklist item). */
function matchesSearch(n: NoteDto, q: string): boolean {
  if (!q) return true;
  return (
    (n.title ?? '').toLowerCase().includes(q) ||
    (n.body ?? '').toLowerCase().includes(q) ||
    n.checklistItems.some((i) => i.text.toLowerCase().includes(q))
  );
}

const EMPTY_COPY: Record<NotesFilter['view'], { title: string; hint: string }> = {
  active: { title: 'Notes you add appear here', hint: 'Use the box above to capture your first note.' },
  reminders: { title: 'No reminders set', hint: 'Add one from a note’s clock icon and it will show up here.' },
  archived: { title: 'No archived notes', hint: 'Archived notes are kept here, out of your way.' },
  trashed: { title: 'Trash is empty', hint: 'Notes you delete land here before being purged.' },
};

/** The confirmation body for "Delete all", saying what happens to notes others shared with us. */
function EmptyTrashPrompt({ notes }: { notes: NoteDto[] }) {
  const hasShared = notes.some((n) => !n.isOwner);
  const what = notes.length === 1 ? 'the note' : `all ${notes.length} notes`;
  return (
    <>
      <p>
        Delete {what} in the trash forever? This can’t be undone.
      </p>
      {hasShared && (
        <p>
          Notes others shared with you are only removed from your notes. Their owners keep them.
        </p>
      )}
    </>
  );
}

/** The masonry grid. Splits pinned vs. others in the default active view. */
export function NotesGrid({
  filter,
  search,
  onOpen,
}: {
  filter: NotesFilter;
  search: string;
  onOpen: (note: NoteDto) => void;
}) {
  const { data, isLoading, isError } = useNotes(filter);
  const emptyTrash = useEmptyTrash();
  const [confirmEmpty, setConfirmEmpty] = useState(false);
  const columnCount = useColumnCount();

  const notes = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (data ?? []).filter((n) => matchesSearch(n, q));
  }, [data, search]);

  if (isLoading) {
    return (
      <div className="flex items-start gap-4">
        {Array.from({ length: columnCount }).map((_, col) => (
          <div key={col} className="flex min-w-0 flex-1 flex-col gap-4">
            {Array.from({ length: 2 }).map((_, row) => (
              <div
                key={row}
                className="animate-pulse rounded-card border border-border-subtle bg-surface"
                style={{ height: 90 + (((col * 2 + row) * 37) % 120) }}
              />
            ))}
          </div>
        ))}
      </div>
    );
  }

  if (isError) {
    return <p className="py-20 text-center text-sm text-danger">Couldn’t load your notes.</p>;
  }

  if (notes.length === 0) {
    const copy = EMPTY_COPY[filter.view];
    return (
      <div className="grid place-items-center py-24 text-center">
        <TypewriterIcon className="mb-4 text-5xl text-border-strong" />
        <p className="text-text-muted">{search ? 'No notes match your search.' : copy.title}</p>
        {!search && <p className="mt-1 text-sm text-text-faint">{copy.hint}</p>}
      </div>
    );
  }

  const showSections = filter.view === 'active' && !search;
  const pinned = showSections ? notes.filter((n) => n.isPinned) : [];
  const others = showSections ? notes.filter((n) => !n.isPinned) : notes;

  return (
    <div className="space-y-6">
      {pinned.length > 0 && (
        <Section label="Pinned" notes={pinned} columnCount={columnCount} onOpen={onOpen} />
      )}
      <Section
        label={pinned.length > 0 ? 'Others' : undefined}
        notes={others}
        columnCount={columnCount}
        onOpen={onOpen}
      />
      {/* Hidden while searching: "all" would be ambiguous between the matches and the whole trash. */}
      {filter.view === 'trashed' && !search && (
        <div className="flex justify-center pb-4">
          <button
            type="button"
            disabled={emptyTrash.isPending}
            onClick={() => setConfirmEmpty(true)}
            className="focus-ring flex items-center gap-2 rounded-lg border border-border-strong px-4 py-2 text-sm font-medium text-danger transition hover:bg-danger-bg disabled:opacity-60"
          >
            <TrashIcon className="text-base" />
            Delete all
          </button>
        </div>
      )}
      {confirmEmpty && (
        <ConfirmDialog
          title="Empty the trash?"
          body={<EmptyTrashPrompt notes={notes} />}
          confirmLabel="Delete forever"
          tone="danger"
          busy={emptyTrash.isPending}
          onCancel={() => setConfirmEmpty(false)}
          onConfirm={() => {
            emptyTrash.mutate(notes.map((n) => n.id));
            setConfirmEmpty(false);
          }}
        />
      )}
    </div>
  );
}

/**
 * A labelled masonry group. Columns are packed in JS rather than by CSS `columns` so the cards read
 * left-to-right in the order the API returned them — see features/notes/masonry.ts.
 */
function Section({
  label,
  notes,
  columnCount,
  onOpen,
}: {
  label?: string;
  notes: NoteDto[];
  columnCount: number;
  onOpen: (note: NoteDto) => void;
}) {
  const columns = useMemo(
    () => distributeIntoColumns(notes, columnCount),
    [notes, columnCount],
  );

  return (
    <section>
      {label && (
        <h2 className="mb-2 px-1 text-xs font-semibold uppercase tracking-wider text-text-faint">
          {label}
        </h2>
      )}
      <div className="flex items-start gap-4">
        {columns.map((column, i) => (
          // Columns are positional, so the index is the only stable key available; the cards
          // inside carry note ids, which is what React actually needs to keep them identified.
          <div key={i} className="flex min-w-0 flex-1 flex-col gap-4">
            {column.map((n) => (
              <NoteCard key={n.id} note={n} onOpen={onOpen} />
            ))}
          </div>
        ))}
      </div>
    </section>
  );
}

/** Column count for the grid, matching the `sm` / `lg` / `xl` breakpoints the layout used before. */
function useColumnCount(): number {
  const sm = useMediaQuery('(min-width: 640px)');
  const lg = useMediaQuery('(min-width: 1024px)');
  const xl = useMediaQuery('(min-width: 1280px)');
  if (xl) return 4;
  if (lg) return 3;
  if (sm) return 2;
  return 1;
}

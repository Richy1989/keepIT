import { useMemo, type ReactNode } from 'react';
import { useNotes, type NotesFilter } from './queries';
import { NoteCard } from './NoteCard';
import { TypewriterIcon } from '../../components/icons';
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

/** The masonry grid (CSS columns). Splits pinned vs. others in the default active view. */
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

  const notes = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (data ?? []).filter((n) => matchesSearch(n, q));
  }, [data, search]);

  if (isLoading) {
    return (
      <div className="columns-1 gap-4 sm:columns-2 lg:columns-3 xl:columns-4">
        {Array.from({ length: 6 }).map((_, i) => (
          <div
            key={i}
            className="mb-4 h-32 animate-pulse break-inside-avoid rounded-card border border-border-subtle bg-surface"
            style={{ height: 90 + ((i * 37) % 120) }}
          />
        ))}
      </div>
    );
  }

  if (isError) {
    return <p className="py-20 text-center text-sm text-rose-300">Couldn’t load your notes.</p>;
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
        <Section label="Pinned">
          {pinned.map((n) => (
            <NoteCard key={n.id} note={n} onOpen={onOpen} />
          ))}
        </Section>
      )}
      <Section label={pinned.length > 0 ? 'Others' : undefined}>
        {others.map((n) => (
          <NoteCard key={n.id} note={n} onOpen={onOpen} />
        ))}
      </Section>
    </div>
  );
}

/** A labelled masonry column-group. */
function Section({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <section>
      {label && (
        <h2 className="mb-2 px-1 text-xs font-semibold uppercase tracking-wider text-text-faint">
          {label}
        </h2>
      )}
      <div className="columns-1 gap-4 sm:columns-2 lg:columns-3 xl:columns-4">{children}</div>
    </section>
  );
}

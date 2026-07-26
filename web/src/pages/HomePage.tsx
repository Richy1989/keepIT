import { useState } from 'react';
import { Topbar } from '../components/Topbar';
import { Sidebar, type Selection } from '../components/Sidebar';
import { NoteComposer } from '../features/notes/NoteComposer';
import { NotesGrid } from '../features/notes/NotesGrid';
import { NoteEditorModal } from '../features/notes/NoteEditorModal';
import { useNotes, type NotesFilter } from '../features/notes/queries';

/** The signed-in app: top bar, sidebar navigation, composer, masonry grid, and the editor modal. */
export function HomePage() {
  const [selection, setSelection] = useState<Selection>({ view: 'active', listId: null });
  const [search, setSearch] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const filter: NotesFilter = {
    view: selection.view,
    listIds: selection.listId ? [selection.listId] : [],
  };

  // The editor is addressed by id and re-selected from the cache on every render — holding the
  // NoteDto in state instead would freeze it at open time, so per-user changes made from inside the
  // editor (reminders, shares, a collaborator's role) would never show up there. Same query key as
  // the grid, so this shares the fetch rather than adding one.
  const { data: notes } = useNotes(filter);
  const editing = editingId ? (notes?.find((n) => n.id === editingId) ?? null) : null;

  // Picking a destination also closes the mobile drawer (no effect on the static desktop sidebar).
  const handleSelect = (s: Selection) => {
    setSelection(s);
    setSidebarOpen(false);
  };

  return (
    <div className="flex h-full flex-col">
      <Topbar search={search} onSearchChange={setSearch} onMenuClick={() => setSidebarOpen(true)} />
      <div className="flex min-h-0 flex-1">
        <Sidebar
          selection={selection}
          onSelect={handleSelect}
          open={sidebarOpen}
          onClose={() => setSidebarOpen(false)}
        />
        <main className="flex-1 overflow-y-auto px-4 py-6 sm:px-8">
          <div className="mx-auto max-w-6xl">
            {selection.view === 'active' && <NoteComposer defaultListIds={filter.listIds} />}
            <NotesGrid filter={filter} search={search} onOpen={(n) => setEditingId(n.id)} />
          </div>
        </main>
      </div>
      {editing && (
        // Keyed so a different note gets a fresh editor — its draft state seeds from `note` once.
        <NoteEditorModal key={editing.id} note={editing} onClose={() => setEditingId(null)} />
      )}
    </div>
  );
}

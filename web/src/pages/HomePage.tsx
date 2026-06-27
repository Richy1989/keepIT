import { useState } from 'react';
import { Topbar } from '../components/Topbar';
import { Sidebar, type Selection } from '../components/Sidebar';
import { NoteComposer } from '../features/notes/NoteComposer';
import { NotesGrid } from '../features/notes/NotesGrid';
import { NoteEditorModal } from '../features/notes/NoteEditorModal';
import type { NotesFilter } from '../features/notes/queries';
import type { NoteDto } from '../api/types';

/** The signed-in app: top bar, sidebar navigation, composer, masonry grid, and the editor modal. */
export function HomePage() {
  const [selection, setSelection] = useState<Selection>({ view: 'active', listId: null });
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<NoteDto | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const filter: NotesFilter = {
    view: selection.view,
    listIds: selection.listId ? [selection.listId] : [],
  };

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
            <NotesGrid filter={filter} search={search} onOpen={setEditing} />
          </div>
        </main>
      </div>
      {editing && <NoteEditorModal note={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

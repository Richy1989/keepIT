import { useEffect, useState, type ReactNode } from 'react';
import { useSetNoteLists, useUpdateNote } from './queries';
import { noteColor } from './palette';
import { ChecklistEditor } from './ChecklistEditor';
import { ColorPicker } from '../../components/ColorPicker';
import { useLists } from '../lists/queries';
import { CheckSquareIcon, PaletteIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import type { ChecklistItemDto, NoteDto, NoteType } from '../../api/types';

/** Returns true when two id sets differ (order-independent). */
function listsChanged(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return true;
  const set = new Set(a);
  return b.some((id) => !set.has(id));
}

/**
 * Full-note editor shown over the grid. Edits title, body/checklist, color, and list membership;
 * persists on close (content via PUT, list membership via the per-user lists endpoint) only when
 * something actually changed.
 */
export function NoteEditorModal({ note, onClose }: { note: NoteDto; onClose: () => void }) {
  const update = useUpdateNote();
  const setLists = useSetNoteLists();
  const { data: allLists } = useLists();

  const [type, setType] = useState<NoteType>(note.type);
  const [title, setTitle] = useState(note.title ?? '');
  const [body, setBody] = useState(note.body ?? '');
  const [items, setItems] = useState<ChecklistItemDto[]>(note.checklistItems);
  const [color, setColor] = useState(note.color ?? 'default');
  const [listIds, setListIds] = useState<string[]>(note.listIds);
  const [showColors, setShowColors] = useState(false);

  function save() {
    const cleanItems = items
      .filter((i) => i.text.trim())
      .map((i, idx) => ({ ...i, text: i.text.trim(), order: idx }));

    const nextColor = color === 'default' ? null : color;
    const contentChanged =
      type !== note.type ||
      (title.trim() || null) !== (note.title ?? null) ||
      (type === 'Text' ? body.trim() || null : null) !== (note.body ?? null) ||
      nextColor !== (note.color ?? null) ||
      JSON.stringify(cleanItems.map((i) => [i.text, i.isChecked])) !==
        JSON.stringify(note.checklistItems.map((i) => [i.text, i.isChecked]));

    if (contentChanged) {
      update.mutate({
        id: note.id,
        body: {
          type,
          title: title.trim() || null,
          body: type === 'Text' ? body.trim() || null : null,
          color: nextColor,
          checklistItems: type === 'Checklist' ? cleanItems : null,
        },
      });
    }
    if (listsChanged(listIds, note.listIds)) {
      setLists.mutate({ id: note.id, listIds });
    }
    onClose();
  }

  // Esc closes (and saves).
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') save();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, title, body, items, color, listIds]);

  const swatch = noteColor(color);

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-start overflow-y-auto bg-black/60 p-4 pt-[8vh] backdrop-blur-sm"
      onMouseDown={save}
    >
      <div
        onMouseDown={(e) => e.stopPropagation()}
        className="mx-auto w-full max-w-2xl rounded-2xl border shadow-2xl shadow-black/60"
        style={{ backgroundColor: swatch.bg, borderColor: swatch.border }}
      >
        <div className="p-5">
          <input
            autoFocus
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Title"
            className="w-full bg-transparent text-lg font-medium outline-none placeholder:text-text-faint"
          />
          <div className="mt-3">
            {type === 'Checklist' ? (
              <ChecklistEditor items={items} onChange={setItems} />
            ) : (
              <textarea
                value={body}
                onChange={(e) => setBody(e.target.value)}
                placeholder="Take a note…"
                rows={8}
                className="w-full resize-none bg-transparent text-sm leading-relaxed outline-none placeholder:text-text-faint"
              />
            )}
          </div>

          {(allLists?.length ?? 0) > 0 && (
            <div className="mt-4 flex flex-wrap gap-2">
              {allLists!.map((l) => {
                const on = listIds.includes(l.id);
                return (
                  <button
                    key={l.id}
                    type="button"
                    onClick={() =>
                      setListIds((cur) =>
                        on ? cur.filter((id) => id !== l.id) : [...cur, l.id],
                      )
                    }
                    className={cn(
                      'focus-ring rounded-full border px-3 py-1 text-xs transition',
                      on
                        ? 'border-accent/60 bg-accent/15 text-accent'
                        : 'border-border-strong text-text-muted hover:text-text',
                    )}
                  >
                    {l.name}
                  </button>
                );
              })}
            </div>
          )}

          {showColors && (
            <div className="mt-4 rounded-lg border border-border-subtle bg-canvas/40 p-2">
              <ColorPicker value={color} onPick={setColor} />
            </div>
          )}
        </div>

        <div className="flex items-center justify-between border-t border-black/20 px-4 py-2.5">
          <div className="flex items-center gap-1">
            <EditorTool label="Background" onClick={() => setShowColors((s) => !s)}>
              <PaletteIcon className="text-lg" />
            </EditorTool>
            <EditorTool
              label={type === 'Checklist' ? 'Switch to text' : 'Switch to checklist'}
              onClick={() => {
                if (type === 'Text') {
                  setType('Checklist');
                  if (items.length === 0)
                    setItems([{ id: null, text: '', isChecked: false, order: 0 }]);
                } else {
                  setType('Text');
                }
              }}
            >
              <CheckSquareIcon className={cn('text-lg', type === 'Checklist' && 'text-accent')} />
            </EditorTool>
          </div>
          <button
            type="button"
            onClick={save}
            className="focus-ring rounded-md px-4 py-1.5 text-sm font-medium text-text-muted transition hover:bg-black/20 hover:text-text"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

/** A small icon button in the editor toolbar. */
function EditorTool({
  label,
  onClick,
  children,
}: {
  label: string;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={onClick}
      className="focus-ring grid size-8 place-items-center rounded-full text-text-muted transition hover:bg-black/20 hover:text-text"
    >
      {children}
    </button>
  );
}

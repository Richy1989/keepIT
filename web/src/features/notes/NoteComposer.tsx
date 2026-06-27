import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useCreateNote } from './queries';
import { noteColor } from './palette';
import { ChecklistEditor } from './ChecklistEditor';
import { ColorPicker } from '../../components/ColorPicker';
import { CheckSquareIcon, PaletteIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import type { ChecklistItemDto, NoteType } from '../../api/types';

/**
 * The pinned "Take a note…" composer. Collapsed it's a single bar; clicking expands it inline into
 * a card with a title, body (or checklist), and a color picker. Saving is explicit — only the Save
 * button commits the note; clicking outside discards the draft. New notes are filed into whichever
 * lists are currently being filtered.
 */
export function NoteComposer({ defaultListIds }: { defaultListIds: string[] }) {
  const create = useCreateNote();
  const ref = useRef<HTMLDivElement>(null);

  const [open, setOpen] = useState(false);
  const [showColors, setShowColors] = useState(false);
  const [type, setType] = useState<NoteType>('Text');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [items, setItems] = useState<ChecklistItemDto[]>([]);
  const [color, setColor] = useState('default');

  function reset() {
    setOpen(false);
    setShowColors(false);
    setType('Text');
    setTitle('');
    setBody('');
    setItems([]);
    setColor('default');
  }

  function save() {
    const cleanItems = items
      .filter((i) => i.text.trim())
      .map((i, idx) => ({ ...i, text: i.text.trim(), order: idx }));
    const hasContent = Boolean(title.trim() || body.trim() || cleanItems.length);
    if (hasContent) {
      create.mutate({
        type,
        title: title.trim() || null,
        body: type === 'Text' ? body.trim() || null : null,
        color: color === 'default' ? null : color,
        checklistItems: type === 'Checklist' ? cleanItems : null,
        listIds: defaultListIds,
      });
    }
    reset();
  }

  // Click-outside cancels: the draft is discarded and the composer collapses. Saving is explicit —
  // only the Save button commits the note.
  useEffect(() => {
    if (!open) return;
    function onDown(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) reset();
    }
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const swatch = noteColor(color);

  if (!open) {
    return (
      <div className="mx-auto mb-8 flex max-w-xl items-center gap-2 rounded-xl border border-border-subtle bg-surface px-4 py-1 shadow-lg shadow-black/30">
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="flex-1 py-2.5 text-left text-sm text-text-faint"
        >
          Take a note…
        </button>
        <button
          type="button"
          onClick={() => {
            setType('Checklist');
            setItems([{ id: null, text: '', isChecked: false, order: 0 }]);
            setOpen(true);
          }}
          title="New checklist"
          className="focus-ring grid size-8 place-items-center rounded-full text-text-muted transition hover:bg-surface-hover hover:text-text"
        >
          <CheckSquareIcon className="text-lg" />
        </button>
      </div>
    );
  }

  return (
    <div
      ref={ref}
      className="mx-auto mb-8 max-w-xl rounded-xl border shadow-xl shadow-black/40"
      style={{ backgroundColor: swatch.bg, borderColor: swatch.border }}
    >
      <div className="p-4">
        <input
          autoFocus
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title"
          className="w-full bg-transparent text-base font-medium outline-none placeholder:text-text-faint"
        />
        <div className="mt-2">
          {type === 'Checklist' ? (
            <ChecklistEditor items={items} onChange={setItems} />
          ) : (
            <textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              placeholder="Take a note…"
              rows={3}
              className="w-full resize-none bg-transparent text-sm outline-none placeholder:text-text-faint"
            />
          )}
        </div>

        {showColors && (
          <div className="mt-3 rounded-lg border border-border-subtle bg-canvas/40 p-2">
            <ColorPicker value={color} onPick={setColor} />
          </div>
        )}
      </div>

      <div className="flex items-center justify-between border-t border-black/20 px-3 py-2">
        <div className="flex items-center gap-1">
          <ComposerTool label="Background" onClick={() => setShowColors((s) => !s)}>
            <PaletteIcon className="text-lg" />
          </ComposerTool>
          <ComposerTool
            label={type === 'Checklist' ? 'Switch to text' : 'Switch to checklist'}
            onClick={() => {
              if (type === 'Text') {
                setType('Checklist');
                if (items.length === 0) setItems([{ id: null, text: '', isChecked: false, order: 0 }]);
              } else {
                setType('Text');
              }
            }}
          >
            <CheckSquareIcon className={cn('text-lg', type === 'Checklist' && 'text-accent')} />
          </ComposerTool>
        </div>
        <button
          type="button"
          onClick={save}
          className="focus-ring rounded-md px-4 py-1.5 text-sm font-medium text-text-muted transition hover:bg-black/20 hover:text-text"
        >
          Save
        </button>
      </div>
    </div>
  );
}

/** A small icon button in the composer toolbar. */
function ComposerTool({
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

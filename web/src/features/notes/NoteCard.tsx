import { useState, type ReactNode } from 'react';
import { useDeleteNote, useSetNoteState, useUpdateNote } from './queries';
import { noteColor } from './palette';
import { ColorPicker } from '../../components/ColorPicker';
import {
  ArchiveIcon,
  CheckIcon,
  PaletteIcon,
  PinIcon,
  RestoreIcon,
  TrashIcon,
} from '../../components/icons';
import { cn } from '../../lib/cn';
import type { ChecklistItemDto, NoteDto, UpdateNoteDto } from '../../api/types';

const MAX_PREVIEW_ITEMS = 8;

/** Builds an UpdateNoteDto from a note plus overrides (used for inline color / checklist edits). */
function toUpdate(note: NoteDto, overrides: Partial<UpdateNoteDto>): UpdateNoteDto {
  return {
    type: note.type,
    title: note.title,
    body: note.body,
    color: note.color,
    checklistItems: note.checklistItems,
    ...overrides,
  };
}

/**
 * A single note card in the masonry grid. The body opens the editor on click; pin and the hover
 * toolbar (color / archive / trash, or restore / delete in the trash view) act directly. Checklist
 * items can be ticked inline.
 */
export function NoteCard({ note, onOpen }: { note: NoteDto; onOpen: (note: NoteDto) => void }) {
  const setState = useSetNoteState();
  const update = useUpdateNote();
  const del = useDeleteNote();
  const [showColors, setShowColors] = useState(false);
  const swatch = noteColor(note.color);

  const checkedItems = note.checklistItems.filter((i) => i.isChecked).length;

  function toggleItem(target: ChecklistItemDto) {
    const items = note.checklistItems.map((i) =>
      i.id === target.id ? { ...i, isChecked: !i.isChecked } : i,
    );
    update.mutate({ id: note.id, body: toUpdate(note, { checklistItems: items }) });
  }

  return (
    <div
      onClick={() => onOpen(note)}
      className="group relative mb-4 block w-full break-inside-avoid rounded-card border p-4 text-left shadow-md shadow-black/20 transition hover:shadow-lg hover:shadow-black/40"
      style={{ backgroundColor: swatch.bg, borderColor: swatch.border }}
    >
      {/* Pin — visible on hover, or always when pinned. */}
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          setState.mutate({ id: note.id, state: { isPinned: !note.isPinned } });
        }}
        title={note.isPinned ? 'Unpin' : 'Pin'}
        className={cn(
          'focus-ring absolute right-2 top-2 grid size-8 place-items-center rounded-full text-text-muted transition hover:bg-black/20 hover:text-text',
          note.isPinned ? 'opacity-100 text-text' : 'opacity-0 group-hover:opacity-100 touch:opacity-100',
        )}
      >
        <PinIcon className="text-base" />
      </button>

      {note.title && (
        <h3 className="mb-1.5 pr-8 font-medium leading-snug text-text">{note.title}</h3>
      )}

      {note.type === 'Checklist' ? (
        <ul className="space-y-1">
          {note.checklistItems.slice(0, MAX_PREVIEW_ITEMS).map((it) => (
            <li key={it.id} className="flex items-start gap-2 text-sm">
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  toggleItem(it);
                }}
                className={cn(
                  'mt-0.5 grid size-4 shrink-0 place-items-center rounded border transition',
                  it.isChecked
                    ? 'border-accent bg-accent text-black'
                    : 'border-border-strong hover:border-text-muted',
                )}
              >
                {it.isChecked && <CheckIcon className="text-[10px]" />}
              </button>
              <span className={cn('text-text', it.isChecked && 'text-text-faint line-through')}>
                {it.text}
              </span>
            </li>
          ))}
          {note.checklistItems.length > MAX_PREVIEW_ITEMS && (
            <li className="pl-6 text-xs text-text-faint">
              + {note.checklistItems.length - MAX_PREVIEW_ITEMS} more
            </li>
          )}
          {note.checklistItems.length > 0 && (
            <li className="pt-1 text-xs text-text-faint">
              {checkedItems}/{note.checklistItems.length} done
            </li>
          )}
        </ul>
      ) : (
        note.body && (
          <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-text/90 [overflow-wrap:anywhere]">
            {note.body.length > 600 ? `${note.body.slice(0, 600)}…` : note.body}
          </p>
        )
      )}

      {!note.title && !note.body && note.checklistItems.length === 0 && (
        <p className="text-sm italic text-text-faint">Empty note</p>
      )}

      {/* Color popover. */}
      {showColors && (
        <div
          onClick={(e) => e.stopPropagation()}
          className="mt-3 rounded-lg border border-border-subtle bg-canvas/60 p-2"
        >
          <ColorPicker
            value={note.color}
            onPick={(key) => {
              update.mutate({
                id: note.id,
                body: toUpdate(note, { color: key === 'default' ? null : key }),
              });
              setShowColors(false);
            }}
          />
        </div>
      )}

      {/* Footer: hover toolbar (left) + always-visible timestamp (right). */}
      <div className="mt-3 flex items-center gap-1">
        <div className="flex items-center gap-1 opacity-0 transition group-hover:opacity-100 touch:opacity-100">
        {note.isTrashed ? (
          <>
            <CardTool
              label="Restore"
              onClick={() => setState.mutate({ id: note.id, state: { isTrashed: false } })}
            >
              <RestoreIcon className="text-base" />
            </CardTool>
            <CardTool label="Delete forever" onClick={() => del.mutate(note.id)}>
              <TrashIcon className="text-base" />
            </CardTool>
          </>
        ) : (
          <>
            <CardTool label="Background" onClick={() => setShowColors((s) => !s)}>
              <PaletteIcon className="text-base" />
            </CardTool>
            <CardTool
              label={note.isArchived ? 'Unarchive' : 'Archive'}
              onClick={() => setState.mutate({ id: note.id, state: { isArchived: !note.isArchived } })}
            >
              <ArchiveIcon className={cn('text-base', note.isArchived && 'text-accent')} />
            </CardTool>
            <CardTool
              label="Trash"
              onClick={() => setState.mutate({ id: note.id, state: { isTrashed: true } })}
            >
              <TrashIcon className="text-base" />
            </CardTool>
          </>
        )}
        </div>
        {note.createdAtUtc && (
          <time className="ml-auto text-xs text-text-faint" dateTime={note.createdAtUtc}>
            {formatDate(note.createdAtUtc)}
          </time>
        )}
      </div>
    </div>
  );
}

/**
 * Formats a backend UTC timestamp in the viewer's local time. Backend values are UTC; Postgres
 * emits a trailing 'Z' but the SQLite dev provider can return DateTimes with no zone designator,
 * which the browser would otherwise parse as local time — so we append 'Z' when it's missing.
 */
function formatDate(iso: string) {
  const utc = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`;
  return new Date(utc).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** A small icon button in the card's hover toolbar. */
function CardTool({
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
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      className="focus-ring grid size-8 place-items-center rounded-full text-text-muted transition hover:bg-black/20 hover:text-text"
    >
      {children}
    </button>
  );
}

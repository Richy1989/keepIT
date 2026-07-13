import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useSetNoteLists, useUpdateNote } from './queries';
import { useRevokeShare } from './shareQueries';
import { noteColor } from './palette';
import { ChecklistEditor } from './ChecklistEditor';
import { Markdown } from './Markdown';
import { MarkdownToolbar } from './MarkdownToolbar';
import { ReminderChip } from './ReminderChip';
import { ReminderMenu } from './ReminderMenu';
import { ShareDialog } from './ShareDialog';
import { ColorPicker } from '../../components/ColorPicker';
import { useLists } from '../lists/queries';
import { useAuth } from '../../auth/AuthContext';
import {
  CheckIcon,
  CheckSquareIcon,
  ClockIcon,
  EyeIcon,
  PaletteIcon,
  PencilIcon,
  ShareIcon,
} from '../../components/icons';
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
 *
 * On a shared note the caller's access governs what's editable: a viewer (`canEdit === false`) sees
 * the content read-only but can still file it into their own lists (list membership is per-user).
 * Only the owner can open the share dialog.
 */
export function NoteEditorModal({ note, onClose }: { note: NoteDto; onClose: () => void }) {
  const update = useUpdateNote();
  const setLists = useSetNoteLists();
  const revoke = useRevokeShare(note.id);
  const { user } = useAuth();
  const { data: allLists } = useLists();

  const canEdit = note.canEdit;

  /** Collaborator-only: remove the caller's own share, dropping the note from their grid. */
  function leaveNote() {
    if (note.isOwner || !user) return;
    if (!window.confirm('Leave this note? It will disappear from your grid until you are invited again.')) return;
    revoke.mutate(user.id, { onSettled: onClose });
  }

  const [type, setType] = useState<NoteType>(note.type);
  const [title, setTitle] = useState(note.title ?? '');
  const [body, setBody] = useState(note.body ?? '');
  const [items, setItems] = useState<ChecklistItemDto[]>(note.checklistItems);
  const [color, setColor] = useState(note.color ?? 'default');
  const [listIds, setListIds] = useState<string[]>(note.listIds);
  const [showColors, setShowColors] = useState(false);
  const [showReminder, setShowReminder] = useState(false);
  const [showShare, setShowShare] = useState(false);
  const [preview, setPreview] = useState(false);
  const bodyRef = useRef<HTMLTextAreaElement>(null);

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

    // Only owners/editors persist content; viewers can't (the server would 403 anyway).
    if (canEdit && contentChanged) {
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
    // List membership is per-user — allowed even for viewers.
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
          {!note.isOwner && (
            <div className="mb-3 flex items-center gap-1.5 text-xs text-text-faint">
              <EyeIcon className="text-sm" />
              Shared with you{note.canEdit ? ' — you can edit' : ' — view only'}
            </div>
          )}
          <input
            autoFocus
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Title"
            readOnly={!canEdit}
            className="w-full bg-transparent text-lg font-medium outline-none placeholder:text-text-faint"
          />
          <div className="mt-3">
            {type === 'Checklist' ? (
              canEdit ? (
                <ChecklistEditor items={items} onChange={setItems} />
              ) : (
                <ReadOnlyChecklist items={items} />
              )
            ) : canEdit ? (
              <>
                <div className="mb-2 flex items-center justify-between gap-2">
                  {preview ? (
                    <span className="text-xs text-text-faint">Preview</span>
                  ) : (
                    <MarkdownToolbar textareaRef={bodyRef} onChange={setBody} />
                  )}
                  <button
                    type="button"
                    title={preview ? 'Edit' : 'Preview'}
                    aria-label={preview ? 'Edit' : 'Preview'}
                    onClick={() => setPreview((p) => !p)}
                    className="focus-ring grid size-7 place-items-center rounded-md text-sm text-text-muted transition hover:bg-black/20 hover:text-text"
                  >
                    {preview ? <PencilIcon /> : <EyeIcon />}
                  </button>
                </div>
                {preview ? (
                  body.trim() ? (
                    <Markdown text={body} />
                  ) : (
                    <p className="text-sm italic text-text-faint">Nothing to preview.</p>
                  )
                ) : (
                  <textarea
                    ref={bodyRef}
                    value={body}
                    onChange={(e) => setBody(e.target.value)}
                    placeholder="Take a note…"
                    rows={8}
                    className="w-full resize-none bg-transparent text-sm leading-relaxed outline-none placeholder:text-text-faint"
                  />
                )}
              </>
            ) : (
              // Viewers get the rendered note, not raw Markdown in a disabled textarea.
              body.trim() && <Markdown text={body} />
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

          {showColors && canEdit && (
            <div className="mt-4 rounded-lg border border-border-subtle bg-canvas/40 p-2">
              <ColorPicker value={color} onPick={setColor} />
            </div>
          )}

          {/* Applies instantly (like pin) — independent of the editor's save-on-close diff. */}
          {showReminder && <ReminderMenu note={note} onClose={() => setShowReminder(false)} />}
        </div>

        <div className="flex items-center justify-between border-t border-black/20 px-4 py-2.5">
          <div className="flex items-center gap-1">
            {canEdit && (
              <>
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
              </>
            )}
            {/* Reminders are per-user, so viewers get this too — no canEdit gate. */}
            <EditorTool label="Remind me" onClick={() => setShowReminder((s) => !s)}>
              <ClockIcon className={cn('text-lg', note.remindAtUtc != null && 'text-accent')} />
            </EditorTool>
            {note.isOwner && (
              <EditorTool label="Share" onClick={() => setShowShare(true)}>
                <ShareIcon className={cn('text-lg', note.isShared && 'text-accent')} />
              </EditorTool>
            )}
            <ReminderChip note={note} onClick={() => setShowReminder(true)} />
            {!note.isOwner && (
              <button
                type="button"
                onClick={leaveNote}
                disabled={revoke.isPending}
                className="focus-ring ml-1 rounded-md px-2 py-1 text-xs text-text-muted transition hover:bg-black/20 hover:text-text disabled:opacity-50"
              >
                {revoke.isPending ? 'Leaving…' : 'Leave note'}
              </button>
            )}
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

      {showShare && note.isOwner && (
        <ShareDialog note={note} onClose={() => setShowShare(false)} />
      )}
    </div>
  );
}

/** Static, non-interactive rendering of a checklist for viewers (no edit affordances). */
function ReadOnlyChecklist({ items }: { items: ChecklistItemDto[] }) {
  return (
    <ul className="space-y-1">
      {items.map((it, i) => (
        <li key={it.id ?? i} className="flex items-start gap-2 text-sm">
          <span
            className={cn(
              'mt-0.5 grid size-4 shrink-0 place-items-center rounded border',
              it.isChecked ? 'border-accent bg-accent text-black' : 'border-border-strong',
            )}
          >
            {it.isChecked && <CheckIcon className="text-[10px]" />}
          </span>
          <span className={cn('text-text', it.isChecked && 'text-text-faint line-through')}>
            {it.text}
          </span>
        </li>
      ))}
    </ul>
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

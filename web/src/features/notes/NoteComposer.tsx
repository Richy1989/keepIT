import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useCreateNote } from './queries';
import { noteColor } from './palette';
import { ChecklistEditor } from './ChecklistEditor';
import { ColorPicker } from '../../components/ColorPicker';
import { CheckSquareIcon, ImageIcon, PaletteIcon, XIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import { isAcceptedImage } from './media/useMediaUpload';
import {
  useUploadNoteMedia,
  ACCEPTED_IMAGE_TYPES,
  MAX_IMAGES_PER_NOTE,
} from './media/queries';
import type { ChecklistItemDto, NoteType } from '../../api/types';

/** A file chosen before the note exists, with its local preview URL. */
interface QueuedFile {
  file: File;
  url: string;
}

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

  const uploadMedia = useUploadNoteMedia();
  const fileInput = useRef<HTMLInputElement>(null);
  const [queued, setQueued] = useState<QueuedFile[]>([]);
  const [attachError, setAttachError] = useState<string | null>(null);

  /** Queues files for upload once the note exists, with a local preview in the meantime. */
  function addFiles(files: File[]) {
    const room = MAX_IMAGES_PER_NOTE - queued.length;
    const accepted = files.filter(isAcceptedImage).slice(0, Math.max(room, 0));
    if (accepted.length < files.length) {
      setAttachError(
        files.some((f) => !isAcceptedImage(f))
          ? 'Only JPEG, PNG, WebP and GIF images can be attached.'
          : `A note can hold at most ${MAX_IMAGES_PER_NOTE} images.`,
      );
    }
    setQueued((q) => [...q, ...accepted.map((file) => ({ file, url: URL.createObjectURL(file) }))]);
  }

  function reset() {
    setOpen(false);
    setShowColors(false);
    setType('Text');
    setTitle('');
    setBody('');
    setItems([]);
    setColor('default');
    setQueued((q) => {
      q.forEach((f) => URL.revokeObjectURL(f.url));
      return [];
    });
  }

  function save() {
    const cleanItems = items
      .filter((i) => i.text.trim())
      .map((i, idx) => ({ ...i, text: i.text.trim(), order: idx }));
    // Images alone are enough to make a note worth saving, even with no text at all.
    const hasContent = Boolean(title.trim() || body.trim() || cleanItems.length || queued.length);
    if (!hasContent) {
      reset();
      return;
    }

    // Both representations are saved regardless of `type` — the server keeps Body and
    // ChecklistItems independently and `type` only picks which renders, so a draft typed as text
    // and then toggled to a checklist (or vice versa) isn't silently thrown away.
    const created = create.mutateAsync({
      type,
      title: title.trim() || null,
      body: body.trim() || null,
      color: color === 'default' ? null : color,
      checklistItems: cleanItems,
      listIds: defaultListIds,
    });

    // Capture the queue before reset clears it; the composer collapses immediately either way, so
    // the note never appears to hang while its images upload.
    const files = queued.map((q) => q.file);
    reset();

    if (files.length) {
      void (async () => {
        try {
          const note = await created;
          for (const file of files) {
            await uploadMedia.mutateAsync({ noteId: note.id, file });
          }
        } catch {
          // The note itself is already saved — a failed attachment must not discard the text.
          setAttachError('The note was saved, but some images could not be attached.');
        }
      })();
    }
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

  const errorBanner = attachError ? (
    <div className="mb-2 flex items-start justify-between gap-2 rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">
      <span>{attachError}</span>
      <button
        type="button"
        onClick={() => setAttachError(null)}
        aria-label="Dismiss"
        className="focus-ring shrink-0 rounded p-0.5 hover:bg-overlay-hover"
      >
        <XIcon className="text-xs" />
      </button>
    </div>
  ) : null;

  if (!open) {
    return (
      <div className="mx-auto mb-8 max-w-xl">
        {errorBanner}
        <div className="flex items-center gap-2 rounded-xl border border-border-subtle bg-surface px-4 py-1 elev-panel">
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
      </div>
    );
  }

  return (
    <div
      ref={ref}
      // Same three entry points as the editor; files wait in the queue until the note has an id.
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        const files = Array.from(e.dataTransfer.files).filter(isAcceptedImage);
        if (!files.length) return;
        e.preventDefault();
        addFiles(files);
      }}
      onPaste={(e) => {
        const files = Array.from(e.clipboardData.files).filter(isAcceptedImage);
        if (!files.length) return; // let normal text paste through
        e.preventDefault();
        addFiles(files);
      }}
      className="mx-auto mb-8 max-w-xl rounded-xl border elev-raised"
      style={{ backgroundColor: swatch.bg, borderColor: swatch.border }}
    >
      <div className="p-4">
        {errorBanner}

        {queued.length > 0 && (
          <div className="mb-3 grid grid-cols-4 gap-2">
            {queued.map((q) => (
              <div key={q.url} className="group/q relative aspect-square overflow-hidden rounded-lg">
                <img src={q.url} alt="" className="size-full object-cover" />
                <button
                  type="button"
                  onClick={() => {
                    URL.revokeObjectURL(q.url);
                    setQueued((list) => list.filter((x) => x.url !== q.url));
                  }}
                  aria-label="Remove image"
                  className="focus-ring absolute right-1 top-1 grid size-6 place-items-center rounded-full bg-black/70 text-white opacity-0 transition-opacity hover:bg-black/85 group-hover/q:opacity-100 focus-visible:opacity-100 touch:opacity-100"
                >
                  <XIcon className="text-xs" />
                </button>
              </div>
            ))}
          </div>
        )}

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
            <ColorPicker
              value={color}
              onPick={(c) => {
                setColor(c);
                setShowColors(false);
              }}
            />
          </div>
        )}
      </div>

      <div className="flex items-center justify-between border-t border-overlay-line px-3 py-2">
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
            <CheckSquareIcon className={cn('text-lg', type === 'Checklist' && 'text-accent-ink')} />
          </ComposerTool>
          <ComposerTool
            label={
              queued.length >= MAX_IMAGES_PER_NOTE
                ? `Limit is ${MAX_IMAGES_PER_NOTE} images`
                : 'Add image'
            }
            onClick={() => fileInput.current?.click()}
            disabled={queued.length >= MAX_IMAGES_PER_NOTE}
          >
            <ImageIcon className="text-lg" />
          </ComposerTool>
          <input
            ref={fileInput}
            type="file"
            accept={ACCEPTED_IMAGE_TYPES}
            multiple
            hidden
            onChange={(e) => {
              addFiles(Array.from(e.target.files ?? []));
              e.target.value = ''; // so picking the same file twice still fires
            }}
          />
        </div>
        <button
          type="button"
          onClick={save}
          className="focus-ring rounded-md px-4 py-1.5 text-sm font-medium text-text-muted transition hover:bg-overlay-hover hover:text-text"
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
  disabled,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={onClick}
      disabled={disabled}
      className="focus-ring grid size-8 place-items-center rounded-full text-text-muted transition hover:bg-overlay-hover hover:text-text disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
    >
      {children}
    </button>
  );
}

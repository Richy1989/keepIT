import { useEffect, useState, type CSSProperties, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { BrandMark } from '../components/BrandMark';
import { CheckIcon, KeyIcon } from '../components/icons';
import { Markdown } from '../features/notes/Markdown';
import { ChecklistEditor } from '../features/notes/ChecklistEditor';
import { NoteTile } from '../features/notes/BackgroundPicker';
import { checklistSections, indentOf } from '../features/notes/checklist';
import { noteColor } from '../features/notes/palette';
import { splitMedia, type MediaSize } from '../features/notes/media/queries';
import { formatDuration } from '../features/notes/media/duration';
import { tokenFromHash } from '../features/notes/publicLink';
import {
  PublicLinkError,
  usePublicMediaBlob,
  usePublicNote,
  useSavePublicNote,
} from '../features/notes/publicNoteQueries';
import { useObjectUrl } from '../lib/useObjectUrl';
import { cn } from '../lib/cn';
import type { ChecklistItemDto, NoteField, NoteMediaDto, PublicNoteDto } from '../api/types';

/** How long typing must pause before a visitor's edit is saved. */
const SAVE_DELAY_MS = 800;

/**
 * The page a public link opens (`/p#<token>`, ARCHITECTURE.md "Public links"), signed in or not.
 * The token is read from the fragment, which the browser never sends anywhere, and goes to the API
 * in a request body only. Read-only by default; when the owner allows it, the visitor edits the
 * title, the text and the checklist, saved as they type.
 */
export function PublicNotePage() {
  const [token, setToken] = useState(() => tokenFromHash(window.location.hash));
  const [password, setPassword] = useState<string | null>(null);

  // A visitor may paste a second link into the same tab: only the fragment changes.
  useEffect(() => {
    const onHash = () => {
      setToken(tokenFromHash(window.location.hash));
      setPassword(null);
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  // Keep a published note out of search engines (the API's answers say the same in a header).
  useEffect(() => {
    const meta = document.createElement('meta');
    meta.name = 'robots';
    meta.content = 'noindex, nofollow';
    document.head.appendChild(meta);
    return () => meta.remove();
  }, []);

  return (
    <div className="min-h-full bg-canvas px-4 py-8">
      <div className="mx-auto w-full max-w-2xl">
        <header className="mb-6 flex items-center gap-2">
          <BrandMark className="size-7" />
          <span className="text-lg font-semibold tracking-tight">keepIT</span>
        </header>
        {token ? (
          <OpenedLink key={token} token={token} password={password} onPassword={setPassword} />
        ) : (
          <Notice>This link is incomplete. Open the whole link you were sent.</Notice>
        )}
      </div>
    </div>
  );
}

function OpenedLink({
  token,
  password,
  onPassword,
}: {
  token: string;
  password: string | null;
  onPassword: (password: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const { data: note, error, isPending, isFetching } = usePublicNote(token, password, editing);

  if (error instanceof PublicLinkError && error.reason === 'password') {
    return <PasswordForm wrong={password !== null && !isFetching} onSubmit={onPassword} busy={isFetching} />;
  }
  if (error instanceof PublicLinkError) {
    return <Notice>This link doesn't exist any more, or it has expired. Ask whoever sent it for a new one.</Notice>;
  }
  if (error) return <Notice>The note couldn't be loaded. Check your connection and reload the page.</Notice>;
  if (isPending || !note) return <p className="text-sm text-text-muted">Opening the note…</p>;

  return <PublicNote token={token} password={password} note={note} onEditing={setEditing} />;
}

function PasswordForm({ wrong, busy, onSubmit }: { wrong: boolean; busy: boolean; onSubmit: (p: string) => void }) {
  const [value, setValue] = useState('');

  function submit(e: FormEvent) {
    e.preventDefault();
    if (value) onSubmit(value);
  }

  return (
    <form onSubmit={submit} className="mx-auto max-w-sm rounded-2xl border border-border-subtle bg-surface p-6 elev-raised">
      <h1 className="flex items-center gap-2 text-lg font-medium">
        <KeyIcon className="text-base" />
        This note is protected
      </h1>
      <p className="mt-1 text-sm text-text-muted">Enter the password you were given with the link.</p>
      <label className="mt-5 block">
        <span className="mb-1 block text-sm text-text-muted">Password</span>
        <input
          type="password"
          value={value}
          autoFocus
          autoComplete="off"
          onChange={(e) => setValue(e.target.value)}
          className="focus-ring w-full rounded-lg border border-border-subtle bg-canvas px-3 py-2 text-sm text-text"
        />
      </label>
      {wrong && (
        <p role="alert" className="mt-3 rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">
          That password is not right.
        </p>
      )}
      <button
        type="submit"
        disabled={busy || !value}
        className="focus-ring mt-4 w-full rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-60"
      >
        {busy ? 'Opening…' : 'Open note'}
      </button>
    </form>
  );
}

/** What a visitor can change. */
interface Draft {
  title: string;
  body: string;
  items: ChecklistItemDto[];
}

const draftOf = (note: PublicNoteDto): Draft => ({
  title: note.title ?? '',
  body: note.body ?? '',
  items: note.checklistItems,
});

/** A checklist's content, without the stored order the server renumbers on every save. */
const itemsKey = (items: ChecklistItemDto[]) =>
  JSON.stringify(items.map(({ id, text, isChecked, indent }) => [id ?? null, text, isChecked, indent ?? 0]));

/** The fields in which `draft` differs from `saved`. */
function changedFields(draft: Draft, saved: Draft): NoteField[] {
  const fields: NoteField[] = [];
  if (draft.title !== saved.title) fields.push('Title');
  if (draft.body !== saved.body) fields.push('Body');
  if (itemsKey(draft.items) !== itemsKey(saved.items)) fields.push('ChecklistItems');
  return fields;
}

/** A random v4 UUID. `crypto.randomUUID` exists only on https; `getRandomValues` everywhere. */
function newUuid(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const b = globalThis.crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/**
 * Gives each new row (the editor makes them with no id) an id of its own. The server keeps an id it
 * is sent when no other row has it, so the saved row comes back as the same row, instead of
 * looking like a different one and being saved again.
 */
function withItemIds(items: ChecklistItemDto[]): ChecklistItemDto[] {
  return items.some((i) => !i.id) ? items.map((i) => (i.id ? i : { ...i, id: newUuid() })) : items;
}

function PublicNote({
  token,
  password,
  note,
  onEditing,
}: {
  token: string;
  password: string | null;
  note: PublicNoteDto;
  onEditing: (editing: boolean) => void;
}) {
  const save = useSavePublicNote(token, password);
  const swatch = noteColor(note.color);
  const { images, recordings } = splitMedia(note.media);

  // The visitor's own copy while they edit. `saved` is the note as the server last confirmed it,
  // what an edit is measured against (and the checklist merge's base, so an item the owner ticked
  // meanwhile stays ticked).
  const [draft, setDraft] = useState<Draft>(() => draftOf(note));
  const [saved, setSaved] = useState<Draft>(() => draftOf(note));
  const dirty = changedFields(draft, saved).length > 0;
  const [preview, setPreview] = useState(false);

  // Hold off re-opening the link while there is something unsaved or a save is in flight.
  const busy = dirty || save.isPending;
  useEffect(() => onEditing(busy), [busy, onEditing]);

  // Someone else's change (the owner, another visitor) arrives with a re-open: take it when the
  // visitor has nothing of their own pending.
  /* eslint-disable react-hooks/set-state-in-effect -- adopting newer server data into the draft */
  useEffect(() => {
    if (busy) return;
    const fresh = draftOf(note);
    setSaved(fresh);
    setDraft(fresh);
  }, [note, busy]);
  /* eslint-enable react-hooks/set-state-in-effect */

  // Save once typing pauses.
  useEffect(() => {
    if (!note.canEdit || !dirty || save.isPending) return;
    const timer = setTimeout(() => {
      const fields = changedFields(draft, saved);
      if (fields.length === 0) return;
      const items = withItemIds(draft.items);
      const sent = items === draft.items ? draft : { ...draft, items };
      if (sent !== draft) setDraft(sent);
      save.mutate(
        {
          ticket: note.viewTicket,
          body: {
            type: note.type,
            title: sent.title || null,
            body: sent.body || null,
            checklistItems: sent.items,
            checklistBase: saved.items,
            fields,
          },
        },
        {
          onSuccess: (confirmed) => {
            const now = draftOf(confirmed);
            setSaved(now);
            // Nothing typed since: take the server's copy (merged with anyone else's change).
            setDraft((current) => (current === sent ? now : current));
          },
          // Rolled back: the visitor's copy goes back to what the server has.
          onError: () => setDraft(saved),
        },
      );
    }, SAVE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [draft, saved, dirty, note, save]);

  const status = save.isPending ? 'Saving…' : dirty ? 'Unsaved' : save.isSuccess ? 'Saved' : null;

  return (
    <article
      className="note-card relative isolate overflow-hidden p-5 sm:p-6"
      style={{ '--card-bg': swatch.bg, '--card-border': swatch.border } as CSSProperties}
    >
      <NoteTile name={note.background} color={note.color} />

      {images.length > 0 && (
        <div className={cn('-mx-5 -mt-5 mb-4 grid gap-1 sm:-mx-6 sm:-mt-6', images.length > 1 && 'grid-cols-2')}>
          {images.map((m) => (
            <PublicImage key={m.id} token={token} ticket={note.viewTicket} media={m} size="preview" />
          ))}
        </div>
      )}

      {note.canEdit ? (
        <input
          value={draft.title}
          onChange={(e) => setDraft({ ...draft, title: e.target.value })}
          placeholder="Title"
          maxLength={200}
          aria-label="Title"
          className="w-full bg-transparent text-xl font-semibold tracking-[-0.01em] text-text outline-none placeholder:text-text-faint"
        />
      ) : (
        note.title && <h1 className="text-xl font-semibold tracking-[-0.01em] text-text">{note.title}</h1>
      )}

      {recordings.length > 0 && (
        <div className="mt-3 flex flex-col gap-2">
          {recordings.map((m) => (
            <PublicAudio key={m.id} token={token} ticket={note.viewTicket} media={m} />
          ))}
        </div>
      )}

      <div className="mt-3">
        {note.type === 'Checklist' ? (
          note.canEdit ? (
            <ChecklistEditor items={draft.items} onChange={(items) => setDraft({ ...draft, items })} />
          ) : (
            <ReadOnlyChecklist items={note.checklistItems} />
          )
        ) : note.canEdit ? (
          <div>
            <div className="mb-2 flex gap-1 text-xs">
              {(['Write', 'Preview'] as const).map((tab) => (
                <button
                  key={tab}
                  type="button"
                  onClick={() => setPreview(tab === 'Preview')}
                  className={cn(
                    'focus-ring rounded-md px-2 py-1 transition',
                    preview === (tab === 'Preview')
                      ? 'bg-overlay-lift text-text'
                      : 'text-text-muted hover:bg-overlay-hover hover:text-text',
                  )}
                >
                  {tab}
                </button>
              ))}
            </div>
            {preview ? (
              <Markdown text={draft.body} />
            ) : (
              <textarea
                value={draft.body}
                onChange={(e) => setDraft({ ...draft, body: e.target.value })}
                placeholder="Note"
                aria-label="Note"
                rows={Math.max(6, draft.body.split('\n').length + 1)}
                className="w-full resize-none bg-transparent text-sm leading-relaxed text-text outline-none placeholder:text-text-faint"
              />
            )}
          </div>
        ) : (
          note.body && <Markdown text={note.body} />
        )}
      </div>

      <footer className="mt-5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-text-faint">
        <span>Updated {new Date(note.updatedAtUtc).toLocaleString()}</span>
        {note.canEdit && <span>Anyone with this link can edit</span>}
        {status && (
          <span className="flex items-center gap-1" aria-live="polite">
            {status === 'Saved' && <CheckIcon className="text-[10px]" />}
            {status}
          </span>
        )}
        {save.isError && (
          <span role="alert" className="text-danger">
            {(save.error as Error).message}
          </span>
        )}
        <Link to="/" className="ml-auto hover:text-text-muted hover:underline">
          Made with keepIT
        </Link>
      </footer>
    </article>
  );
}

/** A checklist to look at: what is left first, then what is done. */
function ReadOnlyChecklist({ items }: { items: ChecklistItemDto[] }) {
  const { active, completed } = checklistSections(items);
  // A heading (the unticked parent of ticked children) is drawn as text with no box.
  const row = (item: ChecklistItemDto, key: string, heading = false) => (
    <li key={key} className={cn('flex items-start gap-2 text-sm', indentOf(item) === 1 && 'ml-6')}>
      <span
        aria-hidden
        className={cn(
          'mt-px grid size-4.5 shrink-0 place-items-center rounded-md border-[1.5px]',
          item.isChecked && !heading ? 'border-accent bg-accent text-black' : 'border-border-control',
          heading && 'invisible',
        )}
      >
        {item.isChecked && !heading && <CheckIcon className="text-[10px]" />}
      </span>
      <span className={cn('text-text', item.isChecked && !heading && 'text-text-faint line-through', heading && 'text-text-faint')}>
        {item.text}
      </span>
      <span className="sr-only">{heading ? '' : item.isChecked ? '(done)' : '(to do)'}</span>
    </li>
  );

  return (
    <div className="space-y-3">
      <ul className="space-y-1.5">{active.map(({ item, index }) => row(item, `a-${index}`))}</ul>
      {completed.length > 0 && (
        <ul className="space-y-1.5 border-t border-border-subtle pt-3">
          {completed.map((c) =>
            c.kind === 'heading'
              ? row({ text: c.text, isChecked: false, indent: 0, order: c.parentIndex }, `h-${c.parentIndex}`, true)
              : row(c.item, `c-${c.index}`),
          )}
        </ul>
      )}
    </div>
  );
}

function PublicImage({ token, ticket, media, size }: { token: string; ticket: string; media: NoteMediaDto; size: MediaSize }) {
  const { data: blob, isError } = usePublicMediaBlob(token, ticket, media.id, size);
  const url = useObjectUrl(blob);
  const width = media.width > 0 ? media.width : 1;
  const height = media.height > 0 ? media.height : 1;

  return (
    <div className="relative w-full overflow-hidden bg-elevated" style={{ aspectRatio: `${width} / ${Math.min(height, width * 1.4)}` }}>
      {url && (
        <a href={url} target="_blank" rel="noreferrer">
          <img src={url} alt="" className="size-full object-cover" />
        </a>
      )}
      {isError && <div className="grid size-full place-items-center text-xs text-text-faint">Image unavailable</div>}
    </div>
  );
}

function PublicAudio({ token, ticket, media }: { token: string; ticket: string; media: NoteMediaDto }) {
  const { data: blob, isError } = usePublicMediaBlob(token, ticket, media.id, 'full');
  const url = useObjectUrl(blob);

  return (
    <div className="flex items-center gap-2 rounded-lg border border-border-subtle bg-surface-hover px-3 py-2">
      <div className="min-w-0 flex-1">
        {url ? (
          <audio controls preload="metadata" src={url} className="h-9 w-full">
            <track kind="captions" />
          </audio>
        ) : (
          <p className="py-2 text-sm text-text-muted">{isError ? "This recording couldn't be loaded." : 'Loading recording…'}</p>
        )}
      </div>
      {media.durationMs != null && (
        <span className="shrink-0 font-mono text-xs text-text-faint">{formatDuration(media.durationMs)}</span>
      )}
    </div>
  );
}

function Notice({ children }: { children: ReactNode }) {
  return <p className="rounded-2xl border border-border-subtle bg-surface p-6 text-sm text-text-muted elev-raised">{children}</p>;
}

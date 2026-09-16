# Note Media — Web Implementation Plan (2 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the React app attach, view and remove images on a note, rendering the C1 card layout (full-bleed hero, title on a scrim, body below).

**Architecture:** The media endpoints are authenticated, so bytes are fetched as blobs through the typed client and wrapped in object URLs — the pattern `Avatar.tsx` already uses, with its StrictMode-safe create/revoke bracket extracted into a shared hook first. Media ids are immutable, so every blob query is `staleTime: Infinity`. Uploads are optimistic and per-file, so one failure never takes a batch down.

**Tech Stack:** React 19, TanStack Query v5, openapi-fetch over a generated schema, Tailwind v4.

**Spec:** `docs/superpowers/specs/2026-09-16-note-media-design.md` — read §2, §6 and §9 before starting.

**Depends on:** `docs/superpowers/plans/2026-09-16-note-media-api.md` must be complete and the API running on `:5025`.

**Branch:** `feat/note-media` (already created).

## Global Constraints

- **Never hand-write TypeScript that mirrors a C# DTO.** Task 1 regenerates the client; the resulting type errors are the complete list of call sites. Do not type `NoteMediaDto` by hand.
- **Server data lives in TanStack Query.** No global store, no context, for anything fetched.
- **Note edits are optimistic** — instant UI, rollback on error.
- **There is no web test project and this plan does not create one.** Each task ends with `npm run build` (which runs `tsc -b`), `npm run lint`, and a named manual check in the browser.
- **Query hooks are co-located** in `features/<name>/queries.ts`; new feature code lives under `web/src/features/`.
- **Commits** are prefixed `web:`.

## File Structure

**Create:**
- `web/src/lib/useObjectUrl.ts` — the StrictMode-safe blob → object URL bracket, shared.
- `web/src/features/notes/media/queries.ts` — blob fetch, upload, delete.
- `web/src/features/notes/media/NoteImage.tsx` — one image with its box reserved from stored dimensions.
- `web/src/features/notes/media/MediaStrip.tsx` — editor thumbnails with remove.
- `web/src/features/notes/media/MediaLightbox.tsx` — full-size overlay.
- `web/src/features/notes/media/useMediaUpload.ts` — the per-file upload queue shared by editor and composer.

**Modify:**
- `web/src/api/schema.d.ts` — regenerated, not edited.
- `web/src/components/Avatar.tsx` — use the extracted hook.
- `web/src/features/notes/NoteCard.tsx` — C1 hero.
- `web/src/features/notes/NoteEditorModal.tsx` — strip, upload entry points, lightbox.
- `web/src/features/notes/NoteComposer.tsx` — hold files until the note exists, then upload.

---

### Task 1: Regenerate the API contract

**Files:**
- Modify: `web/src/api/schema.d.ts` (generated), `web/src/api/types.ts`

**Interfaces:**
- Consumes: the API from plan 1.
- Produces: `NoteMediaDto` and `NoteDto.media` in the generated types — everything below depends on these.

- [ ] **Step 1: Start the API**

Run, in a second terminal:
```bash
dotnet run --project keepIT/keepITCore
```
Expected: listening on `http://localhost:5025`.

- [ ] **Step 2: Regenerate**

```bash
cd web && npm run generate:api
```
Expected: `src/api/schema.d.ts` changes, adding `NoteMediaDto` and the three media paths.

- [ ] **Step 3: Confirm the new shapes landed**

```bash
grep -n "NoteMediaDto" web/src/api/schema.d.ts | head
grep -n "media" web/src/api/schema.d.ts | head
```
Expected: a `NoteMediaDto` component with `id`, `width`, `height`, `byteSize`, `order`, `createdAtUtc`, and `media` on `NoteDto`. If either is missing, the API isn't running the new code — go back to plan 1.

- [ ] **Step 4: Re-export the type**

`web/src/api/types.ts` re-exports the DTOs the app uses. Add `NoteMediaDto` alongside the others, following the existing style in that file exactly.

- [ ] **Step 5: Verify the build still passes**

```bash
npm run build
```
Expected: clean. (`media` is additive, so nothing should break yet.)

- [ ] **Step 6: Commit**

```bash
git add web/src/api
git commit -m "web: regenerate API client with note media"
```

---

### Task 2: Extract the object-URL hook

**Files:**
- Create: `web/src/lib/useObjectUrl.ts`
- Modify: `web/src/components/Avatar.tsx:24-50`

**Interfaces:**
- Consumes: nothing.
- Produces: `useObjectUrl(blob: Blob | null | undefined): string | null`.

**Why first:** `Avatar.tsx:24` documents a real bug — minting the URL in a `useMemo` broke under StrictMode, whose mount→cleanup→mount cycle revoked a URL nothing recreated, leaking one per double-render. We're about to create one object URL *per image* instead of one per session, so that bracket has to be shared rather than copy-pasted.

- [ ] **Step 1: Create the hook**

```ts
import { useEffect, useState } from 'react';

/**
 * Turns a Blob into an object URL, revoking it when the blob changes or the component unmounts.
 *
 * Created and revoked in the *same* effect on purpose. With the URL minted in a `useMemo`,
 * StrictMode's mount→cleanup→mount cycle revoked a URL that nothing then recreated (a `useMemo`
 * doesn't re-run), leaving a dead blob in the <img> and leaking one URL per double-render.
 *
 * The setState-in-effect lint rule is waived here deliberately: an object URL is an external
 * resource whose lifetime must bracket the effect, which is exactly the "synchronize with an
 * external system" case.
 */
/* eslint-disable react-hooks/set-state-in-effect */
export function useObjectUrl(blob: Blob | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!blob) {
      setUrl(null);
      return;
    }
    const next = URL.createObjectURL(blob);
    setUrl(next);
    return () => {
      URL.revokeObjectURL(next);
      setUrl(null);
    };
  }, [blob]);

  return url;
}
/* eslint-enable react-hooks/set-state-in-effect */
```

- [ ] **Step 2: Use it in `Avatar.tsx`**

Delete the local `fetchedUrl` state, its effect and the eslint-disable comments, and replace with:

```tsx
  const fetchedUrl = useObjectUrl(blob);
```

Keep `const url = previewUrl ?? fetchedUrl;` exactly as it is. Add the import:

```tsx
import { useObjectUrl } from '../lib/useObjectUrl';
```

- [ ] **Step 3: Build and lint**

```bash
npm run build && npm run lint
```
Expected: both clean, and `useState`/`useEffect` imports in `Avatar.tsx` are dropped if now unused.

- [ ] **Step 4: Verify in the browser**

Run `npm run dev`, sign in, and confirm the avatar still renders in the topbar, that changing it in settings updates it, and that the console shows no object-URL errors.

- [ ] **Step 5: Commit**

```bash
git add web/src/lib/useObjectUrl.ts web/src/components/Avatar.tsx
git commit -m "web: extract useObjectUrl hook from Avatar"
```

---

### Task 3: Media queries

**Files:**
- Create: `web/src/features/notes/media/queries.ts`

**Interfaces:**
- Consumes: `api` from `web/src/api/client`, `NOTES_KEY` from `../queries`.
- Produces: `useNoteMediaBlob(noteId, mediaId, size)`, `useUploadNoteMedia()` (mutation over `{ noteId, file }` → `NoteMediaDto`), `useDeleteNoteMedia()` (mutation over `{ noteId, mediaId }`), `MEDIA_KEY`.

- [ ] **Step 1: Write the module**

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../api/client';
import { NOTES_KEY } from '../queries';
import type { NoteMediaDto } from '../../../api/types';

export const MEDIA_KEY = 'note-media';

/** Which rendition to fetch: the grid thumbnail or the stored original. */
export type MediaSize = 'thumb' | 'full';

/**
 * Fetches one image as a Blob. The endpoint is authenticated, so it can't be used directly as an
 * <img src>; the caller turns the Blob into an object URL with `useObjectUrl`.
 *
 * `staleTime: Infinity` is safe in a way it isn't for avatars: a media id's bytes never change, so
 * there is no version of this image the cache could be wrong about.
 */
export function useNoteMediaBlob(noteId: string, mediaId: string, size: MediaSize) {
  return useQuery({
    queryKey: [MEDIA_KEY, noteId, mediaId, size],
    staleTime: Infinity,
    gcTime: 30 * 60 * 1000,
    queryFn: async () => {
      const { data, response } = await api.GET('/api/notes/{noteId}/media/{mediaId}', {
        params: { path: { noteId, mediaId }, query: { size } },
        parseAs: 'blob',
      });
      // Throw on any non-OK: caching `null` under staleTime: Infinity would pin this image to a
      // broken state for the rest of the session.
      if (!response.ok) throw new Error('Failed to load the image.');
      return (data as Blob) ?? null;
    },
  });
}

/** Attaches one image to a note, then refreshes the notes cache so the new media id appears. */
export function useUploadNoteMedia() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ noteId, file }: { noteId: string; file: File }) => {
      const { data, error } = await api.POST('/api/notes/{noteId}/media', {
        params: { path: { noteId } },
        body: { file: file as unknown as string },
        bodySerializer: () => {
          const form = new FormData();
          form.append('file', file);
          return form;
        },
      });
      if (error) throw error;
      return data as NoteMediaDto;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
    },
  });
}

/** Removes one image from a note. */
export function useDeleteNoteMedia() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ noteId, mediaId }: { noteId: string; mediaId: string }) => {
      const { error } = await api.DELETE('/api/notes/{noteId}/media/{mediaId}', {
        params: { path: { noteId, mediaId } },
      });
      if (error) throw error;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [NOTES_KEY] });
    },
  });
}
```

- [ ] **Step 2: Build and lint**

```bash
npm run build && npm run lint
```
Expected: clean. If `bodySerializer` types complain, compare against the working `useUploadProfileImage` in `web/src/features/account/queries.ts` and match it — that call solves the same multipart typing problem.

- [ ] **Step 3: Commit**

```bash
git add web/src/features/notes/media/queries.ts
git commit -m "web: add note media queries"
```

---

### Task 4: The image component

**Files:**
- Create: `web/src/features/notes/media/NoteImage.tsx`

**Interfaces:**
- Consumes: `useNoteMediaBlob`, `useObjectUrl`.
- Produces: `<NoteImage noteId media size className />` where `media: NoteMediaDto`.

- [ ] **Step 1: Write the component**

```tsx
import { useNoteMediaBlob, type MediaSize } from './queries';
import { useObjectUrl } from '../../../lib/useObjectUrl';
import { cn } from '../../../lib/cn';
import type { NoteMediaDto } from '../../../api/types';

/**
 * One note image. The box is sized from the stored dimensions *before* the blob arrives — without
 * that, every image landing in the CSS-columns grid reflows the column beneath it.
 */
export function NoteImage({
  noteId,
  media,
  size,
  className,
  maxAspect = 0.75,
}: {
  noteId: string;
  media: NoteMediaDto;
  size: MediaSize;
  className?: string;
  /** Shortest allowed height as a fraction of width — caps how much a tall photo can claim. */
  maxAspect?: number;
}) {
  const { data: blob, isError } = useNoteMediaBlob(noteId, media.id, size);
  const url = useObjectUrl(blob);

  // Reserve the real aspect ratio, but never let one tall photo eat a whole column.
  const ratio = media.width > 0 ? media.height / media.width : 1;
  const aspect = `${media.width} / ${Math.max(media.height, media.width * maxAspect)}`;

  return (
    <div
      className={cn('relative w-full overflow-hidden bg-elevated', className)}
      style={{ aspectRatio: ratio > 1 / maxAspect ? aspect : `${media.width} / ${media.height}` }}
    >
      {url && <img src={url} alt="" className="size-full object-cover" loading="lazy" />}
      {isError && (
        <div className="grid size-full place-items-center text-xs text-text-faint">
          Image unavailable
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Build and lint**

```bash
npm run build && npm run lint
```
Expected: clean.

- [ ] **Step 3: Commit**

```bash
git add web/src/features/notes/media/NoteImage.tsx
git commit -m "web: add NoteImage with reserved aspect box"
```

---

### Task 5: The C1 card layout

**Files:**
- Modify: `web/src/features/notes/NoteCard.tsx`

**Interfaces:**
- Consumes: `NoteImage`.
- Produces: no new exports.

The chosen layout (spec §2.7, §6): full-bleed hero at the card top, the **title only** on a bottom scrim, a `+N` badge top-right when there's more than one image, and body/checklist rows below on the note's own colour.

- [ ] **Step 1: Render the hero**

At the top of the card's returned markup, before the existing title/body block:

```tsx
      {note.media.length > 0 && (
        <div className="relative">
          <NoteImage noteId={note.id} media={note.media[0]} size="thumb" />
          {note.media.length > 1 && (
            <span className="absolute right-2 top-2 rounded-full bg-black/70 px-2 py-0.5 text-[11px] font-semibold text-white backdrop-blur-sm">
              +{note.media.length - 1}
            </span>
          )}
          {note.title && (
            <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/90 via-black/70 to-transparent px-3 pb-2 pt-6">
              <h3 className="line-clamp-2 text-sm font-semibold text-white [text-shadow:0_1px_3px_rgb(0_0_0/0.65)]">
                {note.title}
              </h3>
            </div>
          )}
        </div>
      )}
```

Add the import:

```tsx
import { NoteImage } from './media/NoteImage';
```

- [ ] **Step 2: Suppress the duplicate title**

The existing title element must not render again below the image. Wrap the current title render so it only appears when there is no hero:

```tsx
      {note.media.length === 0 && /* existing title JSX unchanged */}
```

Body text, checklist rows, the reminder chip and the hover toolbar all stay exactly where they are, below the hero.

- [ ] **Step 3: Make the card clip the image**

The card's outer element needs `overflow-hidden` so the full-bleed image follows the card's rounded corners. Add it to the existing `cn(...)` class list if it isn't already there.

- [ ] **Step 4: Build and lint**

```bash
npm run build && npm run lint
```
Expected: clean.

- [ ] **Step 5: Verify in the browser**

With an image attached to a note via the API (curl, from plan 1's Task 5):
- The card shows the photo full-bleed at the top with the title on the scrim.
- A note with no images looks exactly as before.
- A note with 3 images shows `+2`.
- A checklist note with an image shows the checkboxes *below* the photo, not on it.
- Switch to the light theme and confirm the scrim still reads (white-on-scrim works in both themes because the backdrop is the photo, not the theme).
- Resize to phone width: no horizontal overflow, the hero scales with the column.

- [ ] **Step 6: Commit**

```bash
git add web/src/features/notes/NoteCard.tsx
git commit -m "web: render note images as a full-bleed card hero"
```

---

### Task 6: Attaching images in the editor

**Files:**
- Create: `web/src/features/notes/media/useMediaUpload.ts`, `web/src/features/notes/media/MediaStrip.tsx`
- Modify: `web/src/features/notes/NoteEditorModal.tsx`

**Interfaces:**
- Consumes: `useUploadNoteMedia`, `useDeleteNoteMedia`, `NoteImage`.
- Produces: `useMediaUpload(noteId)` → `{ pending, errors, addFiles, dismissError }` where `pending: { id: string; url: string }[]`; `<MediaStrip noteId media pending onRemove canEdit />`.

- [ ] **Step 1: Write the upload queue**

```ts
import { useCallback, useEffect, useRef, useState } from 'react';
import { useUploadNoteMedia } from './queries';

/** One locally-previewed file that hasn't finished uploading. */
interface PendingUpload {
  id: string;
  url: string;
}

/**
 * Uploads files one at a time for a note, exposing local previews while they're in flight.
 *
 * Each file uploads independently so one rejected image (too large, wrong type) doesn't take the
 * rest of a multi-select down with it.
 */
export function useMediaUpload(noteId: string | null) {
  const upload = useUploadNoteMedia();
  const [pending, setPending] = useState<PendingUpload[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const urls = useRef<string[]>([]);

  // Previews are object URLs; revoke whatever is still outstanding when the editor closes.
  useEffect(() => () => urls.current.forEach((u) => URL.revokeObjectURL(u)), []);

  const addFiles = useCallback(
    async (files: File[]) => {
      if (!noteId || files.length === 0) return;

      for (const file of files) {
        const url = URL.createObjectURL(file);
        const id = crypto.randomUUID();
        urls.current.push(url);
        setPending((p) => [...p, { id, url }]);

        try {
          await upload.mutateAsync({ noteId, file });
        } catch (e) {
          const message =
            (e as { message?: string })?.message ?? `Couldn't upload ${file.name}.`;
          setErrors((prev) => [...prev, message]);
        } finally {
          setPending((p) => p.filter((x) => x.id !== id));
          URL.revokeObjectURL(url);
          urls.current = urls.current.filter((u) => u !== url);
        }
      }
    },
    [noteId, upload],
  );

  const dismissError = useCallback(
    (index: number) => setErrors((prev) => prev.filter((_, i) => i !== index)),
    [],
  );

  return { pending, errors, addFiles, dismissError };
}
```

- [ ] **Step 2: Write the strip**

```tsx
import { NoteImage } from './NoteImage';
import { CloseIcon } from '../../../components/icons';
import type { NoteMediaDto } from '../../../api/types';

/**
 * The editor's image row: stored images plus any still uploading (rendered from their local
 * preview, so the image appears the instant it's chosen).
 */
export function MediaStrip({
  noteId,
  media,
  pending,
  canEdit,
  onRemove,
  onOpen,
}: {
  noteId: string;
  media: NoteMediaDto[];
  pending: { id: string; url: string }[];
  canEdit: boolean;
  onRemove: (mediaId: string) => void;
  onOpen: (index: number) => void;
}) {
  if (media.length === 0 && pending.length === 0) return null;

  return (
    <div className="grid grid-cols-3 gap-2 p-3 sm:grid-cols-4">
      {media.map((m, i) => (
        <div key={m.id} className="group relative">
          <button type="button" onClick={() => onOpen(i)} className="block w-full">
            <NoteImage noteId={noteId} media={m} size="thumb" className="rounded-lg" />
          </button>
          {canEdit && (
            <button
              type="button"
              onClick={() => onRemove(m.id)}
              aria-label="Remove image"
              className="absolute right-1 top-1 grid size-6 place-items-center rounded-full bg-black/70 text-white opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100"
            >
              <CloseIcon className="size-3.5" />
            </button>
          )}
        </div>
      ))}
      {pending.map((p) => (
        <div key={p.id} className="relative overflow-hidden rounded-lg">
          <img src={p.url} alt="" className="aspect-square w-full object-cover opacity-50" />
          <div className="absolute inset-0 grid place-items-center">
            <span className="size-5 animate-spin rounded-full border-2 border-white/30 border-t-white" />
          </div>
        </div>
      ))}
    </div>
  );
}
```

If `CloseIcon` doesn't exist in `web/src/components/icons.tsx`, use whichever existing icon means "dismiss" there — do not invent a new icon component in this task.

- [ ] **Step 3: Wire the editor**

In `NoteEditorModal.tsx`:

```tsx
  const { pending, errors, addFiles, dismissError } = useMediaUpload(note.id);
  const deleteMedia = useDeleteNoteMedia();
```

Render `<MediaStrip …/>` directly above the title field. Add three entry points:

```tsx
  // 1. Picker
  <input
    type="file"
    accept="image/jpeg,image/png,image/webp,image/gif"
    multiple
    hidden
    ref={fileInput}
    onChange={(e) => {
      void addFiles(Array.from(e.target.files ?? []));
      e.target.value = '';
    }}
  />
```

```tsx
  // 2. Drop onto the modal
  onDragOver={(e) => e.preventDefault()}
  onDrop={(e) => {
    e.preventDefault();
    void addFiles(Array.from(e.dataTransfer.files).filter((f) => f.type.startsWith('image/')));
  }}
```

```tsx
  // 3. Paste a screenshot — the one people reach for daily
  onPaste={(e) => {
    const files = Array.from(e.clipboardData.files).filter((f) => f.type.startsWith('image/'));
    if (files.length) {
      e.preventDefault();
      void addFiles(files);
    }
  }}
```

Add an image button to the editor's toolbar that calls `fileInput.current?.click()`, shown only when `note.canEdit`. Render `errors` as dismissible inline messages using the editor's existing error styling.

- [ ] **Step 4: Build and lint**

```bash
npm run build && npm run lint
```
Expected: clean.

- [ ] **Step 5: Verify**

In the browser, on a note you own:
- The picker attaches one image; it appears greyed with a spinner, then resolves to the stored thumbnail.
- Selecting four files at once produces four uploads, each resolving independently.
- Dragging an image file onto the open modal attaches it.
- Copying a screenshot and pressing `Ctrl+V` inside the editor attaches it.
- Removing an image drops it from the strip and from the card behind the modal.
- Attaching an 11th image shows the server's "This note already has 10 images." message and the other images are untouched.
- Open the note as a **Viewer** on a second account: images render, no remove buttons, no image button in the toolbar.

- [ ] **Step 6: Commit**

```bash
git add web/src/features/notes/media web/src/features/notes/NoteEditorModal.tsx
git commit -m "web: attach and remove note images in the editor"
```

---

### Task 7: Lightbox and the composer

**Files:**
- Create: `web/src/features/notes/media/MediaLightbox.tsx`
- Modify: `web/src/features/notes/NoteEditorModal.tsx`, `web/src/features/notes/NoteComposer.tsx`

**Interfaces:**
- Consumes: `NoteImage`, `useMediaUpload`, `useCreateNote`.
- Produces: `<MediaLightbox noteId media index onClose onIndexChange />`.

- [ ] **Step 1: Write the lightbox**

```tsx
import { useEffect } from 'react';
import { NoteImage } from './NoteImage';
import type { NoteMediaDto } from '../../../api/types';

/** Full-size overlay for a note's images, with keyboard paging. */
export function MediaLightbox({
  noteId,
  media,
  index,
  onClose,
  onIndexChange,
}: {
  noteId: string;
  media: NoteMediaDto[];
  index: number;
  onClose: () => void;
  onIndexChange: (next: number) => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      if (e.key === 'ArrowRight') onIndexChange((index + 1) % media.length);
      if (e.key === 'ArrowLeft') onIndexChange((index - 1 + media.length) % media.length);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [index, media.length, onClose, onIndexChange]);

  const current = media[index];
  if (!current) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 grid place-items-center bg-black/90 p-4"
      onClick={onClose}
    >
      <div className="max-h-full max-w-4xl" onClick={(e) => e.stopPropagation()}>
        <NoteImage noteId={noteId} media={current} size="full" className="rounded-lg" maxAspect={0} />
      </div>
      {media.length > 1 && (
        <p className="absolute bottom-6 text-sm text-white/70">
          {index + 1} / {media.length}
        </p>
      )}
    </div>
  );
}
```

Note `maxAspect={0}`: the card caps how tall an image may be, the lightbox must not.

- [ ] **Step 2: Open it from the strip**

In `NoteEditorModal.tsx`, hold `const [lightbox, setLightbox] = useState<number | null>(null);`, pass `onOpen={setLightbox}` to `MediaStrip`, and render the lightbox when `lightbox !== null`.

- [ ] **Step 3: Handle the composer**

`NoteComposer` creates a note that doesn't exist yet, so its files must wait for an id. Hold them in state:

```tsx
  const [queuedFiles, setQueuedFiles] = useState<File[]>([]);
```

Show them as local previews (same markup as `MediaStrip`'s pending tiles). On save, after `createNote` resolves with the new note, upload them in order before clearing the composer:

```tsx
      const created = await create.mutateAsync(dto);
      for (const file of queuedFiles) {
        try {
          await uploadMedia.mutateAsync({ noteId: created.id, file });
        } catch {
          // The note is already saved; a failed attachment shouldn't discard the user's text.
          setError('Some images could not be attached.');
        }
      }
      setQueuedFiles([]);
```

Give the composer the same three entry points (picker button, drop, paste).

- [ ] **Step 4: Build and lint**

```bash
npm run build && npm run lint
```
Expected: clean.

- [ ] **Step 5: Verify**

- Clicking a thumbnail in the editor opens the full image; `Esc` closes it; arrows page through.
- In the composer: pick two images, type a title, save → the new card appears with the hero and both images are attached.
- Composer with images but no text still creates a note.
- With the API stopped, attaching in the composer shows the error but the note text is not lost.

- [ ] **Step 6: Commit**

```bash
git add web/src/features/notes
git commit -m "web: add media lightbox and composer attachments"
```

---

## Done when

- `npm run build` and `npm run lint` are clean.
- Every manual check in Tasks 5–7 passes.
- A Viewer sees images but cannot add or remove them.
- Attaching an image on one browser makes it appear in a second browser signed in as a collaborator, without a reload (realtime invalidation).

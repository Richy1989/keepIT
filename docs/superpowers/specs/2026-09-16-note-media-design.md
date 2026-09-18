# Note media (image attachments) — design

**Date:** 2026-09-16
**Branch:** `feat/note-media`
**Status:** approved design, ready for an implementation plan

Closes the item `ARCHITECTURE.md` calls "the biggest missing feature": a note cannot hold a picture.
The media rules that file already committed to (`ARCHITECTURE.md` — *Profile images & media*) are
binding here and are restated below where they shape a decision.

---

## 1. Scope

**In scope.** Images attach to **any** note — text or checklist — as an ordered, append-only
collection. There is no new `NoteType`: an "image note" is simply a note whose content happens to be
images, which is how Keep behaves and how `ARCHITECTURE.md`'s type-3 definition ("primary content is
one or more images") falls out for free.

**Explicitly out of scope for v1**, each a deliberate decision rather than an omission:

| Deferred | Why |
|---|---|
| `NoteType.Image` as a distinct type | Nothing needs it once attachments exist on every note; it would add a discriminator every client must branch on. |
| Background images | A second media *role* with its own contrast problems in three themes. Independent feature. |
| Reordering attachments | Keep shows images in attach order. `Order` is stored and append-only, so a reorder endpoint drops in later with no migration. |
| Per-user storage quota | Registration is gated, so users are semi-trusted. `ByteSize` is stored, so a quota is later a `SUM` check — no migration. |
| Images in the Glance widget | Authenticated image loading inside Glance is its own project. The widget stays text-only. |
| Automated backend/web tests | No test projects exist in this repo; starting one is a separate decision (see §11). |

---

## 2. Decisions

1. **Attachments on any note**, no new note type.
2. **Offline attach is fully supported on Android** — stage the file, queue the op, upload on
   reconnect. The app's headline promise is offline-first, and photographing something with no
   signal is the case that promise exists for.
3. **Thumbnails are generated server-side** (ImageSharp). Clients stay dumb and consistent, grid
   cards stay cheap, and EXIF orientation and stripping happen in exactly one place.
4. **Limits: ~10 MB per image, 10 images per note, no per-user quota.**
5. **Media is a sub-resource of the note**, not a global `/api/media` resource. Authorization is one
   `NoteAccessService` call on the parent note, matching the rule `ARCHITECTURE.md` already set:
   a collaborator reaches a shared note's media *through the note*.
6. **Bytes are delivered as authenticated blobs**, not signed URLs — the pattern
   `web/src/components/Avatar.tsx` and `web/src/features/account/queries.ts` already use. No bearer
   credential in a URL, no token-expiry story, and media ids are immutable so clients cache forever.
7. **Card layout: full-bleed hero with the title on a scrim, body and checklist below** (option C1,
   chosen from mockups).

---

## 3. Data model

New entity `keepIT/keepITCore/Data/NoteMedia.cs`, configured in `AppDbContext` the way
`ChecklistItem` is (key `Id`, index on `NoteId`, cascade delete from `Note`):

| Field | Type | Purpose |
|---|---|---|
| `Id` | `Guid` | Public id **and** storage key. A client filename never touches disk. |
| `NoteId` (+ nav) | `Guid` | Media is owned *through* the note — no second `OwnerId` to keep in sync. |
| `FileName` | `string(128)`, required | `{Id}.{ext}` — unlike the avatar path, no second GUID is generated. Stored rather than derived so the extension (and therefore the response content type) is one lookup, not a re-derivation. |
| `ThumbFileName` | `string(128)`, required | `{Id}_thumb.{ext}`. Stored separately because the thumb's encoding won't always match the source (an animated GIF thumbs to a still). |
| `Width`, `Height` | `int` | Lets a card reserve the correct box *before* the thumbnail arrives — without this the masonry grid reflows as each image lands. |
| `ByteSize` | `long` | Makes a future quota a `SUM` rather than a migration. |
| `Order` | `int` | Same ordered-collection contract as `ChecklistItem.Order`. Append-only in v1: a new row takes `max(Order) + 1` within the note, or `0` for the first. |
| `CreatedAtUtc` | `DateTime` | Server-set, ordering key for concurrent attaches. |

`Note` gains `ICollection<NoteMedia> Media`.

### PostgreSQL correctness

Migrations are Postgres-authoritative (`AppDbContextFactory` always targets Npgsql), while the dev
SQLite DB is `EnsureCreated` — so a schema mistake passes locally and only fails in production.
Three specifics:

- `DateTime` renders as `timestamp with time zone`. **Npgsql throws on a non-UTC `DateTimeKind`**
  where SQLite silently accepts it. `CreatedAtUtc` is set server-side from `DateTime.UtcNow` and is
  never client-supplied, so it cannot reach that path.
- String columns get explicit `HasMaxLength` (the codebase is consistent: `Title` 1000, `Color` 32);
  without it they become unbounded `text`.
- `Width`/`Height` → `integer`, `ByteSize` → `bigint`. Both fine on either provider.

**Verification step (not optional):** generate the migration, run `dotnet ef migrations script`, and
apply it against a real Postgres from `docker compose` before merge. Locally, `App_Data/keepit.db`
must be deleted for the dev schema to pick the table up.

---

## 4. Storage and lifecycle

`IMediaStorage` (save / open / delete / delete-note-prefix) with a `DiskMediaStorage` implementation,
so no controller touches `System.IO` and the S3/MinIO swap `ARCHITECTURE.md` promises stays possible
without touching callers.

Layout follows the avatar precedent, via a new `FolderManagement.GetNoteMediaFolder`:

```
{DataRoot}/users/{ownerId}/notes/{noteId}/{mediaId}.{ext}
{DataRoot}/users/{ownerId}/notes/{noteId}/{mediaId}_thumb.{ext}
```

**Write order: bytes first, row second.** If the row insert fails, delete the file. A row pointing at
missing bytes breaks rendering on every client; a file with no row is invisible and gets swept.

**Delete order: read, then save, then unlink.** Cascade delete removes the rows — and with them the
filenames — so the media list is read into memory *before* `SaveChangesAsync`, and the bytes are
deleted after it succeeds.

**Lifecycle:**
- Hard-deleting a note (owner-only, unchanged) drops rows by cascade and the directory via
  `IMediaStorage`.
- Trash is per-user and reversible, so it never touches files.
- `MediaOrphanSweepService` — a daily `AddHostedService`, same shape as `ReminderDispatcherService` —
  removes note directories with no matching row.

---

## 5. API surface

All four endpoints resolve access through `NoteAccessService`:

| Endpoint | Access | Notes |
|---|---|---|
| `POST /api/notes/{id}/media` | `CanEdit` | Multipart, **one file per request** — multi-select loops client-side so each image gets its own progress and its own failure. → 201 `NoteMediaDto` |
| `DELETE /api/notes/{id}/media/{mediaId}` | `CanEdit` | |
| `GET /api/notes/{id}/media/{mediaId}?size=thumb\|full` | any read access | `size` defaults to `full` when omitted. `FileStreamResult` + ETag + `Cache-Control: private, max-age=31536000, immutable` — honest, because ids are immutable. Content type comes from the stored filename's extension. |

`NoteDto` gains `Media: List<NoteMediaDto>` (`Id`, `Width`, `Height`, `ByteSize`, `Order`,
`CreatedAtUtc`). No URLs in the DTO; clients build the path.

**Realtime:** every mutation fans out to `NoteAccessService.RecipientIdsAsync` with
`RealtimeResources.Notes`. Media is shared note content, not per-user state.

**Rate limiting:** uploads stay on the existing global window. Ten images is ten requests against a
120/min budget; no new policy.

### Processing rules (ImageSharp)

- Originals are **re-encoded**: long edge capped at 2560, quality ~88, **EXIF orientation applied and
  then all metadata stripped**. Phone photos carry GPS, and a shared note would otherwise hand a
  collaborator the coordinates of the photographer's home. Accepted trade-off: pixel-exact originals
  are not preserved.
- Thumbnail: long edge 400.
- Animated GIFs pass through unmodified; their thumbnail is the first frame.
- The **10 MB limit applies to the uploaded file**, checked before processing. `ByteSize` records the
  *stored* size after re-encoding, which is typically smaller.
- Validation reuses `ImageService.LooksLikeImageAsync` (magic bytes) — it becomes `internal static`
  and the new `NoteMediaService` calls it. The avatar path is not otherwise refactored.
- **HEIC needs its own detection branch.** `LooksLikeImageAsync` knows JPEG/PNG/GIF/WebP only, so a
  HEIC file currently falls through to the generic "not a valid image". To deliver the specific
  message promised below, add an ISO-BMFF brand check (`ftypheic` / `ftypheix` / `ftypmif1` at offset
  4) that is recognised purely in order to be rejected by name.
- **Confirm the ImageSharp licence fits before committing** (Six Labors Split License: free for
  open-source/personal use, paid above a revenue threshold). It is fully managed, so no native
  libraries enter the Docker image, and being server-side it has no bearing on the F-Droid
  submission.

### Rejections

| Code | Cause | Message must be specific |
|---|---|---|
| 400 | Not an image / failed signature check | |
| 400 | HEIC | **Called out explicitly.** iPhone-on-Safari users will hit this and "unsupported file type" would baffle them. |
| 409 | Note already holds 10 images | |
| 413 | Over 10 MB (`[RequestSizeLimit]` with multipart headroom) | |
| 404 | No access — same response as "doesn't exist", per the non-enumeration stance | |

---

## 6. Web client

**Refactor first:** extract `useObjectUrl(blob)` from `Avatar.tsx` into `web/src/lib/`. That file
documents a real bug (`Avatar.tsx:24`): minting the URL in a `useMemo` broke under StrictMode, whose
mount→cleanup→mount cycle revoked a URL nothing recreated, leaking one per double-render. We are
about to create one object URL per image instead of one per session, so that create/revoke bracket
becomes a shared hook used by both `Avatar` and the new image component. This is the only change to
existing code.

**New `web/src/features/notes/media/`:**

- `queries.ts` — `useNoteMediaBlob(noteId, mediaId, size)`, shaped like `useProfileImage`
  (`parseAs: 'blob'`, 404 → null, throw on any other non-OK so a failure isn't cached as "no image").
  `staleTime: Infinity` is *more* defensible here than for avatars: an avatar's key is a user id
  whose bytes can change underneath it, whereas a media id's bytes never change.
- `NoteImage.tsx` — blob → object URL → `<img>`, box sized from the stored `width`/`height` so the
  column reserves space before the thumbnail arrives.
- `MediaStrip.tsx` — editor thumbnails with remove.
- `MediaLightbox.tsx` — click for full size.

**`NoteCard` renders C1:** full-bleed hero at the card top (aspect preserved, height-capped), title
on a bottom scrim with a text shadow, `+N` badge top-right, body and checklist rows below on the
note's own colour. Only two lines of large text ever sit over unpredictable pixels — the case that
decided C1 over C2 is a checklist note over a *pale* photo.

**Upload entry points:** picker button, drag-and-drop onto the editor modal, and **paste** (`Ctrl+V`
of a screenshot), which is nearly free to implement and the one people reach for daily.

**Optimistic, per the hard rule:** each file renders immediately as a pending tile from a local
object URL and uploads independently, so one failure doesn't take the batch down; success invalidates
the note query. In the composer the note doesn't exist yet, so files are held in component state, the
note is created on save, then the uploads run.

**Contract flow unchanged:** change the C# DTO → `npm run generate:api` → fix what goes red.

---

## 7. Android client

**Two new ops:** `PendingOp.AttachMedia(noteId, stagedPath, tempMediaId)` and
`PendingOp.DeleteMedia(noteId, mediaId)`. Both use `noteId` as `targetId`, so FIFO causality (a
note's `Create` always ahead of ops referencing it) holds unchanged. `Outbox.remapId`'s `when` is
exhaustive, so the compiler points at both branches needing the temp→real rewrite — a photo attached
to a note that was itself created offline is the case that would otherwise upload to a nonexistent
id.

**Coalescing carve-out:** `enqueue` collapses ops per note. Two photos on one note are two
independent uploads, and an `Update` must not swallow an `AttachMedia`. **Media ops never coalesce,
with either side.**

**Staging is correctness, not optimization.** A picked `content://` URI is a revocable permission
grant that dies on reboot, and the outbox routinely outlives both. Bytes are copied to
`filesDir/offline/media-staging/{tempMediaId}` **before** the op is enqueued, and deleted on success
or on permanent failure.

**Pending attachments are projected from the outbox, never written into the cache snapshot.**
`data/Dtos.kt` must mirror the C# DTOs exactly, so a client-only "pending" flag on `NoteMediaDto`
would be precisely the drift the hard rule forbids. Instead `NotesRepository` exposes a derived flow
merging server media with the outbox's `AttachMedia` ops for that note, rendered from the staged
file. One source of truth; DTOs stay clean.

**`MediaCache`** at `filesDir/offline/media/{mediaId}_{size}`, downloading through the existing
Retrofit client so auth and silent refresh come free. Immutable ids mean no invalidation logic —
just an LRU byte cap. Thumbnails prefetch after a full sync so the grid works offline.

**Rendering:** add Coil (Apache-2.0, no F-Droid concern). Since `MediaCache` already downloads to a
`File` with our own auth, `AsyncImage(model = file)` needs no custom fetcher — Coil only does decode,
downsampling and memory caching.

**The R8 landmine.** Camera capture needs a `FileProvider`, which the framework instantiates **by
class name from the manifest** — exactly the shape that shipped a dead widget twice. It goes into
`reflectivelyConstructed` in `app/build.gradle.kts` alongside the existing entries, and the
instrumented suite constructs it off the real dex.

**DTO sync:** `data/Dtos.kt` gains `NoteMediaDto` and `NoteDto.media`, field names and nullability
aligned exactly with the C# source of truth.

---

## 8. Concurrency

The collection is **append-only**, so media sidesteps the conflict problem entirely: two devices
attaching simultaneously both succeed and sort by `CreatedAtUtc`. None of the last-write-wins
reasoning that governs note bodies applies, and there is no merge logic to write.

---

## 9. Error handling

Extends `SyncEngine.permanentFailureMessage`:

- **Transient** — network, timeout, 5xx → existing backoff retry.
- **Permanent** — 400 (not an image), 409 (10-image cap), 413 (too large), 403 (Editor grant revoked
  mid-flight), 404 (note deleted or share revoked while offline) → drop the op, delete the staged
  file, surface a *specific* message. "Image too large (max 10 MB)" beats a generic sync error.

Server-side, the write/delete orderings in §4 mean a failed insert deletes its file and a crash
between the two leaves an orphan the sweep collects. On web, a failed file leaves a retryable tile
without killing the batch.

---

## 10. Rollout

**Additive and verified safe.** Both `ApiClient.json` and `LocalStore`'s JSON config set
`ignoreUnknownKeys = true` (`LocalStore.kt:38` documents exactly this reasoning), so an
already-installed APK ignores the new `media` field against an updated server. The API can ship
first; no coordinated release.

Phase order — each phase independently verifiable:

1. Backend: entity, storage, processing, endpoints, migration, sweep service.
2. Contract: `npm run generate:api`, then hand-sync `Dtos.kt`.
3. Web.
4. Android.

Web and Android are independent of each other once phase 2 lands.

---

## 11. Testing and verification

**Android — all three layers** (per `CLAUDE.md`):

1. *JVM unit tests:* the coalesce carve-out, `remapId` over media ops, staged-file lifecycle on both
   success and permanent failure, `MediaCache` eviction, and the pending-media projection.
2. *`verifyReleaseKeepRules`:* the new `FileProvider` entry.
3. *Instrumented smoke tests:* construct `FileProvider` off the real dex and attach a bundled test
   image. Any new keep rule must be the narrowest entry point that links, never the internals under
   test.

**Backend and web** have no test projects and this feature does not start one. Manual verification
script instead:

- Multipart upload through the Scalar UI.
- Share matrix: Viewer can `GET` bytes but not `POST`/`DELETE`; Editor can do all three;
  non-collaborator gets 404 on every one.
- The 409 (11th image), 413 (oversized) and HEIC-rejection paths.
- EXIF: upload a geotagged photo, confirm the stored original carries no GPS.
- The migration applied against real Postgres from `docker compose`.

**Honest flag:** media access control is the one place here that warrants an automated test, because
a mistake leaks one user's photos to another account. A minimal integration test project is worth
considering as a follow-up; it is deliberately not folded into this scope.

---

## 12. Documentation to update

- `ARCHITECTURE.md`: "Planned: note media" becomes implemented; add the endpoints and storage layout.
- `README.md`: drop the image-notes item from "What's next".
- `CLAUDE.md`: add the new folders to the layout tree.

# keepIT — Architecture

Reference doc for keepIT, a Google Keep-style notes app. `CLAUDE.md` holds the short
always-loaded rules; this file holds the reasoning and the detail. Read this before
making structural decisions.

## Goal

A Keep-style notes app: masonry grid of note cards, fast/optimistic editing, lists,
search, and live sync so a note edited on one device appears on others without a refresh.

## Shape of the system

Two independent deployables talking over HTTP + WebSocket:

- **`web/`** — React (Vite, TypeScript). The UI and all client logic.
- **`src/keepITCore`** — ASP.NET Core Web API (.NET 10). Business logic, persistence, auth, realtime.

They are built, versioned, and deployed separately. We deliberately do **not** host React
inside ASP.NET Core (the old SPA template approach) — keeping them separate lets either side
be redeployed or scaled alone and keeps the boundary clean.

A third client is **planned**: a **native Android app** (see **Android client** below). It is
not a third deployable in the Docker stack — it ships through the Play Store / an APK — but it
is a first-class consumer of the same `keepITCore` HTTP + SignalR API. The architectural
consequence is that **`keepITCore` is the single backend for every client**: the contract,
auth, and realtime model below must stay client-agnostic, not web-specific.

## Data flow

A note edit travels **down** the stack; live changes from other devices travel **back up**.

1. User edits a note → TanStack Query **mutation** fires with an optimistic update → UI changes instantly.
2. The mutation calls the **typed API client** → `keepITCore` endpoint → EF Core → PostgreSQL.
3. The API broadcasts the change over the **SignalR hub** → other devices receive it →
   they invalidate the relevant TanStack Query cache key → their UI re-syncs.
4. If the mutation errors, TanStack Query rolls the optimistic change back automatically.

## The API contract (most important rule)

The C# DTOs are the single source of truth for the API shape.

- `keepITCore` exposes an **OpenAPI/Swagger** document.
- A **typed TypeScript client** is generated from that document into `web/src/api/`.
- Workflow: change a C# DTO → regenerate the client → TypeScript compile errors point at
  every frontend spot that needs updating. No hand-maintained mirror types, no silent drift.

Generator options (pick one and wire it into `npm run generate:api`):
- **Kiota** — Microsoft's own, integrates cleanly with .NET.
- **openapi-typescript** + **openapi-fetch** — lighter, very popular, minimal runtime.

## Backend (`keepITCore`, .NET 10)

- **Endpoints:** controllers or minimal APIs — pick one style and stay consistent.
- **Persistence:** EF Core. **PostgreSQL (Npgsql) in production; SQLite as a dev fallback** —
  the provider is chosen at startup from configuration (see **Data & database
  configuration**). Entities + `DbContext` + migrations in `keepITCore/Data`.
- **PostgreSQL specifics:** use JSONB for flexible note metadata; use Postgres full-text
  search for note search rather than `LIKE` scans. These are Postgres-only — keep them behind
  the provider abstraction so the SQLite dev path degrades gracefully (see that section).
- **Auth:** ASP.NET Core Identity issues JWTs. Access token returned to the client and held
  in memory; refresh token set as an **httpOnly cookie** (safer against XSS than localStorage).
  A 401 triggers a silent refresh.
- **Validation:** FluentValidation on incoming requests.
- **Realtime:** a SignalR hub (e.g. `NotesHub`) broadcasts create/update/delete events.
  `@microsoft/signalr` is the official TS client on the frontend.

## Data & database configuration

Everything the backend persists is driven by **environment variables**, and everything it
writes to disk lives under **one common data folder**. PostgreSQL is the real database;
SQLite exists only to make local dev frictionless.

### Provider selection (Postgres, else SQLite)

At startup the app decides its EF Core provider from configuration:

- **If a Postgres connection is configured → use PostgreSQL (Npgsql).**
- **Otherwise → fall back to SQLite**, a file under the data folder
  (`{DataRoot}/keepit.db`).

"Configured" means a Postgres connection string is present, assembled from env vars. Support
both a single connection string and discrete parts, e.g.:

```
ConnectionStrings__Postgres = Host=db;Port=5432;Database=keepit;Username=keepit;Password=…
# or discrete:
POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
```

If `ConnectionStrings__Postgres` (or a complete set of `POSTGRES_*` parts) resolves to a
non-empty connection string, register Npgsql; if nothing usable is found, log a clear
"no Postgres configured — using SQLite dev database at {path}" and register SQLite. This keeps
`dotnet run` working out of the box with zero setup, while Docker/prod just sets the env vars.

### One common data folder

All backend-generated data lives under a single configurable root so it's trivial to back up,
mount as one Docker volume, and `.gitignore`:

- `App__DataRoot` — env var, **default `./App_Data`**. The one folder the core writes into.
  (Named `App_Data`, not `data`, so it never collides with the C# `Data/` source folder on
  case-insensitive filesystems like Windows/macOS.)
  - `{DataRoot}/keepit.db` — the SQLite dev database (only when SQLite is in use).
  - `{DataRoot}/media/…` — uploaded media files. **This supersedes the standalone
    `App__MediaRoot` default**: media now defaults to `{DataRoot}/media` (an explicit
    `App__MediaRoot` may still override it for media specifically).
  - Any other on-disk artifacts the core produces (e.g. Data Protection keys for cookie/token
    protection) go here too, so nothing app-generated is scattered outside this folder.
- The whole `App_Data/` folder is **user data**: never commit it — add it to `.gitignore` and
  `.dockerignore`, and mount it as a Docker volume so it survives redeploys.

### Provider differences to respect

The SQLite fallback is a **dev convenience, not a second supported target** — Postgres is the
source of truth. Because the two providers differ:

- **Migrations are provider-specific.** EF Core generates different SQL per provider. Keep the
  **Postgres migrations authoritative** (in `keepITCore/Data/Migrations`); for the SQLite dev
  path, either keep a parallel SQLite migrations set or use `EnsureCreated()`/auto-migrate on
  startup for throwaway local DBs. Don't try to share one migration across both.
- **JSONB & full-text search are Postgres-only.** SQLite has JSON1 and FTS5 but they map
  differently. Access these through a small abstraction (e.g. a search service / query helper)
  so on SQLite the app can degrade to a simpler `LIKE`-based search and plain JSON columns,
  while Postgres uses JSONB + full-text. Feature parity isn't the goal for the dev DB.

## Auth flow

JWT-based login is the standard for the whole app. ASP.NET Core Identity manages users
and password hashing; the API issues tokens.

**Tokens**
- **Access token** — short-lived JWT (e.g. ~15 min). Returned in the response body and held
  **in memory** on the client (never localStorage). Sent as `Authorization: Bearer <token>`.
- **Refresh token** — long-lived, opaque, set as an **httpOnly + Secure + SameSite** cookie
  so JS can't read it. Stored/rotated server-side so it can be revoked. A 401 from the API
  triggers a silent refresh; if refresh fails, the client logs out.

**Endpoints** (contract is C# DTOs as usual; all under `/api/auth`)
- `POST /register` — create account → returns access token + sets refresh cookie.
- `POST /login` — credentials → returns access token + sets refresh cookie.
- `POST /refresh` — reads the refresh cookie, rotates it, returns a new access token.
  No access token required.
- `POST /logout` — revokes the current refresh token and clears the cookie.
- `GET  /me` — returns the current user (requires a valid access token).

**Authorization rule (applies everywhere)**
- Every endpoint requires a valid JWT **except** `register`, `login`, and `refresh`.
- Every `Note`, `ChecklistItem`, `NoteImage`, `MediaItem`, and `List` belongs to an
  `ownerId`. **Ownership is fixed; access is what queries check.** A caller's access to a
  note is *ownership **or** an explicit share* (see **Sharing / collaboration**). Every
  query/command resolves the caller's access first and a user can never read or mutate a
  note they neither own nor have a share on. `List`s are always private to their owner and
  are never shared (see Sharing for how collaborators organize a shared note).
- `GET /api/media/{id}` enforces this access check before streaming bytes — owning the id is
  not enough; the caller must own the media item **or** own/have a share on a note that
  references it.

**SignalR auth**
- The `NotesHub` requires authentication too. Browsers can't set custom headers on the
  WebSocket handshake, so the access token is passed via the query string
  (`?access_token=…`); configure JWT bearer's `OnMessageReceived` to read it for hub paths.
- The hub broadcasts a note's changes to everyone with access to it — the owner's other
  devices **and** every collaborator's devices. Use a **group per note** (`note:{noteId}`)
  that a connection joins for each note it can access; private (unshared) notes are simply a
  group of one user. Realtime never leaks to a user who has neither ownership nor a share.
- When a share is granted or revoked, update the affected user's group membership so access
  changes take effect live (a revoked collaborator stops receiving updates immediately).

## Sharing / collaboration

A note can be shared with other users so they see it in their own grid and (optionally) edit
it. This is the one place the app deliberately relaxes strict per-user data isolation — and
because it does, the access rules below are mandatory, not optional.

**Model.** A `NoteShare` row grants one user (`granteeId`) access to one note at a `role`:
- **Viewer** — read-only. Sees the note and its live updates; cannot mutate it.
- **Editor** — read + write. Can edit body/checklist items/images like the owner, but
  **cannot** delete the note, re-share it, or change other people's roles.

The **owner** is implicit (no `NoteShare` row) and is the only one who can share/un-share,
change roles, or hard-delete the note. Ownership never transfers.

**Access resolution (used by every note query/command).** A caller may act on a note iff:
`note.ownerId == caller` **OR** a `NoteShare(noteId, granteeId == caller)` exists — and for
writes, that share's role is `Editor`. The grid query is therefore "notes I own **UNION**
notes shared with me," not a flat `ownerId == me` filter. Centralize this in one
authorization helper so no endpoint can forget it.

**What is and isn't shared.** The note's content is shared: title, body, checklist items,
image notes, and background — including the referenced `MediaItem` bytes, which a
collaborator reaches *through the note*, not by owning the media (see the media access rule
above). **Not** shared: `List`s (private per user via the `NoteList.userId` join — a
collaborator can file a shared note into their own lists without the owner seeing it), and a
note's pinned/archived/trashed state is **per user** too — a collaborator pinning or
archiving a shared note changes only their own view, not the owner's.

**Endpoints** (all under `/api/notes/{id}/shares`, all require the caller to be the owner
except where noted):
- `POST   /api/notes/{id}/shares` — share the note with a user (by email or user id) at a
  role → creates a `NoteShare`, adds the grantee to the note's SignalR group.
- `GET    /api/notes/{id}/shares` — list collaborators and their roles (owner, or any
  collaborator may read who else has access).
- `PATCH  /api/notes/{id}/shares/{granteeId}` — change a collaborator's role.
- `DELETE /api/notes/{id}/shares/{granteeId}` — revoke a share. The grantee may also call
  this on **their own** share to *leave* a note they no longer want.

**Invites to non-users.** Sharing by email where no account exists yet can either be
rejected ("user not found") or recorded as a pending invite keyed by email and resolved on
signup. Start with the simple version (require an existing user); pending-invite is a later
refinement.

**Edge cases to honor.**
- **Concurrent edits.** Two editors can touch the same note at once. Optimistic updates +
  SignalR keep them roughly in sync; use `updatedAt`/row-version for last-write-wins and
  surface conflicts rather than silently clobbering. Field-level merge/CRDT is out of scope
  for v1.
- **Revocation is immediate.** Removing a share drops the user from the note's SignalR group
  and any subsequent API call 403s — no stale access.
- **Deleting a shared note.** Only the owner purges it; doing so cascades its `NoteShare`
  rows and the note vanishes from every collaborator's grid.

## Frontend (`web/`)

- **Build/dev:** Vite. In dev, Vite's proxy forwards `/api` to the backend to avoid CORS.
  In prod, React is served as static files (nginx) and the API runs separately.
- **Server state:** TanStack Query owns everything fetched from the API — caching,
  background refetch, optimistic mutations. Do not duplicate this into a global store.
- **Client/UI state:** plain React state, or Zustand only for genuinely client-side UI state.
- **HTTP:** the generated typed client (openapi-fetch or a Kiota client).
- **Realtime:** `@microsoft/signalr`; on an incoming event, invalidate the matching query key.
- **Styling:** Tailwind. Masonry via CSS columns or a small masonry lib. See **Look & feel**
  for the visual direction (Keep-like layout, dark-first, modern).
- **Layout shell:** a left **sidebar** (Notes, Lists, Archive, Trash + the user's lists for
  one-click filtering), a top search bar, and the masonry note grid as the main pane.
- **Routing:** React Router (or TanStack Router for typed routes).

## Android client (planned)

A **native Android app** is a planned future client. It is **not** a web view wrapper and not
part of the Docker stack — it's a separately built, store-distributed app that talks to the
**same `keepITCore` REST + SignalR API** as the web app. Treat it as another consumer of the
contract, never a reason to special-case the backend.

- **Native, not hybrid.** Built in **Kotlin** with **Jetpack Compose** (Material 3). No React
  Native / WebView shell — the point is a real native experience and, critically, a real
  **home-screen widget**, which a wrapped web app can't provide well.
- **Same contract, generated client.** The C# DTOs stay the single source of truth. Generate a
  **Kotlin client from the same OpenAPI document** (e.g. OpenAPI Generator's `kotlin` +
  Retrofit/Ktor) so the Android models can't silently drift from the API — the mobile mirror of
  the web's `npm run generate:api` rule. Do **not** hand-maintain Kotlin DTOs.
- **Auth on mobile.** Same JWT model, but the browser cookie assumptions don't apply. The
  refresh token can't live in an httpOnly cookie on native, so store it in **Android's
  encrypted storage** (EncryptedSharedPreferences / DataStore backed by the Keystore), keep the
  access token in memory, and reuse the existing `/api/auth/refresh` rotation flow. The backend
  refresh-token handling shouldn't need to care which client holds the token.
- **Offline-first.** Phones go offline; the app should keep working. Cache notes locally (Room),
  show them immediately, and queue mutations to replay when connectivity returns — the mobile
  analogue of the web's optimistic updates, reconciled against the server on reconnect.
- **Realtime.** Use the official SignalR Kotlin/Java client against `NotesHub`, passing the
  access token via the query string exactly as the web client does (see **SignalR auth**).
  Fall back to refetch-on-resume when a socket isn't held open in the background.

### Home-screen widget

The widget is the headline reason for going native:

- **Built with Jetpack Glance** (Compose-style API over App Widgets).
- **Quick capture** — a "take a note" affordance that deep-links into the app's composer (or, for
  text, posts directly through the API) so a thought can be saved without fully opening the app.
- **Glanceable notes** — show pinned / recent notes (and optionally check off checklist items)
  from the local cache, refreshed in the background via WorkManager and on SignalR pushes.
- **Auth-aware** — when logged out, the widget shows a sign-in prompt rather than stale content.

This section is **forward-looking**: no Android code exists in the repo yet. When it lands it
will live in its own top-level module (e.g. `android/`), built and released independently of
`web/` and `keepITCore`.

## Look & feel / design

The UI should read as **clearly Google Keep** — same mental model and layout — but **darker
and more modern**, not a pixel clone.

- **Keep-like layout.** Masonry grid of note cards; a collapsed "Take a note…" composer
  pinned at the top that expands inline; hover/long-press actions on cards (pin, change
  background, add to list, archive); a left sidebar for navigation (Notes / Lists / Archive /
  Trash) and per-list filtering; a top search bar. The interaction model is Keep's;
  familiarity is the point.
- **Dark-first theme.** The default and primary theme is **dark** — deep neutral background
  (near-black/charcoal, e.g. `zinc-900/950`), slightly elevated note cards (`zinc-800`) so
  the masonry grid reads as cards floating above the canvas, and an accessible accent color
  for actions/active list. Keep's note background **colors** still apply per note and must be
  re-tuned to look right on a dark canvas (muted/desaturated palette, not the bright pastels
  Keep uses on white). A light theme is optional later; design tokens (CSS variables / Tailwind
  theme) should make dark vs light a token swap, not a rewrite.
- **Modern, restrained styling.** Generous spacing, soft rounded corners, subtle elevation
  via shadow/border rather than heavy chrome, smooth micro-interactions (card hover lift,
  optimistic add/remove transitions), and good empty/loading states. Avoid the flat,
  generic look — aim for a polished, intentional product feel.
- **Responsive.** Grid column count adapts from a single column on mobile up to several on
  wide screens; sidebar collapses to an overlay/drawer on small screens.
- **Accessibility.** Meet WCAG AA contrast on the dark theme, full keyboard navigation for
  the grid and composer, and respect `prefers-reduced-motion`.

## Note functions (product definition)

A note is one of several **types**, and any note can carry a customizable background.
These are the required functions of a saved note:

1. **Text note** — free-form text (rich text or markdown) in `body`. The default type.
2. **Checklist note** — an ordered list of checkbox items, each with its own text and
   checked/unchecked state. Items can be reordered, checked off, added, and removed.
3. **Image note** — the note's primary content is one or more images (e.g. a photo,
   a screenshot, a scanned page) rather than text.
4. **Customizable background** — every note (regardless of type) can set a background:
   either a **color** (from a palette) **or** a **background image**. This is distinct
   from an image *note*: here the image is decoration behind the content.

## Note model (starting point)

Refine in EF Core; this is the intent, not final schema. A single `Note` table with a
`type` discriminator keeps querying the masonry grid simple; type-specific data hangs off it.

- `Note`: id, ownerId, **type** (`Text` | `Checklist` | `Image`), title, body (text/markdown,
  used by Text notes), color (palette enum/string, nullable), **backgroundImageId** (FK to a
  stored media item, nullable), isPinned, isArchived, createdAt, updatedAt, and a JSONB
  metadata column for anything flexible. A note has **either** `color` **or**
  `backgroundImageId` set for its background, not both.
- `ChecklistItem`: id, noteId, text, isChecked, order. One-to-many from `Note`
  (populated for `Checklist` notes).
- `NoteImage`: id, noteId, mediaId (FK), order. One-to-many from `Note`
  (populated for `Image` notes; ordered for multi-image notes).
- `MediaItem`: id, ownerId, storageKey, contentType, byteSize, width, height, createdAt.
  The single record for any uploaded binary — referenced by both `NoteImage` (image notes)
  and `Note.backgroundImageId` (background images). See **Media / image storage** below.
- `List`: id, ownerId, name, color (optional, for the sidebar chip), createdAt. A named
  collection a user creates in the **Lists** section. Many-to-many with `Note` — but the
  join is **per user** (`NoteList`: noteId, listId, **userId**, addedAt), so a collaborator
  filing a shared note into one of their lists organizes it for *themselves* only, without
  affecting the owner or other collaborators. A note can belong to zero, one, or many lists.
  See **Lists** below.
- `NoteShare`: id, noteId, granteeId (the user the note is shared with), **role**
  (`Viewer` | `Editor`), createdAt, createdBy. One row per (note, grantee); the owner is
  implicit and never has a `NoteShare` row. This is the single source of truth for "who can
  see/edit a note besides its owner." See **Sharing / collaboration**.
- Soft-delete (trash) rather than hard delete, to mirror Keep's behavior.

## Lists

Lists are the app's way of organizing notes into named collections (this replaces the
generic "labels" idea — there is **one** grouping mechanism and it is Lists). They behave
like Keep's labels but are presented as user-curated lists with their own section.

**Behavior.**
- A user creates and manages lists in a dedicated **Lists** section (sidebar nav).
- Any note can belong to **zero, one, or many** lists; adding/removing a note to/from a list
  is just inserting/deleting a `NoteList` join row.
- The grid can be **filtered** by list: selecting one list shows only its notes; selecting
  several filters to notes in **any** of the selected lists (union). A "No list" / unfiled
  view shows notes the user has put in no list.
- Lists are **per user and private** (`NoteList.userId`). On a shared note, each
  collaborator files it into their *own* lists; one user's lists are never visible to
  another, and the owner's lists don't travel with a shared note. This keeps Lists private
  even though the note's content is shared.
- Renaming or deleting a list never deletes notes — deleting a list just removes its
  `NoteList` join rows (the notes themselves remain, just unfiled from that list).

**Endpoints** (all scoped to the caller; under `/api/lists`):
- `GET    /api/lists` — the caller's lists (with note counts for the sidebar).
- `POST   /api/lists` — create a list (name, optional color).
- `PATCH  /api/lists/{id}` — rename / recolor.
- `DELETE /api/lists/{id}` — delete the list (drops its `NoteList` rows; notes survive).
- `PUT    /api/notes/{id}/lists` — set the lists a note belongs to **for the caller**
  (replace the set), or use `POST`/`DELETE /api/notes/{id}/lists/{listId}` to add/remove one.
- Filtering is a query on the existing notes-list endpoint, e.g.
  `GET /api/notes?listId=…` (repeatable for a multi-list union, `&listId=…`).

**Frontend.** List membership and filter state are server-owned where they're persisted
(`NoteList`) — TanStack Query keys include the active list filter so switching lists is a
cache key change, not a refetch hack. The selected-filter UI state itself is client state.

## Media / image storage

Both image notes and background images need somewhere to put binaries. Rules:

- **Never store image bytes in PostgreSQL.** The DB holds only the `MediaItem` *metadata*
  (id, owner, storage key, content type, size, dimensions). The bytes live outside the DB.
- **Backing store behind an abstraction.** Define an `IMediaStorage` service with
  `Save`, `Open`, and `Delete`. Start with a **local media folder** implementation under the
  common data folder (defaults to `{App__DataRoot}/media`, i.e. `./App_Data/media`; an explicit
  `App__MediaRoot` overrides it). It's mounted as a Docker volume so it survives redeploys;
  keep the interface so it can later swap to S3/MinIO/Azure Blob without touching callers.
  **Do not commit the media folder** — it's user data, covered by ignoring the `App_Data/` folder
  (see **Data & database configuration**).
- **Storage keys, not user filenames.** Generate an opaque key on upload
  (e.g. `{ownerId}/{guid}{ext}`) and fan files out into subfolders to avoid one giant
  directory. Store the key in `MediaItem.storageKey`; never trust the client's filename
  for paths (path-traversal risk).
- **Upload flow:** client POSTs `multipart/form-data` to a media endpoint → validate
  content type + size limit → write via `IMediaStorage` → create `MediaItem` → return its id.
  The id is then attached to a note (as a `NoteImage` or as `backgroundImageId`).
- **Serving:** stream bytes through an API endpoint (`GET /api/media/{id}`) that checks
  ownership/authorization, sets the right content type, and allows long-lived caching
  (immutable content + hashed/opaque keys). Generate thumbnails for grid display so the
  masonry view doesn't download full-size originals.
- **Lifecycle:** when a note is hard-deleted (purged from trash) or an image is removed,
  delete the orphaned `MediaItem` and its underlying file. A periodic orphan sweep is a
  reasonable safety net.

## Deployment

**The target is to run the entire stack in Docker.** Local dev can run bare (`dotnet run` +
`npm run dev`), but the intended way to run keepIT — and the deployment model everything is
built toward — is a single `docker compose up` that brings up every piece as a container.

- **Everything runs in Docker.** `docker-compose.yml` defines the full stack as services:
  - **`api`** — the `keepITCore` container (built from `src/keepITCore/Dockerfile`).
  - **`db`** — PostgreSQL (with a named volume for its data).
  - **`web`** — the React frontend, built to static files and served by a small **nginx**
    container that is also the single entrypoint: it reverse-proxies `/api` to the `api`
    container so both are one origin (no CORS, same-origin refresh cookie). The frontend is a
    **separate container**, not hosted inside the API. Swap nginx for any reverse proxy (Caddy,
    a cloud load balancer, etc.) if you prefer — nothing in the app depends on a specific one.
  - **`redis`** — added if/when caching or a scale-out SignalR backplane is needed.

  For real TLS, terminate HTTPS at the proxy (or a load balancer in front of it) and set
  `Auth__RefreshCookie__Secure=true`.
- Compose passes the Postgres connection via env vars (`ConnectionStrings__Postgres` or
  `POSTGRES_*`), so the API uses Postgres in the stack and silently falls back to SQLite only
  when run bare for local dev.
- Mount the common data folder (`App__DataRoot`, default `./App_Data`) as a named volume so
  media, Data Protection keys, and any other app-written files survive redeploys.
- Each service reads its config from the environment (the `.env` file / Compose `environment`),
  so the same images run unchanged in dev, staging, and prod with only env values differing.

## Build order (suggested)

1. Get `keepITCore` returning a valid OpenAPI/Swagger document.
2. Wire `npm run generate:api` to produce the typed client off that document.
3. Stand up the note CRUD endpoints + EF Core model + migration. Wire startup provider
   selection (Postgres-from-env, else SQLite under `App__DataRoot`) at the same time.
4. Add TanStack Query hooks and the optimistic mutation flow.
5. Add the SignalR hub and cache invalidation on the client.
6. Layer in auth (Identity + JWT + refresh cookie).

Everything builds on steps 1–2: get the contract working first so the frontend is
type-safe from day one.

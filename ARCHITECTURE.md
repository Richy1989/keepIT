# keepIT — Architecture

Reference doc for keepIT, a Google Keep-style notes app. `CLAUDE.md` holds the short
always-loaded rules; this file holds the reasoning and the detail. Read this before
making structural decisions.

## Goal

A Keep-style notes app: masonry grid of note cards, fast/optimistic editing, labels,
search, and live sync so a note edited on one device appears on others without a refresh.

## Shape of the system

Two independent deployables talking over HTTP + WebSocket:

- **`web/`** — React (Vite, TypeScript). The UI and all client logic.
- **`src/keepITCore`** — ASP.NET Core Web API (.NET 10). Business logic, persistence, auth, realtime.

They are built, versioned, and deployed separately. We deliberately do **not** host React
inside ASP.NET Core (the old SPA template approach) — keeping them separate lets either side
be redeployed or scaled alone and keeps the boundary clean.

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
- **Persistence:** EF Core with the Npgsql provider. Entities + `DbContext` + migrations in `keepITCore/Data`.
- **PostgreSQL specifics:** use JSONB for flexible note metadata; use Postgres full-text
  search for note search rather than `LIKE` scans.
- **Auth:** ASP.NET Core Identity issues JWTs. Access token returned to the client and held
  in memory; refresh token set as an **httpOnly cookie** (safer against XSS than localStorage).
  A 401 triggers a silent refresh.
- **Validation:** FluentValidation on incoming requests.
- **Realtime:** a SignalR hub (e.g. `NotesHub`) broadcasts create/update/delete events.
  `@microsoft/signalr` is the official TS client on the frontend.

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
- All data is **scoped to the authenticated user**: every `Note`, `ChecklistItem`,
  `NoteImage`, `MediaItem`, and `Label` belongs to an `ownerId`, and every query/command
  filters by the caller's id. A user can never read or mutate another user's notes or media.
- `GET /api/media/{id}` enforces this ownership check before streaming bytes — owning the
  id is not enough; the caller must own the media item.

**SignalR auth**
- The `NotesHub` requires authentication too. Browsers can't set custom headers on the
  WebSocket handshake, so the access token is passed via the query string
  (`?access_token=…`); configure JWT bearer's `OnMessageReceived` to read it for hub paths.
- The hub only broadcasts a user's changes to **that same user's** other devices (group per
  `ownerId`), so realtime never leaks across accounts.

## Frontend (`web/`)

- **Build/dev:** Vite. In dev, Vite's proxy forwards `/api` to the backend to avoid CORS.
  In prod, React is served as static files (Traefik/Nginx) and the API runs separately.
- **Server state:** TanStack Query owns everything fetched from the API — caching,
  background refetch, optimistic mutations. Do not duplicate this into a global store.
- **Client/UI state:** plain React state, or Zustand only for genuinely client-side UI state.
- **HTTP:** the generated typed client (openapi-fetch or a Kiota client).
- **Realtime:** `@microsoft/signalr`; on an incoming event, invalidate the matching query key.
- **Styling:** Tailwind. Masonry via CSS columns or a small masonry lib.
- **Routing:** React Router (or TanStack Router for typed routes).

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
- `Label`: id, ownerId, name. Many-to-many with `Note`.
- Soft-delete (trash) rather than hard delete, to mirror Keep's behavior.

## Media / image storage

Both image notes and background images need somewhere to put binaries. Rules:

- **Never store image bytes in PostgreSQL.** The DB holds only the `MediaItem` *metadata*
  (id, owner, storage key, content type, size, dimensions). The bytes live outside the DB.
- **Backing store behind an abstraction.** Define an `IMediaStorage` service with
  `Save`, `Open`, and `Delete`. Start with a **local media folder** implementation
  (e.g. `App__MediaRoot`, default `./media`, mounted as a Docker volume so it survives
  redeploys); keep the interface so it can later swap to S3/MinIO/Azure Blob without
  touching callers. **Do not commit the media folder** — it's user data, add it to
  `.gitignore`/`.dockerignore`.
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

- Docker for everything. `docker-compose.yml` runs the API + PostgreSQL (+ Redis if/when
  caching or scale-out SignalR backplane is needed).
- Traefik as the reverse proxy / TLS terminator in front of both the API and the static frontend.

## Build order (suggested)

1. Get `keepITCore` returning a valid OpenAPI/Swagger document.
2. Wire `npm run generate:api` to produce the typed client off that document.
3. Stand up the note CRUD endpoints + EF Core model + migration.
4. Add TanStack Query hooks and the optimistic mutation flow.
5. Add the SignalR hub and cache invalidation on the client.
6. Layer in auth (Identity + JWT + refresh cookie).

Everything builds on steps 1–2: get the contract working first so the frontend is
type-safe from day one.

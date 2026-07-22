# keepIT — Architecture

Reference doc for keepIT, a Google Keep-style notes app. `CLAUDE.md` holds the short
always-loaded rules; this file holds the reasoning and the detail. Read this before
making structural decisions.

## Goal

A Keep-style notes app: masonry grid of note cards, fast/optimistic editing, lists,
search, sharing between users, per-note reminders, and live sync so a note edited on one
device appears on others without a refresh — plus a native Android app with offline
support and a home-screen widget, all self-hosted.

## Shape of the system

One backend, three clients:

- **`keepIT/keepITCore`** — ASP.NET Core Web API (.NET 10). Business logic, persistence,
  auth, realtime, background jobs. **The single backend for every client** — the contract,
  auth, and realtime model are client-agnostic, never web-specific.
- **`web/`** — React (Vite, TypeScript). The web UI and all its client logic.
- **`app/`** — native Android app (Kotlin, Jetpack Compose). Offline-first, with native
  reminder notifications and a home-screen widget. Not part of the Docker stack — it ships
  as an APK (attached to GitHub Releases) and talks to the same HTTP + SignalR API.

Web and API are built, versioned, and deployed separately over HTTP + WebSocket. We
deliberately do **not** host React inside ASP.NET Core (the old SPA template approach) —
keeping them separate lets either side be redeployed alone and keeps the boundary clean.
(The single-container Docker image co-locates nginx and the API as separate *processes* in
one image for deployment convenience — see **Deployment** — but the SPA is still served by
nginx, never by ASP.NET.)

## Data flow

A note edit travels **down** the stack; live changes from other devices travel **back up**.

1. User edits a note → TanStack Query **mutation** fires with an optimistic update → UI changes instantly.
2. The mutation calls the **typed API client** → `keepITCore` endpoint → EF Core → PostgreSQL (or SQLite in dev).
3. After saving, the endpoint pushes a per-user **change signal** over the **SignalR hub**
   (`Changed(["notes","lists"])`) → the user's other devices receive it → they invalidate the
   matching TanStack Query cache keys → their UI re-syncs. The signal names *what* changed; the
   data itself is reloaded through the REST API, not carried in the message.
4. If the mutation errors, TanStack Query rolls the optimistic change back automatically.

The Android app follows the same shape but batches it for offline: mutations apply to a local
cache immediately and queue in an **outbox**; a sync engine replays the queue and refetches
when connectivity (or a SignalR push) arrives. See **Android client**.

## The API contract (most important rule)

The C# DTOs are the single source of truth for the API shape.

- `keepITCore` exposes an **OpenAPI** document (`/openapi/v1.json` in Development, with the
  interactive Scalar UI at `/scalar/v1`).
- A **typed TypeScript client** is generated from that document into `web/src/api/`:
  **openapi-typescript** + **openapi-fetch** (light, minimal runtime), wired into
  `npm run generate:api` → emits `web/src/api/schema.d.ts`, consumed by the typed client in
  `web/src/api/client.ts`. Workflow: change a C# DTO → regenerate → TypeScript compile errors
  point at every frontend spot that needs updating. No hand-maintained mirrors, no silent drift.
- Enums that cross the wire carry `JsonStringEnumConverter`, so the document (and the TS
  client) get a union of string names (`"Text" | "Checklist"`), not opaque numbers. A schema
  transformer (`NumericSchemaTransformer`) also strips .NET's lenient integer-or-string unions
  so numbers generate as plain numbers.
- **Known deviation — the Android app hand-mirrors the DTOs** (`app/.../data/Dtos.kt`,
  kotlinx.serialization): no Kotlin OpenAPI generator is wired up yet. The C# DTOs remain
  authoritative — when one changes, `Dtos.kt` must be updated by hand to match. Enum-like
  fields travel as their C# enum names and are modeled as strings with the known values as
  constants. Wiring up a generated Kotlin client is still the intended end state.

## Backend (`keepITCore`, .NET 10)

- **Endpoints:** **controllers** (`[ApiController]`), one per resource — `NotesController`,
  `NoteSharesController`, `ListsController`, `AuthController`, `UserSettingsController`,
  `UserNotificationController`, `MetaController`.
- **Persistence:** EF Core. **PostgreSQL (Npgsql) in production; SQLite as a dev fallback** —
  the provider is chosen at startup from configuration (see **Data & database configuration**).
  Entities + `AppDbContext` + migrations in `keepITCore/Data`.
- **Auth:** ASP.NET Core Identity issues JWTs. Access token in the response body, held in
  memory client-side; refresh token as a rotating **httpOnly cookie** (see **Auth flow**).
- **Validation:** **DataAnnotations** on the request DTOs, validated automatically by
  `[ApiController]` before the action runs. A custom `InvalidModelStateResponseFactory`
  surfaces the first error message in `ValidationProblemDetails.Detail` so clients can show
  one friendly line. Password complexity is additionally enforced by Identity.
- **Realtime:** SignalR hub (`RealTimeHub`, mapped at `/api/realtime`) pushes per-user change
  signals after mutations. See **SignalR realtime**.
- **Background work:** `ReminderDispatcherService`, a hosted service that fires due note
  reminders every 30 s. See **Reminders**.
- **Logging:** Serilog — clean colored console, one request-log line per request, levels from
  the `Serilog` config section.
- **Edge protection:** forwarded-headers handling + per-IP rate limiting registered in
  `Infrastructure/Security/`. See **Security & abuse protection**.
- **Email:** an `IEmailSender` abstraction — SMTP (`SmtpEmailSender`) when `Email__SmtpHost`
  is configured, otherwise `LogOnlyEmailSender` writes the message to the server log. Used by
  password reset and the settings page's test-email button. On a self-hosted instance the
  operator owns the logs, so "reset link lands in the log" is a legitimate no-SMTP mode.

## Data & database configuration

Everything the backend persists is driven by **environment variables**, and everything it
writes to disk lives under **one common data folder**. PostgreSQL is the real database;
SQLite exists to make local dev and the single-container deployment zero-setup.

### Provider selection (Postgres, else SQLite)

At startup (`Infrastructure/DatabaseSetup.cs`) the app resolves a Postgres connection string
from configuration:

- `ConnectionStrings__Postgres` — a full connection string; takes precedence.
- …or discrete parts: **`POSTGRES_HOST` is the switch** — setting it builds the string from
  `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD`
  (defaults `5432`/`keepit`/`keepit`).

If a connection string resolves → **Npgsql**; otherwise → **SQLite** at `{DataRoot}/keepit.db`
(logged clearly). This keeps `dotnet run` and the single-container image working with zero
setup, while Compose/prod just set the env vars.

> SQLite note: the project deliberately uses `Microsoft.EntityFrameworkCore.Sqlite.Core` plus
> the patched `SourceGear.sqlite3` native binary (registered manually in `Program.cs`) to avoid
> the vulnerable `SQLitePCLRaw.lib.e_sqlite3` bundle.

### Database initialization

- **Postgres runs migrations at startup** (`Database.Migrate()` in `Program.cs`) — the
  migrations in `Data/Migrations` are **Postgres-authoritative** (the design-time factory
  `AppDbContextFactory` targets Npgsql).
- **SQLite uses `EnsureCreated()`** — a throwaway dev DB created from the current model. It
  won't alter an existing file after entity changes; delete `App_Data/keepit.db` to rebuild.

### One common data folder

`App__DataRoot` (default `./App_Data`, resolved and created by
`Infrastructure/FolderManagement.cs`) is the one folder the backend writes into — trivial to
back up and to mount as a single Docker volume:

- `{DataRoot}/keepit.db` — the SQLite database (only when SQLite is in use).
- `{DataRoot}/keys/` — ASP.NET Data Protection keys (cookie/token protection).
- `{DataRoot}/users/{userId}/profile_image/` — uploaded profile images.

(Named `App_Data`, not `data`, so it never collides with the C# `Data/` source folder on
case-insensitive filesystems.) The whole folder is **user data**: gitignored, dockerignored,
mounted as a volume so it survives redeploys — never commit it.

### What was deliberately *not* built

Earlier drafts planned Postgres JSONB metadata columns and Postgres full-text search. Neither
exists: the model needed no flexible metadata yet, and at personal-notes scale **search is
client-side** (the web app filters the already-cached grid by title/body/checklist text —
instant, no endpoint, no provider divergence). Server-side search only becomes worth it if a
user's dataset outgrows "fetch the grid", and would then be a Postgres-only feature behind a
service abstraction.

## Auth flow

JWT-based login for the whole app. ASP.NET Core Identity manages users and password hashing;
the API issues tokens.

**Tokens**
- **Access token** — short-lived JWT (`Jwt__AccessTokenMinutes`, default 15). Returned in the
  response body and held **in memory** on the client (web: `tokenStore.ts`; Android: an
  in-memory `TokenStore`). Sent as `Authorization: Bearer <token>`. Carries the user id in
  the `sub` claim.
- **Refresh token** — long-lived (`Jwt__RefreshTokenDays`, default 14), opaque, set as an
  **httpOnly + Secure + SameSite=Strict** cookie so JS can't read it. Stored server-side
  **hashed** (`RefreshToken` entity: token hash, expiry, revocation, replaced-by chain) so a
  DB leak doesn't leak usable tokens and individual tokens can be revoked. A 401 triggers a
  silent refresh; only a **401 from `/refresh` itself** signs the client out — transient
  failures (429/5xx/network) are retried and never treated as a lost session, because the
  cookie is still valid.
- **Rotation + reuse detection.** Every `/refresh` revokes the presented token and issues a
  replacement. Presenting a token that was already rotated/revoked (but not expired) is the
  signature of a stolen cookie being replayed — **all** of the user's active refresh tokens
  are revoked, forcing both the attacker and the real user to sign in again. Expired rows are
  cleaned up opportunistically; revoked-but-unexpired rows are kept because they *are* the
  replay detector.
- **Rotation grace window (60 s).** A replay *within a minute of the rotation* is exempt from
  the family-wide revoke: that's the browser losing the rotation response (a reload aborting
  the in-flight refresh, or two tabs racing on the shared cookie), not an attacker who sat on
  a stolen cookie. Such a caller gets a fresh sibling token instead. Client-side, tabs also
  serialize refreshes with a cross-tab Web Lock, so the grace path is the backstop, not the
  norm. (Logout revokes without a replaced-by link, so a logged-out token never qualifies.)

**Endpoints** (all under `/api/auth`; contract is the C# DTOs as usual)
- `POST /register` — create account → access token + refresh cookie. Refused with 403 when
  `App__AllowRegistration=false` — the intended mode for an internet-exposed personal
  instance: create your accounts, then close the door (existing users are unaffected).
- `POST /login` — credentials → access token + refresh cookie. Failed attempts count toward
  Identity's per-account **lockout**; a locked account gets the same generic 401 as bad
  credentials (no account/lock-state enumeration).
- `POST /refresh` — rotates the cookie, returns a new access token. No access token required.
- `POST /logout` — revokes the current refresh token and clears the cookie. Idempotent.
- `POST /changepassword` — verifies the current password, sets the new one, then **revokes
  every refresh token** (signs out all other devices) and issues fresh tokens so the current
  device stays signed in.
- `POST /forgot-password` — **always 204**, whether or not the account exists (no email
  enumeration). When it does, a single-use, time-limited Identity reset token is generated and
  the link is delivered via `IEmailSender` (SMTP or the server log). The link points at the
  frontend's `/reset-password` page; its base URL is `App__PublicBaseUrl` if set, else the
  request's `Origin` header (dev: Vite origin), else the request's own scheme+host (prod:
  one origin anyway).
- `POST /reset-password` — completes the reset with the emailed token. Clears any lockout
  (proving control of the email outranks a possibly attacker-induced lockout) and revokes all
  refresh tokens; the user signs in fresh. Bad/expired tokens get a generic error; password-
  rule failures are surfaced in detail (the caller has already proven email control).
- `GET  /me` — the current user (requires a valid access token).

The credential endpoints (register, login, change-/forgot-/reset-password) carry the tight
`auth` rate limit; `/refresh`, `/logout`, and `/me` deliberately sit under only the global
limit — every page reload refreshes, and throttling that signs real users out (see
**Security & abuse protection**).

**Authorization rule (applies everywhere)**
- Every endpoint requires a valid JWT **except** register, login, refresh, logout,
  forgot-/reset-password, and `GET /api/meta`.
- Every resource row carries an owner id, and every query is scoped via `User.GetUserId()`.
  A caller's access to a **note** is *ownership OR an explicit share*, resolved through
  `NoteAccessService` — never a bare `OwnerId == me` (see **Sharing / collaboration**).
  Private resources (lists, settings, notifications, reminders, per-user note state) are
  strictly caller-scoped.
- Profile images have their own narrow rule — see **Profile images**.

**SignalR auth**
- `RealTimeHub` is `[Authorize]`. Browsers can't set headers on the WebSocket handshake, so
  the access token is passed via the query string (`?access_token=…`); JWT bearer's
  `OnMessageReceived` reads it, scoped to the `/api/realtime` path. The Android SignalR client
  authenticates the same way.

## Security & abuse protection

The app is designed to be self-hosted and possibly internet-exposed, so the edge is hardened
in the API itself (`Infrastructure/Security/`) and in the nginx config:

- **Rate limiting** (per client IP): a global sliding window of **120 req/min** on everything,
  and a tighter fixed window of **10 req/min** on the credential endpoints (register, login,
  change-/forgot-/reset-password — password guessing / signup abuse) via the named `auth`
  policy. `/refresh`, `/logout`, and `/me` stay on the global limit only: they run on every
  page load, and a 429 there would knock legitimate sessions out. Rejected callers get 429 +
  `Retry-After`.
- **Forwarded headers:** the API sits behind nginx (and possibly Traefik), so it trusts
  `X-Forwarded-For`/`-Proto` to recover the real client IP — which the rate limiter keys on.
  **`App__ForwardedProxyHops` must equal the number of proxy hops** (1 for the plain stacks,
  2 behind Traefik → nginx): too low and all clients share the proxy's rate-limit bucket, too
  high and a client can spoof its IP with a forged header.
- **No HTTPS redirect in the API** — TLS terminates at the proxy; a redirect inside the API
  would loop behind it. `Auth__RefreshCookie__Secure` controls the cookie's Secure flag
  (`true` behind TLS; `false` only for plain-HTTP LAN use).
- **Request size limits:** note endpoints cap payloads at 2 MB (`[RequestSizeLimit]`) —
  rejecting abuse before model binding instead of at Kestrel's ~28 MB default.
- **Upload validation:** profile images are checked by extension, size (≤2 MB), **and content
  signature** (magic bytes — JPEG/PNG/GIF/WebP) in `Service/ImageService.cs`; stored under a
  fresh GUID filename, never the client's (path-traversal defense).
- **Non-enumeration stance:** login, lockout, forgot-password, reset-password, and the
  profile-image endpoint all return the same generic response for "doesn't exist" and "no
  permission", so none of them can be used to probe which emails/ids are registered.
- **nginx (`web/nginx.conf` and `deploy/nginx.conf`):** security headers (nosniff,
  frame-ancestors DENY, referrer policy, HSTS — inert on plain HTTP, effective under TLS) and
  a same-origin **CSP** (inline script/style allowances only for the pre-paint theme script
  and React inline note colors).

## SignalR realtime

The realtime layer keeps a user's open devices in sync. It is intentionally a thin
**invalidation** channel, not a data channel: the server says *what changed*, and each client
reloads it through the REST API. This avoids the hub contract mirroring the DTOs and keeps
REST the single source of data.

- **Hub:** `keepITCore/SignalR/RealTimeHub.cs`, mapped at **`/api/realtime`** (under `/api`
  so the dev proxy and nginx WebSocket-upgrade rules route it with no extra config).
- **Contract:** one strongly-typed client method, `Changed(IReadOnlyList<string> resources)`,
  where each resource is `"notes"`, `"lists"`, `"notification"`, or `"settings"`
  (`RealtimeResources`). Clients only *receive*; mutations stay on REST, so the hub has **no
  callable server methods**.
- **Push path:** controllers depend on `IRealtimeNotifier` (a thin wrapper over
  `IHubContext<RealTimeHub, IRealTimeHub>`), and after each successful `SaveChanges` call
  `NotifyAsync(userId, …)` with the resources that mutation affected (e.g. a note create
  touches `notes` **and** `lists`, since list counts change). The reminder dispatcher pushes
  too (`notification` + `notes`).
- **Targeting:** `Clients.User(userId)` reaches *every* connection that user has open. A
  custom `IUserIdProvider` (`SubUserIdProvider`) maps a connection to the JWT **`sub`** claim
  (our tokens don't emit `NameIdentifier`, which SignalR's default provider expects). The
  originating device also receives its own signal and harmlessly re-validates (TanStack
  dedupes in-flight loads).
- **Sharing-aware fan-out:** a shared note's content change must reach the owner's devices
  **and** every collaborator's. This is done by fanning out over the recipient set, not
  SignalR groups: the controller asks `NoteAccessService.RecipientIdsAsync(noteId)` (owner +
  all grantees) and calls `NotifyAsync` per user. **Per-user** changes (pin/archive/trash,
  list membership, reminders, settings) notify only the acting caller, since no one else's
  view moved. A `notification` signal targets a single user. A group-per-note model remains a
  future optimization if the recipient loop ever gets expensive.
- **Clients:** web — `web/src/realtime/RealtimeSync.tsx` holds one authenticated connection
  while signed in, maps each resource to its TanStack Query key and invalidates on `Changed`,
  refreshes the token in `accessTokenFactory`, and re-syncs everything on reconnect
  (`withAutomaticReconnect` + `onreconnected`). Android — `data/RealtimeClient.kt` (official
  SignalR Java client) forwards `Changed` to the sync engine / notifications watcher; the Java
  client has no automatic reconnect, so it retries on a delay and re-syncs on every reconnect.
- **Scale-out caveat:** `Clients.User` is in-process. A single API instance (the intended
  deploy) reaches all of a user's devices; running multiple instances behind a load balancer
  would need a Redis backplane (`AddSignalR().AddStackExchangeRedis(...)`) — and the reminder
  dispatcher would need cross-instance locking. Neither exists; **single-instance is an
  explicit assumption**, fine for the self-hosted target.

## Sharing / collaboration

A note can be shared with other users so they see it in their own grid and (optionally) edit
it. This is the one place the app deliberately relaxes strict per-user data isolation — and
because it does, the access rules below are mandatory, not optional.

**Status: implemented** on web, API, and Android (the phone's `ShareSheet` mirrors the web's
`ShareDialog`; share management is online-only on both — nothing is queued offline).

**Model.** A `NoteShare` row grants one user (`granteeId`) access to one note at a `role`:
- **Viewer** — read-only. Sees the note and its live updates; cannot mutate its content.
- **Editor** — read + write. Can edit title/body/checklist/color like the owner, but
  **cannot** delete the note, re-share it, or change other people's roles.

The **owner** is implicit (no `NoteShare` row) and is the only one who can share/un-share,
change roles, or hard-delete. Ownership never transfers. A `NoteShare` exists only once the
recipient has **accepted** an invite — a pending invite is not yet access.

**Access resolution (used by every note query/command).** A caller may act on a note iff
`note.ownerId == caller` **OR** a `NoteShare(noteId, granteeId == caller)` exists — and for
content writes, that share's role is `Editor`. This lives in exactly one place —
**`NoteAccessService`** (`Notes/NoteAccessService.cs`), which returns a `NoteAccess(IsOwner,
Role)` (with `CanEdit = IsOwner || Role == Editor`) and the realtime recipient set. Every note
endpoint resolves access through it; no endpoint hand-rolls an `ownerId == me` check. No
access and "doesn't exist" are both 404; a viewer attempting a content write is 403.

**Per-user view overlay (`NoteUserState`).** Pin/archive/trash are **per user**, not columns
on the note: a `NoteUserState(noteId, userId, isPinned, isArchived, isTrashed)` row holds one
user's private view. **The row's existence also means "this note is in my grid"** — the owner
gets one on create, a grantee on accept — so the grid query is driven off this table (owned
and shared notes fall out of the same query) rather than a `UNION`. A collaborator pinning or
trashing a shared note touches only their own row.

**What is and isn't shared.** Shared: the note's **content** — title, body, checklist items,
color. Not shared (each per-user): pin/archive/trash, list memberships (`NoteList.userId` —
a collaborator files a shared note into their *own* lists), and **reminders** (`NoteReminder`
is keyed per user; a viewer can set their own reminder on a shared note).

**Endpoints.** Sharing is an **invite → accept** flow, not a silent grant (a note shouldn't
just appear in a stranger's grid). Owner-only except where noted:
- `POST   /api/notes/{id}/shares` — invite a user (by email) at a role. Creates a pending
  `ShareInviteNotification` for the recipient and pushes a realtime `notification` signal;
  **no `NoteShare` yet**. Rejects self-shares, duplicates, and non-users (`400`).
- `GET    /api/notes/{id}/shares` — list collaborators and roles (owner or any collaborator).
- `PATCH  /api/notes/{id}/shares/{granteeId}` — change a collaborator's role.
- `DELETE /api/notes/{id}/shares/{granteeId}` — revoke a share (also drops the grantee's
  `NoteUserState` and their private list memberships, so it leaves their grid at once). The
  grantee may call this on **their own** share to *leave* a note.
- **Accept/decline** happens on the notifications resource: `POST
  /api/notifications/{id}/respond` with `{ accept }`. Accept creates the `NoteShare` (at the
  invited role) **and** the grantee's `NoteUserState`; either answer consumes the invite.

**Invites to non-users.** Sharing by email requires an **existing user** (`400` otherwise).
Recording a pending invite keyed by email and resolving it on signup is a planned refinement.

**Edge cases honored.**
- **Concurrent edits:** optimistic updates + SignalR keep editors roughly in sync;
  last-write-wins on `updatedAt`. Field-level merge/CRDT is out of scope.
- **Revocation is immediate:** the next API call 403s/404s and the realtime push tells the
  revoked user's devices to resync (the note vanishes from their grid).
- **Deleting a shared note:** owner-only; cascades shares, per-user state, list rows, and
  reminders, and notifies the whole recipient set so it vanishes everywhere at once. (The
  recipient set is captured *before* the delete.)

## Notifications

A lightweight per-user inbox (`/api/notifications`). Three kinds, modelled
**table-per-hierarchy** (one `Notifications` table, a `NotificationType` discriminator,
subtype fields as nullable columns) so the generated TS client narrows on a
`"System" | "ShareInvite" | "Reminder"` union:

- **System** — a plain text + severity message. Dismiss-only.
- **ShareInvite** — the actionable half of **Sharing / collaboration**: the owner's `POST
  …/shares` raises one for the recipient. Carries denormalized snapshots (sharer email, note
  title, offered role) so it renders without joins and survives a later rename.
- **Reminder** — raised by the reminder dispatcher when a `NoteReminder` fires. Carries the
  note id plus a snapshot title (the note may be renamed or gone by the time it's read).
  Dismiss-only.

**Endpoints** (all owner-scoped to the caller):
- `GET    /api/notifications` — the caller's notifications, newest first.
- `POST   /api/notifications/{id}/respond` — answer a share invite (`{ accept }`).
- `DELETE /api/notifications/{id}` — dismiss.

Every mutation pushes a realtime `notification` signal to the affected user, so the top-bar
bell updates live. Web feature: `web/src/features/notifications/`. On Android, a
`ServerNotificationsWatcher` surfaces inbox entries as **native** notifications.

## Reminders

A user can attach one reminder to any note they can see — one-time or recurring (daily /
weekly / monthly / yearly). Reminders are **per-user, private state** like pin/archive/trash:
on a shared note each collaborator (any role — read access suffices) sets their own without
anyone else seeing it.

**Model.** `NoteReminder` — composite key `(noteId, userId)`, so at most one reminder per
user per note; row existence means "a reminder is set". Fields: `RemindAtUtc` (for recurring
reminders, always the *next* occurrence), `Recurrence`, `FiredAtUtc` (set when a one-time
reminder fires; null = pending; rescheduling resets it).

**Endpoints** (on the notes resource; read access suffices, per-user realtime only):
- `PUT    /api/notes/{id}/reminder` — set/replace the caller's reminder (`{ remindAtUtc, recurrence }`).
- `DELETE /api/notes/{id}/reminder` — clear it (idempotent).
- `GET    /api/notes?reminders=true` — the caller's notes with a reminder set, soonest first.
  Spans active **and** archived (like Keep) but never trash.

**Server-side firing.** `ReminderDispatcherService` (hosted service) ticks every 30 s: it
scans for pending due reminders (skipping notes the user has trashed — a still-pending
reminder fires after restore), raises a `ReminderNotification`, and pushes realtime
(`notification` for the bell + `notes` for the chip). One-time reminders are marked fired;
recurring ones advance to the next *future* occurrence — a long outage produces **one**
catch-up notification, not one per missed slot. Each reminder saves individually so a poison
row can't roll back the batch. Known accepted limitations (documented in the service):
recurrence arithmetic is UTC (wall-clock drift across DST; `AddMonths` end-of-month clamping
compounds), and firing is single-instance (no cross-instance locking).

**Android-side firing.** The server push only helps while a socket is open, so the phone
mirrors pending reminders from its offline cache into a SharedPreferences snapshot and arms
**`AlarmManager`** alarms (`notifications/ReminderScheduler.kt`): exact-and-allow-while-idle
when the user grants the *Alarms & reminders* special access (surfaced in the app's Settings
screen), else inexact-but-Doze-safe. Reminders thus fire as native notifications with the app
closed, the screen locked, or no internet. Duplicate suppression is two-layered (a local
posted-keys set, plus a shared `note-<id>` notification tag that folds the server's
`ReminderNotification` into the already-shown entry). Snoozes are purely local and never touch
the server row.

## Frontend (`web/`)

- **Build/dev:** Vite. In dev, Vite's proxy forwards `/api` (HTTP + WebSocket) to the backend
  — no CORS. In prod, the SPA is static files served by nginx, which reverse-proxies `/api`.
- **Server state:** TanStack Query owns everything fetched from the API — caching, background
  refetch, optimistic mutations. Never duplicated into a global store (no Redux/Zustand;
  query hooks co-located per feature in `features/<name>/queries.ts`).
- **Client/UI state:** plain React state/context (`AuthProvider`, `SettingsProvider`).
- **HTTP:** the generated typed client (`api/client.ts` on openapi-fetch), with a silent
  token-refresh-and-retry on 401 and error extraction in `lib/apiError.ts`.
- **Realtime:** `realtime/RealtimeSync.tsx` — see **SignalR realtime**.
- **Search** is client-side: the top bar's query filters the already-cached grid by title,
  body, and checklist text. Instant, and no server round-trips while typing.
- **Notes:** masonry grid via CSS columns (`NotesGrid`), a collapsed "take a note" composer
  that expands inline (`NoteComposer`), a modal editor (`NoteEditorModal`) with a **Markdown**
  body (custom renderer + formatting toolbar), checklist editing, color picker, share dialog,
  and reminder chip/menu.
- **Routing:** React Router v7 — `AuthPage`, `HomePage`, `SettingsPage`, `ResetPasswordPage`
  (the target of emailed reset links).
- **Styling:** Tailwind v4. The design system is **fully token-based** in `web/src/index.css`
  — semantic chrome tokens plus a per-note palette, themed for dark / dim / light with 8
  independent accent colors. Theme + accent are persisted server-side (`UserSettings`) and
  written to `<html>` as `data-theme` / `data-accent` by `SettingsProvider`, with a pre-paint
  script in `index.html` to avoid a flash. See **Look & feel**.
- **Settings page** also hosts account management (display name/avatar upload, change
  password), the operator's test-email button, and shows the server version from `/api/meta`.

## Android client (`app/`)

**Status: implemented.** A native Android app — Kotlin, Jetpack Compose (Material 3),
package `org.spaceelephant.keepitapp`, minSdk 34. Not a WebView wrapper: the point is a real
native experience, native reminder notifications, and a real home-screen widget. It is a
first-class consumer of the same REST + SignalR contract — the backend never special-cases it.

**Stack & wiring.** Retrofit + OkHttp + kotlinx.serialization for HTTP; the official SignalR
Java client for realtime; Glance for the widget. Dependency wiring is a **hand-rolled
`AppContainer`** in `KeepItApplication` (deliberate: a handful of app-scoped singletons
doesn't justify Hilt). Repositories are app-scoped so their `StateFlow`s survive
configuration changes; screens reach them via `context.appContainer`.

**Server address.** Unlike the web app (same-origin by construction), the phone asks for the
server URL on the sign-in screen and remembers it — one app, any self-hosted instance.

**Auth.** Same JWT model, adapted to native: the access token lives in memory only; the
refresh token is still the server's httpOnly cookie, held by a **persistent OkHttp cookie
jar** (`PersistentCookieJar`) in app-private SharedPreferences — the app plays the browser's
role of holding the cookie, and the backend's refresh handling is identical for all clients.
Refresh is single-flight (the cookie rotates per call) with a 401-retry interceptor. The
session distinguishes **rejected** (server said no → sign out) from **unreachable** (network
problem → stay signed in on the cached user so the offline cache is usable); only an actual
rejection may destroy the session.

**Offline-first sync** (`data/offline/`):
- **`LocalStore`** — the offline cache and outbox as **two JSON files** under
  `filesDir/offline/`, written atomically (temp file + rename). Deliberately **not Room**:
  the whole dataset already lives in memory as `StateFlow<List<NoteDto>>` and is
  personal-note-scale, so indexed queries buy nothing; the five-method surface can be swapped
  for a database later without touching callers.
- **`Outbox` / `PendingOp`** — every mutation (create / update / set-state / set-lists /
  set-reminder / clear-reminder / delete) applies to the local cache instantly and enqueues a
  durable op. Creates use a temp id that is remapped across the queue once the server assigns
  the real one.
- **`SyncEngine`** — drains the outbox against the REST API, then refetches everything (all
  three views + lists, in parallel), overlaying any still-queued local edits on the server
  truth. Kicked on sign-in, connectivity return, every enqueue, SignalR pushes, and
  pull-to-refresh; runs are single-flight. Failure policy per op: network/5xx stops the run
  (retry later, queue intact); 401 defers to the session (re-login resumes replay); any other
  4xx is permanent — the op is dropped **with a user-facing message** (e.g. the note was
  deleted on another device).
- Sign-out best-effort flushes the queue while the session is still valid, then wipes the
  local store, alarms, and posted notifications.

**Realtime, reminders, notifications.** `RealtimeClient` (see **SignalR realtime**) kicks the
sync engine on `notes`/`lists` and the `ServerNotificationsWatcher` on `notification`.
Reminders fire locally via `AlarmManager` (see **Reminders**); a `BootReceiver` re-arms them
after reboot.

**Widget** (`widget/KeepItWidget.kt`, Jetpack Glance) — the headline reason for going native:
the latest notes at a glance, a "+" that deep-links into the composer, and a header refresh
that runs a one-shot background sync. It renders purely from the local cache, so it needs no
network or auth of its own and shows last-known notes even signed out; when the cache
changes, every widget re-renders.

**Screens** (`ui/`): login/register (with server URL + forgot-password), notes grid
(staggered, with sync-status strip and pending-changes count), editor (markdown rendering via
a small custom parser, checklist editing, color, share sheet, reminder dialog),
notifications inbox, settings (theme/accent, exact-alarm access, about/version).

### UI & design parity (native, shared design language)

The Android UI reads as **the same product on a phone** — same tokens, card style, accent
system, Keep-like interaction model — while behaving natively. The approach is **native
Compose with a shared *design language*, not shared code and not pixel-cloning**:

- **The tokens are the contract.** `web/src/index.css` is the canonical design system; the
  Android theme (`ui/theme/`) transcribes the same values into Compose color objects switched
  per theme (dark / dim / light) and accent — never re-picked by eye, and no raw hex scattered
  through composables on either client.
- **Per-note palette:** a Compose map keyed by the **same** color keys the `Note.color` DTO
  stores (`"rose"`, `"amber"`, …), so the palette stays in lockstep across clients.
- **Masonry grid:** Compose `LazyVerticalStaggeredGrid` — a near-1:1 fit for the card grid.
- **Don't chase system-chrome parity:** status bar, back behavior, ripples, and insets follow
  Android conventions. Matching palette/typography/cards/accents is what reads as "same app".
- **Explicitly rejected:** WebView/TWA/Capacitor wrappers (non-native feel, and the widget
  needs native code regardless) and pixel-exact cloning (fights Material conventions).

## Look & feel / design

The UI reads as **clearly Google Keep** — same mental model and layout — but **darker and
more modern**, not a pixel clone.

- **Keep-like layout.** Masonry grid of note cards; a collapsed "Take a note…" composer that
  expands inline; hover/touch actions on cards (pin, color, lists, archive, share, remind);
  left sidebar for navigation (Notes / Reminders / Archive / Trash + the user's lists for
  one-click filtering); top search bar.
- **Dark-first theme.** Dark is the baseline; **dim** and **light** plus **8 independent
  accent colors** are token overrides (`data-theme` / `data-accent`), a swap not a rewrite.
  Note background colors are re-tuned per theme (muted on dark, not Keep's bright pastels).
- **Modern, restrained styling.** Generous spacing, soft rounded corners, subtle elevation,
  smooth micro-interactions, good empty/loading states.
- **Responsive.** Column count adapts from one (phone) up; the sidebar collapses to an
  off-canvas drawer; touch-revealed controls on small screens.
- **Accessibility.** WCAG AA contrast on the dark theme, keyboard navigation, and
  `prefers-reduced-motion` respected.

## Note functions (product definition)

A note is one of several **types**, and any note can carry a background color:

1. **Text note** — free-form **Markdown** text in `body` (rendered on card and in the editor;
   formatting toolbar on web). The default type.
2. **Checklist note** — an ordered list of checkbox items; reorder, check off, add, remove.
3. **Image note** — *planned, not implemented*: primary content is one or more images. The
   media pipeline it needs (below) is the main outstanding backend feature.
4. **Background** — every note can set a background **color** from the palette. Background
   *images* arrive with media handling.

Plus, orthogonal to type: pin / archive / trash (per user), list membership (per user),
sharing (owner-granted), and a reminder (per user).

## Data model (implemented)

Entities in `keepITCore/Data/` (Guid keys throughout; `ApplicationUser.Id` is the owner id
everything else is scoped to):

- `ApplicationUser` — Identity user (`IdentityUser<Guid>`) + `DisplayName`,
  `ProfileImageFileName`, and the refresh-token collection.
- `RefreshToken` — hashed token, expiry, revocation timestamp, replaced-by chain. See
  **Auth flow**.
- `Note` — id, ownerId, **type** (`Text` | `Checklist`), title, body (Markdown), color
  (palette key, nullable), createdAt/updatedAt. **Pin/archive/trash are not on the note** —
  they're per-user. Navigations to checklist items, note-lists, user states, shares, reminders.
- `ChecklistItem` — id, noteId, text, isChecked, order. Replaced wholesale on note update but
  reconciled by id server-side (stable ids, no delete-and-reinsert churn).
- `KeepList` (the `List` resource) — id, ownerId, name, color. Always private to its owner.
- `NoteList` — the per-user join (noteId, listId, **userId**): a collaborator files a shared
  note into their own lists without the owner seeing it.
- `NoteShare` — noteId, granteeId, **role** (`Viewer` | `Editor`), created-by/at; one row per
  (note, grantee); exists only after invite acceptance. See **Sharing / collaboration**.
- `NoteUserState` — composite key (noteId, userId): isPinned, isArchived, isTrashed. One
  user's private view; row existence = "in my grid". See **Sharing / collaboration**.
- `NoteReminder` — composite key (noteId, userId): remindAtUtc, recurrence, firedAtUtc. See
  **Reminders**.
- `UserNotification` (abstract, TPH on `NotificationType`) → `SystemNotification`,
  `ShareInviteNotification` (note id + snapshot title, sharer id + snapshot email, offered
  role), `ReminderNotification` (note id + snapshot title). See **Notifications**.
- `UserSettings` — one row per user (lazy-created): theme (`light|dim|dark|system`), accent
  key. Values validated against server-side allow-lists that mirror the frontend sets.

**Trash is soft-delete and per-user** (`NoteUserState.IsTrashed`), mirroring Keep; only the
owner can `DELETE` (hard-purge) a note, which cascades and removes it for everyone.

## Lists

Lists are the app's one grouping mechanism (they replace the generic "labels" idea) —
user-curated named collections with their own sidebar section.

**Behavior.**
- Any note can belong to zero, one, or many of the caller's lists; membership is just
  `NoteList` join rows.
- The grid can be **filtered** by list; selecting several filters to notes in **any** of them
  (union).
- Lists are **per user and private** (`NoteList.userId`). On a shared note, each collaborator
  files it into their *own* lists; the owner's lists don't travel with the share.
- Renaming/deleting a list never deletes notes — deleting drops its join rows only.

**Endpoints** (all caller-scoped):
- `GET    /api/lists` — the caller's lists (with note counts for the sidebar).
- `POST   /api/lists` — create (name, optional color).
- `PATCH  /api/lists/{id}` — rename / recolor.
- `DELETE /api/lists/{id}` — delete (notes survive, unfiled).
- `PUT    /api/notes/{id}/lists` — replace the set of the caller's lists a note is in.
- Filtering: `GET /api/notes?listId=…` (repeatable for a union).

**Frontend.** TanStack Query keys include the active filter, so switching lists is a cache
key change, not a refetch hack. The selected-filter UI state itself is client state.

## Profile images & media

**Implemented today: profile images only.** Avatar upload/serving lives on the settings
resource, with `Service/ImageService.cs` doing the storage work:

- `POST /api/settings/uploadProfileImage` — multipart upload, validated by extension, size
  (≤2 MB), and **magic bytes** (see **Security & abuse protection**). Stored under
  `{DataRoot}/users/{userId}/profile_image/{guid}.{ext}`; the previous file is deleted so
  re-uploads don't accumulate orphans. The filename lands on `ApplicationUser`.
- `GET /api/settings/getProfileImage/{userId}` — streams the bytes. A caller may fetch their
  own avatar or that of a user they're **connected to through sharing** (note owner ↔
  collaborator, fellow collaborators, or a pending invite between them) — what the share UI
  needs, without making avatars public to any signed-in user. "No image" and "no permission"
  are the same 404, so ids can't be probed.

**Planned: note media (image notes, background images).** The rules for when it lands:
- **Never store image bytes in the database** — DB holds metadata only; bytes live on disk
  under the data folder (behind an `IMediaStorage`-style abstraction so it can later swap to
  S3/MinIO without touching callers).
- **Storage keys, not user filenames** (the profile-image pipeline already follows this).
- **Serve through an access-checked API endpoint** — a collaborator reaches a shared note's
  media *through the note* (the `NoteAccessService` rule), not by owning the media. Generate
  thumbnails for the grid.
- **Lifecycle:** deleting a note purges its media; a periodic orphan sweep as a safety net.

## Versioning & the meta endpoint

`GET /api/meta` (anonymous — the sign-in screens want it before any session exists) returns
the server version. The version is the release tag, baked into the assembly at Docker build
time (`/p:Version` + commit sha, clipped to 7 chars); local builds honestly report
`0.0.0-dev`. The web settings page and the Android about screen both display it. The Android
app's own `versionName`/`versionCode` are derived from the same tag by CI.

## Deployment

**The target is Docker on your own hardware.** Local dev runs bare (`dotnet run` +
`npm run dev`); everything else is containers. There are **two supported shapes**:

### Single container (the headline "run your own" path)

`deploy/Dockerfile` builds one image (`richy1989/keepit` on Docker Hub) bundling:
1. the built React SPA, served by **nginx** (the public face on `:80`),
2. the .NET API on loopback `:8080`, reverse-proxied at `/api`,
3. an entrypoint that runs both and tears the container down if either exits.

React is still *not hosted by ASP.NET* — nginx and the API are separate processes talking
over HTTP, just co-located. With no Postgres configured the API uses its SQLite fallback, so
`docker run -v keepit-data:/data -e Jwt__Key=…` is a complete zero-setup deployment; **all**
writable state lives under `/data`. Setting `POSTGRES_HOST`/`ConnectionStrings__Postgres`
switches it to an external Postgres. An **Unraid Community Apps template** ships at
`deploy/keepit.unraid.xml`.

### Docker Compose (three containers)

`docker-compose.yml`: **`db`** (Postgres 17 + named volume), **`api`** (built from
`keepIT/keepITCore/Dockerfile`, data on a named volume at `/data`), **`web`** (nginx serving
the SPA and proxying `/api` — the single entrypoint on `:8080`). One origin → no CORS in the
stack and a same-origin refresh cookie. The API is not published to the host; only nginx is.
Compose reads five values from `.env` (`JWT_KEY`, `POSTGRES_PASSWORD`,
`REFRESH_COOKIE_SECURE`, `FORWARDED_PROXY_HOPS`, `ALLOW_REGISTRATION`).

For real TLS, terminate HTTPS at a proxy in front (e.g. Traefik), keep
`Auth__RefreshCookie__Secure=true`, and bump `App__ForwardedProxyHops` to match the extra hop
(see **Security & abuse protection**). A `redis` service is sketched in the compose file for
a future SignalR backplane but not enabled — single API instance is the deployed model.

### Releases (CI)

`.github/workflows/release.yml`: pushing a git tag `vX.Y.Z` builds and pushes the Docker
image (tagged `X.Y.Z` + `latest`, with the version/sha baked in for `/api/meta`), builds the
**signed Android APK** (version name/code derived from the tag; keystore from repo secrets,
mirrored locally by a gitignored `app/keystore.properties`), and publishes a GitHub Release
with the APK attached. Sideloading the APK is the current distribution channel; the Play
Store is not (yet) used.

## Dev conveniences

- **Scalar API UI** at `/scalar/v1` (Development only).
- **Seed script** — `scripts/seed-dev-data.sh` / `.ps1` creates `test@test.com` /
  `Test1234#1234` with lists and a variety of notes against a locally running API.
- **`keepITCore.http`** — request collection for manual endpoint poking.
- **No test projects yet** on the .NET/web side; the Android module has a small JVM unit-test
  suite around the offline ops (`OfflineOpsTest`).

## Status & roadmap

All of the original build order is long since **implemented** — contract-first (OpenAPI +
generated TS client), then CRUD + optimistic UI, SignalR invalidation, JWT auth — and since
then: **sharing/collaboration** (invite→accept, roles, per-user overlay), the
**notifications inbox**, **reminders** (server dispatcher + native Android alarms),
**password reset/change + SMTP email**, **security hardening** (rate limiting, lockout,
refresh-token rotation with reuse detection, registration gating), the **native Android app**
(offline-first, widget, share sheet), the **single-container image + Unraid template**, and
the **tag-driven release pipeline**.

**Remaining roadmap** (see README "What's next"):
- 🖼️ **Image notes & note media** — the media pipeline above; the biggest missing feature.
- ✉️ **Invite non-users** — pending share invites keyed by email, resolved on signup.
- 🤖 **Generated Kotlin API client** — replace the hand-mirrored `Dtos.kt` with a client
  generated from the same OpenAPI document.
- 🔀 **Scale-out** (only if ever needed): Redis backplane for SignalR + locking for the
  reminder dispatcher.

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
  as an APK (attached to GitHub Releases) and talks to the same HTTP + SignalR API, or runs
  **standalone** with no server at all (see **Android client → Standalone mode**).

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
  operator owns the logs, so "reset link lands in the log" is a legitimate no-SMTP mode. SMTP
  also needs `App__PublicBaseUrl`, the only source for links in real emails (see **Auth flow**),
  and its connection is always encrypted (see **Security**).

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
- **SQLite uses `EnsureCreated()` + a reconciler.** `EnsureCreated()` builds the whole schema
  from the current model for a file that doesn't exist yet, and does *nothing at all* to one
  that does — so before the reconciler, an instance created under an older model simply never
  gained the new tables and columns, and the first query touching one died with
  `SQLite Error 1: 'no such table: …'` on a database whose notes were all still there.
  `Infrastructure/SqliteSchemaReconciler.cs` closes that gap so an existing file keeps working
  across upgrades. It asks EF for the create script *for the current model in SQLite's own
  dialect*, and then, in order:
  1. runs the `CREATE TABLE` statements for tables the file is missing,
  2. appends missing columns with `ALTER TABLE … ADD COLUMN` — the model's own default where it
     declares one, otherwise the store type's zero value, because SQLite refuses to add a
     `NOT NULL` column with nothing to give the rows already in the table,
  3. runs the `CREATE INDEX` statements for indexes the file is missing (last, so an index can
     cover a column step 2 just added).

  Existing tables and all rows are left untouched, and a run on a current file is a no-op. The
  handful of shapes SQLite can't append in place (a computed column; a `NOT NULL` column whose
  type has no zero value) are logged as warnings rather than crashing the app.

  Migrations can't be retrofitted here instead: they're Postgres-authoritative (Npgsql column
  types), and an `EnsureCreated` database has no `__EFMigrationsHistory`, so `Migrate()` would
  try to replay every migration over populated tables.

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
  frontend's `/reset-password` page. **An emailed link is built only from `App__PublicBaseUrl`**:
  with SMTP configured and no public URL set, no reset email is sent at all (an error is logged,
  and a warning at startup), and the response is still 204. So the gap isn't silent for an
  operator upgrading or one who missed the field, `GET /api/settings/email-status` reports it
  and the web Settings page shows it (see **Frontend**). Only in log-only mode may the link
  fall back to the request's `Origin` (dev: the Vite origin) or its own scheme and host, since
  the operator is the one reading it. See **Security & abuse protection** for why.
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
  the web client passes the access token via the query string (`?access_token=…`); JWT bearer's
  `OnMessageReceived` reads it, scoped to the `/api/realtime` path. The Android SignalR client
  (OkHttp) can set headers, so it sends an ordinary `Authorization: Bearer` header instead.
  A token in a URL lands in access logs, so nginx logs it redacted (see **Security**).

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
  would loop behind it. The refresh cookie is **Secure on every HTTPS request**
  (`Request.IsHttps`, which honours the TLS proxy's `X-Forwarded-Proto`; both nginx configs
  pass it through instead of overwriting it with their own `http`). `Auth__RefreshCookie__Secure`
  only decides whether plain HTTP gets the flag too: `true` refuses plain-HTTP sessions, `false`
  (the single container's default) allows them on a LAN. So an instance later put behind a TLS
  proxy protects its cookie without anyone changing the setting, which existing installs with
  an explicit `false` would not have done.
- **Request size limits:** note endpoints cap payloads at 2 MB (`[RequestSizeLimit]`) —
  rejecting abuse before model binding instead of at Kestrel's ~28 MB default.
- **Upload validation:** profile images are checked by extension, size (≤2 MB), **and content
  signature** (magic bytes — JPEG/PNG/GIF/WebP) in `Service/ImageService.cs`; stored under a
  fresh GUID filename, never the client's (path-traversal defense).
- **Image decoding is bounded.** Decoding costs memory by pixel count, not file size, and a
  1.2 MB PNG can declare 400 megapixels (1.2 GB decoded). So note images are checked against
  `App__Media__MaxImagePixels` from the header before anything is decoded, only an animation's
  first frame is decoded (each frame is a full canvas), and two uploads are processed at a time
  (see **Note media → Limits**).
- **Non-enumeration stance:** login, lockout, forgot-password, reset-password, and the
  profile-image endpoint all return the same generic response for "doesn't exist" and "no
  permission", so none of them can be used to probe which emails/ids are registered.
- **Outbound links never come from the request.** `Origin`, `Host` and forwarded-host headers
  are whatever the sender chooses, and forgot-password is anonymous: a reset link built from
  them would let anyone send a victim a genuine reset email pointing at their own site, and
  collect the token when it's clicked (password-reset poisoning). Links that reach a user's
  inbox are therefore built only from `App__PublicBaseUrl` (`Infrastructure/PublicBaseUrl.cs`),
  which is validated at startup (a malformed value stops the API, like a bad `Jwt__Key`). Any
  future email carrying a link, such as invites to non-users, must follow the same rule.
- **SMTP never falls back to plain text.** STARTTLS is required (MailKit `StartTls`), not
  opportunistic (`StartTlsWhenAvailable`): the offer travels unencrypted, so anyone on the
  path can strip it, and the opportunistic client then sends the SMTP password and every reset
  link in the clear. A server that doesn't offer STARTTLS gets nothing, and the error names the
  fixes (implicit TLS on 465, or the opt-in). `Email__AllowUnencrypted=true` restores the
  fallback for a trusted local relay; it logs a warning at startup and the Settings page shows
  one, via `GET /api/settings/email-status`.
- **nginx (`web/nginx.conf` and `deploy/nginx.conf`):** security headers (nosniff,
  frame-ancestors DENY, referrer policy, HSTS — inert on plain HTTP, effective under TLS) and
  a same-origin **CSP** (inline script/style allowances only for the pre-paint theme script
  and React inline note colors).
- **No credential from a URL is logged.** Two travel in URLs by necessity: the browser's hub
  `access_token` (see **SignalR auth**) and the `token` of a password-reset link
  (`/reset-password?email=…&token=…`). nginx's stock log formats write whole URLs, so both
  configs log with a `redacted` format that blanks those values in the request line and the
  referer; both images log to stdout, so `docker logs` shows it. An error-log line quotes the
  raw request and can't be redacted, so `/api/realtime` has its own location that doesn't write
  one (a failure still shows as, say, a 502 in the access log). And `Referrer-Policy:
  strict-origin` keeps the reset page's full URL out of the referer of everything it loads,
  even same-origin. CI sends both kinds of token through the built image and fails if either
  reaches its log. A new credential must never go in a URL; if one has to, it joins the
  redaction map and that CI step. An operator's own proxy in front logs URLs too, which the
  README points out.
- **The API never runs as root.** In both shapes it runs as uid 1654 and owns `/data`; only a
  start-up step hands `/data` over, without following symlinks, and nginx's master binds `:80`.
  In the single container the API also listens on loopback only. See **Deployment**.

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
  It acts on `notes`/`lists`/`notification` only — **`settings` is deliberately dropped**, since
  the app has no server-synced appearance to apply (see **Android client** → theming).
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
  When SMTP is configured without `App__PublicBaseUrl` (so reset emails are switched off), it
  says so from `GET /api/settings/email-status`: a banner on every section, a marker on the
  Email section, and the full explanation there, suggesting the address currently in use. Once
  configured, the Email section shows where reset links point instead. It also keeps
  `Email__AllowUnencrypted` visible while it's on, as a warning in the Email section.

## Android client (`app/`)

**Status: implemented.** A native Android app — Kotlin, Jetpack Compose (Material 3),
package `org.hyperstarit.keepitapp`, minSdk 34. Not a WebView wrapper: the point is a real
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

**Session bootstrap.** Restoring that session from the cookie runs on the app scope
(`AppContainer.bootstrap`), not in the composition that asks for it. The session is
process-scoped state, so tying its restore to a composition means an activity recreation — a
rotation, the system switching to dark mode, the app being backgrounded — cancels the restore
mid-call. A cancellation is not a failed call (`orNullUnlessCancelled`, `resultUnlessCancelled`):
reading one as failure signed a valid session out and dropped the user on the sign-in screen until
the next restore put it right. On a later open an established session is left alone and an
unresolved one is retried, so a bootstrap that ran with no connectivity still comes good.

**Offline-first sync** (`data/offline/`):
- **`LocalStore`** — the offline cache and outbox as **two JSON files** under
  `filesDir/offline/`, written atomically (temp file + rename). Deliberately **not Room**:
  the whole dataset already lives in memory as `StateFlow<List<NoteDto>>` and is
  personal-note-scale, so indexed queries buy nothing; the five-method surface can be swapped
  for a database later without touching callers.
- **`Outbox` / `PendingOp`** — every mutation (create / update / set-state / set-lists /
  set-reminder / clear-reminder / delete / attach-media / delete-media, and the list ops
  create-list / update-list / delete-list) applies to the local cache instantly and enqueues a
  durable op. Creates — of notes and of lists — use a temp id that is remapped across the queue
  once the server assigns the real one. Replay is FIFO, so no queued op may name a temp list id
  whose create sits behind it (the server would refuse the whole request, not just the list):
  `coalesce` only folds a membership into a note's create when every list it names was created
  first, and deleting a list created offline strips it from every queued membership.
- **`SyncEngine`** — drains the outbox against the REST API, then refetches everything (all
  three views + lists, in parallel), overlaying any still-queued local edits on the server
  truth. Kicked on sign-in, connectivity return, every enqueue, SignalR pushes, and
  pull-to-refresh; runs are single-flight. Failure policy per op: network/5xx stops the run
  (retry later, queue intact); 401 defers to the session (re-login resumes replay); any other
  4xx is permanent — the op is dropped **with a user-facing message** (e.g. the note was
  deleted on another device).
- Sign-out best-effort flushes the queue while the session is still valid, then wipes the
  local store (staged images included), alarms, and posted notifications.
- An upload the server refuses for good (too large, HEIC, the note gone) is saved to the
  gallery before its staged file is deleted — for a photo taken offline, or anything from
  standalone mode, the staged file is the only copy there is.

**Standalone mode** (`data/AppMode.kt`). The app also runs with **no server and no account** —
**Use without a server** on the sign-in screen. It is not a second storage path: it is the
offline-first design with the network taken away. Notes, lists, reminders and images live in
the same cache and outbox; `SyncEngine.sync()` and `kick()` are no-ops while the persisted
`AppMode` flag is set, so the outbox never drains. The check lives in the engine rather than at
the call sites because processes with no UI (widget refresh, `WidgetSyncWorker`) sync too, and
must see the mode before any session is restored.
- **Session.** `SessionState.Standalone` opens the main nav directly; realtime never starts. The
  store is marked as the standalone device's own (a non-GUID owner), so a sign-in can tell it
  apart from another account's cache. Entering standalone over an expired session's cache wipes
  that cache — after a confirmation when changes are still unsynced.
- **What's off.** Sharing, the notification inbox, change password, the server version,
  refresh and the sync strip are hidden. **Sign out** is replaced by **Erase notes** in Settings
  (confirmed — there is no server copy). Unlike a sign-out, which leaves the widget showing the
  last-known notes, an erase empties the widget's snapshot too.
- **Images.** A queued attachment *is* the image: the editor shows it plainly (no upload
  spinner), opens it in the viewer, saves it to the gallery, and removes it by withdrawing its
  op (`Outbox.remove`). Cards fall back to the first queued attachment as their hero — which
  also shows a photo attached offline in server mode before it uploads.
- **Reminders.** Alarms fire from the cache exactly as before, but with no server nothing marks
  a one-time reminder fired or advances a recurring one. `settleDueReminders` does that in the
  cache — only *after* `ReminderScheduler.syncFrom` has seen it, so an occurrence is always
  posted before it is advanced past; the same UTC arithmetic as the server (`advanceOccurrence`).
- **Connecting a server later** (Settings → Connect to a server, the sign-in form again). Once
  the credentials are accepted the queue is readied (`readiedForUpload`: one-time reminders
  already in the past are dropped and recurring ones moved to their next occurrence, or the
  server's dispatcher would fire them again), the mode flips, and the ordinary sync replays the
  whole queue into the account — merged with whatever it already holds. The store changes owner
  only on the sign-in itself, so a crash mid-switch can never restart standalone over a store
  that looks like an account's.
- **Limits.** Data is only as safe as the phone — no backup until a server is connected. Images
  are stored as picked, so ones the server would refuse (over 10 MB or 100 megapixels, HEIC)
  fail on that first upload; they land in the gallery rather than being lost (see above).

**Realtime, reminders, notifications.** `RealtimeClient` (see **SignalR realtime**) kicks the
sync engine on `notes`/`lists` and the `ServerNotificationsWatcher` on `notification`.
Reminders fire locally via `AlarmManager` (see **Reminders**); a `BootReceiver` re-arms them
after reboot.

**Widget** (`widget/KeepItWidget.kt`, Jetpack Glance) — the headline reason for going native:
the latest notes at a glance, a "+" that deep-links into the composer, and a header refresh
that runs a one-shot background sync. It renders purely from the local cache, so it needs no
network or auth of its own and shows last-known notes even signed out; when the cache
changes, every widget re-renders.

Both ways of refreshing it run with **no UI in the process**, which shapes them:

- `RefreshAction` (the header button) and `WidgetSyncWorker` (periodic, 30 min, scheduled by
  `KeepItWidgetReceiver` while at least one widget is placed) do the same three things:
  `loadFromDisk`, then sync, then `renderWidgetNow`. The disk load is there because `AppRoot`
  is what normally restores the cache and outbox, and it never ran; the explicit render is
  there because the repository's own re-render is debounced onto an app-scoped coroutine, and
  once the callback returns Android may kill the process before it fires. Rendering explicitly
  also means a *failed* sync still redraws from cache rather than looking like a dead button.
- `updatePeriodMillis="0"` in the descriptor, because none of the above is the system's job —
  the system update would only re-render the same cached snapshot, not fetch anything.
- `WidgetSyncWorker` is instantiated by WorkManager from a persisted class name, so it belongs
  to the reflectively-constructed set both `verifyReleaseKeepRules` and `ReleaseBuildSmokeTest`
  guard. See the testing section.

**Screens** (`ui/`): login/register (with server URL + forgot-password, or standalone), notes grid
(staggered, with sync-status strip and pending-changes count), editor (markdown rendering via
a small custom parser, checklist editing, color, share sheet, reminder dialog),
notifications inbox, settings (notification + exact-alarm permissions, change password,
about/version — deliberately no theme/accent, see below).

### UI & design parity (native, shared design language)

The Android UI reads as **the same product on a phone** — same tokens, card style, accent
system, Keep-like interaction model — while behaving natively. The approach is **native
Compose with a shared *design language*, not shared code and not pixel-cloning**:

- **The tokens are the contract.** `web/src/index.css` is the canonical design system; the
  Android theme (`ui/theme/`) transcribes the same values into Compose color objects — never
  re-picked by eye, and no raw hex scattered through composables on either client. `Color.kt`
  currently carries the web's **dim** theme (`html[data-theme=dim]`): a softer dark that suits
  phone OLED better than the pitch-black baseline.
- **Appearance is local and fixed on Android — a gap, not a bug.** The web persists a per-user
  **theme + accent** server-side (`UserSettingsController`, `/api/settings`) and restyles a
  user's other open devices live off the `settings` realtime signal. Android does none of it:
  `Theme.kt` builds one `darkColorScheme` over the dim tokens with a single fixed accent, the
  settings screen offers no picker, `Dtos.kt`/`KeepItApi.kt` carry no `UserSettingsDto`, and the
  `settings` push is ignored (`KeepItApplication.kt`). Syncing the value alone would change
  nothing on screen — **there is no theme to switch into yet**, which is why the client doesn't
  pretend to listen. Closing it is a themed-UI job before it is a sync job, in this order:
  (1) make the tokens runtime-swappable — `KeepItColors` is an `object` read directly at **166
  call sites across 12 files** (35 of them `Accent`), so this means a `CompositionLocal` and a
  mechanical sweep; (2) add the dark/light schemes as a token swap, as the web does;
  (3) add the DTO + route + repository, a picker in `SettingsScreen`, and handle `settings` in
  the realtime handler. Until then the dim scheme **is** the Android look, and a user's web
  appearance choice intentionally does not follow them to the phone.
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
2. **Checklist note** — an ordered list of checkbox items; reorder, check off, add, remove. Ticked
   items display at the bottom of the list and return to their original slot when unticked — see
   `ChecklistItem.order` for the contract that makes that work on every client.
3. **Background** — every note can set a background **color** from the palette. Background
   *images* are still not implemented.

Plus, orthogonal to type: **image attachments** (any note, ordered, append-only — see "Profile
images & media"), pin / archive / trash (per user), list membership (per user), sharing
(owner-granted), and a reminder (per user).

Note there is no separate "image note" type: attachments hang off text and checklist notes alike,
so a note whose content is just photos is an image note without the model needing to say so.

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
  reconciled by id server-side (stable ids, no delete-and-reinsert churn). **`order` is the row's
  *home* position, not its display position** — the server renumbers it from the incoming array
  index, and clients must only change it when a row is added, removed or dragged, **never when a
  box is ticked**. Each client then renders unchecked rows first and checked ones at the bottom
  (a stable partition), which is what makes a ticked row sink, an unticked row return to exactly
  the slot it came from, and a new row land above the checked block — identically on every device,
  because it's derived from persisted state rather than remembered client-side. Nothing enforces
  this contract, so a client that writes display order back into `order` breaks the other clients:
  the rule lives in `web/src/features/notes/checklist.ts` and `app/…/data/Checklist.kt`.
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

**Android.** List create / rename / delete are queued ops like every note mutation, so they
work offline (and in standalone mode) and replay later; a list created offline can be filed
into straight away under its temp id. Counts are computed locally from the cache.

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

**Implemented: note media (image attachments).** Images attach to **any** note — text or
checklist — as an ordered, append-only collection (`NoteMedia`, cascade-deleted with the note).
There is deliberately no `NoteType.Image`: an "image note" is simply a note whose content happens
to be images, which is how Keep behaves and what spares every client a type discriminator. The four
original rules all hold:
- **No image bytes in the database** — `NoteMedia` holds metadata and the storage key; bytes live
  under `{DataRoot}/users/{ownerId}/notes/{noteId}/` behind `IMediaStorage`
  (`Service/DiskMediaStorage.cs`), so S3/MinIO can replace it without touching a caller.
- **Storage keys, not user filenames** — `{mediaId}.{ext}` and `{mediaId}_thumb.{ext}`, generated
  server-side.
- **Access-checked serving** — `NoteMediaController` resolves every request through
  `NoteAccessService` on the *parent note*: any access reads bytes, Editor access attaches and
  removes, and a non-collaborator gets the same 404 as a nonexistent note. A 400×400 thumbnail is
  generated on upload for the grid.
- **Lifecycle** — hard-deleting a note purges its folder; `MediaOrphanSweepService` runs daily as
  the safety net for bytes written before a row that never landed.

Endpoints (all under the note): `POST /api/notes/{id}/media` (multipart, one file per request),
`GET /api/notes/{id}/media/{mediaId}?size=thumb|full`, `DELETE /api/notes/{id}/media/{mediaId}`.
`NoteDto.media` carries `id`, `width`/`height` (so a card reserves its box before the thumbnail
arrives, instead of reflowing the grid), `byteSize`, `order` and `createdAtUtc` — no URLs; clients
build the path and fetch the bytes as an authenticated request.

**Processing (ImageSharp).** Originals are re-encoded, not stored verbatim: long edge capped at
2560, EXIF orientation applied and then *all* metadata stripped. That last part is the point —
phone photos carry GPS, and a shared note would otherwise hand a collaborator the coordinates of
the photographer's home. The trade-off is that pixel-exact originals are not preserved. Animated
GIFs pass through untouched and thumbnail from their first frame. HEIC gets its own ISO-BMFF brand
check so it can be refused *by name*, since iPhone-on-Safari users hit it constantly.

ImageSharp is pinned to the **3.1** line on purpose: 4.x requires a Six Labors licence key at build
time, while 3.1 stays under the Split License covering open-source use.

**Limits:** 10 MB per image, 100 megapixels per image and 10 images per note, all configurable
under `App:Media`. There is no per-user quota — registration is gated, and `ByteSize` is stored so
a quota is later a `SUM` rather than a migration. Over-sized uploads are answered by a resource
filter that runs *before* model binding, because the framework's own guard surfaces as a generic
400 and clients map 413 specifically to "image too large".

Bytes don't bound what decoding costs: that follows the pixel count, and a solid-colour PNG of
1.2 MB can declare 20,000 × 20,000 pixels. So `NoteMediaProcessor` reads the dimensions from the
header (`Image.IdentifyAsync`, no decode) and refuses anything over `MaxImagePixels` with a 413
before a pixel is allocated. It decodes only the first frame (`DecoderOptions.MaxFrames = 1`),
since every frame of an animation decodes to a full canvas; nothing is lost, as GIFs are stored
as uploaded and everything else becomes a single JPEG frame. And at most two uploads are buffered
and decoded at once (a process-wide semaphore; the processor itself is scoped), so parallel
uploads queue instead of multiplying memory, and the ones waiting hold only their request body,
which ASP.NET Core keeps on disk.

**Still deferred:** background images, a distinct image note type, reordering attachments, and
images in the Android widget.

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
3. an entrypoint that runs both and tears the container down if either exits, and passes
   `docker stop`'s SIGTERM on so both shut down cleanly.

**Who runs as what:** the API runs as the base image's unprivileged `app` user (uid 1654). It
parses every request body and decodes uploaded images, so a flaw there shouldn't come with
root. The entrypoint starts as root only to hand `/data` to `app` (`find … ! -user app -exec
chown -h`): earlier versions ran the API as root, so existing volumes and Unraid folders are
root-owned, and this makes the upgrade need no manual step. `-h` matters: the API can write
under `/data`, and without it a symlink planted there would aim the next start's root-run
chown at a file outside the folder. Storage that can't change owners (a network share with
root squashing, say) only gets a warning, since such a folder may already be writable for
everyone; one that isn't makes the API fail on start with SQLite's "unable to open database
file", which the warning explains. It then starts the API through `setpriv`. nginx's master
stays root to bind `:80`, and its workers, which handle the requests, run as `www-data`.
Started with `--user`, the entrypoint refuses with an explanation, since nginx couldn't start.
The API listens on `127.0.0.1` only (`ASPNETCORE_URLS`, with the base image's
`ASPNETCORE_HTTP_PORTS` cleared), so other containers on the same Docker network can't bypass
nginx and hand it a forged `X-Forwarded-For`.

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
The API container runs as the image's unprivileged user (uid 1654), never root. Before it
starts, a one-shot **`data-owner`** service (the same image, run as root, with no network)
hands the data volume to that user with the same `chown -h` rule as the single container,
which is what upgrades a volume from the root-run versions; the image also creates `/data`
owned by that user, so a new volume starts out writable.
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
- **API tests** (`keepIT/keepITCore.Tests/`, xUnit, run in CI) host the real API in-process on a
  throwaway SQLite data root per host: the schema reconciler bringing an older database up to date
  without data loss, note media end to end (renditions, the lazily built preview, upload
  limits, an image bomb refused from its header, and only an animation's first frame decoded,
  witnessed by a GIF whose second frame can't be), and where password-reset links point (forged `Origin`/`Host` headers are ignored,
  and no email goes out without `App__PublicBaseUrl`), and that SMTP mail stays encrypted (a
  loopback `FakeSmtpServer` that never offers STARTTLS receives neither the SMTP password nor
  the message), and which requests get a Secure refresh cookie (every HTTPS one, direct or
  forwarded, whatever the setting). They run one host at a time because the
  data root is a process-wide static.
- **Deployment smoke test** (`deploy/smoke-test.sh`, run by CI against the built image): a ~3 MB
  photo upload through nginx — the layer every in-process test bypasses, and where the 1 MB
  default body limit once hid. The same CI job then checks that a hub token and a reset token
  sent in URLs reach the container log only redacted.
- **Dependency advisories** (`.github/workflows/dependencies.yml`, on every push and PR and
  weekly, since an advisory can appear for code that hasn't changed): fails on a high or critical
  advisory in the web app's runtime npm packages or in any NuGet package, direct or transitive.
  `dotnet list package --vulnerable` never fails by itself, so `.github/scripts/nuget-advisories.py`
  judges its report. Build-only npm tooling is left to Dependabot. The same workflow submits the
  libraries the Android app ships with (`releaseRuntimeClasspath`) to GitHub's dependency graph,
  the only way Dependabot alerts see them. Only those: the Android Gradle plugin's own tooling
  runs on build machines only, and its dozens of advisories would bury the app's. Dependabot (`.github/dependabot.yml`) opens grouped weekly version updates for
  all five ecosystems; it never proposes ImageSharp 4.x, the next .NET major, or a new major base
  image, which are deliberate upgrades.
- No web tests yet; the Android module is tested in three layers (see CLAUDE.md).

## Status & roadmap

All of the original build order is long since **implemented** — contract-first (OpenAPI +
generated TS client), then CRUD + optimistic UI, SignalR invalidation, JWT auth — and since
then: **sharing/collaboration** (invite→accept, roles, per-user overlay), the
**notifications inbox**, **reminders** (server dispatcher + native Android alarms),
**password reset/change + SMTP email**, **security hardening** (rate limiting, lockout,
refresh-token rotation with reuse detection, registration gating), the **native Android app**
(offline-first, widget, share sheet, and a **standalone mode** that needs no server), the
**single-container image + Unraid template**, and the **tag-driven release pipeline**.

**Remaining roadmap** (see README "What's next"):
- 🖼️ **Background images** — the remaining half of note media; attachments themselves are done.
- ✉️ **Invite non-users** — pending share invites keyed by email, resolved on signup.
- 🤖 **Generated Kotlin API client** — replace the hand-mirrored `Dtos.kt` with a client
  generated from the same OpenAPI document.
- 🔀 **Scale-out** (only if ever needed): Redis backplane for SignalR + locking for the
  reminder dispatcher.

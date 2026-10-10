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
- **Logging:** Serilog, written by `Infrastructure/ConsoleLogFormatter.cs`: one short, aligned,
  coloured line per event (`18:31:06 INF  POST   /api/notes   201  157 ms`), meant to be read as
  an overview in `docker logs` and in Unraid's log view (a web terminal, so ANSI colour shows).
  The formatter writes the colour itself, because Serilog's console themes apply only when the
  output is a terminal, which in a container it never is; `NO_COLOR` turns it off. A request line
  is the path only (GUIDs cut to 8 characters), never the query string. Everything that came
  from outside is written with its control characters as `\xNN`: the log goes to terminals, and
  an escape sequence in a request path would otherwise recolour or rewrite it. The host's own
  start-up lines are silenced in favour of one `keepIT <version> starting · <database> · data in
  <folder>`, then `ready`. Levels come from the `Serilog` config section.
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
- **Both paths are tested, because each hides the other's mistakes.** SQLite builds its schema
  from the model, so a migration that is wrong or missing passes every SQLite test, and
  Postgres enforces what SQLite ignores (column lengths, `timestamptz` taking only UTC times).
  The API tests therefore run twice in CI: on SQLite, and on Postgres 17 (`API on PostgreSQL`),
  where each test host gets an empty database and the API's own `Migrate()` builds it from the
  first migration up, as on a new Compose install. `DatabaseTests` fails a Postgres run that
  quietly got SQLite, and fails both when the model has changed without a migration.
  `scripts/test-postgres.sh` (and `.ps1`) runs the suite locally on a throwaway container.

### One common data folder

`App__DataRoot` (default `./App_Data`, resolved and created by
`Infrastructure/FolderManagement.cs`) is the one folder the backend writes into — trivial to
back up and to mount as a single Docker volume:

- `{DataRoot}/keepit.db` — the SQLite database (only when SQLite is in use).
- `{DataRoot}/keys/` — ASP.NET Data Protection keys (cookie/token protection).
- `{DataRoot}/users/{userId}/profile_image/` — uploaded profile images.
- `{DataRoot}/tmp/` — uploads being spooled to disk so they can be read back (an import archive
  has to be seekable). Each file is deleted as soon as its request finishes; anything left there
  is a crashed request and is safe to remove.

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
  the `sub` claim. Validation also checks that the account still exists (`OnTokenValidated`,
  one primary-key lookup): a token outlives nothing it names, and without the check a deleted
  account's other devices kept a working token for the rest of its lifetime — reading an empty
  account, failing every write on a foreign key. Refused instead, they refresh, are refused
  again, and sign out.
- **Refresh token** — long-lived (`Jwt__RefreshTokenDays`, default 14), opaque, set as an
  **httpOnly + Secure + SameSite=Strict** cookie so JS can't read it. Stored server-side
  **hashed** (`RefreshToken` entity: token hash, expiry, revocation, replaced-by chain) so a
  DB leak doesn't leak usable tokens and individual tokens can be revoked. A 401 triggers a
  silent refresh; only a **401 from `/refresh` itself** signs the client out — transient
  failures (429/5xx/network) are retried and never treated as a lost session, because the
  cookie is still valid. A 401 refreshes even when the client's clock still calls the token
  fresh — the server has refused it, and a changed signing key, a deleted account or a phone
  clock running behind all look like that. Android once trusted the clock here and never
  refreshed, signing users out on a key change; a client now skips the refresh only when it
  already holds a newer token than the one refused (another caller refreshed meanwhile).
- **Rotation + reuse detection.** Every `/refresh` revokes the presented token and issues a
  replacement. Presenting a rotated (not expired) token whose replacement is **already in use**
  is the signature of a stolen cookie being replayed — **all** of the user's active refresh
  tokens are revoked, forcing both the attacker and the real user to sign in again, and a
  warning is logged. Expired rows are cleaned up opportunistically; revoked-but-unexpired rows
  are kept because they *are* the replay detector.
- **Lost rotations are not theft.** A rotated token whose replacement was **never used**
  belongs to a client that never received the rotation response (a dropped connection, a
  process killed mid-refresh): it gets a fresh sibling, and the old token is **re-pointed at
  that sibling**. Judged by its first, never-used successor it would pass as lost on every
  replay and a copy would mint sessions until it expired; judged by the sibling, it is a copy
  again once the client uses that. A token revoked with **no** replacement (sign-out, password
  change or reset, or a replay ending every session) is refused on its own — a client retrying
  a queued request after signing out must not end the user's other sessions.
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
  credentials (no account/lock-state enumeration). Every 401 is a `LoginFailureDto`; for an
  account with **two-factor** on, the right password alone gets one with `twoFactorRequired`
  set, and the client sends the sign-in again with `twoFactorCode` (see **Two-factor
  authentication** below).
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
- `PUT  /me` — renames the current user (`UpdateProfileRequestDto`): trimmed, and null or blank
  removes the name, stored as null like an account registered without one, so the clients'
  fallback to the email applies. Answers with the updated `UserDto` and pushes `account` to the
  caller's own devices only: the name is shown to its owner alone (shares and invites identify
  people by email). No new token: the access token's `name` claim keeps the old name until its
  next refresh, which is harmless since nothing reads it.
- `POST /delete-account` — deletes the caller's account and everything it owns
  (`DeleteAccountRequestDto`). The password is asked for again (a wrong one is a 400
  ValidationProblem under `Password`, and nothing is touched), so a device left signed in or a
  stolen access token can't erase an account. `AccountDeletionService` does the work in one
  transaction: most of it is the cascade from the user row (owned notes with their checklists,
  media rows, shares and every collaborator's view of them; lists, settings, inbox, refresh
  tokens; the user's own state on notes shared with them). What doesn't cascade is done first:
  shares **to** the user (that FK is `Restrict` on purpose, so no other user delete can sever a
  share), and other people's inbox entries that point at the account by id — pending invites it
  sent, reminders that fired on its notes. Files go after the commit (a leftover file is
  harmless and the media sweep takes it; a note whose files went while its rows stayed is not):
  each owned note's media, then the whole `users/{id}` folder with the profile picture. Realtime
  then tells everyone whose view changed — collaborators who lose the notes, owners who lose a
  collaborator, people whose invite or reminder went — and pushes `account` to the user's other
  devices, whose next request finds no account and signs them out. Answers 204 and clears the
  refresh cookie. Web: Settings → Security; Android: Settings → Account, which on success wipes
  the device like sign-out does (cache, outbox, widget, reminders, profile picture).
- `GET /two-factor`, `POST /two-factor/setup | enable | disable | recovery-codes` — the caller's
  two-factor authentication; see below.

The credential endpoints (register, login, change-/forgot-/reset-password, delete-account, and
the two-factor ones that change something) carry the tight `auth` rate limit; `/refresh`, `/logout`, and `/me` (read or rename) deliberately sit under only the global
limit — every page reload refreshes, and throttling that signs real users out (see **Security &
abuse protection**).

**Two-factor authentication** (`Auth/TwoFactorService.cs`, `TwoFactorController`)
- **What it is:** a six-digit code from an authenticator app (TOTP, RFC 6238: 30-second steps,
  HMAC-SHA1), optional per user. It is Identity's own: `AuthenticatorTokenProvider` checks the
  code, the key and the recovery codes live in Identity's `AspNetUserTokens`, the switch is
  `TwoFactorEnabled`. So there is no schema of ours and no migration, and the SQLite reconciler
  has nothing to add: both tables have been there since the first schema. Text messages and email
  codes were left out on purpose (a paid provider; mail is optional for an operator).
- **Sign-in is stateless.** The client sends email + password; with the right password the 401
  says `twoFactorRequired`, and the client sends all three. Nothing is held between the
  attempts, so there is no half-signed-in token to steal or expire. Only the right password
  reveals that an account uses two-factor; a wrong code counts toward the lockout like a wrong
  password (that, with the rate limit, is what stops code guessing: five tries per 15 minutes
  against a million codes).
- **Setting it up:** `setup` takes the password (a device left signed in mustn't be able to tie
  the account to someone else's phone) and returns a new key three ways: the `otpauth://` URI, the
  key in groups of four, and the URI as a QR code — `TwoFactorSetupDto.qrCode`, rows of `'1'`/`'0'`
  modules with the light border, encoded on the server (Net.Codecrete.QrCodeGenerator, a port of
  Nayuki's generator with no dependencies) and *drawn* by each client, so neither needs a QR
  library and the code is sharp at any size. Always black on white, whatever the theme: scanners
  want dark on light. `enable` takes a code from the app, so a mistyped key can't lock anyone
  out, and returns the first ten recovery codes. `setup` refuses (409) while two-factor is on:
  a new key would silently end the authenticator in use.
- **Recovery codes** sign in once each in place of a code. Identity stores them readable; ours
  are stored as SHA-256 hashes (`TwoFactorService.HashRecoveryCode`), so a copy of the database is
  not a copy of every way past the second factor. Ten codes of ten characters from an alphabet
  without 0/O/1/l/i (about 50 bits each); a plain hash suffices because a code only replaces the
  second factor, never the password. Shown once, when made.
- **Turning it off, or new codes,** take the password *and* a code (or a recovery code): a session
  alone, or a password alone, is not enough to remove the second factor. Off also replaces the
  key and drops the codes, so turning it back on starts afresh. Changes push `account`, and so
  does a sign-in that uses up a recovery code, so a device showing the count stays right; the web
  invalidates its two-factor query on it.
- **A password reset leaves it on:** the emailed link proves control of the mailbox, one factor.
- **The way back in** for someone who lost both the phone and the codes is the operator's:
  `dotnet keepITCore.dll disable-two-factor <email>` on the server (`DisableTwoFactorCommand`,
  run from `Program.cs` after the database init, instead of serving). It also lifts a lockout.
  There is no admin page to do it from, and no email route, deliberately; see FAQ.md.

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
  A token in a URL lands in access logs, so nothing of ours logs a query string (see **Security**).

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
  (`/reset-password?email=…&token=…`). So no log of ours holds a query string or a referer. The
  API's request line is the path only. nginx's stock formats write whole URLs, so both configs
  replace them: nginx logs only what never reached the API (an error it answered itself, or the
  API not answering), by path, in the API's line layout, to stdout beside the API's log. An
  error-log line quotes the raw request and can't be trimmed, so `/api/realtime` has its own
  location that doesn't write one (a failure still shows in the access log as a 502). And
  `Referrer-Policy: strict-origin` keeps the reset page's full URL out of the referer of
  everything it loads, even same-origin. CI sends both kinds of token through the built image and
  fails if either reaches its log. A new credential must never go in a URL; if one has to, it
  must stay out of every log format, and that CI step sends it too. An operator's own proxy in
  front logs URLs too, which the README points out.
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
  where each resource is `"notes"`, `"lists"`, `"notification"`, `"settings"`, or `"account"`
  (`RealtimeResources`; `account` is the signed-in user, renamed via `PUT /api/auth/me`). A
  client ignores a name it doesn't know, which is what lets a new one ship without breaking
  older apps. Clients only *receive*; mutations stay on REST, so the hub has **no
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
- **Sharing-aware fan-out:** a shared note's content change must reach the owner's devices **and**
  every collaborator's. This is done by fanning out over the recipient set, not SignalR groups: the
  controller asks `NoteAccessService.RecipientIdsAsync(noteId)` (owner + all grantees) and calls
  `NotifyAsync` per user. **Per-user** changes (pin/archive/trash, list membership, reminders,
  settings, the display name) notify only the acting caller, since no one else's view moved. A
  `notification` signal targets a single user. A group-per-note model remains a future optimization
  if the recipient loop ever gets expensive.
- **Clients:** web — `web/src/realtime/RealtimeSync.tsx` holds one authenticated connection
  while signed in, maps each resource to its TanStack Query key and invalidates on `Changed`,
  refreshes the token in `accessTokenFactory`, and re-syncs everything on reconnect
  (`withAutomaticReconnect` + `onreconnected`). `account` is the exception: the signed-in user
  lives in `AuthProvider`, not a query, so it and every re-sync call its `refreshUser()`.
  Android — `data/RealtimeClient.kt` (official SignalR Java client) forwards `Changed` to the
  sync engine / notifications watcher, and `account` to `SessionRepository.refreshUser()`; the
  Java client has no automatic reconnect, so it retries on a delay and re-syncs (user included)
  on every reconnect. It acts on `notes`/`lists`/`notification`/`account` only — **`settings` is
  deliberately dropped**, since the app has no server-synced appearance to apply (see **Android
  client** → theming).
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
- **Concurrent edits:** optimistic updates + SignalR keep editors roughly in sync. Each edit
  names the fields it changed (`UpdateNoteDto.fields`: type, title, body, colour, checklist) and the
  server sets only those, so two people changing different parts of a note both keep their change;
  within one field, the edit that arrives last wins. Before, an update replaced the whole note, and
  an edit queued offline on Android silently reverted whatever had changed meanwhile in the parts it
  never touched. The checklist is one field: merging rows, or text within a field (CRDT), is out of
  scope.
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
reminders, always the *next* occurrence), `Recurrence`, `TimeZone` (the IANA zone of the device
that set it), `FirstAtUtc` (the occurrence the user picked), `FiredAtUtc` (set when a one-time
reminder fires; null = pending; rescheduling resets it).

**Endpoints** (on the notes resource; read access suffices, per-user realtime only):
- `PUT    /api/notes/{id}/reminder` — set/replace the caller's reminder
  (`{ remindAtUtc, recurrence, timeZone?, firstAtUtc? }`).
- `DELETE /api/notes/{id}/reminder` — clear it (idempotent).
- `GET    /api/notes?reminders=true` — the caller's notes with a reminder set, soonest first.
  Spans active **and** archived (like Keep) but never trash.

**Server-side firing.** `ReminderDispatcherService` (hosted service) ticks every 30 s: it
scans for pending due reminders (skipping notes the user has trashed — a still-pending
reminder fires after restore), raises a `ReminderNotification`, and pushes realtime
(`notification` for the bell + `notes` for the chip). One-time reminders are marked fired;
recurring ones advance to the next *future* occurrence — a long outage produces **one**
catch-up notification, not one per missed slot. Each reminder saves individually so a poison
row can't roll back the batch. Firing is single-instance (no cross-instance locking), a known
accepted limitation.

**When a repeat goes off** (`Notes/ReminderSchedule.cs`). A reminder is set for a time on
someone's clock, not for an instant, so repeats are counted on the wall clock of its `TimeZone`:
a weekly 08:00 reminder stays at 08:00 when the clocks change. Every occurrence is counted from
`FirstAtUtc`, never from the previous one, so a monthly reminder on the 31st falls on February's
28th and comes back to March's 31st. In the hour the clocks skip, a time moves on by the gap;
in the hour they repeat, it goes off the first time round (java.time's rules, which the server
copies). Both clients send their zone (`Intl` on the web, `ZoneId.systemDefault()` on Android).
A reminder without one, set before 0.9.3 or by an older app, repeats on the server's own zone,
`TZ` (Unraid sets it; Compose defaults it to UTC, which is what every reminder used before).
An unknown zone is stored as none rather than refused, because an outbox can only drop what the
server turns down. `NoteDto.reminderTimeZone` always reports the zone the server actually counts
in, so a phone moving a reminder on by itself never uses a different one.

The Android app holds a copy of these rules (`nextOccurrenceAfter` in `data/offline/NoteOps.kt`),
because it moves reminders on itself: between syncs, with the app closed, and always in
standalone mode. If the copies disagree, one reminder is posted twice, an hour apart. So both are
tested against the same cases, `keepIT/keepITCore.Tests/ReminderOccurrences.json`; add a case
there, not to one side. A standalone phone gives reminders set before zones were kept its own zone
once (`zoneAdoptions`), queued like any reminder change so a server connected later gets it too.

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
- **Settings page** also hosts account management (avatar upload, editing or clearing the display
  name, change password), the operator's test-email button, and an About section
  (`features/about/`) with the server version from `/api/meta`, the project's links and the
  open-source projects keepIT is built on. When SMTP is configured without `App__PublicBaseUrl`
  (so reset emails are switched off), it says so from `GET /api/settings/email-status`: a banner on
  every section, a marker on the Email section, and the full explanation there, suggesting the
  address currently in use. Once configured, the Email section shows where reset links point
  instead. It also keeps `Email__AllowUnencrypted` visible while it's on, as a warning in the Email
  section.

## Android client (`app/`)

**Status: implemented.** A native Android app — Kotlin, Jetpack Compose (Material 3),
package `org.hyperstarit.keepitapp`, minSdk 34. Not a WebView wrapper: the point is a real
native experience, native reminder notifications, and a real home-screen widget. It is a
first-class consumer of the same REST + SignalR contract — the backend never special-cases it.

**Stack & wiring.** Retrofit + OkHttp + kotlinx.serialization for HTTP; the official SignalR
Java client for realtime; Glance for the widget; commonmark-java for note bodies (see **Text
note** under the note model). Dependency wiring is a **hand-rolled
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

**Device backup.** Android backs up everything the rules don't exclude — to the cloud (Google, or
Seedvault on de-Googled phones) and in a transfer to a new phone — so
`res/xml/data_extraction_rules.xml` is exclusions only, and there are two. The cookie's prefs file
(`ApiClient.PREFS_NAME`) stays behind: a restored cookie is either a working sign-in sitting in a
backup or, once the original device has refreshed past it, a replay that the server's reuse
detection answers by ending every session on the account. A restored phone signs in again, and the
restored cache and outbox carry on under the same account (a different one wipes them). The
downloaded-image cache (`offline/media`) stays behind too: it re-downloads, and it alone can exceed
cloud backup's 25 MB per-app quota, which skips the backup altogether. Everything else travels, and
must — for a standalone user the offline store and staged images are the only copy.
`BackupRulesTest` pins both directions.

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
  set-reminder / clear-reminder / delete / attach-media / delete-media / empty-trash, and the
  list ops create-list / update-list / delete-list) applies to the local cache instantly and
  enqueues a durable op. Creates — of notes and of lists — use a temp id that is remapped
  across the queue once the server assigns the real one. Replay is FIFO, so no queued op may
  name a temp list id whose create sits behind it (the server would refuse the whole request,
  not just the list): `coalesce` only folds a membership into a note's create when every list
  it names was created first, and deleting a list created offline strips it from every queued
  membership. Empty-trash names many notes, and one temp id would get it refused just the
  same, so a note that only exists locally leaves the op along with its create; a queued
  set-state survives it, since the server only empties what is in its trash. Against a server
  older than the endpoint (404) it falls back to a `DELETE` per own note still in the trash;
  notes shared with the user then stay there.
- **Acting on several notes at once** (the note list's multi-select: pin, color, lists,
  archive, trash, restore, delete forever) has no op or endpoint of its own. `BulkOps.kt` turns
  it into the single-note ops, one per note that actually changes, and `Outbox.enqueueAll`
  coalesces them in turn exactly as one-by-one enqueues would, but writes the queue once. A
  color is content and the server sets it only through the full update, so a recolor sends each
  note as cached with the new color, as the web card's picker does, and skips view-only notes
  rather than queue a certain 403. Delete forever on a selection is the empty-trash op, which
  already leaves what the user can't delete. Nothing new reaches the server, so the feature works
  against servers older than it.
- **`SyncEngine`** — drains the outbox against the REST API, then refetches everything (all
  three views + lists, in parallel), overlaying any still-queued local edits on the server
  truth. Kicked on sign-in, connectivity return, every enqueue, SignalR pushes, and
  pull-to-refresh; runs are single-flight. Failure policy per op: network/5xx stops the run
  (retry later, queue intact); 401 defers to the session (re-login resumes replay); any other
  4xx is permanent — the op is dropped **with a user-facing message** (e.g. the note was
  deleted on another device).
- **Saying why it's offline.** `ConnectivityMonitor.isOnline` follows request outcomes as well as
  the OS network callback, so it also goes false when the phone has a network and the server still
  can't be had. What stopped the run is classified (`SyncProblem`: a name that won't resolve, no
  connection, a timeout, a certificate, an HTTP status, a failed sign-in renewal, a body that isn't
  the API's JSON) and kept only while the phone has a network — with none, the failed lookup is a
  symptom of being offline. The notes screen's strip shows it instead of "Offline", which is what a
  phone whose DNS had stopped resolving the server used to show, exactly as for airplane mode. A
  refresh that fails without the cookie being rejected throws `RefreshFailedException` with the
  status or the cause, so a refresh endpoint answering 500 isn't reported as an unreachable server.
- Sign-out best-effort flushes the queue while the session is still valid, then wipes the
  local store (staged images included), alarms, and posted notifications. Whatever the flush
  didn't get through is lost with it, so with changes still queued the drawer's Sign out asks
  first, naming how many.
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
- **What's off.** Sharing, the notification inbox, display name, change password, the server
  version, refresh and the sync strip are hidden. **Sign out** is replaced by **Erase notes** in
  Settings (confirmed — there is no server copy). Unlike a sign-out, which leaves the widget showing
  the last-known notes, an erase empties the widget's snapshot too.
- **Images.** A queued attachment *is* the image: the editor shows it plainly (no upload
  spinner), opens it in the viewer, saves it to the gallery, and removes it by withdrawing its
  op (`Outbox.remove`). Cards fall back to the first queued attachment as their hero — which
  also shows a photo attached offline in server mode before it uploads.
- **Reminders.** Alarms fire from the cache exactly as before, but with no server nothing marks
  a one-time reminder fired or advances a recurring one. `settleDueReminders` does that in the
  cache — only *after* `ReminderScheduler.syncFrom` has seen it, so an occurrence is always
  posted before it is advanced past; the same UTC arithmetic as the server (`advanceOccurrence`).
- **Connecting a server later** (Settings → This phone only → Connect to a server, the sign-in
  form again). Once
  the credentials are accepted the queue is readied (`readiedForUpload`: one-time reminders
  already in the past are dropped and recurring ones moved to their next occurrence, or the
  server's dispatcher would fire them again), the mode flips, and the ordinary sync replays the
  whole queue into the account — merged with whatever it already holds. The store changes owner
  only on the sign-in itself, so a crash mid-switch can never restart standalone over a store
  that looks like an account's.
- **Limits.** Data lives only on the phone, so a backup is the user's to take — Settings → Your
  data writes the same archive the server writes (see **Export & import → On Android**), built
  from the cache and the staged images in the outbox. Images are stored as picked, so ones the
  server would refuse (over 10 MB or 100 megapixels, HEIC) fail on that first upload; they land in
  the gallery rather than being lost (see above).

**Realtime, reminders, notifications.** `RealtimeClient` (see **SignalR realtime**) kicks the
sync engine on `notes`/`lists` and the `ServerNotificationsWatcher` on `notification`.
Reminders fire locally via `AlarmManager` (see **Reminders**); a `BootReceiver` re-arms them
after reboot.

**Widget** (`widget/KeepItWidget.kt`, Jetpack Glance) — the headline reason for going native:
the latest notes at a glance, a "+" that deep-links into the composer, and a header refresh
that runs a one-shot background sync. It renders purely from the local cache, so it needs no
network or auth of its own and shows last-known notes even signed out; when the cache
changes, every widget re-renders. It draws in the app's theme (see **UI & design parity**), so a
new theme choice re-renders it too.

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
(staggered, with sync-status strip and pending-changes count), editor (Markdown styled live as it
is typed, checklist editing, color, share sheet, reminder dialog),
notifications inbox, settings (theme, notification + exact-alarm permissions, display name,
change password, about/version — a theme for this device only and no accent, see below).

**Settings** (`ui/settings/`) is a short top level that leads to sub-pages, each its own route in
`SettingsRoutes` (registered in `AppRoot`'s `MainNav`). The top level is the account card (in
standalone mode the device's), the theme (a dialog: four choices need no page), and rows into
Notifications, Your data and About, each summarising where it stands. The Notifications row
re-reads both permissions on every resume and is marked when either is off, so a blocked
permission is visible without opening the page. The notes screen asks too, but only when it
matters: while a reminder is pending and notifications are off, a banner offers to allow them
(`NotificationsOffBanner`). Android asks for the permission only when an app requests it, and the
app used to request it only when a reminder was set on the phone — reminders set on the web then
fired on time and showed nothing. A form or a long explanation gets a page:
Account (display name in a dialog, email, Change password as a page of its own, the server
address, Sign out), This device (standalone: connect a server, erase), Notifications, Your data,
About. Every page is built from `SettingsComponents.kt` (page frame, rounded card of rows, row,
account card), so they read as one screen and follow the theme through `KeepItColors`. Sign out
appears in the drawer and on the Account page, and both use the same unsynced-changes warning
(`ui/auth/SignOutDialog.kt`).

**About** is one page in two clients, in the same words: the web's
`web/src/features/about/about.json` holds the description, links, thanks and credits, and
`ui/settings/AboutContent.kt` is the Android copy. `AboutContentParityTest` reads the JSON and fails
when the parts both pages show drift apart (everything but each client's own credits) — the same
arrangement as the colour tokens below. Both web files that Android tests read (`index.css`,
`about.json`) are declared inputs of the unit-test task in `app/build.gradle.kts`, so a change on
the web side alone reruns them instead of the tests being skipped as up to date. The credits are
also held to the dependencies: `about.test.ts` reads `web/package.json`, `keepITCore.csproj` and
what `deploy/Dockerfile` installs, and `AboutCreditsTest` reads `app/build.gradle.kts`; a library
added or removed without its credit following, or a credit no library needs any more, fails them.
Tooling that never reaches users is listed there as not credited, with why. The page's icon
is drawn from the launcher's own layers (Compose can't draw an adaptive icon), so it stays the
icon `docs/brand/render_icons.py` writes.

### UI & design parity (native, shared design language)

The Android UI reads as **the same product on a phone** — same tokens, card style, accent
system, Keep-like interaction model — while behaving natively. The approach is **native
Compose with a shared *design language*, not shared code and not pixel-cloning**:

- **The tokens are the contract.** `web/src/index.css` is the canonical design system; the
  Android theme (`ui/theme/`) transcribes the same values into Compose color objects — never
  re-picked by eye, and no raw hex scattered through composables on either client. `Color.kt`
  carries all three of the web's themes as `KeepItPalette.Dark`, `.Dim` and `.Light`, and
  `WebTokenParityTest` reads `index.css`, resolves each theme the way the cascade does, and fails
  on any value that drifts — the transcription is about a hundred values, and a mistyped digit
  is a colour nobody notices until the two apps sit side by side. `ThemeContrastTest` holds every
  palette, and the Material scheme built from it, to AA.
- **Themes are a token swap, as on the web.** `KeepITAppTheme` provides the palette through
  `LocalKeepItPalette`, and `KeepItColors.Text` & co. are `@Composable` getters over it, so screens
  restyle without knowing there is a theme. The Material scheme is built from the same palette
  (`colorSchemeFor`), with **every slot a component reads** set: whatever is left out falls back
  to Material's baseline purple, which is how the drawer's selected row and the time picker came
  out lavender-grey. Outside a themed composition there is no palette to read — the Markdown
  renderer and the editor's live highlighting take one as a parameter (the colours are baked
  into the `AnnotatedString`), and the widget picks its own (below).
- **The accent has two forms here too.** `KeepItColors.Accent` is the *fill* — the FAB, filled
  buttons (`accentButtonColors()`, the web's `bg-accent text-black`), the voice-note play button,
  a ticked checklist box — and always carries black. `AccentInk` is the accent as *content*: text,
  icons, links, borders, the cursor, spinners. They are the same on Dim and Dark; Light remaps
  the ink to the icon's deep green, because the bright fill is 2.9:1 on white. Material's
  `primary` is the ink, since TextButtons, focused fields, selection handles and the date picker
  all use it as content. A selected state (drawer row, chip, segment, the time picker's field) is
  the web's 15% accent tint with the **theme's text colour** on it, not the web's ink: the bright
  ink on a tint over Dim's surfaces measures 4.0–4.3:1.
- **Appearance is per device on Android, theme only.** Settings → Theme offers the web's
  four choices with the web's labels (Light, Dim, Dark, Auto); `data/Appearance.kt` keeps the
  choice in app-private prefs (`keepit_appearance`) and never sends it anywhere, so the web's
  per-account theme (`UserSettingsController`, `/api/settings`) does not follow the user to the
  phone, nor the phone's to the web, and the `settings` realtime push is still ignored
  (`KeepItApplication.kt`). Nothing stored means **Dim**, the look the app had before the setting,
  so an update changes nothing. Auto follows the phone between Light and **Dark**, the same rule
  as the web's "system". The accent stays fixed (forest) — there is no accent picker. Syncing
  later would be the DTO + route + repository, writing the server's value into the same store,
  and handling `settings` in the realtime handler.
- **The platform has to agree before Compose draws.** The choice is also handed to the system
  as the app's night mode (`UiModeManager.setApplicationNightMode`), which the system persists,
  so the launch splash and the first window come from the right `values/` or `values-night/`
  theme — no white flash for Dim on a light phone, no dark one for Light on a dark phone. Those
  themes also declare the bar icons (`windowLightStatusBar`): when the night mode flips while the
  app is open, the platform re-derives them during the configuration change, *after* Compose has
  styled the bars, so a theme that left them at Material.Light's default gave Light white status
  icons on white. Android 16 goes further and drops the app's choice outright on that change
  (the window loses `APPEARANCE_CONTROLLED`), so `MainActivity.onConfigurationChanged` re-applies
  the bar style, posted to run after the platform's handling. `MainActivity` handles `uiMode`
  itself (no recreation) and also restyles the bars and the window background whenever the
  palette changes. Checked on API 34 and 36 by reading `dumpsys window displays`
  (`mLastStatusBarAppearanceRegions`) after each theme switch and phone dark-mode flip; a dialog's
  scrim legitimately takes white icons.
- **The widget follows the app's theme.** A Glance composition is outside the app's theme, so
  `LocalKeepItPalette` there would silently be Dim; `KeepItWidget` reads the stored choice and
  uses `KeepItPalette` directly. Under Auto every colour is a light/dark *pair* the launcher picks
  between by its own night mode, so the widget follows the phone without a re-render; any other
  choice is one fixed palette, and the app re-renders the widget when it changes. The widget
  picker's static preview can't know the setting at all, so it follows the phone (`values/` and
  `values-night/colors.xml`).
- **Per-note palette:** a list per theme keyed by the **same** color keys the `Note.color` DTO
  stores (`"rose"`, `"amber"`, …), so the palette stays in lockstep across clients and a theme
  change recolours a note without re-keying it.
- **Masonry grid:** Compose `LazyVerticalStaggeredGrid` — a near-1:1 fit for the card grid.
- **Editor tools are a floating toolbar, not the web's footer** (`ui/notes/EditorToolbar.kt`):
  a Material 3-style pill above the navigation bar and keyboard, holding only what adds to the
  note (add sheet, formatting, colour sheet, checklist, microphone), with "Aa" swapping in the
  Markdown buttons so it stays one row. What acts on the note (reminder, share, pin, archive,
  trash) is in the top bar. The app draws edge to edge, so anything pinned to the bottom must pad
  for the navigation bar itself — the old full-width rows only padded for the keyboard and sat on
  the gesture handle.
- **Don't chase system-chrome parity:** status bar, back behavior, ripples, and insets follow
  Android conventions (the bar *icons* follow the app's theme, not the phone's). Matching
  palette/typography/cards/accents is what reads as "same app".
- **Explicitly rejected:** WebView/TWA/Capacitor wrappers (non-native feel, and the widget
  needs native code regardless) and pixel-exact cloning (fights Material conventions).

## Look & feel / design

The UI reads as **clearly Google Keep** — same mental model and layout — but **darker and
more modern**, not a pixel clone.

- **Keep-like layout.** Masonry grid of note cards; a collapsed "Take a note…" composer that
  expands inline; hover/touch actions on cards (pin, color, lists, archive, share, remind);
  left sidebar for navigation (Notes / Reminders / Archive / Trash + the user's lists for
  one-click filtering); top search bar.
- **The masonry is packed in JS** (`features/notes/masonry.ts`), not by CSS `columns`. CSS
  columns fill **column-major**, so a newest-first list read as a vertical snake down the left
  edge. We walk the notes in order instead, dropping each into the currently shortest column
  (ties left), which gives row-major reading order *and* balanced columns. Balancing needs a
  height before layout, so the module estimates one per note; the estimate only affects how
  even the bottom edge looks, never the order.
- **Dark-first theme.** Dark is the baseline; **dim** and **light** plus **9 independent
  accent colors** are token overrides (`data-theme` / `data-accent`), a swap not a rewrite. The
  default accent is **forest**, the green of the app icon's K (`docs/brand/`); the API
  gives new accounts the same default, and the Android app uses it as its fixed accent.
  Note background colors are re-tuned per theme: on dark and dim, saturated hues kept dark enough
  that secondary text still clears AA on them (an earlier, near-grey set read as dreary); on
  light, pastels.
- **Everything a theme must restate is a token**, not just the palette: elevation
  (`--shadow-card|panel|raised|overlay`, consumed by the `.elev-*` classes — Tailwind inlines a
  `--shadow-*` theme value into its utility, so it can't be overridden per theme), the modal
  scrim (`--color-scrim`), and the tints painted over a surface (`--color-overlay-hover`,
  `--color-overlay-line`). A hardcoded `shadow-black/40` or `hover:bg-black/20` is tuned for a
  near-black canvas and turns into a grey smear or a charcoal blob on the light theme.
- **The accent has two forms.** `--color-accent` is the fill; `--color-accent-ink` is the accent
  *as content* — text, icons, focus rings. They're the same on dark, but light remaps ink to a
  deep shade, because the bright fills sit at 1.7–2.9:1 on white. The fill carries black text
  (`bg-accent text-black`), so it must also clear AA under black: that is why forest's fill is
  `#41aa79` while the icon's `#1f6f4a` is only its ink. The `html[data-accent]` blocks set only
  `--color-accent(-strong)` and an `--accent-ink` input: they come after the theme blocks at equal
  specificity, so setting `--color-accent-ink` there would beat the theme and put the unreadable
  shade back.
- **Note cards are lit from above, and photo cards are painted on their photo.** A card's fill
  (`.note-card` in `index.css`, `NoteCardStyle` on Android) is a gradient of the note's colour:
  light lifts the top toward white, the dark themes sink the bottom toward black. Each theme only
  ever moves the colour away from its text, so the gradient can't cost contrast; lifting the
  dark tops instead, as first designed, took muted text to 4.1:1 on amber. A card with a picture
  is a *photo card*: its hero fades into a blurred copy of the same photo under the theme's
  `--photo-scrim`, and the card swaps its text tokens (and the accent ink) for the `--photo-text`
  pair. The scrim is measured over an all-white and an all-black photo in both token tests, which
  is what lets a photo card promise AA without knowing the photo. The blur is a `filter` on one
  layer (a RenderEffect on Android), never a `backdrop-filter`: there is nothing behind a card
  worth sampling, and re-sampling on every scroll frame is the cost glass usually brings.
- **Modern, restrained styling.** Generous spacing, soft rounded corners, subtle elevation,
  smooth micro-interactions, good empty/loading states. Menus and dialogs animate in
  (`.pop-in` / `.fade-in`, neutralized by the global reduced-motion rule). Confirmations use
  `components/ConfirmDialog.tsx` rather than `window.confirm`, which is the one thing that drops
  out of the app's theme and renders a multi-paragraph prompt as one unstyled run of text.
- **Responsive.** Column count adapts from one (phone) up; the sidebar collapses to an
  off-canvas drawer; touch-revealed controls on small screens.
- **Accessibility.** WCAG AA contrast on **all three** themes for text tokens, keyboard
  navigation, and `prefers-reduced-motion` respected. The focus ring is an `outline` with an
  offset, not a Tailwind ring: a ring's offset is painted a solid color, which drew a
  canvas-colored gap around every tool button sitting on a colored note card.

## Note functions (product definition)

A note is one of several **types**, and any note can carry a background color:

1. **Text note** — free-form **Markdown** text in `body`, rendered on cards and for viewers, with
   a formatting toolbar in both editors. The default type.
   - **One dialect, two spec-compliant parsers.** CommonMark plus the GFM extensions
     (strikethrough, tables, task lists, bare-URL autolinks), and a single newline is a line break
     (remark-breaks) so pre-Markdown notes read as written. The web uses react-markdown +
     remark-gfm + remark-breaks; Android uses commonmark-java with the matching extensions
     (`ui/markdown/`). Android once hand-rolled a regex subset, and every gap in it was a note that
     read differently on the phone — italic arithmetic, parsed code blocks, dead bare URLs — so a
     client must never render the body with anything less than a CommonMark parser.
   - **Raw HTML is shown as text, never interpreted**, and only `http(s)`/`mailto` links open
     (Android adds `tel`). Shared notes are other people's text: a `file://` link crashed the app
     on tap (FileUriExposedException) before Android filtered schemes.
   - **Editing rules are shared**: `web/src/features/notes/markdownEdit.ts` and Android's
     `ui/markdown/MarkdownEdit.kt` implement the same toolbar toggles and Enter-continues-the-list,
     each unit-tested. The Android editor also styles the raw text in place
     (`MarkdownVisualTransformation`: same characters, dimmed syntax), where the web has a
     preview toggle.
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
  because it's derived from persisted state rather than remembered client-side. The editors' "Add
  item" sits in that gap, between the unchecked rows and the checked ones, right where the new row
  appears. Nothing enforces
  this contract, so a client that writes display order back into `order` breaks the other clients:
  the rule lives in `web/src/features/notes/checklist.ts` and `app/…/data/Checklist.kt`.
- `KeepList` (the `List` resource) — id, ownerId, name, color, optional icon. Always private to
  its owner.
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
**Delete all** (`POST /api/notes/trash/empty`) empties the caller's trash in one request: their
own notes are purged as `DELETE` would, and from a note shared with them they are removed the
way leaving the share removes them, so its owner keeps it. It takes the ids the client showed,
not "whatever is in the trash now", and skips any that are no longer in the caller's trash, so
neither a slow click nor a replay from the Android outbox purges a note trashed or restored on
another device in the meantime.

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
- A list may have an **icon**: one emoji or other single symbol, i.e. one grapheme cluster, so a
  flag or a ZWJ family counts as one (`Lists/ListIcon.cs`; Android applies the same rule before
  queueing, `NoteLimits.listIconOrNull`). Both clients offer the same curated grid
  (`web/src/features/lists/listIcons.json`, hand-copied to Android's `ui/notes/ListIcons.kt` and
  held to it by `ListIconsParityTest`), but the server checks only the shape, never the grid, so
  the grid can grow without an API change. A list without one shows the generic list icon.

**Endpoints** (all caller-scoped):
- `GET    /api/lists` — the caller's lists (with note counts for the sidebar).
- `POST   /api/lists` — create (name, optional color and icon).
- `PATCH  /api/lists/{id}` — rename / recolor / re-icon. A null field is left alone, so an empty
  `icon` is how one is removed.
- `DELETE /api/lists/{id}` — delete (notes survive, unfiled).
- `PUT    /api/notes/{id}/lists` — replace the set of the caller's lists a note is in.
- Filtering: `GET /api/notes?listId=…` (repeatable for a union).

**Frontend.** TanStack Query keys include the active filter, so switching lists is a cache
key change, not a refetch hack. The selected-filter UI state itself is client state.

**Android.** List create / edit / delete are queued ops like every note mutation, so they
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
- **On Android** (`data/ProfileImage.kt`) the signed-in user's own picture is fetched through the
  authenticated client into `filesDir/offline/profile/` and drawn over the initial in the drawer
  and on Settings' account card, so it shows offline and from a cold start's first frame. It is
  refetched when realtime connects (every sign-in, every reconnect) and on the `settings` push an
  upload sends. A 404 removes it and any other failure keeps it, so a bad connection never turns a
  picture back into an initial; sign-out deletes it. Each new picture gets a new file name, since
  Coil caches by path. Uploading stays web-only.

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

ImageSharp is licensed to keepIT under the Six Labors Split License's open-source terms, which make
it Apache 2.0. Its builds since 4.x, and 3.2 on the 3.x line, also check for a **license key**
before compiling: a Release build without one fails, a Debug build only warns. keepIT has a free
community key (2026-10, valid two years), and Six Labors forbids publishing it, so it is never
committed. Locally it is `keepIT/keepITCore/sixlabors.lic` (gitignored and kept out of every
Docker build context), which `dotnet build -c Release` finds by itself. CI reads the
`SIXLABORS_LICENSE_KEY` secret, which must exist both as an Actions secret and as a Dependabot
secret, since Dependabot's PRs see only the latter. A Docker build gets it as a BuildKit secret
(`--secret id=sixlabors_license`), mounted for the publish step only, so no image layer holds it.
Anyone else who builds the image needs a key of their own (free, from licensing.sixlabors.com);
pulling it from Docker Hub needs none. When the key expires, every Release build fails until it is
renewed and the secret replaced.

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

### Voice notes

A note's attachments are **one ordered list of two kinds**, discriminated by `NoteMedia.Kind`
(`Image` / `Audio`) rather than split across a second table — so ordering, per-note limits,
deletion, the realtime fan-out and the export archive all carry over untouched. `Image` is
deliberately `0`: the column is appended to existing databases with the store type's zero value,
so every row that predates the enum reads back as what it actually is.

**Recording is Android-only, playback is everywhere.** Browsers can only capture audio in a secure
context — `navigator.mediaDevices` does not exist over plain http, which keepIT supports on a LAN —
so a recorder in the web app would be missing for a real share of users. The phone records; the web
plays.

**Both clients play a recording from the notes overview**, not only from the editor — a
twelve-second voice note is not worth opening a note for. Two constraints shape it, and they are
the same on each client for the same reasons:

* **Nothing downloads until a press.** A recording is served as one authenticated blob (there is no
  range-request streaming, and no token may go near a URL), so a grid where every card fetched on
  mount would pull the whole library over the wire to draw play buttons nobody pressed. The web
  holds the query back with `enabled` (`media/queries.ts`); Android passes the card a lazy
  `openFile` lambda and only then asks `MediaCache` (`ui/notes/CardAudio.kt`).
* **One player lives above the list, not inside a card.** On Android a card leaves the composition
  the moment it scrolls off, which would cut off the recording being listened to, so `NotesScreen`
  owns a single `CardAudioPlayer` and hands it down. The web keeps the equivalent as one
  module-level `playingElement`. Either way, starting one recording stops whatever was playing —
  two cards talking over each other is the one thing a grid of players must not do.

Android plays a **staged** recording from its outbox file too, so a voice note made offline, or in
standalone mode where nothing is ever uploaded, plays from the card before it has ever reached a
server.

**Format: mono, 22.05 kHz, AAC in m4a, ~32 kbps** (`data/AudioRecorder.kt`). A phone's mic array
yields one channel after its own noise suppression, so stereo would store the same voice twice.
22 kHz rather than the 16 kHz speech-to-text consumes, because transcribers downsample anyway —
recording higher costs accuracy nothing and only costs bytes, while 16 kHz is audibly closed-in on
playback. At this bitrate the 10 MB attachment cap is ~40 minutes of speech; at 44.1 kHz stereo it
would be ten. AAC rather than Opus despite Opus being better per bit, because the web plays these
back and Safari's Ogg support cannot be relied on. The recorder stops itself just under the cap, so
a long recording ends with a file that uploads rather than one the server refuses.

**Audio is stored exactly as uploaded.** There is no audio encoder in the container, and adding
ffmpeg to ship voice notes would be a large dependency for a self-hosted image — so the re-encode
that strips an image's GPS metadata has no equivalent here. That makes identifying the bytes the
whole of the validation: `Service/AudioProbe.cs` recognises m4a/ogg/mp3/wav by signature, never by
the name the client sent, and for MPEG-4 walks the box tree to prove the file has a sound track and
**no** video track — an attachment endpoint must not become video hosting. The same walk reads
`mvhd` for the duration, which is why m4a is the format that shows a running time. Everything else
shows none rather than a guess.

One endpoint serves both kinds: `POST /api/notes/{id}/media` sniffs the upload and branches. That
is what lets every client keep a single attach path — on Android it means the offline outbox needed
no new operation, so a recording made with no signal stages, queues, survives a reboot and uploads
through machinery that already existed. `PendingOp.AttachMedia` gained only a `kind` field, with a
default, so an outbox written before voice notes still decodes.

Images and recordings are capped separately (`MaxImagesPerNote`, `MaxAudioPerNote`), so one cannot
crowd out the other. `MaxAudioBytes` is the same 10 MB as an image on purpose: both travel through
an `/api/` proxy capped at 12 MB, and raising it alone would move the refusal from the API, which
explains itself, to nginx, which does not.

**Still deferred:** transcription of voice notes (a planned feature — the recording format above is
already chosen with it in mind), background images, a distinct image note type, reordering
attachments, and images in the Android widget.

## Export & import (`Portability/`)

A self-hosted app that can't hand a user their data back doesn't really give them their data.
Two endpoints do that, and they are the only pair in the app whose *file format* is part of the
contract rather than just the wire shape.

**The archive.** `GET /api/export` streams a zip:

```
keepit-export-YYYY-MM-DD.zip
├─ keepit-export.json     { schemaVersion, exportedAtUtc, appVersion, lists: ListDto[], notes: NoteDto[] }
└─ media/<noteId>/<mediaId>.<ext>
```

The manifest is **the DTOs the API already serves**, not a format of its own. That is the whole
design decision: those types are already the contract (generated into the TypeScript client,
mirrored in Android's `Dtos.kt`), and the Android offline cache already persists exactly this
pair as its `CacheSnapshot`, so an Android-side export is the same bytes it has on disk. One
shape, three producers, nothing extra to keep in sync — and `NoteProjection.ToDto` is shared with
`NotesController` so the export cannot drift from what a client would have been served.

Per-caller fields (`isOwner`, `role`, `canEdit`, `isShared`, `noteCount`) ride along as a snapshot
and are ignored on import. Only originals are archived — thumbnails are derived and regenerated,
so shipping them would double the file. `schemaVersion` is the compatibility gate: an importer
refuses an archive newer than it understands rather than silently dropping whatever was added.

**Export is owner-scoped** — the one read in the app that deliberately is *not* "own OR shared".
A note shared with the caller is someone else's data in their grid, and an archive of it would
outlive the owner revoking the share, so it stops at what the caller owns.

**Export streams.** The manifest is built in memory (the API already returns a user's whole grid
in one response), but image bytes are copied one file at a time into the response, so account size
doesn't become memory. `ZipArchive` has no async write path, so the endpoint lifts
`AllowSynchronousIO` for that one response; the alternative — spooling to a temp file — costs disk
equal to the archive and delays the first byte. What bounds the thread cost is the rate limit, not
the thread pool: export and import each get their own tight per-IP policy
(`RateLimitPolicies.Export` / `.Import`, five per five minutes), kept separate so downloading a
backup doesn't spend the budget for uploading one.

**The proxy has to agree about size.** Both `nginx.conf` files cap `/api/` bodies at 12 MB —
sized for one photo — so `/api/import` gets its own nested location raising it to the API's 256 MB,
with request buffering off (nginx would otherwise spool the whole archive to its own disk before
the API spools it again) and a 600s read timeout, since re-decoding every image in a large archive
takes far longer than nginx's 60s default. `/api/export` turns response buffering off so the zip
streams to the browser as it is produced. This is the failure mode `deploy/smoke-test.sh` exists
for — a proxy refusing what the API accepts is invisible to every test that talks to the API
directly — so the script now exports, imports, and pushes a 20 MB body that must come back 400
from the API rather than 413 from a proxy.

**Import only ever adds.** `POST /api/import` gives every note in the archive a new id and touches
nothing already in the account. Re-importing the same file therefore duplicates it — the accepted
trade, because the one operation that could destroy someone's notes is the one that must not be
able to. Lists are the exception: a list whose name the caller already has is *filed into* rather
than cloned, which destroys nothing and keeps the sidebar usable across repeated restores.
Matching notes by id ("restore over the top") is a later mode, once the format has mileage.

Timestamps are preserved (a restore that claimed every note was written today would sort the grid
into nonsense), and per-user state — pin/archive/trash, list membership, reminders — is restored
as the *importer's* own. One wrinkle worth knowing: a **one-time reminder whose moment has
already passed imports as already fired**, or restoring a year-old backup would hand the
dispatcher every overdue reminder at once and the user would get a notification storm for things
they dealt with long ago. Recurring ones need no help — the dispatcher advances them.

**An archive is a file from the internet.** Ids in it are never reused; file names in it never
reach the disk (entries are matched by the ids in their path and rewritten under server-generated
names, so there is nothing to traverse with); the manifest's *uncompressed* size is checked before
it is read and each image's before it is decompressed; and every image goes back through
`NoteMediaProcessor` — the same signature check, pixel bound, metadata stripping and thumbnailing
an upload gets. Its notes and lists are held to the limits every other write is held to
(`Data/NoteLimits.cs`, which also sizes the columns and the DTOs' `[MaxLength]`): over-long text is
shortened with a warning. Unchecked, a title over its column failed the whole import on Postgres, and
a body or checklist over the API's limit was stored — then refused on every later save. A skipped image is a warning in `ImportResultDto`, never a failed import: one
unreadable photo must not cost someone the other 400 notes in the file.

**Round-tripping is what the tests pin.** `ExportTests` and `ImportTests` export a real account
and read it back into another, so a DTO change that stops surviving the trip fails in CI rather
than the next time a user restores. The format's shape is deliberately *not* in the OpenAPI
document — it is a file format, not a response body — so those tests are its specification.

### On Android

The phone reads and writes the same archive, through `data/portability/`. Which path runs is the
only thing standalone mode changes:

- **Server-backed** — export is `GET /api/export` streamed straight into the file the user picked
  through the system picker, import is the multipart POST, then a refetch. The server holds the
  authoritative copy including images this device may never have downloaded, so asking it beats
  assembling an archive from a partial local cache.
- **Standalone** — there is no server, so `buildStandaloneArchive` assembles the manifest from the
  cache and the **outbox**. That last part is the wrinkle: a standalone note's images are not on
  the note at all (`NoteDto.media` is empty) but are still queued `AttachMedia` ops pointing at
  staged files, so each note gains the media rows its staged bytes justify — and only those,
  because promising an image the archive doesn't carry is worse than leaving it out.
- **Standalone import** replays the archive as ordinary local edits through `NotesRepository`, so
  every restored note and image also lands in the outbox: connect a server later and the whole
  restored set uploads into the account, which is the promise standalone mode already makes.
  One deviation from the server: a **one-time reminder whose moment has passed is skipped, not
  restored**. The server marks such a reminder fired; a queued reminder op cannot, so the
  standalone scheduler would treat every overdue reminder in the archive as due now and fire them
  all at once. The user is told in the import's warnings.

`Archive.kt` is deliberately plain JVM — `java.util.zip`, `File`, streams, and the one `Json`
configuration both ends share — so the format is unit-testable without an emulator; `Uri`,
`ContentResolver` and image decoding stay in `PortabilityRepository`. It is covered at two of the
three Android test layers: `ArchiveTest` on the JVM for the format's rules, and `ArchiveSmokeTest`
on the **minified** variant for the one that only shows up after R8 — kotlinx.serialization
resolves `NoteArchiveDto$$serializer` by name, so losing it would break export and import in
release builds only, silently. That smoke test asserts on JSON text rather than on decoded
objects: it is the actual cross-platform contract, and it keeps the test off data classes whose
getters R8 inlines and whose synthetic constructors it drops.

Voice notes ride the archive unchanged: the manifest carries `NoteMediaDto`, so a recording's kind
and duration travel with it, and the importer branches on the bytes exactly as the upload endpoint
does. The result counts **attachments**, not images, for the same reason.

**Not yet:** importing other apps' exports. There is no interchange format for notes (Keep ships
Takeout JSON, Evernote ENEX, Joplin JEX, Notion Markdown+CSV), so each one is an adapter that
converts *into* this archive and feeds the same import path — one code path that writes data,
foreign formats as an internal detail. A Google Keep adapter is the obvious first, and would be
lossy in named ways: keepIT has no audio, so voice notes cannot come across; HEIC attachments are
refused by the processor; Keep has more colours than the palette; and sharees are Google accounts
that don't exist on the instance. A Markdown export (the outbound half — what makes these notes
openable in Obsidian or Joplin without anyone writing a keepIT importer) is also still open.

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
Compose reads six values from `.env` (`JWT_KEY`, `POSTGRES_PASSWORD`,
`REFRESH_COOKIE_SECURE`, `FORWARDED_PROXY_HOPS`, `ALLOW_REGISTRATION`, `TZ`).

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

The release notes are laid out like calendarIT's (the structure is in CLAUDE.md → Releases).
The workflow writes the frame: the `# keepIT X.Y.Z` title, the logo, and "Changes since" the
last release. Then comes that version's `## X.Y.Z` section of `CHANGELOG.md`, its relative links
pointed at the files as of the tag, since they'd resolve against the release page (relative
images already resolve there). The coffee button, the Docker pull line and the APK line follow,
then GitHub's generated list of changes. A tag without a section still releases, with a warning. **Before tagging:** bump `versionCode`/`versionName` in
`app/app/build.gradle.kts` (F-Droid reads those literals; the code is
`X*1000000 + Y*10000 + Z*100 + 99`, the same the workflow derives, and the workflow refuses a tag
they disagree with before publishing anything, since F-Droid's reproducibility check would
otherwise fail days later), add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
(500 characters at most, for app users) and the `CHANGELOG.md` section (for operators). Up to
0.8.5 the code was `X*10000 + Y*100 + Z` (805); the wider one makes room for betas.

Every workflow names its runner image by version (`ubuntu-26.04`), never `ubuntu-latest`. A
release is then built on the image CI last tested, and a new image arrives as a commit CI runs,
not on whatever day GitHub moves the label.

**Betas.** A tag `vX.Y.Z-beta.N` (N from 1 to 98) is a beta of X.Y.Z, for testers, and it runs
the same workflow with three differences, each so that no one gets a beta without asking. The
GitHub Release is a **pre-release**: GitHub never marks it latest, and Obtainium skips it unless
the app's *Include prereleases* is on. The Docker image is tagged `X.Y.Z-beta.N` and `beta`,
never `latest`. And its notes are CHANGELOG.md's `## Unreleased` section, under a line saying
what a beta is. F-Droid needs nothing: its recipe follows only tags matching `^v[\d.]+$`, so it
never sees one. Nothing in the repository is bumped for a beta; the Gradle literals stay at the
last release, and the workflow builds the APK as `X.Y.Z-beta.N` with versionCode
`X*1000000 + Y*10000 + Z*100 + N`. That places every beta above the release before it and below
its own release (`…99`), so each installs over the last and the release over all of them, from
any source, since every source ships the same signature. The workflow refuses a beta that would
not be newer than the literals.

## Dev conveniences

- **Scalar API UI** at `/scalar/v1` (Development only).
- **Seed script** — `scripts/seed-dev-data.sh` / `.ps1` creates `test@test.com` /
  `Test1234#1234` with lists and a variety of notes against a locally running API.
- **`keepITCore.http`** — request collection for manual endpoint poking.
- **API tests** (`keepIT/keepITCore.Tests/`, xUnit, run in CI) host the real API in-process on a
  throwaway SQLite data root per host, and in a second CI job on an empty Postgres database per
  host (see **Database initialization**): the schema reconciler bringing an older database up to date
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
  default body limit once hid. The same CI job then checks that neither a hub token nor a reset
  token sent in a URL reaches the container log, and that an escape sequence sent in a path is
  shown escaped by both the API and nginx, never obeyed.
- **Dependency advisories** (`.github/workflows/dependencies.yml`, on every push and PR and
  weekly, since an advisory can appear for code that hasn't changed): fails on a high or critical
  advisory in the web app's runtime npm packages or in any NuGet package, direct or transitive.
  `dotnet list package --vulnerable` never fails by itself, so `.github/scripts/nuget-advisories.py`
  judges its report. Build-only npm tooling is left to Dependabot. The same workflow submits the
  libraries the Android app ships with (`releaseRuntimeClasspath`) to GitHub's dependency graph,
  the only way Dependabot alerts see them. Only those: the Android Gradle plugin's own tooling
  runs on build machines only, and its dozens of advisories would bury the app's. Dependabot (`.github/dependabot.yml`) opens grouped weekly version updates for
  all five ecosystems; it never proposes the next .NET major or a new major base image, which are
  deliberate upgrades.
- No web tests yet; the Android module is tested in three layers (see CLAUDE.md).

## Status & roadmap

All of the original build order is long since **implemented** — contract-first (OpenAPI +
generated TS client), then CRUD + optimistic UI, SignalR invalidation, JWT auth — and since
then: **sharing/collaboration** (invite→accept, roles, per-user overlay), the
**notifications inbox**, **reminders** (server dispatcher + native Android alarms),
**password reset/change + SMTP email**, **security hardening** (rate limiting, lockout,
refresh-token rotation with reuse detection, registration gating), the **native Android app**
(offline-first, widget, share sheet, and a **standalone mode** that needs no server), the
**single-container image + Unraid template**, the **tag-driven release pipeline**, and
**export/import** on every client, Android and standalone included (a zip of the caller's own
notes, lists and images, restorable into any account — see "Export & import").

**Remaining roadmap** (see README "What's next"):
- 📥 **Foreign importers** — Google Keep Takeout first, as an adapter *into* the existing archive
  format rather than a second import path.
- 🖼️ **Background images** — the remaining half of note media; attachments themselves are done.
- ✉️ **Invite non-users** — pending share invites keyed by email, resolved on signup.
- 🤖 **Generated Kotlin API client** — replace the hand-mirrored `Dtos.kt` with a client
  generated from the same OpenAPI document.
- 🔀 **Scale-out** (only if ever needed): Redis backplane for SignalR + locking for the
  reminder dispatcher.

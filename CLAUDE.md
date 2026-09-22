# keepIT

A notes app: a React web frontend and a native Android client over a shared ASP.NET Core REST API,
with real-time sync across a user's devices. Notes can be **shared between users** (owner + Viewer/Editor
grants), and support checklists, **image attachments**, colors, pins/archive/trash, per-user lists,
reminders, and search.
**Read `ARCHITECTURE.md` before any structural work** — it holds the design and the reasoning; this
file is only the always-on rules.

Three deployables live in this repo: the **API** (`keepIT/keepITCore/`), the **web app** (`web/`),
and the **Android app** (`app/`). They talk only over HTTP + WebSocket — never share code or a process.

## Stack

- **Backend:** ASP.NET Core Web API on **.NET 10** — `keepIT/keepITCore/` (solution `keepIT/keepITCore.slnx`).
- **Data:** EF Core → **PostgreSQL** in prod, **SQLite** dev fallback (provider chosen at startup from config). All backend-written data (SQLite file, media, Data Protection keys) lives under `App__DataRoot`.
- **Auth:** ASP.NET Core Identity + JWT. Access token in the response body, held in memory; refresh token in an httpOnly cookie; silent refresh on 401.
- **Realtime:** SignalR `RealTimeHub` at `/api/realtime` (JWT via `?access_token=`, per-user delivery). After a mutation the API pushes `Changed(resources)`; clients invalidate the matching cache keys and refetch.
- **Web frontend:** React 19 + Vite + TypeScript — `web/`. TanStack Query owns all server state. Tailwind v4, React Router v7.
- **Android client:** Kotlin + Jetpack Compose (Material 3) — `app/` (package `org.hyperstarit.keepitapp`). Retrofit + OkHttp + kotlinx.serialization for the REST API, the official Microsoft SignalR Java client for realtime, Glance for the home-screen widget, Navigation Compose for nav. **Offline-first** with a local cache + mutation outbox. No Room, no Hilt — see the Android section.
- **API contract:** OpenAPI from C# → clients. **C# DTOs are the single source of truth.** Web regenerates a typed client with `openapi-typescript`; the Android `data/Dtos.kt` is hand-kept in sync with the same DTOs.
- **Deploy:** Docker Compose — nginx (`web`) serves the SPA and reverse-proxies `/api` to the API; Traefik in front for TLS. Also shipped as a single self-contained image for Unraid (`deploy/keepit.unraid.xml`). In both, the API runs unprivileged (uid 1654, which owns `/data`), never as root — see ARCHITECTURE.md → Deployment.

## Hard rules (backend + web)

- **Never hand-write TypeScript that mirrors C# DTOs.** Change a DTO → `npm run generate:api` → fix the TypeScript errors (they are the complete list of call sites). Drift is a bug.
- **Server data lives in TanStack Query** (web) — never a global store (no Redux/Zustand/context for fetched data).
- **Frontend and backend are separate deployables** over HTTP + WebSocket. Never host React inside ASP.NET Core.
- **Note edits are optimistic** — instant UI, rollback on error.
- **Refresh token stays in the httpOnly cookie.** Never put any token in localStorage.
- **Note access is "own OR shared", never a bare `OwnerId == me`.** Resolve every note endpoint's access through `NoteAccessService` (`Notes/NoteAccessService.cs`): read needs ownership or any share; content writes need ownership or an **Editor** share; hard-delete is owner-only. Pin/archive/trash and list membership are **per-user** — write the caller's `NoteUserState` / `NoteList` row, not the shared note.
- **Never build an outbound link from the request.** `Origin`, `Host` and forwarded headers are whatever the sender chooses. A link that reaches a user's inbox (password reset today, invites to non-users later) is built only from `App:PublicBaseUrl` (`Infrastructure/PublicBaseUrl.cs`); without it, don't send the email.
- **Never put a credential in a URL: URLs get logged.** Two exist by necessity, the browser's hub `access_token` and a reset link's `token`, and both `nginx.conf` files log them redacted (CI checks the image's log). A new one would have to join that redaction `map` in both files and the CI check.
- **A new mutating endpoint must push realtime.** After `SaveChangesAsync`, call `IRealtimeNotifier.NotifyAsync(userId, …)` with the affected resources (`notes` / `lists` / `notification`). For a **shared** note's content, fan out to the whole recipient set (`NoteAccessService.RecipientIdsAsync`, i.e. owner + grantees); for **per-user** changes notify only the caller — mirror the existing controllers, or devices won't resync.

## Android app (`app/`)

Mirror the web app's behavior; it's a peer client, not a port. When the web app gains a note capability,
the Android app generally should too. Key design points:

- **Offline-first.** The whole dataset lives in memory as `StateFlow<List<NoteDto>>` in `NotesRepository`, backed by `data/offline/`: `LocalStore` persists a JSON `CacheSnapshot` + the outbox to `filesDir/offline/` (atomic temp-file+rename, **deliberately not Room** — personal-note scale). Mutations — notes *and* lists — enqueue a `PendingOp` in the `Outbox`; `SyncEngine` replays them when connectivity returns (`ConnectivityMonitor`) or on foreground/ sign-in. Go through the repository — never call the API directly from UI.
- **Standalone mode** (`data/AppMode.kt`): the app can run with **no server** — same cache and outbox, but `SyncEngine` is a no-op and the outbox is kept as the record that uploads everything when a server is connected later. A new server-only feature must be hidden (or gated) when `appMode.standalone` is set, and a new `PendingOp` must replay correctly *after* a long standalone stretch. See ARCHITECTURE.md → Android client → Standalone mode.
- **DTOs are hand-synced.** `data/Dtos.kt` mirrors the C# DTOs (the source of truth). Change a C# DTO → update `Dtos.kt` to match. There is no codegen step here, so this is the one place drift can creep in — keep field names and nullability exactly aligned.
- **Session** (`SessionRepository` + `ApiClient`): access token in memory, refresh cookie persisted in app-private `SharedPreferences` via `PersistentCookieJar` (the mobile analogue of the web httpOnly cookie), silent refresh on 401. Base server URL is user-entered at login.
- **Realtime** (`RealtimeClient`): SignalR against `RealTimeHub`; on `Changed` it triggers a sync/refetch, same contract as the web client.
- **Reminders** are native: `AlarmManager` (`notifications/ReminderScheduler`, `ReminderAlarmReceiver`) so they fire offline / app-closed, re-armed after reboot by `BootReceiver`. `ServerNotificationsWatcher` surfaces the server inbox as tray notifications.
- **Single-activity** (`MainActivity`, `launchMode=singleTask`) → Compose nav in `ui/AppRoot.kt`. External entry points arrive as intents and are turned into a `Destination` in `MainActivity.destinationFrom()`: the **widget** deep-links (compose / open note / inbox via extras), and **shared-in text** (`ACTION_SEND`, `text/plain`) opens the composer pre-filled. To add an external entry point: add a `Destination`, map the intent in `destinationFrom()`, and route it in `AppRoot`'s `MainNav`.
- UI is organized under `ui/` by area (`auth/ notes/ notifications/ settings/ markdown/ theme/`); the note editor is `ui/notes/EditorScreen.kt` (null `noteId` = composer). `ui/notes/ShareSheet.kt` is the **share-a-note-with-another-user** feature (owner/Editor grants), not the OS share sheet.
- **Build/verify:** `cd app && ./gradlew.bat :app:compileDebugKotlin` (Windows). Three layers of test, all wired into CI — see the Testing section.

## Testing the Android app

**R8 is the thing that breaks release builds here.** It has shipped a widget that never rendered
(twice) and an app that crashed on cold start — always the same shape: a class built reflectively
by a library, whose constructor R8 removes because nothing references it statically. Nothing
crashes at build time, nothing appears in our logs, a feature just silently never runs. Three
layers guard it, cheapest first:

1. **JVM unit tests** (`app/app/src/test/`) — offline op application, outbox coalescing, checklist
   ordering, the widget's snapshot projection + prefs codec, and the note colour palette.
   `./gradlew.bat :app:testMinifiedUnitTest` (that is the only unit-test task: `testBuildType` scopes
   the test components to the `minified` variant).
2. **`verifyReleaseKeepRules`** — after R8 runs, reads its own `usage.txt` and fails if anything in
   the reflectively-constructed list (`app/build.gradle.kts`) lost its constructor. `assembleRelease`
   is `finalizedBy` it, so it guards local builds and the release workflow. Add to that list whenever
   something new is built by name.
3. **Instrumented smoke tests** (`app/app/src/androidTest/`) — run against the **`minified`**
   variant, which is release's R8 config with debug signing so it installs without secrets
   (`testBuildType = "minified"`). They launch the app, construct the reflective types off the real
   dex, and compose the widget. `./gradlew.bat :app:connectedMinifiedAndroidTest`.

Rules for layer 3: the tests link against the app, and R8 renames, merges and drops whatever it
likes, so `proguard-rules-minified.pro` keeps the API surface the tests reference —
**never the internals they test**. If a smoke test needs a new keep, keep the narrowest entry point
that makes it link, and check the thing under test is still shrunk. `VariantSanityTest` fails if the
suite is ever pointed at an unminified build, where all of this would pass while proving nothing.

CI (`.github/workflows/ci.yml`) runs 1 + 2 in the `android` job and 3 in `android-instrumented`, on
every push and PR.

## Conventions

- **C#:** standard .NET naming; request/response DTOs suffixed `Dto`; EF entities in `keepITCore/Data/`; one controller per resource. Request validation is **DataAnnotations on the DTOs** (no FluentValidation). Every endpoint is scoped via `User.GetUserId()` — a caller reaches only data they own **or** have a share on (see the access hard rule); private resources (lists, settings, notifications) stay strictly owner-scoped.
- **Enums** that cross the wire carry `[JsonConverter(typeof(JsonStringEnumConverter<T>))]` so the OpenAPI doc (and generated clients) get a string-name union, not a number.
- **TypeScript:** generated client in `web/src/api/`; query hooks co-located in `features/<name>/queries.ts`; new features under `web/src/features/`.
- **Kotlin:** package `org.hyperstarit.keepitapp`; Compose UI under `ui/<area>/`; data/networking under `data/`. Match the heavy KDoc style of the surrounding files.
- **Releases:** tag `vX.Y.Z`. Before that: bump `versionCode` (`X*10000 + Y*100 + Z`) and `versionName` in `app/app/build.gradle.kts`, add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (≤500 chars, app users) and a `## X.Y.Z` section in `CHANGELOG.md` (operators), which becomes the GitHub release notes.
- **Commits:** imperative, resource-scoped — `api:`, `web:`, `app:`, `infra:`, `docs:`, `chore:`.

## Layout

```
keepIT/
├─ keepIT/keepITCore/       # ASP.NET Core Web API (.NET 10)
│  ├─ Auth/                 # Identity + JWT + refresh cookie
│  ├─ Data/                 # EF Core entities, AppDbContext, migrations
│  ├─ Notes/                # NotesController + NoteSharesController + NoteMediaController + NoteAccessService + DTOs
│  ├─ Service/              # ImageService, IMediaStorage/DiskMediaStorage, NoteMediaProcessor
│  ├─ Lists/ Settings/      # one controller + DTOs per resource
│  ├─ Notifications/        # UserNotificationController + DTOs (per-user inbox, TPH)
│  ├─ SignalR/              # RealTimeHub, IRealtimeNotifier, SubUserIdProvider
│  ├─ Infrastructure/       # OpenAPI, logging, DB provider selection, security/rate limiting
│  └─ Program.cs
├─ keepIT/keepITCore.Tests/ # xUnit API tests (WebApplicationFactory); TestHost/ holds the factory + helpers
├─ web/src/                 # React app (Vite + TypeScript)
│  ├─ api/                  # generated schema.d.ts, typed client, shared types
│  ├─ auth/                 # AuthProvider, AuthContext, in-memory token store
│  ├─ components/           # shared UI (Sidebar, Topbar, ColorPicker, icons …)
│  ├─ features/             # notes/ lists/ settings/ account/ notifications/ — each has queries.ts + components
│  ├─ realtime/             # RealtimeSync.tsx — SignalR connection + cache invalidation
│  ├─ pages/                # AuthPage, HomePage, SettingsPage
│  └─ lib/                  # utilities (cn, apiError, useDismiss)
├─ app/                     # Android client (Kotlin + Compose) — package org.hyperstarit.keepitapp
│  └─ app/src/main/java/org/hyperstarit/keepitapp/
│     ├─ data/              # ApiClient, KeepItApi (Retrofit), Dtos, NotesRepository, RealtimeClient, SessionRepository
│     │  └─ offline/        # LocalStore, Outbox, SyncEngine, ConnectivityMonitor, PendingOp, NoteOps
│     ├─ notifications/     # AlarmManager reminders, BootReceiver, ServerNotificationsWatcher
│     ├─ ui/                # AppRoot (nav) + auth/ notes/ notifications/ settings/ markdown/ theme/
│     ├─ widget/            # KeepItWidget (Glance home-screen widget)
│     └─ MainActivity.kt    # single-activity host; intents → Destination
├─ deploy/                  # Unraid template (keepit.unraid.xml)
├─ docker-compose.yml
└─ ARCHITECTURE.md
```

## Environment

- Windows host; **PowerShell** is the primary shell. Repo line endings are **LF** (`.gitattributes`).
- **API tests** live in `keepIT/keepITCore.Tests/` (xUnit): the real API in-process via `WebApplicationFactory`, each host on a throwaway SQLite data root. They cover the SQLite schema reconciler (an older database comes up to date without losing data), note media end to end, where password-reset links point, that SMTP never falls back to plain text, which requests get a Secure refresh cookie, and what emptying the trash removes. `KeepItApiFactory` takes per-host `Settings` and `Services` overrides (e.g. `CapturingEmailSender` to read outgoing mail); `FakeSmtpServer` is a loopback SMTP server that records what it receives. Parallelization is off on purpose — `FolderManagement.RootPath` is process-wide static, so two hosts at once would write each other's media. When you change an entity or a media rule, extend these.
- **Deployment smoke test:** `deploy/smoke-test.sh <base-url>` signs up, uploads a ~3 MB photo and reads it back **through whatever proxy is in front** — CI runs it against the freshly built single-container image, which is the only check that sees nginx, then checks tokens sent in URLs reach the container log only redacted. No web tests yet. The **Android app is tested in three layers** — see the Testing section below.
- **Dependencies:** `.github/workflows/dependencies.yml` fails on high/critical advisories in the web app's runtime npm packages and in NuGet packages (transitive too), on every push/PR and weekly; it also feeds the libraries the Android app ships with (`releaseRuntimeClasspath`, not the build tooling) to GitHub's dependency graph. Dependabot (`.github/dependabot.yml`) proposes grouped weekly updates. A package whose next major must not arrive as a routine PR (ImageSharp 4.x needs a licence; .NET-versioned packages) gets an `ignore` entry there.
- **Migrations are Postgres-authoritative** (design-time factory targets Npgsql). After changing an EF entity, add a migration. The **SQLite DB uses `EnsureCreated`, not migrations** — which does nothing to an existing file, so `Infrastructure/SqliteSchemaReconciler.cs` adds the tables, columns and indexes an older file is missing at startup (see ARCHITECTURE.md). Entity changes therefore land on an existing `App_Data/keepit.db` without deleting it. `App_Data/` is user data (gitignored) — never commit it.

## Common commands

```bash
dotnet run --project keepIT/keepITCore             # API on http://localhost:5025 (Scalar UI at /scalar/v1 in Development)
dotnet ef migrations add <Name> --project keepIT/keepITCore
dotnet ef database update --project keepIT/keepITCore
dotnet test keepIT/keepITCore.slnx                 # API tests (in-process, throwaway SQLite data roots)
bash deploy/smoke-test.sh http://localhost:8080    # end to end through the proxy (SMOKE_SKIP_SPA=1 for a bare API)
cd web && npm run dev                              # Vite on :5173, proxies /api to :5025
cd web && npm run generate:api                     # regenerate typed client (backend must be running on :5025)
cd app && ./gradlew.bat :app:compileDebugKotlin    # compile-check the Android app (Windows)
cd app && ./gradlew.bat :app:assembleDebug         # build a debug APK
cd app && ./gradlew.bat :app:testMinifiedUnitTest  # JVM unit tests (only unit-test task; see Testing)
cd app && ./gradlew.bat :app:assembleRelease       # release APK + verifyReleaseKeepRules
cd app && ./gradlew.bat :app:connectedMinifiedAndroidTest   # smoke tests on a device/emulator
docker compose up --build                          # full stack
```

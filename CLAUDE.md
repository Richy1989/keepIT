# keepIT

A notes app: a React web frontend and a native Android client over a shared ASP.NET Core REST API,
with real-time sync across a user's devices. Notes can be **shared between users** (owner + Viewer/Editor
grants), and support checklists, colors, pins/archive/trash, per-user lists, reminders, and search.
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
- **Android client:** Kotlin + Jetpack Compose (Material 3) — `app/` (package `org.spaceelephant.keepitapp`). Retrofit + OkHttp + kotlinx.serialization for the REST API, the official Microsoft SignalR Java client for realtime, Glance for the home-screen widget, Navigation Compose for nav. **Offline-first** with a local cache + mutation outbox. No Room, no Hilt — see the Android section.
- **API contract:** OpenAPI from C# → clients. **C# DTOs are the single source of truth.** Web regenerates a typed client with `openapi-typescript`; the Android `data/Dtos.kt` is hand-kept in sync with the same DTOs.
- **Deploy:** Docker Compose — nginx (`web`) serves the SPA and reverse-proxies `/api` to the API; Traefik in front for TLS. Also shipped as a single self-contained image for Unraid (`deploy/keepit.unraid.xml`).

## Hard rules (backend + web)

- **Never hand-write TypeScript that mirrors C# DTOs.** Change a DTO → `npm run generate:api` → fix the TypeScript errors (they are the complete list of call sites). Drift is a bug.
- **Server data lives in TanStack Query** (web) — never a global store (no Redux/Zustand/context for fetched data).
- **Frontend and backend are separate deployables** over HTTP + WebSocket. Never host React inside ASP.NET Core.
- **Note edits are optimistic** — instant UI, rollback on error.
- **Refresh token stays in the httpOnly cookie.** Never put any token in localStorage.
- **Note access is "own OR shared", never a bare `OwnerId == me`.** Resolve every note endpoint's access through `NoteAccessService` (`Notes/NoteAccessService.cs`): read needs ownership or any share; content writes need ownership or an **Editor** share; hard-delete is owner-only. Pin/archive/trash and list membership are **per-user** — write the caller's `NoteUserState` / `NoteList` row, not the shared note.
- **A new mutating endpoint must push realtime.** After `SaveChangesAsync`, call `IRealtimeNotifier.NotifyAsync(userId, …)` with the affected resources (`notes` / `lists` / `notification`). For a **shared** note's content, fan out to the whole recipient set (`NoteAccessService.RecipientIdsAsync`, i.e. owner + grantees); for **per-user** changes notify only the caller — mirror the existing controllers, or devices won't resync.

## Android app (`app/`)

Mirror the web app's behavior; it's a peer client, not a port. When the web app gains a note capability,
the Android app generally should too. Key design points:

- **Offline-first.** The whole dataset lives in memory as `StateFlow<List<NoteDto>>` in `NotesRepository`, backed by `data/offline/`: `LocalStore` persists a JSON `CacheSnapshot` + the outbox to `filesDir/offline/` (atomic temp-file+rename, **deliberately not Room** — personal-note scale). Mutations enqueue a `PendingOp` in the `Outbox`; `SyncEngine` replays them when connectivity returns (`ConnectivityMonitor`) or on foreground/ sign-in. Go through the repository — never call the API directly from UI.
- **DTOs are hand-synced.** `data/Dtos.kt` mirrors the C# DTOs (the source of truth). Change a C# DTO → update `Dtos.kt` to match. There is no codegen step here, so this is the one place drift can creep in — keep field names and nullability exactly aligned.
- **Session** (`SessionRepository` + `ApiClient`): access token in memory, refresh cookie persisted in app-private `SharedPreferences` via `PersistentCookieJar` (the mobile analogue of the web httpOnly cookie), silent refresh on 401. Base server URL is user-entered at login.
- **Realtime** (`RealtimeClient`): SignalR against `RealTimeHub`; on `Changed` it triggers a sync/refetch, same contract as the web client.
- **Reminders** are native: `AlarmManager` (`notifications/ReminderScheduler`, `ReminderAlarmReceiver`) so they fire offline / app-closed, re-armed after reboot by `BootReceiver`. `ServerNotificationsWatcher` surfaces the server inbox as tray notifications.
- **Single-activity** (`MainActivity`, `launchMode=singleTask`) → Compose nav in `ui/AppRoot.kt`. External entry points arrive as intents and are turned into a `Destination` in `MainActivity.destinationFrom()`: the **widget** deep-links (compose / open note / inbox via extras), and **shared-in text** (`ACTION_SEND`, `text/plain`) opens the composer pre-filled. To add an external entry point: add a `Destination`, map the intent in `destinationFrom()`, and route it in `AppRoot`'s `MainNav`.
- UI is organized under `ui/` by area (`auth/ notes/ notifications/ settings/ markdown/ theme/`); the note editor is `ui/notes/EditorScreen.kt` (null `noteId` = composer). `ui/notes/ShareSheet.kt` is the **share-a-note-with-another-user** feature (owner/Editor grants), not the OS share sheet.
- **Build/verify:** `cd app && ./gradlew.bat :app:compileDebugKotlin` (Windows). No unit tests yet.

## Conventions

- **C#:** standard .NET naming; request/response DTOs suffixed `Dto`; EF entities in `keepITCore/Data/`; one controller per resource. Request validation is **DataAnnotations on the DTOs** (no FluentValidation). Every endpoint is scoped via `User.GetUserId()` — a caller reaches only data they own **or** have a share on (see the access hard rule); private resources (lists, settings, notifications) stay strictly owner-scoped.
- **Enums** that cross the wire carry `[JsonConverter(typeof(JsonStringEnumConverter<T>))]` so the OpenAPI doc (and generated clients) get a string-name union, not a number.
- **TypeScript:** generated client in `web/src/api/`; query hooks co-located in `features/<name>/queries.ts`; new features under `web/src/features/`.
- **Kotlin:** package `org.spaceelephant.keepitapp`; Compose UI under `ui/<area>/`; data/networking under `data/`. Match the heavy KDoc style of the surrounding files.
- **Commits:** imperative, resource-scoped — `api:`, `web:`, `app:`, `infra:`, `docs:`, `chore:`.

## Layout

```
keepIT/
├─ keepIT/keepITCore/       # ASP.NET Core Web API (.NET 10)
│  ├─ Auth/                 # Identity + JWT + refresh cookie
│  ├─ Data/                 # EF Core entities, AppDbContext, migrations
│  ├─ Notes/                # NotesController + NoteSharesController + NoteAccessService + DTOs
│  ├─ Lists/ Settings/      # one controller + DTOs per resource
│  ├─ Notifications/        # UserNotificationController + DTOs (per-user inbox, TPH)
│  ├─ SignalR/              # RealTimeHub, IRealtimeNotifier, SubUserIdProvider
│  ├─ Infrastructure/       # OpenAPI, logging, DB provider selection, security/rate limiting
│  └─ Program.cs
├─ web/src/                 # React app (Vite + TypeScript)
│  ├─ api/                  # generated schema.d.ts, typed client, shared types
│  ├─ auth/                 # AuthProvider, AuthContext, in-memory token store
│  ├─ components/           # shared UI (Sidebar, Topbar, ColorPicker, icons …)
│  ├─ features/             # notes/ lists/ settings/ account/ notifications/ — each has queries.ts + components
│  ├─ realtime/             # RealtimeSync.tsx — SignalR connection + cache invalidation
│  ├─ pages/                # AuthPage, HomePage, SettingsPage
│  └─ lib/                  # utilities (cn, apiError, useDismiss)
├─ app/                     # Android client (Kotlin + Compose) — package org.spaceelephant.keepitapp
│  └─ app/src/main/java/org/spaceelephant/keepitapp/
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
- No test projects yet (backend, web, or Android).
- **Migrations are Postgres-authoritative** (design-time factory targets Npgsql). After changing an EF entity, add a migration. The **SQLite dev DB uses `EnsureCreated`, not migrations** — it won't alter an existing file, so delete `App_Data/keepit.db` to rebuild the schema locally. `App_Data/` is user data (gitignored) — never commit it.

## Common commands

```bash
dotnet run --project keepIT/keepITCore             # API on http://localhost:5025 (Scalar UI at /scalar/v1 in Development)
dotnet ef migrations add <Name> --project keepIT/keepITCore
dotnet ef database update --project keepIT/keepITCore
cd web && npm run dev                              # Vite on :5173, proxies /api to :5025
cd web && npm run generate:api                     # regenerate typed client (backend must be running on :5025)
cd app && ./gradlew.bat :app:compileDebugKotlin    # compile-check the Android app (Windows)
cd app && ./gradlew.bat :app:assembleDebug         # build a debug APK
docker compose up --build                          # full stack
```

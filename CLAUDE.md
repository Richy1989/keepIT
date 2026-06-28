# keepIT

A notes app — React web frontend + ASP.NET Core REST API, with real-time sync across a user's devices.
**Read `ARCHITECTURE.md` before any structural work** — it holds the design and the reasoning; this
file is only the always-on rules. Scope today is **web + API**; a native Android client
(Kotlin/Compose) is planned but **not in the repo yet**.

## Stack

- **Backend:** ASP.NET Core Web API on **.NET 10** — `keepIT/keepITCore/` (solution `keepIT/keepITCore.slnx`).
- **Data:** EF Core → **PostgreSQL** in prod, **SQLite** dev fallback (provider chosen at startup from config). All backend-written data (SQLite file, media, Data Protection keys) lives under `App__DataRoot`.
- **Auth:** ASP.NET Core Identity + JWT. Access token in the response body, held in memory; refresh token in an httpOnly cookie; silent refresh on 401.
- **Realtime:** SignalR `RealTimeHub` at `/api/realtime` (JWT via `?access_token=`, per-user delivery). After a mutation the API pushes `Changed(resources)`; clients invalidate the matching TanStack Query keys and refetch.
- **Frontend:** React 19 + Vite + TypeScript — `web/`. TanStack Query owns all server state. Tailwind v4, React Router v7.
- **API contract:** OpenAPI from C# → `openapi-typescript` → typed client. **C# DTOs are the single source of truth.**
- **Deploy:** Docker Compose — nginx (`web`) serves the SPA and reverse-proxies `/api` to the API; Traefik in front for TLS.

## Hard rules

- **Never hand-write TypeScript that mirrors C# DTOs.** Change a DTO → `npm run generate:api` → fix the TypeScript errors (they are the complete list of call sites). Drift is a bug.
- **Server data lives in TanStack Query** — never a global store (no Redux/Zustand/context for fetched data).
- **Frontend and backend are separate deployables** over HTTP + WebSocket. Never host React inside ASP.NET Core.
- **Note edits are optimistic** — instant UI, rollback on error.
- **Refresh token stays in the httpOnly cookie.** Never put any token in localStorage.
- **A new mutating endpoint must push realtime.** After `SaveChangesAsync`, call `IRealtimeNotifier.NotifyAsync(ownerId, …)` with the affected resources (`notes`/`lists`) — mirror the existing controllers — or other devices won't resync.

## Conventions

- **C#:** standard .NET naming; request/response DTOs suffixed `Dto`; EF entities in `keepITCore/Data/`; one controller per resource. Request validation is **DataAnnotations on the DTOs** (no FluentValidation). Every endpoint is owner-scoped via `User.GetUserId()` — a caller can never touch another user's data.
- **TypeScript:** generated client in `web/src/api/`; query hooks co-located in `features/<name>/queries.ts`; new features under `web/src/features/`.
- **Commits:** imperative, resource-scoped — `api:`, `web:`, `infra:`, `docs:`, `chore:`.

## Layout

```
keepIT/
├─ keepIT/keepITCore/       # ASP.NET Core Web API (.NET 10)
│  ├─ Auth/                 # Identity + JWT + refresh cookie
│  ├─ Data/                 # EF Core entities, AppDbContext, migrations
│  ├─ Notes/ Lists/ Settings/   # one controller + DTOs per resource
│  ├─ SignalR/              # RealTimeHub, IRealtimeNotifier, SubUserIdProvider
│  ├─ Infrastructure/       # OpenAPI, logging, DB provider selection, security/rate limiting
│  └─ Program.cs
├─ web/src/                 # React app (Vite + TypeScript)
│  ├─ api/                  # generated schema.d.ts, typed client, shared types
│  ├─ auth/                 # AuthProvider, AuthContext, in-memory token store
│  ├─ components/           # shared UI (Sidebar, Topbar, ColorPicker, icons …)
│  ├─ features/             # notes/ lists/ settings/ account/ — each has queries.ts + components
│  ├─ realtime/             # RealtimeSync.tsx — SignalR connection + cache invalidation
│  ├─ pages/                # AuthPage, HomePage, SettingsPage
│  └─ lib/                  # utilities (cn, apiError, useDismiss)
├─ docker-compose.yml
└─ ARCHITECTURE.md
```

## Environment

- Windows host; **PowerShell** is the primary shell. Repo line endings are **LF** (`.gitattributes`).
- No test projects yet.

## Common commands

```bash
dotnet run --project keepIT/keepITCore             # API on http://localhost:5025 (Scalar UI at /scalar/v1 in Development)
dotnet ef migrations add <Name> --project keepIT/keepITCore
dotnet ef database update --project keepIT/keepITCore
cd web && npm run dev                              # Vite on :5173, proxies /api to :5025
cd web && npm run generate:api                     # regenerate typed client (backend must be running on :5025)
docker compose up --build                          # full stack
```

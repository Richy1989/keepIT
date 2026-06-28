# keepIT

A notes app. Web frontend + REST API, real-time sync across devices.
Details and rationale live in `ARCHITECTURE.md` — read it before structural work.

## Stack

- **Backend:** ASP.NET Core Web API on **.NET 10** — `keepIT/keepITCore/`.
- **ORM:** EF Core → **PostgreSQL** in production, **SQLite** as a dev fallback. Provider chosen at startup from config. All backend-written data (SQLite file, media, Data Protection keys) lives under `App__DataRoot`.
- **Auth:** ASP.NET Core Identity + JWT. Access token returned in the response body and held in memory; refresh token in an httpOnly cookie. Silent refresh on 401.
- **Realtime:** SignalR hub (`RealTimeHub`) at `/api/realtime`. Pushes per-user change signals (`Changed(resources)`) after mutations; clients invalidate TanStack Query cache keys and refetch.
- **Frontend:** React 19 + Vite + TypeScript — `web/`. TanStack Query owns all server state. Tailwind v4. React Router v7.
- **API contract:** OpenAPI generated from C# → `openapi-typescript` → typed client. **C# DTOs are the single source of truth.**
- **Deploy:** Docker Compose — nginx (`web`) serves the SPA and reverse-proxies `/api` to the API container. Traefik sits in front for TLS.

## Hard rules

- **Never hand-write TypeScript types that mirror C# DTOs.** Change a DTO → run `npm run generate:api` → follow the TypeScript errors. Drift is a bug.
- Server data lives in TanStack Query, not a global store. Do not put fetched data in Redux/Zustand/context.
- Frontend and backend are **separate deployables** talking over HTTP + WebSocket. Do not host React inside ASP.NET Core.
- Note edits use optimistic updates — instant UI, rollback on error.
- Refresh token stays in an httpOnly cookie. Never touch localStorage for tokens.

## Layout

```
keepIT/
├─ keepIT/
│  └─ keepITCore/          # ASP.NET Core Web API (.NET 10)
│     ├─ Auth/             # Identity + JWT + refresh cookie
│     ├─ Data/             # EF Core entities, AppDbContext, migrations
│     ├─ Notes/            # NotesController + DTOs
│     ├─ Lists/            # ListsController + DTOs
│     ├─ Settings/         # UserSettingsController + DTOs
│     ├─ SignalR/          # RealTimeHub, IRealtimeNotifier, SubUserIdProvider
│     ├─ Infrastructure/   # OpenAPI, logging, DB provider selection, rate limiting
│     └─ Program.cs
├─ web/                    # React app (Vite + TypeScript)
│  └─ src/
│     ├─ api/              # Generated schema (schema.d.ts), typed client, shared types
│     ├─ auth/             # AuthProvider, AuthContext, in-memory token store
│     ├─ components/       # Shared UI (Sidebar, Topbar, ColorPicker, icons …)
│     ├─ features/         # notes/, lists/, settings/, account/ — each has queries.ts + components
│     ├─ realtime/         # RealtimeSync.tsx — SignalR connection + cache invalidation
│     ├─ pages/            # AuthPage, HomePage, SettingsPage
│     └─ lib/              # Utilities (cn, apiError, useDismiss)
├─ docker-compose.yml
└─ ARCHITECTURE.md
```

## Conventions

- **C#:** standard .NET naming. DTOs suffixed `Dto`. EF entities in `keepITCore/Data/`. One controller per resource.
- **TypeScript:** generated client in `web/src/api/`. Query hooks co-located in `features/<name>/queries.ts`. New features go under `web/src/features/`.
- **Commit messages:** imperative mood, resource-scoped — `api:`, `web:`, `infra:`, `docs:`, `chore:`.

## Common commands

```bash
# Backend (dev — runs on http://localhost:5025)
dotnet run --project keepIT/keepITCore

# EF Core migrations
dotnet ef migrations add <Name> --project keepIT/keepITCore
dotnet ef database update --project keepIT/keepITCore

# Frontend (dev server on :5173, proxies /api to :5025)
cd web && npm run dev

# Regenerate typed API client (backend must be running on :5025)
cd web && npm run generate:api

# Full stack
docker compose up --build
```

## Regenerating the API client

Run `npm run generate:api` from `web/` whenever a C# DTO changes. It hits `http://localhost:5025/openapi/v1.json` and writes `web/src/api/schema.d.ts`. Then fix any TypeScript errors — those are the complete list of call sites that need updating.

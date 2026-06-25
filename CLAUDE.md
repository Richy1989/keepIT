# keepIT

A Google Keep-style notes app. Web frontend + REST API, real-time sync across devices.
Details and rationale live in `ARCHITECTURE.md` — read it before structural work.

## Stack

- **Backend:** ASP.NET Core Web API on **.NET 10**. Project: `keepITCore`.
- **ORM:** EF Core → **PostgreSQL** (JSONB for note metadata, full-text search). Provider is
  chosen from env config at startup: Postgres if configured, else a **SQLite** dev fallback.
  All backend-written data (SQLite file, media, keys) lives under one `App__DataRoot` folder.
- **Auth:** ASP.NET Core Identity + JWT (access token in memory, refresh token in httpOnly cookie).
- **Realtime:** SignalR hub pushes note changes to other devices.
- **Frontend:** React + **Vite** + **TypeScript**. TanStack Query for server state. Tailwind.
- **API contract:** OpenAPI/Swagger generated from C# → typed TS client. **The C# DTOs are the source of truth.**
- **Deploy:** Docker Compose; the web container's nginx serves the SPA and reverse-proxies the API.

## Hard rules

- **Never hand-write TypeScript types that mirror C# DTOs.** Change a DTO → regenerate the OpenAPI client. Drift between the two is a bug.
- Server data is owned by TanStack Query, not a global store. Don't put fetched data in Redux/Zustand.
- Frontend and backend are **separate deployables** talking over HTTP + WebSocket. Do not host React inside ASP.NET Core.
- Note edits use optimistic updates (instant UI, rollback on error).
- Tokens: refresh token stays in an httpOnly cookie. Never put it in localStorage.

## Layout

```
keepIT/
├─ src/
│  ├─ keepITCore/      # ASP.NET Core Web API (.NET 10) — already created
│  └─ keepIT.Domain/   # shared DTOs / domain models (optional)
├─ web/                # React app (Vite + TS)
├─ docker-compose.yml  # api + postgres + (redis)
└─ keepIT.sln
```

## Conventions

- C#: standard .NET naming. DTOs suffixed `Dto`. EF entities live in `keepITCore/Data`.
- TS: generated client in `web/src/api/`, query hooks alongside it. Features under `web/src/features/`.
- Commit messages: imperative mood, scoped (`api:`, `web:`, `infra:`).

## Common commands

```
# backend
dotnet run --project src/keepITCore
dotnet ef migrations add <Name> --project src/keepITCore
dotnet ef database update --project src/keepITCore

# frontend
cd web && npm run dev          # Vite dev server, proxies /api to backend
npm run generate:api           # regenerate typed client from OpenAPI (wire this up)

# stack
docker compose up -d
```

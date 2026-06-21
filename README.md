# keepIT

A Google Keep–style notes app: a masonry grid of note cards with fast, optimistic
editing, labels, search, and **real-time sync** so a note edited on one device shows
up on your others without a refresh.

> **Status:** early / work in progress. The backend is currently an ASP.NET Core
> scaffold; most features below are designed but not yet implemented. See
> [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full design and
> [`CLAUDE.md`](CLAUDE.md) for the short always-on rules.

## Features

Notes come in several types, and any note can be styled:

- 📝 **Text notes** — free-form text / markdown.
- ☑️ **Checklist notes** — ordered, reorderable checkbox items.
- 🖼️ **Image notes** — one or more images as the note's content.
- 🎨 **Customizable backgrounds** — set a background **color** or **image** on any note.
- 🏷️ Labels, search, pin, and archive (Keep-style soft-delete / trash).
- 🔄 **Real-time sync** across devices via SignalR.

## Tech stack

| Layer      | Choice                                                              |
| ---------- | ------------------------------------------------------------------ |
| Backend    | ASP.NET Core Web API on **.NET 10** (`keepITCore`)                  |
| Data       | EF Core → **PostgreSQL** (JSONB metadata, full-text search)         |
| Auth       | ASP.NET Core Identity + **JWT** (access token in memory, refresh token in an httpOnly cookie) |
| Realtime   | **SignalR** hub pushing note changes to other devices              |
| Frontend   | **React** + Vite + TypeScript, TanStack Query, Tailwind            |
| API client | Typed TS client **generated from OpenAPI** (C# DTOs are source of truth) |
| Deploy     | Docker, behind Traefik                                              |

## Architecture at a glance

Two independent deployables talking over HTTP + WebSocket — the React app is **not**
hosted inside ASP.NET Core, so either side can be deployed and scaled on its own.

```
keepIT/
├─ keepITCore/        # ASP.NET Core Web API (.NET 10)
├─ web/               # React app (Vite + TS) — planned
├─ ARCHITECTURE.md    # full design & rationale
└─ CLAUDE.md          # short always-on rules
```

The **C# DTOs are the single source of truth** for the API shape: change a DTO,
regenerate the typed TypeScript client, and the compiler points at every frontend
spot that needs updating. Don't hand-write TS types that mirror C#.

## Getting started

Prerequisites: **.NET 10 SDK**, **Node.js** (for the frontend, once added),
and **Docker** (for PostgreSQL).

### Backend

```bash
dotnet run --project keepITCore/keepITCore
```

The API exposes an OpenAPI document in development.

### Frontend (planned)

```bash
cd web
npm install
npm run dev          # Vite dev server, proxies /api to the backend
npm run generate:api # regenerate the typed client from OpenAPI
```

### Full stack (planned)

```bash
docker compose up -d   # api + postgres (+ redis)
```

## License

TBD.

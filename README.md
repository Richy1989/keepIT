# keepIT

A Google Keep–style notes app: a masonry grid of note cards with fast, optimistic
editing, lists, search, sharing, and **real-time sync** so a note edited on one device
shows up on your others without a refresh. Keep's familiar layout, with a **dark, modern**
look.

> **Status:** work in progress. **Implemented:** auth (register/login/JWT + refresh),
> text & checklist notes with optimistic editing, colors, pin/archive/trash, lists with
> filtering, the dark Keep-style web UI, and a Docker Compose stack. **Planned next:**
> sharing, image notes / media upload, and real-time sync. See
> [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full design and
> [`CLAUDE.md`](CLAUDE.md) for the short always-on rules.

## Features

Notes come in several types, and any note can be styled:

- 📝 **Text notes** — free-form text. ✅
- ☑️ **Checklist notes** — checkbox items, tick inline. ✅
- 🎨 **Customizable backgrounds** — set a background **color** on any note. ✅ (background **images** planned)
- 🗂️ **Lists** — organize notes into named collections (a note can be in many) and filter the grid by list. ✅
- 🔍 Search, pin, and archive (Keep-style soft-delete / trash). ✅
- 🖼️ **Image notes** — one or more images as the note's content. *(planned)*
- 👥 **Shared notes** — share a note with other users as viewer or editor. *(planned)*
- 🔄 **Real-time sync** across devices via SignalR. *(planned)*

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
├─ keepIT/keepITCore/ # ASP.NET Core Web API (.NET 10)
├─ web/               # React app (Vite + TS)
├─ docker-compose.yml # traefik + api + postgres + web
├─ ARCHITECTURE.md    # full design & rationale
└─ CLAUDE.md          # short always-on rules
```

The **C# DTOs are the single source of truth** for the API shape: change a DTO,
regenerate the typed TypeScript client, and the compiler points at every frontend
spot that needs updating. Don't hand-write TS types that mirror C#.

## Getting started

Prerequisites: **.NET 10 SDK** and **Node.js 22+** for local dev, or just **Docker** for the
full stack. With no Postgres configured, the backend uses a zero-setup **SQLite** dev database
under `keepIT/keepITCore/App_Data/`.

### Local dev (two terminals)

```bash
# 1) Backend — http://localhost:5025 (Scalar API UI at /scalar/v1)
dotnet run --project keepIT/keepITCore

# 2) Frontend — http://localhost:5173 (Vite dev server, proxies /api to the backend)
cd web
npm install
npm run dev
```

Open **http://localhost:5173** and register an account.

Whenever a C# DTO changes, regenerate the typed client (backend must be running):

```bash
cd web && npm run generate:api   # OpenAPI → web/src/api/schema.d.ts
```

### Full stack (Docker)

```bash
cp .env.example .env        # then set JWT_KEY to a random 32+ char secret
docker compose up --build   # traefik + api + postgres + web
```

Open **http://localhost:8080**. Traefik serves the frontend and routes `/api` to the backend
on one origin; Postgres data and the API's data folder persist in named volumes.

## License

TBD.

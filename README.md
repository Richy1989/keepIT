<div align="center">

# 📝 keepIT

**A Google Keep–style notes app — dark, modern, and real-time.**

A masonry grid of note cards with fast, optimistic editing, lists, search, sharing, and
**real-time sync** so a note edited on one device shows up on your others without a refresh.

[![.NET 10](https://img.shields.io/badge/.NET-10-512BD4?logo=dotnet&logoColor=white)](https://dotnet.microsoft.com/)
[![React 19](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite&logoColor=white)](https://vite.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](https://docs.docker.com/compose/)
![Status](https://img.shields.io/badge/status-work_in_progress-yellow)

</div>

<!-- Add a screenshot at docs/screenshot.png and uncomment the line below for a hero image.
<div align="center"><img src="docs/screenshot.png" alt="keepIT screenshot" width="800"></div>
-->
<div align="center"><em>(screenshot coming soon)</em></div>

> **Status:** work in progress. **Implemented:** auth (register/login/JWT + refresh),
> text & checklist notes with optimistic editing, colors, pin/archive/trash, lists with
> filtering, the dark Keep-style web UI, and a Docker Compose stack. **Planned next:**
> sharing, image notes / media upload, and real-time sync. See
> [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full design and
> [`CLAUDE.md`](CLAUDE.md) for the short always-on rules.

## Contents

- [Features](#features)
- [Tech stack](#tech-stack)
- [Architecture at a glance](#architecture-at-a-glance)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
  - [Local dev (two terminals)](#local-dev-two-terminals)
  - [Full stack (Docker)](#full-stack-docker)
- [Regenerating the API client](#regenerating-the-api-client)
- [License](#license)

## Features

Notes come in several types, and any note can be styled:

- 📝 **Text notes** — free-form text. ✅
- ☑️ **Checklist notes** — checkbox items, tick inline, reorderable. ✅
- 🎨 **Customizable backgrounds** — set a background **color** on any note. ✅ (background **images** planned)
- 🗂️ **Lists** — organize notes into named collections (a note can be in many) and filter the grid by list. ✅
- 🔍 Search, pin, and archive (Keep-style soft-delete / trash). ✅
- 🖼️ **Image notes** — one or more images as the note's content. *(planned)*
- 👥 **Shared notes** — share a note with other users as viewer or editor. *(planned)*
- 🔄 **Real-time sync** across devices via SignalR. *(planned)*

## Tech stack

| Layer      | Choice                                                              |
| ---------- | ------------------------------------------------------------------ |
| Backend    | ASP.NET Core Web API on **.NET 10** (`keepITCore`)                 |
| Data       | EF Core → **PostgreSQL 17** (JSONB metadata, full-text search), **SQLite** dev fallback |
| Auth       | ASP.NET Core Identity + **JWT** (access token in memory, refresh token in an httpOnly cookie) |
| Realtime   | **SignalR** hub pushing note changes to other devices *(planned)*  |
| Frontend   | **React 19** + Vite + TypeScript, TanStack Query, React Router, Tailwind |
| API client | Typed TS client **generated from OpenAPI** via `openapi-typescript` + `openapi-fetch` (C# DTOs are source of truth) |
| Deploy     | Docker Compose, behind Traefik                                     |

## Architecture at a glance

Two independent deployables talking over HTTP + WebSocket — the React app is **not**
hosted inside ASP.NET Core, so either side can be deployed and scaled on its own.

```
┌────────────┐   HTTP /api + WebSocket   ┌──────────────────┐      ┌────────────┐
│  web/      │ ────────────────────────► │  keepITCore      │ ───► │ PostgreSQL │
│  React SPA │ ◄──────────────────────── │  ASP.NET Core 10 │      │ (SQLite in │
│  (Vite)    │     SignalR push          │  Web API         │      │  bare dev) │
└────────────┘                           └──────────────────┘      └────────────┘
        ▲                                          │
        └──────── Traefik (one origin in Docker) ──┘
```

The **C# DTOs are the single source of truth** for the API shape: change a DTO,
regenerate the typed TypeScript client, and the compiler points at every frontend
spot that needs updating. Don't hand-write TS types that mirror C#.

## Project structure

```
keepIT/
├─ keepIT/
│  ├─ keepITCore/        # ASP.NET Core Web API (.NET 10)
│  │  ├─ Auth/           # Identity + JWT, token service, DTOs
│  │  ├─ Data/           # EF Core entities, DbContext, migrations
│  │  ├─ Notes/          # notes controller + DTOs
│  │  ├─ Lists/          # lists controller + DTOs
│  │  ├─ Infrastructure/ # OpenAPI transformers, DB provider selection
│  │  └─ Program.cs
│  └─ keepITCore.slnx
├─ web/                  # React app (Vite + TS)
│  └─ src/
│     ├─ api/            # generated typed client (schema.d.ts) + client
│     ├─ auth/           # auth context/provider, in-memory token store
│     ├─ components/     # shared UI (Sidebar, Topbar, icons, ColorPicker)
│     ├─ features/       # notes & lists (cards, editor, query hooks)
│     └─ pages/          # AuthPage, HomePage
├─ docker-compose.yml    # traefik + api + postgres + web
├─ .env.example          # copy to .env (set JWT_KEY)
├─ ARCHITECTURE.md       # full design & rationale
└─ CLAUDE.md             # short always-on rules
```

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

### Full stack (Docker)

```bash
cp .env.example .env        # then set JWT_KEY to a random 32+ char secret
docker compose up --build   # traefik + api + postgres + web
```

Open **http://localhost:8080**. Traefik serves the frontend and routes `/api` to the backend
on one origin; Postgres data and the API's data folder persist in named volumes.

## Regenerating the API client

The typed TS client is generated from the backend's OpenAPI document, so the C# DTOs stay the
single source of truth. Whenever a DTO changes, regenerate it (the backend must be running):

```bash
cd web && npm run generate:api   # OpenAPI (localhost:5025) → web/src/api/schema.d.ts
```

## License

TBD.

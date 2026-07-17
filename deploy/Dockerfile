# syntax=docker/dockerfile:1
#
# Single-container build of keepIT for simple, self-contained deployments (e.g. Unraid testing).
# It bundles three things into one image:
#   1. the built React SPA, served by nginx
#   2. the .NET API (keepITCore), listening on loopback:8080
#   3. nginx as the public face on :80, serving the SPA and reverse-proxying /api to the API
# React is NOT hosted by ASP.NET — nginx and the API are separate processes talking over HTTP, just
# co-located. With no Postgres configured the API uses its SQLite fallback, and ALL writable state
# (SQLite db, Data Protection keys, uploaded images) lives under /data — mount that to persist it.
#
# Build context is the repo root:  docker build -f deploy/Dockerfile -t richy1989/keepit:latest .

# ---- 1. Build the React SPA (the committed OpenAPI client means no backend is needed here) ----
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- 2. Publish the .NET API ----
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS api
# The release version (git tag, e.g. 0.2.0) and commit sha, injected by the release workflow. The
# SDK's automatic sha-stamping needs a .git folder, which the build context doesn't have — so the
# sha comes in as an arg. Local `docker build` without args produces an honest "0.0.0-dev".
ARG VERSION=0.0.0-dev
ARG COMMIT=
WORKDIR /src
COPY keepIT/keepITCore/keepITCore.csproj keepITCore/
RUN dotnet restore keepITCore/keepITCore.csproj
COPY keepIT/ ./
WORKDIR /src/keepITCore
RUN INFOVERSION="$VERSION"; [ -n "$COMMIT" ] && INFOVERSION="$VERSION+$COMMIT"; \
    dotnet publish keepITCore.csproj -c Release -o /app/publish /p:UseAppHost=false \
      /p:Version="$VERSION" /p:InformationalVersion="$INFOVERSION"

# ---- 3. Runtime: API + nginx in one image ----
FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS final
RUN apt-get update \
 && apt-get install -y --no-install-recommends nginx \
 && rm -rf /var/lib/apt/lists/* /etc/nginx/sites-enabled/default

WORKDIR /app
COPY --from=api /app/publish ./
COPY --from=web /web/dist /usr/share/nginx/html
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# API binds loopback:8080; nginx is the public face on :80. All writable data under /data.
ENV ASPNETCORE_HTTP_PORTS=8080 \
    App__DataRoot=/data \
    ASPNETCORE_ENVIRONMENT=Production \
    Auth__RefreshCookie__Secure=false

EXPOSE 80
VOLUME ["/data"]
ENTRYPOINT ["/entrypoint.sh"]

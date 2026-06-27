# Multi-stage build for the keepITCore Web API (.NET 10).
# Build context is ./keepIT (see docker-compose.yml). The API listens on 8080 and is only reached
# through the web container's nginx. Stage names (base/build/publish/final) are kept so Visual
# Studio's container tooling can still attach for fast-mode debugging.
# See https://aka.ms/customizecontainer for how VS uses this file.

# Runtime base — also the stage VS mounts into for fast-mode (Debug) container runs.
FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS base
USER $APP_UID
WORKDIR /app
EXPOSE 8080

# Restore (cached unless the csproj changes), then bring in the rest of the source.
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
ARG BUILD_CONFIGURATION=Release
WORKDIR /src
COPY ["keepITCore/keepITCore.csproj", "keepITCore/"]
RUN dotnet restore "keepITCore/keepITCore.csproj"
COPY . .
WORKDIR "/src/keepITCore"

# Publish a framework-dependent build (no native apphost — the entrypoint runs the DLL directly).
FROM build AS publish
ARG BUILD_CONFIGURATION=Release
RUN dotnet publish "keepITCore.csproj" -c $BUILD_CONFIGURATION -o /app/publish /p:UseAppHost=false

# Final runtime image: just the published output on the non-root base.
FROM base AS final
WORKDIR /app
COPY --from=publish /app/publish .
ENTRYPOINT ["dotnet", "keepITCore.dll"]

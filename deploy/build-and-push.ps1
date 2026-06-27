#requires -Version 5
# Builds the single-container keepIT image for Unraid (linux/amd64) and pushes it to Docker Hub.
#
# Prerequisites (one-time):
#   docker login                 # log in as richy1989
#   docker buildx version        # buildx ships with Docker Desktop
#
# Usage:
#   ./deploy/build-and-push.ps1                  # builds & pushes richy1989/keepit:latest
#   ./deploy/build-and-push.ps1 -Tag v0.1.0      # also tags/pushes a version alongside :latest

param(
    [string]$Image = 'richy1989/keepit',
    [string]$Tag   = 'latest'
)

$ErrorActionPreference = 'Stop'

# Repo root is the parent of this script's folder; build context must be the repo root.
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Push-Location $root
try {
    $tags = @("${Image}:latest")
    if ($Tag -ne 'latest') { $tags += "${Image}:$Tag" }
    $tagArgs = $tags | ForEach-Object { @('-t', $_) } | ForEach-Object { $_ }

    Write-Host "Building and pushing $($tags -join ', ') for linux/amd64 (Unraid)..." -ForegroundColor Cyan
    docker buildx build --platform linux/amd64 -f deploy/Dockerfile @tagArgs --push .
    if ($LASTEXITCODE -ne 0) { throw "docker buildx build failed with exit code $LASTEXITCODE" }

    Write-Host "Done. On Unraid, pull/refresh: ${Image}:$Tag" -ForegroundColor Green
}
finally {
    Pop-Location
}

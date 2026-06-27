#!/usr/bin/env bash
# Builds the single-container keepIT image for Unraid (linux/amd64) and pushes it to Docker Hub.
#
# Prerequisites (one-time):
#   docker login                 # log in as richy1989
#   docker buildx version        # buildx ships with Docker Desktop / modern Docker Engine
#
# Usage:
#   ./deploy/build-and-push.sh                 # builds & pushes richy1989/keepit:latest
#   ./deploy/build-and-push.sh --tag v0.1.0    # also tags/pushes a version alongside :latest
set -euo pipefail

IMAGE="richy1989/keepit"
TAG="latest"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --image) IMAGE="$2"; shift 2 ;;
        --tag)   TAG="$2";   shift 2 ;;
        -h|--help)
            grep '^#' "$0" | sed 's/^# \{0,1\}//' | grep -v '!/usr/bin/env'
            exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# Build context must be the repo root (the parent of this script's folder).
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

tags=(-t "${IMAGE}:latest")
[[ "$TAG" != "latest" ]] && tags+=(-t "${IMAGE}:${TAG}")

echo "Building and pushing ${IMAGE}:${TAG} for linux/amd64 (Unraid)..."
docker buildx build --platform linux/amd64 -f deploy/Dockerfile "${tags[@]}" --push .

echo "Done. On Unraid, pull/refresh: ${IMAGE}:${TAG}"

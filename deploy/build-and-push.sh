#!/usr/bin/env bash
# Builds the single-container keepIT image for Unraid (linux/amd64) and pushes it to Docker Hub.
#
# Always pushes the :dev tag — never :latest. Release tags (:X.Y.Z, :latest) come exclusively
# from the GitHub release workflow (.github/workflows/release.yml), so a manual push can't
# clobber what production pulls.
#
# Prerequisites (one-time):
#   docker login                 # log in as richy1989
#   docker buildx version        # buildx ships with Docker Desktop / modern Docker Engine
#
# Usage:
#   ./deploy/build-and-push.sh                 # builds & pushes richy1989/keepit:dev
#   ./deploy/build-and-push.sh --tag mybranch  # also tags/pushes :mybranch alongside :dev
set -euo pipefail

IMAGE="richy1989/keepit"
TAG="dev"

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

if [[ "$TAG" == "latest" ]]; then
    echo "Refusing to push :latest — that tag is owned by the release workflow." >&2
    exit 1
fi

# Build context must be the repo root (the parent of this script's folder).
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

tags=(-t "${IMAGE}:dev")
[[ "$TAG" != "dev" ]] && tags+=(-t "${IMAGE}:${TAG}")

echo "Building and pushing ${IMAGE}:${TAG} for linux/amd64 (Unraid)..."
docker buildx build --platform linux/amd64 -f deploy/Dockerfile "${tags[@]}" --push .

echo "Done. On Unraid, pull/refresh: ${IMAGE}:${TAG}"

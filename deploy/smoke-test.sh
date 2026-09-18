#!/usr/bin/env bash
# Smoke-tests a running keepIT deployment end to end, through its public port: the web app is
# served, and a signed-in user can attach a multi-megabyte photo and read it back. The upload is the
# point — it only works if every proxy in front of the API lets a body that size through, which is
# exactly what nginx's 1 MB default did not, while every test that talks to the API directly passed.
#
# Usage:   deploy/smoke-test.sh [base-url]        (default http://localhost:8080)
#          SMOKE_SKIP_SPA=1 deploy/smoke-test.sh http://localhost:5025   # a bare API, no nginx
#
# Creates a throwaway account and note on the target: point it at a test instance, not production.
# Needs bash, curl and Python 3.
set -euo pipefail

BASE="${1:-http://localhost:8080}"
PY="$(command -v python3 || command -v python)" || { echo "Python 3 is required" >&2; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# Relative names from here on: Git Bash on Windows rewrites a /tmp path handed to a native
# program, but not one embedded in an argument like curl's "file=@/tmp/...".
cd "$WORK"

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
# Prints one field of a JSON file: json <file> <key>
json() { "$PY" -c 'import json, sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$1" "$2"; }

step "Waiting for $BASE"
curl -fsS -o /dev/null --retry 30 --retry-delay 2 --retry-all-errors "$BASE/api/meta" \
  || fail "the API never answered through $BASE"

if [ "${SMOKE_SKIP_SPA:-}" != 1 ]; then
  step "The web app is served"
  curl -fsS "$BASE/" | grep -q '<div id="root">' || fail "GET / did not return the web app"
fi

step "Register and sign in"
EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
CREDENTIALS="{\"email\":\"$EMAIL\",\"password\":\"Smoke-test-pass-1\"}"
curl -fsS -o /dev/null -H 'Content-Type: application/json' -d "$CREDENTIALS" "$BASE/api/auth/register"
curl -fsS -o "login.json" -H 'Content-Type: application/json' -d "$CREDENTIALS" "$BASE/api/auth/login"
AUTH="Authorization: Bearer $(json "login.json" accessToken)"

step "Create a note"
curl -fsS -o "note.json" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"type":"Text","title":"smoke test"}' "$BASE/api/notes"
NOTE="$(json "note.json" id)"

step "Attach a photo of about 3 MB"
# Random pixels don't compress, so a 1024x1024 PNG lands near 3 MB: well past nginx's 1 MB default,
# well inside the API's 10 MB cap. No image tooling needed beyond the standard library.
"$PY" - "photo.png" <<'PYTHON'
import os, struct, sys, zlib
w = h = 1024
raw = b"".join(b"\x00" + os.urandom(w * 3) for _ in range(h))
chunk = lambda kind, data: struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
header = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
with open(sys.argv[1], "wb") as f:
    f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 1)) + chunk(b"IEND", b""))
PYTHON
SIZE="$(wc -c < "photo.png" | tr -d ' ')"
CODE="$(curl -sS -o "media.json" -w '%{http_code}' -H "$AUTH" \
  -F "file=@photo.png;type=image/png" "$BASE/api/notes/$NOTE/media")"
[ "$CODE" = 201 ] || fail "uploading a $SIZE-byte photo returned HTTP $CODE (413 from a proxy means its body limit is too low): $(head -c 300 "media.json")"
echo "uploaded $SIZE bytes"
MEDIA="$(json "media.json" id)"

step "Read it back"
TYPE="$(curl -fsS -o /dev/null -w '%{content_type}' -H "$AUTH" "$BASE/api/notes/$NOTE/media/$MEDIA?size=preview")"
[ "$TYPE" = image/jpeg ] || fail "the preview came back as '$TYPE', expected image/jpeg"

printf '\nSmoke test passed.\n'

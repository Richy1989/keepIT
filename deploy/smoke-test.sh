#!/usr/bin/env bash
# Smoke-tests a running keepIT deployment end to end, through its public port: the web app is
# served, a signed-in user can attach a multi-megabyte photo and read it back, and they can export
# their account and import it again. The body sizes are the point — they only work if every proxy
# in front of the API lets a body that size through, which is exactly what nginx's 1 MB default did
# not, while every test that talks to the API directly passed. An import is by far the largest body
# the app accepts, so it gets the same treatment.
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

step "Export the account through the proxy"
CODE="$(curl -sS -o "export.zip" -w '%{http_code}' -H "$AUTH" "$BASE/api/export")"
[ "$CODE" = 200 ] || fail "GET /api/export returned HTTP $CODE: $(head -c 300 "export.zip")"
"$PY" - "export.zip" <<'PYTHON'
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    if z.testzip() is not None:
        raise SystemExit("the export is a corrupt zip (a proxy may have mangled the stream)")
    names = z.namelist()
    if "keepit-export.json" not in names:
        raise SystemExit("no manifest in the export: %s" % names)
    manifest = json.loads(z.read("keepit-export.json"))
    if len(manifest["notes"]) != 1:
        raise SystemExit("expected 1 note in the manifest, got %d" % len(manifest["notes"]))
    if not any(n.startswith("media/") for n in names):
        raise SystemExit("the photo is missing from the export: %s" % names)
    print("exported %d note and %d image" % (len(manifest["notes"]), len(names) - 1))
PYTHON

step "Import it back"
CODE="$(curl -sS -o "import.json" -w '%{http_code}' -H "$AUTH" \
  -F "file=@export.zip;type=application/zip" "$BASE/api/import")"
[ "$CODE" = 200 ] || fail "POST /api/import returned HTTP $CODE (413 from a proxy means its body limit is too low for an archive): $(head -c 300 "import.json")"
[ "$(json "import.json" notesImported)" = 1 ] || fail "the import reported $(cat "import.json")"
[ "$(json "import.json" imagesImported)" = 1 ] || fail "the photo did not survive the round trip: $(cat "import.json")"
echo "round trip restored the note and its photo"

step "A 20 MB import body reaches the API rather than a proxy limit"
# Past the 12 MB a photo upload is allowed, well inside the import cap. Deliberately not a real
# archive: the API answers 400, and that is the assertion — a 413 here is a proxy refusing the
# body, which is the failure mode this script exists to catch.
"$PY" -c 'import os, sys; open(sys.argv[1], "wb").write(os.urandom(20 * 1024 * 1024))' "big.bin"
CODE="$(curl -sS -o "big.json" -w '%{http_code}' -H "$AUTH" \
  -F "file=@big.bin;type=application/zip" "$BASE/api/import")"
[ "$CODE" = 400 ] || fail "a 20 MB import body returned HTTP $CODE, expected 400 from the API (413 means a proxy's body limit is too low for an import)"
echo "a 20 MB body reached the API"

printf '\nSmoke test passed.\n'

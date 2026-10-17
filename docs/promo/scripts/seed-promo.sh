#!/usr/bin/env bash
# Adds what the promo video shows on top of the screenshots' demo account: faces on a shared note.
#
# Run it right after scripts/screenshots/seed-demo.sh, on the same fresh server. It gives Jonas a
# profile picture, and adds Mia (with one) and Luca (without, so his face shows an initial), then
# shares Anna's "Lisbon trip" with both. Its card then shows three overlapping faces.
#
# Usage:
#   ./docs/promo/scripts/seed-promo.sh <base-url> [work-dir]
#
# Requires: curl, jq, ImageMagick 7 (magick)
set -euo pipefail

[[ $# -ge 1 ]] || { echo "usage: $0 <base-url> [work-dir]" >&2; exit 2; }
BASE="${1%/}"
ASSETS="$(cd "$(dirname "$0")/../../../scripts/seed-assets" && pwd)"
if [[ $# -ge 2 ]]; then
    WORK="$2"; mkdir -p "$WORK"
else
    WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fi

# The demo account's password, as in seed-demo.sh.
PASSWORD='Demo1234#demo'

api() { # api TOKEN METHOD PATH [JSON]  -> body on stdout, fails on a non-2xx
    local token="$1" method="$2" path="$3" body="${4:-}"
    local args=(-sS --fail-with-body -X "$method" "$BASE$path" -H 'Content-Type: application/json')
    [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
    [[ -n "$body" ]] && args+=(--data "$body")
    curl "${args[@]}"
    sleep 0.15
}
upload() { # upload TOKEN PATH FILE
    curl -sS --fail-with-body -o /dev/null -X POST "$BASE$2" -H "Authorization: Bearer $1" -F "file=@$3"
    sleep 0.15
}
credentials() { jq -n --arg e "$1" --arg p "$PASSWORD" '{email:$e,password:$p}'; }
login() { api '' POST /api/auth/login "$(credentials "$1")" | jq -r .accessToken; }
register() { # register EMAIL NAME -> access token
    api '' POST /api/auth/register "$(credentials "$1" | jq --arg d "$2" '. + {displayName:$d}')" | jq -r .accessToken
}

A="$(login anna@example.com)"
J="$(login jonas@example.com)"
M="$(register mia@example.com 'Mia Novak')"
L="$(register luca@example.com 'Luca Romano')"

# Pictures cut from the seed photos, so no stranger's face ends up in a video: the bird and a
# glass from the terrace.
magick "$ASSETS/hummingbird.jpg" -gravity center -crop 45%x60%+0+0 +repage -resize 512x512^ -extent 512x512 "$WORK/jonas.jpg"
magick "$ASSETS/aperitivo-terrace.jpg" -gravity center -crop 40%x55%+0+0 +repage -resize 512x512^ -extent 512x512 "$WORK/mia.jpg"
upload "$J" /api/settings/uploadProfileImage "$WORK/jonas.jpg"
upload "$M" /api/settings/uploadProfileImage "$WORK/mia.jpg"

lisbon="$(api "$A" GET /api/notes | jq -r '.[] | select(.title == "Lisbon trip") | .id')"
for pair in "mia@example.com:$M" "luca@example.com:$L"; do
    email="${pair%%:*}" token="${pair#*:}"
    api "$A" POST "/api/notes/$lisbon/shares" "$(jq -n --arg e "$email" '{email:$e,role:"Editor"}')" >/dev/null
    invite="$(api "$token" GET /api/notifications | jq -r '.[0].id')"
    api "$token" POST "/api/notifications/$invite/respond" '{"accept":true}' >/dev/null
done

echo "Lisbon trip is shared with: $(api "$A" GET "/api/notes/$lisbon" | jq -r '[.collaborators[].email] | join(", ")')"

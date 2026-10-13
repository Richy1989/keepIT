#!/usr/bin/env bash
# Seeds the demo account every screenshot in the repo is taken from. See README.md in this folder.
#
# Drives a running keepIT over its REST API, like seed-dev-data.sh, but where that one fills a dev
# database with a lot of everything, this one makes the few notes the screenshots show: Anna
# Berger's account with a profile picture, five lists with an emoji each, and ten notes that
# between them have photos, a voice note, checklists, a reminder and a share in each direction
# (Jonas Keller is the second account).
#
# It needs a FRESH server: it registers both accounts and stops if either exists. Notes are created
# oldest first, because the grid sorts by pin and then by last change, so the order below is the
# order on screen read backwards.
#
# Usage:
#   ./scripts/screenshots/seed-demo.sh <base-url> [work-dir]
#
# The profile picture, the voice note and a crop of one photo are made on the way and written to
# work-dir (a temporary folder, removed afterwards, when none is given).
#
# Requires: curl, jq, ImageMagick 7 (magick), ffmpeg, GNU date (Linux, Git Bash, WSL)
set -euo pipefail

[[ $# -ge 1 ]] || { echo "usage: $0 <base-url> [work-dir]" >&2; exit 2; }
BASE="${1%/}"
ASSETS="$(cd "$(dirname "$0")/../seed-assets" && pwd)"
if [[ $# -ge 2 ]]; then
    WORK="$2"; mkdir -p "$WORK"
else
    WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fi

# The screenshot scripts and the README sign in with these.
PASSWORD='Demo1234#demo'
ANNA='anna@example.com'; JONAS='jonas@example.com'

# The short sleeps keep the run well under the API's rate limit (120 requests a minute).
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
register() { # register EMAIL NAME -> access token
    api '' POST /api/auth/register "$(jq -n --arg e "$1" --arg p "$PASSWORD" --arg d "$2" \
        '{email:$e,password:$p,displayName:$d}')" | jq -r .accessToken
}
text_note() { # text_note TOKEN TITLE COLOR LISTS_JSON BODY -> id
    api "$1" POST /api/notes "$(jq -n --arg t "$2" --arg c "$3" --argjson l "$4" --arg b "$5" \
        '{type:"Text",title:$t,body:$b,color:$c,listIds:$l}')" | jq -r .id
}
check_note() { # check_note TOKEN TITLE COLOR LISTS_JSON "text|done"... -> id
    local token="$1" title="$2" color="$3" lists="$4"; shift 4
    local items='[]' order=0 pair
    for pair in "$@"; do
        items="$(jq --arg x "${pair%|*}" --argjson d "${pair##*|}" --argjson o "$order" \
            '. + [{text:$x,isChecked:$d,order:$o}]' <<<"$items")"
        order=$((order + 1))
    done
    api "$token" POST /api/notes "$(jq -n --arg t "$title" --arg c "$color" --argjson l "$lists" --argjson i "$items" \
        '{type:"Checklist",title:$t,color:$c,checklistItems:$i,listIds:$l}')" | jq -r .id
}
pin() { api "$1" PATCH "/api/notes/$2/state" '{"isPinned":true}' >/dev/null; }
share() { # share OWNER_TOKEN NOTE_ID GRANTEE_EMAIL GRANTEE_TOKEN  (as Editor, and accepted)
    api "$1" POST "/api/notes/$2/shares" "$(jq -n --arg e "$3" '{email:$e,role:"Editor"}')" >/dev/null
    local invite
    invite="$(api "$4" GET /api/notifications | jq -r '.[0].id')"
    api "$4" POST "/api/notifications/$invite/respond" '{"accept":true}' >/dev/null
}

A="$(register "$ANNA" 'Anna Berger')"
J="$(register "$JONAS" 'Jonas Keller')"

# Anna's picture: the sculpture from the seed photos, cropped to head and shoulders.
magick "$ASSETS/museum-sculpture.jpg" -crop 480x480+895+1295 +repage -resize 512x512 "$WORK/avatar.jpg"
upload "$A" /api/settings/uploadProfileImage "$WORK/avatar.jpg"

# A nine-second voice note in the format the app records: mono, 22.05 kHz, AAC in m4a.
ffmpeg -loglevel error -y -f lavfi -i 'sine=frequency=660:duration=9' -ac 1 -ar 22050 -c:a aac -b:a 32k "$WORK/voice.m4a"

# A note's card shows its first photo at the photo's own shape, so a portrait one fills the phone's
# first screen by itself. The pinned note at the top therefore leads with a 3:2 crop.
magick "$ASSETS/coastal-town.jpg" -crop 1707x1138+0+700 +repage -quality 92 "$WORK/coastal-landscape.jpg"

declare -A L
add_list() { L["$1"]="$(api "$A" POST /api/lists "$(jq -n --arg n "$1" --arg c "$2" --arg i "$3" '{name:$n,color:$c,icon:$i}')" | jq -r .id)"; }
add_list Home amber '🏠'
add_list Personal sage '🌱'
add_list Recipes coral '🍝'
add_list Travel teal '✈️'
add_list Work indigo '💼'
ids() { local out=() n; for n in "$@"; do out+=("${L[$n]}"); done; jq -n '$ARGS.positional' --args "${out[@]}"; }

# ---- Others, bottom of the grid first ----
hike="$(check_note "$J" 'Sunday hike' sky '[]' 'Thermos|false' 'Trail snacks|false' 'Rain jackets|false' 'Check the weather|true')"
share "$J" "$hike" "$ANNA" "$A"
api "$A" PUT "/api/notes/$hike/lists" "$(jq -n --argjson l "$(ids Personal)" '{listIds:$l}')" >/dev/null

text_note "$A" 'Offsite agenda' rose "$(ids Work)" $'**Mon:** travel, dinner by the lake\n**Tue:** roadmap, then the retro\n**Wed:** workshops, fly home' >/dev/null

museum="$(text_note "$A" 'Museum day' indigo "$(ids Personal)" 'Room 12, late afternoon, light coming in sideways. Everyone walks straight past this one.')"
upload "$A" "/api/notes/$museum/media" "$ASSETS/museum-sculpture.jpg"

check_note "$A" 'Summer BBQ' sage "$(ids Home)" 'Halloumi|false' 'Corn on the cob|false' 'Lemonade|false' 'Charcoal|false' 'Invite the neighbours|true' >/dev/null

text_note "$A" 'Carbonara for four' coral "$(ids Recipes)" $'- 400 g spaghetti\n- 150 g guanciale\n- 4 egg yolks and 1 whole egg\n- Pecorino and black pepper\n\nNo cream. **Ever.**' >/dev/null

garden="$(text_note "$A" 'Garden visitors' sage "$(ids Home)" 'Same branch every morning around eight. Refill the feeder twice a week.')"
upload "$A" "/api/notes/$garden/media" "$ASSETS/hummingbird.jpg"
upload "$A" "/api/notes/$garden/media" "$WORK/voice.m4a"

# ---- Pinned, last of the row first ----
lisbon="$(check_note "$A" 'Lisbon trip' teal "$(ids Travel)" 'Tram 28 tickets|false' 'Pastéis de Belém|false' 'Day trip to Sintra|false' 'Book flights|true' 'Hotel in Alfama|true')"
share "$A" "$lisbon" "$JONAS" "$J"
pin "$A" "$lisbon"

book="$(text_note "$A" 'Book club' violet "$(ids Personal)" 'Finish *The Remains of the Day* before Thursday. Bring the good biscuits.')"
# The coming Thursday at 19:00 in Vienna, and never today: the note says "before Thursday".
thursday="$(TZ=Europe/Vienna date -d tomorrow +%F)"
until [[ "$(date -d "$thursday" +%u)" == 4 ]]; do thursday="$(date -d "$thursday +1 day" +%F)"; done
remind_at="$(date -u -d "@$(TZ=Europe/Vienna date -d "$thursday 19:00" +%s)" +%FT%TZ)"
api "$A" PUT "/api/notes/$book/reminder" "$(jq -n --arg t "$remind_at" '{remindAtUtc:$t,recurrence:"None",timeZone:"Europe/Vienna"}')" >/dev/null
pin "$A" "$book"

groceries="$(check_note "$A" 'Groceries' amber "$(ids Home)" 'Oat milk|false' 'Sourdough bread|false' 'Tomatoes|false' 'Coffee beans|false' 'Basil|true' 'Parmesan|true')"
pin "$A" "$groceries"

puglia="$(text_note "$A" 'Weekend in Puglia' sky "$(ids Travel)" $'Vieste at sunset, then a spritz on the terrace.\n\n**Saturday:** boat to the sea caves\n**Sunday:** Polignano a Mare')"
upload "$A" "/api/notes/$puglia/media" "$WORK/coastal-landscape.jpg"
upload "$A" "/api/notes/$puglia/media" "$ASSETS/aperitivo-terrace.jpg"
pin "$A" "$puglia"

echo "seeded: $(api "$A" GET /api/notes | jq -r '[.[] | (if .isPinned then "*" else " " end) + .title] | join(" | ")')"
api "$A" GET /api/lists | jq -r '[.[] | "\(.icon // "-") \(.name)"] | join(", ")'
echo "sign in as $ANNA / $PASSWORD"

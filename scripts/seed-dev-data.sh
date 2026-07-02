#!/usr/bin/env bash
# Seeds the keepIT dev database with a test user and lots of sample notes/lists.
#
# Drives the running backend over its REST API (http://localhost:5025 by default), so it works
# against either the SQLite dev fallback or Postgres, and the password is hashed by ASP.NET Core
# Identity exactly like a real registration — meaning you can actually log in afterwards.
#
# Designed to be re-run after deleting the dev database during development:
#   1. Start the backend:  dotnet run --project keepIT/keepITCore
#   2. Run this script.
#
# The user is registered if missing, or logged in if it already exists (or if registration is
# disabled on the server). Pass --reset to delete the user's existing notes and lists first, for a
# clean, known data set instead of piling on duplicates.
#
# Usage:
#   ./scripts/seed-dev-data.sh [--reset] [--domain URL|HOST] [--user EMAIL] [--password P] [--display-name N]
#
# --domain takes a full URL (http://localhost:5025) or a bare host/domain (keepit.example.com). A
# bare host is assumed https:// — except localhost / an IP, which get http://. Defaults to the local
# dev backend. --base-url and --email are kept as aliases for --domain and --user.
#
# Requires: curl, jq
set -euo pipefail

DOMAIN="http://localhost:5025"
EMAIL="test@test.com"
PASSWORD="Test1234#1234"
DISPLAY_NAME="Test User"
RESET=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --reset)             RESET=1; shift ;;
        --domain|--base-url) DOMAIN="$2"; shift 2 ;;
        --user|--email)      EMAIL="$2"; shift 2 ;;
        --password)          PASSWORD="$2"; shift 2 ;;
        --display-name)      DISPLAY_NAME="$2"; shift 2 ;;
        -h|--help)           grep '^#' "$0" | sed 's/^# \{0,1\}//' | grep -v '!/usr/bin/env'; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Error: '$tool' is required but not installed." >&2; exit 1; }
done

# Turns a --domain value into a base URL: a full URL is used as-is; a bare host gets https:// (or
# http:// for localhost / an IPv4). The trailing slash is trimmed either way.
resolve_base_url() {
    local v="$1"
    if [[ "$v" != *"://"* ]]; then
        if [[ "$v" =~ ^(localhost|127\.0\.0\.1|([0-9]{1,3}\.){3}[0-9]{1,3})(:[0-9]+)?$ ]]; then
            v="http://$v"
        else
            v="https://$v"
        fi
    fi
    echo "${v%/}"
}

BASE_URL="$(resolve_base_url "$DOMAIN")"
TOKEN=""

# Colors (skipped when not a terminal or NO_COLOR is set).
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
    C_CYAN=$'\e[36m'; C_GREEN=$'\e[32m'; C_YELLOW=$'\e[33m'; C_RESET=$'\e[0m'
else
    C_CYAN=""; C_GREEN=""; C_YELLOW=""; C_RESET=""
fi

# ---- HTTP helper: sets _CODE and _BODY from the response -----------------------------------------
_CODE=""; _BODY=""
request() {
    # request METHOD PATH [BODY_JSON] [anon]
    local method="$1" path="$2" body="${3:-}" anon="${4:-}"
    local args=(-sS -X "$method" "${BASE_URL}${path}" -H "Content-Type: application/json" -w $'\n%{http_code}')
    [[ -z "$anon" && -n "$TOKEN" ]] && args+=(-H "Authorization: Bearer ${TOKEN}")
    [[ -n "$body" ]] && args+=(--data "$body")

    local out
    if ! out="$(curl "${args[@]}" 2>/dev/null)"; then
        _CODE="000"; _BODY=""; return 1
    fi
    _CODE="${out##*$'\n'}"
    _BODY="${out%$'\n'*}"
    return 0
}

# Comma-separated list names → JSON array of their ids (e.g. "Work,Ideas" → ["<id>","<id>"]).
declare -A LIST
names_to_ids() {
    local csv="$1"; local -a ids=()
    if [[ -n "$csv" ]]; then
        local IFS=','; local n
        for n in $csv; do ids+=("${LIST[$n]}"); done
    fi
    if ((${#ids[@]})); then jq -n '$ARGS.positional' --args "${ids[@]}"; else echo '[]'; fi
}

# ---- 1. Confirm the backend is up ---------------------------------------------------------------
echo "${C_CYAN}→ Checking backend at ${BASE_URL} ...${C_RESET}"
if ! request POST "/api/auth/refresh" "" anon; then
    echo "Could not reach the backend at ${BASE_URL}. Start it with: dotnet run --project keepIT/keepITCore" >&2
    exit 1
fi
# Any HTTP response (e.g. 401 for no refresh cookie) means the server is up.

# ---- 2. Register the test user, or log in if it already exists ----------------------------------
echo "${C_CYAN}→ Ensuring test user ${EMAIL} ...${C_RESET}"
reg_body="$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" --arg d "$DISPLAY_NAME" \
    '{email:$e, password:$p, displayName:$d}')"
request POST "/api/auth/register" "$reg_body" anon
if [[ "$_CODE" == "200" || "$_CODE" == "201" ]]; then
    echo "${C_GREEN}  registered new account${C_RESET}"
elif [[ "$_CODE" == "409" || "$_CODE" == "403" ]]; then
    # 409 = the account already exists; 403 = registration is disabled on this server. Either way
    # the account should already be there, so log in with the supplied credentials.
    login_body="$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e, password:$p}')"
    request POST "/api/auth/login" "$login_body" anon
    [[ "$_CODE" == "200" ]] || { echo "Login failed (HTTP $_CODE): $_BODY. If registration is disabled, create the account first, then pass --user/--password." >&2; exit 1; }
    echo "${C_GREEN}  signed in to existing account${C_RESET}"
else
    echo "Register failed (HTTP $_CODE): $_BODY" >&2; exit 1
fi

TOKEN="$(jq -r '.accessToken // empty' <<<"$_BODY")"
[[ -n "$TOKEN" ]] || { echo "No access token returned from auth." >&2; exit 1; }

# ---- 3. Optionally wipe the account's existing data ---------------------------------------------
if [[ "$RESET" == "1" ]]; then
    echo "${C_YELLOW}→ Resetting existing notes & lists ...${C_RESET}"
    declare -a note_ids=()
    for view in "" "?archived=true" "?trashed=true"; do
        request GET "/api/notes${view}"
        while IFS= read -r id; do [[ -n "$id" ]] && note_ids+=("$id"); done < <(jq -r '.[].id' <<<"$_BODY")
    done
    if ((${#note_ids[@]})); then
        while IFS= read -r id; do request DELETE "/api/notes/${id}"; done \
            < <(printf '%s\n' "${note_ids[@]}" | sort -u)
    fi
    request GET "/api/lists"
    while IFS= read -r id; do [[ -n "$id" ]] && request DELETE "/api/lists/${id}"; done \
        < <(jq -r '.[].id' <<<"$_BODY")
    echo "${C_YELLOW}  cleared ${#note_ids[@]} note(s)${C_RESET}"
fi

# ---- 4. Create lists ----------------------------------------------------------------------------
echo "${C_CYAN}→ Creating lists ...${C_RESET}"
add_list() {
    request POST "/api/lists" "$(jq -n --arg n "$1" --arg c "$2" '{name:$n, color:$c}')"
    LIST["$1"]="$(jq -r '.id' <<<"$_BODY")"
}
add_list "Work"     "sky"
add_list "Personal" "sage"
add_list "Shopping" "amber"
add_list "Ideas"    "violet"
add_list "Travel"   "teal"
add_list "Recipes"  "coral"
echo "${C_GREEN}  created ${#LIST[@]} lists${C_RESET}"

# ---- 5. Create notes ----------------------------------------------------------------------------
echo "${C_CYAN}→ Creating notes ...${C_RESET}"
declare -A NOTE_ID
note_count=0

# add_text TITLE COLOR LISTS_CSV BODY
add_text() {
    local title="$1" color="$2" lists="$3" body="$4"
    local payload
    payload="$(jq -n --arg t "$title" --arg b "$body" --arg c "$color" --argjson l "$(names_to_ids "$lists")" \
        '{type:"Text", title:$t, body:$b, color:$c, listIds:$l}')"
    request POST "/api/notes" "$payload"
    NOTE_ID["$title"]="$(jq -r '.id' <<<"$_BODY")"
    ((note_count++))
}

# add_check TITLE COLOR LISTS_CSV ITEM... where each ITEM is "text|true|false"
add_check() {
    local title="$1" color="$2" lists="$3"; shift 3
    local items='[]' order=0 pair text done bool
    for pair in "$@"; do
        text="${pair%|*}"; done="${pair##*|}"
        bool=false; [[ "$done" == "true" ]] && bool=true
        items="$(jq --arg x "$text" --argjson d "$bool" --argjson o "$order" \
            '. + [{text:$x, isChecked:$d, order:$o}]' <<<"$items")"
        ((order++))
    done
    local payload
    payload="$(jq -n --arg t "$title" --arg c "$color" --argjson l "$(names_to_ids "$lists")" --argjson items "$items" \
        '{type:"Checklist", title:$t, color:$c, checklistItems:$items, listIds:$l}')"
    request POST "/api/notes" "$payload"
    NOTE_ID["$title"]="$(jq -r '.id' <<<"$_BODY")"
    ((note_count++))
}

add_text "Welcome to keepIT"        "indigo"  ""             "This is your dev sandbox. Notes here were generated by scripts/seed-dev-data.sh. Re-run it with --reset for a clean slate."
add_text "Q3 roadmap"               "sky"     "Work"         "Ship sharing, then image notes, then real-time sync via SignalR. Cut scope ruthlessly."
add_text "Standup notes"            "default" "Work"         $'Yesterday: finished list filtering.\nToday: optimistic update rollback.\nBlockers: none.'
add_text "Bug: optimistic rollback" "rose"    "Work"         "When a note edit fails the card flashes old content for a frame. Investigate the TanStack Query onError rollback."
add_text "Refactor idea"            "violet"  "Work,Ideas"   "Extract the note card menu into its own component — pin/archive/trash/color all live inline right now."
add_text "Books to read"            "sage"    "Personal"     $'- The Pragmatic Programmer\n- Designing Data-Intensive Applications\n- A Philosophy of Software Design'
add_text "Gift ideas for Mum"       "mauve"   "Personal"     "Pottery class voucher? New gardening gloves. That cookbook she mentioned."
add_text "Apartment wifi password"  "amber"   "Personal"     $'Network: keepIT-guest\nPassword: changeme-please'
add_text "App idea: habit streaks"  "violet"  "Ideas"        "Minimal habit tracker, just streaks, no social. Could reuse keepIT's auth + notes backend."
add_text "Blog post drafts"         "indigo"  "Ideas"        $'1. Why DTOs are the source of truth\n2. SQLite as a zero-setup dev DB\n3. Optimistic UI without tears'
add_text "Tokyo trip"               "teal"    "Travel"       "7 days in spring. Book flights 3 months out. JR Pass. Stay in Shinjuku."
add_text "Packing reminders"        "sky"     "Travel"       "Adapter, charger, passport, meds. Don't overpack — laundry exists."
add_text "Carbonara (the real one)" "coral"   "Recipes"      "Guanciale, pecorino, egg yolks, black pepper. No cream. Ever."
add_text "Weeknight stir-fry"       "sage"    "Recipes"      "High heat, prep everything first, cook fast. Soy + garlic + ginger base."
add_text "Random thought"           "default" ""             "The best feature is the one you don't have to build because you cut the requirement."
add_text "Meeting follow-ups"       "amber"   "Work"         "Email design feedback to Sara. Schedule API contract review. Update the README status block."

add_check "Groceries"        "amber"  "Shopping"    "Milk|true" "Eggs|true" "Coffee beans|false" "Olive oil|false" "Spinach|false" "Parmesan|false"
add_check "Hardware store"   "coral"  "Shopping"    "Picture hooks|false" "AA batteries|false" "Light bulbs (E27)|true"
add_check "Release checklist" "sky"   "Work"        "Regenerate OpenAPI client|true" "Run migrations|true" "Smoke test auth flow|false" "Tag release|false" "Update changelog|false"
add_check "Trip todo"        "teal"   "Travel"      "Renew passport|true" "Book flights|false" "Travel insurance|false" "Hold mail|false"
add_check "Weekend chores"   "sage"   "Personal"    "Laundry|false" "Water plants|false" "Vacuum|false" "Meal prep|false"
add_check "Onboarding setup" "indigo" "Work,Ideas"  "Clone repo|true" "Install .NET 10 SDK|true" "Install Node 22+|true" "Copy .env.example|false" "Run seed script|false"

echo "${C_GREEN}  created ${note_count} notes${C_RESET}"

# ---- 6. Apply pin / archive / trash states so every view has content ----------------------------
echo "${C_CYAN}→ Setting pin / archive / trash states ...${C_RESET}"
set_state() {
    local id="${NOTE_ID[$1]:-}"
    [[ -n "$id" ]] && request PATCH "/api/notes/${id}/state" "$2"
}
set_state "Welcome to keepIT"       '{"isPinned":true}'
set_state "Q3 roadmap"              '{"isPinned":true}'
set_state "Release checklist"       '{"isPinned":true}'
set_state "Apartment wifi password" '{"isArchived":true}'
set_state "Hardware store"          '{"isArchived":true}'
set_state "Standup notes"           '{"isArchived":true}'
set_state "Random thought"          '{"isTrashed":true}'
set_state "Meeting follow-ups"      '{"isTrashed":true}'

# ---- Done ---------------------------------------------------------------------------------------
echo ""
echo "${C_GREEN}✓ Seed complete.${C_RESET}"
echo "  User:  ${EMAIL} / ${PASSWORD}"
echo "  Lists: ${#LIST[@]}   Notes: ${note_count} (some pinned / archived / trashed)"
echo "  Sign in at the frontend (http://localhost:5173) or via ${BASE_URL}."

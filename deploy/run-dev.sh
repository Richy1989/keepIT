#!/usr/bin/env bash
# Builds and runs the keepIT backend + web frontend together, with test data seeded.
#
# One terminal, both halves of the stack: the API on http://localhost:5025 and the Vite dev server
# on http://localhost:5173 (whose proxy forwards /api and the SignalR socket to the API, so the
# browser sees a single origin). Ctrl+C stops both.
#
# The API is built in the foreground first, so a compile error lands here rather than scrolling
# past inside a backgrounded process.
#
# Seeding is skipped when the dev account already has notes, so re-running this is cheap and never
# piles up duplicate notes. Use --reseed when you want the known data set back.
#
# Prerequisites: .NET 10 SDK, Node.js 22+, curl. Seeding additionally needs jq — without it the
# stack still comes up and only the seeding step is skipped.
#
# Usage:
#   ./deploy/run-dev.sh              # dev server, seed only if the dev account is empty
#   ./deploy/run-dev.sh --reseed     # wipe the dev account's notes/lists and seed a fresh set
#   ./deploy/run-dev.sh --no-seed    # skip seeding entirely
#   ./deploy/run-dev.sh --prod       # serve the production bundle (vite preview) instead
set -euo pipefail

PROD=0
RESEED=0
SEED=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        --prod)    PROD=1;   shift ;;
        --reseed)  RESEED=1; shift ;;
        --no-seed) SEED=0;   shift ;;
        -h|--help)
            grep '^#' "$0" | sed 's/^# \{0,1\}//' | grep -v '!/usr/bin/env'
            exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# Everything runs from the repo root (the parent of this script's folder).
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

API_URL="http://localhost:5025"
WEB_URL="http://localhost:5173"
PREVIEW_URL="http://localhost:4173"
READY_TIMEOUT=60
# Mirrors the defaults in scripts/seed-dev-data.sh — the account this script checks and seeds.
SEED_USER="test@test.com"
SEED_PASSWORD="Test1234#1234"
SEED_SCRIPT="$root/scripts/seed-dev-data.sh"

# Colors (skipped when not a terminal or NO_COLOR is set) — same convention as the seed script.
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
    C_CYAN=$'\e[36m'; C_GREEN=$'\e[32m'; C_YELLOW=$'\e[33m'; C_RESET=$'\e[0m'
else
    C_CYAN=""; C_GREEN=""; C_YELLOW=""; C_RESET=""
fi

API_PID=""
WEB_PID=""

# `dotnet run` and `npm` both spawn the real server as a child process, so killing only the
# launcher would orphan whatever is actually holding port 5025 / 5173. Take the children first.
stop_tree() {
    local pid="${1:-}"
    [[ -n "$pid" ]] || return 0
    kill -0 "$pid" 2>/dev/null || return 0
    pkill -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
}

cleanup() {
    trap - INT TERM EXIT
    echo ""
    echo "${C_CYAN}→ Stopping ...${C_RESET}"
    stop_tree "$WEB_PID"
    stop_tree "$API_PID"
    wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

# ---- 1. Prerequisites ----------------------------------------------------------------------------
for tool in dotnet npm curl; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "Error: '$tool' is required but not installed." >&2; exit 1; }
done

if [[ ! -d "$root/web/node_modules" ]]; then
    echo "${C_CYAN}→ Installing web dependencies (first run) ...${C_RESET}"
    ( cd "$root/web" && npm install )
fi

# ---- 2. Build the API, then start it -------------------------------------------------------------
echo "${C_CYAN}→ Building the API ...${C_RESET}"
dotnet build keepIT/keepITCore --nologo -v minimal

echo "${C_CYAN}→ Starting the API on ${API_URL} ...${C_RESET}"
dotnet run --project keepIT/keepITCore --no-build --launch-profile http &
API_PID=$!

# ---- 3. Wait until the API answers ---------------------------------------------------------------
# /api/meta is [AllowAnonymous], so a 200 means "up" without needing a session.
echo "${C_CYAN}→ Waiting for the API to come up ...${C_RESET}"
ready=0
for _ in $(seq 1 "$READY_TIMEOUT"); do
    if ! kill -0 "$API_PID" 2>/dev/null; then
        echo "The API exited before it became ready — see its output above." >&2
        exit 1
    fi
    if curl -fsS -o /dev/null "${API_URL}/api/meta" 2>/dev/null; then ready=1; break; fi
    sleep 1
done
if [[ "$ready" != "1" ]]; then
    echo "The API did not answer at ${API_URL} within ${READY_TIMEOUT}s." >&2
    exit 1
fi
echo "${C_GREEN}  API is up${C_RESET}"

# ---- 4. Make sure there is test data -------------------------------------------------------------
# A seeding failure must not take the stack down with it — the API and frontend are still usable.
run_seed() {
    bash "$SEED_SCRIPT" "$@" || echo "${C_YELLOW}  warning: seeding failed (the stack is still up)${C_RESET}"
}

seed_if_needed() {
    if [[ "$SEED" == "0" ]]; then
        echo "  seeding skipped (--no-seed)"
        return 0
    fi
    if ! command -v jq >/dev/null 2>&1; then
        echo "${C_YELLOW}  seeding skipped — 'jq' is not installed (the stack is still up)${C_RESET}"
        return 0
    fi
    if [[ "$RESEED" == "1" ]]; then
        echo "  --reseed: clearing and re-seeding the dev account"
        run_seed --reset
        return 0
    fi

    # Log in as the dev account to see whether it already holds notes. A failed login just means
    # the account doesn't exist yet, which is itself a reason to seed.
    local login token count
    login="$(curl -fsS -X POST "${API_URL}/api/auth/login" \
        -H 'Content-Type: application/json' \
        --data "$(jq -n --arg e "$SEED_USER" --arg p "$SEED_PASSWORD" '{email:$e, password:$p}')" \
        2>/dev/null || true)"
    token="$(jq -r '.accessToken // empty' <<<"${login:-}" 2>/dev/null || true)"

    if [[ -z "$token" ]]; then
        echo "  no dev account yet — seeding"
        run_seed
        return 0
    fi

    count="$(curl -fsS "${API_URL}/api/notes" -H "Authorization: Bearer ${token}" 2>/dev/null \
        | jq 'length' 2>/dev/null || echo 0)"
    if [[ "${count:-0}" -gt 0 ]]; then
        echo "${C_GREEN}  ${count} note(s) already seeded — skipping (--reseed for a clean set)${C_RESET}"
    else
        echo "  dev account is empty — seeding"
        run_seed
    fi
}

echo "${C_CYAN}→ Checking test data ...${C_RESET}"
seed_if_needed

# ---- 5. Start the frontend -----------------------------------------------------------------------
# `exec` in the subshell makes $! the npm process itself rather than the subshell wrapping it, so
# stop_tree can find npm's children.
if [[ "$PROD" == "1" ]]; then
    echo "${C_CYAN}→ Building the web bundle ...${C_RESET}"
    ( cd "$root/web" && npm run build )
    echo "${C_CYAN}→ Serving the production bundle on ${PREVIEW_URL} ...${C_RESET}"
    ( cd "$root/web" && exec npm run preview ) &
    WEB_PID=$!
    FRONTEND_URL="$PREVIEW_URL"
else
    echo "${C_CYAN}→ Starting the web dev server on ${WEB_URL} ...${C_RESET}"
    ( cd "$root/web" && exec npm run dev ) &
    WEB_PID=$!
    FRONTEND_URL="$WEB_URL"
fi

# ---- 6. Ready ------------------------------------------------------------------------------------
echo ""
echo "${C_GREEN}✓ keepIT is running.${C_RESET}"
echo "  Web:    ${FRONTEND_URL}"
echo "  API:    ${API_URL}"
echo "  Scalar: ${API_URL}/scalar/v1"
echo "  Sign in: ${SEED_USER} / ${SEED_PASSWORD}"
echo "  Ctrl+C stops both."
echo ""

# Return as soon as either half exits; the EXIT trap then stops the other.
wait -n 2>/dev/null || true
echo "${C_YELLOW}A process exited — shutting the other one down.${C_RESET}"

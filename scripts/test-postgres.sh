#!/usr/bin/env bash
# Runs the API tests on PostgreSQL, the database Docker Compose runs, instead of SQLite.
#
# Starts a throwaway Postgres 17 (the version docker-compose.yml runs) in Docker with its data in
# memory, runs the tests against it, and removes it again, also when a test fails or on Ctrl+C.
# Each test host creates an empty database of its own there, so the API builds every schema from
# the migrations, exactly as a new Compose install does on its first start. CI runs the suite the
# same way, in the "API on PostgreSQL" job.
#
# Prerequisites: .NET 10 SDK, Docker.
#
# Usage:
#   ./scripts/test-postgres.sh                                  # the whole suite
#   ./scripts/test-postgres.sh --filter ReminderScheduleTests   # anything else goes to dotnet test
#
# KEEPIT_TEST_POSTGRES_PORT picks another local port than 55432.
set -euo pipefail

# Everything runs from the repo root (the parent of this script's folder).
cd "$(dirname "$0")/.."

PORT="${KEEPIT_TEST_POSTGRES_PORT:-55432}"
NAME="keepit-test-postgres-$$"
PASSWORD="keepit-tests"

docker run --rm -d --name "$NAME" -p "127.0.0.1:$PORT:5432" \
    -e POSTGRES_PASSWORD="$PASSWORD" --tmpfs /var/lib/postgresql/data \
    postgres:17-alpine >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

# Over TCP: while the image initialises, Postgres listens on its socket only.
for _ in $(seq 1 60); do
    docker exec "$NAME" pg_isready -q -h 127.0.0.1 -U postgres && break
    sleep 0.5
done
docker exec "$NAME" pg_isready -q -h 127.0.0.1 -U postgres || {
    echo "Postgres didn't start; its log:" >&2
    docker logs "$NAME" >&2
    exit 1
}

KEEPIT_TEST_POSTGRES="Host=127.0.0.1;Port=$PORT;Database=postgres;Username=postgres;Password=$PASSWORD" \
    dotnet test keepIT/keepITCore.slnx "$@"

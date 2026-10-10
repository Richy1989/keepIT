#requires -Version 5
# Runs the API tests on PostgreSQL, the database Docker Compose runs, instead of SQLite.
#
# Starts a throwaway Postgres 17 (the version docker-compose.yml runs) in Docker with its data in
# memory, runs the tests against it, and removes it again, also when a test fails or on Ctrl+C.
# Each test host creates an empty database of its own there, so the API builds every schema from
# the migrations, exactly as a new Compose install does on its first start. CI runs the suite the
# same way, in the "API on PostgreSQL" job.
#
# Prerequisites: .NET 10 SDK, Docker Desktop.
#
# Usage:
#   ./scripts/test-postgres.ps1                                  # the whole suite
#   ./scripts/test-postgres.ps1 --filter ReminderScheduleTests   # anything else goes to dotnet test
#
# $env:KEEPIT_TEST_POSTGRES_PORT picks another local port than 55432.

$ErrorActionPreference = 'Stop'

# Everything runs from the repo root (the parent of this script's folder).
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

$port     = if ($env:KEEPIT_TEST_POSTGRES_PORT) { $env:KEEPIT_TEST_POSTGRES_PORT } else { '55432' }
$name     = "keepit-test-postgres-$PID"
$password = 'keepit-tests'

docker run --rm -d --name $name -p "127.0.0.1:${port}:5432" `
    -e "POSTGRES_PASSWORD=$password" --tmpfs /var/lib/postgresql/data `
    postgres:17-alpine | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not start Postgres in Docker.' }

$exitCode = 1
try {
    # Over TCP: while the image initialises, Postgres listens on its socket only.
    $ready = $false
    for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
        docker exec $name pg_isready -q -h 127.0.0.1 -U postgres
        $ready = $LASTEXITCODE -eq 0
        if (-not $ready) { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) {
        docker logs $name
        throw "Postgres didn't start; its log is above."
    }

    $env:KEEPIT_TEST_POSTGRES = "Host=127.0.0.1;Port=$port;Database=postgres;Username=postgres;Password=$password"
    dotnet test keepIT/keepITCore.slnx @args
    $exitCode = $LASTEXITCODE
}
finally {
    Remove-Item Env:KEEPIT_TEST_POSTGRES -ErrorAction SilentlyContinue
    docker rm -f $name 2>$null | Out-Null
}
exit $exitCode

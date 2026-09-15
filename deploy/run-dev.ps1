#requires -Version 5
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
# piles up duplicate notes. Use -Reseed when you want the known data set back.
#
# Prerequisites: .NET 10 SDK, Node.js 22+.
#
# Usage:
#   ./deploy/run-dev.ps1             # dev server, seed only if the dev account is empty
#   ./deploy/run-dev.ps1 -Reseed     # wipe the dev account's notes/lists and seed a fresh set
#   ./deploy/run-dev.ps1 -NoSeed     # skip seeding entirely
#   ./deploy/run-dev.ps1 -Prod       # serve the production bundle (vite preview) instead

param(
    [switch]$Prod,
    [switch]$Reseed,
    [switch]$NoSeed
)

$ErrorActionPreference = 'Stop'

# Everything runs from the repo root (the parent of this script's folder).
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

$ApiUrl              = 'http://localhost:5025'
$WebUrl              = 'http://localhost:5173'
$PreviewUrl          = 'http://localhost:4173'
$ReadyTimeoutSeconds = 60
# Mirrors the defaults in scripts/seed-dev-data.ps1 — the account this script checks and seeds.
$SeedUser     = 'test@test.com'
$SeedPassword = 'Test1234#1234'
$SeedScript   = Join-Path $root 'scripts/seed-dev-data.ps1'

$script:ApiProcess = $null
$script:WebProcess = $null

# `dotnet run` and `npm` both spawn the real server as a child (and npm is launched through
# cmd.exe, adding another level), so killing only the launcher would orphan whatever is actually
# holding port 5025 / 5173. Recurse to the leaves and kill from the bottom up.
function Stop-ProcessTree {
    param([int]$OwnerProcessId)
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$OwnerProcessId" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-ProcessTree -OwnerProcessId $_.ProcessId }
    Stop-Process -Id $OwnerProcessId -Force -ErrorAction SilentlyContinue
}

function Stop-Launched {
    param($Process)
    if (-not $Process) { return }
    try { if ($Process.HasExited) { return } } catch { return }
    Stop-ProcessTree -OwnerProcessId $Process.Id
}

function Stop-All {
    Write-Host ''
    Write-Host '→ Stopping ...' -ForegroundColor Cyan
    Stop-Launched $script:WebProcess
    Stop-Launched $script:ApiProcess
}

# ---- 1. Prerequisites ----------------------------------------------------------------------------
foreach ($tool in @('dotnet', 'npm')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "'$tool' is required but was not found on PATH."
    }
}

$webDir = Join-Path $root 'web'
if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
    Write-Host '→ Installing web dependencies (first run) ...' -ForegroundColor Cyan
    Push-Location $webDir
    try {
        npm install
        if ($LASTEXITCODE -ne 0) { throw "npm install failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
}

# npm on Windows is npm.cmd, which CreateProcess can't launch directly — go through cmd.exe so
# -NoNewWindow keeps its output in this console.
function Start-Npm {
    param([string]$Script)
    return Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm', 'run', $Script `
        -WorkingDirectory $webDir -NoNewWindow -PassThru
}

try {
    # ---- 2. Build the API, then start it ---------------------------------------------------------
    Write-Host '→ Building the API ...' -ForegroundColor Cyan
    dotnet build (Join-Path $root 'keepIT/keepITCore') --nologo -v minimal
    if ($LASTEXITCODE -ne 0) { throw "dotnet build failed with exit code $LASTEXITCODE" }

    Write-Host "→ Starting the API on $ApiUrl ..." -ForegroundColor Cyan
    $script:ApiProcess = Start-Process -FilePath 'dotnet' `
        -ArgumentList 'run', '--project', (Join-Path $root 'keepIT/keepITCore'), '--no-build', '--launch-profile', 'http' `
        -WorkingDirectory $root -NoNewWindow -PassThru

    # ---- 3. Wait until the API answers -----------------------------------------------------------
    # /api/meta is [AllowAnonymous], so a 200 means "up" without needing a session.
    Write-Host '→ Waiting for the API to come up ...' -ForegroundColor Cyan
    $ready = $false
    for ($i = 0; $i -lt $ReadyTimeoutSeconds; $i++) {
        if ($script:ApiProcess.HasExited) {
            throw 'The API exited before it became ready — see its output above.'
        }
        try {
            Invoke-RestMethod -Uri "$ApiUrl/api/meta" -TimeoutSec 3 -ErrorAction Stop | Out-Null
            $ready = $true
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $ready) { throw "The API did not answer at $ApiUrl within $ReadyTimeoutSeconds seconds." }
    Write-Host '  API is up' -ForegroundColor Green

    # ---- 4. Make sure there is test data ---------------------------------------------------------
    # A seeding failure must not take the stack down with it — the API and frontend are still usable.
    function Invoke-Seed {
        param([switch]$Reset)
        try {
            if ($Reset) { & $SeedScript -Reset } else { & $SeedScript }
        } catch {
            Write-Host "  warning: seeding failed ($($_.Exception.Message)) — the stack is still up" -ForegroundColor Yellow
        }
    }

    Write-Host '→ Checking test data ...' -ForegroundColor Cyan
    if ($NoSeed) {
        Write-Host '  seeding skipped (-NoSeed)'
    } elseif ($Reseed) {
        Write-Host '  -Reseed: clearing and re-seeding the dev account'
        Invoke-Seed -Reset
    } else {
        # Log in as the dev account to see whether it already holds notes. A failed login just means
        # the account doesn't exist yet, which is itself a reason to seed.
        $token = $null
        try {
            $body = @{ email = $SeedUser; password = $SeedPassword } | ConvertTo-Json
            $auth = Invoke-RestMethod -Uri "$ApiUrl/api/auth/login" -Method Post `
                -ContentType 'application/json' -Body $body -ErrorAction Stop
            $token = $auth.accessToken
        } catch {
            $token = $null
        }

        if (-not $token) {
            Write-Host '  no dev account yet — seeding'
            Invoke-Seed
        } else {
            # Assign the response first, then filter. Wrapping the call directly —
            # `@(Invoke-RestMethod ...)` — counts an empty JSON array as ONE item, because the
            # cmdlet emits the array without enumerating it; an empty account would then look like
            # it still held a note and would never be seeded. Filtering on .id counts real notes
            # whichever way the response comes back.
            $notes = @()
            try {
                $response = Invoke-RestMethod -Uri "$ApiUrl/api/notes" `
                    -Headers @{ Authorization = "Bearer $token" } -ErrorAction Stop
                $notes = @($response | Where-Object { $_ -and $_.id })
            } catch {
                $notes = @()
            }
            if ($notes.Count -gt 0) {
                Write-Host "  $($notes.Count) note(s) already seeded — skipping (-Reseed for a clean set)" -ForegroundColor Green
            } else {
                Write-Host '  dev account is empty — seeding'
                Invoke-Seed
            }
        }
    }

    # ---- 5. Start the frontend -------------------------------------------------------------------
    if ($Prod) {
        Write-Host '→ Building the web bundle ...' -ForegroundColor Cyan
        Push-Location $webDir
        try {
            npm run build
            if ($LASTEXITCODE -ne 0) { throw "npm run build failed with exit code $LASTEXITCODE" }
        } finally { Pop-Location }

        Write-Host "→ Serving the production bundle on $PreviewUrl ..." -ForegroundColor Cyan
        $script:WebProcess = Start-Npm -Script 'preview'
        $frontendUrl = $PreviewUrl
    } else {
        Write-Host "→ Starting the web dev server on $WebUrl ..." -ForegroundColor Cyan
        $script:WebProcess = Start-Npm -Script 'dev'
        $frontendUrl = $WebUrl
    }

    # ---- 6. Ready --------------------------------------------------------------------------------
    Write-Host ''
    Write-Host '✓ keepIT is running.' -ForegroundColor Green
    Write-Host "  Web:    $frontendUrl"
    Write-Host "  API:    $ApiUrl"
    Write-Host "  Scalar: $ApiUrl/scalar/v1"
    Write-Host "  Sign in: $SeedUser / $SeedPassword"
    Write-Host '  Ctrl+C stops both.'
    Write-Host ''

    # Return as soon as either half exits; the finally block then stops the other.
    while (-not ($script:ApiProcess.HasExited -or $script:WebProcess.HasExited)) {
        Start-Sleep -Seconds 1
    }
    Write-Host 'A process exited — shutting the other one down.' -ForegroundColor Yellow
} finally {
    Stop-All
}

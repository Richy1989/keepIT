#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Seeds the keepIT dev database with a test user and lots of sample notes/lists.

.DESCRIPTION
    Drives the running backend over its REST API (http://localhost:5025 by default), so it works
    against either the SQLite dev fallback or Postgres, and the user's password is hashed by ASP.NET
    Core Identity exactly like a real registration — meaning you can actually log in afterwards.

    Designed to be re-run after deleting the dev database during development:
      1. Start the backend (dotnet run --project keepIT/keepITCore).
      2. Run this script.

    The user is registered if missing, or logged in if it already exists. Pass -Reset to delete the
    user's existing notes and lists first, so re-running gives you a clean, known data set instead of
    piling duplicates on top.

.PARAMETER BaseUrl
    Backend base URL. Defaults to http://localhost:5025 (the dev "http" launch profile).

.PARAMETER Email
    Test account email. Defaults to test@test.com.

.PARAMETER Password
    Test account password. Defaults to Test1234#1234.

.PARAMETER Reset
    Delete the account's existing notes and lists before seeding (fresh, idempotent data set).

.EXAMPLE
    ./scripts/seed-dev-data.ps1

.EXAMPLE
    ./scripts/seed-dev-data.ps1 -Reset
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:5025",
    [string]$Email = "test@test.com",
    [string]$Password = "Test1234#1234",
    [string]$DisplayName = "Test User",
    [switch]$Reset
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
$script:Token = $null

function Invoke-Api {
    param(
        [Parameter(Mandatory)][ValidateSet("GET", "POST", "PUT", "PATCH", "DELETE")] [string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [object]$Body,
        [switch]$Anonymous
    )
    $headers = @{}
    if (-not $Anonymous -and $script:Token) { $headers["Authorization"] = "Bearer $script:Token" }

    $args = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ContentType = "application/json"
    }
    if ($PSBoundParameters.ContainsKey("Body") -and $null -ne $Body) {
        $args["Body"] = ($Body | ConvertTo-Json -Depth 8)
    }
    return Invoke-RestMethod @args
}

function Get-HttpStatus {
    param($ErrorRecord)
    $resp = $ErrorRecord.Exception.Response
    if ($resp -and $resp.StatusCode) { return [int]$resp.StatusCode }
    return $null
}

# ---- 1. Confirm the backend is up ---------------------------------------------------------------
Write-Host "→ Checking backend at $BaseUrl ..." -ForegroundColor Cyan
try {
    Invoke-Api -Method POST -Path "/api/auth/refresh" -Anonymous | Out-Null
} catch {
    $status = Get-HttpStatus $_
    if ($null -eq $status) {
        Write-Error "Could not reach the backend at $BaseUrl. Start it with: dotnet run --project keepIT/keepITCore"
        exit 1
    }
    # 401 (no refresh cookie) is the expected, healthy response here — the server is up.
}

# ---- 2. Register the test user, or log in if it already exists ----------------------------------
Write-Host "→ Ensuring test user $Email ..." -ForegroundColor Cyan
$auth = $null
try {
    $auth = Invoke-Api -Method POST -Path "/api/auth/register" -Anonymous -Body @{
        email       = $Email
        password    = $Password
        displayName = $DisplayName
    }
    Write-Host "  registered new account" -ForegroundColor Green
} catch {
    if ((Get-HttpStatus $_) -eq 409) {
        $auth = Invoke-Api -Method POST -Path "/api/auth/login" -Anonymous -Body @{
            email    = $Email
            password = $Password
        }
        Write-Host "  account exists — logged in" -ForegroundColor Green
    } else {
        throw
    }
}
$script:Token = $auth.accessToken
if (-not $script:Token) { Write-Error "No access token returned from auth."; exit 1 }

# ---- 3. Optionally wipe the account's existing data ---------------------------------------------
if ($Reset) {
    Write-Host "→ Resetting existing notes & lists ..." -ForegroundColor Yellow
    $existing = @()
    foreach ($view in @("", "?archived=true", "?trashed=true")) {
        $existing += Invoke-Api -Method GET -Path "/api/notes$view"
    }
    foreach ($n in ($existing | Sort-Object id -Unique)) {
        Invoke-Api -Method DELETE -Path "/api/notes/$($n.id)" | Out-Null
    }
    foreach ($l in (Invoke-Api -Method GET -Path "/api/lists")) {
        Invoke-Api -Method DELETE -Path "/api/lists/$($l.id)" | Out-Null
    }
    Write-Host "  cleared $($existing.Count) note(s)" -ForegroundColor Yellow
}

# ---- 4. Create lists ----------------------------------------------------------------------------
Write-Host "→ Creating lists ..." -ForegroundColor Cyan
$listSpecs = @(
    @{ name = "Work";      color = "sky" }
    @{ name = "Personal";  color = "sage" }
    @{ name = "Shopping";  color = "amber" }
    @{ name = "Ideas";     color = "violet" }
    @{ name = "Travel";    color = "teal" }
    @{ name = "Recipes";   color = "coral" }
)
$lists = @{}
foreach ($spec in $listSpecs) {
    $created = Invoke-Api -Method POST -Path "/api/lists" -Body $spec
    $lists[$spec.name] = $created.id
}
Write-Host "  created $($lists.Count) lists" -ForegroundColor Green

# ---- 5. Create notes ----------------------------------------------------------------------------
Write-Host "→ Creating notes ..." -ForegroundColor Cyan

# Text-note seed data: title / body / color key / list names it belongs to.
$textNotes = @(
    @{ t = "Welcome to keepIT";        c = "indigo"; lists = @();             b = "This is your dev sandbox. Notes here were generated by scripts/seed-dev-data.ps1. Re-run it with -Reset for a clean slate." }
    @{ t = "Q3 roadmap";               c = "sky";    lists = @("Work");       b = "Ship sharing, then image notes, then real-time sync via SignalR. Cut scope ruthlessly." }
    @{ t = "Standup notes";            c = "default";lists = @("Work");       b = "Yesterday: finished list filtering.`nToday: optimistic update rollback.`nBlockers: none." }
    @{ t = "Bug: optimistic rollback"; c = "rose";   lists = @("Work");       b = "When a note edit fails the card flashes old content for a frame. Investigate the TanStack Query onError rollback." }
    @{ t = "Refactor idea";            c = "violet"; lists = @("Work","Ideas"); b = "Extract the note card menu into its own component — pin/archive/trash/color all live inline right now." }
    @{ t = "Books to read";            c = "sage";   lists = @("Personal");   b = "- The Pragmatic Programmer`n- Designing Data-Intensive Applications`n- A Philosophy of Software Design" }
    @{ t = "Gift ideas for Mum";       c = "mauve";  lists = @("Personal");   b = "Pottery class voucher? New gardening gloves. That cookbook she mentioned." }
    @{ t = "Apartment wifi password";  c = "amber";  lists = @("Personal");   b = "Network: keepIT-guest`nPassword: changeme-please" }
    @{ t = "App idea: habit streaks";  c = "violet"; lists = @("Ideas");      b = "Minimal habit tracker, just streaks, no social. Could reuse keepIT's auth + notes backend." }
    @{ t = "Blog post drafts";         c = "indigo"; lists = @("Ideas");      b = "1. Why DTOs are the source of truth`n2. SQLite as a zero-setup dev DB`n3. Optimistic UI without tears" }
    @{ t = "Tokyo trip";               c = "teal";   lists = @("Travel");     b = "7 days in spring. Book flights 3 months out. JR Pass. Stay in Shinjuku." }
    @{ t = "Packing reminders";        c = "sky";    lists = @("Travel");     b = "Adapter, charger, passport, meds. Don't overpack — laundry exists." }
    @{ t = "Carbonara (the real one)"; c = "coral";  lists = @("Recipes");    b = "Guanciale, pecorino, egg yolks, black pepper. No cream. Ever." }
    @{ t = "Weeknight stir-fry";       c = "sage";   lists = @("Recipes");    b = "High heat, prep everything first, cook fast. Soy + garlic + ginger base." }
    @{ t = "Random thought";           c = "default";lists = @();             b = "The best feature is the one you don't have to build because you cut the requirement." }
    @{ t = "Meeting follow-ups";       c = "amber";  lists = @("Work");       b = "Email design feedback to Sara. Schedule API contract review. Update the README status block." }
)

# Checklist-note seed data: title / color / list names / items (text + checked flag).
$checklistNotes = @(
    @{ t = "Groceries"; c = "amber"; lists = @("Shopping"); items = @(
        @{ x = "Milk"; done = $true }, @{ x = "Eggs"; done = $true }, @{ x = "Coffee beans"; done = $false },
        @{ x = "Olive oil"; done = $false }, @{ x = "Spinach"; done = $false }, @{ x = "Parmesan"; done = $false }) }
    @{ t = "Hardware store"; c = "coral"; lists = @("Shopping"); items = @(
        @{ x = "Picture hooks"; done = $false }, @{ x = "AA batteries"; done = $false }, @{ x = "Light bulbs (E27)"; done = $true }) }
    @{ t = "Release checklist"; c = "sky"; lists = @("Work"); items = @(
        @{ x = "Regenerate OpenAPI client"; done = $true }, @{ x = "Run migrations"; done = $true },
        @{ x = "Smoke test auth flow"; done = $false }, @{ x = "Tag release"; done = $false }, @{ x = "Update changelog"; done = $false }) }
    @{ t = "Trip todo"; c = "teal"; lists = @("Travel"); items = @(
        @{ x = "Renew passport"; done = $true }, @{ x = "Book flights"; done = $false },
        @{ x = "Travel insurance"; done = $false }, @{ x = "Hold mail"; done = $false }) }
    @{ t = "Weekend chores"; c = "sage"; lists = @("Personal"); items = @(
        @{ x = "Laundry"; done = $false }, @{ x = "Water plants"; done = $false },
        @{ x = "Vacuum"; done = $false }, @{ x = "Meal prep"; done = $false }) }
    @{ t = "Onboarding setup"; c = "indigo"; lists = @("Work","Ideas"); items = @(
        @{ x = "Clone repo"; done = $true }, @{ x = "Install .NET 10 SDK"; done = $true },
        @{ x = "Install Node 22+"; done = $true }, @{ x = "Copy .env.example"; done = $false }, @{ x = "Run seed script"; done = $false }) }
)

$createdNotes = New-Object System.Collections.Generic.List[object]

foreach ($n in $textNotes) {
    $listIds = @($n.lists | ForEach-Object { $lists[$_] })
    $note = Invoke-Api -Method POST -Path "/api/notes" -Body @{
        type    = "Text"
        title   = $n.t
        body    = $n.b
        color   = $n.c
        listIds = $listIds
    }
    $createdNotes.Add($note)
}

foreach ($n in $checklistNotes) {
    $listIds = @($n.lists | ForEach-Object { $lists[$_] })
    $order = 0
    $items = @($n.items | ForEach-Object {
        $row = @{ text = $_.x; isChecked = $_.done; order = $order }
        $order++
        $row
    })
    $note = Invoke-Api -Method POST -Path "/api/notes" -Body @{
        type           = "Checklist"
        title          = $n.t
        color          = $n.c
        checklistItems = $items
        listIds        = $listIds
    }
    $createdNotes.Add($note)
}

Write-Host "  created $($createdNotes.Count) notes" -ForegroundColor Green

# ---- 6. Apply pin / archive / trash states so every view has content ----------------------------
Write-Host "→ Setting pin / archive / trash states ..." -ForegroundColor Cyan
function Set-NoteState {
    param([string]$Title, [hashtable]$State)
    $note = $createdNotes | Where-Object { $_.title -eq $Title } | Select-Object -First 1
    if ($note) { Invoke-Api -Method PATCH -Path "/api/notes/$($note.id)/state" -Body $State | Out-Null }
}

Set-NoteState "Welcome to keepIT"        @{ isPinned = $true }
Set-NoteState "Q3 roadmap"               @{ isPinned = $true }
Set-NoteState "Release checklist"        @{ isPinned = $true }
Set-NoteState "Apartment wifi password"  @{ isArchived = $true }
Set-NoteState "Hardware store"           @{ isArchived = $true }
Set-NoteState "Standup notes"            @{ isArchived = $true }
Set-NoteState "Random thought"           @{ isTrashed = $true }
Set-NoteState "Meeting follow-ups"       @{ isTrashed = $true }

# ---- Done ---------------------------------------------------------------------------------------
Write-Host ""
Write-Host "✓ Seed complete." -ForegroundColor Green
Write-Host "  User:  $Email / $Password"
Write-Host "  Lists: $($lists.Count)   Notes: $($createdNotes.Count) (some pinned / archived / trashed)"
Write-Host "  Sign in at the frontend (http://localhost:5173) or via $BaseUrl."

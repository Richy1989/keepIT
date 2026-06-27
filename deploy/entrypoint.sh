#!/usr/bin/env bash
# Starts the two processes that make up the single-container deployment: the .NET API and nginx.
# If either one exits, we tear the whole container down so Docker/Unraid can restart it cleanly
# (rather than limping along with half the app dead).
set -euo pipefail

# The API binds 127.0.0.1:8080 (ASPNETCORE_HTTP_PORTS); nginx is the public face on :80.
dotnet /app/keepITCore.dll &
api_pid=$!

nginx -g 'daemon off;' &
nginx_pid=$!

# Wait for whichever process exits first, then stop the other and exit with its code.
wait -n "$api_pid" "$nginx_pid"
exit_code=$?
kill "$api_pid" "$nginx_pid" 2>/dev/null || true
exit "$exit_code"

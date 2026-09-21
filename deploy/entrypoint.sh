#!/usr/bin/env bash
# Starts the two processes that make up the single-container deployment: the .NET API and nginx.
# If either one exits, we tear the whole container down so Docker/Unraid can restart it cleanly
# (rather than limping along with half the app dead).
set -euo pipefail

# Plain http on a LAN is the normal way this image is reached, so the refresh cookie is not marked
# Secure unless the operator opts in (Auth__RefreshCookie__Secure=true when serving over https).
# The default lives here rather than as an ENV in the Dockerfile because BuildKit's
# SecretsUsedInArgOrEnv lint flags every "Auth__*" name regardless of how un-secret the value is.
: "${Auth__RefreshCookie__Secure:=false}"
export Auth__RefreshCookie__Secure

# The API runs as the base image's unprivileged `app` user: it parses every request body and
# decodes uploaded images, so a flaw there shouldn't come with root. Root is kept only by this
# script and nginx's master process, which binds port 80; nginx's workers, which handle the
# requests, run as www-data.
data="${App__DataRoot:-/data}"
if [ "$(id -u)" = 0 ]; then
  # Hand the data folder to `app`. Earlier versions ran the API as root, so an existing volume or
  # host folder is root-owned; only entries with another owner are touched, so a normal start
  # changes nothing. -h changes a symlink itself, never its target: the API can write here, and
  # must not be able to aim this root-run chown at a file outside the folder.
  # Some storage doesn't let root change owners (a network share with root squashing, say). That
  # needn't be fatal, since such a folder may already be writable for everyone, so say what
  # happened and let the API's own errors report a folder it can't use.
  mkdir -p "$data"
  if ! find "$data" \! -user app -exec chown -h app:app {} +; then
    echo "keepIT: couldn't give everything in $data to the 'app' user (uid 1654). If saving" >&2
    echo "fails, make that folder owned by uid 1654, or writable for it." >&2
  fi
  api=(env HOME=/home/app setpriv --reuid=app --regid=app --init-groups --inh-caps=-all
       dotnet /app/keepITCore.dll)
else
  # nginx's master needs root for port 80 and its working folders, and would fail with a far less
  # helpful message; the unprivileged user is this script's job, not --user's.
  echo "keepIT: start this container as root, without --user. The API already runs as the" >&2
  echo "unprivileged 'app' user, and nginx drops to www-data for handling requests." >&2
  exit 1
fi

# The API listens on 127.0.0.1:8080 only (ASPNETCORE_URLS); nginx is the public face on :80.
"${api[@]}" &
api_pid=$!

nginx -g 'daemon off;' &
nginx_pid=$!

# As PID 1 this script gets `docker stop`'s SIGTERM, and a signal with no handler is ignored by
# PID 1: Docker would wait out its timeout and SIGKILL both, cutting the API off mid-request. Pass
# it on, so the API finishes what it's doing and nginx closes its connections.
trap 'kill -TERM "$api_pid" "$nginx_pid" 2>/dev/null || true' TERM INT

# Wait for whichever process exits first, then stop the other and exit with its code.
wait -n "$api_pid" "$nginx_pid"
exit_code=$?
kill "$api_pid" "$nginx_pid" 2>/dev/null || true
exit "$exit_code"

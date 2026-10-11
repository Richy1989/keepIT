#!/usr/bin/env bash
# The image's Docker HEALTHCHECK: healthy when keepIT answers /api/health through nginx, which
# means nginx, the API, the database and the data folder all work (see HealthEndpoint.cs). The
# image has no curl or wget, and bash can open the connection itself, so nothing is installed for it.
exec 3<>/dev/tcp/127.0.0.1/80 || exit 1
printf 'GET /api/health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
read -r -t 10 _ status _ <&3
[ "$status" = 200 ]

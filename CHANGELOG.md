# Changelog

What changed in each keepIT release, for people running the server and for users of the Android
app. Earlier versions are on the [releases page](https://github.com/Richy1989/keepIT/releases).

## 0.7.5

A security release: six fixes from an audit of the server, more reliable sign-in in the Android
app, and a Delete all button in the trash. Most setups update without any change, but check the
first list below before you do.

### Before you update

These setups need a change, or keepIT or its email stops working:

- **Email (SMTP) without `App__PublicBaseUrl`:** password-reset emails aren't sent until you set it
  to the address your users open keepIT at. The server log and the web app's Settings page say so.
  ([FAQ](FAQ.md#password-reset-emails-stopped-arriving-after-the-update))
- **A malformed `App__PublicBaseUrl`**, for example without `https://`: the server stops at startup
  and names the setting. ([FAQ](FAQ.md#keepit-wont-start-apppublicbaseurl))
- **A mail server without STARTTLS:** email is no longer sent unencrypted. Use port 465 with
  `Email__UseStartTls=false`, or, for a relay on your own network only,
  `Email__AllowUnencrypted=true`. ([FAQ](FAQ.md#the-test-email-fails-with-doesnt-offer-starttls))
- **The data folder on a network share that can't change file owners**, writable only by root:
  keepIT doesn't start. Make the folder writable for uid 1654.
  ([FAQ](FAQ.md#keepit-wont-start-unable-to-open-database-file))

You'll also notice, with nothing to do:

- The data folder (on Unraid, `appdata/keepit`) now belongs to uid 1654, because keepIT no longer
  runs as root. ([FAQ](FAQ.md#why-does-my-data-folder-now-belong-to-user-1654))
- The single container's log includes nginx's request lines. Docker's log rotation can cap it.
  ([FAQ](FAQ.md#my-container-log-got-much-bigger))

### New

- **Delete all** in the trash, in the web app and the Android app. Your own notes are deleted
  for good; a note someone shared with you is only removed from your notes, and its owner keeps
  it. The Android app on a server older than 0.7.5 deletes only your own notes.

### Security

- Password-reset links are built only from `App__PublicBaseUrl`, never from the request. Anyone
  could forge the request's address and have your server send a genuine reset email pointing at
  their own site.
- Email always goes out encrypted: STARTTLS is required, where before it was silently skipped when
  the mail server, or someone in between, didn't offer it.
- An uploaded image's size is checked before it is decoded (at most 100 megapixels, set with
  `App__Media__MaxImagePixels`), only an animation's first frame is decoded, and at most two
  uploads are processed at once. Before, one small crafted file could exhaust the server's memory.
- Sign-in tokens that travel in URLs (the web app's live-sync connection, password-reset links)
  are blanked in nginx's logs, and pages send only the site's origin as the referer.
- The app runs as an unprivileged user in both container images, and in the single container it
  can only be reached through nginx. `docker stop` now shuts it down cleanly.
- The sign-in cookie is marked HTTPS-only on every HTTPS request, so an instance behind a TLS
  proxy is protected without changing `Auth__RefreshCookie__Secure`.
- The web app's router is updated past a high-severity advisory, in a mode keepIT doesn't use.

### Android app

- Turning the phone, switching to dark mode or leaving the app while it starts no longer shows
  the sign-in screen or signs you out, and signing in or out finishes even if the screen is
  rebuilt.
- When the server refuses a photo as too large, the message names both limits: 10 MB and 100
  megapixels.
- Updated libraries: Compose, Navigation, Coil, OkHttp 5, Retrofit 3 and the SignalR client. The
  app is compiled against Android SDK 37; the Android versions it runs on are unchanged.

### Also

- An [FAQ](FAQ.md) for people running keepIT: updating, reverse proxies and HTTPS, email, images.
- Settings shows a warning while unencrypted email is allowed.
- Dependency updates, among them .NET packages 10.0.12, SQLite 3.53, React 19.3 and nginx 1.31 in
  the Compose web image.
- Known-vulnerable dependencies now fail CI, checked weekly as well, and Dependabot proposes
  updates.

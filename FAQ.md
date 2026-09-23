# keepIT FAQ

Answers for people running keepIT: what changes when you update, and what to check when
something doesn't work. Settings are given as environment variables; the
[README](README.md#quick-start) lists them all. On Unraid most are fields in the template;
add any other one as a variable.

- [Updating](#updating)
- [Password reset and email](#password-reset-and-email)
- [HTTPS and reverse proxies](#https-and-reverse-proxies)
- [Images](#images)
- [Logs and security](#logs-and-security)

## Updating

### Do I need to change anything when I update?

Usually not, including from 0.7.1 or earlier to 0.7.5, the release that brought the changes
below (see the [CHANGELOG](CHANGELOG.md)). keepIT keeps its settings, moves its data folder over
to the user it now runs as, and picks up the new protections on its own. A few setups do need a
change, each with its own answer below:

- You send email (SMTP) but never set `App__PublicBaseUrl`: password-reset emails stop until
  you do. See [Password-reset emails stopped arriving](#password-reset-emails-stopped-arriving-after-the-update).
- `App__PublicBaseUrl` is set, but not to a full `https://…` address: keepIT won't start. See
  [keepIT won't start: "App:PublicBaseUrl"](#keepit-wont-start-apppublicbaseurl).
- Your mail server doesn't offer encryption (STARTTLS): email stops. See
  [The test email fails](#the-test-email-fails-with-doesnt-offer-starttls).
- Your data folder is on a network share that doesn't allow changing file owners: keepIT may
  not start. See [keepIT won't start: "unable to open database file"](#keepit-wont-start-unable-to-open-database-file).

Two things you'll notice but don't need to act on: the data folder
[now belongs to user 1654](#why-does-my-data-folder-now-belong-to-user-1654), and the container
log [got bigger](#my-container-log-got-much-bigger).

### Password-reset emails stopped arriving after the update

keepIT now builds the link in a reset email only from `App__PublicBaseUrl`, the address your
users open keepIT at (for example `https://notes.example.com`). Without it, no reset email is
sent. The server log says so at startup, and the web app's Settings page shows a warning. Set
`App__PublicBaseUrl` and restart. [Why keepIT insists on it](#why-does-keepit-insist-on-app__publicbaseurl).

### keepIT won't start: "App:PublicBaseUrl"

The address is malformed, usually because it lacks `https://` (`notes.example.com` instead of
`https://notes.example.com`). Older versions accepted it and sent broken links; now it stops at
startup so you notice. Use the full address, without a path or query.

### The test email fails with "doesn't offer STARTTLS"

keepIT no longer sends mail unencrypted. Your mail server, on the port you configured, doesn't
offer to encrypt the connection. Pick one:

- If your provider supports implicit TLS, use port 465 with `Email__UseStartTls=false`.
- For a relay on your own network that can't do TLS at all (a local Postfix, say), set
  `Email__AllowUnencrypted=true`. Only there: anywhere else, anyone on the network path could
  read your reset links and your SMTP password. Settings shows a warning while it's on.

### Why does my data folder now belong to user 1654?

keepIT no longer runs as root. The app runs as an unprivileged user, `app` (uid 1654), so a flaw
in it wouldn't come with full control of the container. On every start, before the app runs, the
container makes everything in the data folder that user's. That's how data written by older
versions, which ran as root, keeps working with no manual step. Files you add later as root, a
restored backup for example, are handed over at the next start.

On Unraid, `appdata/keepit` shows 1654 as its owner. Backups (CA Appdata Backup and the like)
work as before, and access over SMB is the same as it was with root: read-only.

### keepIT won't start: "unable to open database file"

If the log also says keepIT couldn't give the data folder to the `app` user, the folder is on
storage that doesn't allow changing owners, typically a network share (NFS or SMB) with root
squashing. keepIT can then only use the folder if user 1654 may write to it. Either make the
share writable for uid 1654, or move the data folder to local storage. On Unraid, appdata on the
cache, a pool or the array is fine.

### Can I run the container with `--user`, or set PUID/PGID?

No. Start it normally. The container already runs the app as the unprivileged user, and it needs
root for a moment at startup to hand the data folder over and to let nginx open port 80. Started
with `--user`, it stops with a message saying so.

### My container log got much bigger

In the single container, the log now includes a line from nginx for every request, next to the
app's own line. It used to go to a file inside the container, which nothing ever cleaned up. Now
Docker's log handling can cap it:

- On Unraid, make sure **Settings → Docker → Docker LOG rotation** is enabled (the default).
- With plain Docker, add `--log-opt max-size=10m --log-opt max-file=3` to `docker run`, or set
  the same under `logging:` in a Compose file, or once for all containers in Docker's
  `daemon.json`. Without it, Docker keeps every line.

## Password reset and email

### Can I use password reset without a mail server?

Yes. Leave `Email__SmtpHost` empty and the reset link is written to the server log
(`docker logs keepit`, or the log view on Unraid). As the operator, you pass it on to the user.
This is deliberate: it's the only place keepIT writes a reset link to a log.

### Which SMTP settings do I need?

`Email__SmtpHost`, `Email__From` and usually `Email__SmtpUsername` / `Email__SmtpPassword`, plus
`App__PublicBaseUrl`. Port 587 with STARTTLS is the default; for port 465 set
`Email__UseStartTls=false`. Then use **Settings → Email → Send test email** in the web app. It
shows exactly what went wrong if delivery fails.

### Why does keepIT insist on App__PublicBaseUrl?

Anyone can ask for a reset email for any address, and the web address in that request is
whatever the requester sends. If keepIT built the link from it, an attacker could have your
server send a real reset email to one of your users, with a link pointing at the attacker's site,
and take over the account when it's clicked. A configured address is the only safe source.

## HTTPS and reverse proxies

### How do I run keepIT behind Nginx Proxy Manager, SWAG or Traefik?

- Point the proxy at the container's port **80**, the one you publish (8080 on the host by
  default). The app's own port inside the container isn't reachable from outside.
- Turn on WebSocket support (in Nginx Proxy Manager: **Websockets Support** on the proxy host).
  Live sync uses it.
- Set `App__ForwardedProxyHops=2`, since your proxy is a second hop in front of keepIT's own.
  Otherwise every visitor looks like your proxy and they all share one rate limit.
- Nothing to do for the sign-in cookie: keepIT marks it HTTPS-only by itself whenever a request
  arrives over HTTPS.
- Raise your proxy's request body limit if you plan to **import** a backup. keepIT accepts an
  archive up to 256 MB and its own nginx allows that, but yours sits in front and usually caps
  bodies far lower (Nginx Proxy Manager and SWAG default to 1 MB). In an NPM proxy host that is
  **Advanced → `client_max_body_size 256m;`**; in SWAG, the same line in your site config. Without
  it a large restore stops with a 413 that comes from your proxy, not from keepIT.
- If your proxy keeps access logs, leave query strings out of them, or at least `token` and
  `access_token`. The live-sync connection carries a sign-in token in its URL, and a reset link
  carries its token. keepIT's own logs already blank both.

### Should Auth__RefreshCookie__Secure be true or false?

Over HTTPS the sign-in cookie is always HTTPS-only, whatever this says. The setting only decides
plain http:

- `false` (the single container's default): plain http on your LAN keeps working, and access
  through a TLS proxy is protected anyway.
- `true` (Compose's default): plain http can't stay signed in. Choose it if keepIT should only
  ever be reached over HTTPS. `http://localhost` still works, because browsers treat it as secure.

### I keep getting signed out

You get signed out when the browser can't send the sign-in cookie back. Common causes:

- `Auth__RefreshCookie__Secure=true` while you use plain http on an address other than
  `localhost`, such as a LAN IP. Set it to `false`.
- The same host name is used over both https and plain http (through a proxy on one port and
  directly on another). A cookie from the https side can't be replaced from the http side, so
  plain http can't stay signed in. Use one of the two.

### Live sync doesn't work, changes only show after a reload

Usually the proxy in front doesn't pass WebSockets on; turn on WebSocket support (see
[behind a reverse proxy](#how-do-i-run-keepit-behind-nginx-proxy-manager-swag-or-traefik)).
The web app then falls back to slower methods, which some proxies hold back. The container log
shows the live-sync connection as `GET /api/realtime?…`: `101` means it's working, a `502` means
the app couldn't be reached.

## Images

### "Image too large"

An image may have up to 10 MB and 100 megapixels. The pixel limit exists because decoding an
image costs memory by its pixel count, not its file size: a 1 MB file can hold hundreds of
megapixels. 100 megapixels is above any phone photo that fits in 10 MB. On a server with little
memory you can lower it with `App__Media__MaxImagePixels` (the default is `100000000`).

### iPhone photos are refused (HEIC)

keepIT doesn't support HEIC yet. On the iPhone, set **Settings → Camera → Formats → Most
Compatible**, or share the photo as JPEG. The Android app saves a photo the server refuses to your
gallery (Pictures/keepIT) instead of losing it.

### Where did my photo's location and camera data go?

keepIT removes it on upload. Phone photos carry GPS coordinates, and a shared note would
otherwise tell everyone you share it with where the photo was taken. The image itself is kept,
turned the right way up and at most 2560 pixels on its longest side. GIFs are kept as they are,
so animations still play.

## Backups, export and import

### How do I back up my notes?

Two ways, and they answer different questions. **Settings → Your data → Download my notes** gives
you a `.zip` of everything you own — notes, checklists, lists, reminders and the images attached
to them — which you can read without keepIT and restore into any keepIT account. That is the one
to keep off the server. Backing up the **data folder** (`App_Data`, or your Unraid appdata share)
is the operator's version: it captures every user at once, plus accounts and settings, but only
restores onto a keepIT server.

### Importing adds my notes twice

That is what it is meant to do. Import never overwrites or merges — every note in the archive
arrives as a **new** note, so importing the same file twice gives you two of each. It works that
way on purpose: the one operation that could destroy your notes is the one that must not be able
to. Lists are the exception, since a list you already have is filed into rather than duplicated.
Delete the extra copies, or import into a fresh account.

### My import fails, or stops on a big archive

Check who is refusing it. A **413** almost always comes from a proxy in front of keepIT rather
than from keepIT itself — see the reverse-proxy question above for raising the limit. keepIT's own
cap is 256 MB, and it answers an archive it cannot read with a plain message saying what was
wrong ("no keepit-export.json inside", "written by a newer version of keepIT"). A **504** on a
large archive is also the proxy: every image is re-checked and re-encoded on the way in, which can
take a few minutes, so raise its read timeout.

Individual images that could not be imported are listed after the import finishes rather than
failing the whole thing — one unreadable photo never costs you the rest of the archive.

### Can I import my notes from Google Keep?

Not yet. keepIT reads its own export format only; a Google Takeout importer is on the roadmap.

## Logs and security

### Does keepIT write sign-in tokens to its logs?

No. The web app's live-sync connection has to carry its sign-in token in the URL, and a reset
link carries its token in the page address. keepIT's nginx writes both as `[redacted]`. The one
deliberate exception is a reset link when no mail server is configured
([see above](#can-i-use-password-reset-without-a-mail-server)). Your own proxy's logs are
[yours to configure](#how-do-i-run-keepit-behind-nginx-proxy-manager-swag-or-traefik).

### Can other containers reach the app directly, without nginx?

No. In the single container the app listens only inside the container, behind nginx. In the
Compose setup the app publishes no port; only keepIT's own containers share its network.

### Should I close registration?

If keepIT is reachable from the internet: yes, once your accounts exist. Set
`App__AllowRegistration=false`. Existing users keep working. Registration would otherwise also
tell anyone whether an email address already has an account.

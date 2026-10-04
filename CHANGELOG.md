# Changelog

What changed in each keepIT release, for people running the server and for users of the Android
app. Earlier versions are on the [releases page](https://github.com/Richy1989/keepIT/releases).

## 0.9.1

A fix for the Android editor, and new screenshots.

### Fixed

- **Opening a note on Android no longer counts as editing it.** Closing a note, or switching away
  from the app while one was open, saved it even when nothing had changed: it jumped to the top of
  the list and was sent to everyone it is shared with. On a shared note it could do worse, and send
  back the copy you opened over a change someone else had made in the meantime. Only real changes
  are saved now, as on the web.

### Also

- New screenshots of the current app, in all three themes, for the README, F-Droid and the Unraid
  app listing.

## 0.9.0

Many notes at once, and more colour. On Android, long-press a note to select several and act on
them together; the app now shows your profile picture, and its Settings becomes a short list with a
page for each part. On the web and Android, note colours are livelier, your display name is no
longer fixed at sign-up, and there is a proper About page. On the server, the log becomes a short,
coloured overview of what is happening. Update your server along with the app: it is what saves
the display name.

### New

- **Change your display name after sign-up.** It could only be set once, on the sign-up form, and
  never again. Settings now has a **Display name** field, in the web app's General section and the
  Android app's Account section: change the name, or clear it to show your email instead. Your
  other signed-in devices pick up the new name straight away.
- **A tidier Settings screen on Android.** One long page became a short list that says where each
  thing stands, with a page for anything that needs room:
  - **Account:** your display name, email, password and server, and Sign out (it is still in the
    menu too). In standalone mode this is **This phone only**, with Connect to a server and Erase
    notes.
  - **Notifications:** the two permissions reminders depend on. The row on the main list is marked
    when either is off, so a blocked permission no longer hides further down the page.
  - **Your data**, as before; the theme is now picked in a small dialog.
- **A proper About page, on the web and Android.** What keepIT is, the version you are running
  (and, on Android, your server's), links to the source code, issues, release notes, licence and
  support, and the open-source projects keepIT is built on, with our thanks. On the web it is the
  new **About** section in Settings; on Android, **Settings → About**.
- **Select several notes at once, on Android.** Long-press a note to select it, tap others to add
  them, and the top bar acts on all of them together: pin, change the colour, file them into lists
  (or make a new list for them), archive, or move them to the trash. In the trash, restore them or
  delete them for good. Archive, trash and restore offer **Undo**; Back or ✕ ends the selection.
  Notes shared with you as view-only keep their colour, as they would one at a time. It works
  offline and in standalone mode, and needs nothing new from the server.
- **Livelier note colours, on the web and Android.** On the dark and dim themes the nine note
  colours were so muted they read as shades of grey; they are now about twice as saturated and a
  little brighter, with text on them as legible as before. The light theme's pastels are a touch
  richer too. Your notes keep the colours you gave them: only the shades change.
- **A server log you can read at a glance.** The container log is now one short, coloured line
  per request: time, method, path, status and how long it took, with the status green, amber or
  red so a problem stands out. It shows in colour in Unraid's log view and in `docker logs`. Each
  request used to be logged twice, once in a long line by nginx; now nginx only speaks up for what
  never reached keepIT (a missing file, an upload over the limit, the app not answering), and
  start-up says in one line which version is running on which database. See
  [What does the container log show?](FAQ.md#what-does-the-container-log-show)
  (`NO_COLOR=1` turns the colour off).

### Fixed

- **The Android app shows your profile picture.** A picture uploaded in the web app's Settings
  only ever appeared on the web: the Android app drew your initial instead. It now shows the
  picture in the menu and at the top of Settings, keeps it for when you're offline, and picks up a
  new one as soon as you upload it. Uploading is still done on the web.
- **Stopping the single container cut keepIT off mid-request.** On a stop, the container's start
  script passed the signal on to keepIT and then quit at once, and the moment it quit Docker killed
  everything left in the container. keepIT now gets to finish what it was doing first.

### Updating

- Nothing to configure. The name is saved by the server, so update it along with the app: against
  an older server, saving a name in the Android app shows an error and changes nothing.

### Also

- **Betas, for anyone who wants to test what's coming.** Before a release, betas of it may now go
  out as GitHub pre-releases and as the Docker tag `beta`. Nobody gets one without asking: Docker's
  `latest`, F-Droid, and Obtainium with its default settings stay on releases. To take part, see
  [Beta releases](README.md#beta-releases).
- The About page also thanks nginx, the web server in the Docker image, and SQLitePCLRaw, which
  connects the server to SQLite. Both were missing; tests now keep the list in step with the
  libraries keepIT actually uses.

## 0.8.5

A new look. keepIT has a new icon -- a K of soft, rounded strokes, a green stem and a brass chevron
-- on the home screen, in the browser tab and in the F-Droid listing, and the app's default accent
is now that green. The Android app gains the web app's themes: Light, Dark and Auto beside Dim.
Nothing about your notes or your setup changes.

### New look

- **A new app icon everywhere:** the Android launcher (round and square masks, and Android 13's
  themed icons, which use a single-colour drawing of the same K), the browser tab, the icon iPhones
  and iPads use for a home-screen bookmark, the web app's top bar and sign-in page, and the F-Droid
  listing. The small keepIT mark inside the web app -- next to Settings, and on an empty notes
  page -- is the K too.
- **A new F-Droid banner**, with the new icon and a line that says where your notes live: on your
  phone and your own server, never on anyone else's.
- **Forest green is the default accent.** New accounts start on it, and the Android app, which has
  no accent setting, uses it throughout. Yellow and the other seven are still in the web app's
  Appearance menu (the palette icon in the top bar). In the light theme, text and icons in the
  accent use the icon's own deep green; buttons use a lighter shade of it, so the black text on
  them stays easy to read.
- **Your current accent stays as it is.** The web app saves your settings the first time you open
  it, so an existing account keeps yellow. To switch, pick **Forest** in the Appearance menu.
- **Themes on Android.** Settings has a new Appearance section with the web app's four themes:
  **Light**, **Dim** (the look the app has always had, and still the default), **Dark**, and
  **Auto**, which follows your phone between light and dark. The home-screen widget follows
  along. The choice belongs to the phone: it isn't synced, so the web app keeps its own setting.

### Updating

- Nothing to do: no migration and no configuration change. The server accepts the new `forest`
  accent and gives it to new accounts.

### Also

- The icon is now made in Blender, and `docs/brand/keepit-icon.blend` is its only source: one
  command (`docs/brand/render_icons.py`) renders it and writes every icon file at its exact size,
  and `docs/brand/render_feature_graphic.py` renders the F-Droid banner. See
  [docs/brand/README.md](docs/brand/README.md).
- The contrast tests on both clients now also check that black text stays readable on the accent,
  for every accent, which a darker colour like this one could otherwise break unnoticed.
- Routine library updates: MailKit and Scalar on the server; Vite and TanStack Query in the web
  app; AndroidX Core, Navigation and WorkManager in the Android app, which is now built with
  Gradle 9.8.

## 0.8.1

A fix for being signed out of every device at once, for no apparent reason; a roomier note editor on
Android; and Markdown that reads the same on the phone as on the web. The sign-out fix is
server-side, so updating the server is what applies it.

### Fixed

- **Signed out everywhere, with nothing to explain it.** Presenting a refresh token that had
  already been rotated was read as a stolen cookie in every case -- and the answer to a stolen
  cookie is to end every session the account has. Two entirely ordinary situations reached it. A
  client that never received a rotation response still holds the previous token and presents it
  again: a dropped connection, or the app killed at the wrong moment, is enough. And a client
  retrying a queued request after the user signed out does the same. Either one ended every session
  on every device, minutes or hours later.

  The tokens say which happened. A replacement that has never been used is in nobody's hands, and
  signing out leaves no replacement at all. Only a replacement already in circulation means someone
  else is holding a copy, and that case still ends every session, exactly as before. A client whose
  lost response was made good is held to the token it was given instead, so a copy of its old token
  is still caught once that one is in use, rather than working until it expires.

- **The Android app now writes a rotated refresh cookie durably** instead of leaving it to an
  asynchronous write, closing the window where the app being killed just after a refresh left the
  superseded token on disk for the next launch to present.

- **Android backups no longer carry the app's sign-in.** Android backed up the whole app, refresh
  cookie included, so restoring a backup or moving to a new phone brought a copy of it along. If
  the old phone had refreshed since the backup was taken, that copy looked exactly like a stolen
  cookie and ended every session on the account -- the same symptom as above, by another route. If
  it had not, the backup held a working sign-in. The cookie and the server address now stay out of
  backups and transfers: on a new phone you sign in again, and the notes and any changes not yet
  uploaded carry on under the same account. Downloaded images stay behind too. They download
  again, and on their own they could take the app past Android's 25 MB cloud-backup allowance,
  which skips the backup entirely.

- **Android said "Offline" while the phone was online.** Anything that stopped a sync read the
  same: a server name the phone's DNS would not resolve (or the app being blocked from the
  network, which Android reports the same way), a certificate the phone does not trust, a server
  answering 500, the sign-in failing to renew. Only the first is the phone's problem, and nothing
  said which it was. The status line now names it -- "Can't find keepit.example.com on this
  network", "Server error (HTTP 500)" -- and "Offline" means a phone with no network at all. The
  reason is logged too, for `adb logcat`.

- **Signing out on Android warns about changes that haven't synced.** Sign-out tries once more to
  send them, then clears the phone's copy whether or not that worked -- so signing out to get past
  a sync problem threw away every edit, photo and voice note still waiting. It now asks first,
  saying how many changes are waiting.

- **The status bar icons on Android stay light** on a phone set to light mode, where the clock and
  battery were drawn dark on the app's dark background.

- **Tapping a `file://` link in a note crashed the Android app**, and a note shared with you could
  hold one. Only web, mail and phone links open now; anything else shows as its text. On the web,
  a link that pointed nowhere -- the formatting toolbar's `url` placeholder -- opened keepIT itself
  in a new tab; it is plain text now too.

### Android editor

- **The tools float above the navigation bar.** They sat directly on the gesture bar -- or under
  the buttons of 3-button navigation -- because the app draws edge to edge and they only ever made
  room for the keyboard. They are now a rounded toolbar clear of both, with larger buttons.
- **One row instead of two**: add, formatting, colour, checklist and microphone. "Aa" swaps in the
  formatting buttons instead of stacking a second row; there are more of them than fit, so they
  scroll, and fade at the edge where more are hidden.
- **Add and colour open sheets**: labelled choices for a photo, images or a voice note, and large
  named swatches that recolour the note while the sheet is still open, so trying a few is a tap
  each.
- **Reminder and share moved to the top bar**, beside pin, and trash into the menu there -- it used
  to sit one slip away from the camera.
- **Markdown is styled as you type**: bold reads bold, headings are larger, links are coloured, and
  the syntax itself is dimmed. It is still the plain text underneath, so nothing moves under the
  cursor.

### Markdown

- **Notes read the same on the phone as on the web.** The Android app parsed Markdown with a
  hand-rolled subset that disagreed with the web in small ways nobody could predict: `2 * 3 * 4`
  came out in italics, a `# comment` inside a code block became a heading, `_emphasis_` and bare
  URLs stayed raw, and a link was cut at its first `)`. It now uses commonmark-java, the same
  CommonMark and GitHub dialect the web renders. The widget and reminder previews come from the
  same parse, so they no longer drop every asterisk in the text.
- **Enter continues a list**, in the web and Android editors alike: after `- milk` the next line
  starts `- `, after `3.` it starts `4.`, and a new task starts unticked. Enter on an empty item
  ends the list.
- **Formatting toolbar fixes, on both clients.** Italic on a bold word turned it into italic instead
  of bold italic. The list and heading buttons selected the whole line, so the next keystroke
  replaced it; the cursor now stays put. Switching bullets to numbers stacked the markers (`1. - a`)
  instead of replacing them, and numbering counted blank lines. Styling a selection that ended in a
  space, or ran over several lines, gave Markdown that did not render.
- **Images in a note show as their description, linked to the image**, on the web. The security
  policy only loads images from keepIT itself, so one from anywhere else was a broken-image icon.

### Also

- Ending every session for an account is now logged: a warning naming the account, how old the
  replayed token was, and how many sessions it ended. It wrote nothing at all before, which is why
  it could not be explained afterwards. The two ordinary cases above log at information level, so a
  client that keeps losing rotation responses reads as a pattern rather than a one-off.

## 0.8.0

Voice notes, and a way to take everything with you. Record straight into a note on Android and play
it back there or in the browser -- from the notes overview, without opening the note. Export your
whole account to a file you keep, and load it back on another server or another phone.

**This release needs the server updated and its reverse proxy reconfigured.** On an old proxy
config voice notes play back as dead controls and import is refused outright, so read Updating
first.

### Updating

- **Two columns are added** to note attachments (the kind of attachment, and how long a recording
  runs). On PostgreSQL the migration applies at startup; on SQLite the schema reconciler adds them
  to the database file you already have. Nothing is rewritten and nothing is lost.
- **Both nginx configs changed** -- `deploy/nginx.conf` for the single container and
  `web/nginx.conf` for Compose. If you run your own proxy, carry two things across:
  - The Content-Security-Policy needs `media-src 'self' blob:`. Without it the browser blocks
    playback and a voice note renders as greyed-out, dead controls. Images were unaffected because
    `img-src` already allowed `blob:`.
  - `/api/import` and `/api/export` need their own location blocks: a 256 MB request body, request
    buffering off, and a 600-second read timeout. The 12 MB cap that suits a single photo refuses a
    real backup at the proxy, before the API ever sees it or can say why.
- **Two new settings**, both with working defaults: `App:Media:MaxAudioBytes` (10 MB, the same as an
  image, because both travel through that proxy cap) and `App:Media:MaxAudioPerNote` (10).
- Export and import each get their own rate limit, so downloading a backup does not spend the
  budget you need to upload one.

### Voice notes

- **Recording is on Android**: a microphone button in the editor, a live timer, and playback with a
  progress bar. Not in the web app, because a browser cannot record audio over plain http -- which
  keepIT supports on a LAN -- so a record button there would simply be missing for a real share of
  users.
- **Playback is everywhere**, including straight from the notes overview on both clients, so a
  twelve-second recording does not need a note opened to hear it. Only one plays at a time, and
  nothing is downloaded until you press play.
- Recordings are stored **exactly as uploaded**. There is no audio encoder in the container, so the
  file that was recorded is the file that is kept and served. That makes identifying the bytes the
  whole of the validation: the format is recognised by signature rather than by the name the client
  sent, and an MPEG-4 carrying a video track is refused -- an attachment endpoint must not become
  video hosting.
- Mono, 22.05 kHz, AAC in m4a, around 32 kbps: roughly 40 minutes of speech inside the 10 MB
  attachment cap.
- Recording works offline and in standalone mode. It queues in the same outbox a photo uses, and
  plays from the staged file before it has ever reached a server.

### Export and import

- `GET /api/export` streams a zip of the account -- notes, lists and every attachment -- and
  `POST /api/import` reads one back. In the web app both are on the settings page; on Android they
  are in Settings.
- **In standalone mode the archive is built and applied on the phone**, so a device that has never
  seen a server can still back itself up and restore.
- **Import only ever adds.** Every note in the file arrives as a new note and nothing already in
  the account is touched, so importing the same file twice gives you duplicates. That is the
  deliberate trade: the one operation that could destroy someone's notes is the one that must not
  be able to.
- The archive is the API's own note and list formats plus the attachment files, and it carries a
  schema version -- so an older keepIT refuses a newer file outright instead of importing half of
  it.
- The archive also records the version of the server that wrote it, so a failed import can be
  traced back to where the file came from.

## 0.7.6

A visual release: the web app is easier to read, above all in the light theme, and lays notes out
in reading order. In the Android app, sign-out moves into the menu. The server itself is
unchanged, so updating needs no change to your setup.

### Web app

- Notes are laid out row by row, newest first across the top, in balanced columns. Before, they
  ran down the left-hand column first.
- Easier to read in the light theme: text and icons in the accent colour use a darker shade of it,
  so the default yellow no longer disappears on white. The light background is a touch grey, so
  white notes stand out from it.
- Timestamps, counts, hints and placeholders have more contrast in all three themes, on menus and
  dialogs as well as on the page.
- Deleting a list, leaving a shared note and emptying the trash ask in a keepIT dialog instead of
  the browser's pop-up.
- A note's reminder and timestamp wrap onto a new line in a narrow column instead of being cut off.
- The search box fits its label on a phone again. The keepIT mark now shows from tablet width up,
  where there is room for it; on a phone the menu button already marks the app.
- The Inter typeface now actually loads. keepIT serves it itself, so no font service is contacted.
- Menus and dialogs fade in, unless your system asks for reduced motion. Shadows, the dimmed
  backdrop behind dialogs and the keyboard focus outline follow the theme.

### Android app

- Sign out has moved from the top bar to the bottom of the menu, under your name and email address.
  In standalone mode there is still no sign-out in the menu: erasing the phone stays in Settings.
- The menu scrolls, so with many lists, Notifications and Settings stay within reach.
- Timestamps, counters and hints have more contrast, in the app and on the home-screen widget.

### Also

- The web app has its first automated tests, run in CI: every theme and accent combination is
  checked for readable contrast, and the note layout for reading order. The Android app checks its
  colours the same way.

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
  ([FAQ](FAQ.md#how-do-i-keep-the-container-log-from-growing))

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

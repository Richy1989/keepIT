# Privacy Policy

**keepIT** · Effective 5 October 2026

keepIT is free, open-source software for notes: an Android app, a web app and a server you run
yourself. It is a personal project by Richard Leopold (“the developer”), not a service. This policy
explains what happens to your data when you use any part of keepIT.

## In short

- **The developer runs no keepIT servers and receives none of your data.** Not your notes, not your
  account, not how you use the app.
- **No ads, no analytics, no tracking, no crash reporting, no third-party SDKs** — in the Android
  app, the web app or the server.
- **Your notes live where you choose:** on the keepIT server you connect to, or only on your phone.
- **Whoever runs that server is responsible for the data on it.** If you run it yourself, that is
  you.

## Who holds your data

The Android app works in one of two ways:

- **On its own (“standalone”), with no server.** Everything stays on your phone. Nothing is sent
  anywhere.
- **Connected to a keepIT server.** Your notes are stored on that server, and the app keeps a copy
  on your phone so it works offline. The app and the web app talk only to that server.

A keepIT server is run by whoever installed it — “the operator”: you on your own hardware, or a
friend, family member or organisation who invited you. The operator can access what is stored on
their server, because it is their machine, and they decide how it is backed up and protected. If
you use someone else’s server, ask them how they handle your data; their rules apply to it. The
developer has no access to any keepIT server.

## What keepIT stores

### On a keepIT server

- **Your account:** your email address, your password (only as a salted hash, never in readable
  form), and, if you choose to add them, a display name and a profile picture.
- **Your notes and everything in them:** titles, text, checklists, colours, photos, voice
  recordings, lists, pins, archive and trash, reminders and who a note is shared with.
- **Photos** are re-encoded when you upload them, and all their metadata is removed, including
  the location where they were taken. Voice recordings are stored as recorded.
- **Sign-in sessions:** a hashed sign-in token with its creation and expiry time, for as long as
  you stay signed in (14 days by default).
- **Your inbox:** share invitations and reminders that have gone off.

The server does **not** store IP addresses, device identifiers, your location or your contacts.
It uses the address a request comes from only in memory, to limit how many requests one address
can make in a minute, which protects sign-in from password guessing.

**Server logs.** For each request the server logs the time, method, path, response status and how
long it took — never the part of the address after a `?`, and never an IP address. The logs go to
the container’s output, where the operator can read them, and the operator’s settings decide how
long they are kept. The built-in web server can record an IP address in its internal error log
when a request fails before reaching keepIT.

**Email.** If the operator has configured a mail server, keepIT uses it to send password-reset
emails and the test email you can request in Settings, and for nothing else. Without one, the reset link is written to the server log, where the
operator can pass it on to you.

### On your phone (Android app)

- A copy of your notes, lists and changes not yet sent, in the app’s private storage, so the app
  works offline. In standalone mode, this is the only copy.
- Photos and recordings you have added, and images downloaded from your server.
- The server address and a sign-in cookie, in the app’s private storage.
- Your theme choice.

**Android backup.** If backup is turned on for your phone (for example Google’s backup or
Seedvault), Android may include keepIT’s notes in it, so they survive a phone change. Your sign-in
cookie and downloaded images are excluded. Backups are handled by your phone’s backup service under
its own terms.

Recent notes appear in the home-screen widget if you add it, and reminders appear as notifications,
so both can be seen by anyone looking at your phone.

### In your browser (web app)

- A sign-in cookie that keeps you signed in. It is needed for the app to work, cannot be read by
  scripts, and is not used for tracking.
- Your theme and accent colour, in the browser’s local storage.

There are no tracking or advertising cookies. The web app loads everything from your own server
and nothing from anywhere else.

## Android permissions

| Permission | Why |
|---|---|
| Internet, network state | To sync with your server, and to notice when you are back online. Not used in standalone mode. |
| Notifications | To show reminders and share invitations. |
| Alarms and reminders (exact alarms) | To show reminders at the minute they are due, even with the app closed. |
| Start at boot | To set your reminders again after the phone restarts. |
| Microphone | Only while you record a voice note, after you have allowed it. |

Taking a photo uses your camera app and choosing one uses the system photo picker, so keepIT needs
no camera or storage permission and sees only the photos you pick.

## Sharing notes with other people

When you share a note, the people you share it with see the note with its photos and recordings,
your email address and, if you set one, your profile picture. Invitations arrive in their keepIT
inbox. Each person keeps their own pins, lists and reminders, which the others do not see.

## Other services

keepIT includes no third-party services. Links you choose to open — the source code and release
notes on GitHub, or the page to support the developer — open in your browser, and those sites’ own
privacy policies apply.

Wherever you download keepIT from — F-Droid, GitHub, Obtainium, Google Play or Docker Hub — may
collect information about the download under its own policy.

## Your control over your data

- **Export everything** you own as a single file: Settings → Your data, on the web and on Android.
  It includes notes, checklists, lists, reminders and photos, and you can load it into any keepIT
  account.
- **Delete notes:** move a note to the trash, then delete it forever. A note shared with you is
  only removed from your notes; its owner keeps it.
- **Remove everything from a phone:** sign out, which deletes the notes stored on the phone, or in
  standalone mode use Settings → This phone only → Erase notes. Uninstalling the app also deletes
  everything it stored.
- **Delete your account:** Settings → Security → Delete account on the web, or Settings → Account
  → Delete account on Android. After you enter your password again, the server deletes your
  account, your notes with every photo and recording in them, your lists, settings, profile
  picture and notifications. Notes you shared are gone for everyone you shared them with; notes
  others shared with you stay with their owners. This cannot be undone, so export first if you
  want a copy. It needs a server on keepIT 0.9.2 or newer; on an older one, ask the operator.
  Copies the operator made of the server, such as backups, are up to the operator.

## Security

Passwords are stored only as salted hashes. Sign-in tokens are short-lived and the long-lived one
is kept where scripts cannot read it. Photos lose their location data on upload, and no sign-in
token is ever written to keepIT’s logs. A server reached over the internet should be put behind
HTTPS; the [FAQ](FAQ.md#https-and-reverse-proxies) explains how.

## Children

keepIT is not directed at children. The developer collects no personal data from anyone,
children included.

## Changes to this policy

Changes are published in this file, and every earlier version stays visible in the
[project history](https://github.com/Richy1989/keepIT/commits/main/PRIVACY.md). The date at the
top shows when it last changed.

## Contact

Questions about this policy or about keepIT:
[open an issue on GitHub](https://github.com/Richy1989/keepIT/issues). For questions about the data
on a particular keepIT server, contact the person who runs it.

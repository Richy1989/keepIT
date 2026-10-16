# Screenshots

Every screenshot in the repo shows the same demo account: Anna Berger's, with a profile picture,
five lists and ten notes chosen to show photos, a voice note, checklists, a reminder and sharing.
The account is not kept anywhere. `seed-demo.sh` builds it on a fresh server, and the rest of this
folder takes the screenshots from it.

Retake them when notes, the menu, Settings or the About page look different from the pictures.
F-Droid reads `fastlane/metadata` from the release tag, so new ones must be on `main` before the tag.

| File | What it does |
|---|---|
| `seed-demo.sh` | Builds the demo account over the REST API. |
| `web-shots.cjs` | Takes the three web screenshots (Playwright). |
| `adb-ui.py` | Taps, waits and captures on the emulator by the text on screen. |
| `finish.py` | Checks the new files and writes them where the repo keeps them. |

The web half is one command. The phone half is done by hand with `adb-ui.py`, following the table
in step 3. It is not scripted on purpose: nine screens in three themes and a widget on the launcher
come down to swipe distances and the launcher's layout, and a script of those breaks with the next
change to the app.

## The screenshots

| File | Shows | Used by |
|---|---|---|
| `images/web-notes-dark.png` | Web, dark: the notes | README (top) |
| `images/web-notes-light.png` | Web, light: the notes | README |
| `images/web-editor-dim.png` | Web, dim: "Weekend in Puglia" open | README |
| `images/screenshot_1.png` | A copy of `web-notes-dark.png` | Unraid template |
| `images/screenshot_phone.png` | A copy of phone `1.png` | Unraid template |
| `fastlane/…/phoneScreenshots/1.png` to `9.png` | The Android app, see below | F-Droid; README shows 1, 2, 3, 6, 7 |
| `fastlane/…/featureGraphic.png` | The banner | F-Droid (`docs/brand/README.md` has how) |

Web screenshots are 2880×1800 (a 1440×900 window at twice the density), phone ones are taken at
1080×2400, all without an alpha channel. `finish.py` refuses any other size, and widens the phone
ones to 1350×2400 by repeating their outermost columns: Play Console takes phone screenshots at
9:16 only, and F-Droid and the README show the same files. Play takes at most eight, so `9.png`
(About) is the one it goes without.

## 1. A server with the demo account

Build the single-container image **as the version the screenshots will ship with**: the web
footer and both About pages print it. From the repo root:

```bash
docker build --secret id=sixlabors_license,src=keepIT/keepITCore/sixlabors.lic \
    --build-arg VERSION=1.0.0 --build-arg COMMIT="$(git rev-parse --short HEAD)" \
    -f deploy/Dockerfile -t keepit-shots:local .
docker run -d --name keepit-shots -p 127.0.0.1:18080:80 \
    -e Jwt__Key="$(openssl rand -hex 32)" keepit-shots:local
./scripts/screenshots/seed-demo.sh http://localhost:18080
```

The seed needs `curl`, `jq`, ImageMagick 7, `ffmpeg` and GNU `date` (on Windows: Git Bash or WSL),
and a server nobody has signed up on. It ends by printing the notes in the order the grid shows
them. Sign in as `anna@example.com` / `Demo1234#demo`.

A card prints when its note was last changed (the time of day, while it is still that day), so
take the web and the phone screenshots from one run of the seed, on the day it ran.

## 2. Web

```bash
SHOTS="$(mktemp -d)"
node scripts/screenshots/web-shots.cjs http://localhost:18080 "$SHOTS/web"
```

It needs `npm ci` in `web/` and `npx playwright install chromium` there, as the browser smoke
test does.

## 3. Android

Build the `minified` variant, which is the release's R8 build with debug signing: it is what users
get, it installs without the release key, and it sits beside a released keepIT as its own app
(`org.hyperstarit.keepitapp.minified`). Give it the same version as the image:

```bash
(cd app && VERSION_NAME=1.0.0 VERSION_CODE=1000099 ./gradlew :app:assembleMinified)
```

Start an emulator with a 1080×2400 screen (the Pixel 7a profile), then:

```bash
P=org.hyperstarit.keepitapp.minified
adb install -r app/app/build/outputs/apk/minified/app-minified.apk
adb shell pm clear $P                                   # a signed-out app with no notes
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb shell appops set $P SCHEDULE_EXACT_ALARM allow      # or Settings says reminders may arrive late
./scripts/screenshots/adb-ui.py demo on                 # 9:41, full Wi-Fi and battery
adb shell monkey -p $P -c android.intent.category.LAUNCHER 1
```

Sign in with the server URL `http://10.0.2.2:18080` (the emulator's name for the host's
localhost) and the demo account. Then take each screen with `adb-ui.py shot "$SHOTS/phone/N.png"`.
`adb-ui.py ls` lists what can be tapped by name; scroll with `adb shell input swipe`.

| File | Theme | Screen |
|---|---|---|
| `1.png` | Dim | The notes, at the top: the Puglia photo note and Groceries. Tap Refresh first. |
| `2.png` | Dim | Scrolled to the other three pinned notes, all selected: long-press Groceries, tap Book club and Lisbon trip. |
| `4.png` | Dim | "Weekend in Puglia" open in the editor. |
| `5.png` | Dim | "Lisbon trip" open in the editor. |
| `3.png` | Light | The notes, scrolled to "Garden visitors" (photo and voice note) and "Carbonara for four". |
| `8.png` | Light | Settings. |
| `9.png` | Dark | Settings → About. |
| `6.png` | Dark | The menu, scrolled to its top: the lists with their icons, the account at the bottom. |
| `7.png` | Dim | The launcher with the keepIT widget, made taller so it shows more notes. |

That is the order to take them in, one theme after the other. The theme is Settings → Theme, and
Settings is in the menu below the lists, so scroll the menu to reach it.

What went wrong the last time:

- **A shield in the status bar** in spite of demo mode: the emulator's own notifications ("Set a
  screen lock"). Open the shade (`adb shell cmd statusbar expand-notifications`), tap "Clear all".
- **"Reminders may arrive late"** in Settings: the `appops` line above, then leave Settings and
  open it again.
- **The menu remembers where it was scrolled to**, so after opening Settings from it, scroll it
  back up before `6.png`.
- **Two "keepIT" entries in the widget picker** when a released keepIT is installed too, and the
  first is the other app's. After placing the widget, `adb shell dumpsys appwidget | grep provider=`
  must name `.minified`.
- **Dragging a widget** from the picker works with `adb shell input draganddrop`. Dragging one off
  the home screen again does not: hold it with `adb shell input motionevent DOWN x y`, wait a
  second, send a few `MOVE`s up to the "Remove" target at the top, then `UP` there.
- **zsh does not split a variable into a command and its arguments**: call `adb-ui.py` by its path,
  not through `U="python3 …/adb-ui.py"`.
- **A portrait photo first in a note** fills the phone's whole first screen, because a card shows a
  photo at its own shape. The seed crops the Puglia note's first photo to 3:2 for that reason.

## 4. Into the repo

```bash
python scripts/screenshots/finish.py "$SHOTS"       # needs Pillow
python docs/brand/render_feature_graphic.py         # only if note cards or their colours changed
```

`finish.py` writes the web and phone files and the two Unraid copies, and takes one half alone
when `$SHOTS` holds only `web/` or only `phone/`. Then read `README.md`'s alt texts against the
new pictures, and add a line to `CHANGELOG.md` under Deployment & Docs.

## 5. Clean up

```bash
./scripts/screenshots/adb-ui.py demo off
adb shell pm clear org.hyperstarit.keepitapp.minified
docker rm -fv keepit-shots && docker rmi keepit-shots:local
```

and take the widget off the emulator's home screen.

# Promo video

A 25-second video of keepIT for the store listings and the README: the real Android app, on a phone
hanging in the air, a caption for each beat, and the F-Droid and Google Play badges centred under
it. 30 fps, with music; Play autoplays a preview muted, so the captions carry it without.

It comes in two shapes from one source: **landscape** (1920×1080) for YouTube, Play and the README,
with the caption beside the phone, and **portrait** (1080×1920) for phones, with the caption above
it. Play takes either; a portrait video must fill the frame, with no black bars, which this one does.

It is made in two halves:

- **The footage** is one take of the real app on the emulator, driven by a script, on the same demo
  account the screenshots show (`scripts/screenshots/`), plus a few people to share a note with.
- **The video** is a [Remotion](https://www.remotion.dev) composition (React) in `src/`: the phone
  frame, its motion, the background, the captions, the close-up of a shared note's faces and the
  badges. It cuts the footage by the moments the script recorded, so a new take needs no edit.

Remotion's licence is free for individuals, for-profit organisations with up to three employees
and non-profits; a larger company would need a company licence. It is only used here, to make the
video, and ships to no one.

## Make it

From the repo root, on a machine with the Android SDK, Docker or the .NET SDK, Node, ffmpeg,
ImageMagick 7 and `jq`.

**1. A fresh server, without the welcome note.** Every demo account would otherwise start with one.

```bash
App__WelcomeNote=false App__DataRoot="$(mktemp -d)" dotnet run --project keepIT/keepITCore
```

**2. The demo account, and the people for the shared note.**

```bash
./scripts/screenshots/seed-demo.sh http://localhost:5025
./docs/promo/scripts/seed-promo.sh http://localhost:5025
```

**3. The footage.** Start an emulator with a 1080×2400 screen (the Pixel 7a profile; `-no-window`
and `-gpu swiftshader_indirect` work), install the app (`cd app && ./gradlew :app:installDebug`),
then:

```bash
python3 docs/promo/scripts/record-tour.py http://10.0.2.2:5025
```

It clears the app, signs in as Anna, turns on the demo status bar (9:41, full battery) and records
the tour: Groceries opens, two items are ticked, the note turns Sage, and the list scrolls down to
"Lisbon trip" and its three faces. It writes `public/tour.mp4` and `src/tour-marks.json`. A take
changes the notes, so the next one needs a freshly seeded server again.

**4. The video.**

```bash
cd docs/promo
npm ci
npm run studio      # preview in the browser, with a timeline to scrub
npm run render      # out/keepit-promo-landscape.mp4 and out/keepit-promo-portrait.mp4
```

`npm run render:landscape` and `npm run render:portrait` make one of them.

## Where things are

| File | What it is |
|---|---|
| `scripts/seed-promo.sh` | Gives Jonas a picture, adds Mia and Luca, and shares "Lisbon trip" with them. |
| `scripts/record-tour.py` | Records the footage and the moment of each tap. |
| `src/timeline.ts` | Which parts of the footage play when, and the five beats. |
| `src/layout.ts` | Where everything goes, in landscape and in portrait. |
| `src/Captions.tsx` | What each beat says. |
| `src/Phone.tsx` | The phone, its frame and how it floats. |
| `src/Callout.tsx` | The close-up of the shared note's faces. |
| `src/Background.tsx`, `Brand.tsx`, `Badges.tsx` | The backdrop, the logo, and the store badges. |
| `src/music.ts`, `Music.tsx` | The music: where it starts, and how it fades out. |
| `public/` | The store badges. The footage (`tour.mp4`) and the music land here too, and stay out of git. The icon comes from `docs/brand`. |

The badges are the stores' own, unaltered, as the README shows them. The Google Play one says
"Get it on Google Play": publish the video only once keepIT is public there.

## Music

"Dirty Thinkin'" from [Mixkit](https://mixkit.co/free-stock-music/), under the Mixkit Stock Music
Free License: free to use in a promo video, and no credit required ("Music: Mixkit" in a video's
description is a courtesy). The licence does not allow passing the file on, so it is not in the
repository: download it from Mixkit and save it as `public/music/dirty-thinkin.mp3` before a render.

It runs at 120 BPM. Its intro is a beat on its own while the phone rises; the full groove comes in
on "Tick it off", 3.5 s in, and it fades out under the logo (`src/music.ts`).

After uploading to YouTube, look under YouTube Studio → Copyright for a claim on the music: Google
Play shows only a video without ads, and a claim can put ads on it even with monetisation off.

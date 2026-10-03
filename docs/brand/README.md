# keepIT icon

The app icon is a clay-style typewriter, the same mark as the line icon in the web app
(`TypewriterIcon`): the paper is the note, on a platen with two supports, over a keyboard with a
space bar. It is modelled and rendered in Blender, and **`keepit-icon.blend` is the only source**:
every icon file in the repo is generated from it.

| File | What it is |
|---|---|
| `keepit-icon.blend` | The scene: model, materials, lights, camera. Made with Blender 5.2. |
| `render_icons.py` | Renders the scene and writes every icon file at its exact size. |
| `keepit-icon-1024.png` | The finished icon, rounded tile, for reference and anything new. |
| `keepit-icon-foreground-1024.png` | The mark alone on transparent, framed for Android's adaptive-icon safe zone. |

## Changing the icon

1. Open `keepit-icon.blend`, change what you need, save.
2. From the repo root:

   ```bash
   blender -b docs/brand/keepit-icon.blend --python docs/brand/render_icons.py
   ```

   On Windows, Blender lives at `C:\Program Files\Blender Foundation\Blender 5.2\blender.exe`. A CPU
   render takes a while; the script prints each file as it writes it.
3. Review the regenerated files with `git diff --stat` and commit them with the `.blend`.

The colours are the materials' base colours (`kIT Body`, `kIT Platen`, `kIT Brass Trim`,
`kIT Key Cap`, `kIT Paper`, `kIT Ink`). The cream tile behind the typewriter isn't rendered: it is
painted by the script from two scene custom properties, `keepit_tile_base` and `keepit_tile_glow`
(Scene properties → Custom Properties), so it comes out exactly that colour. If you change those,
change the stops in `app/app/src/main/res/drawable/ic_launcher_background.xml` to match — that
vector is the Android launcher's background layer.

## Where the icon is used

| Path | Size | Shape |
|---|---|---|
| `docs/logo.png` (README, Unraid template) | 512 | rounded tile |
| `fastlane/metadata/android/en-US/images/icon.png` (F-Droid) | 512 | rounded tile |
| `app/app/src/main/ic_launcher-playstore.png` | 512 | full square (Play masks it) |
| `app/.../res/mipmap-*/ic_launcher_foreground.webp` | 108–432 | adaptive foreground |
| `app/.../res/mipmap-*/ic_launcher.webp`, `ic_launcher_round.webp` | 48–192 | legacy launcher |
| `web/public/favicon.png` | 96 | rounded tile |
| `web/public/apple-touch-icon.png` | 180 | full square (iOS rounds it) |
| `web/public/keepit-icon.png` (top bar, sign-in) | 128 | rounded tile |

## F-Droid feature graphic

`fastlane/metadata/android/en-US/images/featureGraphic.png` (1024×500, the banner across the top
of the F-Droid listing) is `feature-graphic.html` rendered in headless Chromium:

```bash
python docs/brand/render_feature_graphic.py
```

It needs Python with Playwright (`playwright install chromium`) and Pillow, and `npm ci` in `web/`
for the Inter font. It embeds `keepit-icon-1024.png`, so run `render_icons.py` first when the icon
changes. The colours are the app's tokens — the accent fill, the canvas, and the dim theme's note
colours — so re-render it if those change too. F-Droid crops the top and bottom of the band, never
the sides, which is why the content sits in the middle 420 px.

## Not generated

By design: Android's themed-icon layer
(`res/drawable/ic_launcher_monochrome.xml`) is the flat line drawing, since a shaded render has no
single-colour silhouette; and the launcher background is the vector above.

## Brand green

The body's green, `#1f6f4a`, is also the app's default accent ("forest"). It is the accent's *ink*
— its form as text and icons in the light theme. It is too deep to be the fill (black on it is
3.4:1) or to be read on the dark themes (2.2:1), so the fill is the same hue lifted to `#41aa79`.
See `web/src/index.css` and ARCHITECTURE.md → "The accent has two forms".

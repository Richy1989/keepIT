# Brand assets

## App icon

Edit [counter-sign.svg](../icon/counter-sign.svg) to change the Forest artwork. The 1024 px PNG in `docs/icon` supplies the store banner and promo artwork.

From the repository root, regenerate the PNGs, Android launcher layers, and lossless WebPs with Python 3, librsvg (`rsvg-convert`), and ImageMagick 7.

```sh
python3 docs/brand/render_icons.py
```

The themed Android launcher uses the letter faces as an alpha mask. The Play Store and Apple touch icons use square tiles for platform masking. The README and Unraid template share `docs/logo.png`.

## F-Droid feature graphic

```bash
python docs/brand/render_feature_graphic.py
```

It needs Python with Playwright (`playwright install chromium`) and Pillow, and `npm ci` in `web/`
for the Inter font. It embeds `../icon/counter-sign-1024.png`, so run `python3 docs/brand/render_icons.py` first when the icon
changes. The colours are the app's tokens — the accent fill, the canvas, and the dim theme's note
colours — so re-render it if those change too. F-Droid crops the top and bottom of the band, never
the sides, which is why the content sits in the middle 420 px.

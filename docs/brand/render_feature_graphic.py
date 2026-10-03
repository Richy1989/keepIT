"""
Renders feature-graphic.html to the F-Droid feature graphic, 1024x500.

    python docs/brand/render_feature_graphic.py

Needs Python with Playwright (and its Chromium: ``playwright install chromium``) and Pillow, plus
``npm ci`` in web/ for the Inter font. Run render_icons.py first if the icon changed: the graphic
embeds docs/brand/keepit-icon-1024.png.

The page is loaded with its font and icon inlined as data: URLs rather than from disk, because
Chromium refuses a file:// page's cross-file font loads. The result is saved without alpha, as the
store listings expect.
"""

import base64
import io
import sys
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT = REPO / "fastlane/metadata/android/en-US/images/featureGraphic.png"

FONT = "../../web/node_modules/@fontsource-variable/inter/files/inter-latin-wght-normal.woff2"
ICON = "keepit-icon-1024.png"


def data_url(path, mime):
    return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def main():
    font = (HERE / FONT).resolve()
    if not font.exists():
        sys.exit(f"Inter not found at {font} - run `npm ci` in web/ first")
    html = (HERE / "feature-graphic.html").read_text(encoding="utf-8")
    for relative, url in ((FONT, data_url(font, "font/woff2")), (ICON, data_url(HERE / ICON, "image/png"))):
        if relative not in html:
            sys.exit(f"feature-graphic.html no longer references {relative}; update this script")
        html = html.replace(relative, url)

    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1024, "height": 500}, device_scale_factor=1)
        page.set_content(html, wait_until="load")
        page.evaluate("document.fonts.load('700 74px Inter')")
        page.evaluate("document.fonts.ready")
        loaded = page.evaluate("[...document.fonts].some(f => f.family.includes('Inter') && f.status === 'loaded')")
        if not loaded:
            sys.exit("Inter failed to load; the wordmark would render in a fallback font")
        png = page.screenshot(type="png")
        browser.close()

    Image.open(io.BytesIO(png)).convert("RGB").save(OUT, optimize=True)
    print(f"wrote {OUT.relative_to(REPO)}")


main()

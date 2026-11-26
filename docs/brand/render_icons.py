"""Regenerate keepIT icon exports with librsvg and ImageMagick 7."""
from copy import deepcopy
from pathlib import Path
from shutil import copyfile
import subprocess
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ICON = ROOT / "docs/icon"
RES = ROOT / "app/app/src/main/res"
NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", NS)


def export(svg, path, size):
    png = subprocess.check_output(
        ["rsvg-convert", "--width", str(size), "--height", str(size)],
        input=ET.tostring(svg),
    )
    if path.suffix == ".webp":
        subprocess.run(["magick", "png:-", "-define", "webp:lossless=true", str(path)],
                       input=png, check=True)
    else:
        path.write_bytes(png)


def main():
    artwork = ICON / "counter-sign.svg"
    tile = ET.parse(artwork).getroot()
    copyfile(artwork, ROOT / "web/public/keepit-icon.svg")
    square = deepcopy(tile)
    background = square.find(f"{{{NS}}}rect")
    background.attrib.pop("rx")
    circle = deepcopy(square)
    circle.find(f"{{{NS}}}rect").set("rx", "48")

    for path, size, svg in [
        (ICON / "counter-sign-1024.png", 1024, tile),
        (ROOT / "docs/logo.png", 512, tile),
        (ROOT / "fastlane/metadata/android/en-US/images/icon.png", 512, tile),
        (ROOT / "app/app/src/main/ic_launcher-playstore.png", 512, square),
        (ROOT / "web/public/apple-touch-icon.png", 180, square),
        (ROOT / "web/public/keepit-icon.png", 128, tile),
        (ROOT / "web/public/favicon.png", 96, tile),
    ]:
        export(svg, path, size)

    # The launcher displays the central 72 dp of its 108 dp adaptive layers.
    adaptive = deepcopy(tile)
    adaptive.remove(adaptive.find(f"{{{NS}}}rect"))
    adaptive.set("viewBox", "-24 -24 144 144")
    for density, scale in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]:
        folder = RES / f"mipmap-{density}"
        export(adaptive, folder / "ic_launcher_foreground.webp", round(108 * scale))
        export(tile, folder / "ic_launcher.webp", round(48 * scale))
        export(circle, folder / "ic_launcher_round.webp", round(48 * scale))

    # The themed layer uses alpha only. Keep the letter faces, including the hole in P.
    sign = adaptive.find(f"{{{NS}}}g")
    sign.remove(sign.find("*[@id='sign-depth']"))
    folder = RES / "drawable-xxxhdpi"
    folder.mkdir(exist_ok=True)
    export(adaptive, folder / "ic_launcher_monochrome.webp", 432)
    (RES / "drawable/ic_launcher_background.xml").write_text(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n'
        f'    <path android:fillColor="{background.get("fill")}" android:pathData="M0,0h108v108H0z"/>\n'
        '</vector>\n'
    )


if __name__ == "__main__":
    main()

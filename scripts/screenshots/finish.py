#!/usr/bin/env python3
"""Puts freshly taken screenshots where the repo keeps them. See README.md in this folder.

    python scripts/screenshots/finish.py <shots-dir>

<shots-dir>/web holds what web-shots.cjs wrote, <shots-dir>/phone holds 1.png to 9.png from the
emulator. Either folder may be missing, to redo only the other half.

Each file is checked first (its size, and that nothing in it is transparent), then written
without an alpha channel and losslessly optimised: F-Droid and the README show these as they are,
and a screencap carries an alpha channel that nothing uses. The Unraid template links two of them
under names of their own, so those are written as byte-for-byte copies.

Phone shots are then widened from the emulator's 1080x2400 to 1350x2400, because Google Play takes
phone screenshots only at 9:16. The 135 pixels added on each side repeat the screen's outermost
column, row by row, so each screen keeps its own margin in its own theme (the status bar, a note's
colour, the wallpaper) and nothing on it is scaled. F-Droid and the README show the same files.

Needs Pillow, the same as docs/brand/render_feature_graphic.py.
"""
import sys
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[2]
IMAGES = REPO / "images"
PHONE = REPO / "fastlane/metadata/android/en-US/images/phoneScreenshots"

WEB_SIZE = (2880, 1800)
PHONE_SIZE = (1080, 2400)
# What a phone shot is widened to: Play Console takes phone screenshots at 16:9 or 9:16 only.
PHONE_STORE_WIDTH = 1350
WEB_NAMES = ["web-notes-dark.png", "web-notes-light.png", "web-editor-dim.png"]
PHONE_NAMES = [f"{n}.png" for n in range(1, 10)]
# What deploy/keepit.unraid.xml links, and the screenshot each is a copy of.
UNRAID_COPIES = {"screenshot_1.png": IMAGES / "web-notes-dark.png", "screenshot_phone.png": PHONE / "1.png"}


def widen(image, width):
    """`image` centred in `width`, its first and last columns repeated out to the new edges."""
    left = (width - image.width) // 2
    right = width - image.width - left
    wide = Image.new("RGB", (width, image.height))
    wide.paste(image, (left, 0))
    edge = Image.Resampling.NEAREST
    wide.paste(image.crop((0, 0, 1, image.height)).resize((left, image.height), edge), (0, 0))
    last = image.crop((image.width - 1, 0, image.width, image.height)).resize((right, image.height), edge)
    wide.paste(last, (left + image.width, 0))
    return wide


def file_in(source, target, size, width):
    image = Image.open(source)
    if image.size != size:
        raise SystemExit(f"{source}: {image.size[0]}x{image.size[1]}, expected {size[0]}x{size[1]}")
    if "A" in image.getbands() and image.getchannel("A").getextrema() != (255, 255):
        raise SystemExit(f"{source}: has transparent pixels")
    image = image.convert("RGB")
    if width != image.width:
        image = widen(image, width)
    image.save(target, optimize=True)
    print(f"{target.relative_to(REPO)}  {target.stat().st_size:,} bytes")


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    shots = Path(sys.argv[1])
    halves = [
        (shots / "web", WEB_NAMES, IMAGES, WEB_SIZE, WEB_SIZE[0]),
        (shots / "phone", PHONE_NAMES, PHONE, PHONE_SIZE, PHONE_STORE_WIDTH),
    ]
    present = [half for half in halves if half[0].is_dir()]
    if not present:
        raise SystemExit(f"{shots}: no web/ and no phone/ folder")
    # All or nothing per half: a missing file stops before anything is written.
    for folder, names, *_ in present:
        missing = [name for name in names if not (folder / name).is_file()]
        if missing:
            raise SystemExit(f"{folder}: missing {', '.join(missing)}")
    for folder, names, target, size, width in present:
        for name in names:
            file_in(folder / name, target / name, size, width)
    for name, original in UNRAID_COPIES.items():
        (IMAGES / name).write_bytes(original.read_bytes())
        print(f"images/{name}  = {original.relative_to(REPO)}")


if __name__ == "__main__":
    main()

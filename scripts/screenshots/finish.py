#!/usr/bin/env python3
"""Puts freshly taken screenshots where the repo keeps them. See README.md in this folder.

    python scripts/screenshots/finish.py <shots-dir>

<shots-dir>/web holds what web-shots.cjs wrote, <shots-dir>/phone holds 1.png to 9.png from the
emulator. Either folder may be missing, to redo only the other half.

Each file is checked first (its size, and that nothing in it is transparent), then written
without an alpha channel and losslessly optimised: F-Droid and the README show these as they are,
and a screencap carries an alpha channel that nothing uses. The Unraid template links two of them
under names of their own, so those are written as byte-for-byte copies.

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
WEB_NAMES = ["web-notes-dark.png", "web-notes-light.png", "web-editor-dim.png"]
PHONE_NAMES = [f"{n}.png" for n in range(1, 10)]
# What deploy/keepit.unraid.xml links, and the screenshot each is a copy of.
UNRAID_COPIES = {"screenshot_1.png": IMAGES / "web-notes-dark.png", "screenshot_phone.png": PHONE / "1.png"}


def file_in(source, target, size):
    image = Image.open(source)
    if image.size != size:
        raise SystemExit(f"{source}: {image.size[0]}x{image.size[1]}, expected {size[0]}x{size[1]}")
    if "A" in image.getbands() and image.getchannel("A").getextrema() != (255, 255):
        raise SystemExit(f"{source}: has transparent pixels")
    image.convert("RGB").save(target, optimize=True)
    print(f"{target.relative_to(REPO)}  {target.stat().st_size:,} bytes")


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    shots = Path(sys.argv[1])
    halves = [(shots / "web", WEB_NAMES, IMAGES, WEB_SIZE), (shots / "phone", PHONE_NAMES, PHONE, PHONE_SIZE)]
    present = [half for half in halves if half[0].is_dir()]
    if not present:
        raise SystemExit(f"{shots}: no web/ and no phone/ folder")
    # All or nothing per half: a missing file stops before anything is written.
    for folder, names, _, _ in present:
        missing = [name for name in names if not (folder / name).is_file()]
        if missing:
            raise SystemExit(f"{folder}: missing {', '.join(missing)}")
    for folder, names, target, size in present:
        for name in names:
            file_in(folder / name, target / name, size)
    for name, original in UNRAID_COPIES.items():
        (IMAGES / name).write_bytes(original.read_bytes())
        print(f"images/{name}  = {original.relative_to(REPO)}")


if __name__ == "__main__":
    main()

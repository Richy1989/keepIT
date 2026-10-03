"""
Renders every keepIT app icon from keepit-icon.blend and writes each file where it is used.

    blender -b docs/brand/keepit-icon.blend --python-exit-code 1 --python docs/brand/render_icons.py

Paths resolve from this script, so any working directory works. Edit the model, materials or
camera in the .blend, save, run this, and every icon in the repo is regenerated at its exact size —
there is no other copy of the artwork to keep in step.

How an icon is made: Cycles renders the mark (the two-tone K) on a transparent film, with the floor
("Backdrop") as a shadow catcher so its soft shadow lands in the alpha. That render is then laid
over a flat tile — the cream gradient whose two colours are stored on the scene as the custom
properties ``keepit_tile_base`` / ``keepit_tile_glow`` — and cut to each target's shape. The tile is
painted here rather than rendered so its colour is exact, not whatever the lighting makes of it.
The shadow falls only behind the mark: the rim light behind it has shadows switched off in the
.blend, because the tall strokes cast long streaks toward the viewer otherwise.

Two framings are rendered:

- **tile**: the camera exactly as saved in the .blend, focal length and shift — the icon as a
  finished picture (store listings, README, favicon, legacy launcher).
- **foreground**: the same camera zoomed out until the mark fits Android's adaptive-icon safe
  zone (a 66dp circle in the 108dp layer), on a transparent background. The launcher supplies the
  background layer (``res/drawable/ic_launcher_background.xml``) and its own mask.

Renders are made at twice the largest output and area-averaged down, in linear light with
premultiplied alpha, so small sizes stay crisp without fringes. WebP is written lossless.

Android 13+ themed icons use ``res/drawable/ic_launcher_monochrome.xml``, a flat drawing of the
same K: a single-colour silhouette can't come from a shaded render.
"""

import os
import tempfile
from pathlib import Path

import bpy
import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]

MASTER = 1024              # the largest file written
RENDER = MASTER * 2        # supersampled render size
SAMPLES = 128              # per pixel, before denoising; the 2x render adds four more per output pixel
SAFE_RADIUS = 33 / 108     # adaptive-icon safe zone, as a fraction of the layer's width
SAFE_TARGET = 0.29         # how far out the foreground mark may reach (a little inside the zone)
TILE_CORNER = 0.225        # corner radius of the rounded tile, as a fraction of its width

ANDROID_RES = "app/app/src/main/res"
DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]

# (path from the repo root, framing, size in px, shape)
TARGETS = [
    ("docs/brand/keepit-icon-1024.png", "tile", 1024, "rounded"),
    ("docs/brand/keepit-icon-foreground-1024.png", "foreground", 1024, "none"),
    ("docs/logo.png", "tile", 512, "rounded"),                                    # README, Unraid template
    ("fastlane/metadata/android/en-US/images/icon.png", "tile", 512, "rounded"),  # F-Droid listing
    ("app/app/src/main/ic_launcher-playstore.png", "tile", 512, "square"),        # Play: full bleed, it masks
    ("web/public/favicon.png", "tile", 96, "rounded"),
    ("web/public/apple-touch-icon.png", "tile", 180, "square"),                   # iOS rounds it itself
    ("web/public/keepit-icon.png", "tile", 128, "rounded"),                       # in-app brand mark
]
for name, scale in DENSITIES:
    TARGETS += [
        (f"{ANDROID_RES}/mipmap-{name}/ic_launcher_foreground.webp", "foreground", round(108 * scale), "none"),
        (f"{ANDROID_RES}/mipmap-{name}/ic_launcher.webp", "tile", round(48 * scale), "rounded"),
        (f"{ANDROID_RES}/mipmap-{name}/ic_launcher_round.webp", "tile", round(48 * scale), "circle"),
    ]


# ---------------------------------------------------------------------------------------------
# Colour

def srgb_to_linear(c):
    c = np.asarray(c, np.float32)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0.0, None)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


def hex_rgb(value):
    value = value.lstrip("#")
    return np.array([int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)], np.float32)


# ---------------------------------------------------------------------------------------------
# Rendering

def configure(scene):
    """Pins every render setting the icon depends on, whatever the .blend was last saved with."""
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = SAMPLES
    scene.cycles.use_denoising = True
    scene.render.resolution_x = scene.render.resolution_y = RENDER
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True
    scene.view_settings.view_transform = "Khronos PBR Neutral"  # keeps the brand colours honest
    scene.view_settings.look = "None"
    scene.view_settings.exposure = 0.0
    scene.view_settings.gamma = 1.0
    settings = scene.render.image_settings
    settings.file_format = "PNG"
    settings.color_mode = "RGBA"
    settings.color_depth = "16"
    bpy.data.objects["Backdrop"].is_shadow_catcher = True


def render(scene, view, zoom, workdir):
    """
    Renders the saved ``view`` (lens, shift_x, shift_y) magnified by ``zoom`` and returns
    top-down RGBA: sRGB colour, straight alpha, float32. Zooming scales the picture about the
    optical axis, so the shift that centres the mark has to scale with it.
    """
    lens, shift_x, shift_y = view
    cam = scene.camera.data
    cam.lens, cam.shift_x, cam.shift_y = lens * zoom, shift_x * zoom, shift_y * zoom
    path = os.path.join(workdir, f"render_{zoom:.4f}.png")
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    image = bpy.data.images.load(path, check_existing=False)
    w, h = image.size
    pixels = np.empty(w * h * 4, np.float32)
    image.pixels.foreach_get(pixels)
    is_float = image.is_float
    bpy.data.images.remove(image)
    rgba = pixels.reshape(h, w, 4)[::-1].copy()     # Blender stores rows bottom-up
    if is_float:                                     # a 16-bit PNG loads as scene-linear floats
        rgba[..., :3] = linear_to_srgb(rgba[..., :3])
    return rgba


def mark_radius(rgba):
    """How far the solid mark reaches from the centre, as a fraction of the width."""
    h, w = rgba.shape[:2]
    ys, xs = np.nonzero(rgba[..., 3] > 0.5)
    return float(np.sqrt(((xs + 0.5) / w - 0.5) ** 2 + ((ys + 0.5) / h - 0.5) ** 2).max())


# ---------------------------------------------------------------------------------------------
# Compositing and shapes (all arrays top-down)

def tile_background(size, base, glow):
    """The flat tile: ``base``, warming to ``glow`` toward a light source at the top left."""
    v, u = (np.mgrid[0:size, 0:size].astype(np.float32) + 0.5) / size
    distance = np.sqrt((u - 0.35) ** 2 + ((v - 0.05) / 0.9) ** 2)
    t = (np.clip(1.0 - distance, 0.0, 1.0) ** 1.6)[..., None]
    return hex_rgb(base) * (1 - t) + hex_rgb(glow) * t


def over_tile(mark, base, glow):
    """The mark laid over the tile: an opaque square."""
    alpha = mark[..., 3:4]
    rgb = mark[..., :3] * alpha + tile_background(mark.shape[0], base, glow) * (1 - alpha)
    return np.dstack([rgb, np.ones(mark.shape[:2], np.float32)])


def shape_mask(size, shape, supersample=4):
    """Anti-aliased coverage of ``shape`` ("rounded", "circle", "square", "none") at ``size``."""
    if shape in ("square", "none"):
        return np.ones((size, size), np.float32)
    n = size * supersample
    y, x = (np.mgrid[0:n, 0:n].astype(np.float32) + 0.5) / supersample
    if shape == "circle":
        inside = (x - size / 2) ** 2 + (y - size / 2) ** 2 <= (size / 2) ** 2
    elif shape == "rounded":
        r = TILE_CORNER * size
        cx = np.clip(x, r, size - r)
        cy = np.clip(y, r, size - r)
        inside = (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    else:
        raise ValueError(f"unknown shape: {shape}")
    return inside.reshape(size, supersample, size, supersample).mean(axis=(1, 3)).astype(np.float32)


def _area_average(values, n_out, axis):
    """
    Shrinks ``axis`` to ``n_out``: each output pixel is the mean of the input it covers, partial
    pixels weighted by overlap. Read off a running sum, so it costs O(n) whatever the ratio — a
    dense weight matrix is mostly zeros, and Blender's bundled numpy multiplies it slowly.
    """
    n_in = values.shape[axis]
    if n_out > n_in:
        raise ValueError(f"only downscaling: {n_in} -> {n_out}")
    values = np.moveaxis(values, axis, 0).astype(np.float64)
    integral = np.concatenate([np.zeros((1,) + values.shape[1:]), np.cumsum(values, axis=0)])
    edges = np.arange(n_out + 1) * (n_in / n_out)
    whole = np.minimum(np.floor(edges).astype(int), n_in)
    part = (edges - whole).reshape((-1,) + (1,) * (values.ndim - 1))
    padded = np.concatenate([values, np.zeros((1,) + values.shape[1:])])
    at_edges = integral[whole] + part * padded[whole]          # the running sum at each edge
    return np.moveaxis((at_edges[1:] - at_edges[:-1]) * (n_out / n_in), 0, axis).astype(np.float32)


def resize(rgba, size):
    """Area-average resample, in linear light with premultiplied alpha (no dark fringes)."""
    if rgba.shape[0] == size:
        return rgba.copy()
    alpha = rgba[..., 3:4]
    premultiplied = np.dstack([srgb_to_linear(rgba[..., :3]) * alpha, alpha])
    out = _area_average(_area_average(premultiplied, size, 0), size, 1)
    a = out[..., 3:4]
    rgb = np.divide(out[..., :3], a, out=np.zeros_like(out[..., :3]), where=a > 1e-6)
    return np.dstack([linear_to_srgb(rgb), a[..., 0]]).astype(np.float32)


def write(rgba, path):
    """Writes 8-bit RGBA PNG or lossless WebP, by extension."""
    path.parent.mkdir(parents=True, exist_ok=True)
    h, w = rgba.shape[:2]
    image = bpy.data.images.new("keepit_icon_out", w, h, alpha=True, float_buffer=False)
    image.pixels.foreach_set(np.clip(rgba[::-1], 0.0, 1.0).astype(np.float32).ravel())
    image.file_format = "WEBP" if path.suffix == ".webp" else "PNG"
    image.save(filepath=str(path), quality=100)    # 100 is lossless for WebP
    bpy.data.images.remove(image)


# ---------------------------------------------------------------------------------------------

def main():
    scene = bpy.context.scene
    base = scene.get("keepit_tile_base", "#f3ecd8")
    glow = scene.get("keepit_tile_glow", "#fffdf6")
    configure(scene)
    cam = scene.camera.data
    saved_view = (cam.lens, cam.shift_x, cam.shift_y)

    with tempfile.TemporaryDirectory() as workdir:
        tile_mark = render(scene, saved_view, 1.0, workdir)
        # Focal length scales the image without moving the camera, so the reach scales with it.
        zoom = SAFE_TARGET / mark_radius(tile_mark)
        foreground = render(scene, saved_view, zoom, workdir)
    cam.lens, cam.shift_x, cam.shift_y = saved_view

    reach = mark_radius(foreground)
    if reach > SAFE_RADIUS:
        raise SystemExit(f"foreground reaches {reach:.3f} of the width, past the {SAFE_RADIUS:.3f} safe zone")

    sources = {"tile": over_tile(tile_mark, base, glow), "foreground": foreground}
    for rel, framing, size, shape in TARGETS:
        out = resize(sources[framing], size)
        out[..., 3] *= shape_mask(size, shape)
        write(out, REPO / rel)
        print(f"  {size:>5}px  {shape:<8} {rel}")
    print(f"foreground lens {saved_view[0] * zoom:.1f}mm, mark reaches {reach:.3f} (safe zone {SAFE_RADIUS:.3f})")


main()

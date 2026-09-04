#!/usr/bin/env python3
"""Parametric application-icon generator for PhotoInventory.

Concept: an open cardboard box with a photo card peeking out — the app's
whole metaphor in one glyph. The script draws the motif once in a 108x108
"dp" design space and rasterises it to every size the app and the desktop
launcher need:

  * adaptive foreground PNGs  -> app/src/main/res/mipmap-*/ic_launcher_foreground.png
    (transparent; every point inside the adaptive safe zone, the centered
    circle of 66% canvas diameter — the adaptive BACKGROUND is supplied by
    the @color/ic_launcher_background resource in mipmap-anydpi-v26 XML)
  * legacy launcher PNGs      -> app/src/main/res/mipmap-*/ic_launcher.png
    and ic_launcher_round.png (full-bleed rounded square / circle in the
    same teal; glyph enlarged a touch because legacy shapes are not masked)
  * desktop icon              -> desktop/src/jvmMain/resources/icon.png (512)

Only Pillow is required (no network, no fonts — pure geometry). The design
is idempotent and re-runnable: tweak the constants below and re-run

    python3 scripts/make_icon.py
"""

import os
from PIL import Image, ImageChops, ImageDraw

# --------------------------------------------------------------------------
# Brand colors (mirror of app/src/main/java/com/inventory/ui/theme/Color.kt)
# --------------------------------------------------------------------------
TEAL_BG    = (0x00, 0x69, 0x6A, 255)  # #00696A primary teal — icon background
WHITE      = (0xFF, 0xFF, 0xFF, 255)  # glyph: box, flaps, photo card
AMBER      = (0xF6, 0xBF, 0x47, 255)  # #F6BF47 accent — sun in the photo
TEAL_LIGHT = (0x6F, 0xF6, 0xF7, 255)  # #6FF6F7 teal container — mountains

# --------------------------------------------------------------------------
# Motif geometry — every coordinate lives in a 108x108 dp design space with
# the adaptive SAFE ZONE being the centered circle of diameter 66 units
# (r = 33). All points below sit inside that circle.
# --------------------------------------------------------------------------
SS = 4  # supersampling factor for clean edges at small sizes

CARD = {"x0": 42.5, "y0": 23, "x1": 65.5, "y1": 49, "r": 2.5}  # white photo card
SUN  = {"cx": 58.5, "cy": 31, "radius": 3.2}               # amber sun circle
MOUNTAINS = [                                              # teal triangles
    [(43.5, 49.2), (51, 34.5), (58.5, 49.2)],   # tall back peak
    [(55, 49.2), (62.5, 39), (65.5, 49.2)],     # lower front peak
]
BOX_BODY = [(30, 58), (78, 58), (68, 83), (40, 83)]        # open box trapezoid
FLAP_LEFT = [(30, 58), (44, 58), (37, 46), (23, 46)]       # left open flap
FLAP_RIGHT = [(78, 58), (64, 58), (71, 46), (85, 46)]      # right open flap

# Legacy/round/desktop are not masked to the safe zone, so the glyph is
# scaled up around the canvas center to fill the shape better.
EXTENDED_GLYPH_SCALE = 1.18
CORNER_RADIUS_RATIO = 0.22   # legacy rounded-square corner radius

# --------------------------------------------------------------------------
# Output map: density -> (foreground px, legacy px). Sizes mandated by the
# adaptive-icon spec (foreground = 108dp per bucket, mdpi 1x).
# --------------------------------------------------------------------------
DENSITIES = {
    "mdpi":     (108, 48),
    "hdpi":     (162, 72),
    "xhdpi":    (216, 96),
    "xxhdpi":   (324, 144),
    "xxxhdpi":  (432, 192),
}
DESKTOP_ICON_SIZE = 512
DESKTOP_ICON_PATH = "desktop/src/jvmMain/resources/icon.png"
RES_ROOT = "app/src/main/res"

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _scaled(unit_pts, size, glyph_scale):
    """Map design-space points (0..108) to pixel coords at `size`, applying
    an extra enlargement around the center when glyph_scale != 1."""
    out = []
    for x, y in unit_pts:
        x = (x - 54.0) * glyph_scale + 54.0
        y = (y - 54.0) * glyph_scale + 54.0
        out.append((x * size / 108.0, y * size / 108.0))
    return out


def _draw_glyph(base, size, glyph_scale):
    """Paint the white box + photo-card glyph onto an RGBA image in place."""
    draw = ImageDraw.Draw(base)
    # Photo card is drawn first; the box body/flaps sit in front of its
    # bottom edge so the card reads as rising out of the open box.
    c = CARD
    (cx0, cy0), (cx1, cy1) = _scaled([(c["x0"], c["y0"]), (c["x1"], c["y1"])], size, glyph_scale)
    draw.rounded_rectangle([cx0, cy0, cx1, cy1], radius=c["r"] * size / 108.0, fill=WHITE)

    # The landscape inside the card is clipped to the card's rounded rect,
    # so overspill from the mountain bases never bleeds over the border.
    # (`base` is already a supersampled canvas, so no extra scaling here.)
    scene = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(scene)
    sun = SUN
    scx, scy = _scaled([(sun["cx"], sun["cy"])], size, glyph_scale)[0]
    sr = sun["radius"] * size / 108.0
    sd.ellipse([scx - sr, scy - sr, scx + sr, scy + sr], fill=AMBER)
    for tri in MOUNTAINS:
        sd.polygon(_scaled(tri, size, glyph_scale), fill=TEAL_LIGHT)
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    (m0, m1), (m2, m3) = _scaled([(c["x0"], c["y0"]), (c["x1"], c["y1"])], size, glyph_scale)
    md.rounded_rectangle([m0, m1, m2, m3], radius=c["r"] * size / 108.0, fill=255)
    scene.putalpha(ImageChops.multiply(scene.getchannel("A"), mask))
    base.alpha_composite(scene)

    for poly in (BOX_BODY, FLAP_LEFT, FLAP_RIGHT):
        draw.polygon(_scaled(poly, size, glyph_scale), fill=WHITE)


def render_foreground(size):
    """Transparent adaptive foreground of the given pixel size."""
    canvas = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    _draw_glyph(canvas, size * SS, 1.0)
    return canvas.resize((size, size), Image.LANCZOS)


def render_legacy(size, circular=False):
    """Full-bleed teal rounded square (or circle) + scaled-up glyph."""
    canvas = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    ss_px = size * SS
    if circular:
        draw.ellipse([0, 0, ss_px - 1, ss_px - 1], fill=TEAL_BG)
    else:
        draw.rounded_rectangle(
            [0, 0, ss_px - 1, ss_px - 1],
            radius=ss_px * CORNER_RADIUS_RATIO,
            fill=TEAL_BG,
        )
    _draw_glyph(canvas, ss_px, EXTENDED_GLYPH_SCALE)
    return canvas.resize((size, size), Image.LANCZOS)


def main():
    written = []

    def save(img, rel_path):
        path = os.path.join(REPO_ROOT, rel_path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        written.append((rel_path, img.size))

    for density, (fg_px, legacy_px) in DENSITIES.items():
        save(render_foreground(fg_px), f"{RES_ROOT}/mipmap-{density}/ic_launcher_foreground.png")
        save(render_legacy(legacy_px), f"{RES_ROOT}/mipmap-{density}/ic_launcher.png")
        save(render_legacy(legacy_px, circular=True), f"{RES_ROOT}/mipmap-{density}/ic_launcher_round.png")

    save(render_legacy(DESKTOP_ICON_SIZE), DESKTOP_ICON_PATH)

    print("icon generation complete:")
    for rel_path, (w, h) in written:
        print(f"  {rel_path}  {w}x{h}")


if __name__ == "__main__":
    main()

"""
Generates the app icon: a white water droplet with the Bluetooth rune cut out of it, on a
blue gradient background.

Everything is drawn from the same geometry, so the adaptive-icon layers (108x108 design
space) and the legacy raster icons stay identical:

  * ic_launcher_background.xml  - vector gradient
  * ic_launcher_foreground.xml  - vector droplet (full bleed, 108x108)
  * mipmap-*/ic_launcher.webp   - legacy rounded-square rasters
  * mipmap-*/ic_launcher_round.webp - legacy circular rasters

The droplet is traced as a cubic Bezier teardrop and rasterised as a polygon; the rune is
punched out of the droplet mask afterwards, which keeps the two layers perfectly registered.
"""
import math
import os
import numpy as np
from PIL import Image, ImageDraw

ROOT = r"D:\Professional\AndroidProject\watercontrol\app\src\main\res"

# --- design space ------------------------------------------------------------
# Android adaptive icons: 108x108 viewport, but only the centre 72x72 is guaranteed to be
# visible (the launcher masks and animates the outer ring). Everything below is kept inside
# that safe zone, with a little margin for the drop shadow-free white shape.
S = 108.0
CX = 54.0
SAFE_CENTER = 54.0
SAFE_RADIUS = 33.0          # a hair inside the 36 half-size, so nothing kisses the mask edge

# --- geometry ---------------------------------------------------------------
# A classic teardrop: a long, narrow taper that swells into a round belly. The taper has to
# stay slim for a while or the shape just reads as a circle.
TIP = (54.0, 24.5)
BELL = 26.0                 # half-width of the droplet at its widest point
EQ_TOP = 59.0               # where the widest point sits
BOTTOM = 81.0

# The droplet outline, clockwise from the tip. Each segment is (c1, c2, end).
DROPLET = [
    # tip -> right shoulder -> right equator (the shoulder control point stays low and close
    # to the axis, which is what makes the taper narrow instead of conical)
    ((CX + 4.2, TIP[1] + 13.0), (CX + BELL, EQ_TOP - 7.0), (CX + BELL, EQ_TOP)),
    # right equator -> bottom -> left equator
    ((CX + BELL, BOTTOM - 4.6), (CX + 16.2, BOTTOM), (CX, BOTTOM)),
    ((CX - 16.2, BOTTOM), (CX - BELL, BOTTOM - 4.6), (CX - BELL, EQ_TOP)),
    # left equator -> left shoulder -> tip
    ((CX - BELL, EQ_TOP - 7.0), (CX - 4.2, TIP[1] + 13.0), TIP),
]

# --- Bluetooth rune, in the same 108x108 space ------------------------------
# Sits low in the belly, where the droplet is widest, so the stroke never comes close to the
# outline. Small rasters drop it entirely (see LEGACY_SIZES_WITH_RUNE): at 48px a rune this
# size collapses into a smudge, and a plain droplet reads far better.
SPINE_TOP = 50.0
SPINE_BOTTOM = 70.0
SPINE_MID = (SPINE_TOP + SPINE_BOTTOM) / 2.0
ARM = 6.2                   # half-width of the chevrons
TOP_ELBOW = (CX + ARM, SPINE_TOP + (SPINE_MID - SPINE_TOP) / 2.0)
BOTTOM_ELBOW = (CX - ARM, SPINE_MID + (SPINE_BOTTOM - SPINE_MID) / 2.0)
RUNE_JOINT = (CX + ARM, SPINE_MID + (SPINE_BOTTOM - SPINE_MID) / 2.0)
RUNE_WIDTH = 2.9            # stroke width of the rune

# --- colours -----------------------------------------------------------------
BACKGROUND_TOP = (0x21, 0x82, 0xC4)     # lighter, sky-ish blue at the top
BACKGROUND_BOTTOM = (0x00, 0x3E, 0x6B)  # deep water blue at the bottom
FOREGROUND = (0xFF, 0xFF, 0xFF)


def bezier_point(p0, c1, c2, p1, t):
    mt = 1.0 - t
    x = (mt ** 3) * p0[0] + 3 * (mt ** 2) * t * c1[0] + 3 * mt * (t ** 2) * c2[0] + (t ** 3) * p1[0]
    y = (mt ** 3) * p0[1] + 3 * (mt ** 2) * t * c1[1] + 3 * mt * (t ** 2) * c2[1] + (t ** 3) * p1[1]
    return (x, y)


def droplet_polygon(steps_per_segment=220):
    """Outlines the droplet by walking the Bezier segments."""
    points = [TIP]  # the tip
    for c1, c2, end in DROPLET:
        start = points[-1]
        for i in range(1, steps_per_segment + 1):
            points.append(bezier_point(start, c1, c2, end, i / steps_per_segment))
    return points


def rune_path():
    """The Bluetooth glyph as a polyline, drawn as one continuous stroke."""
    return [
        (CX, SPINE_TOP),
        TOP_ELBOW,                                  # up-right
        (CX, SPINE_MID),                            # back to the middle
        RUNE_JOINT,                                 # down-right
        (CX, SPINE_BOTTOM),                         # to the bottom
        BOTTOM_ELBOW,                               # up-left
        (CX, SPINE_MID),                            # back to the middle
    ]


def check_safe_zone():
    """Fails loudly if any geometry escapes the guaranteed-visible circle."""
    worst = 0.0
    for x, y in droplet_polygon():
        d = math.hypot(x - SAFE_CENTER, y - SAFE_CENTER)
        worst = max(worst, d)
    # The rune lives inside the droplet, so the droplet is the only thing to check.
    print(f"  droplet extent from centre: {worst:.2f}  (safe radius {SAFE_RADIUS})")
    assert worst <= SAFE_RADIUS, "droplet escapes the adaptive-icon safe zone"


def scale_points(points, scale):
    return [(x * scale, y * scale) for x, y in points]


def droplet_mask(size, with_rune=True):
    """Anti-aliased mask of the droplet, optionally with the rune knocked out."""
    ss = max(4, int(2048 / size))          # supersample factor
    big = size * ss
    scale = big / S

    mask = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(mask)
    draw.polygon(scale_points(droplet_polygon(), scale), fill=255)

    if with_rune:
        # Rune is removed from the droplet, so it reads as a cut-out.
        path = scale_points(rune_path(), scale)
        width = max(1, int(round(RUNE_WIDTH * scale)))
        draw.line(path, fill=0, width=width, joint="curve")
        # Round the two free ends so the stroke does not look chopped.
        r = width / 2.0
        for x, y in (path[0], path[-1]):
            draw.ellipse([x - r, y - r, x + r, y + r], fill=0)

    return mask.resize((size, size), Image.LANCZOS)


def background_gradient(size):
    """Vertical blue gradient, light at the top."""
    ss = max(4, int(2048 / size))
    big = size * ss
    top = np.array(BACKGROUND_TOP, dtype=np.float64)
    bottom = np.array(BACKGROUND_BOTTOM, dtype=np.float64)
    ramp = np.linspace(0.0, 1.0, big)[:, None, None]
    rows = top[None, None, :] * (1.0 - ramp) + bottom[None, None, :] * ramp
    img = Image.fromarray(np.repeat(rows, big, axis=1).astype(np.uint8), "RGB")
    return img.resize((size, size), Image.LANCZOS)


def rgba_of(size, with_background, with_rune=True):
    if with_background:
        base = background_gradient(size).convert("RGBA")
    else:
        base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer = Image.new("RGBA", (size, size), FOREGROUND + (255,))
    layer.putalpha(droplet_mask(size, with_rune))
    base.alpha_composite(layer)
    return base


def rounded_mask(size, shape):
    ss = 4
    big = size * ss
    m = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, big - 1, big - 1], fill=255)
    else:
        # Android's legacy launcher icons use a squircle-ish rounded square.
        d.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.167), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def write_adaptive_vectors():
    """Vector layers. The background is a full-bleed gradient, the foreground is the droplet."""
    bg = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  Adaptive icon background: a vertical blue gradient, light at the top. Must stay full bleed
  (108x108) because the launcher crops it to whatever mask shape the device uses.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="54" android:startY="0"
                android:endX="54" android:endY="108">
                <item android:offset="0" android:color="#FF2182C4" />
                <item android:offset="1" android:color="#FF003E6B" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""
    path_data = droplet_path_data()
    fg = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  Adaptive icon foreground: a white water droplet with the Bluetooth rune cut out of it.
  Kept inside the central 72x72 safe zone so no launcher mask can clip it. Also used as the
  monochrome layer, where Android tints it from the system palette.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{path_data}" />
</vector>
"""
    with open(os.path.join(ROOT, "drawable", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write(bg)
    with open(os.path.join(ROOT, "drawable", "ic_launcher_foreground.xml"), "w", encoding="utf-8") as f:
        f.write(fg)
    print("  wrote drawable/ic_launcher_background.xml and ic_launcher_foreground.xml")


def droplet_path_data():
    """The droplet as a single SVG path string, with the rune as a second subpath.

    Both subpaths wind the same way and the fill type is `evenOdd`, so the rune really is
    punched out rather than filled.
    """
    def fmt(v):
        return f"{v:.2f}".rstrip("0").rstrip(".")

    parts = [f"M{fmt(TIP[0])},{fmt(TIP[1])}"]
    for c1, c2, end in DROPLET:
        parts.append(f"C{fmt(c1[0])},{fmt(c1[1])} {fmt(c2[0])},{fmt(c2[1])} {fmt(end[0])},{fmt(end[1])}")
    parts.append("Z")

    # The rune as a filled outline so evenOdd can knock it out: a closed ribbon around the
    # polyline. It is built from the same points, offset to either side.
    path = rune_path()
    half = RUNE_WIDTH / 2.0
    left, right = [], []
    for i, (x, y) in enumerate(path):
        # Direction of the local segment, for the perpendicular offset.
        if i == 0:
            dx, dy = path[1][0] - x, path[1][1] - y
        elif i == len(path) - 1:
            dx, dy = x - path[i - 1][0], y - path[i - 1][1]
        else:
            dx = path[i + 1][0] - path[i - 1][0]
            dy = path[i + 1][1] - path[i - 1][1]
        length = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / length * half, dx / length * half
        left.append((x + nx, y + ny))
        right.append((x - nx, y - ny))

    parts.append(f"M{fmt(left[0][0])},{fmt(left[0][1])}")
    for x, y in left[1:]:
        parts.append(f"L{fmt(x)},{fmt(y)}")
    for x, y in reversed(right):
        parts.append(f"L{fmt(x)},{fmt(y)}")
    parts.append("Z")
    return " ".join(parts)


DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Below this size the cut-out rune stops being legible (48px mdpi is the worst case), so the
# small rasters use a plain droplet instead of a smudged glyph.
MIN_SIZE_FOR_RUNE = 96


def write_legacy_rasters():
    """Pre-API-26 launcher icons.

    The legacy tile is the same 108-design rendered at the density's icon size: the artwork
    keeps the adaptive icon's proportions (droplet inside the middle 72/108), so the old and
    new icons look like the same app.
    """
    for folder, size in DENSITIES.items():
        out_dir = os.path.join(ROOT, f"mipmap-{folder}")
        with_rune = size >= MIN_SIZE_FOR_RUNE
        art = rgba_of(size, with_background=True, with_rune=with_rune)
        for name, shape in (("ic_launcher", "square"), ("ic_launcher_round", "circle")):
            tile = art.copy()
            tile.putalpha(rounded_mask(size, shape))
            path = os.path.join(out_dir, f"{name}.webp")
            tile.save(path, "WEBP", quality=95, method=6)
            print(f"  {folder}/{name}.webp  {size}x{size}{'' if with_rune else '  (plain droplet)'}")


def write_previews(out_dir):
    """Renders a contact sheet so the icon can be eyeballed at real launcher sizes.

    Each size is rendered natively rather than downscaled from a master, so the preview shows
    exactly what that density's raster will contain -- including the sizes that drop the rune.
    Written to tools/preview/, and not shipped in the APK.
    """
    os.makedirs(out_dir, exist_ok=True)

    master = rgba_of(432, with_background=True)
    for label, size, shape in (
        ("adaptive-circle", 192, "circle"),
        ("adaptive-square", 192, "square"),
    ):
        img = master.resize((size, size), Image.LANCZOS)
        img.putalpha(rounded_mask(size, shape))
        img.save(os.path.join(out_dir, f"{label}.png"))

    for size in (144, 96, 72, 48):
        art = rgba_of(size, with_background=True, with_rune=size >= MIN_SIZE_FOR_RUNE)
        art.putalpha(rounded_mask(size, "circle"))
        art.save(os.path.join(out_dir, f"legacy-{size}.png"))

    # Contact sheet on a neutral background, worst-case size last.
    sheet = Image.new("RGB", (700, 260), (0xF2, 0xF2, 0xF2))
    x = 24
    for size in (192, 144, 96, 72, 48):
        if size == 192:
            icon = master.resize((size, size), Image.LANCZOS)
        else:
            icon = rgba_of(size, with_background=True, with_rune=size >= MIN_SIZE_FOR_RUNE)
        icon.putalpha(rounded_mask(size, "circle"))
        sheet.paste(icon, (x, 130 - size // 2), icon)
        x += size + 26
    sheet.save(os.path.join(out_dir, "sizes.png"))
    print(f"  previews written to {out_dir}")


if __name__ == "__main__":
    print("safe zone:")
    check_safe_zone()
    print("adaptive icon vectors:")
    write_adaptive_vectors()
    print("legacy rasters:")
    write_legacy_rasters()
    print("previews:")
    write_previews(os.path.join(os.path.dirname(os.path.abspath(__file__)), "preview"))
    print("done")

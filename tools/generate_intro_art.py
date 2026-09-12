"""
Generates the illustrated intro art: a stadium at night, lights off and on.

WHY AN IMAGE AT ALL. The other four intros are drawn live on a Canvas, which is
right for anything geometric — a net, a formation, a departure board. It is the
wrong tool for atmosphere: wide additive bloom, crowd texture, film grain and a
graded night sky are all cheap here and expensive or ugly there.

Two frames are produced rather than one, so the app can cross-fade and the
lights genuinely come ON. That transition is the point of the intro, and it is
the thing a single static image could not give.

Run:  python tools/generate_intro_art.py
Out:  app/src/main/res/drawable-nodpi/intro_stadium_dark.webp
      app/src/main/res/drawable-nodpi/intro_stadium_lit.webp
"""
from __future__ import annotations

import math
import pathlib
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1080, 1920
OUT = pathlib.Path("app/src/main/res/drawable-nodpi")

# The app's own palette, so the art cannot drift from the UI it opens into.
NIGHT_TOP = (4, 6, 11)
NIGHT_HORIZON = (14, 17, 25)
GRASS = (9, 27, 16)
GRASS_LIT = (19, 56, 31)
WARM = (255, 244, 214)

HORIZON = int(H * 0.42)
STAND_TOP = int(H * 0.15)
PYLONS = [0.10, 0.36, 0.64, 0.90]
HEAD_Y = int(H * 0.065)

# The pitch in perspective: narrow at the horizon, running off frame at the
# bottom. Everything else is positioned against these.
TOP_L, TOP_R = W * 0.26, W * 0.74
BOT_L, BOT_R = -W * 0.28, W * 1.28

# The far goal, and the height it therefore clears for the advertising boards
# behind it. Sharing these is what stops the boards being painted straight over
# the goal, which is exactly what happened when each owned its own numbers.
GOAL_W = (TOP_R - TOP_L) * 0.135
GOAL_H = GOAL_W * 0.50

rng = random.Random(20260901)


def pitch_edges(y: float) -> tuple[float, float]:
    """Left and right edge of the pitch at a given height."""
    f = (y - HORIZON) / (H - HORIZON)
    return TOP_L + (BOT_L - TOP_L) * f, TOP_R + (BOT_R - TOP_R) * f


def sky(d: ImageDraw.ImageDraw) -> None:
    """Graded, because a flat fill reads as a bug and a gradient reads as dusk."""
    for y in range(HORIZON):
        f = y / HORIZON
        d.line([(0, y), (W, y)],
               fill=tuple(int(a + (b - a) * f) for a, b in zip(NIGHT_TOP, NIGHT_HORIZON)))


def crowd_block(d: ImageDraw.ImageDraw, lit: bool, x0, y0, x1, y1, density=1.0) -> None:
    """
    A block of spectators.

    STRUCTURE IS WHAT MAKES THIS READ AS A CROWD. An even speckle across a
    rectangle just looks like sensor noise, which is exactly how the first pass
    came out. Real stands are broken into tiers by walkways and into blocks by
    aisles, and it is those DARK GAPS the eye uses to recognise a stadium.
    """
    if x1 <= x0 or y1 <= y0:
        return
    base = 108 if lit else 36
    area = (x1 - x0) * (y1 - y0)
    for _ in range(int(area * 0.055 * density)):
        x = rng.uniform(x0, x1)
        y = rng.uniform(y0, y1)
        depth = (y - y0) / max(1.0, y1 - y0)
        v = int(base * (0.35 + 0.9 * depth) * rng.uniform(0.25, 1.6))
        tint = rng.choice([
            (v, v, int(v * 1.2)), (int(v * 1.15), v, int(v * 0.9)),
            (v, v, v), (int(v * 0.8), int(v * 0.85), v),
        ])
        d.point((x, y), fill=tuple(min(255, max(0, c)) for c in tint))

    # Walkways between tiers and aisles between blocks — but THIN and not
    # black. At four pixels wide and pure black they stopped reading as gaps
    # between people and started reading as mortar between bricks.
    gap = (7, 8, 12)
    for i in range(1, 3):
        yy = y0 + (y1 - y0) * i / 3
        d.rectangle([x0, yy - 1.5, x1, yy + 1.5], fill=gap)
    for i in range(1, 5):
        xx = x0 + (x1 - x0) * i / 5
        d.rectangle([xx - 1, y0, xx + 1, y1], fill=gap)

    if lit:
        for _ in range(int(area * 0.0005)):
            d.point((rng.uniform(x0, x1), rng.uniform(y0, y1)), fill=(255, 236, 190))


def stands(d: ImageDraw.ImageDraw, lit: bool) -> None:
    """The far stand across the top, and the two that enclose the pitch."""
    # Roof, sagging slightly so the bowl is not a rectangle.
    for x in range(0, W, 2):
        sag = int(18 * math.sin(math.pi * x / W))
        d.line([(x, STAND_TOP - 34 + sag), (x, STAND_TOP + sag)], fill=(6, 7, 11))

    d.rectangle([0, STAND_TOP, W, HORIZON], fill=(10, 12, 17))
    crowd_block(d, lit, 0, STAND_TOP + 6, W, HORIZON - 8)

    # The side stands: the wedges either side of the pitch. Without these the
    # grass floats in a black void and the whole frame reads as a carpet rather
    # than a ground.
    # As POLYGONS, not a stack of rectangles. Stepping down the slope in 70
    # slices left a visibly staircased edge along the touchline, which is the
    # single most obvious tell that a picture was generated rather than drawn.
    d.polygon([(0, HORIZON), (TOP_L, HORIZON), (BOT_L, H), (0, H)], fill=(11, 13, 18))
    d.polygon([(W, HORIZON), (TOP_R, HORIZON), (BOT_R, H), (W, H)], fill=(11, 13, 18))

    for _ in range(26000):
        y = rng.uniform(HORIZON, H)
        l, r = pitch_edges(y)
        if rng.random() < 0.5:
            if l <= 2:
                continue
            x = rng.uniform(0, l)
        else:
            if r >= W - 2:
                continue
            x = rng.uniform(r, W)
        v = int((92 if lit else 30) * rng.uniform(0.25, 1.5))
        d.point((x, y), fill=(v, v, min(255, int(v * 1.15))))


def hoardings(d: ImageDraw.ImageDraw, lit: bool) -> None:
    """
    Advertising boards along the far touchline.

    A hard line where grass meets crowd is the giveaway of a flat illustration.
    A lit band there separates the two planes and is what real broadcast
    pictures are full of.
    """
    l, r = pitch_edges(HORIZON + 1)
    h = 24
    # Behind the goal, therefore higher in frame. Drawing them level with the
    # goal line painted them straight over the goal and lost it entirely.
    bottom = HORIZON - GOAL_H - 6
    d.rectangle([l - 40, bottom - h, r + 40, bottom], fill=(24, 28, 36) if not lit else (58, 64, 78))
    if lit:
        for i in range(14):
            x0 = (l - 40) + (r - l + 80) * i / 14
            x1 = (l - 40) + (r - l + 80) * (i + 0.86) / 14
            c = rng.choice([(120, 130, 150), (150, 128, 60), (96, 112, 128)])
            d.rectangle([x0, bottom - h + 4, x1, bottom - 4], fill=c)


def pitch(d: ImageDraw.ImageDraw, lit: bool) -> None:
    """The grass, its mowing bands, and only the markings that read at this size."""
    grass = GRASS_LIT if lit else GRASS
    d.polygon([(TOP_L, HORIZON), (TOP_R, HORIZON), (BOT_R, H), (BOT_L, H)], fill=grass)

    bands = 10
    for i in range(bands):
        if i % 2 == 0:
            continue
        f0, f1 = (i / bands) ** 1.6, ((i + 1) / bands) ** 1.6
        y0 = HORIZON + (H - HORIZON) * f0
        y1 = HORIZON + (H - HORIZON) * f1
        l0, r0 = pitch_edges(y0)
        l1, r1 = pitch_edges(y1)
        d.polygon([(l0, y0), (r0, y0), (r1, y1), (l1, y1)],
                  fill=tuple(min(255, int(c * 1.09)) for c in grass))

    line = (216, 236, 220) if lit else (74, 88, 78)
    lw = 4

    def across(f):
        y = HORIZON + (H - HORIZON) * f
        l, r = pitch_edges(y)
        return l, r, y

    l, r, y = across(0.26)
    d.line([(l, y), (r, y)], fill=line, width=lw)
    cw = (r - l) * 0.19
    d.ellipse([W / 2 - cw, y - cw * 0.32, W / 2 + cw, y + cw * 0.32], outline=line, width=lw)

    l0, r0, y0 = across(0.70)
    l1, r1, y1 = across(0.995)
    b0, b1 = (r0 - l0) * 0.30, (r1 - l1) * 0.30
    d.line([(W / 2 - b0, y0), (W / 2 + b0, y0)], fill=line, width=lw)
    d.line([(W / 2 - b0, y0), (W / 2 - b1, y1)], fill=line, width=lw)
    d.line([(W / 2 + b0, y0), (W / 2 + b1, y1)], fill=line, width=lw)


def far_goal(d: ImageDraw.ImageDraw, lit: bool) -> None:
    """
    The goal at the far end, standing on the top edge of the pitch.

    Small — a goal is 7.3m across a 68m pitch, so it is barely a twentieth of
    the frame at that distance — but it is the single object that tells the eye
    how far away the far end is. Without it the pitch had no depth cue at all
    beyond its own converging touchlines.
    """
    gw, gh = GOAL_W, GOAL_H
    cx = W / 2
    l, r = cx - gw / 2, cx + gw / 2
    top = HORIZON - gh

    # Net, behind the frame.
    mesh = (86, 96, 104) if lit else (34, 39, 46)
    for i in range(1, 7):
        x = l + gw * i / 7
        d.line([(x, top + 2), (x, HORIZON)], fill=mesh, width=1)
    for i in range(1, 4):
        y = top + gh * i / 4
        d.line([(l, y), (r, y)], fill=mesh, width=1)

    post = (250, 252, 252) if lit else (112, 120, 128)
    d.line([(l, top), (r, top)], fill=post, width=4)
    d.line([(l, top), (l, HORIZON)], fill=post, width=4)
    d.line([(r, top), (r, HORIZON)], fill=post, width=4)


def pylons(d: ImageDraw.ImageDraw, lit: bool) -> None:
    for fx in PYLONS:
        x = int(W * fx)
        d.line([(x, HEAD_Y), (x, STAND_TOP - 20)], fill=(24, 27, 34), width=9)
        for row in range(3):
            for col in range(5):
                bx, by = x - 62 + col * 31, HEAD_Y - 30 + row * 23
                d.ellipse([bx - 8, by - 8, bx + 8, by + 8],
                          fill=WARM if lit else (44, 48, 56))


def haze(img: Image.Image, lit: bool) -> Image.Image:
    """
    Distance haze: everything near the horizon softens and drifts towards the
    colour of the air.

    THE SHARPNESS TELL. Before this, the far stand was rendered as crisply as
    the near touchline, which no photograph and no painting ever is — and it was
    the clearest signal that the frame had been generated rather than observed.
    Depth here is distance from the horizon line, since that is where the far
    end of the ground sits.
    """
    blurred = img.filter(ImageFilter.GaussianBlur(4.2))
    base = np.asarray(img, dtype=float)
    soft = np.asarray(blurred, dtype=float)

    yy = np.arange(H, dtype=float)[:, None]
    falloff = H * 0.115
    m = np.exp(-np.abs(yy - HORIZON) / falloff)          # 1 at the horizon
    m = np.repeat(m, W, axis=1)[..., None]

    out = base * (1 - m * 0.85) + soft * (m * 0.85)
    tint = np.array([62.0, 70.0, 84.0] if lit else [22.0, 27.0, 36.0])
    return Image.fromarray(
        np.clip(out * (1 - m * 0.34) + tint * (m * 0.34), 0, 255).astype("uint8")
    )


def bloom(img: Image.Image) -> Image.Image:
    """
    Haloes at the lamps, shafts through the air, and pools where they land.

    THIS IS THE REASON THE INTRO IS AN IMAGE AT ALL. A wide soft additive bloom
    over four overlapping pools on a receding plane is a handful of lines here
    and genuinely awkward on a Compose Canvas.
    """
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    g = ImageDraw.Draw(glow)

    # UNEVEN AND WARM. Four identical grey haloes read as a diagram; real banks
    # differ in age, aim and output, and metal-halide light is warm rather than
    # neutral. The variation is fixed rather than random so the two frames stay
    # in register with each other.
    strength = [1.00, 0.82, 1.12, 0.90]
    for fx, k in zip(PYLONS, strength):
        x = int(W * fx)
        halo = (int(196 * k), int(172 * k), int(112 * k))
        g.ellipse(
            [x - 210 * k, HEAD_Y - 170 * k, x + 210 * k, HEAD_Y + 170 * k],
            fill=halo,
        )
        g.polygon(
            [(x - 90, HEAD_Y), (x + 90, HEAD_Y), (x + 430, H), (x - 430, H)],
            fill=(int(30 * k), int(27 * k), int(19 * k)),
        )
        # The pool it throws, nudged off centre so the four do not tile.
        py = int(HORIZON + (H - HORIZON) * (0.36 + 0.09 * (k - 0.9)))
        px = x + int(W * 0.02 * (k - 1.0))
        g.ellipse(
            [px - 380 * k, py - 115 * k, px + 380 * k, py + 115 * k],
            fill=(int(52 * k), int(58 * k), int(38 * k)),
        )
    glow = glow.filter(ImageFilter.GaussianBlur(80))
    return Image.fromarray(
        np.clip(np.asarray(img, int) + np.asarray(glow, int), 0, 255).astype("uint8")
    )


def finish(img: Image.Image) -> Image.Image:
    """Vignette and grain — what stops generated art looking generated."""
    arr = np.asarray(img, dtype=float)
    yy, xx = np.mgrid[0:H, 0:W]
    r = np.sqrt(((xx - W / 2) / (W * 0.80)) ** 2 + ((yy - H * 0.52) / (H * 0.76)) ** 2)
    arr *= np.clip(1.10 - 0.50 * r**2, 0.22, 1.0)[..., None]
    arr = np.clip(arr + np.random.default_rng(7).normal(0, 3.2, (H, W, 1)), 0, 255)
    return Image.fromarray(arr.astype("uint8"))


def build(lit: bool) -> Image.Image:
    img = Image.new("RGB", (W, H), NIGHT_TOP)
    d = ImageDraw.Draw(img)
    sky(d)
    pitch(d, lit)
    stands(d, lit)
    hoardings(d, lit)
    far_goal(d, lit)
    pylons(d, lit)
    # Haze before bloom: the air softens the scene, and the light then hangs in
    # that air rather than being softened by it.
    img = haze(img, lit)
    if lit:
        img = bloom(img)
    return finish(img)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for lit, name in ((False, "intro_stadium_dark"), (True, "intro_stadium_lit")):
        path = OUT / f"{name}.webp"
        build(lit).save(path, "WEBP", quality=84, method=6)
        print(f"{path}  {path.stat().st_size // 1024} KB")

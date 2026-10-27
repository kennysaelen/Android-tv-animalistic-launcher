"""Procedurally generates an original stylized 'pine valley lake' wallpaper for KennysLauncher.

Usage: python make_wallpaper.py [seed] [palette]   palette: day | dusk | night
Writes wallpaper_<palette>_<seed>.png (1920x1080) next to this script.
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1920, 1080
S = 2  # supersampling factor
SHORE = 830

PALETTES = {
    "day": dict(sky_top=(120, 205, 210), sky_light=(190, 240, 235), haze=(130, 205, 200),
                tree_dark=(10, 62, 58), tree_lit=(120, 190, 70), tree_mid=(25, 105, 80),
                water_top=(70, 200, 185), water_bot=(20, 120, 125), grass=(140, 200, 80),
                cabin=(190, 55, 40), cabin_lit=(230, 95, 60), rock=(170, 160, 140), light=(255, 250, 210)),
    "dusk": dict(sky_top=(70, 80, 130), sky_light=(250, 170, 120), haze=(190, 130, 130),
                 tree_dark=(20, 30, 50), tree_lit=(200, 120, 80), tree_mid=(50, 55, 75),
                 water_top=(170, 120, 140), water_bot=(40, 45, 80), grass=(150, 110, 80),
                 cabin=(170, 50, 45), cabin_lit=(240, 120, 70), rock=(140, 110, 110), light=(255, 200, 150)),
    "night": dict(sky_top=(8, 18, 40), sky_light=(40, 80, 120), haze=(40, 70, 100),
                  tree_dark=(4, 12, 22), tree_lit=(40, 90, 110), tree_mid=(10, 30, 45),
                  water_top=(30, 70, 100), water_bot=(5, 15, 30), grass=(30, 60, 60),
                  cabin=(90, 30, 35), cabin_lit=(150, 60, 50), rock=(60, 70, 85), light=(180, 210, 255)),
}


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(lerp(a, b, t)) for a, b in zip(c1, c2))


def ridge(rng, n, base, amp, octaves=5):
    """Smooth 1D ridge line as sum of random sines."""
    xs = np.linspace(0, 1, n)
    y = np.full(n, base, dtype=float)
    for o in range(octaves):
        f = 2 ** o * rng.uniform(0.8, 1.4)
        y += amp / (1.7 ** o) * np.sin(2 * math.pi * (f * xs + rng.random()))
    return y


class Sil:
    """Draws a silhouette in local units (y up, origin at the feet), scaled and optionally mirrored."""

    def __init__(self, draw, ox, oy, scale, color, flip=False):
        self.d, self.ox, self.oy, self.sc, self.c, self.f = draw, ox, oy, scale, color, -1 if flip else 1

    def p(self, x, y):
        return (sp(self.ox + self.f * x * self.sc), sp(self.oy - y * self.sc))

    def poly(self, pts):
        self.d.polygon([self.p(x, y) for x, y in pts], fill=self.c)

    def ell(self, cx, cy, rx, ry):
        (x1, y1), (x2, y2) = self.p(cx - rx, cy + ry), self.p(cx + rx, cy - ry)
        self.d.ellipse([min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)], fill=self.c)

    def line(self, pts, wdt):
        self.d.line([self.p(x, y) for x, y in pts], fill=self.c, width=max(1, int(sp(wdt * self.sc))), joint="curve")


def stag(s, head_down=False):
    s.ell(0, 0.58, 0.46, 0.17)
    s.poly([(0.30, 0.66), (0.48, 0.70), (0.50, 0.62), (0.40, 0.52)])  # rump
    s.line([(0.47, 0.66), (0.53, 0.60)], 0.05)  # tail
    for x, kx in ((-0.30, -0.33), (-0.20, -0.18), (0.26, 0.30), (0.35, 0.36)):
        s.line([(x, 0.50), (kx, 0.25), (x * 0.95, 0.0)], 0.065)
    if head_down:
        s.poly([(-0.30, 0.68), (-0.44, 0.70), (-0.66, 0.22), (-0.56, 0.18), (-0.20, 0.50)])
        s.ell(-0.64, 0.16, 0.09, 0.06)
        return
    s.poly([(-0.32, 0.70), (-0.48, 0.98), (-0.36, 1.02), (-0.18, 0.62)])  # neck
    s.ell(-0.48, 1.00, 0.10, 0.065)
    s.poly([(-0.52, 1.03), (-0.68, 0.95), (-0.66, 0.92), (-0.50, 0.95)])  # snout
    s.poly([(-0.38, 1.04), (-0.30, 1.12), (-0.35, 1.02)])  # ear
    s.line([(-0.44, 1.05), (-0.38, 1.30), (-0.30, 1.48), (-0.22, 1.58)], 0.03)
    s.line([(-0.38, 1.30), (-0.50, 1.40)], 0.025)
    s.line([(-0.33, 1.42), (-0.42, 1.56)], 0.025)
    s.line([(-0.49, 1.06), (-0.60, 1.28), (-0.64, 1.46)], 0.03)
    s.line([(-0.60, 1.28), (-0.72, 1.34)], 0.025)
    s.line([(-0.62, 1.40), (-0.54, 1.52)], 0.025)


def doe(s):
    s.ell(0, 0.50, 0.36, 0.14)
    for x in (-0.24, -0.16, 0.20, 0.28):
        s.line([(x, 0.45), (x * 0.95, 0.0)], 0.045)
    s.poly([(-0.25, 0.58), (-0.38, 0.84), (-0.28, 0.88), (-0.14, 0.54)])
    s.ell(-0.38, 0.86, 0.08, 0.055)
    s.poly([(-0.42, 0.88), (-0.54, 0.82), (-0.40, 0.82)])
    s.poly([(-0.32, 0.90), (-0.24, 1.00), (-0.30, 0.88)])
    s.poly([(-0.36, 0.91), (-0.34, 1.02), (-0.39, 0.90)])


def bear(s):
    s.ell(0, 0.50, 0.56, 0.30)
    s.ell(-0.18, 0.66, 0.26, 0.20)  # shoulder hump
    s.ell(-0.62, 0.52, 0.17, 0.14)
    s.poly([(-0.72, 0.52), (-0.86, 0.44), (-0.84, 0.40), (-0.66, 0.42)])  # muzzle
    s.ell(-0.58, 0.67, 0.05, 0.05)
    for x, w_ in ((-0.38, 0.13), (-0.12, 0.13), (0.24, 0.14), (0.44, 0.13)):
        s.poly([(x - w_ / 2, 0.40), (x + w_ / 2, 0.40), (x + w_ / 2, 0.0), (x - w_ / 2 - 0.03, 0.0)])


def fox(s):
    s.ell(0, 0.32, 0.30, 0.11)
    for x in (-0.20, -0.13, 0.17, 0.23):
        s.line([(x, 0.28), (x, 0.0)], 0.035)
    s.poly([(-0.22, 0.36), (-0.38, 0.52), (-0.30, 0.56), (-0.12, 0.40)])
    s.poly([(-0.44, 0.52), (-0.60, 0.46), (-0.40, 0.44)])  # snout
    s.ell(-0.38, 0.52, 0.08, 0.06)
    s.poly([(-0.40, 0.56), (-0.38, 0.68), (-0.33, 0.56)])
    s.poly([(-0.33, 0.56), (-0.28, 0.67), (-0.27, 0.54)])
    s.poly([(0.26, 0.36), (0.50, 0.40), (0.72, 0.30), (0.62, 0.22), (0.40, 0.26), (0.26, 0.28)])  # bushy tail


def heron(s):
    s.ell(0, 0.80, 0.22, 0.10)
    s.poly([(0.18, 0.82), (0.36, 0.70), (0.20, 0.76)])  # tail feathers
    s.line([(-0.16, 0.84), (-0.24, 1.02), (-0.16, 1.16), (-0.22, 1.28)], 0.035)  # S-neck
    s.ell(-0.24, 1.30, 0.05, 0.035)
    s.poly([(-0.27, 1.31), (-0.48, 1.27), (-0.27, 1.27)])  # beak
    s.line([(-0.02, 0.72), (-0.04, 0.0)], 0.028)
    s.line([(0.04, 0.72), (0.06, 0.0)], 0.028)


def bird(d, x, y, size, color):
    pts = [(x - size, y - size * 0.2), (x - size * 0.45, y - size * 0.45), (x, y), (x + size * 0.45, y - size * 0.45), (x + size, y - size * 0.2)]
    d.line([(sp(a), sp(b)) for a, b in pts], fill=color, width=sp(2), joint="curve")


def sp(v):
    return v * S


def main(seed=7, palette="day"):
    rng = random.Random(seed)
    nrng = np.random.default_rng(seed)
    P = PALETTES[palette]
    w, h = W * S, H * S

    # Sky: diagonal gradient toward light source in the top-right.
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    t = np.clip((xx / w) * 0.75 + (1 - yy / h) * 0.25, 0, 1) ** 1.6
    sky = np.zeros((h, w, 3), np.float32)
    for i in range(3):
        sky[..., i] = P["sky_top"][i] + (P["sky_light"][i] - P["sky_top"][i]) * t
    img = Image.fromarray(sky.astype(np.uint8), "RGB")
    d = ImageDraw.Draw(img)

    # Distant mountain layers (hazy, lighter further away).
    for layer, (base, amp, haze_t) in enumerate([(330, 90, 0.78), (420, 70, 0.6)]):
        r = ridge(nrng, 400, base, amp)
        r += np.linspace(-60, 40, 400)
        pts = [(sp(i * W / 399), sp(float(r[i]))) for i in range(400)] + [(w, sp(SHORE)), (0, sp(SHORE))]
        col = mix(P["tree_mid"], P["haze"], haze_t)
        d.polygon(pts, fill=col)

    # Big forested hillside on the left, sloping to the lake.
    hill = ridge(nrng, 400, 0, 30, 4)
    hx = np.linspace(0, 1, 400)
    hill += 40 + 520 * hx ** 1.25  # high on the left, lower to the right
    hill_at = lambda x: float(np.interp(min(max(x, 0), W) / W, hx, hill))

    # Forest: rows of stylized tiered two-tone pines, far to near.
    sil_col = mix(P["tree_dark"], (0, 0, 0), 0.55)
    rows = 42
    doe_row = int(rows * 0.8)
    clearing = (1295, 1395)
    for ri in range(rows):
        depth = ri / (rows - 1)  # 0 = far (top), 1 = near (shore)
        size = lerp(16, 52, depth ** 1.6)
        x = rng.uniform(-size, 0)
        while x < W + size:
            th = size * rng.uniform(1.7, 2.3)
            tw = size * rng.uniform(0.85, 1.1)
            top_limit = hill_at(x)
            y_base = lerp(top_limit + th * 0.9, SHORE + 4, depth ** 0.9) + rng.uniform(-8, 8)
            if y_base > SHORE + 8 or (ri > doe_row and clearing[0] < x < clearing[1]):
                x += tw * 0.5
                continue
            xn = min(max(x / W, 0.0), 1.0)
            light = xn ** 1.2 * 0.9 + rng.uniform(-0.1, 0.12)
            haze_t = (1 - depth) ** 1.4 * 0.62 * (0.7 + 0.3 * xn)
            dark = mix(mix(P["tree_dark"], P["tree_mid"], light * 0.7), P["haze"], haze_t)
            lit = mix(mix(P["tree_mid"], P["tree_lit"], light), P["haze"], haze_t * 0.85)
            cx = x
            tiers = 3
            for k in range(tiers):  # stacked tiers give a pine silhouette
                t_top = y_base - th + k * th * 0.24
                t_bot = y_base - (tiers - 1 - k) * th * 0.16
                half = tw / 2 * (0.6 + 0.4 * (k + 1) / tiers)
                d.polygon([(sp(cx), sp(t_top)), (sp(cx - half), sp(t_bot)), (sp(cx), sp(t_bot))], fill=dark)
                d.polygon([(sp(cx), sp(t_top)), (sp(cx + half), sp(t_bot)), (sp(cx), sp(t_bot))], fill=lit)
            x += tw * rng.uniform(0.45, 0.7)
        if ri == doe_row:  # a doe peeking out between the trees
            gx = sum(clearing) / 2
            gy = lerp(hill_at(gx) + size * 2, SHORE + 4, depth ** 0.9)
            floor = mix(mix(P["tree_dark"], P["tree_mid"], 0.5), P["haze"], 0.1)
            d.polygon([(sp(clearing[0] + 15), sp(gy - 40)), (sp(clearing[1] - 15), sp(gy - 40)), (sp(clearing[1] + 10), sp(SHORE + 4)), (sp(clearing[0] - 10), sp(SHORE + 4))], fill=floor)
            doe(Sil(d, gx, gy, 78, mix(sil_col, P["haze"], 0.12)))

    # Power line poles along the shore.
    poles = [760, 1010, 1270, 1530, 1790]
    for px in poles:
        d.line([(sp(px), sp(SHORE - 85)), (sp(px), sp(SHORE + 2))], fill=mix(P["rock"], (60, 60, 60), 0.3), width=sp(3))
        d.line([(sp(px - 10), sp(SHORE - 80)), (sp(px + 10), sp(SHORE - 80))], fill=mix(P["rock"], (60, 60, 60), 0.3), width=sp(2))
    for a, b in zip([640] + poles, poles):
        for dy in (-80, -74):
            pts = []
            for k in range(21):
                tt = k / 20
                pts.append((sp(lerp(a, b, tt)), sp(SHORE + dy + 14 * math.sin(math.pi * tt))))
            d.line(pts, fill=mix(P["sky_light"], (255, 255, 255), 0.3), width=S)

    # Grassy shoreline strip.
    d.polygon([(0, sp(SHORE - 6)), (w, sp(SHORE - 10)), (w, sp(SHORE + 10)), (0, sp(SHORE + 12))], fill=P["grass"])

    # Animals along the shore (drawn before the lake so they get reflected).
    bear(Sil(d, 300, SHORE + 6, 115, sil_col))
    stag(Sil(d, 1130, SHORE + 6, 120, sil_col))
    stag(Sil(d, 930, SHORE + 12, 95, sil_col, flip=True), head_down=True)

    # Red A-frame cabin on stilts.
    cx, base, cw, ch = 610, SHORE + 4, 190, 200
    roof_top = (cx, base - ch)
    for sx in (-80, -40, 0, 40, 80):
        d.line([(sp(cx + sx), sp(base - 30)), (sp(cx + sx), sp(base + 22))], fill=(70, 45, 40), width=sp(4))
    d.polygon([(sp(cx - 120), sp(base - 30)), (sp(cx + 140), sp(base - 30)), (sp(cx + 140), sp(base - 22)), (sp(cx - 120), sp(base - 22))], fill=(110, 70, 55))
    d.polygon([sp(roof_top[0]), sp(roof_top[1]), sp(cx - cw / 2), sp(base - 30), sp(cx), sp(base - 30)], fill=P["cabin"])
    d.polygon([sp(roof_top[0]), sp(roof_top[1]), sp(cx + cw / 2), sp(base - 30), sp(cx), sp(base - 30)], fill=P["cabin_lit"])
    for k in range(1, 9):  # plank lines
        tt = k / 9
        d.line([(sp(cx), sp(roof_top[1])), (sp(lerp(cx - cw / 2, cx + cw / 2, tt)), sp(base - 30))], fill=mix(P["cabin"], (0, 0, 0), 0.25), width=S)
    d.rectangle([sp(cx - 9), sp(base - 120), sp(cx + 9), sp(base - 98)], fill=(250, 230, 170) if palette != "day" else (60, 40, 40))
    d.rectangle([sp(cx + 30), sp(base - 70), sp(cx + 60), sp(base - 30)], fill=mix(P["cabin"], (0, 0, 0), 0.5))
    for k in range(5):  # log pile
        d.ellipse([sp(cx - 150 + k % 3 * 14), sp(base - 50 + k // 3 * 12), sp(cx - 136 + k % 3 * 14), sp(base - 38 + k // 3 * 12)], fill=(160, 120, 80), outline=(90, 60, 40))

    # Lake with mirrored, blurred reflection.
    above = img.crop((0, sp(SHORE - 520), w, sp(SHORE)))
    refl = above.transpose(Image.FLIP_TOP_BOTTOM).resize((w, h - sp(SHORE + 10)))
    refl = refl.filter(ImageFilter.GaussianBlur(sp(4)))
    water = np.zeros((h - sp(SHORE + 10), w, 3), np.float32)
    ty = np.linspace(0, 1, water.shape[0])[:, None]
    for i in range(3):
        water[..., i] = P["water_top"][i] + (P["water_bot"][i] - P["water_top"][i]) * ty
    blended = (np.asarray(refl, np.float32) * 0.45 + water * 0.55)
    lake = Image.fromarray(blended.clip(0, 255).astype(np.uint8))
    ld = ImageDraw.Draw(lake)
    for _ in range(260):  # ripples
        ry = rng.uniform(0, lake.height) ** 1.0
        rx = rng.uniform(0, w)
        rl = rng.uniform(30, 160) * S * (0.4 + ry / lake.height)
        ld.line([(rx, ry), (rx + rl, ry)], fill=mix(P["water_top"], (255, 255, 255), 0.35), width=S)
    img.paste(lake, (0, sp(SHORE + 10)))
    d = ImageDraw.Draw(img)
    heron(Sil(d, 820, 985, 95, sil_col))
    for k in range(4):
        bird(d, 1280 + k * 55 + rng.uniform(-10, 10), 170 + abs(k - 1.5) * 26, 16 - k * 1.5, sil_col)

    # Foreground rocks and bank on the right.
    bank = [(sp(1380), h), (sp(1560), sp(960)), (sp(1700), sp(900)), (sp(1830), sp(780)), (w, sp(720)), (w, h)]
    d.polygon(bank, fill=mix(P["grass"], P["tree_dark"], 0.55))
    d.polygon([(sp(1560), sp(960)), (sp(1700), sp(900)), (sp(1830), sp(780)), (w, sp(720)), (w, sp(760)), (sp(1840), sp(820)), (sp(1710), sp(940)), (sp(1580), sp(990))], fill=mix(P["grass"], P["tree_lit"], 0.4))
    for _ in range(14):
        rx = rng.uniform(1050, 1800)
        ry = rng.uniform(890, 1060)
        rw = rng.uniform(35, 110) * (0.6 + (ry - 890) / 300)
        rh = rw * rng.uniform(0.4, 0.6)
        d.ellipse([sp(rx - rw), sp(ry - rh), sp(rx + rw), sp(ry + rh * 0.6)], fill=mix(P["rock"], (0, 0, 0), 0.25))
        d.ellipse([sp(rx - rw * 0.9), sp(ry - rh), sp(rx + rw * 0.8), sp(ry)], fill=P["rock"])
    for _ in range(90):  # grass tufts on the bank
        gx = rng.uniform(1600, 1920)
        gy = rng.uniform(720, 1000)
        if gy < 1080 - (1920 - gx) * 0.9:
            continue
        for k in range(5):
            ang = math.radians(rng.uniform(-35, 35))
            ln = rng.uniform(15, 40)
            d.line([(sp(gx), sp(gy)), (sp(gx + ln * math.sin(ang)), sp(gy - ln * math.cos(ang)))], fill=mix(P["grass"], P["tree_lit"], rng.random()), width=sp(2))
    fox(Sil(d, 1790, 832, 75, sil_col))

    # Soft light rays from the top-right.
    rays = Image.new("L", (w, h), 0)
    rd = ImageDraw.Draw(rays)
    for _ in range(7):
        x0 = rng.uniform(0.55, 1.05) * w
        spread = rng.uniform(0.03, 0.08) * w
        rd.polygon([(x0, 0), (x0 + spread, 0), (x0 - 0.55 * w + spread * 3, h), (x0 - 0.55 * w, h)], fill=int(rng.uniform(18, 40)))
    rays = rays.filter(ImageFilter.GaussianBlur(sp(40)))
    light = Image.new("RGB", (w, h), P["light"])
    img = Image.composite(light, img, rays)

    # UI legibility: darken top-left (clock) and bottom (app row) gently.
    arr = np.asarray(img, np.float32)
    vy, vx = np.mgrid[0:h, 0:w].astype(np.float32)
    shade = 1 - 0.35 * np.clip(1 - vx / (w * 0.45), 0, 1) * np.clip(1 - vy / (h * 0.35), 0, 1)
    shade *= 1 - 0.55 * np.clip((vy / h - 0.72) / 0.28, 0, 1) ** 1.5
    arr *= shade[..., None]
    arr += nrng.normal(0, 3.0, arr.shape[:2])[..., None]  # subtle grain
    img = Image.fromarray(arr.clip(0, 255).astype(np.uint8)).resize((W, H), Image.LANCZOS)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"wallpaper_{palette}_{seed}.png")
    img.save(out, optimize=True)
    print(out)


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 7, sys.argv[2] if len(sys.argv) > 2 else "day")


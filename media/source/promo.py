"""Promo images and video for Signal OPS IRL, drawn in the app's own HUD/CRT style."""
import math, random, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

F = "/home/claude/promo/fonts/"
ORB = F + "fontsource-orbitron-5.3.0/files/orbitron-latin-900-normal.woff"
ORB7 = F + "fontsource-orbitron-5.3.0/files/orbitron-latin-700-normal.woff"
MONO = F + "fontsource-jetbrains-mono-5.3.0/files/jetbrains-mono-latin-400-normal.woff"
MONOB = F + "fontsource-jetbrains-mono-5.3.0/files/jetbrains-mono-latin-700-normal.woff"
VT = F + "fontsource-vt323-5.3.0/files/vt323-latin-400-normal.woff"

BG = (5, 8, 12)
SURF = (10, 17, 24)
CYAN = (0, 229, 255)
GREEN = (57, 255, 136)
AMBER = (255, 179, 0)
RED = (255, 45, 85)
VIOLET = (124, 77, 255)
TEXT = (207, 233, 238)
DIM = (127, 163, 176)
OUTLINE = (30, 74, 85)

URL = "github.com/tonyispht/signalopsirl-for-android"
_fc = {}


def font(path, size):
    k = (path, int(size))
    if k not in _fc:
        _fc[k] = ImageFont.truetype(path, max(6, int(size)))
    return _fc[k]


def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def ease(x):
    x = clamp(x)
    return 1 - (1 - x) ** 3


def mix(c, k):
    return tuple(int(v * k) for v in c)


def tw(d, s, f):
    b = d.textbbox((0, 0), s, font=f)
    return b[2] - b[0]


def text_c(d, cx, y, s, f, fill):
    d.text((cx - tw(d, s, f) / 2, y), s, font=f, fill=fill)


# ---------------------------------------------------------------- background

_bg_cache = {}


def background(W, H):
    if (W, H) in _bg_cache:
        return _bg_cache[(W, H)].copy()
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    step = max(24, int(min(W, H) / 18))
    for x in range(0, W, step):
        d.line([(x, 0), (x, H)], fill=(9, 20, 28), width=1)
    for y in range(0, H, step):
        d.line([(0, y), (W, y)], fill=(9, 20, 28), width=1)
    _bg_cache[(W, H)] = im
    return im.copy()


_post_cache = {}


def post(im):
    """Glow + scanlines + vignette: the app's MOVIE FX look."""
    W, H = im.size
    glow = im.resize((W // 4, H // 4)).filter(ImageFilter.GaussianBlur(6)).resize((W, H))
    from PIL import ImageChops
    im = ImageChops.add(im, glow.point(lambda v: int(v * 0.55)))
    if (W, H) not in _post_cache:
        ov = Image.new("L", (W, H), 255)
        od = ImageDraw.Draw(ov)
        for y in range(0, H, 4):
            od.line([(0, y), (W, y)], fill=212)
        vig = Image.radial_gradient("L").resize((W, H))
        vig = vig.point(lambda v: 255 - int(clamp((v - 120) / 135) * 150))
        _post_cache[(W, H)] = ImageChops.multiply(ov, vig)
    mask = _post_cache[(W, H)]
    return ImageChops.multiply(im, Image.merge("RGB", (mask, mask, mask)))


def hud_frame(d, x0, y0, x1, y1, col, w=2, cut=None):
    c = cut or int(min(x1 - x0, y1 - y0) * 0.08)
    pts = [(x0, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1), (x0 + c, y1), (x0, y1 - c), (x0, y0)]
    d.line(pts, fill=col, width=w, joint="curve")


def corner_brackets(d, W, H, col, s):
    m, L = int(28 * s), int(70 * s)
    w = max(2, int(3 * s))
    for (x, y, dx, dy) in [(m, m, 1, 1), (W - m, m, -1, 1), (m, H - m, 1, -1), (W - m, H - m, -1, -1)]:
        d.line([(x, y + dy * L), (x, y), (x + dx * L, y)], fill=col, width=w)


# ---------------------------------------------------------------- elements

BLIPS = [  # angle (deg), radius (0-1), colour, label
    (30, 0.35, CYAN, "EARBUDS"), (95, 0.62, GREEN, "SENSOR 21.4C"), (150, 0.82, CYAN, "SMARTWATCH"),
    (205, 0.48, AMBER, "UNNAMED x3"), (250, 0.72, CYAN, "PHONE"), (305, 0.28, GREEN, "FITNESS BAND"),
    (340, 0.88, RED, "ITEM TRACKER"), (120, 0.2, CYAN, "SPEAKER"), (60, 0.78, GREEN, "SMART PLUG"),
]


def radar(d, cx, cy, R, t, s, labels=True, reveal=1.0):
    for k in (1.0, 0.75, 0.5, 0.25):
        d.ellipse([cx - R * k, cy - R * k, cx + R * k, cy + R * k], outline=OUTLINE, width=max(1, int(2 * s)))
    d.line([(cx - R, cy), (cx + R, cy)], fill=OUTLINE, width=1)
    d.line([(cx, cy - R), (cx, cy + R)], fill=OUTLINE, width=1)
    ang = (t * 140) % 360
    for i in range(28):  # sweep trail
        a = math.radians(ang - i * 1.6)
        k = 1 - i / 28
        d.line([(cx, cy), (cx + R * math.cos(a), cy + R * math.sin(a))], fill=mix(CYAN, 0.55 * k), width=max(2, int(4 * s)))
    n = int(len(BLIPS) * reveal + 0.999)
    for i, (a, r, col, lab) in enumerate(BLIPS[:n]):
        since = ((ang - a) % 360) / 360.0
        bright = 0.45 + 0.55 * (1 - since)
        x = cx + R * r * math.cos(math.radians(a))
        y = cy + R * r * math.sin(math.radians(a))
        rr = (7 + 6 * (1 - since)) * s
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=mix(col, bright))
        if labels:
            d.text((x + 14 * s, y - 12 * s), lab, font=font(MONOB, 20 * s), fill=mix(col, 0.35 + 0.65 * bright))


def phone(W, H, cx, cy, ph, t, mode, s):
    """A stylised phone showing the Signals list. mode: list | alert | levelup."""
    pw = ph * 0.49
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    x0, y0, x1, y1 = cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2
    d.rounded_rectangle([x0, y0, x1, y1], radius=pw * 0.12, fill=(14, 20, 27), outline=(60, 80, 92), width=max(3, int(5 * s)))
    m = pw * 0.05
    sx0, sy0, sx1, sy1 = x0 + m, y0 + m * 1.6, x1 - m, y1 - m * 1.6
    d.rounded_rectangle([sx0, sy0, sx1, sy1], radius=pw * 0.08, fill=BG)
    u = (sx1 - sx0) / 100.0  # screen unit
    # header
    d.text((sx0 + 5 * u, sy0 + 5 * u), "SIGNAL OPS IRL", font=font(ORB, 6.2 * u), fill=CYAN)
    d.text((sx0 + 5 * u, sy0 + 13 * u), "LV 12  //  FIELD OPERATIVE", font=font(MONOB, 3.6 * u), fill=VIOLET)
    bx0, by = sx0 + 5 * u, sy0 + 19 * u
    xp = 0.62 if mode != "levelup" else clamp(0.62 + ease(t) * 0.38)
    d.rectangle([bx0, by, sx1 - 5 * u, by + 2.2 * u], outline=OUTLINE)
    d.rectangle([bx0, by, bx0 + (sx1 - 10 * u - sx0) * xp, by + 2.2 * u], fill=VIOLET)
    # status card
    cy0 = sy0 + 25 * u
    hud_frame(d, sx0 + 5 * u, cy0, sx1 - 5 * u, cy0 + 17 * u, OUTLINE, max(1, int(0.5 * u)), int(3 * u))
    d.ellipse([sx0 + 9 * u, cy0 + 4.2 * u, sx0 + 12 * u, cy0 + 7.2 * u], fill=GREEN)
    d.text((sx0 + 14 * u, cy0 + 3 * u), "SCANNING", font=font(MONOB, 4.2 * u), fill=GREEN)
    d.text((sx0 + 9 * u, cy0 + 9.5 * u), "SIGNALS 47   NEW 9   KINDS 14", font=font(MONO, 3.4 * u), fill=DIM)
    # rows
    rows = [("EARBUDS", "-48 dBm", CYAN, "AUDIO"), ("SMARTWATCH", "-55 dBm", CYAN, "WEARABLE"),
            ("SENSOR 21.4C 48%", "-61 dBm", GREEN, "SENSOR"), ("ITEM TRACKER", "-63 dBm", RED, "TRACKER"),
            ("PHONE", "-70 dBm", CYAN, "PHONE"), ("SMART PLUG", "-74 dBm", GREEN, "HOME"),
            ("UNNAMED x3", "-81 dBm", AMBER, "ROTATING"), ("FITNESS BAND", "-84 dBm", CYAN, "WEARABLE")]
    ry = cy0 + 21 * u
    d.text((sx0 + 5 * u, ry), "// SIGNALS", font=font(MONOB, 4 * u), fill=CYAN)
    ry += 7 * u
    shown = len(rows) if mode != "list" else int(clamp(t) * len(rows) + 0.999)
    for i, (nm, rs, col, kind) in enumerate(rows[:shown]):
        hy = ry + i * 13 * u
        if hy + 11 * u > sy1 - 4 * u:
            break
        hot = mode == "alert" and kind == "TRACKER"
        hud_frame(d, sx0 + 5 * u, hy, sx1 - 5 * u, hy + 11 * u, RED if hot else OUTLINE, max(1, int(0.5 * u)), int(2 * u))
        d.rectangle([sx0 + 5 * u, hy + 2 * u, sx0 + 6.2 * u, hy + 9 * u], fill=col)
        d.text((sx0 + 9 * u, hy + 1.6 * u), nm, font=font(MONOB, 3.9 * u), fill=TEXT)
        d.text((sx0 + 9 * u, hy + 6.4 * u), kind, font=font(MONO, 3 * u), fill=col)
        d.text((sx1 - 7 * u - tw(d, rs, font(MONO, 3.2 * u)), hy + 3.8 * u), rs, font=font(MONO, 3.2 * u), fill=DIM)
    if mode == "alert":
        on = int(t * 6) % 2 == 0
        if on:
            d.rounded_rectangle([sx0, sy0, sx1, sy1], radius=pw * 0.08, outline=RED, width=int(2.4 * u))
        ay = sy1 - 30 * u
        d.rectangle([sx0 + 4 * u, ay, sx1 - 4 * u, ay + 25 * u], fill=(40, 6, 14), outline=RED, width=max(1, int(0.6 * u)))
        text_c(d, (sx0 + sx1) / 2, ay + 4 * u, "TRACKER FOLLOWING?", font(ORB, 5.2 * u), RED)
        text_c(d, (sx0 + sx1) / 2, ay + 13 * u, "SEEN AT 4 PLACES  //  42 MIN", font(MONO, 3.3 * u), TEXT)
        text_c(d, (sx0 + sx1) / 2, ay + 18.5 * u, "LOCATE IT  >>", font(MONOB, 3.6 * u), AMBER)
    if mode == "levelup":
        a = clamp((t - 0.2) * 3)
        if a > 0:
            ly = (sy0 + sy1) / 2 - 18 * u
            d.rectangle([sx0, ly - 4 * u, sx1, ly + 36 * u], fill=(20, 10, 40))
            text_c(d, (sx0 + sx1) / 2, ly, "LEVEL UP", font(ORB, 9 * u), mix(VIOLET, 0.6 + 0.4 * a))
            text_c(d, (sx0 + sx1) / 2, ly + 12 * u, "13", font(ORB, 14 * u), (255, 255, 255))
            text_c(d, (sx0 + sx1) / 2, ly + 29 * u, "+250 XP  //  NEW TITLE UNLOCKED", font(MONOB, 3.2 * u), AMBER)
    return im


def confetti(d, W, H, t, s, seed=7):
    rnd = random.Random(seed)
    cols = [CYAN, GREEN, AMBER, VIOLET, RED, (255, 255, 255)]
    for i in range(240):
        side = i % 2
        vx = (rnd.uniform(0.25, 0.9) * (1 if side == 0 else -1)) * W
        vy = -rnd.uniform(1.4, 2.4) * H
        tt = clamp(t - rnd.uniform(0, 0.25), 0, 2)
        x = (0 if side == 0 else W) + vx * tt * 0.9
        y = H + vy * tt + 1.6 * H * tt * tt
        if tt <= 0 or y > H + 20:
            continue
        r = rnd.uniform(5, 11) * s
        a = tt * rnd.uniform(4, 12)
        dx, dy = r * math.cos(a), r * 0.45
        d.polygon([(x - dx, y - dy), (x + dx, y - dy), (x + dx * 0.6, y + dy), (x - dx * 0.6, y + dy)], fill=rnd.choice(cols))


def caption(d, x, y, kicker, title, sub, s, align="left", W=None, a=1.0):
    fk, ft, fs = font(MONOB, 30 * s), font(ORB, 96 * s), font(MONO, 34 * s)
    lines = title.split("\n")
    if align == "center":
        text_c(d, W / 2, y, kicker, fk, mix(CYAN, a))
        yy = y + 52 * s
        for ln in lines:
            text_c(d, W / 2, yy, ln, ft, mix((255, 255, 255), a))
            yy += 112 * s
        for ln in sub.split("\n"):
            text_c(d, W / 2, yy + 14 * s, ln, fs, mix(DIM, a))
            yy += 48 * s
    else:
        d.text((x, y), kicker, font=fk, fill=mix(CYAN, a))
        yy = y + 52 * s
        for ln in lines:
            d.text((x, yy), ln, font=ft, fill=mix((255, 255, 255), a))
            yy += 112 * s
        for ln in sub.split("\n"):
            d.text((x, yy + 14 * s), ln, font=fs, fill=mix(DIM, a))
            yy += 48 * s


# ---------------------------------------------------------------- scenes

def scene_boot(W, H, t, s):
    im = background(W, H)
    d = ImageDraw.Draw(im)
    lines = ["SIGNAL OPS IRL // FIELD INTEL UNIT", "> RADIO SUBSYSTEM ............ ONLINE",
             "> SIGNATURE DATABASE ......... LOADED", "> ADVERTISING DECODER ........ ARMED",
             "> SENSOR PARSERS ............. ARMED", "> STANDING BY"]
    total = sum(len(l) for l in lines)
    n = int(ease(t / 0.85) * total)
    fsz = (46 if W > H else 40) * s
    y = H * 0.32
    x = (W - tw(d, lines[1], font(VT, fsz))) / 2
    for i, l in enumerate(lines):
        if n <= 0:
            break
        part = l[:n]
        n -= len(l)
        cur = "█" if 0 < len(part) < len(l) or (n <= 0 and int(t * 8) % 2) else ""
        d.text((x, y), part + cur, font=font(VT, fsz * (1.25 if i == 0 else 1)), fill=CYAN if i == 0 else GREEN)
        y += fsz * (1.6 if i == 0 else 1.25)
    return im


def scene_radar(W, H, t, s, gt):
    im = background(W, H)
    d = ImageDraw.Draw(im)
    a = ease(t * 3)
    if W == H:
        caption(d, 0, H * 0.05, "// SCAN THE AIR AROUND YOU", "SEE THE\nINVISIBLE", "Every earbud, watch, sensor\nand tracker nearby, live.", s * 0.78, "center", W, a=a)
        radar(d, W / 2, H * 0.69, H * 0.28, gt, s * 0.8, reveal=clamp(t * 1.4))
    elif W > H:
        radar(d, W * 0.66, H * 0.52, H * 0.40, gt, s, reveal=clamp(t * 1.4))
        caption(d, W * 0.07, H * 0.30, "// SCAN THE AIR AROUND YOU", "SEE THE\nINVISIBLE", "Every earbud, watch, sensor and\ntracker nearby, live on radar.", s, a=a)
    else:
        caption(d, 0, H * 0.10, "// SCAN THE AIR AROUND YOU", "SEE THE\nINVISIBLE", "Every earbud, watch, sensor\nand tracker nearby, live.", s, "center", W, a=a)
        radar(d, W / 2, H * 0.66, W * 0.40, gt, s, reveal=clamp(t * 1.4))
    return im


def _phone_scene(W, H, t, s, mode, kicker, title, sub, under=None):
    im = background(W, H)
    d = ImageDraw.Draw(im)
    if under:
        under(d)
    a = ease(t * 3)
    if W == H:
        caption(d, 0, H * 0.05, kicker, title, sub, s * 0.78, "center", W, a=a)
        p = phone(W, H, W / 2, H * 0.715, H * 0.54, t, mode, s)
    elif W > H:
        caption(d, W * 0.07, H * 0.30, kicker, title, sub, s, a=a)
        p = phone(W, H, W * 0.70, H * 0.52, H * 0.86, t, mode, s)
    else:
        caption(d, 0, H * 0.07, kicker, title, sub, s, "center", W, a=a)
        p = phone(W, H, W / 2, H * 0.66, H * 0.58, t, mode, s)
    im.paste(p, (0, 0), p)
    return im


def scene_identify(W, H, t, s):
    return _phone_scene(W, H, t, s, "list", "// DECODE EVERY SIGNAL", "KNOW WHAT'S\nAROUND YOU",
                        "Identifies device types, decodes\nsensors, names makers.")


def scene_protect(W, H, t, s):
    return _phone_scene(W, H, t, s, "alert", "// PERSONAL SECURITY", "SPOT TRACKERS\nFOLLOWING YOU",
                        "Tracker-follow alerts, home sweeps\nand pairing checks for your own gear.")


def scene_level(W, H, t, s):
    im = _phone_scene(W, H, t, s, "levelup", "// IT'S A GAME", "LEVEL UP\nIRL",
                      "XP, 20 achievements, titles,\ndaily quests and streaks.",
                      under=(lambda d: confetti(d, W, H, (t - 0.25) * 1.3, s)) if t > 0.25 else None)
    return im


def scene_end(W, H, t, s, gt):
    im = background(W, H)
    d = ImageDraw.Draw(im)
    a = ease(t * 2.5)
    R = min(W, H) * 0.36
    radar(d, W / 2, H / 2, R, gt, s * 0.8, labels=False)
    d.rectangle([0, H / 2 - 210 * s, W, H / 2 + 230 * s], fill=BG)
    text_c(d, W / 2, H / 2 - 170 * s, "SIGNAL OPS IRL", font(ORB, (120 if W > H else 92) * s), mix(CYAN, a))
    text_c(d, W / 2, H / 2 - 30 * s, "THE BLUETOOTH WORLD IS YOUR GAME BOARD", font(MONOB, (34 if W > H else 26) * s), mix(TEXT, a))
    tag = "FREE  •  OPEN SOURCE  •  NO ADS  •  ANDROID 8+"
    text_c(d, W / 2, H / 2 + 30 * s, tag, font(MONOB, (30 if W > H else 24) * s), mix(GREEN, a))
    text_c(d, W / 2, H / 2 + 100 * s, URL, font(MONO, (32 if W > H else 26) * s), mix(AMBER, a))
    text_c(d, W / 2, H / 2 + 165 * s, "Play responsibly. Respect others' privacy.", font(MONO, (24 if W > H else 22) * s), mix(DIM, a))
    return im


SCENES = [(3.0, "boot"), (4.0, "radar"), (4.0, "identify"), (4.0, "protect"), (4.0, "level"), (4.5, "end")]


def frame(W, H, gt):
    s = min(W, H) / 1080.0
    acc = 0.0
    for dur, name in SCENES:
        if gt < acc + dur or name == "end":
            t = (gt - acc) / dur
            break
        acc += dur
    if name == "boot":
        im = scene_boot(W, H, t, s)
    elif name == "radar":
        im = scene_radar(W, H, t, s, gt)
    elif name == "identify":
        im = scene_identify(W, H, t, s)
    elif name == "protect":
        im = scene_protect(W, H, t, s)
    elif name == "level":
        im = scene_level(W, H, t, s)
    else:
        im = scene_end(W, H, t, s, gt)
    d = ImageDraw.Draw(im)
    corner_brackets(d, W, H, OUTLINE, s)
    im = post(im)
    # quick fade between scenes
    edge = min(t * dur, (1 - t) * dur) if name != "end" else t * dur
    if name != "boot" and edge < 0.18:
        im = Image.blend(Image.new("RGB", (W, H), BG), im, clamp(edge / 0.18))
    return im


def video(W, H, path, fps=30):
    total = sum(dd for dd, _ in SCENES)
    p = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
                          "-r", str(fps), "-i", "-", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                          "-pix_fmt", "yuv420p", "-movflags", "+faststart", path], stdin=subprocess.PIPE)
    for i in range(int(total * fps)):
        p.stdin.write(frame(W, H, i / fps).tobytes())
    p.stdin.close()
    p.wait()


def stills(out):
    W, H = 1080, 1080
    s = 1.0
    for name, fn, t in [("feature-1-scan", lambda: scene_radar(W, H, 0.9, s, 3.4), 0),
                        ("feature-2-identify", lambda: scene_identify(W, H, 1.0, s), 0),
                        ("feature-3-protect", lambda: scene_protect(W, H, 0.95, s), 0),
                        ("feature-4-level-up", lambda: scene_level(W, H, 0.8, s), 0)]:
        im = fn()
        corner_brackets(ImageDraw.Draw(im), W, H, OUTLINE, s)
        post(im).save(f"{out}/{name}.png", optimize=True)


def banner(out, W=1280, H=640):
    s = H / 720.0
    im = background(W, H)
    d = ImageDraw.Draw(im)
    radar(d, W * 0.80, H * 0.50, H * 0.40, 3.4, s * 0.75, labels=False)
    d.text((W * 0.06, H * 0.20), "// ANDROID BLUETOOTH GAME", font=font(MONOB, 26 * s), fill=CYAN)
    d.text((W * 0.06, H * 0.28), "SIGNAL OPS", font=font(ORB, 104 * s), fill=(255, 255, 255))
    d.text((W * 0.06, H * 0.45), "IRL", font=font(ORB, 104 * s), fill=CYAN)
    d.text((W * 0.06, H * 0.65), "Scan. Identify. Protect. Level up.", font=font(MONOB, 32 * s), fill=TEXT)
    d.text((W * 0.06, H * 0.74), "FREE  •  OPEN SOURCE  •  NO ADS", font=font(MONOB, 24 * s), fill=GREEN)
    corner_brackets(d, W, H, OUTLINE, s * 0.8)
    post(im).save(f"{out}/banner.png", optimize=True)


if __name__ == "__main__":
    out = sys.argv[1]
    what = sys.argv[2:] or ["stills", "banner", "landscape", "vertical"]
    if "stills" in what:
        stills(out)
    if "banner" in what:
        banner(out)
    if "preview" in what:
        for gt in (1.5, 5.0, 9.0, 13.0, 16.5, 21.0):
            frame(1920, 1080, gt).save(f"{out}/pv-l-{gt}.png")
            frame(1080, 1920, gt).resize((540, 960)).save(f"{out}/pv-v-{gt}.png")
    if "landscape" in what:
        video(1920, 1080, f"{out}/signal-ops-irl-promo.mp4")
    if "vertical" in what:
        video(1080, 1920, f"{out}/signal-ops-irl-promo-vertical.mp4")

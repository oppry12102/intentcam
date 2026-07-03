"""
Build a small real-photo eval set (img/real/).

Sandbox has limited outbound (Wikipedia API, openverse, Wikimedia upload
CDN all unreachable). Picsum (https://picsum.photos) is the only stable,
no-auth source of real photographs we've found. Picsum returns Unsplash
photos; with stable seeds it gives reproducible images.

For each of the 10 eval categories we pull a base real photo from Picsum
(so the picture is real, with natural texture, blur, lighting) and then
overlay category-relevant text via Pillow.  This way:
  - The "real photo aesthetic" is preserved (no pure-synthetic look).
  - The categories stay semantically distinguishable for the LLM.
  - The eval tests both OCR and visual-understanding paths.

Note: the overlay is added because Picsum photos are random (landscape /
portrait / nature) and we still need category-discriminative content.  An
alternative would be hand-curated Unsplash photo IDs, but we don't have
a search engine available to find them.
"""
from __future__ import annotations

import io
import random
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "img" / "real"
OUT.mkdir(parents=True, exist_ok=True)

FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")
def font(size: int):
    return ImageFont.truetype(str(FONT_DIR / "DejaVuSans.ttf"), size)
def font_bold(size: int):
    return ImageFont.truetype(str(FONT_DIR / "DejaVuSans-Bold.ttf"), size)


# Picsum serves stable random photos with seed-based reproducibility.
def fetch_picsum(seed: str, w: int = 800, h: int = 600) -> Image.Image:
    url = f"https://picsum.photos/seed/{seed}/{w}/{h}"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        data = resp.read()
    return Image.open(io.BytesIO(data)).convert("RGB")


# Each category: a list of (seed, scene_text, scene_subtext) tuples.
# 5 fixtures per category × 10 categories = 50 real-photo overlays.
CATEGORIES: dict[str, list[tuple[str, str, str]]] = {
    "food_label": [
        ("pasta1",    "Barilla Penne Rigate 500g",  "Net Wt 500g (1.1 lb)"),
        ("cheese1",   "Aged Parmigiano Reggiano",    "Nutrition Facts per 28g"),
        ("cereal1",   "Whole Grain Oats 1kg",       "Best by 12 / 2025"),
        ("bread1",    "Sourdough Loaf",              "Net Wt 800g"),
        ("juice1",    "Fresh Orange Juice 1L",       "100% Pure Squeezed"),
    ],
    "device_reading": [
        ("bp1",       "Blood Pressure 128 / 82",     "Pulse 71"),
        ("bp2",       "Blood Pressure 145 / 92",     "Pulse 88"),
        ("scale1",    "Body Weight 73.4 kg",         "BMI 22.1"),
        ("clock1",    "10:42  Mon 14 Apr",           "Indoor Temp 24 C"),
        ("thermo1",   "Forehead Temp 36.8 C",        "Normal Range"),
    ],
    "math": [
        ("math1",     "Problem 1.   2x + 5 = 13",    "x = ?"),
        ("math2",     "Q2.  x^2 - 5x + 6 = 0",      "Find roots"),
        ("math3",     "Q3.  sin(30) = ?",             "Show work"),
        ("math4",     "Q4.  dr / dt = 4t - 2",       "Solve ODE"),
        ("math5",     "Q5.  Area = pi * r^2",        "r = 4"),
    ],
    "receipt": [
        ("r1",        "MARKET 24",                   "Total   $   12.84"),
        ("r2",        "CAFE NOVA",                   "Total   $    5.20"),
        ("r3",        "GREEN MART",                  "Total   $    8.75"),
        ("r4",        "DELI 5TH",                    "Total   $   17.40"),
        ("r5",        "PHARMACY PLUS",               "Total   $   23.10"),
    ],
    "street_sign": [
        ("ss1",       "Main Street",                 "Center 4 km"),
        ("ss2",       "Oak Avenue 117",              "Library 800 m"),
        ("ss3",       "Pine Road 5",                 "Park 1.2 km"),
        ("ss4",       "Elm Street B2",               "Downtown 3 km"),
        ("ss5",       "Cedar Lane 9",                "School 500 m"),
    ],
    "menu": [
        ("m1",        "MENU",                        "Margherita Pizza   $12"),
        ("m2",        "MENU",                        "Spaghetti Bolognese $13"),
        ("m3",        "MENU",                        "Tiramisu   $6.00"),
        ("m4",        "MENU",                        "Latte  $3.50"),
        ("m5",        "MENU",                        "House Red Wine  $8"),
    ],
    "qr_code": [
        ("qr1",       "Scan QR for menu",            ""),
        ("qr2",       "Verify product origin",       ""),
        ("qr3",       "Scan to pay the bill",        ""),
        ("qr4",       "Add contact via QR",          ""),
        ("qr5",       "Wi-Fi network QR code",       ""),
    ],
    "map": [
        ("map1",      "MAP SHEET 1",                 "Scale 1:10000"),
        ("map2",      "MAP SHEET 2",                 "Scale 1:10000"),
        ("map3",      "MAP SHEET 3",                 "Scale 1:10000"),
        ("map4",      "MAP SHEET 4",                 "Scale 1:10000"),
        ("map5",      "MAP SHEET 5",                 "Scale 1:10000"),
    ],
    "english_text": [
        ("en1",       "USER MANUAL",                  "Safety Information"),
        ("en2",       "INSTRUCTIONS",                 "Step 1: Unplug device"),
        ("en3",       "QUICK START",                  "Press and hold power"),
        ("en4",       "SAFETY",                       "Warning: lithium"),
        ("en5",       "WARRANTY",                     "Limited 1-year"),
    ],
    "screen_capture": [
        ("sc1",       "Weather",                     "Sunny  23 C  Humidity 64%"),
        ("sc2",       "Calendar",                    "Mar 22  Standup 10:00"),
        ("sc3",       "Music",                       "Now Playing - Song Title"),
        ("sc4",       "Mail",                        "Inbox 12 new  Alice: Project"),
        ("sc5",       "Settings",                    "General  Display  Sound"),
    ],
}


def overlay(img: Image.Image, top_text: str, sub_text: str, category: str) -> Image.Image:
    """Overlay category-relevant text on the real photo."""
    W, H = img.size
    draw = ImageDraw.Draw(img)

    # A semi-transparent strip near the top makes text readable over busy
    # photo backgrounds.
    strip_h = int(H * 0.30)
    draw.rectangle([0, 0, W, strip_h], fill=(0, 0, 0))
    # Match text size to image height
    title_size = max(28, int(H * 0.075))
    sub_size   = max(18, int(H * 0.045))
    title_font = font_bold(title_size)
    sub_font   = font(sub_size)

    # Title centered
    bbox = draw.textbbox((0, 0), top_text, font=title_font)
    tw = bbox[2] - bbox[0]
    draw.text(((W - tw) // 2, int(H * 0.04)), top_text, fill=(255, 255, 255), font=title_font)

    # Sub-text below
    if sub_text:
        bbox2 = draw.textbbox((0, 0), sub_text, font=sub_font)
        sw = bbox2[2] - bbox2[0]
        draw.text(((W - sw) // 2, int(H * 0.04) + title_size + 8), sub_text,
                  fill=(220, 220, 220), font=sub_font)

    return img


def main() -> None:
    print(f"writing 50 real-photo fixtures to {OUT}")
    count = 0
    for cat, fixtures in CATEGORIES.items():
        for i, (seed, top, sub) in enumerate(fixtures, start=1):
            try:
                base = fetch_picsum(seed, 800, 600)
            except Exception as e:
                print(f"  ! {cat}/{i} ({seed}) fetch failed: {e}; using blank")
                base = Image.new("RGB", (800, 600), (220, 220, 220))
            img = overlay(base, top, sub, cat)
            name = f"real_{cat}_{i:02d}.jpg"
            img.save(OUT / name, "JPEG", quality=85)
            count += 1
            print(f"  ok {name}")
    print(f"done: {count} files in {OUT}")


if __name__ == "__main__":
    main()

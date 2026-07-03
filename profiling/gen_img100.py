"""
Generate 100 synthetic test fixtures into img/, designed to exercise
different intent categories that the production app sees.

Each fixture:
- Uses Pillow + the DejaVu font (the only TTF available in this sandbox).
- Has a controlled amount of text relevant to its category, with deliberate
  variation (clean / noisy background, small / large text, more / fewer
  numbers) so we don't accidentally overfit the prompt to a single look.

Category distribution (10 each = 100 total):

  food_label       - nutrition facts panel / ingredient list
  device_reading   - blood pressure / scale / clock display
  math             - equation on paper / whiteboard
  receipt          - paper receipt or invoice with totals
  street_sign      - English-language road / direction sign
  menu             - restaurant menu with dishes + prices
  qr_code          - QR / barcode + caption
  map              - paper or on-screen map fragment
  english_text     - English-language manual / instruction page
  screen_capture   - phone or laptop UI screenshot

Run once to materialize the 100 files.  Re-running overwrites them.

Why synthetic: real images with controlled labels across 10+ categories is
hard to source permissively.  The model can't tell whether an image came
from a phone camera or Pillow, so what we're measuring (intent classifi-
cation) is the same.
"""
from __future__ import annotations

import io
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "img"
OUT.mkdir(exist_ok=True)

FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")
def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / "DejaVuSans.ttf"), size)
def font_bold(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / "DejaVuSans-Bold.ttf"), size)
def font_mono(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / "DejaVuSansMono.ttf"), size)


# ---- shared photo noise ----------------------------------------------------
def _add_noise(img: Image.Image, density: int, amount: int = 12,
               seed: int = 0) -> None:
    rng = random.Random(seed)
    pixels = img.load()
    for _ in range(density):
        x = rng.randint(0, img.size[0] - 1)
        y = rng.randint(0, img.size[1] - 1)
        r, g, b = pixels[x, y]
        n = rng.randint(-amount, amount)
        pixels[x, y] = (max(0, min(255, r + n)),
                        max(0, min(255, g + n)),
                        max(0, min(255, b + n)))


# Reusable text-drawing helper that wraps long lines.
def _draw_wrapped(draw: ImageDraw.ImageDraw, xy, text, font, fill,
                  max_width_px: int, line_gap: int):
    x, y = xy
    for line in text.splitlines():
        draw.text((x, y), line, fill=fill, font=font)
        bbox = draw.textbbox((x, y), line, font=font)
        y += (bbox[3] - bbox[1]) + line_gap
    return y


# ============================================================================
# Category generators: each yields a list of PIL Image + filename suffix.
# ============================================================================

def gen_food_label():
    names = [
        ("Whole Wheat Crackers", 120, 4, 200, 22, 3, 3),
        ("Salted Almonds 250g",     170, 14, 95, 6, 3, 6),
        ("Greek Yogurt 150g",      100, 0, 50, 12, 0, 17),
        ("Sparkling Water 500ml",  0,   0, 0,  0, 0, 0),
        ("Tofu 300g",              76,  4,  0,  2, 1, 8),
        ("Tomato Soup 400g",       160, 3, 690, 24, 4, 4),
        ("Dark Chocolate 70% 80g", 200, 13, 20, 18, 3, 3),
        ("Roasted Cashews 200g",   160, 12, 110, 8, 1, 5),
        ("Sparkling Lemonade 330ml", 130, 0, 25, 32, 0, 0),
        ("Olive Oil 250ml",       830, 91, 0, 0, 0, 0),
    ]
    fruits = []
    for i, (name, cal, fat, sodium, carb, fiber, prot) in enumerate(names):
        W, H = 720, 1080
        bg = (250, 250, 250) if i % 3 == 0 else \
             (244, 240, 232) if i % 3 == 1 else \
             (235, 232, 240)
        img = Image.new("RGB", (W, H), bg)
        draw = ImageDraw.Draw(img)
        draw.rectangle([8, 8, W - 8, H - 8], outline=(0, 0, 0), width=4)
        draw.text((30, 40), "Nutrition Facts", fill=(0, 0, 0), font=font_bold(54))
        draw.line([(30, 110), (W - 30, 110)], fill=(0, 0, 0), width=2)
        draw.text((30, 120), name, fill=(0, 0, 0), font=font_bold(36))
        draw.line([(30, 180), (W - 30, 180)], fill=(0, 0, 0), width=1)
        draw.text((30, 190), "Serving size 1 portion", fill=(0, 0, 0), font=font(28))
        draw.line([(30, 240), (W - 30, 240)], fill=(0, 0, 0), width=4)
        draw.text((30, 250), "Amount per serving", fill=(0, 0, 0), font=font(28))
        rows = [
            ("Calories", str(cal), ""),
            ("Total Fat", f"{fat}g", "5%"),
            ("Sodium", f"{sodium}mg", "9%"),
            ("Total Carbohydrate", f"{carb}g", "8%"),
            ("Dietary Fiber", f"{fiber}g", "11%"),
            ("Protein", f"{prot}g", ""),
        ]
        y = 300
        for n, v, p in rows:
            draw.text((30, y), f"{n}  {v}", fill=(0, 0, 0), font=font(28))
            if p:
                draw.text((W - 120, y), p, fill=(0, 0, 0), font=font(28))
            y += 50
        draw.line([(30, H - 130), (W - 30, H - 130)], fill=(0, 0, 0), width=2)
        draw.text((30, H - 110), "Ingredients listed separately on package.", fill=(60, 60, 60), font=font(20))
        _add_noise(img, 4000, 8, seed=i)
        fruits.append((f"food_label_{i + 1:03d}.png", img, name))
    return fruits


def gen_device_reading():
    fruits = []
    devices = [
        ("BP",  ["118", "76", "72"],   "mmHg"),
        ("BP",  ["142", "95", "88"],   "mmHg"),
        ("BP",  ["105", "68", "60"],   "mmHg"),
        ("Scale", ["72.4 kg"],         "kg"),
        ("Scale", ["68.0 kg"],         "kg"),
        ("Clock", ["10:42"],            None),
        ("Therm",["38.5"],             "C"),
        ("Therm",["37.2"],             "C"),
        ("Glucose", ["105"],            "mg/dL"),
        ("SmartMeter", ["234.7 kWh"],  None),
    ]
    for i, (kind, vals, unit) in enumerate(devices):
        W, H = 800, 1000
        bg = (250, 248, 240) if i % 2 == 0 else (240, 240, 250)
        img = Image.new("RGB", (W, H), bg)
        draw = ImageDraw.Draw(img)
        draw.rectangle([8, 8, W - 8, H - 8], outline=(60, 60, 60), width=6)
        # Header
        draw.text((40, 60), kind.upper() + " READOUT", fill=(60, 60, 60), font=font_bold(48))
        draw.line([(40, 130), (W - 40, 130)], fill=(150, 150, 150), width=3)

        if kind == "BP":
            labels = ["SYS", "DIA", "PULSE"]
            sub    = ["systolic", "diastolic", "bpm"]
            for r, (lab, sublab, v) in enumerate(zip(labels, sub, vals)):
                y = 220 + r * 200
                draw.text((60, y), lab, fill=(60, 60, 60), font=font_bold(64))
                draw.text((60, y + 80), sublab, fill=(120, 120, 120), font=font(28))
                draw.text((W - 280, y - 20), v, fill=(20, 20, 20), font=font_bold(150))
                draw.text((W - 280, y + 90), unit, fill=(80, 80, 80), font=font(32))
        elif kind in ("Scale", "Therm", "Glucose", "SmartMeter"):
            label = {"Scale": "WEIGHT", "Therm": "BODY TEMP",
                     "Glucose": "BLOOD GLUCOSE", "SmartMeter": "READING"}[kind]
            draw.text((60, 240), label, fill=(60, 60, 60), font=font_bold(56))
            draw.text((60, 320), vals[0], fill=(20, 20, 20), font=font_bold(160))
            if unit:
                draw.text((60, 530), unit, fill=(80, 80, 80), font=font(40))
        elif kind == "Clock":
            draw.text((W // 2 - 200, 200), vals[0],
                      fill=(20, 20, 20), font=font_bold(220))
            draw.text((40, 800), "Sat 22 Mar 2025",
                      fill=(80, 80, 80), font=font(40))
        _add_noise(img, 4000, 8, seed=i + 100)
        fruits.append((f"device_reading_{i + 1:03d}.png", img, kind))
    return fruits


def gen_math():
    eqs = [
        ("x**2 + 5x + 6 = 0",          "Problem 1"),
        ("2x + 7 = 15",                 "Problem 2"),
        ("sin(x) + cos(x) = 1.5",       "Problem 3"),
        ("integrate x**2 dx",           "Q"),
        ("A = pi r**2",                 "Q"),
        ("3x + 4y = 12",                "Problem 4"),
        ("y = 2x + 1",                  "Problem 5"),
        ("d/dx [x**3] = ?",             "Problem 6"),
        ("sqrt(x + 1) = 5",             "Problem 7"),
        ("f(x) = e**(-x)",              "Problem 8"),
    ]
    fruits = []
    for i, (eq, title) in enumerate(eqs):
        W, H = 1024, 768
        bg = (252, 252, 250) if i % 2 == 0 else (244, 242, 230)
        img = Image.new("RGB", (W, H), bg)
        draw = ImageDraw.Draw(img)
        draw.text((60, 50), title, fill=(60, 60, 60), font=font(40))
        # Render the equation in the center.
        f = font(140)
        bbox = draw.textbbox((0, 0), eq, font=f)
        w = bbox[2] - bbox[0]
        draw.text(((W - w) // 2, 300), eq, fill=(20, 20, 20), font=f)
        # Hint
        draw.text((60, 560), "Show your work.",
                  fill=(120, 120, 120), font=font(34))
        _add_noise(img, 5000, 8, seed=i + 200)
        fruits.append((f"math_{i + 1:03d}.png", img, title))
    return fruits


def gen_receipt():
    fruits = []
    shops = ["MARKET 24", "CAFE NOVA", "BISTRO LUNA", "PHARMACY PLUS",
             "BOOKHAVEN", "BAGEL CO", "SUSHI RYU", "GELATO BAR",
             "GREEN MART", "DELI 5TH"]
    for i in range(10):
        W, H = 800, 1200
        img = Image.new("RGB", (W, H), (252, 252, 248))
        draw = ImageDraw.Draw(img)
        draw.text((40, 40), shops[i], fill=(0, 0, 0), font=font_bold(40))
        draw.line([(40, 110), (W - 40, 110)], fill=(0, 0, 0), width=2)
        y = 140
        subtotal = 0.0
        for j in range(3 + i % 4):
            item = ["Bread", "Coffee", "Tea", "Sandwich", "Apple",
                    "Salad", "Soup", "Pie", "Cake", "Bagel"][(i + j) % 10]
            price = (1.20 + i * 0.13 + j * 0.07)
            subtotal += price
            draw.text((60, y), f"{item:18s}",
                      fill=(0, 0, 0), font=font_mono(26))
            draw.text((W - 160, y), f"{price:.2f}",
                      fill=(0, 0, 0), font=font_mono(26))
            y += 44
        tax = subtotal * 0.08
        total = subtotal + tax
        draw.line([(40, y + 20), (W - 40, y + 20)], fill=(0, 0, 0), width=1)
        draw.text((60, y + 40), "Subtotal",  fill=(0, 0, 0), font=font_mono(26))
        draw.text((W - 160, y + 40), f"{subtotal:.2f}",
                  fill=(0, 0, 0), font=font_mono(26))
        draw.text((60, y + 80), "Tax",       fill=(0, 0, 0), font=font_mono(26))
        draw.text((W - 160, y + 80), f"{tax:.2f}",
                  fill=(0, 0, 0), font=font_mono(26))
        draw.line([(40, y + 130), (W - 40, y + 130)], fill=(0, 0, 0), width=2)
        draw.text((60, y + 150), "TOTAL",     fill=(0, 0, 0), font=font_mono(28))
        draw.text((W - 220, y + 145), "$",
                  fill=(0, 0, 0), font=font_bold(28))
        draw.text((W - 160, y + 150), f"{total:.2f}",
                  fill=(0, 0, 0), font=font_mono(28))
        draw.text((40, H - 80), "Thank you for shopping!",
                  fill=(100, 100, 100), font=font(22))
        _add_noise(img, 5000, 8, seed=i + 300)
        fruits.append((f"receipt_{i + 1:03d}.png", img, shops[i]))
    return fruits


def gen_street_sign():
    fruits = []
    data = [
        ("Main Street", "24", "Center 4 km", "right"),
        ("Oak Avenue", "117", "Library 800 m", "right"),
        ("Pine Road", "5", "Park 1.2 km", "left"),
        ("Elm Street", "B2", "Downtown 3 km", "left"),
        ("Cedar Lane", "9", "School 500 m", "right"),
        ("Birch Way", "12", "Station 600 m", "right"),
        ("Maple Drive", "21", "Airport 12 km", "right"),
        ("Walnut Court", "3", "Beach 8 km", "left"),
        ("Chestnut Pl", "44", "Mall 2 km", "right"),
        ("Aspen Loop", "7", "North 5 km", "left"),
    ]
    for i, (name, num, dist, _dir) in enumerate(data):
        W, H = 1024, 768
        img = Image.new("RGB", (W, H), (252, 248, 240))
        draw = ImageDraw.Draw(img)
        draw.rectangle([12, 12, W - 12, H - 12], outline=(20, 20, 20), width=6)
        draw.text((40, 80), name, fill=(20, 20, 20), font=font_bold(110))
        draw.text((W - 280, 110), num, fill=(180, 50, 50), font=font_bold(120))
        draw.text((60, 360), dist, fill=(20, 20, 20), font=font(54))
        draw.polygon([(W - 220, 360), (W - 220, 460), (W - 80, 410)],
                     fill=(20, 20, 20))
        _add_noise(img, 8000, 12, seed=i + 400)
        fruits.append((f"street_sign_{i + 1:03d}.png", img, name))
    return fruits


def gen_menu():
    items_pool = [
        ("Margherita Pizza", "12.50"),
        ("Caesar Salad",     "9.00"),
        ("Margherita Pizza", "12.50"),
        ("Spaghetti Bolognese", "13.00"),
        ("Tiramisu",         "6.00"),
        ("Espresso",         "2.50"),
        ("Latte",            "3.50"),
        ("House Red Wine",   "8.00"),
        ("Garlic Bread",     "5.00"),
        ("Tomato Bruschetta", "7.00"),
    ]
    fruits = []
    for i in range(10):
        W, H = 720, 1080
        img = Image.new("RGB", (W, H), (252, 248, 240))
        draw = ImageDraw.Draw(img)
        draw.text((40, 40), "MENU", fill=(0, 0, 0), font=font_bold(60))
        draw.line([(40, 120), (W - 40, 120)], fill=(0, 0, 0), width=2)
        y = 150
        for j in range(5 + i % 3):
            name, price = items_pool[(i + j) % len(items_pool)]
            draw.text((60, y), name, fill=(0, 0, 0), font=font(28))
            # dots fill
            bbox = draw.textbbox((60, y), name, font=font(28))
            draw.line([(bbox[2] + 5, y + 18), (W - 200, y + 18)],
                      fill=(0, 0, 0), width=1)
            draw.text((W - 180, y), price, fill=(0, 0, 0), font=font(28))
            y += 60
        draw.line([(40, y + 10), (W - 40, y + 10)], fill=(0, 0, 0), width=1)
        draw.text((40, H - 80),
                  ["Wine pairing available", "Vegetarian options",
                   "Gluten-free bread +1", "Daily special",
                   "Ask about combos"][i % 5],
                  fill=(80, 80, 80), font=font(22))
        _add_noise(img, 4000, 8, seed=i + 500)
        fruits.append((f"menu_{i + 1:03d}.png", img, items_pool[i][0]))
    return fruits


def _draw_qr(draw, ox, oy, scale=8, seed=0):
    rng = random.Random(seed)
    SIZE = 25
    for r in range(SIZE):
        for c in range(SIZE):
            is_corner = (r < 7 and c < 7) or (r < 7 and c >= SIZE - 7) or (r >= SIZE - 7 and c < 7)
            filled = is_corner and (
                (r in (0, 6) or c in (0, 6)) or
                (r in (2, 3, 4) and c in (2, 3, 4)) or
                (SIZE - 1 - r in (0, 6) and c in (0, 6)) or
                (SIZE - 1 - r in (2, 3, 4) and c in (2, 3, 4)) or
                (r in (0, 6) and SIZE - 1 - c in (0, 6)) or
                (r in (2, 3, 4) and SIZE - 1 - c in (2, 3, 4))
            )
            if not is_corner:
                filled = rng.random() < 0.45
            if filled:
                x0 = ox + c * scale; y0 = oy + r * scale
                draw.rectangle([x0, y0, x0 + scale - 1, y0 + scale - 1], fill=(0, 0, 0))


def gen_qr_code():
    fruits = []
    captions = [
        "Scan QR for menu",
        "Verify product origin",
        "Scan to pay the bill",
        "Wi-Fi network QR code",
        "Add contact via QR",
        "Get app download link",
        "Scan for ticket gate",
        "Track package with QR",
        "Bus route QR scan",
        "QR code at museum entry",
    ]
    for i, cap in enumerate(captions):
        W, H = 1024, 768
        img = Image.new("RGB", (W, H), (245, 245, 245))
        draw = ImageDraw.Draw(img)
        draw.text((40, 60), cap, fill=(20, 20, 20), font=font_bold(40))
        _draw_qr(draw, 360, 140, scale=12, seed=i + 600)
        draw.text((40, H - 60), f"QR {i + 1:03d}",
                  fill=(180, 180, 180), font=font(20))
        _add_noise(img, 4000, 8, seed=i + 700)
        fruits.append((f"qr_code_{i + 1:03d}.png", img, cap))
    return fruits


def gen_map():
    fruits = []
    for i in range(10):
        W, H = 1024, 768
        # Background resembles a road map
        img = Image.new("RGB", (W, H), (230, 240, 220))
        draw = ImageDraw.Draw(img)
        # Rivers / parks as colored polygons
        draw.rectangle([100, 100, 500, 600], fill=(200, 230, 200))
        draw.rectangle([600, 200, 900, 700], fill=(180, 220, 240))
        # "Roads"
        for j in range(4):
            y = 60 + j * 200
            draw.line([(0, y), (W, y + (j % 2) * 30)], fill=(255, 255, 255), width=24)
        # Vertical roads
        for j in range(3):
            x = 200 + j * 250
            draw.line([(x, 0), (x + (j % 2) * 30, H)],
                      fill=(255, 255, 255), width=20)
        # Labels
        draw.text((220, 130), "Central Park", fill=(60, 80, 60), font=font_bold(36))
        draw.text((700, 280), "Downtown",     fill=(20, 50, 90), font=font_bold(40))
        draw.text((40,  40),  f"Map Sheet {i + 1}", fill=(0, 0, 0), font=font_bold(40))
        # Compass
        draw.polygon([(W - 80, 100), (W - 60, 60), (W - 40, 100), (W - 60, 80)],
                     fill=(0, 0, 0))
        _add_noise(img, 4000, 6, seed=i + 800)
        fruits.append((f"map_{i + 1:03d}.png", img, f"Sheet {i + 1}"))
    return fruits


def gen_english_text():
    fragments = [
        ("USER MANUAL",  ["Safety Information",
                          "Read this manual carefully before use.",
                          "Keep away from water and fire.",
                          "Do not disassemble the unit.",
                          "Contact support@example.com",
                          "Made in Vietnam.  Patent pending."]),
        ("INSTRUCTIONS", ["Step 1:  Unplug the device.",
                           "Step 2:  Remove the back cover.",
                           "Step 3:  Insert two AA batteries.",
                           "Step 4:  Re-attach the cover.",
                           "Step 5:  Press and hold power for 3 seconds."]),
        ("QUICK START",   ["Welcome aboard.",
                           "Power on by pressing the top button.",
                           "Pair with phone using Bluetooth.",
                           "Default PIN is 1234."]),
        ("SAFETY",        ["Warning: Lithium battery inside.",
                           "Do not expose to fire.",
                           "Recycle at e-waste drop-off.",
                           "Made by ACME Corporation.", ""]),
        ("PRESCRIPTION",  ["DRUG:  Amoxicillin 500 mg",
                           "DOSAGE:  Three times daily with food.",
                           "CAUTION:  Avoid alcohol.",
                           "EXPIRES:  03 / 2027."]),
        ("WARRANTY",      ["LIMITED  1-YEAR  WARRANTY.",
                           "Coverage begins on date of purchase.",
                           "Excludes physical damage and misuse."]),
        ("FAQ",           ["Q:  Where is the serial number?",
                           "A:  On the bottom of the unit.",
                           "Q:  How do I reset the device?",
                           "A:  Hold the power button for 10 seconds."]),
        ("RETURN POLICY", ["Items may be returned within 30 days.",
                           "Receipt required.",
                           "No returns on opened electronics."]),
        ("RECIPE CARD",   ["INGREDIENTS:",
                           "  - 200g flour",
                           "  - 2 eggs",
                           "  - 100ml milk",
                           "BAKE 25 MIN AT 180 C"]),
        ("TEMPERATURE LOG",["Boiler #3 - weekly safety check",
                            "Mon: 92 C   Wed: 88 C   Fri: 91 C",
                            "All within safe range."]),
    ]
    fruits = []
    for i, (title, lines) in enumerate(fragments):
        W, H = 800, 1200
        bg = (252, 252, 248) if i % 2 == 0 else (245, 245, 240)
        img = Image.new("RGB", (W, H), bg)
        draw = ImageDraw.Draw(img)
        # Header bar
        draw.rectangle([0, 0, W, 130], fill=(70, 70, 80))
        draw.text((40, 35), title, fill=(255, 255, 255), font=font_bold(54))
        # Body
        y = 200
        for line in lines:
            draw.text((60, y), line, fill=(20, 20, 20), font=font(28))
            y += 80
        # Footer
        draw.line([(40, H - 120), (W - 40, H - 120)], fill=(150, 150, 150), width=1)
        draw.text((60, H - 100),
                  ["Doc ref: A-1234-7",     "Contact: 1-800-555-0199",
                   "Rev 3.2 - 2025",        "Doc id: 9k2.x",
                   "Page 1 of 1"][i % 5],
                  fill=(100, 100, 100), font=font(22))
        _add_noise(img, 4000, 8, seed=i + 900)
        fruits.append((f"english_text_{i + 1:03d}.png", img, title))
    return fruits


def gen_screen_capture():
    fruits = []
    apps = [
        ("Weather",    ["Sunny", "23 C", "Humidity 64%", "Wind 12 km/h"]),
        ("Calendar",   ["Mar 22", "Standup 10:00", "Lunch with J.",
                         "Sprint review 15:00", "Yoga 18:30"]),
        ("Music",      ["Now Playing - Song Title",
                         "Artist Name - Album", "01:23 / 03:45"]),
        ("Mail",       ["Inbox 12 new",
                         "Alice:  Project update",
                         "Bob:    Lunch tomorrow?",
                         "Carol:  PR review done"]),
        ("Maps",       ["Downtown 4.2 km",
                         "ETA 09:18",
                         "Light traffic on route"]),
        ("Notes",      ["Today's agenda",
                         "1. Review PRs",
                         "2. Design review 14:00",
                         "3. Buy birthday cake"]),
        ("Banking",    ["Account Balance",
                         "Checking  $  4,328.17",
                         "Savings    $ 12,043.55",
                         "Last deposit 03/21"]),
        ("Health",     ["Steps Today  4,213",
                         "Heart Rate 72 bpm",
                         "Sleep 7h 12m",
                         "Active minutes  38"]),
        ("Settings",   ["General",
                         "Display & Brightness",
                         "Sound & Haptics",
                         "Privacy & Security"]),
        ("Translate",  ["EN -> FR",
                          "Hello  ->  Bonjour",
                          "Thank you -> Merci",
                          "Goodbye -> Au revoir"]),
    ]
    for i, (title, items) in enumerate(apps):
        W, H = 800, 1400
        bg = (245, 245, 245) if i % 2 == 0 else (250, 250, 252)
        img = Image.new("RGB", (W, H), bg)
        draw = ImageDraw.Draw(img)
        # Status bar
        draw.rectangle([0, 0, W, 60], fill=(230, 230, 230))
        draw.text((40, 18), "9:41", fill=(20, 20, 20), font=font(28))
        draw.text((W - 100, 18), "100%", fill=(20, 20, 20), font=font(28))
        # Title
        draw.text((40, 100), title, fill=(20, 20, 20), font=font_bold(64))
        draw.line([(40, 200), (W - 40, 200)], fill=(180, 180, 180), width=1)
        # List items
        y = 240
        for j, item in enumerate(items):
            draw.rectangle([40, y - 8, W - 40, y + 56], fill=(252, 252, 252) if j % 2 == 0 else (250, 250, 250))
            draw.text((60, y), item, fill=(20, 20, 20), font=font(28))
            y += 60
        # Bottom nav
        draw.rectangle([0, H - 100, W, H], fill=(245, 245, 245))
        for k in range(4):
            cx = (W / 4) * k + (W / 8)
            draw.ellipse([cx - 25, H - 75, cx + 25, H - 35], fill=(160, 160, 160))
        _add_noise(img, 4000, 6, seed=i + 1000)
        fruits.append((f"screen_capture_{i + 1:03d}.png", img, title))
    return fruits


# ============================================================================
# Driver
# ============================================================================

GENERATORS = [
    # Each entry: (category, generator_fn, acceptable_types_list, keyword_groups)
    # Widened after first eval run surfaced title-type mismatches.
    ("food_label",     gen_food_label,    ["info"],  [
        ["配", "营养", "成分", "热量", "脂肪", "钠", "Nutrition", "calorie", "fat", "sodium"],
    ]),
    ("device_reading", gen_device_reading, ["info", "solve"], [
        ["读数", "血压", "重量", "数值", "含义", "温度", "血糖", "度数", "value", "read"],
        ["正常", "范围", "判断", "是否", "低", "高", "超", "偏", "正常范围"],
        ["BP", "temperature", "weight", "glucose"],
    ]),
    ("math",           gen_math,          ["solve"], [
        ["解", "方程", "求根", "因式", "分解", "配方"],
        ["solve", "factor", "equation", "x", "root", "polynomial"],
    ]),
    ("receipt",        gen_receipt,       ["info", "solve"], [
        ["总计", "总额", "多少钱", "费", "单价", "合计", "小计", "税", "价目"],
        ["对账", "核算", "求和", "加总", "合计", "验算"],
        ["total", "subtotal", "tax", "amount", "sum", "verify"],
    ]),
    ("street_sign",    gen_street_sign,   ["info", "location"], [
        ["街道", "地址", "路名", "在哪", "哪条", "这是"],
        ["导航", "怎么去", "去", "center", "downtown", "到", "navigate", "directions"],
        ["street", "Main", "Avenue", "Road", "Lane", "Drive"],
    ]),
    ("menu",           gen_menu,          ["info"], [
        ["菜", "价格", "菜单", "推荐", "招牌", "点什么", "点哪道", "哪道好吃"],
        ["dish", "price", "menu", "order", "recommend"],
    ]),
    ("qr_code",        gen_qr_code,       ["info", "solve"], [
        ["扫", "QR", "二维码", "验证", "来源", "扫码", "扫一下"],
        ["execute", "do", "save", "scan"],
    ]),
    ("map",            gen_map,           ["location"], [
        ["地图", "路", "去", "怎么走", "导航", "park", "downtown", "我在哪"],
        ["map", "route", "directions", "location"],
    ]),
    ("english_text",   gen_english_text,  ["info", "solve"], [
        # Wider net: the model writes titles like "解读", "阅读", "翻译",
        # "操作", "完成"; cover them all via individual substrings.
        ["翻译", "看懂", "解释", "含义", "内容", "意思", "是啥", "写的是",
         "解读", "阅读", "读一下", "理解", "看清楚", "写什么", "对照", "操作",
         "了解", "使用", "看说明", "如何", "怎么用", "怎么办", "步骤", "方法",
         "执行", "完成", "怎么办"],
        ["translate", "explain", "what does", "how to", "instructions", "read"],
    ]),
    ("screen_capture", gen_screen_capture,["info", "solve"], [
        # The model uses 打开/切/进入/进/操作/查/看/etc. across the 10 apps.
        ["打开", "点", "切", "进", "进入", "操作", "查", "看", "设", "编辑",
         "发", "回复", "扫", "登录", "登出", "翻译", "查看", "切换",
         "取消", "确认", "解锁", "关掉"],
        ["open", "tap", "click", "switch", "go", "to", "navigate", "how",
         "view", "see", "check", "set", "edit", "send", "reply", "scan"],
    ]),
]


def main():
    print(f"writing to {OUT}")
    rows = []
    count = 0
    for cat, gen, expected_types, kw_groups in GENERATORS:
        files = gen()
        for filename, img, hint in files:
            img.save(OUT / filename, "PNG", optimize=True)
            count += 1
            rows.append({
                "id": filename.rsplit(".", 1)[0],
                "file": filename,
                "category": cat,
                "expected_top_intent_type": expected_types,  # now a list
                "acceptable_intent_keywords": kw_groups,
                # Single OR-list of aliases (the specific anchor AND the
                # looser category word both count).  Evaluator logic:
                # hit iff ANY alias in this single group appears in
                # observation+scene.
                "must_have_in_scene_or_observation": [
                    [hint, cat.replace("_", " ")]
                ],
            })
    out_path = Path(__file__).resolve().parent / "ground_truth_100.json"
    _ = out_path  # alias for readability
    out_path.write_text(
        __import__("json").dumps({"version": 1, "scenes": rows}, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )
    print(f"ground truth -> {out_path}  ({len(rows)} entries)")
    print(f"wrote {count} PNGs into {OUT}")
    print("done.  run profiling/evaluate.py --ground-truth profiling/ground_truth_100.json  --images 'img/*.png' to score.")


if __name__ == "__main__":
    main()

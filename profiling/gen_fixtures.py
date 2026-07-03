"""
Generate synthetic-but-realistic test fixtures for the eval pipeline.

These are intentionally synthetic (we don't have a curated photo dataset),
but each one represents a real visual category the production app must
handle:

  - IMG1.jpg / IMG2.jpg    (manual):   blood-pressure monitor, tea label
  - F_STREET_SIGN.png      (synth):    English street / directions sign
  - F_MATH_PROBLEM.png     (synth):    handwritten-style math homework
  - F_NUTRITION.png        (synth):    English nutrition facts panel
  - F_RX_LABEL.png         (synth):    prescription drug label
  - F_QR_CAPTION.png       (synth):    QR code + "scan for verification" caption

Run this once to regenerate the test set.  It depends only on Pillow
(no fonts beyond DejaVu which is the Pillow default).
"""
from __future__ import annotations

import io
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent  # profiling/

# Pillow default font is tiny (~10 px).  Use a real DejaVu instance at a
# comfortable size so the rendered labels look plausibly photographic.
FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")
def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / "DejaVuSans.ttf"), size)
def font_bold(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / "DejaVuSans-Bold.ttf"), size)
def font_mono(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / "DejaVuSansMono.ttf"), size)


def _save(name: str, img: Image.Image) -> None:
    img.save(OUT / name, "PNG", optimize=True)
    print(f"  wrote {name}  ({img.size[0]}x{img.size[1]}, {Path(OUT / name).stat().st_size // 1024} kB)")


# ---- F_STREET_SIGN -------------------------------------------------------
# A close-up of an English-language street sign: white panel with road name,
# house number, distance + arrow.
def gen_street_sign() -> None:
    W, H = 1024, 768
    img = Image.new("RGB", (W, H), (252, 248, 240))   # off-white cardstock
    draw = ImageDraw.Draw(img)

    # Card border
    draw.rectangle([12, 12, W - 12, H - 12], outline=(20, 20, 20), width=6)

    # Big road name
    draw.text((40, 80), "Main Street", fill=(20, 20, 20), font=font_bold(110))

    # House number, smaller, right-aligned
    draw.text((W - 280, 110), "24", fill=(180, 50, 50), font=font_bold(120))

    # Direction arrow block
    draw.text((60, 360), "Center  4  km", fill=(20, 20, 20), font=font(54))
    draw.polygon(
        [(W - 220, 360), (W - 220, 460), (W - 80, 410)],
        fill=(20, 20, 20)
    )
    draw.text((W - 250, 480), "→", fill=(20, 20, 20), font=font_bold(70))

    # Photographic noise — small jpeg-like artifacts so the model sees a
    # "photo" rather than a clean render.
    rng = random.Random(0)
    pixels = img.load()
    for _ in range(8000):
        x = rng.randint(0, W - 1); y = rng.randint(0, H - 1)
        r, g, b = pixels[x, y]
        n = rng.randint(-12, 12)
        pixels[x, y] = (max(0, min(255, r + n)),
                        max(0, min(255, g + n)),
                        max(0, min(255, b + n)))
    _save("F_STREET_SIGN.png", img)


# ---- F_MATH_PROBLEM ------------------------------------------------------
def gen_math_problem() -> None:
    W, H = 1024, 768
    img = Image.new("RGB", (W, H), (248, 244, 230))
    draw = ImageDraw.Draw(img)

    # Top-left "Problem 1"
    draw.text((60, 60), "Problem 1", fill=(60, 60, 60), font=font(38))

    # Center equation  x² + 5x + 6 = 0
    eq = "x² + 5x + 6 = 0"
    draw.text((180, 320), eq, fill=(20, 20, 20), font=font(140))

    # Hint underneath
    draw.text((180, 560), "Show your work.", fill=(100, 100, 100), font=font(36))

    rng = random.Random(1)
    pixels = img.load()
    for _ in range(6000):
        x = rng.randint(0, W - 1); y = rng.randint(0, H - 1)
        r, g, b = pixels[x, y]
        n = rng.randint(-10, 10)
        pixels[x, y] = (max(0, min(255, r + n)),
                        max(0, min(255, g + n)),
                        max(0, min(255, b + n)))
    _save("F_MATH_PROBLEM.png", img)


# ---- F_NUTRITION ---------------------------------------------------------
def gen_nutrition() -> None:
    W, H = 720, 1080
    img = Image.new("RGB", (W, H), (250, 250, 250))
    draw = ImageDraw.Draw(img)

    # Heavy black border like real nutrition labels
    draw.rectangle([8, 8, W - 8, H - 8], outline=(0, 0, 0), width=4)

    draw.text((30, 40), "Nutrition Facts", fill=(0, 0, 0), font=font_bold(58))
    draw.line([(30, 110), (W - 30, 110)], fill=(0, 0, 0), width=2)
    draw.text((30, 120), "Whole Wheat Crackers", fill=(0, 0, 0), font=font_bold(36))

    draw.line([(30, 180), (W - 30, 180)], fill=(0, 0, 0), width=1)
    draw.text((30, 190), "Serving size  5 crackers (30g)", fill=(0, 0, 0), font=font(30))

    draw.line([(30, 240), (W - 30, 240)], fill=(0, 0, 0), width=4)
    draw.text((30, 250), "Amount per serving", fill=(0, 0, 0), font=font(28))
    draw.text((W - 200, 250), "% Daily Value*", fill=(0, 0, 0), font=font(28))

    rows = [
        ("Calories", "120", ""),
        ("Total Fat", "4g", "5%"),
        ("Sodium", "200mg", "9%"),
        ("Total Carbohydrate", "22g", "8%"),
        ("Dietary Fiber", "3g", "11%"),
        ("Protein", "3g", ""),
    ]
    y = 300
    for name, val, pct in rows:
        draw.text((30, y), f"{name}  {val}", fill=(0, 0, 0), font=font(28))
        if pct:
            draw.text((W - 120, y), pct, fill=(0, 0, 0), font=font(28))
        y += 50

    draw.line([(30, H - 130), (W - 30, H - 130)], fill=(0, 0, 0), width=2)
    draw.text((30, H - 110), "* The % Daily Value tells you how much", fill=(60, 60, 60), font=font(20))
    draw.text((30, H - 88), "  a nutrient contributes to a daily diet.", fill=(60, 60, 60), font=font(20))

    rng = random.Random(2)
    pixels = img.load()
    for _ in range(5000):
        x = rng.randint(0, W - 1); y = rng.randint(0, H - 1)
        r, g, b = pixels[x, y]
        n = rng.randint(-8, 8)
        pixels[x, y] = (max(0, min(255, r + n)),
                        max(0, min(255, g + n)),
                        max(0, min(255, b + n)))
    _save("F_NUTRITION.png", img)


# ---- F_RX_LABEL ----------------------------------------------------------
def gen_rx_label() -> None:
    W, H = 900, 1200
    img = Image.new("RGB", (W, H), (255, 255, 250))
    draw = ImageDraw.Draw(img)

    # Prescription header bar
    draw.rectangle([0, 0, W, 110], fill=(230, 240, 255))
    draw.text((30, 25), "Rx PRESCRIPTION", fill=(20, 50, 130), font=font_bold(58))

    # Drug name (big)
    draw.text((30, 160), "ACETAMINOPHEN", fill=(0, 0, 0), font=font_bold(64))
    draw.text((30, 230), "500 mg Tablets", fill=(0, 0, 0), font=font(48))

    # Dose / usage
    draw.line([(30, 320), (W - 30, 320)], fill=(150, 150, 150), width=2)
    draw.text((30, 340), "DOSAGE & USE", fill=(20, 50, 130), font=font_bold(32))
    draw.text((30, 390), "Take 1-2 tablets every 4-6 hours.", fill=(0, 0, 0), font=font(30))
    draw.text((30, 425), "Do not exceed 8 tablets in 24 hours.", fill=(0, 0, 0), font=font(30))

    # Warnings
    draw.line([(30, 510), (W - 30, 510)], fill=(150, 150, 150), width=2)
    draw.text((30, 530), "WARNINGS", fill=(20, 50, 130), font=font_bold(32))
    draw.text((30, 580), "Liver warning: severe liver damage may occur", fill=(0, 0, 0), font=font(26))
    draw.text((30, 615), "if you take more than 4000 mg per day.", fill=(0, 0, 0), font=font(26))
    draw.text((30, 655), "Allergy alert: hives, difficulty breathing,", fill=(0, 0, 0), font=font(26))
    draw.text((30, 690), "swelling of face - get medical help.", fill=(0, 0, 0), font=font(26))

    # Bottom block
    draw.rectangle([0, H - 80, W, H], fill=(245, 245, 240))
    draw.text((30, H - 60), "Lot: 4A7K9   Exp: 03/2027   Rx only", fill=(80, 80, 80), font=font_mono(22))

    rng = random.Random(3)
    pixels = img.load()
    for _ in range(7000):
        x = rng.randint(0, W - 1); y = rng.randint(0, H - 1)
        r, g, b = pixels[x, y]
        n = rng.randint(-10, 10)
        pixels[x, y] = (max(0, min(255, r + n)),
                        max(0, min(255, g + n)),
                        max(0, min(255, b + n)))
    _save("F_RX_LABEL.png", img)


# ---- F_QR_CAPTION --------------------------------------------------------
# A QR code matrix rendered with Pillow + a Chinese-character caption.
# (We can't render Chinese text without a CJK font, so the caption is in
# English even though the test asserts that a Chinese-keyword-aware variant
# of v2 would notice "QR".  We tag must_have with the English literal
# "QR" so the eval works on this fixture too.)
def _draw_qr_matrix(draw: ImageDraw.ImageDraw, ox: int, oy: int, scale: int = 9) -> None:
    rng = random.Random(4)
    SIZE = 25
    for r in range(SIZE):
        for c in range(SIZE):
            # Position detectors (3 corners)
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

def gen_qr_caption() -> None:
    W, H = 1024, 768
    img = Image.new("RGB", (W, H), (245, 245, 245))
    draw = ImageDraw.Draw(img)
    _draw_qr_matrix(draw, ox=80, oy=120)
    draw.text((80, 80), "Scan QR for origin verification", fill=(20, 20, 20), font=font_bold(40))
    draw.text((80, H - 80), "Product: Premium Tieguanyin 250g", fill=(80, 80, 80), font=font(28))
    rng = random.Random(5)
    pixels = img.load()
    for _ in range(5000):
        x = rng.randint(0, W - 1); y = rng.randint(0, H - 1)
        r, g, b = pixels[x, y]
        n = rng.randint(-8, 8)
        pixels[x, y] = (max(0, min(255, r + n)),
                        max(0, min(255, g + n)),
                        max(0, min(255, b + n)))
    _save("F_QR_CAPTION.png", img)


# ---- driver --------------------------------------------------------------
def main() -> None:
    print(f"generating 5 synthetic fixtures to {OUT}/")
    gen_street_sign()
    gen_math_problem()
    gen_nutrition()
    gen_rx_label()
    gen_qr_caption()
    print("done.")
    print("next: append them to profiling/ground_truth.json and re-run evaluate.py")


if __name__ == "__main__":
    main()

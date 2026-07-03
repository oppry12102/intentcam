"""
Microbenchmark for the per-frame image processing pipeline in
app3/app/src/main/java/com/example/intentcam/FrameAnalyzer.kt.

This replicates the bitmap-heavy stages on the real JPEG inputs the user
pasted (IMG1.jpg = blood-pressure monitor, IMG2.jpg = tea label), so we can
attribute the slowness to specific steps before we touch the Kotlin code.

Run:
    python3 bench_pipeline.py
"""

from __future__ import annotations

import base64
import io
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path("/home/oppry/work/app3")
IMG1 = ROOT / "IMG1.jpg"      # blood-pressure monitor (3072x4096)
IMG2 = ROOT / "IMG2.jpg"      # tea product label (3072x4096)


def now() -> float:
    return time.perf_counter()


def fmt_ms(seconds: float) -> str:
    return f"{seconds * 1000:8.2f} ms"


def fmt_kb(num_bytes: int) -> str:
    return f"{num_bytes / 1024:8.1f} KB"


def bench_stage(name: str, fn, iters: int = 1):
    samples = []
    out = None
    for _ in range(iters):
        t0 = now()
        out = fn()
        samples.append(now() - t0)
    avg = sum(samples) / len(samples)
    print(f"  {name:<42} {fmt_ms(avg)}  (n={iters})")
    return out, avg


def downscale_luma(rgba: np.ndarray, w: int, h: int) -> np.ndarray:
    """Mimic FrameAnalyzer.downscaleLuma at w*h resolution."""
    img = Image.fromarray(rgba, mode="RGBA")
    small = img.resize((w, h), Image.BILINEAR)
    px = np.asarray(small, dtype=np.int32)
    r, g, b = px[..., 0], px[..., 1], px[..., 2]
    return ((r * 299 + g * 587 + b * 114) // 1000).astype(np.int16)


def mean_abs_diff(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.mean(np.abs(a.astype(np.int32) - b.astype(np.int32))))


def to_jpeg_max_dim(rgba: np.ndarray, max_dim: int, quality: int) -> bytes:
    img = Image.fromarray(rgba, mode="RGBA")
    w, h = img.size
    scale = max_dim / max(w, h)
    if scale < 1.0:
        img = img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
    buf = io.BytesIO()
    img.convert("RGB").save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def main() -> int:
    print("\n=== Input files ===")
    for p in (IMG1, IMG2):
        print(f"  {p.name}: {p.stat().st_size/1024:.1f} KB")

    print("\n=== Per-stage timings (single-thread, CPU only) ===")
    print("  Replicates FrameAnalyzer.analyze on the captured JPEG,")
    print("  going through: decode -> RGBA -> 24x24 luma -> diff -> 768px JPEG")
    print()

    for label, path in (("IMG1 (BP monitor)", IMG1), ("IMG2 (tea label)", IMG2)):
        print(f"--- {label} ---")

        # 1) JPEG -> RGBA bitmap (similar to ImageProxy.toBitmap).
        rgba, t_decode = bench_stage("jpeg decode -> RGBA bitmap", lambda: np.asarray(
            Image.open(path).convert("RGBA"), dtype=np.uint8
        ), iters=3)
        print(f"      bitmap shape: {rgba.shape}, "
              f"memory: {rgba.nbytes / 1024 / 1024:.1f} MB")

        # 2) downscale 24x24 + per-pixel luma (stability-check, current path)
        prev = None
        def f_small():
            return downscale_luma(rgba, 24, 24)
        small, t_small = bench_stage("downscale -> 24x24 + luma", f_small, iters=10)

        # 2b) Cheap alternative: just use the luma plane of the JPEG (YUV)
        # when reading back from bytes.
        def f_small_yuv():
            jpg = Image.open(path)
            jpg.load()
            return np.asarray(jpg.convert("L"), dtype=np.uint8)
        # To make this comparable we resize from RGB not RGBA:
        rgb_for_cheap = Image.open(path).convert("RGB")
        def f_small_cheap():
            small = np.asarray(rgb_for_cheap.resize((24, 24), Image.BILINEAR), dtype=np.uint8)
            return small
        _, t_small_cheap = bench_stage(
            "[alt] resize 24x24 RGB, skip RGBA/luma", f_small_cheap, iters=10
        )

        # 3) meanAbsDiff vs the previous frame.
        diff_prev = np.zeros((24, 24), dtype=np.int16)
        def f_diff():
            return mean_abs_diff(small, diff_prev)
        _, t_diff = bench_stage("meanAbsDiff (24x24 int)", f_diff, iters=200)

        # 4) Downscale to 768px + JPEG encode (the "emit stable frame" step)
        def f_emit():
            return to_jpeg_max_dim(rgba, max_dim=768, quality=80)
        jpeg, t_emit = bench_stage("downscale 768 + JPEG q80", f_emit, iters=3)
        print(f"      emitted JPEG: {fmt_kb(len(jpeg))}, base64: "
              f"{fmt_kb(len(base64.b64encode(jpeg)))}")

        # 4b) Smaller, cheaper alternative: 512 px, q72
        def f_emit_small():
            return to_jpeg_max_dim(rgba, max_dim=512, quality=72)
        jpeg_small, t_emit_small = bench_stage(
            "[alt] downscale 512 + JPEG q72", f_emit_small, iters=3
        )
        print(f"      [alt] emitted JPEG: {fmt_kb(len(jpeg_small))}, base64: "
              f"{fmt_kb(len(base64.b64encode(jpeg_small)))}")

        # 4c) Proposed: emit BOTH at the same time (same source bitmap).
        # analyze uses small (server-side cheaper), answer uses large (more detail).
        def f_emit_dual():
            a = to_jpeg_max_dim(rgba, max_dim=512, quality=70)
            b = to_jpeg_max_dim(rgba, max_dim=768, quality=75)
            return a, b
        dual, t_emit_dual = bench_stage(
            "[plan] downscale 512q70 + 768q75 in one pass", f_emit_dual, iters=3
        )
        a_jpeg, b_jpeg = dual
        print(f"      [plan] analyze jpeg: {fmt_kb(len(a_jpeg))}, "
              f"b64 {fmt_kb(len(base64.b64encode(a_jpeg)))} | "
              f"answer jpeg: {fmt_kb(len(b_jpeg))}, "
              f"b64 {fmt_kb(len(base64.b64encode(b_jpeg)))}")

        total_now = t_decode + t_small + t_diff + t_emit
        total_alt = t_decode + t_small_cheap + t_diff + t_emit_small
        total_dual = t_decode + t_small + t_diff + t_emit_dual
        print(f"  >>> CPU work (current code path):   {fmt_ms(total_now)}")
        print(f"  >>> CPU work (alt small + 512 q72):  {fmt_ms(total_alt)}")
        print(f"  >>> CPU work (dual emit 512+768):    {fmt_ms(total_dual)}")
        print()

    # Network-bandwidth proxy for the emitted JPEG.
    print("=== Network payload (out of FrameAnalyzer -> into LlmClient) ===")
    for label, jpeg in (("IMG1 @768 q80", jpeg), ("IMG2 @768 q80", jpeg)):
        b64 = base64.b64encode(jpeg)
        print(f"  {label}: raw {fmt_kb(len(jpeg))}, base64 {fmt_kb(len(b64))}")

    return 0


if __name__ == "__main__":
    sys.exit(main())

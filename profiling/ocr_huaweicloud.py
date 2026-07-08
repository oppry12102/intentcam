"""
Huawei Cloud OCR helper for the eval pipeline.

Replaces the on-device HMS ML Kit OCR backend in evaluations.
`profiling/eval_rctw_v2.py` was previously stuck because the on-device
OCR backend only works on a real Android device — this module lets
the eval run on any host by calling Huawei Cloud OCR instead.

Env vars (from the user's demo):
    HUAWEICLOUD_SDK_AK         — access key
    HUAWEICLOUD_SDK_SK         — secret key
    HUAWEICLOUD_SDK_PROJECT_ID — project id

Returns a normalized list of {text, confidence, bbox} blocks so callers
don't have to know about Huawei's words_block_list / location layout.
The bbox is `[ (x0, y0), (x1, y1), (x2, y2), (x3, y3) ]` in
normalized [0, 1] coords (mirrors the on-device `AndroidOcrEngine.kt`
output that `OcrEngine.kt` consumes).  When the SDK isn't installed or
the env vars are missing, `recognize()` returns `None` so callers can
fall back to a stub without crashing the eval.
"""

from __future__ import annotations

import base64
import io
import logging
import os
from pathlib import Path
from typing import Iterable, Optional

log = logging.getLogger("ocr_huaweicloud")

try:
    from huaweicloudsdkcore.auth.credentials import BasicCredentials
    from huaweicloudsdkcore.http.http_config import HttpConfig
    from huaweicloudsdkcore.exceptions import exceptions as huaweiexceptions
    from huaweicloudsdkocr.v1 import (
        OcrClient,
        RecognizeGeneralTextRequest,
        GeneralTextRequestBody,
    )
    from huaweicloudsdkocr.v1.region.ocr_region import OcrRegion
    _SDK_OK = True
except Exception as _e:  # pragma: no cover — graceful fallback
    _SDK_OK = False
    _SDK_IMPORT_ERROR = _e


def sdk_available() -> bool:
    """True iff the Huawei SDK imports cleanly.  Eval callers should
    check this before deciding to log warnings about a stub fallback."""
    return _SDK_OK


def env_available() -> bool:
    """True iff all three env vars are set so a real call can be made."""
    return all(os.environ.get(k) for k in (
        "HUAWEICLOUD_SDK_AK",
        "HUAWEICLOUD_SDK_SK",
        "HUAWEICLOUD_SDK_PROJECT_ID",
    ))


def _b64_image(image: bytes | str | Path) -> str:
    """Encode bytes / file-path / Path as base64 string."""
    if isinstance(image, (str, Path)):
        return base64.b64encode(Path(image).read_bytes()).decode()
    return base64.b64encode(image).decode()


def _client():
    """Build a fresh OcrClient.  Caller-side caching isn't worth it —
    OCR is one round-trip per fixture."""
    ak = os.environ["HUAWEICLOUD_SDK_AK"]
    sk = os.environ["HUAWEICLOUD_SDK_SK"]
    project_id = os.environ["HUAWEICLOUD_SDK_PROJECT_ID"]
    config = HttpConfig.get_default_config()
    credentials = BasicCredentials(ak, sk, project_id)
    return (
        OcrClient.new_builder()
        .with_http_config(config)
        .with_credentials(credentials)
        .with_region(OcrRegion.value_of("cn-north-4"))
        .build()
    )


def _normalize_block(block: dict, img_w: int, img_h: int) -> dict:
    """Convert a Huawei `words_block_list[i]` to the on-device shape:
        { text, confidence, bbox: [(x0, y0), (x1, y1), (x2, y2), (x3, y3)] }
    Bbox coords are normalized [0, 1].  Confidence is left as the float
    string Huawei returns (it can be "0.9876") — callers ignore when
    irrelevant.
    """
    text = (block.get("words") or "").strip()
    conf = block.get("confidence", "")
    try:
        conf_f = float(conf) if conf not in (None, "") else None
    except (TypeError, ValueError):
        conf_f = None
    loc = block.get("location") or []
    bbox_norm: list[tuple[float, float]] = []
    for pt in loc[:4]:
        # Huawei returns `location` as a list of [x, y] pairs (each pair
        # is itself a 2-element list).  Some older shapes serialize as
        # dicts with `x`/`y` keys — accept either.
        if isinstance(pt, dict):
            try:
                x = float(pt.get("x", 0))
                y = float(pt.get("y", 0))
            except (TypeError, ValueError):
                continue
        elif isinstance(pt, (list, tuple)) and len(pt) >= 2:
            try:
                x = float(pt[0]); y = float(pt[1])
            except (TypeError, ValueError):
                continue
        else:
            continue
        bbox_norm.append((x / max(1, img_w), y / max(1, img_h)))
    # Pad to 4 corners if Huawei returned < 4 (rare but defensive).
    while len(bbox_norm) < 4:
        bbox_norm.append(bbox_norm[-1] if bbox_norm else (0.0, 0.0))
    return {"text": text, "confidence": conf_f, "bbox": bbox_norm[:4]}


def recognize(
    image: bytes | str | Path,
    *,
    detect_direction: bool = True,
    language: str = "zh",
) -> Optional[list[dict]]:
    """Run `RecognizeGeneralText` on a JPEG and return a list of blocks,
    or `None` if the SDK/env aren't available.  Network/Huawei errors
    raise so the eval can decide whether to log+continue or fail loud."""
    if not _SDK_OK:
        log.warning("huaweicloudsdkocr not installed — OCR call skipped")
        return None
    if not env_available():
        log.warning(
            "HUAWEICLOUD_SDK_AK / SK / PROJECT_ID not all set — OCR call skipped"
        )
        return None

    # Get a JPEG-decoded image width/height for bbox normalization.
    # The SDK accepts JPEG/PNG bytes directly — no need to write to a
    # tmp file.
    if isinstance(image, (str, Path)):
        img_bytes = Path(image).read_bytes()
    else:
        img_bytes = image
    img_w, img_h = _peek_size(img_bytes)

    client = _client()
    request = RecognizeGeneralTextRequest()
    request.body = GeneralTextRequestBody(
        image=base64.b64encode(img_bytes).decode(),
        detect_direction=detect_direction,
        language=language,
        return_markdown_result=False,
    )
    response = client.recognize_general_text(request).to_dict()
    result = (response.get("result") or {})
    blocks = result.get("words_block_list") or []
    return [_normalize_block(b, img_w, img_h) for b in blocks if b]


def _peek_size(jpeg_bytes: bytes) -> tuple[int, int]:
    """Best-effort decode width/height for bbox normalization.
    Falls back to (1, 1) so the bbox stays unit-normalized."""
    try:
        from PIL import Image
        with Image.open(io.BytesIO(jpeg_bytes)) as img:
            return img.size
    except Exception:
        return (1, 1)


def crop_then_recognize(
    jpeg_bytes: bytes,
    x: float,
    y: float,
    w: float,
    h: float,
    *,
    max_dim: int = 1280,
    quality: int = 85,
    language: str = "zh",
) -> Optional[list[dict]]:
    """`read_text` helper — crop the requested region out of a JPEG and
    OCR the crop.  Returns the same block shape as `recognize()`.  Bboxes
    are normalized to the crop, not the original — call sites that need
    original-frame coords need to re-project themselves."""
    from PIL import Image
    img = Image.open(io.BytesIO(jpeg_bytes))
    W, H = img.size
    L = max(0, int(x * W))
    T = max(0, int(y * H))
    R = min(W, max(L + 1, int((x + w) * W)))
    B = min(H, max(T + 1, int((y + h) * H)))
    if R <= L or B <= T:
        return []
    cropped = img.crop((L, T, R, B))
    s = max_dim / max(cropped.size)
    if s < 1:
        cropped = cropped.resize(
            (int(cropped.size[0] * s), int(cropped.size[1] * s)),
            Image.LANCZOS,
        )
    buf = io.BytesIO()
    cropped.convert("RGB").save(buf, "JPEG", quality=quality)
    return recognize(buf.getvalue(), language=language)


def blocks_to_text(blocks: Optional[Iterable[dict]], sep: str = "\n") -> str:
    """Flatten a list of blocks to a newline-joined text blob."""
    if not blocks:
        return ""
    return sep.join(b.get("text", "") for b in blocks if b.get("text"))


# CLI mode for spot-checking.  Mirrors the user's `ocr_demo.py` so
# it can be used directly: `python3 profiling/ocr_huaweicloud.py <img>`.
if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2:
        print("usage: python3 ocr_huaweicloud.py <image_path>")
        sys.exit(1)
    blocks = recognize(sys.argv[1])
    if blocks is None:
        print("OCR unavailable — install huaweicloudsdkocr and set env vars")
        sys.exit(2)
    for i, b in enumerate(blocks, 1):
        print(f"{i:02d}. {b['text']}  conf={b['confidence']}  bbox={b['bbox']}")
    print("\n===== full text =====")
    print(blocks_to_text(blocks))

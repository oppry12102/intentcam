#!/usr/bin/env python3
"""
One-shot OCR runner: reads a base64-encoded JPEG from stdin, calls
Huawei Cloud OCR via `ocr_huaweicloud.recognize`, prints the result
as JSON to stdout.

Output JSON shape (mirrors `OcrResult` in shared/.../OcrEngine.kt):
  {"blocks": [
      {"text": "...", "confidence": 0.95,
       "corners": [[x, y], [x, y], [x, y], [x, y]]},  // normalized [0,1]
      ...
   ],
   "full_text": "..."}

`corners` are normalized to [0,1] in the input image's coordinate
system, matching what `AndroidOcrEngine.kt` produces (TL → TR → BR → BL
vertex order).

Used by `shared/.../eval/JvmHuaweiCloudOcrEngine.kt` to feed OCR
results into the Kotlin eval's `OcrEngine.Impl` without re-implementing
Huawei's SDK-HMAC-SHA256 signing.

Env vars (must all be set, propagated by Kotlin subprocess):
  HUAWEICLOUD_SDK_AK, HUAWEICLOUD_SDK_SK, HUAWEICLOUD_SDK_PROJECT_ID

Exit codes:
  0 — success (blocks JSON printed)
  1 — missing env var / SDK import failure
  2 — OCR call failed (network, auth, etc.)
  3 — empty stdin / malformed base64 input
"""
from __future__ import annotations

import base64
import json
import os
import sys


def main() -> int:
    try:
        from huaweicloudsdkcore.auth.credentials import BasicCredentials
        from huaweicloudsdkcore.http.http_config import HttpConfig
        from huaweicloudsdkocr.v1.region.ocr_region import OcrRegion
        # Import the existing helper so we don't duplicate the SDK
        # call + bbox-normalization logic.
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import ocr_huaweicloud  # noqa: E402
    except Exception as e:
        print(f"ERROR: import failed: {type(e).__name__}: {e}", file=sys.stderr)
        return 1

    ak = os.environ.get("HUAWEICLOUD_SDK_AK")
    sk = os.environ.get("HUAWEICLOUD_SDK_SK")
    project_id = os.environ.get("HUAWEICLOUD_SDK_PROJECT_ID")
    if not (ak and sk and project_id):
        print("ERROR: HUAWEICLOUD_SDK_AK / SK / PROJECT_ID must all be set",
              file=sys.stderr)
        return 1

    # Read base64 JPEG from stdin.
    raw = sys.stdin.buffer.read()
    if not raw:
        print("ERROR: empty stdin (expected base64 JPEG)", file=sys.stderr)
        return 3
    try:
        jpeg_bytes = base64.b64decode(raw)
    except Exception as e:
        print(f"ERROR: base64 decode failed: {e}", file=sys.stderr)
        return 3
    if len(jpeg_bytes) < 8 or jpeg_bytes[:2] != b"\xff\xd8":
        print(f"ERROR: decoded bytes don't start with JPEG SOI marker "
              f"(got {jpeg_bytes[:4].hex()})", file=sys.stderr)
        return 3

    # Call the existing helper which handles SDK + bbox normalization.
    try:
        blocks = ocr_huaweicloud.recognize(jpeg_bytes, detect_direction=True, language="zh")
    except Exception as e:
        print(f"ERROR: OCR call failed: {type(e).__name__}: {e}", file=sys.stderr)
        return 2

    if blocks is None:
        # Helper logs a warning and returns None when SDK/env missing.
        # We treat this as "empty OCR" rather than an error.
        blocks = []

    out_blocks = []
    for b in blocks:
        # ocr_huaweicloud returns 4-corner normalized coords already.
        out_blocks.append({
            "text": b.get("text", ""),
            "confidence": b.get("confidence") or 0.0,
            "corners": b.get("bbox", [[0, 0]] * 4),
        })
    full_text = " ".join(b.get("text", "") for b in out_blocks if b.get("text"))
    print(json.dumps({
        "blocks": out_blocks,
        "full_text": full_text,
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
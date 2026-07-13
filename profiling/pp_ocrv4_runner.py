#!/usr/bin/env python3
"""
Long-lived OCR runner that wraps `pp_ocrv4_mobile_engine.PaddleMobileEngine`
behind a line-delimited JSON-RPC interface on stdin/stdout.

This replaces the per-call `ocr_huaweicloud_runner.py` (which shells out
to Huawei Cloud) with an in-process, persistent Python process.  The
process keeps the PaddleOCR model resident for the entire eval run —
PaddleOCR's first init takes 5-30 s (weight download + load) and re-
paying that per fixture is unacceptable for 20+ fixture eval loops.

Protocol (line-delimited JSON over stdin/stdout):

  Request:
    {"id": 1, "method": "recognize", "params": {"image_b64": "..."}}

  Response (success):
    {"id": 1, "result": {"blocks": [
        {"text": "...", "confidence": 0.95, "corners": [[x, y], ...]}
    ], "full_text": "..."}}

  Response (error):
    {"id": 1, "error": {"code": -1, "message": "..."}}

  Control:
    {"method": "ping"}          → {"result": {"pong": true, ...}}
    {"id": N, "method": "shutdown"} → {"id": N, "result": {"bye": true}} + exit 0

Output JSON schema matches the existing `ocr_huaweicloud_runner.py`
shape so the Kotlin side (`JvmHuaweiCloudOcrEngine.parseRunnerOutput`,
`JvmLocalOcrEngine`) can share parsing logic.  Bbox `corners` are
4-corner polygon TL→TR→BR→BL normalized to [0, 1] in the
preprocessed-image coordinate system (equivalent to source-image
coords because `preprocess_image` preserves aspect ratio).

Env vars:
    LOCAL_OCR_KIND        "mobile" (default) | "server"
    LOCAL_OCR_MAX_LONG    int (default 4096)
    LOCAL_OCR_JPG_QUALITY int (default 90; engine validates 50..100)
    LOCAL_OCR_USE_GPU     "1" to enable (default 0; untested on this box)

PaddleOCR's print/log noise is sent to stderr by setting `show_log=False`
on PaddleOCR.  We do NOT redirect Python's stdout to stderr because we
need stdout for JSON-RPC — if PaddleOCR's internals print to stdout in
spite of `show_log=False`, the Kotlin side filters non-JSON lines.

Failure modes (each surfaces stderr log + error JSON, subprocess keeps
running for next call):
    - missing image_b64         → error.code = -1
    - base64 decode failure     → error.code = -2
    - non-JPEG bytes            → error.code = -3
    - engine init failure       → error.code = -10 (and subprocess keeps
                                 running; next call retries init)
    - per-image OCR failure     → error.code = -10
"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import sys
import time
import uuid
from pathlib import Path

# ---------------------------------------------------------------------------
# Logging — all of our logs go to stderr so they don't pollute the
# JSON-RPC stream on stdout.
# ---------------------------------------------------------------------------

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [pp-ocrv4-runner] %(levelname)s %(message)s",
    stream=sys.stderr,
)
log = logging.getLogger("pp_ocrv4_runner")

# ---------------------------------------------------------------------------
# Engine (lazily built on first recognize call so the import cost of
# paddleocr + the 5-30 s first-init don't block startup ping).
# ---------------------------------------------------------------------------

_engine = None  # PaddleMobileEngine singleton (process-global)


def _build_engine():
    global _engine
    if _engine is not None:
        return _engine

    kind = os.environ.get("LOCAL_OCR_KIND", "mobile")
    max_long = int(os.environ.get("LOCAL_OCR_MAX_LONG", "4096"))
    jpg_quality = int(os.environ.get("LOCAL_OCR_JPG_QUALITY", "90"))
    use_gpu = os.environ.get("LOCAL_OCR_USE_GPU", "0") == "1"

    # Import here so the runner module loads fast (lets ping reply
    # immediately even when paddleocr isn't installed).
    from pp_ocrv4_mobile_engine import PaddleMobileEngine  # noqa: WPS433

    log.info(
        "building engine: kind=%s max_long=%d jpg_quality=%d use_gpu=%s",
        kind, max_long, jpg_quality, use_gpu,
    )
    t0 = time.time()
    _engine = PaddleMobileEngine(
        engine_kind=kind,
        use_gpu=use_gpu,
        max_long=max_long,
        jpg_quality=jpg_quality,
        show_log=False,  # suppress PaddleOCR's chatty logger
    )
    log.info("engine built in %.1fs", time.time() - t0)
    return _engine


# ---------------------------------------------------------------------------
# Tempfile management — content-addressed so repeat JPEGs hit the page
# cache and don't re-pay the disk write.
# ---------------------------------------------------------------------------

_TMP_DIR = Path("/tmp/pp_ocrv4_eval")
_TMP_DIR.mkdir(parents=True, exist_ok=True)


def _tmp_for_jpeg(jpeg_bytes: bytes) -> Path:
    h = hashlib.sha1(jpeg_bytes).hexdigest()[:16]
    p = _TMP_DIR / f"{h}.jpg"
    if not p.exists():
        p.write_bytes(jpeg_bytes)
    return p


# ---------------------------------------------------------------------------
# Bbox normalization: preprocessed-image pixel coords → [0, 1].
#
# PaddleOCR returns bboxes in pixel coords of the preprocessed image
# (the JPEG written by `preprocess_image` to
# `/tmp/pp_ocrv4_mobile_engine/<uuid5_hash>.jpg`).  That image has the
# same aspect ratio as the source, so normalizing by preprocessed dims
# gives a [0, 1] representation equivalent to source-image [0, 1] — the
# downstream Kotlin hint formatter can treat either as the same.
# ---------------------------------------------------------------------------


def _preprocessed_dims(src_path: Path) -> tuple[int, int]:
    """Return (width, height) of the preprocessed JPEG that
    `preprocess_image` writes for `src_path`.  Predictable path:
    /tmp/pp_ocrv4_mobile_engine/<uuid5(NAMESPACE_URL, src_path) hex[:12]>.jpg
    """
    h = uuid.uuid5(uuid.NAMESPACE_URL, str(src_path)).hex[:12]
    pp = Path(f"/tmp/pp_ocrv4_mobile_engine/{h}.jpg")
    if pp.exists():
        try:
            from PIL import Image as PILImage  # local import — keep cold-start fast
            with PILImage.open(pp) as im:
                return im.size  # (w, h)
        except Exception as e:  # pragma: no cover — defensive
            log.warning("could not read preprocessed dims from %s: %s", pp, e)
    # Fallback: use the source dims.  Won't be reached in normal flow
    # because engine.recognize has just written the preprocessed file.
    try:
        from PIL import Image as PILImage
        with PILImage.open(src_path) as im:
            return im.size
    except Exception:
        return (1, 1)


def _normalize_bbox(polygon, img_w: int, img_h: int) -> list[list[float]]:
    out = []
    denom_w = max(1, img_w)
    denom_h = max(1, img_h)
    for pt in polygon:
        try:
            x, y = float(pt[0]), float(pt[1])
        except (TypeError, ValueError, IndexError):
            continue
        nx = max(0.0, min(1.0, x / denom_w))
        ny = max(0.0, min(1.0, y / denom_h))
        out.append([nx, ny])
    # Pad to 4 corners if PaddleOCR returned < 4 (rare; defensive).
    while len(out) < 4:
        out.append(out[-1] if out else [0.0, 0.0])
    return out[:4]


# ---------------------------------------------------------------------------
# Request handlers
# ---------------------------------------------------------------------------


def _decode_jpeg(params: dict) -> tuple[bytes | None, dict | None]:
    """Return (jpeg_bytes, None) on success or (None, error_dict) on fail."""
    img_b64 = params.get("image_b64") or params.get("jpeg_b64")
    if not img_b64:
        return None, {"code": -1, "message": "missing image_b64/jpeg_b64 in params"}
    try:
        jpeg_bytes = base64.b64decode(img_b64)
    except Exception as e:
        return None, {"code": -2, "message": f"base64 decode failed: {e}"}
    if len(jpeg_bytes) < 8 or jpeg_bytes[:2] != b"\xff\xd8":
        return None, {
            "code": -3,
            "message": f"not a JPEG (no SOI marker; first bytes: {jpeg_bytes[:4].hex()})",
        }
    return jpeg_bytes, None


def handle_recognize(req_id, params):
    jpeg_bytes, err = _decode_jpeg(params)
    if err is not None:
        return {"id": req_id, "error": err}

    try:
        engine = _build_engine()
        tmp = _tmp_for_jpeg(jpeg_bytes)
        t0 = time.time()
        result = engine.recognize(str(tmp))
        elapsed = time.time() - t0

        # Read preprocessed-image dims to normalize bbox coords to [0, 1].
        pp_w, pp_h = _preprocessed_dims(tmp)

        blocks = []
        for box in result.boxes:
            text = (box.text or "").strip()
            if not text:
                continue
            blocks.append({
                "text": text,
                "confidence": float(box.conf),
                "corners": _normalize_bbox(box.bbox, pp_w, pp_h),
            })
        full_text = " ".join(b["text"] for b in blocks)
        return {
            "id": req_id,
            "result": {
                "blocks": blocks,
                "full_text": full_text,
                "_meta": {
                    "engine_kind": engine.engine_kind,
                    "elapsed_s": round(elapsed, 4),
                    "n_lines": len(blocks),
                    "preprocessed_dims": [pp_w, pp_h],
                },
            },
        }
    except Exception as e:
        log.exception("recognize failed")
        return {
            "id": req_id,
            "error": {"code": -10, "message": f"{type(e).__name__}: {e}"},
        }


def handle_ping(req_id, _params):
    return {
        "id": req_id,
        "result": {
            "pong": True,
            "engine_built": _engine is not None,
            "engine_kind": _engine.engine_kind if _engine is not None else None,
            "pid": os.getpid(),
        },
    }


HANDLERS = {
    "recognize": handle_recognize,
    "ping": handle_ping,
}


# ---------------------------------------------------------------------------
# Main loop — read JSON lines from stdin, dispatch, write JSON lines.
# ---------------------------------------------------------------------------


def _send(obj: dict) -> None:
    """Write a JSON response to stdout + flush."""
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main() -> int:
    log.info("starting (PID=%d, kind=%s)", os.getpid(), os.environ.get("LOCAL_OCR_KIND", "mobile"))
    # Announce readiness before entering the loop so the parent (Kotlin)
    # can wait on this exact line as a startup ping.
    _send({"event": "ready", "pid": os.getpid()})

    try:
        for raw in sys.stdin:
            line = raw.strip()
            if not line:
                continue
            try:
                req = json.loads(line)
            except json.JSONDecodeError as e:
                log.warning("malformed JSON on stdin: %s", e)
                _send({"error": {"code": -100, "message": f"bad json: {e}"}})
                continue

            method = req.get("method")
            req_id = req.get("id")
            params = req.get("params") or {}

            if method == "shutdown":
                log.info("shutdown received, exiting")
                _send({"id": req_id, "result": {"bye": True}})
                return 0

            handler = HANDLERS.get(method)
            if not handler:
                _send({
                    "id": req_id,
                    "error": {"code": -404, "message": f"unknown method: {method!r}"},
                })
                continue

            try:
                resp = handler(req_id, params)
            except Exception as e:
                log.exception("handler %s crashed", method)
                resp = {
                    "id": req_id,
                    "error": {"code": -500, "message": f"handler crashed: {type(e).__name__}: {e}"},
                }
            _send(resp)
    except BrokenPipeError:
        log.warning("stdin closed (broken pipe), exiting")
    except KeyboardInterrupt:
        log.warning("interrupted, exiting")
    return 0


if __name__ == "__main__":
    sys.exit(main())
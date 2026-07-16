# 2026-07-11 — Auto-OCR on every zoom crop (Phase 2)

## Status
Accepted. Shipped Phase 2, 2026-07-11.

## Context

Pre-Phase 2 the model had two paths to get high-fidelity OCR of
a region:
1. `read_text(bbox)` — explicit tool call returning formatted OCR
   text.  Cost: one tool round + the model's own text continues
   to drift around the OCR hint.
2. `zoom_in(bbox)` — explicit tool call returning a high-res
   crop image.  Cost: one image token.

The two were **not equivalent** — `read_text` was the only way
to get verbatim characters without paying image-token cost, but
it required the model to commit to "I want text only" up front.
The model often defaulted to `zoom_in` (image), then complained
the crop was blurry / the OCR was bad — the very OCR it could
have triggered explicitly.

A first attempt (Phase 1) **removed `read_text` entirely** and
forced the model to use `zoom_in` for everything.  This failed:
eval showed "free information paradox" — without OCR attached,
the model hedged and downgraded its confidence, costing -0.05
composite.

## Decision

Ship Phase 2 (the load-bearing piece):
- **Every `zoom_in` crop auto-runs `OcrEngine.recognize(cropBytes)`**
  on-device and **the formatted OCR hint is attached to the next
  user message alongside the image** (without a separate
  `read_text` call).
- The crop OCR hint uses `OcrResult.formatHint(blocks,
  maxLines=10, isCropHint=true)` with header `【zoom_in crop OCR
  高保真重扫】` and echoes "**trust 这些字符 verbatim**" for
  [LOW] lines (vs round-1's "workflow: 调 zoom_in 重扫" since
  this IS the high-fidelity re-scan).
- The system prompt Step 1-4 workflow narrative is the
  load-bearing piece: tells the model that crop OCR is
  automatically trustworthy, eliminating the hedge.

## Consequences

- Removed `read_text` tool (3-tool → 2-tool final-answer surface;
  became 3-tool again with v1.1's `extract_text`).
- Net composite lift on Phase 2 @100: +0.04 to +0.06 across
  eval runs (vs Phase 1 failure).
- Crop bboxes are in crop frame, not original; system prompt
  instructs the model to offset by zoom (x, y, w, h) when
  reusing bbox coordinates in `details[]`.

## Follow-up

v1.1 (2026-07-12) added `extract_text` — text-only OCR for
already-visible regions.  Model self-routes between
`extract_text` (text-only, cheaper) and `zoom_in` (need new
pixels).
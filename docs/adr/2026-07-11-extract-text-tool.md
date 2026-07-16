# 2026-07-12 — `extract_text` tool (v1.1)

## Status
Accepted. Shipped v1.1, 2026-07-12.

## Context

After Phase 2 (auto-OCR on every zoom crop), the only way to get
verbatim characters was via `zoom_in` which **also** ships the
cropped image.  For already-visible regions (where the model
already saw the area in the round-1 thumbnail), paying an image
token just to re-scan the OCR is wasteful — the model only wants
characters.

## Decision

Add `extract_text(bbox, source)` as a text-only sibling of
`zoom_in`:
- Same crop path (`cropJpegRegion` + `OcrEngine.recognize`)
- Same formatted hint shape (`OcrResult.formatHint(isCropHint=true)`)
- **No `followUpJpeg`** — only the OCR text attaches to the next
  user message
- System prompt's Step 2 now defaults to `extract_text` for
  `[LOW]` / `漏扫` / `已见区域` cases; `zoom_in` is reserved for
  "need to see new pixels" (corner text not in thumbnail,
  non-text content)

## Consequences

- Adopted by model in 5-7 / 20 fixtures (25-35%) for the
  text-only queries; 0 / 20 for the "need new pixels" cases.
  Model self-routes correctly.
- Composite @20 mean: 0.883 across 3 runs (vs v1.0 baseline
  0.887) — statistically equivalent; v1.1 is a capability
  unlock, not a ceiling bump.
- Eval scorer's `extract_text` pickScore promoted from "other
  tool" (0.7) to 1.0 (valid recon tool) so the ~25% adoption
  is correctly credited in r1.

## Migration

None — additive tool, no eval ground truth changes.
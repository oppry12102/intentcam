# 2026-07-10 — On-device 4096 sensor resolution + cold-start camera pre-warm (v1.0)

## Status
Accepted. Implemented in v1.0 ship.

## Context

Pre-v1.0 the CameraX `ImageAnalysis` use case had **no explicit
`ResolutionSelector`**, which made CameraX default to **640×480
VGA** regardless of what `MAX_DIM` / `MAX_FULL_DIM` constants said.
The downstream `encodeBitmap` only downscales (never upscales), so
the LLM-facing JPEG came out at 640×480 — a thumbnail smaller than
a website favicon.  `zoom_in` follow-ups were also dying because
50% of 640 = 320px = a useless "magnified" crop.

Additionally, the first-launch sequence
`onCreate` → `ProcessCameraProvider.getInstance()` → permission
grant → `bindToLifecycle` → `analyze()` first frame took 200-800ms
on real devices.  Any shutter tap during that window hit the
500ms capture timeout.

## Decision

Two coordinated fixes:

1. **`pickLargestAnalysisSize(provider, selector)`** in
   `MainActivity.kt` queries the back camera's
   `StreamConfigurationMap.getOutputSizes(YUV_420_888)` via
   `Camera2CameraInfo`, picks the largest 4:3 (fall back to
   largest-by-area), and passes it to
   `ResolutionSelector(targetSize, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`.
   Works on every CameraX-supported device without hardcoding a
   number that would crash analyzers on 50MP sensors or waste
   resolution on 5MP ones.

2. **`ProcessCameraProvider.getInstance(app)` kicked off at
   `AppViewModel` construction** (during `onCreate`).  Provider
   service connection runs in parallel with permission grant +
   UI render.  Capture timeout raised 500ms → 3000ms as a
   safety net.

## Consequences

- Every fixture now sees the architecture the model was tuned
  for (full sensor resolution, not VGA).
- Cold-start to first-capture latency dropped from "often times
  out" to "typically <100ms wait".
- On-device image pipeline matches the eval's 3200-px input.
- Debug log gained `[CAP]` line with actual `waited=` and
  `src=WxH` so cold-vs-warm + sensor-res issues are visible
  at a glance.

## Migration

None — captured inline. The `pickLargestAnalysisSize()` helper
lives in `MainActivity.kt` next to its caller.
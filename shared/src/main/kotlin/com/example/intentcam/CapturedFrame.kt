package com.example.intentcam

/**
 * One captured frame, in two encodings (1-only image strategy):
 *  - [thumbnail] is sized for the LLM (768 max-dim, q80).  Sent as
 *    the round-1 image so the model can pick a tool + scan the scene.
 *  - [fullRes] is the original photo at high quality.  Stays in
 *    memory in case the model's `zoom_in` tool needs to crop a
 *    region at native pixels.
 *  - [quadrants] used to carry four 768px crops bundled into round 1
 *    ("1+4" mode — the LLM-native OCR substitute).  Retired 2026-07-06
 *    after a 20-fixture RCTW-17 ablation showed 1-only beats 1+4 by
 *    +0.10 composite and halved timeout rate.  Stays on the data
 *    class as `emptyList()` so the rest of the pipeline (notably
 *    [com.example.intentcam.LlmClient.userImageWithQuadrants]) can
 *    still compile, and `--quadrants` eval flag can flip it back on
 *    for A/B testing.
 *
 * Pure-Kotlin data class — usable from both the Android app and the
 * JVM eval without any Android-only types.
 */
data class CapturedFrame(
    val thumbnail: ByteArray,
    val fullRes: ByteArray,
    val quadrants: List<ByteArray> = emptyList(),
)
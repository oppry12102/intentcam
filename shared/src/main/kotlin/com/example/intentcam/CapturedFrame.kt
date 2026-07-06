package com.example.intentcam

/**
 * One captured frame, in three encodings:
 *  - [thumbnail] is sized for the LLM (768 max-dim, q80).  Sent as
 *    the round-1 image so the model can pick a tool + scan the scene.
 *  - [fullRes] is the original photo at high quality.  Stays in
 *    memory in case the model's `zoom_in` tool needs to crop a
 *    region at native pixels.
 *  - [quadrants] are four crops of the upright full-res, each scaled
 *    to 768 max-dim at q85.  Bundled with the thumbnail in round 1
 *    so the model sees high-detail coverage of every corner without
 *    paying for a 1-2 round zoom_in cycle (LLM-native substitute for
 *    on-device OCR).  Order: top-left, top-right, bottom-left,
 *    bottom-right.
 *
 * Pure-Kotlin data class — usable from both the Android app and the
 * JVM eval without any Android-only types.
 */
data class CapturedFrame(
    val thumbnail: ByteArray,
    val fullRes: ByteArray,
    val quadrants: List<ByteArray> = emptyList(),
)
package com.example.intentcam

/**
 * Image-pipeline dimension + quality constants shared between the
 * Android `FrameAnalyzer` (production) and the JVM `EvalRunner`
 * (eval).  Before 2026-07-14, `MAX_FULL_DIM=4096` was hardcoded as
 * a literal in `EvalRunner.kt:158` to mirror `FrameAnalyzer.kt:176`
 * — any drift between the two was invisible until a regression
 * appeared.
 *
 * Lives in `shared/` so both `:app` and `:shared:eval` can import
 * the same `const val` (Kotlin `const val` in an `object` is
 * inlined at the call site, no runtime dispatch).
 */
object ImagePipeline {
    /** Round-1 thumbnail max-dim sent to the LLM every round.
     *  3200 is the 2026-07-12 sweet spot — pushes the LLM out of
     *  "must drill down for everything" into "can resolve dense
     *  fixtures directly", but stays under the model's
     *  attention-spread cliff (4096 regressed -0.017 in option D
     *  testing). */
    const val MAX_DIM = 3200

    /** JPEG quality for the round-1 thumbnail re-encode. */
    const val QUALITY = 90

    /** Full-res kept in memory for `zoom_in` crop source.
     *  4096 is the upper bound — the JPEG re-encode is at native
     *  phone-photo size (4:3 sensor ~1920x1440, higher-end
     *  ~4032x3024), so 4096 just caps memory. */
    const val MAX_FULL_DIM = 4096

    /** JPEG quality for the full-res encoding — q95 is visually
     *  lossless so each subsequent crop is also lossless. */
    const val FULL_QUALITY = 95
}

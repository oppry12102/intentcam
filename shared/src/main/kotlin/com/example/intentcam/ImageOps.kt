package com.example.intentcam

/**
 * Strategy holder for image operations that need a platform-specific
 * implementation (Bitmap on Android, BufferedImage / ImageIO on the
 * JVM eval).
 *
 * `cropJpegRegion` is called by the `zoom_in` tool body to crop a
 * JPEG byte array down to a normalized rectangle.  The Android
 * implementation uses `BitmapFactory` + `BitmapRegionDecoder`; the
 * JVM eval implementation uses `javax.imageio.ImageIO`.
 *
 * `cropImpl` is set once at startup:
 *   - Android app: `MainActivity.onCreate` installs the Bitmap impl
 *   - JVM eval:    `EvalMain` installs the ImageIO impl before any
 *                  `runCycle` call
 *
 * If `cropImpl` is null (eval that forgot to install, or unit test
 * that doesn't care), `cropJpegRegion` returns null and the calling
 * tool sees a "crop failed" result — fail-closed rather than NPE.
 */
object ImageOps {
    /**
     * Crop + downscale to thumbnail sizing + re-encode.  [quality] is
     * the JPEG quality (0-100) the impl should use for the re-encode;
     * Android and JVM impls must accept the same range.  Without
     * per-call quality the eval's quadrant path was double-encoding
     * (default q75 → q85) while the app's was single-encoding (q85),
     * so eval scores drifted from prod.
     */
    @JvmStatic
    var cropImpl: ((jpeg: ByteArray, x: Float, y: Float, w: Float, h: Float, quality: Int) -> ByteArray?)? = null

    @JvmStatic
    var thumbnailImpl: ((jpeg: ByteArray, maxDim: Int, quality: Int) -> ByteArray?)? = null

    fun cropJpegRegion(
        jpeg: ByteArray,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        quality: Int = DEFAULT_CROP_QUALITY,
    ): ByteArray? = cropImpl?.invoke(jpeg, x, y, w, h, quality)

    /**
     * Encode the JPEG as a thumbnail (max-dim cap + JPEG re-encode).
     * Used by the eval to produce a FrameAnalyzer-equivalent thumbnail
     * from a raw fixture image.
     */
    fun encodeThumbnail(jpeg: ByteArray, maxDim: Int, quality: Int): ByteArray? =
        thumbnailImpl?.invoke(jpeg, maxDim, quality)

    /** Default quality for `cropJpegRegion` callers (zoom_in).
     *  Phase 2 (2026-07-11) removed `read_text` so this is now only
     *  used by zoom_in.  Bumped from 80 → 90 (2026-07-10): at q80,
     *  small text glyphs in the crop start to smudge on the 1568-cap
     *  re-encode; q90 keeps the edge detail the LLM needs to read
     *  dense-text fixtures. */
    const val DEFAULT_CROP_QUALITY = 90

    /** Max-dim cap on the JPEG bytes produced by `cropJpegRegion`.
     *  3200 matches `MAX_DIM` (round-1 thumbnail) — the proven
     *  2026-07-12 sweet spot.  Option D (4096/4096) was tested
     *  and REVERTED — pushing the cap to model max caused
     *  "attention-spread" regression (composite 0.902 → 0.885).
     *  3200/3200 keeps zoom_in a real (or equal-resolution)
     *  magnifier while staying in the model's focused-attention
     *  band. */
    const val CROP_OUTPUT_MAX_DIM = 3200
}
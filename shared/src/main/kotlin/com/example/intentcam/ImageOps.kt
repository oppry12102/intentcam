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
    @JvmStatic
    var cropImpl: ((jpeg: ByteArray, x: Float, y: Float, w: Float, h: Float) -> ByteArray?)? = null

    @JvmStatic
    var thumbnailImpl: ((jpeg: ByteArray, maxDim: Int, quality: Int) -> ByteArray?)? = null

    fun cropJpegRegion(
        jpeg: ByteArray,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): ByteArray? = cropImpl?.invoke(jpeg, x, y, w, h)

    /**
     * Encode the JPEG as a thumbnail (max-dim cap + JPEG re-encode).
     * Used by the eval to produce a FrameAnalyzer-equivalent thumbnail
     * from a raw fixture image.
     */
    fun encodeThumbnail(jpeg: ByteArray, maxDim: Int, quality: Int): ByteArray? =
        thumbnailImpl?.invoke(jpeg, maxDim, quality)
}
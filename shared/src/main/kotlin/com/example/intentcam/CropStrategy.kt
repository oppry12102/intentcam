package com.example.intentcam

/**
 * Top-level convenience wrapper that delegates to [ImageOps.cropJpegRegion].
 * Kept as a free function so call sites don't need to know about the
 * strategy holder — `import com.example.intentcam.cropJpegRegion` and
 * call away, the right implementation is plugged in at app startup.
 */
fun cropJpegRegion(
    fullResJpeg: ByteArray,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    quality: Int = ImageOps.DEFAULT_CROP_QUALITY,
): ByteArray? = ImageOps.cropJpegRegion(fullResJpeg, x, y, w, h, quality)

/**
 * Same for thumbnail-style downscale + re-encode.  Used by the eval to
 * replicate FrameAnalyzer's thumbnail step on raw fixture bytes.
 */
fun encodeThumbnail(
    fullResJpeg: ByteArray,
    maxDim: Int,
    quality: Int,
): ByteArray? = ImageOps.encodeThumbnail(fullResJpeg, maxDim, quality)
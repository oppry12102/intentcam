package com.example.intentcam

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.util.Log
import com.huawei.hms.mlsdk.MLAnalyzerFactory
import com.huawei.hms.mlsdk.common.MLApplication
import com.huawei.hms.mlsdk.common.MLApplicationSetting
import com.huawei.hms.mlsdk.common.MLFrame
import com.huawei.hms.mlsdk.text.MLLocalTextSetting
import com.huawei.hms.mlsdk.text.MLRemoteTextSetting
import com.huawei.hms.mlsdk.text.MLText
import com.huawei.hms.mlsdk.text.MLTextAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HMS ML Kit OCR backend with end-cloud fallback.
 *
 * Strategy:
 *   1. **On-device OCR** (default) — pure local, no network.  Uses
 *      the bundled Chinese model pack
 *      (`ml-computer-vision-ocr-cn-model`) + Latin model inside
 *      `ml-computer-vision-ocr`.
 *   2. **Cloud OCR** (fallback) — HMS remote endpoint with our
 *      `api_key` from `agconnect-services.json`.  Activated only when
 *      on-device fails (init NPE, model download broken, or returns
 *      0 blocks).  This is **still HMS** (no other OCR library),
 *      just the cloud variant — same policy constraints.
 *
 * Both analyzers are created once at [installAndroidOcr] and reused
 * across calls so HMS's model cache stays hot.  Bitmap is decoded
 * with `ARGB_8888` and downscaled to 1920 px on the long side per
 * HMS best practices (small bitmaps → faster + more accurate OCR;
 * also avoids HMS's internal size limits).
 *
 * Wired into [OcrEngine] via [installAndroidOcr] from
 * `MainActivity.onCreate`.  Returns text + 4-point coordinates +
 * confidence per block, all in **normalized** [0, 1] coordinates so
 * the LLM tooling can reason about position without knowing the
 * input image's pixel dimensions.
 *
 * ## Threading
 *
 * HMS's `MLTextAnalyzer.asyncAnalyseFrame` is callback-based; we
 * bridge it to a suspend function via [suspendCancellableCoroutine].
 * `recognize` is therefore safe to call from any coroutine context
 * (it's the public entry point used by the auto-OCR path —
 * round-1 pre-pass + per-zoom_in crop).  Phase 2 (2026-07-11) removed
 * the `read_text` tool that previously called this directly.
 */

/** Languages recognized up-front by the local analyzer.  HMS will
 *  lazy-download the matching model packs (zh from
 *  `ml-computer-vision-ocr-cn-model`, en bundled in the OCR AAR).
 *  We anchor on "zh" so the API call is well-typed; per-image
 *  auto-language-detection runs inside the analyzer regardless. */
private const val PRIMARY_LANGUAGE = "zh"

/** Max long-side dimension for the bitmap fed to HMS OCR.  Larger
 *  images get downscaled; smaller ones are sent as-is.  1920 px is
 *  HMS's recommended sweet spot — covers 1080p+ camera output
 *  comfortably while staying under HMS's internal limits. */
private const val MAX_BITMAP_DIM = 1920

private const val TAG = "AndroidOcrEngine"

/** Set true after [installAndroidOcr] completes the install path. */
@Volatile private var installed: Boolean = false

/** Cached on-device analyzer (preferred path).  Created lazily on
 *  first call to keep the install path fast; once built, reused
 *  across all subsequent recognize() calls so HMS's model cache
 *  stays hot. */
@Volatile private var localAnalyzer: MLTextAnalyzer? = null

/** Cached cloud analyzer (fallback when local fails).  Lazy +
 *  best-effort — if creation fails (no network, AGC auth missing,
 *  remote service disabled), we just stay on local-only mode and
 *  log the cloud-unavailable state. */
@Volatile private var cloudAnalyzer: MLTextAnalyzer? = null

/** Cached Application context for analyzer creation.  Captured at
 *  [installAndroidOcr] so subsequent calls don't need it threaded
 *  through. */
@Volatile private var cachedContext: Context? = null

/** Tracks whether the FIRST on-device call has been issued — used
 *  to log a one-shot "模型可能正在下载" message so users don't
 *  think the app is stuck on the first ever OCR (the Chinese model
 *  pack downloads on demand from Huawei's CDN, 5-30s on cold cache). */
@Volatile private var firstLocalCallDone: Boolean = false

/**
 * Set up the HMS ML Kit OCR backend.
 *
 *   1. Initialize ML Kit Services framework (with AGC JSON if
 *      present, no-key fallback otherwise).
 *   2. Install an [OcrEngine.Impl] that decodes + downscales +
 *      recognizes each JPEG, with end→cloud fallback.
 *
 * Idempotent: safe to call more than once (subsequent calls are
 * no-ops via the [installed] flag).
 *
 * @param app the [Application] context — required by HMS for
 *   analyzer creation and HMS Core Services bootstrap.
 */
fun installAndroidOcr(app: Application) {
    if (installed) return
    installed = true
    cachedContext = app.applicationContext ?: app

    // Configure ML Kit Services.  `fromResource` looks up the
    // `agconnect-services` resource bundled by AppGallery Connect;
    // when it's present (proper AGC integration), the SDK uses it
    // for the app ID + API key + cloud endpoint.  When it's missing
    // (local-dev without AGC), `fromResource` throws and we fall
    // back to the no-key `initialize(Context)` overload.
    //
    // HMS 3.x requires `MLApplication` to have a valid app ID even
    // for pure-local mode — analyzer.create() reads getAppId() and
    // NPEs otherwise.  So the AGC JSON path is the only reliable
    // init in 3.x; the no-key fallback exists for diagnostic
    // purposes only and will produce 0-block OCR.
    val existing = MLApplication.getInstance()
    val mlApplication: MLApplication? = if (existing != null) {
        Log.i(TAG, "MLApplication 已初始化 (instance=${existing.javaClass.simpleName})")
        existing
    } else try {
        val setting: MLApplicationSetting = MLApplicationSetting.fromResource(app)
        MLApplication.initialize(app, setting)
    } catch (e: Throwable) {
        Log.w(TAG, "MLApplicationSetting.fromResource failed (${e.message}); " +
            "falling back to no-key initialize (will NPE on analyzer.create in HMS 3.x)")
        try {
            MLApplication.initialize(app)
        } catch (e2: Throwable) {
            Log.w(TAG, "MLApplication.initialize (no-key) also failed: ${e2.message}")
            null
        }
    }
    if (mlApplication == null) {
        Log.w(TAG, "OCR 后端 init 失败：本地 + 云测都不可用")
    }

    OcrEngine.impl = OcrEngine.Impl { jpegBytes ->
        recognizeBlocking(jpegBytes)
    }

    Log.i(TAG, "HMS ML Kit OCR backend installed (Latin + Chinese offline, cloud fallback)")
}

/**
 * Decode a JPEG into a Bitmap, downscaled to ≤ [MAX_BITMAP_DIM] on
 * the long side and converted to ARGB_8888.
 *
 * Why downscale:
 *   - HMS OCR is dimension-bounded internally; > ~4K images can be
 *     silently truncated or rejected.
 *   - Smaller bitmaps = faster recognition + lower memory pressure
 *     (we never hold a 4096×3072 ARGB bitmap through analyzer + GC).
 *   - OCR accuracy plateaus around 1080p for printed text; 4K
 *     brings no signal, just noise.
 *
 * Why ARGB_8888 (not BitmapFactory default RGB_565):
 *   - 565 loses 5 bits per channel; grayscale text on tinted
 *     backgrounds can drop below OCR's binarization threshold.
 *   - HMS samples commonly use 8888 — staying aligned avoids a
 *     class of "works on Pixel, fails on Huawei" regressions.
 */
private fun decodeBitmap(jpegBytes: ByteArray): Bitmap? {
    // First pass: bounds-only decode to learn src dimensions
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, boundsOpts)
    val srcW = boundsOpts.outWidth
    val srcH = boundsOpts.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    // Power-of-2 sample size — coarse but cheap, and HMS doesn't
    // need exact pixels.  We overshoot a bit (target max * 2) so
    // that the resulting bitmap isn't significantly smaller than
    // the target after rounding.
    var sample = 1
    val longest = maxOf(srcW, srcH)
    while (longest / (sample * 2) >= MAX_BITMAP_DIM) {
        sample *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOpts)
}

/**
 * Suspend wrapper around the callback-based HMS API.  Strategy:
 *
 *   1. Try on-device analyzer (cached).  If it returns ≥1 block,
 *      use the result.
 *   2. On empty result OR exception, try cloud analyzer.  If it
 *      returns ≥1 block, use the result.
 *   3. Both empty → return [OcrResult.EMPTY] so the OCR_ERR /
 *      OCR status log surfaces "no text" cleanly.
 *
 * Throws [IllegalArgumentException] only when the JPEG itself is
 * undecodable (truncated, not a JPEG, etc.).
 */
private suspend fun recognizeBlocking(jpegBytes: ByteArray): OcrResult {
    // Decode + downscale off the main thread — a 12MP JPEG decode is
    // tens of ms and, combined with the native OCR, would otherwise
    // block the UI thread (runCycle is driven from viewModelScope on
    // Dispatchers.Main).  Keeps taps responsive and avoids ANR kills
    // that can look like a "crash on recognize".
    val bitmap = withContext(Dispatchers.IO) { decodeBitmap(jpegBytes) }
        ?: throw IllegalArgumentException("OCR: 无法解码 JPEG (${jpegBytes.size}B)")

    // First-call UX: surface that HMS may be downloading the
    // Chinese model pack on first ever use.  Without this the
    // user sees a 5-30s freeze with no indication of what's
    // happening and assumes the app crashed.
    if (!firstLocalCallDone) {
        Log.i(TAG, "首次调用本地 OCR — 如果模型未缓存，HMS 会从云端下载（约 5-30s）")
    }

    // Attempt 1: on-device
    val localResult = try {
        recognizeWith(bitmap, useCloud = false)
    } catch (e: Throwable) {
        Log.w(TAG, "本地 OCR 异常：${e.javaClass.simpleName}: ${e.message}，切换云测")
        OcrResult.EMPTY
    }

    if (localResult.blocks.isNotEmpty()) {
        firstLocalCallDone = true
        bitmap.recycle()
        return localResult
    }

    // Local returned 0 blocks.  Either the image has no text, OR
    // the model pack is broken / not yet downloaded.  Try cloud
    // to disambiguate — if cloud returns text, we know local was
    // the problem; if cloud also returns nothing, the image truly
    // has no text.
    Log.w(TAG, "本地 OCR 0 块（模型未下载 / 图上无文字），尝试云测...")
    val cloudResult = try {
        recognizeWith(bitmap, useCloud = true)
    } catch (e: Throwable) {
        Log.w(TAG, "云测 OCR 也失败：${e.javaClass.simpleName}: ${e.message}")
        OcrResult.EMPTY
    }
    firstLocalCallDone = true
    bitmap.recycle()
    return cloudResult
}

/**
 * Run one recognize call against either the local or cloud
 * analyzer.  Returns null when the analyzer can't be created
 * (init failure, missing AGC config, etc.) so the caller can
 * decide whether to fall through.
 */
private suspend fun recognizeWith(
    bitmap: Bitmap,
    useCloud: Boolean,
): OcrResult {
    val analyzerOrNull = if (useCloud) getOrCreateCloudAnalyzer()
                        else getOrCreateLocalAnalyzer()
    val analyzer = analyzerOrNull ?: return OcrResult.EMPTY

    val frame = MLFrame.fromBitmap(bitmap)
    return suspendCancellableCoroutine { cont ->
        val task = analyzer.asyncAnalyseFrame(frame)
        task.addOnSuccessListener { mlText ->
            try {
                val result = mlTextToResult(mlText, bitmap.width, bitmap.height)
                // async succeeded → trust the result (may be empty =
                // "no text").  An empty local result already flows to
                // the cloud fallback in recognizeBlocking.
                cont.resume(result)
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
        task.addOnFailureListener { e ->
            Log.w(TAG, "${if (useCloud) "云测" else "本地"} asyncAnalyseFrame 失败：" +
                "${e.javaClass.simpleName}: ${e.message}")
            cont.resumeWithException(e)
        }
        cont.invokeOnCancellation {
            // HMS tasks don't expose a direct cancel API; dropping
            // the listener refs is the closest we get.
        }
    }
}

/**
 * Lazily build the on-device analyzer.  One instance per process
 * is enough — HMS keeps its model cache hot across calls and the
 * analyzer is thread-safe (per HMS docs).
 *
 * Returns null when analyzer creation fails (typically: missing
 * AGC config → MLApplication NPE).  Callers fall back to cloud in
 * that case.
 */
private fun getOrCreateLocalAnalyzer(): MLTextAnalyzer? {
    localAnalyzer?.let { return it }
    val setting = MLLocalTextSetting.Factory()
        .setLanguage(PRIMARY_LANGUAGE)
        .setOCRMode(MLLocalTextSetting.OCR_DETECT_MODE)
        .create()
    return try {
        // Documented HMS entry point.  MLTextAnalyzer.create(app,
        // local, remote, boolean) is an INTERNAL static that skips the
        // factory's model-manager + service registration; calling it
        // directly is a known cause of a SIGSEGV/SIGABRT on the first
        // asyncAnalyseFrame (the native side is handed an un-registered
        // analyzer).  getLocalTextAnalyzer runs the full init path.
        MLAnalyzerFactory.getInstance().getLocalTextAnalyzer(setting)
            .also { localAnalyzer = it }
    } catch (e: Throwable) {
        Log.w(TAG, "本地 analyzer 创建失败：${e.javaClass.simpleName}: ${e.message}")
        null
    }
}

/**
 * Lazily build the cloud analyzer.  Requires a working AGC JSON
 * (api_key + project_id); returns null when those aren't available
 * (cloud silently disabled — local OCR keeps working).
 *
 * Cloud OCR is intentionally a fallback, not a primary path —
 * offline OCR matches the original product requirement (no
 * network needed for the common case) and avoids leaking captured
 * frames to Huawei's cloud unless local explicitly failed.
 */
private fun getOrCreateCloudAnalyzer(): MLTextAnalyzer? {
    cloudAnalyzer?.let { return it }
    // HMS 3.x's MLRemoteTextSetting.Factory exposes border type but
    // not language (cloud is Chinese-only by default — fine for our
    // use case since the local analyzer handles other languages).
    // setBorderType takes a String: NGON (polygon corner points, what
    // our OcrBlock.corners expects) or ARC (curved text).  There is no
    // OCR_DETECT_BORDER constant in this SDK — NGON is the general-
    // purpose choice and matches the 4-point vertex model we normalize.
    val remoteSetting = try {
        MLRemoteTextSetting.Factory()
            .setBorderType(MLRemoteTextSetting.NGON)
            .create()
    } catch (e: Throwable) {
        Log.w(TAG, "MLRemoteTextSetting 创建失败：${e.message}")
        return null
    }
    return try {
        // Documented factory path (see getOrCreateLocalAnalyzer for why
        // we avoid the internal MLTextAnalyzer.create static).
        MLAnalyzerFactory.getInstance().getRemoteTextAnalyzer(remoteSetting)
            .also { cloudAnalyzer = it }
    } catch (e: Throwable) {
        Log.w(TAG, "云测 analyzer 创建失败：${e.javaClass.simpleName}: ${e.message}（AGC JSON 可能不完整）")
        null
    }
}

/**
 * Translate HMS's [MLText] into our [OcrResult] (text +
 * normalized 4-corner coords + confidence).  Walks blocks →
 * text-lines → words; we emit **line** granularity (a balance
 * between readability and useful position info for the LLM).
 *
 * Coordinate normalization: HMS returns pixel coordinates in the
 * source Bitmap's coordinate space.  We divide by width/height
 * so the LLM can compare across crops regardless of input size.
 *
 * **Both-layer fallback**: HMS occasionally returns a Block whose
 * `contents` (line list) is empty but whose own `getStringValue()`
 * still has the recognized text — typically when the engine
 * skipped the line-tier for a very short region.  We always try
 * the inner `contents` first, then fall back to the Block's own
 * text + 4 corners.
 */
private fun mlTextToResult(mlText: MLText, width: Int, height: Int): OcrResult {
    if (width <= 0 || height <= 0) return OcrResult.EMPTY
    val blocks = mutableListOf<OcrBlock>()
    for (block in mlText.blocks) {
        walkBlock(blocks, block, width, height)
    }
    val fullText = blocks.joinToString(separator = " ") { it.text }
    return OcrResult(blocks = blocks, fullText = fullText)
}

/**
 * Walk one [MLText.Block] and append its lines to [sink].  Inner
 * `contents` first; if empty (engine-skipped the line tier), use
 * the block's own text + corners so we never lose a recognized
 * region to an empty sub-list.
 */
private fun walkBlock(
    sink: MutableList<OcrBlock>,
    block: MLText.Block,
    width: Int,
    height: Int,
) {
    val lines: List<MLText.TextLine> = try {
        block.contents ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }
    if (lines.isEmpty()) {
        addLine(sink, block.stringValue, block.vertexes, block.confidence, width, height)
    } else {
        for (line in lines) {
            // Line-level can ALSO have an empty contents() in rare
            // cases; fall back to the line's own stringValue.
            val words: List<MLText.Word> = try {
                line.contents ?: emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
            if (words.isEmpty()) {
                addLine(sink, line.stringValue, line.vertexes, line.confidence, width, height)
            } else {
                for (word in words) {
                    addLine(sink, word.stringValue, word.vertexes, word.confidence, width, height)
                }
            }
        }
    }
}

/**
 * Convert one MLTextBase (Block / TextLine / Word) into an
 * [OcrBlock].  Skips empty text.  Vertex coords are normalized
 * to [0, 1] in the source-image frame.
 */
private fun addLine(
    sink: MutableList<OcrBlock>,
    rawText: String?,
    rawCorners: Array<Point>?,
    rawConfidence: Float?,
    width: Int,
    height: Int,
) {
    val text = rawText?.trim().orEmpty()
    if (text.isEmpty()) return
    val corners: List<OcrPoint> = rawCorners?.map { p ->
        OcrPoint(
            x = (p.x.toFloat() / width).coerceIn(0f, 1f),
            y = (p.y.toFloat() / height).coerceIn(0f, 1f),
        )
    }.orEmpty()
    // Confidence can be null on some 1.x model packs; fall back
    // to 1.0 so the LLM doesn't discount accurate reads.
    val confidence = (rawConfidence ?: 1.0f).coerceIn(0f, 1f)
    sink.add(
        OcrBlock(
            text = text,
            corners = corners,
            confidence = confidence,
        )
    )
}

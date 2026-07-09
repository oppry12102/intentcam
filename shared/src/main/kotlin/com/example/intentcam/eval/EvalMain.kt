package com.example.intentcam.eval

import com.example.intentcam.CapturedFrame
import com.example.intentcam.ImageOps
import com.example.intentcam.LlmClient
import com.example.intentcam.OcrEngine
import com.example.intentcam.ToolContext
import com.example.intentcam.ToolRegistry
import com.example.intentcam.ToolUseLoop
import com.example.intentcam.cropJpegRegion
import com.example.intentcam.encodeThumbnail
import com.example.intentcam.registerDefaultTools
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Eval entry point — calls the real [ToolUseLoop] + [LlmClient] used
 * by the Android app, so eval and prod stay in sync by construction.
 * No parallel Python orchestrator to drift.
 *
 * The things that differ from the app:
 *  1. [ImageOps.cropImpl] + [ImageOps.thumbnailImpl] are installed as
 *     ImageIO-based impls (no Android Bitmap on the JVM).
 *  2. [OcrEngine.impl] is wired to [JvmHuaweiCloudOcrEngine] when the
 *     `HUAWEICLOUD_SDK_AK/SK/PROJECT_ID` env vars are all set — the
 *     round-1 OCR hint then mirrors what HMS ML Kit produces on-device.
 *     Falls back to a no-backend stub when env vars are missing (eval
 *     runs, just without the hint).
 *
 * Run via `gradle :shared:eval` (or `gradle :shared:eval --args=...` to
 * pass through).  Default args match the previous Python eval: 20
 * fixtures at 768/q80.
 */
fun main(args: Array<String>) {
    installJvmImageOps()
    // OCR backend.  Tries Huawei Cloud first (env vars must all be set);
    // silently falls back to no-backend when the env is missing.  With
    // the cloud backend installed the round-1 pre-pass AND each
    // zoom_in crop auto-OCR get the same OCR hint the Android app
    // ships via HMS ML Kit — keeping eval and prod truly aligned, so
    // thumbnail / crop experiments aren't biased by the "blind LLM"
    // baseline.
    val ocrInstalled = JvmHuaweiCloudOcrEngine.installIfConfigured()
    System.err.println(
        if (ocrInstalled) "[OCR] Huawei Cloud backend installed (env vars OK)"
        else "[OCR] Huawei Cloud env vars missing — running without OCR hint " +
            "(set HUAWEICLOUD_SDK_AK/SK/PROJECT_ID for prod-mirror mode)"
    )

    val opts = parseArgs(args)
    val config = EvalConfig(
        groundTruth = opts.groundTruth,
        imgDir = opts.imgDir,
        limit = opts.limit,
        resize = opts.resize,
        quality = opts.quality,
        jsonOut = opts.jsonOut,
        cropOcrCap = opts.cropOcrCap,
        debugFixtures = opts.debugFixtures,
        fixtures = opts.fixtures,
    )
    val exit = EvalRunner(config).run()
    exitProcess(exit)
}

internal fun installJvmImageOps() {
    ImageOps.cropImpl = ::jvmCropJpegRegion
    ImageOps.thumbnailImpl = ::jvmEncodeThumbnail
}

private fun jvmCropJpegRegion(
    fullResJpeg: ByteArray,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    quality: Int,
): ByteArray? {
    return try {
        val src = ImageIO.read(fullResJpeg.inputStream())
        val fullW = src.width
        val fullH = src.height
        if (fullW <= 0 || fullH <= 0) return null
        val left = (x.coerceIn(0f, 1f) * fullW).toInt().coerceAtLeast(0)
        val top = (y.coerceIn(0f, 1f) * fullH).toInt().coerceAtLeast(0)
        val right = ((x + w).coerceIn(0f, 1f) * fullW).toInt().coerceAtMost(fullW)
        val bot = ((y + h).coerceIn(0f, 1f) * fullH).toInt().coerceAtMost(fullH)
        if (right <= left || bot <= top) return null
        val cropped = src.getSubimage(left, top, right - left, bot - top)
        // Cap at CROP_OUTPUT_MAX_DIM (3200, matches MAX_DIM and the
        // 2026-07-12 sweet spot).  Tested 4096 here, REVERTED —
        // attention-spread regression.  3200 stays in the
        // focused-attention band.
        val scale = ImageOps.CROP_OUTPUT_MAX_DIM.toFloat() / maxOf(cropped.width, cropped.height)
        val out = java.io.ByteArrayOutputStream()
        val finalImage = if (scale < 1f) {
            val tw = (cropped.width * scale).toInt().coerceAtLeast(1)
            val th = (cropped.height * scale).toInt().coerceAtLeast(1)
            val scaled = java.awt.image.BufferedImage(
                tw, th, java.awt.image.BufferedImage.TYPE_INT_RGB
            )
            val g = scaled.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(cropped, 0, 0, tw, th, null)
            g.dispose()
            scaled
        } else cropped
        // Use the per-call quality so this impl matches Android's
        // Bitmap.compress(JPEG, quality, ...) instead of ImageIO's
        // default (~0.75).  Without this, the per-call quality arg
        // was ignored and crops were encoded at ImageIO's default
        // q0.75 — drifting the eval's crop quality from prod.
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return null
        val writer = writers.next()
        val ios = ImageIO.createImageOutputStream(out)
        writer.output = ios
        val param = writer.defaultWriteParam
        if (param.canWriteCompressed()) {
            param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = (quality / 100f).coerceIn(0f, 1f)
        }
        writer.write(finalImage)
        writer.dispose()
        ios.close()
        out.toByteArray()
    } catch (_: Throwable) {
        null
    }
}

private fun jvmEncodeThumbnail(
    fullResJpeg: ByteArray,
    maxDim: Int,
    quality: Int,
): ByteArray? {
    return try {
        val src = ImageIO.read(fullResJpeg.inputStream())
        val scale = maxDim.toFloat() / maxOf(src.width, src.height)
        val out = java.io.ByteArrayOutputStream()
        val finalImage = if (scale < 1f) {
            val tw = (src.width * scale).toInt().coerceAtLeast(1)
            val th = (src.height * scale).toInt().coerceAtLeast(1)
            val scaled = java.awt.image.BufferedImage(
                tw, th, java.awt.image.BufferedImage.TYPE_INT_RGB
            )
            val g = scaled.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(src, 0, 0, tw, th, null)
            g.dispose()
            scaled
        } else src
        // ImageIO doesn't expose a quality param for JPEG; get a
        // writer and set compression mode manually.  Default quality
        // is ~0.75; closer to the Android intent at 80/85.
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return null
        val writer = writers.next()
        val ios = ImageIO.createImageOutputStream(out)
        writer.output = ios
        val param = writer.defaultWriteParam
        if (param.canWriteCompressed()) {
            param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = (quality / 100f).coerceIn(0f, 1f)
        }
        writer.write(finalImage)
        writer.dispose()
        ios.close()
        out.toByteArray()
    } catch (_: Throwable) {
        null
    }
}

// ── Args + Config ────────────────────────────────────────────────────

internal data class EvalOpts(
    val groundTruth: File,
    val imgDir: File,
    val limit: Int,
    val resize: Int,
    val quality: Int,
    val jsonOut: File?,
    // Phase 2a (2026-07-11): max number of followUpJpeg OCRs per
    // cycle.  0 = unlimited (prod default).  Iter runs use small
    // values to keep wall-time at ~2-min/20-fixture pace.
    val cropOcrCap: Int,
    // Phase 2b debug (2026-07-12): when non-empty, ToolUseLoop logs
    // (round/OCR/tool dispatch) are forwarded to stderr for any
    // fixture whose `id` is in this set.  Used to investigate why
    // MAX_FULL_DIM=4096 produces empty bubbles on rctw_01/03/10/18.
    val debugFixtures: Set<String> = emptySet(),
    // Phase 2b debug (2026-07-12): when non-empty, restrict the run
    // to just these fixture ids (order preserved).  Used together
    // with --debug-fixtures to run targeted diagnostic iterations
    // without rebuilding the whole 20-fixture set.
    val fixtures: Set<String> = emptySet(),
)

internal fun parseArgs(args: Array<String>): EvalOpts {
    var gt = "profiling/ground_truth_rctw.json"
    var imgDir = "img/rctw"
    var limit = 20
    // Defaults: --resize 3200 --quality 90.  Mirrors FrameAnalyzer.MAX_DIM.
    // 2026-07-12 option C: 3200 is the sweet spot.  Tested
    // 1568 (baseline 0.889) → 3200 (0.902 ⭐) → 4096 (0.885,
    // REVERTED).  3200 mirrors prod and is the prod-mirror
    // ceiling for this LLM.
    var resize = 3200
    var quality = 90
    var jsonOut: String? = null
    // Phase 2a (2026-07-11): fast-iteration knob.  Default 0 =
    // unlimited (prod).  Set e.g. 1 for ~2-min/20-fixture pace:
    // round-1 OCR + first zoom crop OCR; subsequent crops skipped.
    var cropOcrCap = 0
    var debugFixtures: Set<String> = emptySet()
    var fixtures: Set<String> = emptySet()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--ground-truth" -> { gt = args[++i] }
            "--img-dir"      -> { imgDir = args[++i] }
            "--limit"        -> { limit = args[++i].toInt() }
            "--resize"       -> { resize = args[++i].toInt() }
            "--quality"      -> { quality = args[++i].toInt() }
            "--json-out"     -> { jsonOut = args[++i] }
            "--crop-ocr-cap" -> { cropOcrCap = args[++i].toInt() }
            "--debug-fixtures" -> {
                debugFixtures = args[++i].split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            "--fixtures" -> {
                fixtures = args[++i].split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            "--help", "-h"   -> {
                println("Usage: eval [--ground-truth PATH] [--img-dir PATH] [--limit N] [--resize PX] [--quality Q] [--json-out PATH] [--crop-ocr-cap N] [--debug-fixtures id1,id2,...] [--fixtures id1,id2,...]")
                println("  defaults: --resize 768 --quality 90 (1-only mode; matches prod)")
                println("  --crop-ocr-cap N: max followUpJpeg OCRs per cycle (0 = unlimited; default 0)")
                println("  --debug-fixtures id1,id2: forward ToolUseLoop logs to stderr for these fixture ids")
                println("  --fixtures id1,id2: restrict the run to just these fixture ids")
                exitProcess(0)
            }
            else -> System.err.println("Unknown arg: ${args[i]}")
        }
        i++
    }
    return EvalOpts(
        groundTruth = File(gt),
        imgDir = File(imgDir),
        limit = limit,
        resize = resize,
        quality = quality,
        jsonOut = jsonOut?.let { File(it) },
        cropOcrCap = cropOcrCap,
        debugFixtures = debugFixtures,
        fixtures = fixtures,
    )
}

// ── Public configuration that gets passed to EvalRunner ──────────────

internal data class EvalConfig(
    val groundTruth: File,
    val imgDir: File,
    val limit: Int,
    val resize: Int,
    val quality: Int,
    val jsonOut: File?,
    // Phase 2a (2026-07-11): see EvalOpts.cropOcrCap.
    val cropOcrCap: Int,
    // Phase 2b debug (2026-07-12): see EvalOpts.debugFixtures.
    val debugFixtures: Set<String> = emptySet(),
    // Phase 2b debug (2026-07-12): see EvalOpts.fixtures.
    val fixtures: Set<String> = emptySet(),
)

// Stub for the app's LlmConfig — only the model name + URL are used.
internal val evalLlmConfig = com.example.intentcam.LlmConfig(
    baseUrl = System.getenv("ANTHROPIC_BASE_URL") ?: "https://api.minimaxi.com/anthropic",
    authToken = System.getenv("ANTHROPIC_AUTH_TOKEN") ?: error("ANTHROPIC_AUTH_TOKEN not set"),
    model = System.getenv("ANTHROPIC_MODEL") ?: "MiniMax-M3",
)
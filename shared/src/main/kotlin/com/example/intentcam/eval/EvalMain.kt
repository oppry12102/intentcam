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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Eval entry point — calls the real [ToolUseLoop] + [LlmClient] used
 * by the Android app, so eval and prod stay in sync by construction.
 * No parallel Python orchestrator to drift.
 *
 * The two things that differ from the app:
 *  1. [ImageOps.cropImpl] + [ImageOps.thumbnailImpl] are installed as
 *     ImageIO-based impls (no Android Bitmap on the JVM).
 *  2. [OcrEngine.impl] is left null — read_text returns "" so the model
 *     sees "[OCR unavailable in eval]" and falls back to its own
 *     reading of the quadrant crops.  This mirrors the on-device
 *     behaviour when the recognizer hasn't been installed yet.
 *
 * Run via `gradle :shared:eval` (or `gradle :shared:eval --args=...` to
 * pass through).  Default args match the previous Python eval: 20
 * fixtures at 768/q80.
 */
fun main(args: Array<String>) {
    installJvmImageOps()
    // OcrEngine.impl is intentionally left null: read_text returns a
    // "[OCR unavailable]" stub since the eval machine has no on-device
    // OCR.  The system prompt steers the model to NOT call read_text
    // by default anyway.

    val opts = parseArgs(args)
    val config = EvalConfig(
        groundTruth = opts.groundTruth,
        imgDir = opts.imgDir,
        limit = opts.limit,
        resize = opts.resize,
        quality = opts.quality,
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
        val scale = 768f / maxOf(cropped.width, cropped.height)
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
        ImageIO.write(finalImage, "jpeg", out)
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
)

internal fun parseArgs(args: Array<String>): EvalOpts {
    var gt = "profiling/ground_truth_rctw.json"
    var imgDir = "img/rctw"
    var limit = 20
    var resize = 768
    var quality = 80
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--ground-truth" -> { gt = args[++i] }
            "--img-dir"      -> { imgDir = args[++i] }
            "--limit"        -> { limit = args[++i].toInt() }
            "--resize"       -> { resize = args[++i].toInt() }
            "--quality"      -> { quality = args[++i].toInt() }
            "--help", "-h"   -> {
                println("Usage: eval [--ground-truth PATH] [--img-dir PATH] [--limit N] [--resize PX] [--quality Q]")
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
    )
}

// ── Public configuration that gets passed to EvalRunner ──────────────

internal data class EvalConfig(
    val groundTruth: File,
    val imgDir: File,
    val limit: Int,
    val resize: Int,
    val quality: Int,
)

// Stub for the app's LlmConfig — only the model name + URL are used.
internal val evalLlmConfig = com.example.intentcam.LlmConfig(
    baseUrl = System.getenv("ANTHROPIC_BASE_URL") ?: "https://api.minimaxi.com/anthropic",
    authToken = System.getenv("ANTHROPIC_AUTH_TOKEN") ?: error("ANTHROPIC_AUTH_TOKEN not set"),
    model = System.getenv("ANTHROPIC_MODEL") ?: "MiniMax-M3",
)

// Suppress unused warnings for imports retained for future use.
@Suppress("unused") private val keepImports = listOf(
    Base64::class, JSONArray::class, JSONObject::class, File::class
)
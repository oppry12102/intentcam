package com.example.intentcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * On-device OCR using ML Kit (Latin + Chinese).  Recognizers are loaded lazily
 * on first use so Application startup doesn't pay the model-load cost; the
 * first call amortizes the warm-up while subsequent calls run hot.
 *
 * Both recognizers run in parallel and their text is merged with spatial
 * deduplication: Latin is preferred inside the same spatial bucket (where
 * digits / English script live), Chinese wins in fresh regions with unique
 * content.  Output coordinates are pre-scaled back to original JPEG space
 * so callers don't need to know about the internal downsampling.
 */
class OcrEngine {

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Decode the JPEG with [inSampleSize] = 2 and run recognition.  The
     * decoded bitmap is half the linear resolution of the JPEG; [RecognizedLine]
     * coordinates are rescaled back to JPEG space before returning.
     */
    suspend fun recognizeFromBytes(jpeg: ByteArray): OcrResult = withContext(Dispatchers.Default) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = DOWNSAMPLE
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: return@withContext OcrResult.EMPTY

        val imageWidth = bitmap.width * DOWNSAMPLE
        val imageHeight = bitmap.height * DOWNSAMPLE
        try {
            recognizeOnBitmap(bitmap).copy(imageWidth = imageWidth, imageHeight = imageHeight)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognizeOnBitmap(bitmap: Bitmap): OcrResult = coroutineScope {
        val input = InputImage.fromBitmap(bitmap, 0)

        val latinDeferred = async(Dispatchers.Default) {
            runRecognizer(latinRecognizer, input, scaleUp = DOWNSAMPLE)
        }
        val chineseDeferred = async(Dispatchers.Default) {
            runRecognizer(chineseRecognizer, input, scaleUp = DOWNSAMPLE)
        }

        val latin = latinDeferred.await()
        val chinese = chineseDeferred.await()

        OcrResult(
            lines = mergeLines(latin, chinese),
            latinText = latin.fullText,
            chineseText = chinese.fullText,
            imageWidth = 0,   // patched by caller after scale-up
            imageHeight = 0
        )
    }

    private suspend fun runRecognizer(
        recognizer: TextRecognizer,
        input: InputImage,
        scaleUp: Int
    ): RecognizerOutput = asOutput(recognizer.process(input).awaitCancellable(), scaleUp)

    private fun asOutput(text: Text, scaleUp: Int): RecognizerOutput {
        val lines = ArrayList<RecognizedLine>(text.textBlocks.size)
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val s = line.text.trim()
                if (s.isEmpty()) continue
                lines.add(
                    RecognizedLine(
                        text = s,
                        left = box.left * scaleUp,
                        top = box.top * scaleUp,
                        right = box.right * scaleUp,
                        bottom = box.bottom * scaleUp,
                        confidence = line.confidence ?: 0f
                    )
                )
            }
        }
        return RecognizerOutput(lines = lines, fullText = text.text.trim())
    }

    /**
     * Combine line lists from both recognizers.  We deduplicate by spatial
     * overlap using the IoU (intersection over union) of bounding boxes: when
     * the same screen region triggers both recognizers, the higher-confidence
     * line wins.  Lines from non-overlapping regions are kept regardless of
     * which recognizer produced them.
     *
     * The previous bucket-key dedup was too coarse — it let two boxes
     * starting at the same top-left collide even if their bodies extended in
     * completely different directions, and it always preferred the Latin
     * recognizer (which misreads most Chinese as garbled Latin script).
     */
    private fun mergeLines(
        latin: RecognizerOutput,
        chinese: RecognizerOutput
    ): List<RecognizedLine> {
        val latinLines = latin.lines.filter { it.text.isNotBlank() }
        val chineseLines = chinese.lines.filter { it.text.isNotBlank() }
        if (latinLines.isEmpty()) return chineseLines.sortedByRow()
        if (chineseLines.isEmpty()) return latinLines.sortedByRow()

        val out = ArrayList<RecognizedLine>(latinLines.size + chineseLines.size)

        // For each Chinese line, find an overlapping Latin line.  If one
        // exists, keep the higher-confidence line; otherwise keep the
        // Chinese line itself.
        for (cn in chineseLines) {
            val overlappingLatin = latinLines.firstOrNull {
                bboxIou(cn, it) >= OVERLAP_THRESHOLD
            }
            if (overlappingLatin != null) {
                out.add(if (cn.confidence >= overlappingLatin.confidence) cn else overlappingLatin)
            } else {
                out.add(cn)
            }
        }

        // Add Latin lines that didn't overlap with any Chinese line.
        for (la in latinLines) {
            val overlaps = chineseLines.any { bboxIou(la, it) >= OVERLAP_THRESHOLD }
            if (!overlaps) out.add(la)
        }

        return out.distinctBy { it.text }.sortedByRow()
    }

    /** Intersection-over-union of two bounding boxes, in pixels. */
    private fun bboxIou(a: RecognizedLine, b: RecognizedLine): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val iw = (right - left).coerceAtLeast(0)
        val ih = (bottom - top).coerceAtLeast(0)
        if (iw == 0 || ih == 0) return 0f
        val intersection = iw * ih
        val areaA = (a.right - a.left).coerceAtLeast(0) * (a.bottom - a.top).coerceAtLeast(0)
        val areaB = (b.right - b.left).coerceAtLeast(0) * (b.bottom - b.top).coerceAtLeast(0)
        val union = areaA + areaB - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun List<RecognizedLine>.sortedByRow(): List<RecognizedLine> =
        sortedBy { (it.top + it.bottom) / 2 }

    private companion object {
        const val DOWNSAMPLE = 2

        // Two lines are considered the "same text region" if their IoU is at
        // least this fraction.  0.30 catches genuine label lines that both
        // recognizers pick up; lower values start to merge distinct labels
        // that happen to share an edge.
        const val OVERLAP_THRESHOLD = 0.30f
    }
}

private data class RecognizerOutput(
    val lines: List<RecognizedLine>,
    val fullText: String
)

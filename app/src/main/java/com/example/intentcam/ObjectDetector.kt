package com.example.intentcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector as MlObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device object detection using ML Kit.  Single-image mode with
 * classification enabled so the model gets a hint label list
 * ("blood pressure monitor", "bottle", ...) as a sanity check.
 *
 * The detector is initialized lazily on first use — loading the bundled
 * ~5 MB model is the bulk of the cold-start cost.
 */
class ObjectDetector {

    private val detector: MlObjectDetector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableClassification()
                .build()
        )
    }

    suspend fun recognizeFromBytes(jpeg: ByteArray): ObjectResult = withContext(Dispatchers.Default) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = DOWNSAMPLE
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: return@withContext ObjectResult.EMPTY

        try {
            recognizeOnBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognizeOnBitmap(bitmap: Bitmap): ObjectResult {
        val input = InputImage.fromBitmap(bitmap, 0)
        return asResult(detector.process(input).awaitCancellable())
    }

    private fun asResult(detected: List<DetectedObject>): ObjectResult {
        val items = ArrayList<ObjectItem>(detected.size)
        for (obj in detected) {
            val box = obj.boundingBox ?: continue
            // Pick the most confident classification label; fall back to a
            // neutral string if classification is empty (rare for the model
            // we configured, but defensive).
            val top = obj.labels.maxByOrNull { it.confidence }
            items.add(
                ObjectItem(
                    label = top?.text ?: "object",
                    confidence = top?.confidence ?: 0f,
                    left = box.left * DOWNSAMPLE,
                    top = box.top * DOWNSAMPLE,
                    right = box.right * DOWNSAMPLE,
                    bottom = box.bottom * DOWNSAMPLE
                )
            )
        }
        // Sort top-to-bottom, then left-to-right so the prompt sees roughly
        // the same order the user reads the scene.
        return ObjectResult(
            items.sortedWith(compareBy({ it.top }, { it.left }))
        )
    }

    private companion object {
        // Same downsample factor as [OcrEngine] so the resulting ObjectItem
        // coordinates are in the original JPEG space, matching what the OCR
        // engine returns.  The two engines can be overlaid directly on the
        // same coordinate system.
        const val DOWNSAMPLE = 2
    }
}

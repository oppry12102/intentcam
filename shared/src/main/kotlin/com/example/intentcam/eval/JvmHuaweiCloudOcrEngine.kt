package com.example.intentcam.eval

import com.example.intentcam.OcrBlock
import com.example.intentcam.OcrEngine
import com.example.intentcam.OcrPoint
import com.example.intentcam.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * JVM-side `OcrEngine.Impl` that calls Huawei Cloud OCR (via the
 * `profiling/ocr_huaweicloud_runner.py` helper) instead of the
 * on-device HMS ML Kit.  This makes the Kotlin eval a true mirror of
 * the Android prod pipeline (both paths now have a real OCR hint
 * feeding round-1), so thumbnail / crop experiments aren't run
 * against the "blind LLM" baseline that previously biased results
 * toward smaller images (the LLM was reading dense text off a single
 * low-res overview; without OCR hint, larger images just spread
 * attention thinner).
 *
 * **Why a Python subprocess?**  Huawei IAM `password` auth requires
 * SDK-HMAC-SHA256 request signing, and the bearer token endpoint
 * requires a domain-id lookup that the SDK does via a signed GET
 * `/v3/auth/domains` round-trip.  Re-implementing that signing in
 * Kotlin is ~200 lines of careful code we'd never use in prod (prod
 * uses HMS ML Kit).  The runner script does the IAM round-trip via
 * the official `huaweicloudsdkocr` SDK and prints OCR blocks as JSON;
 * Kotlin just base64-encodes the JPEG bytes, ships them over stdin,
 * and parses the JSON result.
 *
 * Per-call cost: ~2-3s for cold SDK init + HTTPS roundtrip.  The eval
 * runs one OCR per fixture (round-1 pre-pass) — for 20 fixtures that's
 * ~40-60s extra wall time on top of the LLM round-trips.  Acceptable
 * for an iteration-speed eval; production uses HMS ML Kit so this
 * overhead never lands on users.
 *
 * Env vars (must all be set for OCR to install; propagated to the
 * subprocess via inherited environment):
 *   HUAWEICLOUD_SDK_AK         — access key (HPUA... = permanent AK)
 *   HUAWEICLOUD_SDK_SK         — secret access key
 *   HUAWEICLOUD_SDK_PROJECT_ID — project id
 *
 * Failure modes (each surfaces a clear stderr log + returns [OcrResult.EMPTY]):
 *   - any env var missing  → no backend installed (matches pre-OCR baseline)
 *   - python3 absent       → ditto
 *   - runner helper fails  → stderr surfaces the underlying error
 *
 * Path: the helper is `profiling/ocr_huaweicloud_runner.py`.
 * Working directory when running via `:shared:eval` is the project root
 * (`shared/build.gradle.kts` sets `workingDir = rootDir` for the eval
 * task), so a relative path resolves correctly.
 */
object JvmHuaweiCloudOcrEngine {

    private const val OCR_RUNNER = "profiling/ocr_huaweicloud_runner.py"

    /**
     * Install as the active [OcrEngine.Impl].  No-op when the env vars
     * aren't all set OR when the runner script is missing — eval keeps
     * running with `OcrResult.EMPTY` and the model relies entirely on
     * vision (matches the pre-OCR-hint baseline).
     */
    fun installIfConfigured(): Boolean {
        val ak = System.getenv("HUAWEICLOUD_SDK_AK")
        val sk = System.getenv("HUAWEICLOUD_SDK_SK")
        val pid = System.getenv("HUAWEICLOUD_SDK_PROJECT_ID")
        if (ak.isNullOrBlank() || sk.isNullOrBlank() || pid.isNullOrBlank()) return false
        if (!File(OCR_RUNNER).exists()) {
            System.err.println("[OCR] runner script not found at $OCR_RUNNER " +
                "(expected relative to project root)")
            return false
        }

        OcrEngine.impl = OcrEngine.Impl { jpegBytes -> recognize(jpegBytes) }
        return true
    }

    private suspend fun recognize(jpegBytes: ByteArray): OcrResult = withContext(Dispatchers.IO) {
        try {
            val b64 = Base64.getEncoder().encodeToString(jpegBytes)
            val proc = ProcessBuilder("python3", OCR_RUNNER)
                .redirectErrorStream(false)
                .start()
            // Send base64 JPEG on stdin; close stdin so the runner knows
            // it's done.
            proc.outputStream.bufferedWriter().use { it.write(b64) }
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream?.bufferedReader()?.readText().orEmpty()
            val exit = proc.waitFor()
            if (exit != 0) {
                System.err.println("[OCR] runner exited $exit: ${stderr.take(200)}")
                return@withContext OcrResult.EMPTY
            }
            parseRunnerOutput(stdout)
        } catch (e: Throwable) {
            System.err.println("[OCR] recognize failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            OcrResult.EMPTY
        }
    }

    /**
     * Parse the runner's stdout JSON into our [OcrResult].  Defensive
     * against missing fields so a partial response (e.g. blocked by
     * rate limiting) doesn't kill the eval.
     */
    private fun parseRunnerOutput(stdout: String): OcrResult {
        val json = runCatching { JSONObject(stdout) }.getOrNull() ?: return OcrResult.EMPTY
        val arr = json.optJSONArray("blocks") ?: JSONArray()
        val parsed = mutableListOf<OcrBlock>()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val text = b.optString("text", "").trim()
            if (text.isEmpty()) continue
            val conf = b.optDouble("confidence", 0.0).toFloat()
            val cornersArr = b.optJSONArray("corners") ?: JSONArray()
            val corners = mutableListOf<OcrPoint>()
            for (j in 0 until cornersArr.length()) {
                val pair = cornersArr.optJSONArray(j) ?: continue
                if (pair.length() < 2) continue
                corners.add(
                    OcrPoint(
                        x = pair.optDouble(0, 0.0).toFloat().coerceIn(0f, 1f),
                        y = pair.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f),
                    )
                )
            }
            // Pad to 4 corners if the runner returned < 4 (rare;
            // Huawei generally returns 4).  Pad with the last corner
            // so the bbox stays rectangular-ish.
            while (corners.size < 4) {
                corners.add(corners.lastOrNull() ?: OcrPoint(0f, 0f))
            }
            parsed.add(OcrBlock(text = text, corners = corners.take(4), confidence = conf))
        }
        val fullText = json.optString("full_text").ifBlank {
            parsed.joinToString(" ") { it.text }
        }
        return OcrResult(blocks = parsed, fullText = fullText)
    }
}
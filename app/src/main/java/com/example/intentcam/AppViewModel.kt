package com.example.intentcam

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val location = LocationHelper(app)
    private val client = LlmClient(settings.load())
    private val ocr = OcrEngine()
    private val objDetector = ObjectDetector()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val config: LlmConfig get() = client.config

    @Volatile private var lastFrame: FrameJpegs? = null
    private val analyzing = AtomicBoolean(false)

    private var currentAnalyzeJob: Job? = null

    // Last error string from a failed analyze cycle, captured for adb logcat
    // debugging.  Not surfaced to UI (per product contract: parse failures
    // must not show as bubbles or toasts).  Read via:
    //   adb logcat | grep IntentCam | grep "intent err="
    @Volatile private var lastError: String? = null

    // Incremented on every cycle boundary (new capture, scene change,
    // restart).  Running coroutines compare against this to know their
    // results are still relevant.  AtomicLong gives a true atomic ++ across
    // the analyzer thread (FrameAnalyzer callbacks) and the UI thread
    // (restart/saveConfig).
    private val analyzeCycleId = AtomicLong(0L)

    @Volatile private var cachedLocation: String? = null
    @Volatile private var cachedLocationAtMs: Long = 0L

    // Captured-frame timestamp from the first stable observation.  After
    // [CAPTURE_AFTER_MS] of accumulated stability, the next stable frame
    // triggers the capture (recognition runs in the background).
    // -1L = no stability observation yet this SCANNING phase.
    @Volatile private var stableSinceMs: Long = -1L

    fun isBusy(): Boolean {
        if (analyzing.get()) return true
        if (_state.value.phase != Phase.SCANNING) return true
        // When the user is looking at a bubble's detail view, the camera
        // pipeline must stay quiet so the displayed image doesn't go stale.
        if (_state.value.selectedBubble != null) return true
        return false
    }

    fun onPermissionsGranted() {
        if (_state.value.phase == Phase.NEED_PERMISSION) {
            _state.value = _state.value.copy(phase = Phase.SCANNING)
        }
    }

    /**
     * Gatekeeper for intent recognition.  The camera analyzer emits one
     * [FrameJpegs] each time the scene has been stable for a few frames; we
     * keep the live preview on screen regardless and accumulate that
     * observation here.  When 1 second of stable frames have accumulated, the
     * *next* stable emit kicks off the background recognition cycle.  The UI
     * keeps showing the live camera the whole time — the captured JPEG is
     * only used to feed OCR + object detection + the multi-round LLM call.
     */
    fun onStableFrame(jpegs: FrameJpegs) {
        if (_state.value.phase != Phase.SCANNING) return
        // Don't accept new captures while the user is reading a bubble's
        // detail view — the displayed image would otherwise be replaced by
        // a stale bubble with new image.  The detail view forces rearm
        // back to SCANNING.
        if (_state.value.selectedBubble != null) return
        // `lastFrame` doubles as "have we captured this cycle" — once a
        // capture is in flight, ignore subsequent emits until a fresh
        // rearm (which happens when the new bubble lands).
        if (lastFrame != null) return

        val now = SystemClock.elapsedRealtime()
        if (stableSinceMs == -1L) {
            stableSinceMs = now
            return
        }
        if (now - stableSinceMs < CAPTURE_AFTER_MS) {
            // still accumulating; this emit proves the camera is still steady
            return
        }

        // Past 1 s of stable observations — capture and dispatch the
        // recognition cycle.  Live UI keeps showing the camera; the JPEG
        // bytes are only for the model + the bubble's eventual thumbnail.
        stableSinceMs = -1L
        lastFrame = jpegs

        // Clear only the in-flight analysis status.  Bubbles from prior
        // captures are kept on screen until the new round lands, so the
        // user has a continuous bubble history.
        _state.value = _state.value.copy(
            analyzing = true,
            partialScene = null,
        )

        if (!analyzing.compareAndSet(false, true)) return
        val myCycle = analyzeCycleId.incrementAndGet()
        currentAnalyzeJob = viewModelScope.launch {
            runAnalysisCycle(myCycle, jpegs.analyze, jpegs.answer)
        }
    }

    fun onSceneChange() {
        if (_state.value.phase != Phase.SCANNING) return

        // If we've already captured, camera moves are not actionable — user
        // is committed to the captured image until they tap 重新扫描.
        if (lastFrame != null) return

        // Pre-capture: blow away the partial stability count so we have to
        // accumulate a fresh 1 s before we capture.
        analyzeCycleId.incrementAndGet()
        currentAnalyzeJob?.cancel()
        currentAnalyzeJob = null
        analyzing.set(false)
        stableSinceMs = -1L

        _state.value = _state.value.copy(
            lastSceneChangeMs = SystemClock.elapsedRealtime(),
            analyzing = false,
            partialScene = null,
            error = null,
            ocrOverlay = emptyList(),
        )
    }

    /**
     * Run up to 3 rounds (BROAD → VERIFY → DECIDE), stopping early when the
     * intents are decisive enough.  Each round shares the same image to keep
     * cost bounded; only the prompt changes.  A scene-change cancels via
     * [analyzeCycleId] drifting away from `myCycle`.
     */
    private suspend fun runAnalysisCycle(
        myCycle: Long,
        analyzeJpeg: ByteArray,
        answerJpeg: ByteArray
    ) {
        _state.value = _state.value.copy(analyzing = true, partialScene = null, error = null)
        try {
            val loc = resolveLocation()
            val history = ArrayList<AnalysisResult>(3)
            var current: AnalysisResult? = null

            // Kick off the slow on-device work in parallel:
            //   - Latin + Chinese OCR (~200–500 ms first time)
            //   - Object Detection with classification (~150–250 ms)
            //   - center-crop for DECIDE (~50–80 ms)
            // OCR + Object-Det outputs feed every round's prompt as hints.
            // Each helper tolerates non-cancellation throwables but
            // re-throws [CancellationException] so structured concurrency
            // remains correct — `kotlin.runCatching` would absorb the
            // cancellation and the parent would continue on stale results.
            val ocrDeferred = viewModelScope.async(Dispatchers.Default) {
                orDefaultOfCancellation { ocr.recognizeFromBytes(answerJpeg) }
                    ?: OcrResult.EMPTY
            }
            val objDeferred = viewModelScope.async(Dispatchers.Default) {
                orDefaultOfCancellation { objDetector.recognizeFromBytes(answerJpeg) }
                    ?: ObjectResult.EMPTY
            }
            val zoomJpeg: ByteArray? = withContext(Dispatchers.Default) {
                runOrNullOnCancellation { cropCenter(answerJpeg) }
            }

            val ocrResult = ocrDeferred.await()
            val objects = objDeferred.await()
            if (myCycle != analyzeCycleId.get()) return

            // Surface OCR boxes to the overlay composable.  Frozen image
            // dimensions were already set when capture fired (we don't wait
            // for OCR to project anything).
            if (_state.value.phase == Phase.SCANNING) {
                _state.value = _state.value.copy(
                    ocrOverlay = ocrResult.lines,
                    ocrEpoch = _state.value.ocrEpoch + 1
                )
            }

            val rounds = listOf(
                LlmClient.AnalysisLevel.BROAD,
                LlmClient.AnalysisLevel.VERIFY,
                LlmClient.AnalysisLevel.DECIDE
            )
            for (level in rounds) {
                if (myCycle != analyzeCycleId.get()) return
                val result = client.analyzeRefined(
                    level = level,
                    jpeg = analyzeJpeg,
                    zoomJpeg = zoomJpeg,
                    location = loc,
                    history = history.toList(),
                    ocr = ocrResult,
                    objects = objects,
                    onDelta = { partial ->
                        if (myCycle != analyzeCycleId.get()) return@analyzeRefined
                        val scene = extractPartialScene(partial)
                        if (scene.isNotEmpty() && _state.value.phase == Phase.SCANNING) {
                            _state.value = _state.value.copy(partialScene = scene)
                        }
                    }
                )
                if (myCycle != analyzeCycleId.get()) return
                history += result
                current = result

                when (decideToStop(result.intents, level)) {
                    CycleVerdict.STOP -> break
                    CycleVerdict.CONTINUE -> { /* loop */ }
                    CycleVerdict.FORCED -> break
                }
            }

            if (myCycle != analyzeCycleId.get()) return
            val final = current ?: return

            // Convert the model output into up to BUBBLE_MAX bubbles,
            // appending to the existing queue.  New bubbles are pushed to
            // the end; if the queue would exceed BUBBLE_MAX, the oldest
            // (head) is dropped.  The "新出顶掉老的" behavior is what the
            // product spec calls for.
            //
            // When the model returns no intents at all (or only a single
            // low-quality fallback), we synthesize one "无意图" bubble
            // carrying the model's `observation` + `scene` so the user
            // still sees a meaningful summary of what was on screen.
            val newBubbles: List<Bubble> = if (final.intents.isEmpty()) {
                listOf(makeNoIntentBubble(final, answerJpeg))
            } else {
                final.intents.take(UiState.BUBBLE_MAX).map { it.toBubble(answerJpeg) }
            }
            val merged = (_state.value.bubbles + newBubbles)
                .takeLast(UiState.BUBBLE_MAX)

            _state.value = _state.value.copy(
                scene = final.scene,
                bubbles = merged,
                partialScene = null,
                location = loc,
                roundCount = _state.value.roundCount + 1,
                analyzing = false,
            )
            // Camera + FrameAnalyzer continue running; the next stable
            // frame will fire runAnalysisCycle again.  No need to wait
            // for user input — the new capture replaces `lastFrame` only
            // if the user has not selected a bubble (see isBusy()).
            if (myCycle == analyzeCycleId.get()) {
                lastFrame = null
                stableSinceMs = -1L
            }
        } catch (e: CancellationException) {
            if (myCycle == analyzeCycleId.get() && _state.value.phase == Phase.SCANNING) {
                _state.value = _state.value.copy(analyzing = false, partialScene = null)
            }
        } catch (e: Exception) {
            // Model returned unparseable output, timed out, or otherwise
            // failed.  Per the user contract, parse failures do NOT surface
            // as bubbles or error toasts — silently re-arm the camera so
            // the next stable frame kicks off a fresh cycle.  The raw error
            // is captured in [lastError] for adb logcat inspection.
            if (myCycle == analyzeCycleId.get()) {
                lastError = e.message
                rearmScanning()
            }
        } finally {
            analyzing.set(false)
            if (myCycle == analyzeCycleId.get()) currentAnalyzeJob = null
        }
    }

    /** Build a [Bubble] from a model-returned [IntentItem] plus the captured JPEG. */
    private fun IntentItem.toBubble(imageBytes: ByteArray): Bubble = Bubble(
        id = "bubble-${System.currentTimeMillis()}-${title.hashCode()}",
        type = type,
        title = title,
        detail = detail,
        confidence = confidence,
        imageBytes = imageBytes,
        createdAtMs = System.currentTimeMillis(),
    )

    /**
     * Synthesize a single "无意图" [Bubble] when the model produced no
     * intents at all.  Title is "无意图" so the user understands the
     * classifier didn't pick an action; the detail is the model's
     * observation + scene as a free-form description of what's on
     * screen.  Confidence is reported as 0 since this is a
     * classification failure, not a real signal.
     */
    private fun makeNoIntentBubble(result: AnalysisResult, imageBytes: ByteArray): Bubble {
        val desc = buildList {
            if (result.observation.isNotBlank()) add(result.observation)
            if (result.scene.isNotBlank() && result.scene !in this) add(result.scene)
        }.joinToString(" · ")
        return Bubble(
            id = "bubble-no-intent-${System.currentTimeMillis()}",
            type = "info",
            title = "无意图",
            detail = desc.ifBlank { "（模型未返回任何描述）" },
            confidence = 0f,
            imageBytes = imageBytes,
            createdAtMs = System.currentTimeMillis(),
        )
    }

    private enum class CycleVerdict { STOP, CONTINUE, FORCED }

    /**
     * Decide whether to terminate the cycle after [level].  Threshold design
     * (tuned against typical vision-model confidence):
     *
     * - BROAD: a single clear winner (top≥0.7 AND gap≥0.25) is enough to
     *   commit.
     * - VERIFY: after self-review, accept if top≥0.6 AND gap≥0.18.
     * - DECIDE: always stop; the round itself is forced-final.
     */
    private fun decideToStop(intents: List<IntentItem>, level: LlmClient.AnalysisLevel): CycleVerdict {
        val top = intents.firstOrNull() ?: return CycleVerdict.CONTINUE
        val second = intents.getOrNull(1)
        val gap = top.confidence - (second?.confidence ?: 0f)
        return when (level) {
            LlmClient.AnalysisLevel.BROAD -> when {
                top.confidence >= 0.70f && gap >= 0.25f -> CycleVerdict.STOP
                top.confidence >= 0.55f && intents.size == 1 -> CycleVerdict.STOP
                else -> CycleVerdict.CONTINUE
            }
            LlmClient.AnalysisLevel.VERIFY -> when {
                top.confidence >= 0.60f && gap >= 0.18f -> CycleVerdict.STOP
                else -> CycleVerdict.CONTINUE
            }
            LlmClient.AnalysisLevel.DECIDE -> CycleVerdict.FORCED
        }
    }

    /**
     * User tapped a bubble.  Show its detail (full image + title + detail).
     * The camera pipeline stays quiet until [clearBubbleSelection] runs.
     */
    fun selectBubble(bubble: Bubble) {
        if (_state.value.selectedBubble?.id == bubble.id) return
        _state.value = _state.value.copy(
            phase = Phase.SHOWING_DETAIL,
            selectedBubble = bubble,
        )
    }

    /** User dismissed the detail view; rearm the capture pipeline. */
    fun clearBubbleSelection() {
        if (_state.value.selectedBubble == null) return
        _state.value = _state.value.copy(
            phase = Phase.SCANNING,
            selectedBubble = null,
        )
        // Bump the cycle id so any in-flight runAnalysisCycle that started
        // before the user opened the detail is invalidated.  Then rearm the
        // stability counter so a fresh capture cycle can begin immediately.
        analyzeCycleId.incrementAndGet()
        currentAnalyzeJob = null
        analyzing.set(false)
        lastFrame = null
        stableSinceMs = -1L
    }

    /** User explicitly tapped "重新扫描" — full reset including bubble history. */
    fun restartScanning() {
        currentAnalyzeJob?.cancel()
        currentAnalyzeJob = null
        analyzeCycleId.incrementAndGet()  // invalidate any in-flight coroutine
        lastFrame = null  // allow the next capture to fire
        analyzing.set(false)
        stableSinceMs = -1L  // ready to start accumulating stability again
        _state.value = _state.value.copy(
            phase = Phase.SCANNING,
            bubbles = emptyList(),
            selectedBubble = null,
            scene = "",
            partialScene = null,
            lastSceneChangeMs = 0L,
            error = null,
            ocrOverlay = emptyList(),
        )
    }

    /**
     * Quiet recovery from a failed analyze cycle (parse error, timeout,
     * etc).  Same as [clearBubbleSelection] but doesn't touch the selected
     * bubble field.  Used when the model returns unparseable output and
     * we want to immediately start the next capture without UI noise.
     */
    private fun rearmScanning() {
        currentAnalyzeJob?.cancel()
        currentAnalyzeJob = null
        analyzeCycleId.incrementAndGet()
        lastFrame = null
        analyzing.set(false)
        stableSinceMs = -1L
        _state.value = _state.value.copy(
            bubbles = _state.value.bubbles,
            selectedBubble = null,
            scene = "",
            partialScene = null,
            error = null,
            ocrOverlay = emptyList(),
        )
        // phase stays as-is; the user is still in SCANNING during the
        // failed analyze cycle.
    }

    fun openSettings() {
        _state.value = _state.value.copy(phase = Phase.SETTINGS)
    }

    fun closeSettings() {
        restartScanning()
    }

    fun saveConfig(newConfig: LlmConfig) {
        val cfg = newConfig.copy(
            baseUrl = newConfig.baseUrl.ifBlank { LlmConfig.DEFAULT_BASE_URL },
            authToken = newConfig.authToken.ifBlank { LlmConfig.DEFAULT_TOKEN },
            model = newConfig.model.ifBlank { LlmConfig.DEFAULT_MODEL }
        )
        settings.save(cfg)
        client.config = cfg
        closeSettings()
    }

    fun resetConfigToDefault() {
        settings.reset()
        client.config = settings.load()
    }

    private suspend fun resolveLocation(): String? {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedLocation
        if (cached != null && now - cachedLocationAtMs < 15_000L) return cached
        val fresh = location.currentLocationText()
        if (fresh != null) {
            cachedLocation = fresh
            cachedLocationAtMs = now
        }
        return fresh ?: cached
    }

    /**
     * Best-effort extraction of the "scene" string from a partially-built JSON
     * object during a streaming response.  Handles the JSON-string escapes
     * that LLM responses routinely contain (`\n`, `\t`, `\"`, `\\`, `\uXXXX`).
     * For `\uXXXX` we decode to the actual character (vision APIs often emit
     * non-ASCII as escape sequences) — otherwise the partial-scene UI would
     * show literal `中` instead of "中".
     */
    private fun extractPartialScene(json: String): String {
        val keyIdx = json.indexOf("\"scene\"")
        if (keyIdx < 0) return ""
        val colon = json.indexOf(':', keyIdx + 7)
        if (colon < 0) return ""
        val q1 = json.indexOf('"', colon + 1)
        if (q1 < 0) return ""
        val sb = StringBuilder()
        var i = q1 + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '"') break
            if (c == '\\' && i + 1 < json.length) {
                val nx = json[i + 1]
                when (nx) {
                    'n'  -> { sb.append('\n');     i += 2 }
                    't'  -> { sb.append('\t');     i += 2 }
                    'r'  -> { sb.append('\r');     i += 2 }
                    'b'  -> { sb.append('\b');     i += 2 }
                    'f'  -> { sb.append(' ');   i += 2 }
                    '"'  -> { sb.append('"');      i += 2 }
                    '\\' -> { sb.append('\\');     i += 2 }
                    '/'  -> { sb.append('/');      i += 2 }
                    'u'  -> {
                        // Decode \uXXXX to the actual character so the UI
                        // shows "中" instead of literal `中`.  [appendCodePoint]
                        // handles supplementary plane characters via surrogate pairs.
                        val decoded = decodeUnicodeEscape(json, i)
                        if (decoded > 0) {
                            sb.appendCodePoint(decoded)
                            i += 6
                        } else {
                            sb.append(nx); i += 2
                        }
                    }
                    else -> { sb.append(nx); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** Returns the code point for a `\uXXXX` escape starting at [at] (the `\`). */
    private fun decodeUnicodeEscape(json: String, at: Int): Int {
        if (at + 5 >= json.length) return 0
        val hex = json.substring(at + 2, at + 6)
        return hex.toIntOrNull(16) ?: 0
    }

    /**
     * Center-crop the larger answer JPEG for the DECIDE round.  Done on
     * `Dispatchers.Default` once per cycle so the three rounds all share
     * the same cropped bytes.  Re-encodes at q80 to recover detail that the
     * upstream q75 encode threw away.
     */
    private fun cropCenter(jpeg: ByteArray): ByteArray {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: return jpeg

        val cropFrac = 0.65
        val cropW = (full.width * cropFrac).toInt().coerceAtLeast(1)
        val cropH = (full.height * cropFrac).toInt().coerceAtLeast(1)
        val offX = (full.width - cropW) / 2
        val offY = (full.height - cropH) / 2

        val cropped = Bitmap.createBitmap(full, offX, offY, cropW, cropH)
        if (cropped !== full) full.recycle()

        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 80, out)
        cropped.recycle()
        return out.toByteArray()
    }

    /**
     * Run [block] and return its result on success, swallowing normal
     * exceptions and returning null on non-cancellation throwables.  Re-throws
     * [CancellationException] so structured-concurrency callers actually cancel
     * — `kotlin.runCatching` would absorb the cancellation and the parent
     * coroutine would keep running on stale results.
     */
    private suspend inline fun <T> orDefaultOfCancellation(block: () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** Same as [orDefaultOfCancellation] but specialised for nullable outputs. */
    private suspend inline fun <T> runOrNullOnCancellation(block: () -> T?): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        // How long the scene must stay still before we capture a still.
        // Tuned for typical "hold camera on subject for ~1 s" interaction.
        const val CAPTURE_AFTER_MS: Long = 1000L
    }
}

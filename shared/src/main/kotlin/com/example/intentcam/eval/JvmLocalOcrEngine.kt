package com.example.intentcam.eval

import com.example.intentcam.OcrBlock
import com.example.intentcam.OcrEngine
import com.example.intentcam.OcrPoint
import com.example.intentcam.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.PrintWriter
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * JVM-side `OcrEngine.Impl` that calls a **local** OCR engine — PP-OCRv4
 * (PaddleOCR 2.7.3) — via the long-lived `profiling/pp_ocrv4_runner.py`
 * subprocess.  This replaces [JvmHuaweiCloudOcrEngine] as the **primary**
 * eval backend (Huawei Cloud becomes the fallback when local install /
 * init fails — wired in [EvalMain]).
 *
 * **Why a long-lived subprocess?**
 *
 * PaddleOCR's first init downloads + loads model weights (5-30 s on
 * cold cache, ~12 MB mobile / ~450 MB server).  Spawning a fresh Python
 * process per fixture would re-pay that 60+ times for a 60-fixture
 * suite.  The runner script keeps a single `PaddleMobileEngine`
 * resident in process-global cache (`pp_ocrv4_mobile_engine/engine.py:246`)
 * and serves requests via line-delimited JSON-RPC over stdin/stdout.
 * Per-call latency is ~2.4 s (mobile) / ~27 s (server) on CPU — the
 * PaddleOCR recognition pass, not Python startup.
 *
 * **Protocol** (one JSON object per line, both directions):
 *
 *   request:  `{"id": N, "method": "recognize", "params": {"image_b64": "..."}}`
 *   success:  `{"id": N, "result": {"blocks": [...], "full_text": "..."}}`
 *   error:    `{"id": N, "error": {"code": ..., "message": "..."}}`
 *   control:  `{"id": N, "method": "shutdown"}` → clean exit
 *
 * Output `blocks` schema mirrors [JvmHuaweiCloudOcrEngine]'s
 * `parseRunnerOutput` so the same `OcrBlock` shape feeds the eval
 * scorer — `corners: [[x, y], ...]` 4 corners TL→TR→BR→BL normalized
 * to [0, 1] (preprocessed-image coords, equivalent to source-image
 * coords because `preprocess_image` preserves aspect ratio).
 *
 * **Env vars** (all optional, defaults shown):
 *   - `OCR_PYTHON`            (default `python3`) — Python interpreter
 *   - `OCR_PYTHONPATH`        (default `/home/oppry/work`) — appended to
 *                              PYTHONPATH so `pp_ocrv4_mobile_engine` is
 *                              importable.  Set to empty string to skip
 *                              augmentation.
 *   - `LOCAL_OCR_KIND`        (default `mobile`) — `mobile` | `server`
 *   - `LOCAL_OCR_MAX_LONG`    (default `4096`)
 *   - `LOCAL_OCR_JPG_QUALITY` (default `90`; engine validates 50..100)
 *   - `LOCAL_OCR_USE_GPU`     (default `0`)
 *
 * **Failure modes** (each surfaces a stderr log + returns [OcrResult.EMPTY]
 * so the eval keeps running):
 *   - `OCR_PYTHON` missing OR runner script absent → not installed (cascade to Huawei)
 *   - subprocess start failure → not installed
 *   - startup ping times out (30 s) → not installed
 *   - per-call timeout (30 s mobile / 120 s server) → restart + retry once
 *   - subprocess crash mid-call → restart + retry once; on second failure return [OcrResult.EMPTY]
 *
 * **Path**: the runner is `profiling/pp_ocrv4_runner.py`.  Working
 * directory when running via `:shared:eval` is the project root
 * (`shared/build.gradle.kts` sets `workingDir = rootDir`), so a
 * relative path resolves correctly.
 */
object JvmLocalOcrEngine {

    private const val OCR_RUNNER = "profiling/pp_ocrv4_runner.py"
    private const val STARTUP_TIMEOUT_MS = 30_000L
    private const val MOBILE_CALL_TIMEOUT_MS = 30_000L
    private const val SERVER_CALL_TIMEOUT_MS = 120_000L

    @Volatile
    private var _activeSession: SubprocessSession? = null

    /**
     * Install as the active [OcrEngine.Impl].  Returns `true` iff the
     * subprocess starts AND the startup ping succeeds — otherwise the
     * caller should fall back to [JvmHuaweiCloudOcrEngine].
     *
     * No-op when `OCR_PYTHON` is missing OR the runner script is absent
     * OR the startup ping fails — eval keeps running with
     * `OcrResult.EMPTY` (matches pre-OCR baseline).
     */
    fun installIfConfigured(): Boolean {
        val python = System.getenv("OCR_PYTHON") ?: "python3"
        if (!File(OCR_RUNNER).exists()) {
            System.err.println("[OCR-LOCAL] runner script not found at $OCR_RUNNER " +
                "(expected relative to project root)")
            return false
        }

        val session = startSession(python) ?: return false
        val kind = System.getenv("LOCAL_OCR_KIND") ?: "mobile"
        System.err.println("[OCR-LOCAL] backend installed: kind=$kind pid=${session.pid()}")

        _activeSession = session
        OcrEngine.impl = OcrEngine.Impl { jpegBytes -> recognize(jpegBytes) }
        return true
    }

    /**
     * Build ProcessBuilder with augmented PYTHONPATH, start it, and verify
     * via the startup ping.  Returns the live [SubprocessSession] on success,
     * `null` on any failure (with stderr log).
     */
    private fun startSession(python: String): SubprocessSession? {
        val pb = ProcessBuilder(python, OCR_RUNNER)
            .redirectErrorStream(false)

        // Augment PYTHONPATH so `pp_ocrv4_mobile_engine` is importable.
        val pythonPathAdd = System.getenv("OCR_PYTHONPATH") ?: "/home/oppry/work"
        if (pythonPathAdd.isNotBlank()) {
            val env = pb.environment()
            val existing = env["PYTHONPATH"] ?: ""
            env["PYTHONPATH"] = if (existing.isBlank()) pythonPathAdd
                else "$existing:$pythonPathAdd"
        }

        val proc = try {
            pb.start()
        } catch (e: Throwable) {
            System.err.println("[OCR-LOCAL] failed to start subprocess: " +
                "${e.javaClass.simpleName}: ${e.message?.take(160)}")
            return null
        }

        val session = SubprocessSession(proc)
        if (!session.startAndPing(STARTUP_TIMEOUT_MS)) {
            System.err.println("[OCR-LOCAL] startup ping failed; not installed")
            session.destroy()
            return null
        }
        return session
    }

    private suspend fun recognize(jpegBytes: ByteArray): OcrResult = withContext(Dispatchers.IO) {
        // Try the active session first; on failure, restart + retry once.
        val first = tryActiveRecognize(jpegBytes)
        if (first != null) return@withContext first
        System.err.println("[OCR-LOCAL] active session failed; restarting and retrying once")
        restartSession()
        val second = tryActiveRecognize(jpegBytes)
        second ?: OcrResult.EMPTY
    }

    private fun tryActiveRecognize(jpegBytes: ByteArray): OcrResult? {
        val session = _activeSession ?: return null
        return try {
            val kind = System.getenv("LOCAL_OCR_KIND") ?: "mobile"
            val timeoutMs = if (kind == "server") SERVER_CALL_TIMEOUT_MS else MOBILE_CALL_TIMEOUT_MS
            session.recognize(jpegBytes, timeoutMs)
        } catch (e: Throwable) {
            System.err.println("[OCR-LOCAL] recognize failed: " +
                "${e.javaClass.simpleName}: ${e.message?.take(160)}")
            null
        }
    }

    private fun restartSession() {
        val old = _activeSession
        old?.destroy()
        val python = System.getenv("OCR_PYTHON") ?: "python3"
        val session = startSession(python)
        if (session == null) {
            _activeSession = null
            System.err.println("[OCR-LOCAL] restart failed; session dropped (will return EMPTY)")
        } else {
            _activeSession = session
            System.err.println("[OCR-LOCAL] restart ok pid=${session.pid()}")
        }
    }

    /**
     * Encapsulates one live subprocess + its JSON-RPC IO handles.
     * Thread-safe: all stdin/stdout access guarded by the same lock;
     * [recognize] is `suspend` but the synchronous IO happens on
     * [Dispatchers.IO] via the caller.
     */
    private class SubprocessSession(private val proc: Process) {

        private val lock = Any()
        private val writer: PrintWriter = PrintWriter(proc.outputStream.bufferedWriter(), false)
        private val reader: BufferedReader = proc.inputStream.bufferedReader()
        private val nextId = AtomicLong(1)

        init {
            // Drain stderr in a daemon thread so the OS pipe buffer
            // doesn't fill up and block the subprocess.
            val t = Thread({
                try {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        System.err.println("[pp-ocrv4-runner] $line")
                    }
                } catch (_: Throwable) { /* pipe closed */ }
            }, "ocr-local-stderr")
            t.isDaemon = true
            t.start()
        }

        fun pid(): Long = try { proc.pid() } catch (_: Throwable) { -1 }

        /**
         * Read the startup "ready" line from stdout and verify the
         * subprocess is alive.  Times out after [timeoutMs].
         */
        fun startAndPing(timeoutMs: Long): Boolean {
            return try {
                val ready = readLineWithTimeout(timeoutMs) ?: return false
                val obj = runCatching { JSONObject(ready) }.getOrNull() ?: return false
                if (obj.optString("event") != "ready") {
                    System.err.println("[OCR-LOCAL] unexpected startup line: ${ready.take(120)}")
                    return false
                }
                val pong = sendRpc("ping", null, timeoutMs) ?: return false
                pong.optJSONObject("result")?.optBoolean("pong") == true
            } catch (e: Throwable) {
                System.err.println("[OCR-LOCAL] startAndPing failed: " +
                    "${e.javaClass.simpleName}: ${e.message?.take(160)}")
                false
            }
        }

        fun recognize(jpegBytes: ByteArray, timeoutMs: Long): OcrResult? {
            val b64 = Base64.getEncoder().encodeToString(jpegBytes)
            val params = JSONObject().put("image_b64", b64)
            val resp = sendRpc("recognize", params, timeoutMs) ?: return null

            if (resp.has("error")) {
                val err = resp.optJSONObject("error")
                System.err.println("[OCR-LOCAL] runner error: code=${err?.optInt("code")} " +
                    "msg=${err?.optString("message")?.take(200)}")
                return null
            }
            val result = resp.optJSONObject("result") ?: return null
            return parseRunnerResult(result)
        }

        private fun sendRpc(method: String, params: JSONObject?, timeoutMs: Long): JSONObject? {
            val reqId = nextId.getAndIncrement()
            val req = JSONObject().apply {
                put("id", reqId)
                put("method", method)
                if (params != null) put("params", params)
            }
            synchronized(lock) {
                writer.println(req.toString())
                writer.flush()
                if (writer.checkError()) {
                    System.err.println("[OCR-LOCAL] write to stdin failed (broken pipe)")
                    return null
                }
                val line = readLineWithTimeout(timeoutMs) ?: return null
                val parsed = runCatching { JSONObject(line) }.getOrElse {
                    System.err.println("[OCR-LOCAL] non-JSON stdout: ${line.take(120)}")
                    return null
                }
                if (parsed.optLong("id", -1) != reqId) {
                    // Defensive: PaddleOCR with show_log=False shouldn't
                    // pollute stdout, but if it does, drop the noise and
                    // recurse once with a fresh read.
                    System.err.println("[OCR-LOCAL] ignoring stray stdout line: " +
                        "${line.take(120)}")
                    return sendRpc(method, params, timeoutMs)
                }
                return parsed
            }
        }

        private fun readLineWithTimeout(timeoutMs: Long): String? {
            // BufferedReader.readLine blocks indefinitely; run on a shared
            // daemon pool and race against a wall-clock timeout using
            // the standard Future.get(timeout, unit) overload — no need
            // to pull in coroutines here.
            val fut: Future<String?> = sharedPool.submit<String?> { reader.readLine() }
            return try {
                fut.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                fut.cancel(true)
                null
            } catch (e: Throwable) {
                fut.cancel(true)
                null
            }
        }

        fun destroy() {
            try {
                synchronized(lock) {
                    runCatching {
                        writer.println(JSONObject().put("method", "shutdown").toString())
                        writer.flush()
                    }
                }
            } catch (_: Throwable) { /* ignore */ }
            proc.destroy()
            try { proc.waitFor(1, TimeUnit.SECONDS) } catch (_: Throwable) { }
            if (proc.isAlive) proc.destroyForcibly()
        }

        /**
         * Parse the runner's `result` JSON into our [OcrResult].
         * Mirrors the defensive parsing in
         * [JvmHuaweiCloudOcrEngine.parseRunnerOutput].
         */
        private fun parseRunnerResult(result: JSONObject): OcrResult {
            val arr = result.optJSONArray("blocks") ?: JSONArray()
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
                while (corners.size < 4) {
                    corners.add(corners.lastOrNull() ?: OcrPoint(0f, 0f))
                }
                parsed.add(OcrBlock(text = text, corners = corners.take(4), confidence = conf))
            }
            val fullText = result.optString("full_text").ifBlank {
                parsed.joinToString(" ") { it.text }
            }
            return OcrResult(blocks = parsed, fullText = fullText)
        }

        companion object {
            // Shared pool for blocking reads on stdout.  Single daemon
            // thread is enough — the runner is strictly request/response
            // and Kotlin only sends one RPC at a time.
            private val sharedPool = Executors.newSingleThreadExecutor { r ->
                Thread(r, "ocr-local-read").apply { isDaemon = true }
            }
        }
    }
}
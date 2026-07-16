package com.example.intentcam

import org.json.JSONArray
import org.json.JSONObject

/**
 * Orchestrates one recognition cycle: round 1 picks a tool, round 2+
 * finalizes the answer.
 *
 * Flow:
 *  1. Round 1 — `streamToolUse` with `tools[]` and the user image.
 *  2. If model emitted any `tool_use` blocks, dispatch each via the
 *     registered [ToolDef.body] and collect results.
 *  3. If any tool returned `finalBubble = true`, surface that bubble
 *     immediately and stop.
 *  4. If any tool returned `needsUserInput = true`, return
 *     [Outcome.PendingUserInput] and pause.
 *  5. Otherwise append the tool_results to the message history and
 *     run another round.  Stop when the model emits no more
 *     `tool_use` blocks (treat its text as the final answer) or
 *     after [MAX_ROUNDS].
 *
 * Logging hooks let the caller surface per-round progress on the
 * debug overlay without coupling this class to UI state.
 */
class ToolUseLoop(
    private val client: LlmClient,
    private val registry: ToolRegistry,
    private val intents: IntentRegistry,
    private val log: (tag: String, msg: String) -> Unit = { _, _ -> },
) {

    /** Outcome of one [runCycle].  Exactly one of these is set per call. */
    sealed class Outcome {
        /** The name of the first non-[FINAL_BUBBLE_TOOL] tool the model
         *  invoked this cycle, if any.  Exposed so callers (notably the
         *  eval) can tell "model did reconnaissance before answering"
         *  apart from "model went straight to emit_bubble". */
        abstract val firstToolName: String?

        /** A regular final bubble (no more rounds needed). */
        data class Bubble(
            val bubble: com.example.intentcam.Bubble,
            override val firstToolName: String? = null,
        ) : Outcome()

        /** A placeholder bubble plus a request for user input.
         *  Resume by calling [runCycle] again with non-null [userText]. */
        data class PendingUserInput(
            val placeholder: com.example.intentcam.Bubble,
            val request: UserInputRequest,
            override val firstToolName: String? = null,
        ) : Outcome()

        /** The cycle errored out (LLM timeout, parse failure, etc.).
         *  Caller surfaces a fallback bubble. */
        data class Error(val message: String) : Outcome() {
            override val firstToolName: String? = null
        }
    }

    /**
     * Run one cycle.
     *
     * @param thumbnail the small JPEG sent to the LLM as the
     *   round-1 overview.
     * @param fullRes the original full-resolution JPEG; preserved
     *   across rounds so zoom_in can crop from it on demand.
     * @param userText optional follow-up text from a prior
     *   [Outcome.PendingUserInput]; pass "" on round 1.
     * @param cropOcrCap fast-iteration knob for the eval: max number
     *   of crop OCR runs to perform across the whole cycle (0 =
     *   unlimited, prod default).  Round-1 OCR pre-pass is always
     *   counted separately and is unaffected.
     * @param actionIds registered `ActionDef` ids the
     *   model can propose in `emit_bubble.action_ids`.  Spliced into
     *   the system prompt (the `actions ⊆ {...}` line).  Empty list
     *   = no LLM-proposal branch in [com.example.intentcam.ActionResolver];
     *   non-empty = the model can opt in / out of suggestions per
     *   cycle.  Defaulted to empty so existing callers (eval, tests)
     *   don't need to update.
     */
    suspend fun runCycle(
        thumbnail: ByteArray,
        fullRes: ByteArray,
        userText: String,
        cropOcrCap: Int = 0,
        actionIds: List<String> = emptyList(),
        /** Stable id of the
         *  owning cycle job (set by [com.example.intentcam.CycleManager]
         *  via [CycleProgress.cycleId]).  Defaults to "default" for
         *  the legacy single-cycle caller (eval, single-shot
         *  triggers) so the parameter is fully backwards-compatible.
         *  The id lands on [com.example.intentcam.Bubble.cycleId] so
         *  the live UI can route per-bubble events back to the
         *  originating CycleJob even when multiple jobs run
         *  concurrently. */
        cycleId: String = "default",
        /** Per-emit progress callback.  Fires
         *  every time the cycle produces a finalized (post-verifier)
         *  bubble — at minimum once per cycle when [Outcome.Bubble]
         *  is returned, plus on every successful `emit_bubble` parse
         *  inside the loop (so the live UI sees the partial bubble
         *  before the cycle's final round).  No-op by default.
         *  Carries enough data for [CycleManager] to drive
         *  `CycleJob.bubble` without re-querying the tool loop.
         *
         *  `suspend` because [CycleManager]'s callback re-invokes
         *  [com.example.intentcam.ActionResolver.suggestIds] (also
         *  suspend — it reads SharedPreferences) on every partial
         *  emit.  Cheap when the action set is stable; the
         *  suspend boundary lets us call without a separate
         *  launch-per-emit. */
        onProgress: suspend (CycleProgress) -> Unit = {},
        /** Optional gate called after each successful
         *  `emit_bubble` parse, BEFORE the cycle returns.  When the
         *  callback returns [FinalizeDecision.CONTINUE], the loop
         *  injects a missing-input nudge into the next user message
         *  and runs another round (capped at
         *  [INPUT_MISSING_MAX_RETRIES]); when it returns
         *  [FinalizeDecision.FINALIZE] (or the callback is null), the
         *  cycle ends with the current bubble as the Outcome.
         *
         *  Designed so [ToolUseLoop] stays decoupled from
         *  [com.example.intentcam.ActionOrchestrator] — the caller
         *  ([com.example.intentcam.CycleManager]) wires the gate by
         *  closing over the orchestrator.  Default null preserves
         *  the legacy single-shot emit behavior for eval + tests. */
        onEmit: ((bubble: com.example.intentcam.Bubble, round: Int) -> FinalizeDecision)? = null,
        /** Optional projection called once on the final
         *  bubble before it returns in [Outcome.Bubble].  Lets the
         *  caller stamp `validatedInputs` / `pendingInputs` onto
         *  the bubble's data-class fields using whatever
         *  parser-registry is at hand.  Prod wires
         *  [com.example.intentcam.ActionOrchestrator.markValidatedInputs];
         *  eval wires a pure-shared helper (no Android dep).  Default
         *  null leaves the fields empty (legacy behavior). */
        markValidated: ((bubble: com.example.intentcam.Bubble) -> com.example.intentcam.Bubble)? = null,
    ): Outcome {
        val config = client.config
        val maxRounds = MAX_ROUNDS
        val toolsJson = registry.toAnthropicToolsJson()
        val cropOcrBudget = if (cropOcrCap > 0) cropOcrCap else Int.MAX_VALUE
        var cropOcrUsed = 0
        // Round-1 OCR pre-pass: run OcrEngine on the full-resolution
        // photo so the model has verbatim text + 4-corner coords +
        // confidence per line.  We use fullRes (not the downscaled
        // thumbnail) because OCR quality on small text drops sharply
        // with input resolution.  Failures are silent
        // — read_text still works as a manual fallback.
        //
        // The full [OcrResult] is cached for the whole cycle and
        // passed into every [ToolContext] so `compare_text` can
        // diff the LLM's own reading against round-1's OCR output
        // without re-running OCR on every tool call.
        var ocrException: Throwable? = null
        val ocrResult: OcrResult = runCatching { OcrEngine.recognize(fullRes) }
            .onFailure { e ->
                // Surface the actual exception in the debug overlay
                // so backend-init failures (cachedMlApplication ==
                // null on non-Huawei, MLApplicationSetting.fromResource
                // throwing on a build without agconnect-services,
                // asyncAnalyseFrame race, etc.) aren't silently
                // swallowed and reported as "0 块识别到".  Without
                // this log, an HMS Core missing device looks
                // identical to "no text in scene".
                ocrException = e
                log("OCR_ERR", formatThrowable(e))
            }
            .getOrDefault(OcrResult.EMPTY)
        val ocrHint = OcrResult.formatHint(ocrResult.blocks)
        // Surface OCR pre-pass result in the debug overlay so we
        // can tell at a glance whether HMS OCR ran successfully,
        // returned 0 blocks (e.g. no text in the scene), threw an
        // exception (init failure), or was never installed (impl
        // == null — e.g. non-Huawei device).
        val ocrEx = ocrException
        // ocr_hit = did OCR find ANY text in the frame?  Surfaced as
        // the leading field on every branch so the user can grep it.
        // Useful for sanity-checking the on-device pipeline: e.g.
        // ocr_hit=false with fullRes at 87KB strongly suggests the
        // full-resolution ImageAnalysis buffer is unexpectedly small.
        // See `docs/adr/2026-07-10-on-device-sensor-resolution.md`
        // for the resolution contract.  It also dumps
        // confidence stats (avg / min / max) so a partial-OCR run
        // (most blocks skipped because confidence < threshold) is
        // visible in the overlay instead of silently halving the
        // hit count.
        val ocrStatus = when {
            OcrEngine.impl == null -> "ocr_hit=false 后端未安装（impl == null）"
            ocrEx != null -> "ocr_hit=false 异常（见 OCR_ERR 行）：${ocrEx.javaClass.simpleName}: " +
                (ocrEx.message?.take(80) ?: "?")
            ocrResult.blocks.isEmpty() -> "ocr_hit=false 0 块（图上无文字 / 模型未下完）"
            else -> {
                val lowCount = ocrResult.blocks.count {
                    it.confidence < OcrResult.LOW_CONFIDENCE_THRESHOLD
                }
                val avgConf = ocrResult.blocks
                    .map { it.confidence }
                    .average()
                val minConf = ocrResult.blocks.minOf { it.confidence }
                val maxConf = ocrResult.blocks.maxOf { it.confidence }
                "ocr_hit=true ${ocrResult.blocks.size} 行（${lowCount} [LOW]），" +
                    "conf avg=${"%.2f".format(avgConf)} " +
                    "min=${"%.2f".format(minConf)} " +
                    "max=${"%.2f".format(maxConf)}，" +
                    "hint ${ocrHint.length} 字符"
            }
        }
        log("OCR", ocrStatus)
        val messages = JSONArray()
            .put(
                client.userImageMessage(thumbnail, userText, ocrHint)
            )

        var lastRound: RoundSnapshot? = null
        var pendingUserInput: PendingUserInput? = null
        var chosenToolName: String? = null
        // Phase 2c (2026-07-10): retry-once on `stop_reason=max_tokens`
        // when the truncated JSON produced an empty bubble.  Without
        // this, 2-7/100 fixtures (e.g. rctw_21, rctw_49) hit
        // `max_tokens` mid-`emit_bubble` JSON, parse fails → empty
        // `{}` → bubble with empty content + details, costing ~-0.015
        // composite in the @100 noise floor.  Detected via the empty-
        // bubble fingerprint (detail blank AND details empty) on a
        // `stop=max_tokens` round.  Capped at 1 retry per fixture to
        // bound wall-time; if the retry also truncates we fall through
        // to the empty-bubble path and the caller scores it as-is.
        var maxTokensRetries = 0
        // Input-missing nudge loop.  After each
        //  emit_bubble parse, [onEmit] (the orchestrator's gate) is
        //  consulted: CONTINUE → inject missing-input hint + re-loop.
        //  Capped at [INPUT_MISSING_MAX_RETRIES] (3) per fixture to
        //  bound wall-time.  Mirrors the max_tokens retry pattern
        //  (ToolUseLoop.kt:411-444) but is distinct: this gate fires
        //  when the bubble parses cleanly but is missing required
        //  action inputs (LLM didn't extract them in round 1); the
        //  max_tokens gate fires when the bubble itself is malformed.
        var inputMissingRetries = 0

        // Multi-round drill-down chain: persists across rounds so
        // round-N's `zoom_in(source="last")` crops round-N-1's
        // last followUpJpeg, not fullRes.  Bug fix: previously
        // reset to fullRes at the top of every round, so chained
        // zoom_in would only ever crop fullRes regardless of how
        // many zooms deep we were.  This persistence is what
        // makes the [LOW]-line re-OCR workflow (round-1 OCR →
        // round-2 zoom_in the [LOW] bbox → round-3 zoom_in on the
        // crop again for deeper drill-down) actually work end-to-end.
        var currentImage: ByteArray = fullRes

        for (round in 1..maxRounds) {
            log("TOOL", "→ 第 $round 轮（messages=${messages.length()}）")
            val response: ToolUseResponse = try {
                client.streamToolUse(
                    system = LlmClient.toolUseSystemPrompt(actionIds),
                    messages = messages,
                    toolsJson = toolsJson,
                )
            } catch (e: Throwable) {
                log("TOOL_ERR", formatThrowable(e))
                // Timeout / HTTP / SSE failure mid-cycle.  Don't drop the
                // entire cycle's work — synthesize a Bubble from
                // `lastRound` (whichever round's text we last saw) so
                // the caller still gets a parseable, scoreable answer.
                // Without this, the eval/scoring sees Outcome.Error and
                // pins composite to 0.15 forever (12/100 fixtures in
                // 2026-07-06 1-only @100 were lost this way).
                val partial = lastRound
                return if (partial != null && partial.text.isNotBlank()) {
                    log(
                        "TOOL_FALLBACK",
                        "round $round failed (${e.message?.take(60) ?: "?"}); " +
                            "recovering with partial text from round ${partial.round} " +
                            "(${partial.text.length}字)"
                    )
                    Outcome.Bubble(
                        com.example.intentcam.Bubble(
                            id = "bubble-${System.currentTimeMillis()}",
                            type = IntentRegistry.FALLBACK_ID,
                            title = partial.text.take(40).ifBlank { "未识别" },
                            detail = partial.text.take(200).ifBlank { "（识别超时，已用部分结果）" },
                            // Lower than the normal 0.7 so the UI knows
                            // this answer is incomplete; eval doesn't
                            // gate on confidence.
                            confidence = 0.4f,
                            imageBytes = thumbnail,
                            createdAtMs = System.currentTimeMillis(),
                            toolName = null,
                        ),
                        firstToolName = null,
                    )
                } else {
                    Outcome.Error(e.message ?: "LLM 失败")
                }
            }
            log(
                "TOOL",
                "← text=${response.text.length}字 blocks=${response.toolBlocks.size} stop=${response.stopReason}"
            )

            lastRound = RoundSnapshot(
                round = round,
                text = response.text,
                toolBlocks = response.toolBlocks,
            )

            // Persist the assistant message exactly as the model sent
            // it — this is required by Anthropic's protocol so the
            // next round can quote tool_use_id back.
            messages.put(reconstructAssistantMessage(response))

            if (response.toolBlocks.isEmpty()) {
                // Model produced text without tool_use: that's our
                // final answer.
                break
            }

            // Dispatch tools.
            val toolResults = JSONArray()
            var anyNeedsInput = false
            var anyFinalBubble: ToolResult? = null
            // Chain state: each zoom_in (with default source="last")
            // crops the previously produced image, allowing iterative
            // zoom-in.  `currentImage` is declared OUTSIDE the for-
            // round loop so it persists across rounds — the round-N
            // default-source zoom_in crops round-N-1's last
            // followUpJpeg, not fullRes.
            // All follow-up images in call order, attached to the
            // next user message in the same order.  Multi-zoom in
            // one round produces a list of N images.
            val followUps = mutableListOf<ByteArray>()
            for (block in response.toolBlocks) {
                val def = registry.get(block.name)
                // Track the FIRST non-finalizing tool the model picked.
                // emit_bubble is the final-answer tool, not the
                // interpretation tool; its name shouldn't overwrite the
                // tool that did the actual work (e.g. read_device_reading).
                if (block.name != FINAL_BUBBLE_TOOL && chosenToolName == null) {
                    chosenToolName = block.name
                }
                if (def == null) {
                    log("TOOL", "未知工具 ${block.name}，回 error 提示")
                    toolResults.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", block.id)
                            .put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", "未知工具：${block.name}")
                                    )
                            )
                            .put("is_error", true)
                    )
                    continue
                }
                log("TOOL", "→ ${def.name}(${block.inputJson.take(120)})")
                val parsedInput = runCatching { JSONObject(block.inputJson) }
                    .getOrElse { JSONObject() }
                val toolResult = try {
                    // Build a fresh ToolContext per call.  The body
                    // sees the chain-up-to-this-point via ctx.jpeg;
                    // the original is always available via
                    // ctx.originalFullRes for source="original" mode.
                    // ocrCache carries the round-1 OCR result so
                    // compare_text can reuse it instead of re-running
                    // OCR on already-scanned regions.  (read_text was
                    // removed in Phase 2 — auto-OCR on every zoom_in
                    // crop covers the same need.)
                    // regions.
                    def.body(
                        ToolContext(
                            jpeg = currentImage,
                            originalFullRes = fullRes,
                            thumbnail = thumbnail,
                            userText = userText,
                            config = config,
                            ocrCache = ocrResult,
                        ),
                        parsedInput,
                    )
                } catch (e: Throwable) {
                    log("TOOL_ERR", "${def.name}: ${formatThrowable(e)}")
                    ToolResult(toolSummary = "工具执行失败：${e.message?.take(80) ?: "未知错误"}")
                }
                log("TOOL", "← ${def.name} final=${toolResult.finalBubble} needsInput=${toolResult.needsUserInput} followUp=${toolResult.followUpJpeg != null}")
                if (toolResult.needsUserInput) {
                    anyNeedsInput = true
                    pendingUserInput = PendingUserInput(
                        toolName = def.name,
                        prompt = toolResult.userInputPrompt.ifBlank { "请补充信息" },
                        bubble = buildPlaceholder(
                            jpeg = thumbnail,
                            toolName = def.name,
                            detail = toolResult.toolSummary,
                            // Stamp the owning
                            //  cycle's id onto the placeholder so
                            //  AppViewModel can route the user's
                            //  follow-up text back to the correct
                            //  CycleJob via `Bubble.cycleId`.  The
                            //  legacy single-cycle caller (eval)
                            //  passes the default "default" cycleId,
                            //  matching Bubble.cycleId's default
                            //  behavior so backward compat is
                            //  preserved.
                            cycleId = cycleId,
                        ),
                    )
                }
                if (toolResult.finalBubble && anyFinalBubble == null) {
                    anyFinalBubble = toolResult
                }
                // Advance the chain.  A followUpJpeg becomes the
                // source for any subsequent zoom_in in the same
                // round (with source="last") and for the next round
                // entirely.
                if (toolResult.followUpJpeg != null) {
                    currentImage = toolResult.followUpJpeg
                    followUps.add(toolResult.followUpJpeg)
                }
                toolResults.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.id)
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", toolResult.toolSummary)
                                )
                        )
                )
            }

            // Phase 2c (2026-07-10): empty-bubble retry.  If the model
            // hit `stop_reason=max_tokens` mid-`emit_bubble` JSON, the
            // parser may have partially succeeded (org.json.JSONObject
            // is lenient about trailing junk), yielding a half-built
            // bubble whose `content`/`detail` field is blank even when
            // some `details[]` rows made it through.  Don't return that
            // — push a "summarize short" nudge and re-run the round so
            // the model has another chance to emit a complete JSON.
            // Cap at 1 retry per fixture so a pathologically verbose
            // model can't loop forever.
            //
            // Trigger condition: `detail` field blank (the user-facing
            // content).  Catches both fingerprints:
            //  - detail blank + details empty (rctw_21/49)
            //  - detail blank + details non-empty (rctw_14, 2026-07-10
            //    debug: 11 detail rows but content empty)
            if (response.stopReason == "max_tokens" &&
                anyFinalBubble?.detail.isNullOrBlank() &&
                maxTokensRetries < 1
            ) {
                maxTokensRetries++
                log(
                    "TOOL_RETRY",
                    "round $round truncated emit_bubble at stop=max_tokens " +
                        "(detail empty, details empty); nudging to summarize short"
                )
                // Replace the normal next-round user message with a
                // single nudge text block.  We don't include the
                // follow-up images or prior tool_results — they were
                // all part of the failed round and re-attaching them
                // would just bloat the next request.  The model's
                // conversation history already has the assistant's
                // truncated emit_bubble from the failed round (we
                // persisted it via reconstructAssistantMessage above);
                // Anthropic's protocol will quote it back unchanged.
                val nudgeContent = JSONArray()
                    .put(
                        JSONObject().put("type", "text").put(
                            "text",
                            "你上一次的 emit_bubble 调用被 token 预算截断了（response 撞到 " +
                                "max_tokens）。请**立刻**重新调一次 emit_bubble，这次：" +
                                "\n  - content 用 ≤150 字总结场景" +
                                "\n  - 完整文字 / 数字 / 品牌 / 价格写到 details[]（≥1 行，" +
                                "≤10 行，多了会再次撞 max_tokens）" +
                                "\n  - 不要在 emit_bubble 调用前再写长 prose——直接 emit。"
                        )
                    )
                messages.put(JSONObject().put("role", "user").put("content", nudgeContent))
                continue
            }
            // Build next user message.  If any tool returned a
            // follow-up image (zoom_in), prepend an image content
            // block + a hint per image so the model sees the
            // high-detail regions in addition to the tool_results.
            //
            // Phase 2a (2026-07-11): every zoom_in crop auto-runs
            // on-device OCR and ships the formatted hint alongside
            // the image.  The hint uses the CROP variant of
            // OcrResult.formatHint: header is "zoom_in crop OCR
            // 高保真重扫" (not the round-1 全图扫描 wording), and the
            // [LOW] follow-up advice is "trust verbatim" instead of
            // "不要 verbatim 复制" — the workflow narrative is in
            // TOOL_USE_SYSTEM (LlmClient.kt) and the hint text just
            // echoes the verdict.
            val nextContent = JSONArray()
            if (followUps.isNotEmpty()) {
                followUps.forEachIndexed { i, img ->
                    val b64 = java.util.Base64.getEncoder().encodeToString(img)
                    nextContent.put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "image/jpeg")
                                    .put("data", b64 as Any)
                            )
                    )
                    // Auto-OCR per crop.  Silent drop on exception
                    // (matches round-1's contract).  Budget-gated by
                    // cropOcrCap so the eval can fast-iterate at
                    // ~2-min/20-fixture pace; prod uses unlimited.
                    val cropNote = if (cropOcrUsed < cropOcrBudget) {
                        cropOcrUsed++
                        val result = runCatching { OcrEngine.recognize(img) }
                        result.onFailure { e ->
                            log("OCR_ERR_CROP", formatThrowable(e))
                        }
                        val cropOcr = result.getOrDefault(OcrResult.EMPTY)
                        val cropHint = OcrResult.formatHint(
                            cropOcr.blocks,
                            maxLines = OcrResult.MAX_CROP_OCR_HINT_LINES,
                            isCropHint = true,
                        )
                        log(
                            "OCR_CROP",
                            "blocks=${cropOcr.blocks.size} " +
                                "topConf=${cropOcr.blocks.maxOfOrNull { it.confidence }?.let { "%.2f".format(it) } ?: "n/a"} " +
                                "hintLines=${if (cropHint.isNotBlank()) cropHint.lines().count { it.startsWith("  line ") } else 0} " +
                                "lowCount=${cropOcr.blocks.count { it.confidence < OcrResult.LOW_CONFIDENCE_THRESHOLD }}"
                        )
                        if (cropHint.isNotBlank()) cropHint
                        else "(已对该裁剪区域跑 OCR，未识别到文字——继续 zoom_in 钻更细或直接 emit)"
                    } else null
                    val hintPrefix = if (followUps.size == 1) {
                        "已放大你刚才要求的区域（更高分辨率）"
                    } else {
                        "放大区域 #${i + 1}/${followUps.size}"
                    }
                    val hint = if (cropNote != null) "$hintPrefix。$cropNote" else hintPrefix
                    nextContent.put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", hint)
                    )
                }
            }
            for (i in 0 until toolResults.length()) {
                nextContent.put(toolResults.get(i))
            }
            // If the model didn't emit_bubble this round, nudge it
            // to wrap up.  Keeps the loop bounded: the model can
            // zoom_in as many times as it likes, but at the end of
            // each round we ask for a final summary.
            if (anyFinalBubble == null) {
                nextContent.put(JSONObject().put("type", "text").put(
                    "text",
                    "你已经 zoom_in ${followUps.size} 次。如果还有看不清的，可以再 zoom_in；如果内容已经清楚，" +
                        "**必须**调 emit_bubble 总结 (content / intent / type / confidence)。"
                ))
            }
            messages.put(JSONObject().put("role", "user").put("content", nextContent))

            if (anyFinalBubble != null) {
                val tb = anyFinalBubble
                // The LLM's `emit_bubble.type` and `action_ids` are
                // authoritative.  ActionOrchestrator only validates each
                // action's required inputs and projects chip state; it does
                // not rewrite the model's intent or action selection.  See
                // `docs/adr/2026-07-14-v3-inversion.md`.
                val verifiedType = tb.type
                val verifiedActions: List<String>? =
                    tb.proposedActions?.takeIf { it.isNotEmpty() }
                val finalBubble = com.example.intentcam.Bubble(
                    id = "bubble-${System.currentTimeMillis()}",
                    cycleId = cycleId,
                    type = verifiedType,
                    title = tb.title.ifBlank { "未识别" },
                    detail = tb.detail,
                    confidence = tb.confidence,
                    imageBytes = thumbnail,
                    createdAtMs = System.currentTimeMillis(),
                    toolName = chosenToolName,
                    // emit_bubble body populates tb.details from
                    // the model's details[] JSON array.  Without
                    // this, every Bubble from runCycle has an
                    // empty details list even when the model did
                    // emit the rows — the r2_text plateau's
                    // companion symptom.
                    details = tb.details,
                    // Persist the model's explicit
                    // action_ids choice onto the bubble so
                    // AppViewModel's resolver can use it as the
                    // LLM-override branch.  Null when emit_bubble
                    // didn't receive the field (the legacy
                    // applicability path).
                    llmProposedActions = verifiedActions,
                )
                // Notify the CycleManager that
                // this cycle has a finalized bubble.  `isTerminal =
                // true` because the cycle returns Outcome.Bubble
                // immediately after this callback.  CycleManager
                // pushes the bubble into its CycleJob.bubble
                // StateFlow; the live UI (Phase C) will render it.
                onProgress.invoke(CycleProgress(
                    cycleId = cycleId,
                    round = round,
                    bubble = finalBubble,
                    isTerminal = true,
                ))
                // Input-missing gate.  When the caller
                //  wires [onEmit] (typically
                //  [com.example.intentcam.ActionOrchestrator.shouldFinalize]
                //  via [com.example.intentcam.CycleManager]), this
                //  fires AFTER the bubble is finalized.  CONTINUE →
                //  inject a missing-input nudge + re-enter the for-
                //  round loop (capped at [INPUT_MISSING_MAX_RETRIES]).
                //  The bubble isn't returned to the caller yet — the
                //  next round's emit_bubble will overwrite it.  When
                //  onEmit is null (legacy / eval), this branch is
                //  skipped and the cycle ends here.
                val gate = onEmit?.invoke(finalBubble, round)
                if (gate is FinalizeDecision.CONTINUE &&
                    inputMissingRetries < INPUT_MISSING_MAX_RETRIES
                ) {
                    inputMissingRetries++
                    log(
                        "TOOL_NUDGE",
                        "round $round missing=${gate.missing}; " +
                            "retry $inputMissingRetries/$INPUT_MISSING_MAX_RETRIES"
                    )
                    val nudgeContent = buildMissingInputNudge(gate.missing)
                    messages.put(JSONObject().put("role", "user").put("content", nudgeContent))
                    continue
                }
                if (gate is FinalizeDecision.FINALIZE) {
                    log("TOOL_FINALIZE", "round $round reason=${gate.reason}")
                }
                // Stamp validation state onto the bubble
                //  before returning.  Wraps the bubble in a copy()
                //  so the data-class fields reflect the orchestrator's
                //  (or eval-side helper's) view.  Null = no-op
                //  (legacy callers keep empty fields).
                val stamped = markValidated?.invoke(finalBubble) ?: finalBubble
                return Outcome.Bubble(stamped, firstToolName = chosenToolName)
            }
            if (anyNeedsInput && pendingUserInput != null) {
                val pui = pendingUserInput
                return Outcome.PendingUserInput(
                    pui.bubble,
                    UserInputRequest(pui.toolName, pui.prompt),
                    firstToolName = chosenToolName,
                )
            }
        }

        // Hit MAX_ROUNDS without the model emitting_bubble.  Take
        // whatever the last round's text said as the description, and
        // synthesize a default emit_bubble from it.  Better than
        // failing the whole cycle.
        val finalText = lastRound?.text.orEmpty()
        val fallbackBubble = com.example.intentcam.Bubble(
            id = "bubble-${System.currentTimeMillis()}",
            type = IntentRegistry.FALLBACK_ID,
            title = finalText.take(40).ifBlank { "未识别" },
            detail = finalText.take(200).ifBlank { "（模型未给出内容描述）" },
            confidence = 0.5f,
            imageBytes = thumbnail,
            createdAtMs = System.currentTimeMillis(),
            toolName = chosenToolName,
        )
        // Apply markValidated to the 兜底 bubble too so
        //  eval/prod see consistent validation state regardless of
        //  whether the cycle hit the round cap or emitted normally.
        val stampedFallback = markValidated?.invoke(fallbackBubble) ?: fallbackBubble
        return Outcome.Bubble(stampedFallback, firstToolName = chosenToolName)
    }

    private fun buildPlaceholder(
        jpeg: ByteArray,
        toolName: String,
        detail: String,
        // Cycle id stamped onto the placeholder's
        //  Bubble.cycleId so AppViewModel can route the user's
        //  follow-up text back to the originating CycleJob when
        //  multiple cycles run concurrently.  Defaults to the
        //  legacy "default" value used by eval / single-shot callers
        //  so backward compatibility is preserved for tests that
        //  don't pass a cycleId.
        cycleId: String = "default",
    ): com.example.intentcam.Bubble = com.example.intentcam.Bubble(
        id = "bubble-${System.currentTimeMillis()}",
        cycleId = cycleId,
        type = IntentRegistry.FALLBACK_ID,
        title = "需要补充信息",
        detail = detail,
        confidence = 0.5f,
        imageBytes = jpeg,
        createdAtMs = System.currentTimeMillis(),
        toolName = toolName,
        needsUserInput = true,
    )

    /**
     * Build the user-role nudge message that steers the LLM toward
     * extracting the missing required inputs.  Mirrors the shape of
     * the max_tokens retry nudge (ToolUseLoop.kt:411-444) — a single
     * text block appended to the messages array so the next round's
     * `streamToolUse` call quotes it as the user's voice.
     *
     * The wording tells the LLM three things:
     *   1. Which input keys are still missing (deduplicated, in
     *      first-appearance order).
     *   2. The two exploration tools at its disposal
     *      (`extract_text` for already-visible regions, `zoom_in` for
     *      corners the thumbnail didn't show).
     *   3. **Verbatim back into details[]**: the parsers read from
     *      `bubble.details[].value`, so any text the LLM extracts
     *      must land there, not just in `detail` prose.
     *
     * Capped at [INPUT_MISSING_MAX_RETRIES] (3) — beyond that, the
     * bubble ships as-is with `pendingInputs` populated for the UI's
     * ghost-chip state.  The cap is enforced at the call site
     * (ToolUseLoop.kt:643 onward), not here.
     */
    private fun buildMissingInputNudge(missing: List<String>): JSONArray {
        val text = buildString {
            append("你刚才的 emit_bubble 还差 ${missing.size} 个 input: ")
            append(missing.joinToString("、"))
            append("。")
            append("\n请按以下步骤补齐:")
            append("\n  - **已经在缩略图里看到 + OCR 不确定** → 调 `extract_text(bbox)` 拿 verbatim 字符（不付 image token）")
            append("\n  - **缩略图里完全看不到那块**（角落字 / 裁切掉的部分）→ 调 `zoom_in(bbox, source='original')` 拉新像素")
            append("\n  - **缺地址 / 店名** → 一般在 title / detail / details[].value 任意一个非空字段里，extract_text 抓回来 verbatim 写到 details[]")
            append("\n\n**关键**：input 是从 bubble.details[].value 抽取的，所以 OCR / 自己读到的字符必须 verbatim 写进 details[].value（label 用 “手机号” / “地点” 等），parser 才能在下一轮 validate 时拿到。")
            append("\n不要意译、不要概括、不要只写到 detail prose 里——写不到 details[] = parser 抽不到 = 还是缺 input。")
        }
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
    }

    /**
     * Reconstruct the assistant message exactly as the model emitted
     * it — a `content` array of text blocks + tool_use blocks.  This
     * is what Anthropic requires for the next round to quote back
     * `tool_use_id`.
     */
    private fun reconstructAssistantMessage(response: ToolUseResponse): JSONObject {
        val content = JSONArray()
        if (response.text.isNotEmpty()) {
            content.put(JSONObject().put("type", "text").put("text", response.text))
        }
        for (block in response.toolBlocks) {
            val input = runCatching { JSONObject(block.inputJson) }
                .getOrElse {
                    log("PARSE_ERR", "tool input JSON unparseable for ${block.name}: ${block.inputJson.take(120)}")
                    JSONObject()
                }
            content.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", block.id)
                    .put("name", block.name)
                    .put("input", input)
            )
        }
        return JSONObject().put("role", "assistant").put("content", content)
    }

    private data class RoundSnapshot(
        val round: Int,
        val text: String,
        val toolBlocks: List<ToolUseBlock>,
    )

    private data class PendingUserInput(
        val toolName: String,
        val prompt: String,
        val bubble: com.example.intentcam.Bubble,
    )

    private companion object {
        /** Soft cap on rounds per recognition cycle.  With the
         *  two-stage content-then-intent flow + iterative zoom_in,
         *  the model can need 5-10 rounds to converge on dense
         *  images.  30 is plenty for normal use; bigger is fine for
         *  debugging.  Tested 15 (2026-07-10 round 2): at least one
         *  fixture (rctw_default_10) hit the cap and the 兜底 Bubble
         *  fired with empty content → r2_text_fuzzy 0.0, composite
         *  -0.257.  Tighter cap = not safe.  Reverted. */
        const val MAX_ROUNDS = 30

        /** Cap on input-missing nudge rounds per cycle.
         *  After this many retries the bubble ships as-is with
         *  `pendingInputs` populated (ghost chips render in UI).
         *  3 chosen to match the empirical "1 round emit + 1-2
         *  rounds explore" median for phone_20 fixtures; bumping
         *  to 5 adds wall-time without measurable input-fill gain
         *  in the smoke test. */
        const val INPUT_MISSING_MAX_RETRIES = 3

        /** The tool name the model uses to emit the final Bubble.
         *  Tracked separately so we don't overwrite the interpreting
         *  tool's name in the Bubble's `toolName` field. */
        const val FINAL_BUBBLE_TOOL = "emit_bubble"
    }
}

/**
 * One progress event from
 * [ToolUseLoop.runCycle].  Fired on every successful `emit_bubble`
 * parse inside the loop (so partial bubbles reach the live UI
 * before the cycle's terminal round) and once more on the final
 * round.  Carries enough state for [com.example.intentcam.CycleJob]
 * to update its `MutableStateFlow`s without re-querying the tool
 * loop.  `round` is the 1-indexed round number that produced the
 * bubble; `isTerminal` is true when this is the last bubble the
 * cycle will emit (i.e. when [ToolUseLoop.runCycle] returns
 * `Outcome.Bubble`).
 *
 * Lives in `shared/` because [ToolUseLoop] is in `shared/` and the
 * callback type must be importable from both `:shared` (the LLM
 * driver) and `:app` (the CycleManager consumer).
 */
data class CycleProgress(
    val cycleId: String,
    val round: Int,
    val bubble: com.example.intentcam.Bubble,
    val isTerminal: Boolean,
)
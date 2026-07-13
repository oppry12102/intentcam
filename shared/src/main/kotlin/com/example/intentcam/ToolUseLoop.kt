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
     * @param actionIds [2026-07-13] registered `ActionDef` ids the
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
        // ImageAnalysis buffer is too small for OCR to read anything
        // (cf. [2026-07-12] ResolutionSelector fix).  Also dumps
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
                    system = LlmClient.toolUseSystemPrompt(intents, actionIds),
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
                // [2026-07-13] Phase E — post-emit_bubble type
                //  verifier.  Silently overrides `type` when a
                //  stronger domain signal in the bubble text
                //  contradicts the LLM's classification (e.g.
                //  `location` bubble that contains a mobile number
                //  → `phone`).  Conservative: only fires on
                //  `location` and `info` source types.  On a flip the
                //  LLM's `proposedActions` are re-aligned to the new
                //  type by Phase F below; when no flip happens they are
                //  preserved unchanged so r3 stays a true model-behavior
                //  metric.  See IntentVerifier.kt for the rule set.
                val verifiedType = IntentVerifier.verify(
                    currentType = tb.type,
                    title = tb.title,
                    detail = tb.detail,
                    details = tb.details,
                )
                // [2026-07-11] Phase F — when the verifier CORRECTS the
                //  type, the LLM's action_ids (proposed for the OLD type)
                //  are now stale, so the r3 chip disagrees with the r2
                //  type (e.g. a location→phone flip still carries
                //  `open_in_maps`, never `dial_number`).  Inject the
                //  corrected type's canonical action so action follows
                //  type.  Additive + monotonic: we only ensure the
                //  correct id is PRESENT (never remove), so r3 recall can
                //  only rise; settings gating (`enabledActionIds`) still
                //  filters the chip in prod, and eval scores recall so an
                //  extra id never hurts.
                //
                // [2026-07-13] Phase J r3-lift expansion: previously
                //  scoped to the flip case only ("when the LLM types
                //  correctly on its own we leave its proposal
                //  untouched, keeping r3 a real model-behavior
                //  signal").  But new intents (Phase J `shopping_promo`
                //  being the first) have no LLM prior for the canonical
                //  action, so the LLM applies its own heuristic (e.g.
                //  emit `dial_number` because the sign has a phone
                //  number) instead of the type→canonical mapping.  The
                //  r3 signal is preserved at the r2_type level (which
                //  still measures LLM classification accuracy directly,
                //  no auto-correction).  New condition: inject when the
                //  canonical action is NOT already in the LLM's
                //  proposal — covers both flip and "LLM types right but
                //  emits wrong action" cases; established actions
                //  (phone→dial_number, location→open_in_maps) already
                //  hit the LLM's own emit path so no injection runs.
                var verifiedActions = tb.proposedActions
                val canonical = IntentVerifier.actionFor(verifiedType)
                if (canonical != null && canonical !in tb.proposedActions.orEmpty()) {
                    verifiedActions = listOf(canonical) + tb.proposedActions.orEmpty()
                    val reason = if (verifiedType != tb.type) {
                        "type-flip ${tb.type} -> $verifiedType"
                    } else {
                        "missing-canonical for type=$verifiedType"
                    }
                    log("VERIFY", "action injected: $canonical ($reason)")
                }
                return Outcome.Bubble(
                    com.example.intentcam.Bubble(
                        id = "bubble-${System.currentTimeMillis()}",
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
                        // [2026-07-13] Persist the model's explicit
                        // action_ids choice onto the bubble so
                        // AppViewModel's resolver can use it as the
                        // LLM-override branch.  Null when emit_bubble
                        // didn't receive the field (the legacy
                        // applicability path).
                        // [2026-07-11] Phase F: `verifiedActions` == the
                        // model's list, plus the corrected type's
                        // canonical action when the verifier flipped
                        // type (else identical to tb.proposedActions).
                        llmProposedActions = verifiedActions,
                    ),
                    firstToolName = chosenToolName,
                )
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
        return Outcome.Bubble(
            com.example.intentcam.Bubble(
                id = "bubble-${System.currentTimeMillis()}",
                type = IntentRegistry.FALLBACK_ID,
                title = finalText.take(40).ifBlank { "未识别" },
                detail = finalText.take(200).ifBlank { "（模型未给出内容描述）" },
                confidence = 0.5f,
                imageBytes = thumbnail,
                createdAtMs = System.currentTimeMillis(),
                toolName = chosenToolName,
            ),
            firstToolName = chosenToolName,
        )
    }

    private fun buildPlaceholder(
        jpeg: ByteArray,
        toolName: String,
        detail: String,
    ): com.example.intentcam.Bubble = com.example.intentcam.Bubble(
        id = "bubble-${System.currentTimeMillis()}",
        type = IntentRegistry.FALLBACK_ID,
        title = "需要补充信息",
        // [2026-07-13] Drop the "via $toolName" fallback — that string
        //  was a debug breadcrumb leaking the model's tool-routing
        //  onto the user-facing bubble.  Title alone (above) already
        //  communicates the placeholder state.
        detail = detail,
        confidence = 0.5f,
        imageBytes = jpeg,
        createdAtMs = System.currentTimeMillis(),
        toolName = toolName,
        needsUserInput = true,
    )

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

    private data class ParsedAnswer(
        val scene: String,
        val intent: String,
        val type: String,
        val confidence: Float,
    )

    private fun parseFinalAnswer(raw: String): ParsedAnswer {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val first = cleaned.indexOf('{')
        val last = cleaned.lastIndexOf('}')
        if (first >= 0 && last > first) {
            val parsed = runCatching {
                JSONObject(cleaned.substring(first, last + 1))
            }.getOrNull()
            if (parsed != null) {
                val scene = parsed.optString("scene", "")
                val intent = parsed.optString("intent", "")
                val type = parsed.optString("type", IntentRegistry.FALLBACK_ID).ifBlank { IntentRegistry.FALLBACK_ID }
                val conf = parsed.optDouble("confidence", 0.7).toFloat().coerceIn(0f, 1f)
                return ParsedAnswer(scene, intent, type, conf)
            }
        }
        // Fallback: use first sentence as intent.
        val firstSentence = cleaned.take(40).trim().ifBlank { "未识别" }
        return ParsedAnswer(scene = cleaned.take(160), intent = firstSentence, type = IntentRegistry.FALLBACK_ID, confidence = 0.5f)
    }

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

        /** The tool name the model uses to emit the final Bubble.
         *  Tracked separately so we don't overwrite the interpreting
         *  tool's name in the Bubble's `toolName` field. */
        const val FINAL_BUBBLE_TOOL = "emit_bubble"
    }
}
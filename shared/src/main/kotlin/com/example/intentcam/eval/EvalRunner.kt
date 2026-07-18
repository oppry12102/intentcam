package com.example.intentcam.eval

import com.example.intentcam.ActionInputSpec
import com.example.intentcam.Bubble
import com.example.intentcam.CapturedFrame
import com.example.intentcam.ImagePipeline
import com.example.intentcam.LlmClient
import com.example.intentcam.ToolRegistry
import com.example.intentcam.ToolUseLoop
import com.example.intentcam.encodeThumbnail
import com.example.intentcam.registerDefaultTools
import com.example.intentcam.projectInputsValidation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import java.io.File

/**
 * The actual eval loop.  Builds a real [LlmClient] + [ToolUseLoop]
 * (the same classes the Android app uses), runs one cycle per
 * fixture, scores the outcome, and prints a per-fixture + overall
 * summary.
 *
 * Scoring is the same composite metric the previous Python eval
 * used:
 *   composite = 0.50 * r1_score + 0.50 * r2_score
 *   r1_score  = 0.70 * tool_pick + 0.30 * input_valid
 *   r2_score  = 0.50 * text_keyword_hit_rate + 0.50 * emit_type
 *
 * r2_text = avg of:
 *   - fraction of expected_description_keywords found in model text
 *   - fraction of expected_details matched in emit_bubble.details
 */
internal class EvalRunner(private val config: EvalConfig) {

    private val client = LlmClient(evalLlmConfig)
    // Tool registry built without an intent registry — the intent
    //  taxonomy was retired 2026-07-17 (intent is the LLM's free-form
    //  summary, not a registered/scored enum). emit_bubble's action_ids
    //  is the sole action-routing signal.
    private val registry = ToolRegistry().also { it.registerDefaultTools() }
    // The model can only emit `action_ids` that are enumerated in the
    //  system prompt's `actions ⊆ {...}` block.  Mirror the Android
    //  app's default registry by id so the eval cycle sees the same
    //  action-id vocabulary as prod.  A registry type isn't needed
    //  here — we score the bubble's raw proposal
    //  (`llmProposedActions`) directly against the ground truth, not
    //  the resolver-filtered `actions` list, so a disabled / future
    //  action change doesn't silently move the recall number.
    //  Keep in lockstep with `registerDefaultActions` in `app/`.
    private val defaultActionIds = listOf(
        "open_in_maps",   // location → maps
        "dial_number",    // phone → system dialer
        "scan_to_pay",    // payment_qr → guidance Toast
        "redact_id",      // id_document → guidance Toast
        "share",          // unified share-text action (was
                          //   copy_listing/save_posting/copy_warning/
                          //   copy_menu/copy_hours/copy_promo)
    )
    private val defaultActionIdSet = defaultActionIds.toSet()

    /** Eval-side parser-mirror registry.  Used by the
     *  [markValidated] callback passed to ToolUseLoop.runCycle so
     *  eval-side ScorerV3 sees populated `validatedInputs` /
     *  `pendingInputs` fields (parity with prod's
     *  `ActionOrchestrator.markValidatedInputs`).
     *
     *  Mirrors the production `InputParsers` regex set — single
     *  source of truth, see ADR
     *  docs/adr/2026-07-16-input-parsers-drift-risk.md.
     *
     *  Returned as `Map<actionId, List<ActionInputSpec>>` so it
     *  feeds directly into [projectInputsValidation]. */
    private fun defaultRequiredInputs(): Map<String, List<ActionInputSpec>> {
        // Use the shared InputParsers from `:shared/` (single source
        // of truth for prod + eval).  See ADR
        // docs/adr/2026-07-16-input-parsers-drift-risk.md.
        return mapOf(
            "dial_number"   to listOf(ActionInputSpec(
                "phone_number", "手机号",
                { b -> if (com.example.intentcam.InputParsers.phoneNumber(b) != null) "present" else null }
            )),
            "open_in_maps"  to listOf(ActionInputSpec(
                "query", "地点或地址",
                { b -> if (com.example.intentcam.InputParsers.locationQuery(b) != null) "present" else null }
            )),
            "share"         to listOf(ActionInputSpec(
                "text", "正文",
                { b -> if (com.example.intentcam.InputParsers.textContent(b) != null) "present" else null }
            )),
            // scan_to_pay / redact_id have no requiredInputs.
        )
    }

    private companion object {
        // Phone regex constants live in `com.example.intentcam.InputParsers`
        // (single source of truth shared with prod).  No local copies.

        /** Char-overlap threshold for [hybridMatch]'s secondary
         *  fallback.  Matches Python aligned4's `_hybrid_match` ≥0.67
         *  ratio.  Below this threshold we declare no hit (avoid
         *  over-credit on trivial 1-2 char matches). */
        const val CHAR_OVERLAP_THRESHOLD = 0.67
    }
    // Phase 2b debug: forward ToolUseLoop logs to stderr when
    // --debug-fixtures is set, otherwise stay silent like before.
    // The orchestrator's log callback runs in a hot loop so we don't
    // want unconditional stderr — only emit for the fixtures we care
    // about.
    private val debugFixtures: Set<String> = config.debugFixtures
    private val currentSceneId = AtomicReference<String?>(null)
    private val orchestrator = ToolUseLoop(
        client = client,
        registry = registry,
        log = { tag, msg ->
            if (currentSceneId.get()?.let { it in debugFixtures } == true) {
                System.err.println("[${currentSceneId.get()}][$tag] $msg")
            }
        },
    )

    fun run(): Int {
        if (!config.groundTruth.exists()) {
            System.err.println("missing ground truth: ${config.groundTruth}")
            return 1
        }
        val gt = JSONObject(config.groundTruth.readText())
        val scenes = gt.optJSONArray("scenes")
        if (scenes == null) {
            System.err.println(
                "ERROR: ground truth ${config.groundTruth} is missing the 'scenes' key. " +
                "The eval reads the 'scenes' array (not 'fixtures') — see memory " +
                "'feedback-eval-runner-scenes-key'."
            )
            return 1
        }
        val sceneList = (0 until scenes.length()).map { scenes.getJSONObject(it) }
        if (sceneList.isEmpty()) {
            System.err.println("ERROR: ground truth ${config.groundTruth} has empty 'scenes' array.")
            return 1
        }
        // --fixtures restricts the run to a curated id set,
        // preserving GT order so jsonOut is comparable to the
        // 20-fixture baselines.  When the user passes --fixtures
        // together with --limit, --fixtures wins.
        val filtered = if (config.fixtures.isNotEmpty()) {
            val wanted = config.fixtures
            sceneList.filter { it.optString("id", "?") in wanted }
                .also { check ->
                    val missing = wanted - check.map { it.optString("id") }.toSet()
                    if (missing.isNotEmpty()) {
                        System.err.println("  WARN: --fixtures ids not in GT: $missing")
                    }
                    if (check.isEmpty()) {
                        System.err.println("ERROR: --fixtures filtered out every scene in ${config.groundTruth}.")
                        return 1
                    }
                }
        } else sceneList
        val limit = if (config.limit > 0) minOf(config.limit, filtered.size) else filtered.size
        val useScenes = filtered.take(limit)

        println("Loaded $limit real-photo fixtures from ${config.groundTruth.name}")
        println(
            "FrameAnalyzer simulation: --resize ${config.resize} --quality ${config.quality}  " +
                "image-strategy=1-only"
        )

        val perCategory = mutableMapOf<String, MutableList<Double>>()
        val results = mutableListOf<Map<String, Any>>()

        for ((i, scene) in useScenes.withIndex()) {
            val sceneId = scene.optString("id", "?")
            currentSceneId.set(sceneId)
            val category = scene.optString("category", "?")
            val imgName = scene.optString("file", "")
            if (imgName.isEmpty()) continue
            val imgPath = File(config.imgDir, imgName)
            if (!imgPath.exists()) {
                System.err.println("  SKIP $sceneId: missing $imgPath")
                continue
            }

            // Build a CapturedFrame that mirrors what FrameAnalyzer
            // produces on-device: 1 thumbnail + 1 fullRes (1-only
            // image strategy).  The ImageIO-based thumbnail/crop
            // impls (installed by EvalMain) are the JVM equivalent
            // of BitmapFactory + BitmapRegionDecoder.  Thumbnail
            // sizing comes from --resize/--quality.
            val rawBytes = imgPath.readBytes()
            val fullRes = encodeThumbnail(
                rawBytes,
                maxDim = ImagePipeline.MAX_FULL_DIM,
                quality = ImagePipeline.FULL_QUALITY,
            ) ?: rawBytes
            val thumbnail = encodeThumbnail(
                rawBytes,
                maxDim = config.resize,
                quality = config.quality,
            ) ?: rawBytes
            val frame = CapturedFrame(
                thumbnail = thumbnail,
                fullRes = fullRes,
            )

            // Run the real orchestrator.  This calls into the same
            // ToolUseLoop the Android app uses — no parallel
            // implementation.
            val outcome = runBlocking {
                orchestrator.runCycle(
                    thumbnail = frame.thumbnail,
                    fullRes = frame.fullRes,
                    userText = "",
                    cropOcrCap = config.cropOcrCap,
                    // Splice the registered action-id vocabulary
                    //  into the system prompt.  Empty list = no
                    //  LLM-proposal branch (legacy applicability
                    //  filter).
                    actionIds = defaultActionIds,
                    // Stamp validation state on the bubble using
                    //  shared-side parser mirrors.  Prod uses
                    //  ActionOrchestrator.markValidatedInputs (which
                    //  closes over the Android-only ActionRegistry);
                    //  eval can't reach that registry, so it walks
                    //  the same parser definitions via
                    //  [InputsValidator].  Kept lightweight — the
                    //  regex chain runs once per cycle on the
                    //  bubble's already-parsed text surface.
                    //
                    //  [2026-07-17 content-rescue] Mirror the prod
                    //  rescue loop inline so eval scores what users
                    //  actually see. Same InputParsers constants —
                    //  single source of truth across prod + eval
                    //  (see ADR
                    //  docs/adr/2026-07-16-input-parsers-drift-risk.md).
                    markValidated = { bubble ->
                        // Shared visible-chip-set computation (single
                        //  implementation with prod since 2026-07-18 —
                        //  the inline rescue mirror that lived here
                        //  before drifted twice; see
                        //  docs/adr/2026-07-16-input-parsers-drift-risk.md).
                        //  Eval passes the FULL registered vocabulary
                        //  as `enabled` (no user consent gating) so
                        //  scores measure the idealized config by
                        //  design — see
                        //  docs/adr/2026-07-18-eval-prod-parity.md.
                        val visible = com.example.intentcam.ActionRescue
                            .visibleActions(bubble, defaultActionIdSet)
                        val effectiveBubble = bubble.copy(actions = visible)
                        val specs = defaultRequiredInputs()
                        val (validated, pending) = projectInputsValidation(effectiveBubble, specs)
                        effectiveBubble.copy(validatedInputs = validated, pendingInputs = pending)
                    },
                )
            }
            if (outcome is ToolUseLoop.Outcome.Error) {
                System.err.println("  [DBG $sceneId] Outcome.Error: ${outcome.message.take(120)}")
            }

            val bubble = (outcome as? ToolUseLoop.Outcome.Bubble)?.bubble
                ?: (outcome as? ToolUseLoop.Outcome.PendingUserInput)?.placeholder
            // Action-first canonical score (ScorerV3 is the sole scorer
            //  after the 2026-07-17 intent-taxonomy retirement):
            //  0.55·r_actions(recall) + 0.30·r_text + 0.15·r_inputs.
            //  No r_type — intent is free-form, not scored. r_text is
            //  computed here; r_actions + r_inputs inside ScorerV3.
            val textScore = scoreRound2Text(outcome, scene)
            val scorer = com.example.intentcam.eval.ScorerV3Result.compute(
                bubble = bubble,
                scene = scene,
                textScore = textScore,
            )
            // Diagnostic side-metrics (do NOT feed composite) — stashed
            //  so post-hoc analysis can re-inspect text/action gaps
            //  without re-running the LLM.
            val detailsCount = bubble?.details?.size ?: 0
            val contentLen = bubble?.detail?.length ?: 0
            val rawContent = bubble?.detail.orEmpty()
            val rawDetails = JSONArray()
            bubble?.details?.forEach { d ->
                rawDetails.put(JSONObject()
                    .put("kind", d.kind)
                    .put("label", d.label)
                    .put("value", d.value))
            }
            val emittedActions = JSONArray()
            bubble?.llmProposedActions?.forEach { emittedActions.put(it) }
            val expectedActions = scene.optJSONArray("expected_actions") ?: JSONArray()
            // Over-fire: the LLM emitted chips on a fixture whose GT
            //  expects NO action (the `none` suite).  Recall is 1.0 on
            //  empty expected so it can't see this — the over-fire rate
            //  is the precision signal recall lacks.  `actual` mirrors
            //  ScorerV3.computeActions's visible set (.actions, falling
            //  back to llmProposedActions for legacy bubbles).
            val expectedEmpty = expectedActions.length() == 0
            val actualActions: Set<String> = when {
                bubble?.actions?.isNotEmpty() == true -> bubble!!.actions.toSet()
                !bubble?.llmProposedActions.isNullOrEmpty() ->
                    bubble!!.llmProposedActions!!.toSet()
                else -> emptySet()
            }
            val overFired = expectedEmpty && actualActions.isNotEmpty()
            results.add(mapOf(
                "id" to sceneId,
                "category" to category,
                "composite_v3" to scorer.composite,
                "v3_actions" to scorer.actions,
                "v3_text" to scorer.text,
                "v3_inputs" to scorer.inputs,
                "over_fired" to overFired,
                "details_count" to detailsCount,
                "content_len" to contentLen,
                "raw_content" to rawContent,
                "raw_details" to rawDetails,
                "emitted_action_ids" to emittedActions,
                "expected_actions" to expectedActions,
            ))
            perCategory.getOrPut(category) { mutableListOf() }.add(scorer.composite)

            if (i < 5 || i == useScenes.size - 1 || (i + 1) % 10 == 0) {
                println(
                    "  [${i + 1}/${useScenes.size}] ${sceneId.padEnd(30)} " +
                        "cat=${category.padEnd(15)} " +
                        "v3[ a=${"%.2f".format(scorer.actions)} tx=${"%.2f".format(scorer.text)} " +
                        "i=${"%.2f".format(scorer.inputs)} c=${"%.2f".format(scorer.composite)} ]"
                )
            }
        }

        println()
        println("=".repeat(60))
        println("fixtures: ${results.size}")
        if (results.isNotEmpty()) {
            val overallV3 = results.map { it["composite_v3"] as Double }.average()
            println("average composite_v3: ${"%.3f".format(overallV3)}")
            val avgV3Actions = results.map { it["v3_actions"] as Double }.average()
            val avgV3Text = results.map { it["v3_text"] as Double }.average()
            val avgV3Inputs = results.map { it["v3_inputs"] as Double }.average()
            println(
                "v3 components: actions=${"%.3f".format(avgV3Actions)} " +
                    "text=${"%.3f".format(avgV3Text)} inputs=${"%.3f".format(avgV3Inputs)}"
            )
            val overFire = results.count { it["over_fired"] as Boolean }
            println("over-fire: $overFire/${results.size} fixtures emitted chips when GT expected none")
            // Diagnostic aggregates — not part of composite.
            val avgDetails = results.map { (it["details_count"] as Int).toDouble() }.average()
            val emptyDetails = results.count { (it["details_count"] as Int) == 0 }
            val avgContentLen = results.map { (it["content_len"] as Int).toDouble() }.average()
            println(
                "details: avg ${"%.1f".format(avgDetails)} rows/fixture, " +
                    "$emptyDetails/${results.size} empty | content avg ${"%.0f".format(avgContentLen)} chars"
            )
        }
        println()
        println("${"category".padEnd(18)} ${"n".padStart(3)} ${"avg".padStart(6)}")
        val categoryAvgs = perCategory.toSortedMap().mapValues { it.value.average() }
        for ((cat, avg) in categoryAvgs) {
            val n = perCategory.getValue(cat).size
            println("${cat.padEnd(18)} ${n.toString().padStart(3)} ${"%.3f".format(avg).padStart(6)}")
        }
        config.jsonOut?.let { writeJsonReport(it, results, categoryAvgs) }
        return 0
    }

    /** Write a structured JSON report (per-fixture scores + category
     *  averages + overall) so runs can be diffed across commits. */
    private fun writeJsonReport(
        file: File,
        results: List<Map<String, Any>>,
        categoryAvgs: Map<String, Double>,
    ) {
        val root = JSONObject()
        // version 3 = post-2026-07-17 action-first shape (composite_v3
        //  sole score; r_type/ScorerV2/v2_* fields removed).
        root.put("version", 3)
        root.put("description", "Kotlin eval results — calls real ToolUseLoop + LlmClient")
        root.put("ground_truth", config.groundTruth.name)
        root.put("img_dir", config.imgDir.path)
        root.put("limit", config.limit)
        root.put("resize", config.resize)
        root.put("quality", config.quality)
        // composite_v3 is the canonical action-first score.  run_regression.sh /
        //  check_regression.py compare against overall_composite_v3.
        val overallV3 = if (results.isNotEmpty()) {
            results.map { it["composite_v3"] as Double }.average()
        } else 0.0
        root.put("overall_composite_v3", overallV3)
        // Per-component averages (top-level keys for the baseline
        //  checkers' per-component threshold checks).
        if (results.isNotEmpty()) {
            root.put("overall_v3_actions",
                results.map { it["v3_actions"] as Double }.average())
            root.put("overall_v3_text",
                results.map { it["v3_text"] as Double }.average())
            root.put("overall_v3_inputs",
                results.map { it["v3_inputs"] as Double }.average())
            // Over-fire rate: fraction of fixtures where the LLM emitted
            //  chips despite GT expecting none.  Only meaningful on the
            //  `none` suite (else expected is non-empty → never over-fires),
            //  but harmless to report everywhere.
            val overFire = results.count { it["over_fired"] as Boolean }
                .toDouble() / results.size
            root.put("overall_over_fire_rate", overFire)
        } else {
            root.put("overall_v3_actions", 0.0)
            root.put("overall_v3_text", 0.0)
            root.put("overall_v3_inputs", 0.0)
            root.put("overall_over_fire_rate", 0.0)
        }
        root.put("fixture_count", results.size)
        val perCategory = JSONObject()
        for ((cat, avg) in categoryAvgs) {
            perCategory.put(cat, avg)
        }
        root.put("per_category", perCategory)
        val fixtures = JSONArray()
        for (r in results) {
            val o = JSONObject()
            o.put("id", r["id"])
            o.put("category", r["category"])
            o.put("composite_v3", r["composite_v3"] as Double)
            o.put("v3_actions", r["v3_actions"] as Double)
            o.put("v3_text", r["v3_text"] as Double)
            o.put("v3_inputs", r["v3_inputs"] as Double)
            o.put("over_fired", r["over_fired"] as Boolean)
            // Raw LLM proposal + GT expected actions + raw content /
            //  details, kept so post-hoc scorer experiments can re-cut
            //  the score without re-running the LLM.
            o.put("emitted_action_ids", r["emitted_action_ids"] as JSONArray)
            o.put("expected_actions", r["expected_actions"] as JSONArray)
            o.put("details_count", r["details_count"] as Int)
            o.put("content_len", r["content_len"] as Int)
            o.put("raw_content", r["raw_content"] as String)
            o.put("raw_details", r["raw_details"] as JSONArray)
            fixtures.put(o)
        }
        root.put("fixtures", fixtures)
        file.writeText(root.toString(2))
        println("wrote ${results.size} fixture results to ${file.path}")
    }

    /** r_text — verbatim OCR fidelity.  Hit-rate against GT
     *  `expected_description_keywords` + `expected_details` values,
     *  matched via [hybridMatch] against the union haystack (content
     *  + details[].value).  Both annotations absent → 1.0 (no
     *  penalty for un-annotated fixtures).  Extracted from the former
     *  `scoreRound2` text half when the legacy composite was retired
     *  (2026-07-15). */
    private fun scoreRound2Text(
        outcome: ToolUseLoop.Outcome,
        scene: JSONObject,
    ): Double {
        val bubble = (outcome as? ToolUseLoop.Outcome.Bubble)?.bubble
            ?: (outcome as? ToolUseLoop.Outcome.PendingUserInput)?.placeholder
        val content = bubble?.detail.orEmpty()
        val details = bubble?.details.orEmpty()

        val expectedKeywords = scene.optJSONArray("expected_description_keywords")
        val expectedDetails = scene.optJSONArray("expected_details")

        // Union haystack: model can put verbatim text in `content` OR in
        // any `details[].value` row — both count.  The 50/50 average of
        // textScore/detailScore below pools both, so a hit anywhere in
        // the model's output registers equally.
        val haystack = normalize(
            content + " " + details.joinToString(" ") { it.value }
        )

        // Text keyword hit rate via [hybridMatch] (fuzzyMatch + ≥0.67
        // char-overlap fallback) so near-correct transcriptions count.
        var textScore = 0.0
        var denom = 0
        if (expectedKeywords != null && expectedKeywords.length() > 0) {
            val hits = (0 until expectedKeywords.length()).count { i ->
                val kw = expectedKeywords.getString(i)
                if (kw.isEmpty()) return@count true
                hybridMatch(haystack, kw)
            }
            textScore = hits.toDouble() / expectedKeywords.length()
            denom++
        }
        // Detail hit rate — match on VALUE only (GT uses positional
        // labels, model uses semantic ones; value is the OCR signal).
        if (expectedDetails != null && expectedDetails.length() > 0) {
            var hits = 0
            for (i in 0 until expectedDetails.length()) {
                val exp = expectedDetails.getJSONObject(i)
                val eValue = normalize(exp.optString("value", ""))
                if (eValue.isEmpty()) continue
                if (hybridMatch(haystack, eValue)) hits++
            }
            val detailScore = hits.toDouble() / expectedDetails.length()
            textScore = if (denom > 0) (textScore + detailScore) / 2.0 else detailScore
            denom++
        }
        if (denom == 0) textScore = 1.0
        return textScore
    }

    /**
     * Score-scoring normalizer.  Closes the 0.3+ gap between strict and
     * fuzzy r2_text by collapsing the noisiest Unicode variants the
     * model produces differently from GT:
     *  - NFKC folds fullwidth / compatibility forms (「（店）」 ↔ "(店)")
     *  - quote / colon variants → ASCII
     *  - all whitespace → single ASCII space
     *
     * Does NOT do synonyms, simplification, or traditional/simplified
     * conversion — those would change what "correct" means.
     */
    private fun normalize(s: String): String {
        if (s.isEmpty()) return s
        var n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        n = n.replace(Regex("[‘’“”「」『』]"), "'")
        n = n.replace('：', ':')  // fullwidth colon ：
        n = n.replace(Regex("\\s+"), " ")
        return n.trim().lowercase()
    }

    /**
     * Bidirectional normalized contains.  Returns true iff [needle]
     * (post-normalize) appears in [hay] OR [hay] appears in [needle]
     * — comparing both with and without internal whitespace.  The
     * no-whitespace pass catches "建国路 100号" vs "建国路100号"
     * which the plain pass misses (neither contains the other once
     * normalized, because of a single space).
     *
     * The reverse direction matters when the model produces a value
     * longer than the GT (e.g. model writes "品名: 工夫红茶 250g" and
     * GT expects "工夫红茶 250g").  Guarded by length to avoid a
     * single-char needle matching every long answer.
     */
    private fun fuzzyMatch(hay: String, needle: String): Boolean {
        if (needle.isEmpty()) return true
        if (hay.isEmpty()) return false
        val n = normalize(needle)
        val h = normalize(hay)
        if (n in h) return true
        if (h in n && n.length >= 2) return true
        // Whitespace-insensitive fallback.
        val nNoWs = n.replace(" ", "")
        val hNoWs = h.replace(" ", "")
        if (nNoWs.isEmpty() || hNoWs.isEmpty()) return false
        if (nNoWs in hNoWs) return true
        if (hNoWs in nNoWs && nNoWs.length >= 2) return true
        return false
    }

    /**
     * Two-stage match.  Tries [fuzzyMatch] first (bidirectional
     * substring contains), then falls back to a ≥[CHAR_OVERLAP_THRESHOLD]
     * char-overlap ratio (mirrors Python `eval_rctw_v2._hybrid_match`).
     *
     * Without the char-overlap fallback, strict substring misses many
     * near-correct Chinese transcriptions where the model split a
     * multi-character token with whitespace ("建国路100号" vs
     * "建国路 100 号").  Python aligned4 found this lifted r2_text
     * by +0.021 on @100 with no over-credit risk at 0.67.
     */
    private fun hybridMatch(hay: String, needle: String): Boolean {
        if (fuzzyMatch(hay, needle)) return true
        val n = normalize(needle).replace(" ", "")
        if (n.isEmpty()) return true
        val h = normalize(hay).replace(" ", "")
        if (h.isEmpty()) return false
        val nChars = n.toSet()
        if (nChars.isEmpty()) return true
        if (nChars.size == 1) return nChars.first() in h
        val present = nChars.count { it in h }
        return present.toDouble() / nChars.size >= CHAR_OVERLAP_THRESHOLD
    }
}
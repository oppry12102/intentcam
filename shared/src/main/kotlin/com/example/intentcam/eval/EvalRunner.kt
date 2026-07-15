package com.example.intentcam.eval

import com.example.intentcam.ActionInputSpec
import com.example.intentcam.Bubble
import com.example.intentcam.CapturedFrame
import com.example.intentcam.ImagePipeline
import com.example.intentcam.IntentFamily
import com.example.intentcam.IntentRegistry
import com.example.intentcam.LlmClient
import com.example.intentcam.ToolRegistry
import com.example.intentcam.ToolUseLoop
import com.example.intentcam.encodeThumbnail
import com.example.intentcam.registerDefaultIntents
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
    // [2026-07-10] Intent registry built before tools so the emit_bubble
    //  schema enum and the system prompt's type list see the same set
    //  of ids the orchestrator fallbacks point at.  The same registry
    //  also drives the A2 family-based type scoring below.
    private val intentRegistry = IntentRegistry()
        .also { registerDefaultIntents(it) }
    private val registry = ToolRegistry().also { it.registerDefaultTools(intentRegistry) }
    // [2026-07-13] The model can only emit `action_ids` that are
    //  enumerated in the system prompt's `actions ⊆ {...}` block.
    //  Mirror the Android app's default registry by id so the eval
    //  cycle sees the same action-id vocabulary as prod.  A registry
    //  type isn't needed here — we score the bubble's raw proposal
    //  (`llmProposedActions`) directly against the ground truth, not
    //  the resolver-filtered `actions` list, so a disabled / future
    //  action change doesn't silently move the recall number.
    //  Keep in lockstep with `registerDefaultActions` in `app/`.
    private val defaultActionIds = listOf(
        "open_in_maps",   // location → maps
        "dial_number",    // [2026-07-13] phone → system dialer (Phase A)
        "scan_to_pay",    // [2026-07-13] payment_qr → guidance Toast (Phase B)
        "redact_id",      // [2026-07-13] id_document → guidance Toast (Phase B)
        "share",          // [2026-07-15] unified share-text action (was
                          //   copy_listing/save_posting/copy_warning/
                          //   copy_menu/copy_hours/copy_promo)
    )

    /** [2026-07-15] Eval-side parser-mirror registry.  Used by the
     *  [markValidated] callback passed to ToolUseLoop.runCycle so
     *  eval-side ScorerV2 sees populated `validatedInputs` /
     *  `pendingInputs` fields (parity with prod's
     *  `ActionOrchestrator.markValidatedInputs`).
     *
     *  Mirrors the production `InputParsers` regex set
     *  (`app/.../ActionDecl.kt`) so a bubble that's "Complete" in
     *  prod is also Complete in eval.  See ScorerV2's inputSatisfied
     *  for the same set — both stay in lockstep with the canonical
     *  `ACTION_REQUIRED_INPUTS` table in
     *  `scripts/migrate_gt_v2_to_v3.py`.
     *
     *  Returned as `Map<actionId, List<ActionInputSpec>>` so it
     *  feeds directly into [projectInputsValidation]. */
    private fun defaultRequiredInputs(): Map<String, List<ActionInputSpec>> {
        val textParser: (Bubble) -> String? = { b ->
            if (b.title.isNotBlank() || b.detail.isNotBlank()) "present" else null
        }
        val locationParser: (Bubble) -> String? = { b ->
            if (b.title.isNotBlank() || b.detail.isNotBlank() ||
                b.details.any { it.value.isNotBlank() }
            ) "present" else null
        }
        val phoneParser: (Bubble) -> String? = { b ->
            val corpus = buildString {
                append(b.title).append('\n')
                append(b.detail).append('\n')
                b.details.forEach { d -> append(d.value).append('\n') }
            }
            // Match the prod regex chain: mobile → service → landline.
            if (MOBILE_REGEX.containsMatchIn(corpus)) "present"
            else if (SERVICE_REGEX.containsMatchIn(corpus)) "present"
            else if (LANDLINE_REGEX.containsMatchIn(corpus)) "present"
            else null
        }
        return mapOf(
            "dial_number"   to listOf(ActionInputSpec("phone_number", "手机号", phoneParser)),
            "open_in_maps"  to listOf(ActionInputSpec("query",        "地点或地址", locationParser)),
            "share"         to listOf(ActionInputSpec("text",         "正文", textParser)),
            // scan_to_pay / redact_id have no requiredInputs.
        )
    }

    private companion object {
        // Eval-side phone regex mirrors — kept here (not in ScorerV2)
        //  because ToolUseLoop's markValidated callback runs before
        //  ScorerV2.compute is called and needs the same set.  Same
        //  regex strings as ScorerV2.kt:119-121; sync by hand.
        private val MOBILE_REGEX = Regex("""1[3-9]\d{9}""")
        private val SERVICE_REGEX = Regex("""(?:400|800)[\s-]?\d{3,4}[\s-]?\d{3,4}""")
        private val LANDLINE_REGEX = Regex("""\b0?\d{3,4}[\s-]?\d{7,8}\b""")

        /** Char-overlap threshold for [hybridMatch]'s secondary
         *  fallback.  Matches Python aligned4's `_hybrid_match` ≥0.67
         *  ratio.  Below this threshold we declare no hit (avoid
         *  over-credit on trivial 1-2 char matches). */
        const val CHAR_OVERLAP_THRESHOLD = 0.67
    }
    // Phase 2b debug (2026-07-12): forward ToolUseLoop logs to stderr
    // when --debug-fixtures is set, otherwise stay silent like before.
    // The orchestrator's log callback runs in a hot loop so we don't
    // want unconditional stderr — only emit for the fixtures we care
    // about (currently the empty-bubble underperformers from the 4096
    // retest: rctw_01/03/10/18).
    private val debugFixtures: Set<String> = config.debugFixtures
    private val currentSceneId = AtomicReference<String?>(null)
    private val orchestrator = ToolUseLoop(
        client = client,
        registry = registry,
        intents = intentRegistry,
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
        // Phase 2b (2026-07-12): --fixtures restricts the run to a
        // curated id set, preserving GT order so jsonOut is
        // comparable to the 20-fixture baselines.  When the user
        // passes --fixtures together with --limit, --fixtures wins.
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
                "image-strategy=1-only (matches prod since 2026-07-06)"
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
            // image strategy, default since 2026-07-06).  The
            // ImageIO-based thumbnail/crop impls (installed by
            // EvalMain) are the JVM equivalent of BitmapFactory +
            // BitmapRegionDecoder.  Thumbnail sizing comes from
            // --resize/--quality.
            val rawBytes = imgPath.readBytes()
            // TEST 2026-07-12: mirror MAX_FULL_DIM 2048→4096
            // (matches FrameAnalyzer.kt:167 retest).  2026-07-14:
            // hardcoded literal replaced with `ImagePipeline`
            // shared constant — see shared/.../ImagePipeline.kt.
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
                    // [2026-07-13] Splice the registered action-id
                    //  vocabulary into the system prompt.  Empty
                    //  list = no LLM-proposal branch (legacy
                    //  applicability filter).
                    actionIds = defaultActionIds,
                    // [2026-07-15] Stamp validation state on the
                    //  bubble using shared-side parser mirrors.
                    //  Prod uses ActionOrchestrator.markValidatedInputs
                    //  (which closes over the Android-only
                    //  ActionRegistry); eval can't reach that
                    //  registry, so it walks the same parser
                    //  definitions via [InputsValidator].  Kept
                    //  lightweight — the regex chain runs once
                    //  per cycle on the bubble's already-parsed
                    //  text surface.
                    markValidated = { bubble ->
                        val specs = defaultRequiredInputs()
                        val (validated, pending) = projectInputsValidation(bubble, specs)
                        bubble.copy(validatedInputs = validated, pendingInputs = pending)
                    },
                )
            }
            if (outcome is ToolUseLoop.Outcome.Error) {
                System.err.println("  [DBG $sceneId] Outcome.Error: ${outcome.message.take(120)}")
            }

            val bubble = (outcome as? ToolUseLoop.Outcome.Bubble)?.bubble
                ?: (outcome as? ToolUseLoop.Outcome.PendingUserInput)?.placeholder
            // [2026-07-15 scoring redesign] composite_v2 is the sole
            //  score (legacy 0.45·r1+0.45·r2+0.10·r3 retired).  r_text +
            //  r_type are computed here (the latter needs the populated
            //  intentRegistry); r_actions (Jaccard) + r_inputs inside
            //  ScorerV2.
            val textScore = scoreRound2Text(outcome, scene)
            val typeScore = scoreRound2Type(outcome, scene)
            val scorerV2 = com.example.intentcam.eval.ScorerV2Result.compute(
                bubble = bubble,
                scene = scene,
                textScore = textScore,
                typeScore = typeScore,
            )
            // [2026-07-15 v4 — action-first scorer, dual-run] companion
            //  ScorerV3 runs side-by-side.  Reuses ScorerV2's already-
            //  computed r_text/r_actions/r_inputs (no double evaluation).
            //  Until IntentCam Dev signs off on the dual-run report,
            //  composite_v2 remains the regression-gating headline;
            //  composite_v3 is purely informational here.  See
            //  ~/.claude/plans/action-first-architecture.md §3 Step 1.
            val scorerV3 = com.example.intentcam.eval.ScorerV3Result.compute(
                bubble = bubble,
                scene = scene,
                textScore = scorerV2.text,
                inputsScore = scorerV2.inputs,
                actionsScore = scorerV2.actions,
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
            results.add(mapOf(
                "id" to sceneId,
                "category" to category,
                "composite_v2" to scorerV2.composite,
                "v2_type" to scorerV2.type,
                "v2_text" to scorerV2.text,
                "v2_actions" to scorerV2.actions,
                "v2_inputs" to scorerV2.inputs,
                // [2026-07-15 v4] dual-run side-channel; composite_v3
                //  is purely informational here until IntentCam Dev
                //  signs off on the dual-run report.
                "composite_v3" to scorerV3.composite,
                "v3_actions" to scorerV3.actions,
                "v3_text" to scorerV3.text,
                "v3_inputs" to scorerV3.inputs,
                "details_count" to detailsCount,
                "content_len" to contentLen,
                "raw_content" to rawContent,
                "raw_details" to rawDetails,
                "emitted_action_ids" to emittedActions,
                "expected_actions" to expectedActions,
            ))
            perCategory.getOrPut(category) { mutableListOf() }.add(scorerV2.composite)

            if (i < 5 || i == useScenes.size - 1 || (i + 1) % 10 == 0) {
                println(
                    "  [${i + 1}/${useScenes.size}] ${sceneId.padEnd(30)} " +
                        "cat=${category.padEnd(15)} " +
                        "v2[ t=${"%.2f".format(scorerV2.type)} tx=${"%.2f".format(scorerV2.text)} " +
                        "a=${"%.2f".format(scorerV2.actions)} i=${"%.2f".format(scorerV2.inputs)} " +
                        "c=${"%.2f".format(scorerV2.composite)} ] " +
                        "v3[ a=${"%.2f".format(scorerV3.actions)} " +
                        "c=${"%.2f".format(scorerV3.composite)} ]"
                )
            }
        }

        println()
        println("=".repeat(60))
        println("fixtures: ${results.size}")
        if (results.isNotEmpty()) {
            val overallV2 = results.map { it["composite_v2"] as Double }.average()
            println("average composite_v2: ${"%.3f".format(overallV2)}")
            // [2026-07-15 v4] dual-run side-channel — informational only
            //  until IntentCam Dev signs off on the dual-run report.
            //  See ~/.claude/plans/action-first-architecture.md §3 Step 1.
            val overallV3 = results.map { it["composite_v3"] as Double }.average()
            println("average composite_v3: ${"%.3f".format(overallV3)}")
            val avgType = results.map { it["v2_type"] as Double }.average()
            val avgText = results.map { it["v2_text"] as Double }.average()
            val avgActions = results.map { it["v2_actions"] as Double }.average()
            val avgInputs = results.map { it["v2_inputs"] as Double }.average()
            println(
                "v2 components: type=${"%.3f".format(avgType)} text=${"%.3f".format(avgText)} " +
                    "actions=${"%.3f".format(avgActions)} inputs=${"%.3f".format(avgInputs)}"
            )
            val avgV3Actions = results.map { it["v3_actions"] as Double }.average()
            val avgV3Text = results.map { it["v3_text"] as Double }.average()
            val avgV3Inputs = results.map { it["v3_inputs"] as Double }.average()
            println(
                "v3 components: actions=${"%.3f".format(avgV3Actions)} " +
                    "text=${"%.3f".format(avgV3Text)} inputs=${"%.3f".format(avgV3Inputs)}"
            )
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
        // version 2 = post-2026-07-15 scoring redesign (composite_v2
        //  sole score; legacy composite / r1 / r2_type / r3 removed).
        root.put("version", 2)
        root.put("description", "Kotlin eval results — calls real ToolUseLoop + LlmClient (post-2026-07-06 refactor)")
        root.put("ground_truth", config.groundTruth.name)
        root.put("img_dir", config.imgDir.path)
        root.put("limit", config.limit)
        root.put("resize", config.resize)
        root.put("quality", config.quality)
        // composite_v2 is the canonical score.  run_regression.sh /
        //  check_regression.py compare against overall_composite_v2.
        val overallV2 = if (results.isNotEmpty()) {
            results.map { it["composite_v2"] as Double }.average()
        } else 0.0
        root.put("overall_composite_v2", overallV2)
        // [2026-07-15 v4] dual-run side-channel.  Until IntentCam Dev
        //  signs off on the v4 plan's regression-stability gate
        //  (composite_v2 PASS + composite_v3 |Δ| ≤ 0.03 week-over-week),
        //  overall_composite_v3 is purely informational.  After sign-off,
        //  `check_regression.py` flips its read target to this field.
        val overallV3 = if (results.isNotEmpty()) {
            results.map { it["composite_v3"] as Double }.average()
        } else 0.0
        root.put("overall_composite_v3", overallV3)
        // Per-component averages (top-level keys for the baseline
        //  checkers' per-component threshold checks).
        if (results.isNotEmpty()) {
            root.put("overall_v2_type",
                results.map { it["v2_type"] as Double }.average())
            root.put("overall_v2_text",
                results.map { it["v2_text"] as Double }.average())
            root.put("overall_v2_actions",
                results.map { it["v2_actions"] as Double }.average())
            root.put("overall_v2_inputs",
                results.map { it["v2_inputs"] as Double }.average())
            // [2026-07-15 v4] v3 component aggregates
            root.put("overall_v3_actions",
                results.map { it["v3_actions"] as Double }.average())
            root.put("overall_v3_text",
                results.map { it["v3_text"] as Double }.average())
            root.put("overall_v3_inputs",
                results.map { it["v3_inputs"] as Double }.average())
        } else {
            root.put("overall_v2_type", 0.0)
            root.put("overall_v2_text", 0.0)
            root.put("overall_v2_actions", 0.0)
            root.put("overall_v2_inputs", 0.0)
            root.put("overall_v3_actions", 0.0)
            root.put("overall_v3_text", 0.0)
            root.put("overall_v3_inputs", 0.0)
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
            o.put("composite_v2", r["composite_v2"] as Double)
            o.put("v2_type", r["v2_type"] as Double)
            o.put("v2_text", r["v2_text"] as Double)
            o.put("v2_actions", r["v2_actions"] as Double)
            o.put("v2_inputs", r["v2_inputs"] as Double)
            // [2026-07-15 v4] dual-run side-channel — informational only
            //  during the dual-run window; canonical switch gated on
            //  IntentCam Dev sign-off (see ~/.claude/plans/action-first-
            //  architecture.md §3 Step 4).
            o.put("composite_v3", r["composite_v3"] as Double)
            o.put("v3_actions", r["v3_actions"] as Double)
            o.put("v3_text", r["v3_text"] as Double)
            o.put("v3_inputs", r["v3_inputs"] as Double)
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

    /** r_type — graded intent-type correctness against `bubble.type`:
     *  exact registered match 1.0 / same registered family 0.7 /
     *  both registered but wrong family 0.3 / empty·unknown 0.0.
     *  Dual-reads expected `expected_type` → `expected_top_intent_type`;
     *  family lookup via the populated [intentRegistry].  Extracted +
     *  regraded from the former `scoreRound2` type half when the
     *  legacy composite was retired (2026-07-15); replaces the old
     *  tautological `r_intent_derived`. */
    private fun scoreRound2Type(
        outcome: ToolUseLoop.Outcome,
        scene: JSONObject,
    ): Double {
        val bubble = (outcome as? ToolUseLoop.Outcome.Bubble)?.bubble
            ?: (outcome as? ToolUseLoop.Outcome.PendingUserInput)?.placeholder
        val type = bubble?.type
        // Schema dual-read: v3 suites author `expected_top_intent_type`;
        //  RCTW keeps the old `expected_type`.  Reading both keeps
        //  backward-compat.  Missing both → FALLBACK_ID ("info").
        val expectedType = scene.optString(
            "expected_type",
            scene.optString("expected_top_intent_type", IntentRegistry.FALLBACK_ID),
        )
        val observeFamily = intentRegistry.idsInFamily(IntentFamily.OBSERVE)
        val actFamily = intentRegistry.idsInFamily(IntentFamily.ACT_ON)
        val registeredIds = observeFamily + actFamily
        // `type` is `String?`.  Treat null and "" alike as "no intent
        //  emitted" → 0.0.
        val nonBlankType = type?.takeIf { it.isNotBlank() } ?: ""
        fun familyOf(id: String): IntentFamily? = when {
            id.isBlank() -> null
            id in observeFamily -> IntentFamily.OBSERVE
            id in actFamily -> IntentFamily.ACT_ON
            else -> null
        }
        // Strict unknown handling: exact-match credit only when the
        //  emitted type is actually registered — guards against an
        //  unregistered string accidentally matching an unregistered
        //  expected value.
        return when {
            nonBlankType.isEmpty() -> 0.0
            nonBlankType == expectedType && nonBlankType in registeredIds -> 1.0
            familyOf(nonBlankType) != null &&
                familyOf(nonBlankType) == familyOf(expectedType) -> 0.7
            nonBlankType in registeredIds -> 0.3
            else -> 0.0
        }
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
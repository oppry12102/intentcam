package com.example.intentcam.eval

import com.example.intentcam.CapturedFrame
import com.example.intentcam.IntentFamily
import com.example.intentcam.IntentRegistry
import com.example.intentcam.LlmClient
import com.example.intentcam.ToolRegistry
import com.example.intentcam.ToolUseLoop
import com.example.intentcam.encodeThumbnail
import com.example.intentcam.registerDefaultIntents
import com.example.intentcam.registerDefaultTools
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
    //  Keep in lockstep with `registerDefaultActions` in `app/`
    //  (`ActionRegistry.DEFAULT_ID = "view_details"`).
    private val defaultActionIds = listOf(
        "view_details",   // no-op chip (DEFAULT_ID in app/)
        "open_in_maps",   // location → maps
        "dial_number",    // [2026-07-13] phone → system dialer (Phase A)
        "copy_listing",   // [2026-07-13] real_estate_rental → share (Phase B)
        "save_posting",   // [2026-07-13] recruit_hiring → share (Phase B)
        "scan_to_pay",    // [2026-07-13] payment_qr → guidance Toast (Phase B)
        "redact_id",      // [2026-07-13] id_document → guidance Toast (Phase B)
        "copy_warning",   // [2026-07-12] warning_safety → share (Phase G)
        "copy_menu",      // [2026-07-12] menu_food → share (Phase G)
        "copy_hours",     // [2026-07-12] hours_schedule → share (Phase G)
    )
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
        val scenes = gt.optJSONArray("scenes") ?: JSONArray()
        val sceneList = (0 until scenes.length()).map { scenes.getJSONObject(it) }
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
            // (matches FrameAnalyzer.kt:167 retest).
            val fullRes = encodeThumbnail(rawBytes, maxDim = 4096, quality = 95) ?: rawBytes
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
                )
            }
            if (outcome is ToolUseLoop.Outcome.Error) {
                System.err.println("  [DBG $sceneId] Outcome.Error: ${outcome.message.take(120)}")
            }

            val r1 = scoreRound1(outcome, scene)
            val r2 = scoreRound2(outcome, scene)
            val r3 = scoreRound3(outcome, scene)
            // [2026-07-13] Composite gains a third dimension for
            //  action_recall.  Weights chosen so r1+r2 stays dominant
            //  (0.90) and r3 is a soft 0.10 nudge.  With only 20/100
            //  location-class fixtures having ground-truth
            //  expected_actions, r3 is mostly a 1.0 floor right now
            //  — the real signal will appear once we annotate more
            //  fixtures (or pay attention to over-proposals as a
            //  separate negative signal).
            val composite = 0.45 * r1.first + 0.45 * r2.first + 0.10 * r3.first
            // Diagnostic side-metrics (do NOT feed composite — kept
            // comparable to the pre-instrumentation baseline).  These
            // separate "model genuinely misread the text" from
            // "strict substring scorer zeroed a near-correct answer",
            // and expose whether the model actually populated details.
            val bubble = (outcome as? ToolUseLoop.Outcome.Bubble)?.bubble
                ?: (outcome as? ToolUseLoop.Outcome.PendingUserInput)?.placeholder
            val detailsCount = bubble?.details?.size ?: 0
            val contentLen = bubble?.detail?.length ?: 0
            val r2TextFuzzy = scoreRound2TextFuzzy(outcome, scene)
            // Stash raw content + details rows so future scorer
            // experiments can be dry-run re-scored against saved
            // outputs (no LLM re-run).  Keeps the per-fixture JSON
            // ~5-10× bigger but still well under 1 MB for 100 fixtures.
            val rawContent = bubble?.detail.orEmpty()
            val rawDetails = JSONArray()
            bubble?.details?.forEach { d ->
                rawDetails.put(JSONObject()
                    .put("kind", d.kind)
                    .put("label", d.label)
                    .put("value", d.value))
            }
            results.add(mapOf(
                "id" to sceneId,
                "category" to category,
                "composite" to composite,
                "r1" to r1.first,
                "r2_text" to r2.first,
                "r2_type" to r2.second,
                "r2_text_fuzzy" to r2TextFuzzy,
                "r3_actions" to r3.first,
                "details_count" to detailsCount,
                "content_len" to contentLen,
                "raw_content" to rawContent,
                "raw_details" to rawDetails,
                "r1_details" to r1.third,
                "emitted_action_ids" to r3.second.optJSONArray("emitted_action_ids"),
                "expected_actions" to r3.second.optJSONArray("expected_actions"),
            ))
            perCategory.getOrPut(category) { mutableListOf() }.add(composite)

            val picked = r1.third.optString("picked_tool", "?")
            if (i < 5 || i == useScenes.size - 1 || (i + 1) % 10 == 0) {
                println(
                    "  [${i + 1}/${useScenes.size}] ${sceneId.padEnd(30)} " +
                        "cat=${category.padEnd(15)} picked=$picked " +
                        "r1=${"%.2f".format(r1.first)} r2_text=${"%.2f".format(r2.first)} " +
                        "r2_type=${"%.2f".format(r2.second)} composite=${"%.2f".format(composite)}"
                )
            }
        }

        println()
        println("=".repeat(60))
        println("fixtures: ${results.size}")
        if (results.isNotEmpty()) {
            val overall = results.map { it["composite"] as Double }.average()
            println("average composite: ${"%.3f".format(overall)}")
            // Diagnostic aggregates — not part of composite.
            val r2Strict = results.map { it["r2_text"] as Double }.average()
            val r2Fuzzy = results.map { it["r2_text_fuzzy"] as Double }.average()
            val avgDetails = results.map { (it["details_count"] as Int).toDouble() }.average()
            val emptyDetails = results.count { (it["details_count"] as Int) == 0 }
            val avgContentLen = results.map { (it["content_len"] as Int).toDouble() }.average()
            // [2026-07-13] r3_actions aggregate.  The 1.0 floor for
            //  un-annotated fixtures makes this metric look
            //  optimistic when only a few fixtures carry
            //  expected_actions; pass --fixtures to a location-only
            //  subset for a real action-recall number.
            val r3Avg = results.map { it["r3_actions"] as Double }.average()
            // Recall broken down on the subset that has ground-truth
            // actions: gives a cleaner signal of the model's
            //  hit-rate when there's something to hit.
            val actionableFixtures = results.count {
                val ea = it["expected_actions"] as JSONArray
                ea.length() > 0
            }
            val r3OnAnnotated = if (actionableFixtures > 0) {
                results.filter {
                    val ea = it["expected_actions"] as JSONArray
                    ea.length() > 0
                }.map { it["r3_actions"] as Double }.average()
            } else 0.0
            println(
                "r2_text strict=${"%.3f".format(r2Strict)} " +
                    "fuzzy=${"%.3f".format(r2Fuzzy)} (gap=${"%.3f".format(r2Fuzzy - r2Strict)} = scorer strictness)"
            )
            println(
                "details: avg ${"%.1f".format(avgDetails)} rows/fixture, " +
                    "$emptyDetails/${results.size} empty | content avg ${"%.0f".format(avgContentLen)} chars"
            )
            println(
                "r3_actions=${"%.3f".format(r3Avg)} " +
                    "($actionableFixtures annotated fixtures, " +
                    "on-annotated avg ${"%.3f".format(r3OnAnnotated)})"
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
        root.put("version", 1)
        root.put("description", "Kotlin eval results — calls real ToolUseLoop + LlmClient (post-2026-07-06 refactor)")
        root.put("ground_truth", config.groundTruth.name)
        root.put("img_dir", config.imgDir.path)
        root.put("limit", config.limit)
        root.put("resize", config.resize)
        root.put("quality", config.quality)
        val overall = if (results.isNotEmpty()) {
            results.map { it["composite"] as Double }.average()
        } else 0.0
        root.put("overall_composite", overall)
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
            o.put("composite", r["composite"] as Double)
            o.put("r1", r["r1"] as Double)
            o.put("r2_text", r["r2_text"] as Double)
            o.put("r2_type", r["r2_type"] as Double)
            o.put("r2_text_fuzzy", r["r2_text_fuzzy"] as Double)
            // [2026-07-13] r3 (action_recall) + raw LLM proposal +
            //  ground-truth expected actions so post-hoc scorer
            //  experiments can re-cut the score without re-running
            //  the LLM.
            o.put("r3_actions", r["r3_actions"] as Double)
            o.put("emitted_action_ids", r["emitted_action_ids"] as JSONArray)
            o.put("expected_actions", r["expected_actions"] as JSONArray)
            o.put("details_count", r["details_count"] as Int)
            o.put("content_len", r["content_len"] as Int)
            o.put("raw_content", r["raw_content"] as String)
            o.put("raw_details", r["raw_details"] as JSONArray)
            val details = r["r1_details"] as JSONObject
            o.put("picked_tool", details.optString("picked_tool", "?"))
            o.put("has_text", details.optBoolean("has_text", false))
            fixtures.put(o)
        }
        root.put("fixtures", fixtures)
        file.writeText(root.toString(2))
        println("wrote ${results.size} fixture results to ${file.path}")
    }

    private companion object {
        /** Char-overlap threshold for [hybridMatch]'s secondary
         *  fallback.  Mirrors Kotlin's own `scoreRound2TextFuzzy`
         *  ≥0.67 ratio and Python aligned4's `_hybrid_match`.  Below
         *  this threshold we declare no hit (avoid over-credit on
         *  trivial 1-2 char matches). */
        const val CHAR_OVERLAP_THRESHOLD = 0.67
    }

    private fun scoreRound1(
        outcome: ToolUseLoop.Outcome,
        scene: JSONObject,
    ): Triple<Double, JSONObject, JSONObject> {
        val empty = JSONObject()
        val r1Details = JSONObject()
        // ToolUseLoop.Outcome exposes firstToolName — the first
        // non-final tool the model invoked (zoom_in / compare_text
        // if it did reconnaissance, emit_bubble if it
        // went straight to the final summary, null if the model just
        // emitted text without any tool call).  Score the choice
        // directly so the metric reflects "did the model think
        // before answering?" instead of collapsing to "did the
        // cycle end with a bubble?".
        //
        // The penalty for skipping reconnaissance depends on whether
        // the fixture has text content: for text-heavy fixtures
        // (ground truth lists expected_description_keywords /
        // expected_details) the model really should zoom_in to
        // verify text; for non-text fixtures the 5
        // round-1 images already let the model answer correctly.
        //
        // End-cloud (2026-07-07+): the round-1 OCR hint is the
        // verbatim character source — a model that trusts OCR enough
        // to skip zoom_in and still nails the text is doing the
        // right thing, not lazy.  Bumped skipReconScore from 0.5 to
        // 0.85 to reflect this; we keep a small penalty so a model
        // that skips recon AND drops text still loses points (r2
        // covers the "did you actually get the text right" half).
        val firstTool = outcome.firstToolName
        val hasText = scene.optJSONArray("expected_description_keywords")?.length()?.let { it > 0 } == true ||
            scene.optJSONArray("expected_details")?.length()?.let { it > 0 } == true
        val skipReconScore = if (hasText) 0.85 else 1.0
        val pickScore: Double
        val pickedLabel: String
        when {
            outcome is ToolUseLoop.Outcome.Error -> {
                pickScore = 0.0
                pickedLabel = "(error)"
            }
            firstTool == null -> {
                pickScore = skipReconScore
                pickedLabel = "(none)"
            }
            firstTool == "emit_bubble" -> {
                pickScore = skipReconScore
                pickedLabel = "emit_bubble"
            }
            firstTool == "zoom_in" || firstTool == "compare_text" || firstTool == "extract_text" -> {
                pickScore = 1.0
                pickedLabel = firstTool
            }
            else -> {
                pickScore = 0.7
                pickedLabel = firstTool
            }
        }
        r1Details.put("picked_tool", pickedLabel)
        r1Details.put("has_text", hasText)
        val inputOk = 1.0
        val composite = 0.70 * pickScore + 0.30 * inputOk
        return Triple(composite, empty, r1Details)
    }

    private fun scoreRound2(
        outcome: ToolUseLoop.Outcome,
        scene: JSONObject,
    ): Pair<Double, Double> {
        val bubble = (outcome as? ToolUseLoop.Outcome.Bubble)?.bubble
            ?: (outcome as? ToolUseLoop.Outcome.PendingUserInput)?.placeholder
        val type = bubble?.type
        val content = bubble?.detail.orEmpty()
        val details = bubble?.details.orEmpty()

        val expectedKeywords = scene.optJSONArray("expected_description_keywords")
        val expectedDetails = scene.optJSONArray("expected_details")

        // Union haystack: model can put verbatim text in `content` OR in
        // any `details[].value` row — both count.  This fixes the failure
        // pattern where the model fills 10+ details rows with verbatim
        // text but skips the content field (the 3200+4096 strict-architecture
        // "details-full content-empty" mode, see eval-option-c-ship
        // memory).  The 50/50 average of textScore/detailScore below
        // pools both, so a hit anywhere in the model's output registers
        // equally.  Net effect on @100: composite 0.881 → 0.900
        // (+0.019, 16W/2L/82T, no LLM variance).
        val haystack = normalize(
            content + " " + details.joinToString(" ") { it.value }
        )

        // Text keyword hit rate.  Uses [hybridMatch] (fuzzyMatch +
        // ≥0.67 char-overlap fallback) so that near-correct transcriptions
        // like "建国路 100 号" matching GT "建国路100号" count as hits —
        // mirrors Python aligned4's `_hybrid_match`.  See hybridMatch's
        // docstring for the rationale.  Matches against the union
        // haystack (content + details values).
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
        // Detail hit rate.  We deliberately match on VALUE only, not on
        // label/kind.  The GT uses positional labels ("区域1", "招牌",
        // ...) or generic types while the model writes semantic ones
        // ("品牌", "价格", "营业时间") — matching on either would zero
        // hits that are textually correct.  Value is the OCR signal; if
        // the model emitted the same text the GT expects (post
        // normalize), that's a hit regardless of how it labelled the row.
        // Also matches against the union haystack, so GT detail values
        // present in `content` (not just in `details`) count.
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

        // Type match — three-way partial credit instead of binary:
        //   - right bucket                          → 1.0
        //   - same family, different id             → 1.0  (v1.3, 2026-07-10;
        //                                              generalizes to all
        //                                              IntentFamilies, not
        //                                              just OBSERVE)
        //   - any registered type, wrong family     → 0.5
        //   - empty / unknown                       → 0.0
        //
        // Why info ↔ location is full credit: signs / storefronts / 商户
        // 招牌 (e.g. "大懒人冒菜", "FJ儿童业态") are BOTH "read the
        // sign" (info) AND "find this place" (location).  Previously the
        // partial-credit floor held 6/20 fixtures at composite 0.82 in
        // v12c even with 100% keyword match (composite capped because
        // r2_type = 0.5 floored r2 = 0.625, then 0.5 × r1 + 0.5 × 0.625
        // ≈ 0.82).  Promoting to 1.0 unblocks those; solve stays
        // partial because "solve this problem" is a different intent
        // class from "read what's here".
        //
        // 9/100 fixtures in 2026-07-07 1-only @100 v2 regressed solely
        // because the model picked a non-info type for fixtures the GT
        // locks at "info", dropping composite by 0.25 each.  The
        // partial credit (now full for same-family pair) restores that
        // without inflating true matches.
        //
        // [2026-07-10] Family lookup now consults the registered
        // IntentRegistry instead of the previous `setOf("info",
        // "location")` literal.  Behavior on the 3 default intents is
        // unchanged; adding a 4th ACT_ON intent in the future will
        // automatically pair it with "solve" at 1.0 without editing
        // this scorer.
        //
        // [2026-07-12] Schema dual-read: per-image GT authoring
        //  migrated to `expected_top_intent_type` for the Phase B
        //  fixtures (pii20 / phone_20 / location_20 / real) while
        //  RCTW-171 keeps the old `expected_type` field.  Reading
        //  both keeps backward-compat.  Fixes the systematic
        //  r2_type=0.5 issue on every non-RCTW fixture (the LLM
        //  emits the correct family but `expected_type` was missing,
        //  so the eval fell through to FALLBACK_ID="info" forcing
        //  cross-family partial credit).
        val expectedType = scene.optString(
            "expected_type",
            scene.optString("expected_top_intent_type", IntentRegistry.FALLBACK_ID),
        )
        val observeFamily = intentRegistry.idsInFamily(IntentFamily.OBSERVE)
        val actFamily = intentRegistry.idsInFamily(IntentFamily.ACT_ON)
        val registeredIds = observeFamily + actFamily
        // `type` is `String?` from JSON lookups.  Treat null and "" the
        // same as "no intent was emitted" — both score 0.0.
        val nonBlankType = type?.takeIf { it.isNotBlank() } ?: ""
        // Look up the family of an id (null if not registered or empty).
        fun familyOf(id: String): IntentFamily? = when {
            id.isBlank() -> null
            id in observeFamily -> IntentFamily.OBSERVE
            id in actFamily -> IntentFamily.ACT_ON
            else -> null
        }
        val typeScore = when {
            nonBlankType.isEmpty() -> 0.0
            nonBlankType == expectedType -> 1.0
            familyOf(nonBlankType) != null && familyOf(nonBlankType) == familyOf(expectedType) -> 1.0
            nonBlankType in registeredIds -> 0.5
            else -> 0.0
        }

        val r2 = 0.50 * textScore + 0.50 * typeScore
        return Pair(r2, typeScore)
    }

    /**
     * [2026-07-13] Round 3 — action_recall.  Score the model's
     * ability to *propose* the right `action_ids` against the
     * ground truth's `expected_actions` list.
     *
     * Scoring rule (intentionally simple for v1):
     *   - expected_actions absent / empty list  → 1.0 (no signal;
     *     we don't penalize fixtures that don't exercise this
     *     dimension — the surface area is small right now)
     *   - otherwise                             → |predicted ∩ expected|
     *                                             / |expected|
     *     ("recall" — every expected id showed up at least once in
     *     the proposal).  Precision is not penalized: over-proposing
     *     `[open_in_maps, view_details]` on a fixture that expects
     *     only `[open_in_maps]` still scores 1.0.  When the surface
     *     grows beyond 2 actions and we have more ground truth,
     *     add a precision term to break ties.
     *
     * Reads `bubble.llmProposedActions` (the raw list the model
     * emitted in `emit_bubble.action_ids`) rather than the
     * resolver-filtered `bubble.actions`.  Decouples the metric
     * from SettingsStore-enabled changes so the score stays
     * stable across user-toggle experiments.
     *
     * Returns: Pair(recallScore, detail).  The detail is a
     * JSONObject carrying `emitted_action_ids` + `expected_actions`
     * arrays so the JSON report can dump them per-fixture.
     */
    private fun scoreRound3(
        outcome: ToolUseLoop.Outcome,
        scene: JSONObject,
    ): Pair<Double, JSONObject> {
        val bubble = (outcome as? ToolUseLoop.Outcome.Bubble)?.bubble
            ?: (outcome as? ToolUseLoop.Outcome.PendingUserInput)?.placeholder
        val proposed: List<String> = bubble?.llmProposedActions.orEmpty()
        val expectedArr = scene.optJSONArray("expected_actions")
        val expected: List<String> = when {
            expectedArr == null -> emptyList()
            else -> (0 until expectedArr.length()).map { expectedArr.getString(it) }
        }
        val detail = JSONObject()
            .put("emitted_action_ids", JSONArray(proposed))
            .put("expected_actions", JSONArray(expected))
        if (expected.isEmpty()) {
            // No ground truth for this fixture — score as full
            // credit so it doesn't drag composite down.  Inverted
            // from "absent = 0"; the alternative would penalize
            // every non-annotated fixture into the floor on a
            // partial-annotation rollout.
            return Pair(1.0, detail)
        }
        if (proposed.isEmpty()) {
            // Expected something, got nothing — wrong.  Returning
            // 0.0 lets r3's 0.10 weight pull composite 0.10 lower
            // for these fixtures, which is the actionable signal.
            return Pair(0.0, detail)
        }
        val proposedSet = proposed.toSet()
        val hits = expected.count { it in proposedSet }
        return Pair(hits.toDouble() / expected.size, detail)
    }

    /**
     * Diagnostic-only variant of the r2 *text* score that counts an
     * expected keyword as a hit when a high fraction of its characters
     * appear in the model's answer, not only on exact substring
     * containment.  Compared against the strict [scoreRound2] text
     * score, the gap tells us how much of the r2_text plateau is the
     * scorer being brittle (near-correct Chinese transcriptions zeroed)
     * versus the model genuinely misreading the scene.  NOT part of the
     * composite — purely a measurement aid.
     */
    private fun scoreRound2TextFuzzy(
        outcome: ToolUseLoop.Outcome,
        scene: JSONObject,
    ): Double {
        val bubble = (outcome as? ToolUseLoop.Outcome.Bubble)?.bubble
            ?: (outcome as? ToolUseLoop.Outcome.PendingUserInput)?.placeholder
        // Pool everything the model produced: content + every details value.
        val hay = buildString {
            append(bubble?.detail.orEmpty())
            bubble?.details.orEmpty().forEach { append(' ').append(it.value) }
        }
        val hayNorm = normalize(hay)
        val expectedKeywords = scene.optJSONArray("expected_description_keywords")
            ?: return 1.0
        if (expectedKeywords.length() == 0) return 1.0
        var hits = 0.0
        for (i in 0 until expectedKeywords.length()) {
            val kw = expectedKeywords.getString(i)
            if (kw.isEmpty()) continue
            val kwNorm = normalize(kw)
            if (kwNorm in hayNorm) { hits += 1.0; continue }
            // Fraction of the keyword's characters present in the answer.
            val present = kwNorm.toSet().count { it in hayNorm }
            val ratio = present.toDouble() / kwNorm.toSet().size
            if (ratio >= 0.67) hits += ratio
        }
        return hits / expectedKeywords.length()
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
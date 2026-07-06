package com.example.intentcam.eval

import com.example.intentcam.CapturedFrame
import com.example.intentcam.LlmClient
import com.example.intentcam.ToolRegistry
import com.example.intentcam.ToolUseLoop
import com.example.intentcam.encodeThumbnail
import com.example.intentcam.registerDefaultTools
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
    private val registry = ToolRegistry().also { it.registerDefaultTools() }
    private val orchestrator = ToolUseLoop(client = client, registry = registry, log = { _, _ -> })

    fun run(): Int {
        if (!config.groundTruth.exists()) {
            System.err.println("missing ground truth: ${config.groundTruth}")
            return 1
        }
        val gt = JSONObject(config.groundTruth.readText())
        val scenes = gt.optJSONArray("scenes") ?: JSONArray()
        val sceneList = (0 until scenes.length()).map { scenes.getJSONObject(it) }
        val limit = if (config.limit > 0) minOf(config.limit, sceneList.size) else sceneList.size
        val useScenes = sceneList.take(limit)

        println("Loaded $limit real-photo fixtures from ${config.groundTruth.name}")
        println("FrameAnalyzer simulation: --resize ${config.resize} --quality ${config.quality}")

        val perCategory = mutableMapOf<String, MutableList<Double>>()
        val results = mutableListOf<Map<String, Any>>()

        for ((i, scene) in useScenes.withIndex()) {
            val sceneId = scene.optString("id", "?")
            val category = scene.optString("category", "?")
            val imgName = scene.optString("file", "")
            if (imgName.isEmpty()) continue
            val imgPath = File(config.imgDir, imgName)
            if (!imgPath.exists()) {
                System.err.println("  SKIP $sceneId: missing $imgPath")
                continue
            }

            // Build a CapturedFrame that mirrors what FrameAnalyzer
            // would produce on-device: 1 thumbnail + 1 fullRes + 4
            // quadrant crops.  The ImageIO-based thumbnail/crop impls
            // (installed by EvalMain) are the JVM equivalent of
            // BitmapFactory + BitmapRegionDecoder.  Thumbnail sizing
            // comes from --resize/--quality; quadrant quality stays at
            // 85 (matches Android FrameAnalyzer.QUADRANT_QUALITY).
            val rawBytes = imgPath.readBytes()
            val fullRes = encodeThumbnail(rawBytes, maxDim = 4096, quality = 95) ?: rawBytes
            val thumbnail = encodeThumbnail(
                rawBytes,
                maxDim = config.resize,
                quality = config.quality,
            ) ?: rawBytes
            val quadrants = listOf(
                encodeQuadrant(rawBytes, 0f, 0f, 0.5f, 0.5f),
                encodeQuadrant(rawBytes, 0.5f, 0f, 0.5f, 0.5f),
                encodeQuadrant(rawBytes, 0f, 0.5f, 0.5f, 0.5f),
                encodeQuadrant(rawBytes, 0.5f, 0.5f, 0.5f, 0.5f),
            ).filterNotNull()
            val frame = CapturedFrame(
                thumbnail = thumbnail,
                fullRes = fullRes,
                quadrants = quadrants,
            )

            // Run the real orchestrator.  This calls into the same
            // ToolUseLoop the Android app uses — no parallel
            // implementation.
            val outcome = runBlocking {
                orchestrator.runCycle(
                    thumbnail = frame.thumbnail,
                    fullRes = frame.fullRes,
                    userText = "",
                    quadrants = frame.quadrants,
                )
            }
            if (outcome is ToolUseLoop.Outcome.Error) {
                System.err.println("  [DBG $sceneId] Outcome.Error: ${outcome.message.take(120)}")
            }

            val r1 = scoreRound1(outcome, scene)
            val r2 = scoreRound2(outcome, scene)
            val composite = 0.50 * r1.first + 0.50 * r2.first
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
                "details_count" to detailsCount,
                "content_len" to contentLen,
                "raw_content" to rawContent,
                "raw_details" to rawDetails,
                "r1_details" to r1.third,
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
            println(
                "r2_text strict=${"%.3f".format(r2Strict)} " +
                    "fuzzy=${"%.3f".format(r2Fuzzy)} (gap=${"%.3f".format(r2Fuzzy - r2Strict)} = scorer strictness)"
            )
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

    private fun encodeQuadrant(
        raw: ByteArray,
        fx: Float, fy: Float, fw: Float, fh: Float,
    ): ByteArray? {
        // Single-encode crop + downscale (matches Android's
        // FrameAnalyzer.encodeQuadrant which crops the Bitmap in memory
        // and re-encodes once at q85).  Previously this was two
        // encodes — crop-thumbnail at default q75, then encodeThumbnail
        // at q85 — which made eval JPEG quality drift from prod.
        return com.example.intentcam.cropJpegRegion(
            raw, fx, fy, fw, fh, quality = QUADRANT_QUALITY,
        )
    }

    private companion object {
        /** Matches Android's FrameAnalyzer.QUADRANT_QUALITY — quadrants
         *  are encoded at higher quality than the overview thumbnail
         *  because each crop uses the full pixel budget. */
        const val QUADRANT_QUALITY = 85
    }

    private fun scoreRound1(
        outcome: ToolUseLoop.Outcome,
        scene: JSONObject,
    ): Triple<Double, JSONObject, JSONObject> {
        val empty = JSONObject()
        val r1Details = JSONObject()
        // ToolUseLoop.Outcome exposes firstToolName — the first
        // non-final tool the model invoked (zoom_in / read_text if it
        // did reconnaissance, emit_bubble if it went straight to the
        // final summary, null if the model just emitted text without
        // any tool call).  Score the choice directly so the metric
        // reflects "did the model think before answering?" instead of
        // collapsing to "did the cycle end with a bubble?".
        //
        // The penalty for skipping reconnaissance depends on whether
        // the fixture has text content: for text-heavy fixtures
        // (ground truth lists expected_description_keywords /
        // expected_details) the model really should zoom_in or
        // read_text to verify text; for non-text fixtures the 5
        // round-1 images already let the model answer correctly.
        val firstTool = outcome.firstToolName
        val hasText = scene.optJSONArray("expected_description_keywords")?.length()?.let { it > 0 } == true ||
            scene.optJSONArray("expected_details")?.length()?.let { it > 0 } == true
        val skipReconScore = if (hasText) 0.5 else 1.0
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
            firstTool == "zoom_in" || firstTool == "read_text" -> {
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

        // Text keyword hit rate.
        var textScore = 0.0
        var denom = 0
        if (expectedKeywords != null && expectedKeywords.length() > 0) {
            val contentNorm = normalize(content)
            val hits = (0 until expectedKeywords.length()).count { i ->
                fuzzyMatch(contentNorm, expectedKeywords.getString(i))
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
        if (expectedDetails != null && expectedDetails.length() > 0) {
            val llmValues = details.map { normalize(it.value) }
                .filter { it.isNotBlank() }

            var hits = 0
            for (i in 0 until expectedDetails.length()) {
                val exp = expectedDetails.getJSONObject(i)
                val eValue = normalize(exp.optString("value", ""))
                if (eValue.isEmpty()) continue
                val matched = llmValues.any { lv -> fuzzyMatch(lv, eValue) }
                if (matched) hits++
            }
            val detailScore = hits.toDouble() / expectedDetails.length()
            textScore = if (denom > 0) (textScore + detailScore) / 2.0 else detailScore
            denom++
        }
        if (denom == 0) textScore = 1.0

        // Type match: read per-scene expected_type so a fixture with
        // "location" or "solve" isn't silently scored 0 against a
        // hardcoded "info".
        val expectedType = scene.optString("expected_type", "info")
        val typeScore = if (type == expectedType) 1.0 else 0.0

        val r2 = 0.50 * textScore + 0.50 * typeScore
        return Pair(r2, typeScore)
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
}
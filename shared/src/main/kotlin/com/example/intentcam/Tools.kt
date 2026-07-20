package com.example.intentcam

import org.json.JSONArray
import org.json.JSONObject

/**
 * Single tool exposed to the LLM via Anthropic's `tools[]` array.
 *
 * Mirrors the shape of an Anthropic tool definition: `name`,
 * `description`, and an `input_schema` JSON Schema object the model
 * must satisfy when emitting `tool_use`.  The [body] is the in-process
 * function that runs once the orchestrator has matched a tool_use
 * block back to this definition.
 *
 * Tools are stateless: every body invocation gets a fresh
 * [ToolContext].  Stateful work (e.g. an LLM follow-up call after the
 * body finishes) lives in [ToolUseLoop], not here.
 */
data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val body: ToolBody,
)

/**
 * Function executed when the model picks [ToolDef.name].  Receives the
 * orchestrator context (image, config, etc.) and the parsed input
 * JSON object; returns the [ToolResult] that becomes the next user-role
 * message in the conversation.
 */
typealias ToolBody = suspend (ctx: ToolContext, input: JSONObject) -> ToolResult

/**
 * Runtime context passed to every tool body.  The orchestrator builds
 * one of these per recognition cycle and reuses it across all rounds.
 *
 * Three image fields, in increasing "zoom level":
 *  - [thumbnail] is the small JPEG (3200 px max-dim, q90) sent to
 *    the LLM as the round-1 image.  Tools that want to talk to the
 *    LLM again (e.g. a hypothetical verify tool) would use this.
 *  - [jpeg] is the **current croppable** image.  For round 1 it's
 *    the full-resolution photo; after a zoom_in, it advances to
 *    the new crop.  Subsequent zoom_ins in the same round chain
 *    off this — by default each call crops whatever was most
 *    recently produced.
 *  - [originalFullRes] is the **original** full-resolution photo.
 *    It never changes during a cycle.  zoom_in with `source =
 *    "original"` crops this instead of the chain — useful for
 *    sibling views in the same round.
 */
data class ToolContext(
    /** Current croppable image (advances with each zoom_in by default). */
    val jpeg: ByteArray,
    /** Original full-resolution photo.  Never changes during a cycle. */
    val originalFullRes: ByteArray,
    /** Down-scaled JPEG sent to the LLM as the round-1 image. */
    val thumbnail: ByteArray,
    /** Optional follow-up user text (e.g. destination typed in the
     *  user-input dialog).  Empty for round 1. */
    val userText: String,
    /** Active LLM config.  Tools that need to make follow-up LLM
     *  calls (e.g. default_describe wrapping the BROAD prompt) read
     *  this. */
    val config: LlmConfig,
    /** Round-1 OCR result (full image).  Populated by
     *  [ToolUseLoop] from [OcrEngine.recognize]; empty when the
     *  OCR backend isn't installed (e.g. JVM eval).  Used by
     *  `compare_text` to diff the LLM's own reading against
     *  the round-1 OCR without re-running OCR on already-scanned
     *  regions.  Phase 2 (2026-07-11) — `read_text` removed; only
     *  `compare_text` consults the cache now. */
    val ocrCache: OcrResult = OcrResult.EMPTY,
)

/**
 * Output of one tool body.  The orchestrator translates this into:
 *   - either a `tool_result` content block fed back to the model, OR
 *   - a final [Bubble] surfaced to the user (when [finalBubble] is
 *     true and there's no further round).
 *
 * If [needsUserInput] is true, the orchestrator pauses and surfaces a
 * [UserInputRequest] instead of continuing the round-trip.  The
 * user's reply resumes the loop with `ctx.userText` populated.
 */
data class ToolResult(
    /** Short tag sent back to the model as the `tool_result` content.
     *  Should be a one-line summary — the model uses this to phrase
     *  its final answer (or to call emit_bubble with structured fields). */
    val toolSummary: String,
    /** True when this result is itself a final user-facing bubble and
     *  the orchestrator can stop without another LLM round. */
    val finalBubble: Boolean = false,
    /** Synthesized bubble fields, populated when [finalBubble] is true
     *  OR when the tool wants the UI to show a placeholder.
     *
     *  Default = [DEFAULT_BUBBLE_TYPE] (`"info"`).  Kept as a string
     *  so [ToolResult] stays a pure data class with no dependency on
     *  IntentCam wiring.  Free-form after the intent-taxonomy
     *  retirement — not a registered enum. */
    val type: String = DEFAULT_BUBBLE_TYPE,
    val title: String = "",
    val detail: String = "",
    val confidence: Float = 0.7f,
    /** Structured details extracted from the image (text, numbers,
     *  brand names, etc.).  Each entry becomes a row in the
     *  detail-view table.  Populated by emit_bubble. */
    val details: List<com.example.intentcam.Detail> = emptyList(),
    /** True when the orchestrator should park and ask the user for
     *  free-form input before continuing. */
    val needsUserInput: Boolean = false,
    /** Required when [needsUserInput] is true.  Goes into the
     *  [UserInputRequest] surfaced to the UI. */
    val userInputPrompt: String = "",
    /** Optional follow-up image to attach to the next user message.
     *  Used by zoom_in: the orchestrator pulls this out and adds it
     *  to the next round's user content alongside the tool_result,
     *  so the model sees both the new image and the previous round
     *  context.  Kept raw (not base64) since the orchestrator
     *  base64-encodes it itself. */
    val followUpJpeg: ByteArray? = null,
    /** When `emit_bubble` accepts an `action_ids: List<String>`
     *  field from the model, populated here so the orchestrator can
     *  persist it onto [Bubble.llmProposedActions].  This is the sole
     *  action-routing signal after the 2026-07-17 intent-taxonomy
     *  retirement: non-null list = the model's explicit pick
     *  (intersected with enabled ids in the resolver); null = no LLM
     *  proposals, only content-rescue can still add chips.  Kept on
     *  the shared ToolResult so the prompt-time schema and the
     *  orchestrator's plumbing don't drift. */
    val proposedActions: List<String>? = null,
    /** When `emit_bubble` accepts a `label_markdown` field from the
     *  model (full label content as markdown for label-like scenes),
     *  populated here so the orchestrator can persist it onto
     *  [Bubble.labelMarkdown].  Drives the `view_label` action's
     *  required input + rendered-page payload.  Null for non-label
     *  scenes. */
    val labelMarkdown: String? = null,
    /** `emit_bubble.ad_markdown` — full ad transcription for posted
     *  advertisements (view_ad).  Null for non-ad scenes. */
    val adMarkdown: String? = null,
    /** `emit_bubble.ad_bbox` — the ad body region, 4-corner
     *  normalized coordinates (TL→TR→BR→BL), for crop + perspective
     *  correction.  Null when not framed. */
    val adBbox: List<OcrPoint>? = null,
)

/**
 * Mutable container of registered [ToolDef]s.  Build once at app
 * start (see [registerDefaultTools]) and pass to [ToolUseLoop].
 *
 * The registry owns no runtime state — bodies read from [ToolContext]
 * and write via [ToolResult].  Re-registering is safe but wasteful;
 * prefer to build the registry once and reuse.
 */
class ToolRegistry {

    private val defs = mutableListOf<ToolDef>()
    private val byName = mutableMapOf<String, ToolDef>()

    fun register(def: ToolDef): ToolDef {
        check(def.name !in byName) { "duplicate tool name: ${def.name}" }
        defs.add(def)
        byName[def.name] = def
        return def
    }

    fun get(name: String): ToolDef? = byName[name]

    fun list(): List<ToolDef> = defs.toList()

    /**
     * Serialize as Anthropic's `tools[]` JSON array.  Stripped of any
     * Kotlin-side fields so it can be dropped into the request body
     * verbatim.
     */
    fun toAnthropicToolsJson(): JSONArray {
        val out = JSONArray()
        for (def in defs) {
            out.put(
                JSONObject()
                    .put("name", def.name)
                    .put("description", def.description)
                    .put("input_schema", def.inputSchema)
            )
        }
        return out
    }
}
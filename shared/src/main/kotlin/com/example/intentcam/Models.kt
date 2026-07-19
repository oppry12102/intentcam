package com.example.intentcam

/** Default free-form `type` label for a [Bubble] when the LLM omits
 *  the optional `type` field (or a fallback/placeholder bubble is
 *  synthesized).  After the 2026-07-17 intent-taxonomy retirement
 *  `type` is NOT a registered enum — this is just the conventional
 *  placeholder string ("info"). */
const val DEFAULT_BUBBLE_TYPE = "info"

/**
 * One row in the detail-view table.  Each detail is something the
 * LLM extracted from the image (text, number, object, color, etc.)
 * and the table renders them in (kind, label, value) columns.
 *
 * [bbox] is the optional 4-corner normalized [0,1] coordinates of
 * the row's text region in the source image (TL → TR → BR → BL).
 * Populated by `emit_bubble` when the model copied the bbox from
 * the round-1 OCR hint so the detail view can highlight the row's
 * position in the photo.  Null for rows whose source the LLM
 * described from memory (no positional anchor).  Backward
 * compatible — old bubbles with `bbox = null` render unchanged.
 */
data class Detail(
    val kind: String,    // "text" | "number" | "object" | "color" | "shape" | ...
    val label: String,   // human-friendly name, e.g. "Brand name"
    val value: String,   // the extracted content
    val bbox: List<OcrPoint>? = null,  // 4-corner normalized [0,1]; null = no anchor
)

/**
 * One intent bubble shown to the user.  Carries the captured JPEG so
 * the bubble can show a thumbnail and the detail view can show the
 * full picture without re-fetching from anywhere.
 *
 * Two-stage recognition result:
 *  - [content]  : 1-2 sentence overall image description
 *  - [title]    : the user's inferred intent (动宾短语, ≤30 chars)
 *  - [type]     : optional free-form label (default "info"); NOT a
 *                 registered enum after the 2026-07-17 intent-taxonomy
 *                 retirement.  Kept for debug/log only — [intent] is
 *                 the authoritative free-form summary, [actions] drive
 *                 chips.
 *  - [details]  : structured items for the detail-view table; can
 *                 be empty if the LLM chose not to extract any
 *  - [confidence] : 0.0..1.0
 *  - [actions]    : ids of [com.example.intentcam.ActionDef]s that
 *                   should render as chips on this bubble.  Populated
 *                   by AppViewModel after ToolUseLoop returns the
 *                   bubble (resolver.suggest).  Empty until UI runs
 *                   the resolver.  Storing IDs (not ActionDef refs)
 *                   keeps Bubble cross-platform — `shared/` doesn't
 *                   know about Android-only types.
 *
 * [imageBytes] is the **thumbnail** JPEG (display-only, ~3200 px).
 * The full-res original is NOT held here — for the needs-input resume
 * path the app keeps it in a transient AppViewModel field, not in the
 * bubble history, so a 4-bubble history stays small.  [needsUserInput]
 * is true for placeholder bubbles parked while the orchestrator waits
 * for the user to type a follow-up.
 *
 * [llmProposedActions] (2026-07-13) — when non-null, this is the raw
 * action_ids list the model emitted in `emit_bubble`.  The resolver
 * intersects this with the user's enabled set, so the model is
 * authoritative on which chips to show.  Null when the model omitted
 * action_ids (rare after the "默认应填" prompt nudge) — then no chips
 * render except content-rescue additions.
 */
data class Bubble(
    val id: String,
    /** Owning cycle job's id
     *  (UUID from [com.example.intentcam.CycleManager.startCycle]).
     *  Defaults to the bubble's own [id] for the legacy single-cycle
     *  path (eval, ad-hoc emit_bubble outside CycleManager).  Lets
     *  the live UI route per-bubble taps back to the right cycle
     *  when 2+ jobs are concurrent (Phase C). */
    val cycleId: String = id,
    /** Free-form intent label the LLM may emit alongside [intent]
     *  (optional, defaults to [DEFAULT_BUBBLE_TYPE] = "info").  After
     *  the 2026-07-17 intent-taxonomy retirement this is NOT a
     *  registered/scored enum — just an optional label for logs /
     *  debug.  [intent] is the authoritative free-form summary. */
    val type: String = DEFAULT_BUBBLE_TYPE,
    /** Free-form Chinese phrase describing what the user wants to do
     *  with this bubble (≤30 chars, e.g. "拨打联系电话", "导航去这家店").
     *  The LLM supplies this authoritative intent; [type] remains the
     *  legacy classification used for compatibility.  Defaults to [type]
     *  so older call sites keep working.  See
     *  `docs/adr/2026-07-14-v3-inversion.md`. */
    val intent: String = type,
    val title: String,             // intent (动宾短语)
    val detail: String,            // content description (was 'detail' in tool)
    val confidence: Float,
    val imageBytes: ByteArray,
    val createdAtMs: Long,
    val toolName: String? = null,
    val needsUserInput: Boolean = false,
    val details: List<Detail> = emptyList(),
    val actions: List<String> = emptyList(),
    val llmProposedActions: List<String>? = null,
    /** Per-action validation
     *  status.  Key = action id, value = true when every required
     *  input for that action was satisfied by the bubble's surface
     *  text (per [com.example.intentcam.ActionInputSpec.parser]),
     *  false otherwise.  Populated by [com.example.intentcam.ActionOrchestrator]
     *  after each `emit_bubble`.  Empty for legacy bubbles (no
     *  requiredInputs registered → all actions are implicitly
     *  "validated").  Drives the live-UI chip state in Phase C. */
    val validatedInputs: Map<String, Boolean> = emptyMap(),
    /** Full label content as markdown when the LLM recognized a
     *  label-like structured text block (商品标签/价签/吊牌/合格证/快递
     *  面单/票据/铭牌 …) and emitted `emit_bubble.label_markdown`.
     *  Drives the `view_label` action: required input + rendered-page
     *  payload.  Null for non-label scenes. */
    val labelMarkdown: String? = null,
    /** Cross-action aggregate of missing input
     *  keys (deduplicated, ordered by first appearance across
     *  [actions]).  Empty when every action's requiredInputs are
     *  satisfied.  Drives the orchestrator's missing-input framing
     *  for the next LLM round — the LLM sees a flat list of
     *  labels to chase, not a per-action map. */
    val pendingInputs: List<String> = emptyList(),
)

/** Whole-screen UI state exposed by [AppViewModel]. */
data class UiState(
    val phase: Phase = Phase.NEED_PERMISSION,
    val scene: String = "",
    val selectedBubble: Bubble? = null,
    val error: String? = null,
    /** When true, the recognition process streams onto a translucent
     *  overlay above the camera preview.  Persisted in [SettingsStore]. */
    val debugEnabled: Boolean = true,
    /** Newest-last ring of [DebugLogEntry] entries.  Capped at [DEBUG_LOG_MAX];
     *  older entries are dropped when new ones arrive. */
    val debugLogs: List<DebugLogEntry> = emptyList(),
    /** Persistent buffer of `FrameAnalyzer` errors (OOM, decode failure,
     *  etc.) — INDEPENDENT of [debugEnabled] so a user with the panel
     *  off can still surface analyzer faults.  Capped at [DEBUG_LOG_MAX].
     *  2026-07-14 C-cleanup: split out from `debugLogs` so the toggle
     *  doesn't hide real crashes. */
    val analyzerErrorLog: List<DebugLogEntry> = emptyList(),
    /** Non-null while a tool needs free-form user input to continue
     *  (e.g. navigate_to_block's destination).  The UI shows a dialog;
     *  AppViewModel.submitUserInput(text) feeds it back into the
     *  orchestrator. */
    val userInputRequest: UserInputRequest? = null,
    /** Non-null while an action's [ActionDef.body] has returned
     *  [com.example.intentcam.ActionOutcome.RequestArgs] and is
     *  parked waiting for the form values.  The UI shows an
     *  action-arg input dialog; AppViewModel.submitActionArgs(map)
     *  re-invokes the action with the form values.  Mutually
     *  exclusive with [userInputRequest] in practice (the user can
     *  only interact with one dialog at a time). */
    val pendingAction: PendingAction? = null,
    /** Non-null while a chip tap has parked an
     *  AlertDialog confirmation (currently only `dial_number`
     *  sets this; the dialog asks the user to confirm before
     *  launching the dialer).  The UI shows a Compose AlertDialog;
     *  AppViewModel.confirmAction() routes through dispatch,
     *  cancelConfirmation() dismisses.  Lives separately from
     *  [pendingAction] so the action-args form and the consent
     *  dialog stay disjoint states — a confirmation is a
     *  yes/no gate, an args form is a fields-to-fill gate, mixing
     *  them would compose badly. */
    val pendingConfirmation: PendingConfirmation? = null,
    /** Non-null while the `view_label` action's rendered-label page is
     *  on screen.  Set by `executeAndDispatch` on
     *  `ActionOutcome.ShowRenderedLabel`; cleared by
     *  `AppViewModel.dismissRenderedLabel()`.  Overlays whatever phase
     *  is underneath (camera or detail screen). */
    val renderedLabel: RenderedLabel? = null,
    /** Live in-flight cycles
     *  keyed by [com.example.intentcam.CycleJob.id].  Each entry's
     *  [CycleSnapshot] exposes the cycle's current bubble + status
     *  as `StateFlow`s so the live UI can `collectAsState` per
     *  cycle without re-rendering the whole list on every emit.
     *  Populated by [com.example.intentcam.CycleManager].  The
     *  single source of truth for rendered bubbles — the live
     *  UI iterates `state.cycles` directly. */
    val cycles: Map<String, CycleSnapshot> = emptyMap(),
    /** Number of cycles in [JobStatus.PENDING] or [JobStatus.IN_FLIGHT].
     *  Drives shutter availability via
     *  `CYCLE_QUEUE_DEPTH - activeCycleCount` and is synchronized on
     *  every cycle transition.
     *
     *  This is stored separately because [cycles] also retains terminal
     *  jobs, while status changes do not alter the map structure.  Keeping
     *  the derived count in [UiState] guarantees recomposition when a cycle
     *  starts or finishes. */
    val activeCycleCount: Int = 0,
) {
    companion object {
        /** Max entries kept in [debugLogs] before the oldest is evicted. */
        const val DEBUG_LOG_MAX = 40
        /** Maximum number of queued or in-flight cycles.  Terminal cycles
         *  do not consume this capture budget; completing a cycle frees a
         *  shutter slot immediately.  Queue depth is intentionally distinct
         *  from [CYCLE_CONCURRENCY].  See
         *  `docs/adr/2026-07-16-producer-consumer-pipeline.md`. */
        const val CYCLE_QUEUE_DEPTH = 8

        /** Maximum number of cycles that process OCR and LLM work at once.
         *  A small worker pool bounds API load and peak device resources
         *  while [CYCLE_QUEUE_DEPTH] absorbs capture bursts.  See
         *  `docs/adr/2026-07-16-producer-consumer-pipeline.md`. */
        const val CYCLE_CONCURRENCY = 2

        /** Maximum number of cycle snapshots retained for bubble history.
         *  On overflow, [CycleManager] evicts the oldest terminal entry and
         *  never drops a queued or in-flight cycle.  See
         *  `docs/adr/2026-07-16-producer-consumer-pipeline.md`. */
        const val CYCLES_MAX_TOTAL = 8
    }
}

/** One cycle job's reactive
 *  surface, surfaced to Compose via [UiState.cycles].  Carries
 *  `StateFlow` references (not values) so the UI can `collectAsState`
 *  per cycle independently — updating one job's bubble does not
 *  force every other cycle's card to recompose.
 *
 *  Identity ([id]) is stable across the cycle's lifetime.  Status
 *  transitions PENDING → IN_FLIGHT → COMPLETE / SUPERSEDED /
 *  ERRORED; the bubble flow's `null` value signals "no bubble
 *  emitted yet" (PENDING or early IN_FLIGHT).
 *
 *  Lives in `shared/` so [UiState] (also in shared/) can hold a
 *  `Map<String, CycleSnapshot>` without dragging Android types.
 *  The concrete `MutableStateFlow` instances are constructed in
 *  `app/CycleManager.kt`. */
data class CycleSnapshot(
    val id: String,
    val status: kotlinx.coroutines.flow.StateFlow<JobStatus>,
    val bubble: kotlinx.coroutines.flow.StateFlow<Bubble?>,
    val nRounds: kotlinx.coroutines.flow.StateFlow<Int>,
    val capturedAtMs: Long,
    val pendingInputs: kotlinx.coroutines.flow.StateFlow<List<String>>,
    /** Captured frame's thumbnail JPEG, shared with the eventual
     *  [Bubble.imageBytes].  The live UI can render the final card shape
     *  immediately while [bubble] is still null, then fill in recognition
     *  fields without a layout swap.  This exposes the bytes already held
     *  by the cycle rather than copying them. */
    val thumbnail: ByteArray,
)

/** One cycle job's lifecycle status.  See
 *  [com.example.intentcam.CycleManager] for the transition rules.
 *  `SUPERSEDED` is the only soft state — a superseded job keeps
 *  running in the background and may eventually reach COMPLETE,
 *  but the user has already moved on to a newer cycle. */
enum class JobStatus {
    /** Registered in [com.example.intentcam.CycleManager.allJobs]
     *  and **waiting in the pending FIFO queue** for a free worker
     *  slot ([UiState.CYCLE_CONCURRENCY]).  ToolUseLoop has not
     *  started round 1 yet.  The live UI renders this as a
     *  "排队中…" placeholder card (distinct from IN_FLIGHT's
     *  "识别中…" spinner). */
    PENDING,
    /** Round 1 (or later) in flight. */
    IN_FLIGHT,
    /** Cycle reached its terminal bubble (or max-rounds); bubble
     *  flow carries the final value; UI may show "complete" badge. */
    COMPLETE,
    /** User took a newer photo before this cycle reached COMPLETE;
     *  the cycle keeps running but its UI affordances are demoted
     *  (faded card or auto-evicted when CYCLE_MAX_CONCURRENT is hit). */
    SUPERSEDED,
    /** Cycle terminated by an unrecoverable error (LLM 529 storm,
     *  network failure, parse-time exception).  Bubble flow carries
     *  the last good value or null. */
    ERRORED,
}

/** Asked by a tool body that needs a free-form follow-up from the
 *  user before it can finish.  The UI surfaces this as a dialog; the
 *  orchestrator resumes once [AppViewModel.submitUserInput] (or
 *  [AppViewModel.cancelUserInput]) is called. */
data class UserInputRequest(
    val toolName: String,
    val prompt: String,
)

/** [PendingAction] / [ActionArgSpec] / [ArgKind] live in
 *  [com.example.intentcam.ActionArgs.kt] so the data carriers are
 *  shared between the cross-platform [UiState] (here) and the
 *  Android-only [com.example.intentcam.ActionOutcome].  See that file
 *  for the rationale on the split. */

/** One entry in the recognition debug log.  Surfaced on screen as a
 *  scrolling overlay while [UiState.debugEnabled] is true. */
data class DebugLogEntry(
    /** Wall-clock millis when the entry was logged.  Display only. */
    val timestampMs: Long,
    /** Process-monotonic sequence number, unique per AppViewModel
     *  instance.  Used as the LazyColumn key — `timestampMs` collides
     *  when two logDebug calls land in the same millisecond, which
     *  crashes the LazyColumn with "Key already used".  The seq is
     *  cheap to allocate (AtomicLong.incrementAndGet) and monotonic. */
    val seq: Long,
    /** Short tag for at-a-glance filtering ("OCR", "R1", "TOOL", "ERR"). */
    val tag: String,
    /** Single-line human-readable message.  Newlines are stripped by the
     *  emitter; rendering truncates at 3 lines regardless. */
    val message: String,
)

enum class Phase {
    NEED_PERMISSION,  // camera permission not granted yet
    SCANNING,         // live preview + shutter button armed
    SHOWING_DETAIL,   // user tapped a bubble; full image + description visible
    SETTINGS          // settings screen
}

/** User-editable model configuration.  The only knobs are the endpoint
 *  URL, the bearer token, and the model id; everything else
 *  (temperature, max_tokens, tool-use protocol) is hard-coded for
 *  determinism. */
data class LlmConfig(
    val baseUrl: String,
    val authToken: String,
    val model: String,
) {
    companion object {
        // Defaults baked into the APK so the app starts working out of
        // the box.  DEFAULT_TOKEN here is only a placeholder: the app
        // module overrides it with BuildConfig.DEFAULT_AUTH_TOKEN
        // (resolved at build time from secrets.properties / the
        // ANTHROPIC_AUTH_TOKEN env var — see app/build.gradle.kts and
        // `bakedDefaultToken` in SettingsStore.kt).  The Settings screen
        // can still override at runtime.  This placeholder is used only
        // when no build secret is present (e.g. shared-module / eval use,
        // where EvalMain reads ANTHROPIC_AUTH_TOKEN from the env directly).
        const val DEFAULT_BASE_URL = "https://api.minimaxi.com/anthropic"
        const val DEFAULT_TOKEN = "REPLACE_AT_RUNTIME"
        const val DEFAULT_MODEL = "MiniMax-M3"
    }
}
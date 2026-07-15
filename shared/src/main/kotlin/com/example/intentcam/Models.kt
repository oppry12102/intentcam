package com.example.intentcam

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
 *  - [type]     : precise intent id from the registered IntentDecl set
 *                 (e.g. "phone" / "payment_qr" / "warning_safety" /
 *                 "route_to" / ...).  Used by the UI for accent color
 *                 and by the resolver for action-chip suggestion.
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
 * intersects this with the user's enabled set instead of the
 * applicability filter, so the model can opt in/out of suggestions
 * per-cycle.  Stays null when the prompt hasn't asked for it (the
 * "no override" path falls through to applicableIntents /
 * applicableFamilies as before).
 */
data class Bubble(
    val id: String,
    /** [2026-07-14 Phase B — inversion v3.0] Owning cycle job's id
     *  (UUID from [com.example.intentcam.CycleManager.startCycle]).
     *  Defaults to the bubble's own [id] for the legacy single-cycle
     *  path (eval, ad-hoc emit_bubble outside CycleManager).  Lets
     *  the live UI route per-bubble taps back to the right cycle
     *  when 2+ jobs are concurrent (Phase C). */
    val cycleId: String = id,
    /** Legacy field: 14-id intent classification (Phase A-K).
     *  Kept as a `@Deprecated` alias for one release cycle after
     *  Phase E ships — see [intent] for the new free-form summary.
     *  When [intent] is unset (Phase A-D), downstream UI / eval
     *  fall back to this string. */
    val type: String,             // "info" | "location" | "solve" | ... (legacy 14-bucket)
    /** [2026-07-14 Phase A — inversion v3.0] Free-form Chinese phrase
     *  describing what the user wants to do with this bubble (≤30
     *  chars, e.g. "拨打联系电话", "导航去这家店").  Replaces the
     *  hardcoded [type] enumeration starting in Phase E.  Defaults
     *  to [type] for backwards compatibility through Phase A-D —
     *  every existing call site keeps working unchanged. */
    val intent: String = type,
    val title: String,             // intent (动宾短语)
    val detail: String,            // content description (was 'detail' in tool)
    val confidence: Float,
    val imageBytes: ByteArray,
    val createdAtMs: Long,
    val toolName: String? = null,
    val needsUserInput: Boolean = false,
    val details: List<Detail> = emptyList(),
    // [2026-07-10] Action ids; empty until AppViewModel resolves
    //  them against ActionRegistry + SettingsStore preference.
    val actions: List<String> = emptyList(),
    // [2026-07-13] Raw LLM-emitted action_ids (when the prompt's
    //  emit_bubble schema carries them).  Drives the LLM-override
    //  branch of ActionResolver.  Null = no override (legacy
    //  applicability filter).  Kept distinct from `actions` (the
    //  post-resolve chip list) so debug payloads can tell which
    //  path produced the final set.
    val llmProposedActions: List<String>? = null,
    /** [2026-07-14 Phase A — inversion v3.0] Per-action validation
     *  status.  Key = action id, value = true when every required
     *  input for that action was satisfied by the bubble's surface
     *  text (per [com.example.intentcam.ActionInputSpec.parser]),
     *  false otherwise.  Populated by [com.example.intentcam.ActionOrchestrator]
     *  after each `emit_bubble`.  Empty for legacy bubbles (no
     *  requiredInputs registered → all actions are implicitly
     *  "validated").  Drives the live-UI chip state in Phase C. */
    val validatedInputs: Map<String, Boolean> = emptyMap(),
    /** [2026-07-14 Phase A] Cross-action aggregate of missing input
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
    /** A network request is in flight (LLM call). */
    val analyzing: Boolean = false,
    /** FIFO queue of bubbles; oldest evicted when length exceeds [BUBBLE_MAX]. */
    val bubbles: List<Bubble> = emptyList(),
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
    /** [2026-07-13] Non-null while a chip tap has parked an
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
    /** [2026-07-14 Phase B — inversion v3.0] Live in-flight cycles
     *  keyed by [com.example.intentcam.CycleJob.id].  Each entry's
     *  [CycleSnapshot] exposes the cycle's current bubble + status
     *  as `StateFlow`s so the live UI can `collectAsState` per
     *  cycle without re-rendering the whole list on every emit.
     *  Empty in the legacy single-cycle path; populated only when
     *  [com.example.intentcam.CycleManager] is in use (Phase B+).
     *  Legacy code paths that read [bubbles] still work — the
     *  bubble list is now derived as `cycles.values.mapNotNull
     *  { it.bubble.value }` so the FIFO cap (BUBBLE_MAX) keeps
     *  its semantics. */
    val cycles: Map<String, CycleSnapshot> = emptyMap(),
    /** [2026-07-15 P0 fix] Count of cycles whose status is
     *  [JobStatus.PENDING] or [JobStatus.IN_FLIGHT].  Drives the
     *  shutter button's "还可以拍 N 张" counter via
     *  `CYCLE_MAX_CONCURRENT - activeCycleCount`.  Updated by
     *  [com.example.intentcam.AppViewModel.syncCycleCounters] on
     *  every cycle transition (startCycle / complete / error /
     *  cancel / restart).
     *
     *  Why a separate field instead of computing from `cycles`:
     *  `cycles` contains COMPLETE / ERRORED / SUPERSEDED entries
     *  too, and recomposition would require per-cycle status
     *  subscriptions to track status changes (the cycles map's
     *  *structure* doesn't change when a status flips).  A plain
     *  Int in UiState recomposes on every state copy and lets the
     *  ShutterButton read with zero ceremony.
     *
     *  Bug this fixes: pre-fix the counter was
     *  `CYCLES_MAX_TOTAL - cycles.size` (total).  After 8
     *  captures the counter stayed at 0 even after every cycle
     *  COMPLETE'd, because COMPLETE entries never leave the map
     *  (the bubble UI keeps referencing them).  The user had to
     *  tap "重新扫描" to release the shutter.  With this field
     *  tracking active count, the counter increments back to 8
     *  as cycles complete and "重新扫描" is only needed for
     *  the explicit "clean slate" intent (clearing bubbles,
     *  dismissing error banners). */
    val activeCycleCount: Int = 0,
) {
    companion object {
        /** [2026-07-15] Hard cap on the legacy [bubbles] FIFO queue.
         *  When a new bubble arrives and we're already at this count,
         *  the oldest is dropped.
         *
         *  History: was 4 in Phase A; bumped to 8 in the v3.0 polish
         *  batch because users taking a long photo session wanted to
         *  scroll back further than 4 entries to find an earlier
         *  result.  Note: this cap is for the **legacy** single-cycle
         *  path ([bubbles]); the live-UI path uses the [cycles] map
         *  whose cap is [CYCLE_MAX_CONCURRENT] (= 2 concurrent
         *  IN_FLIGHT cycles, not a "saved bubbles" cap).  The two
         *  caps serve different purposes — see CycleManager.kt for
         *  the live-UI cap logic.
         *
         *  [Future work] Persistence: this is in-memory only and is
         *  wiped on process death.  A follow-up will write
         *  [bubbles] to disk on `onStop` and rehydrate on cold
         *  start, bumping the cap further (or moving it to a
         *  queryable window) once storage cost is bounded.  Until
         *  then 8 is the sweet spot — enough history to scroll
         *  back, not so much that a long session OOMs. */
        const val BUBBLE_MAX = 8
        /** Max entries kept in [debugLogs] before the oldest is evicted. */
        const val DEBUG_LOG_MAX = 40
        /** [2026-07-14 Phase B → v3.0 polish] Hard cap on
         *  *active* (PENDING + IN_FLIGHT) cycles the CycleManager
         *  will keep alive concurrently.  Beyond this count the
         *  oldest active job is dropped from the UI map (its
         *  coroutine is cancelled so the LLM API call doesn't
         *  bill for a discarded result).
         *
         *  History: was 2 in Phase B (the "one focused + one
         *  buffered" rapid-2-photo use case).  Bumped to 8 in
         *  the v3.0 UI polish batch to match the user-facing
         *  "还可以拍 8 张" shutter counter — a user wanting
         *  exactly 8 captures per session shouldn't be limited
         *  by the backend's "2 in flight" cap, and the cycle's
         *  90s timeout (see CycleManager.llmTimeoutMs) makes
         *  8-concurrent manageable.
         *
         *  Note: COMPLETE cycles don't count toward this cap.
         *  When a cycle finishes, the active count drops by 1
         *  automatically (its status transitions to COMPLETE),
         *  freeing a slot for the next shutter tap.  This is
         *  the "释放出一个" semantics the user asked for — the
         *  shutter counter decrements as cycles complete, not
         *  just as new ones are added. */
        const val CYCLE_MAX_CONCURRENT = 8

        /** [2026-07-15 UI polish] Hard cap on the total number
         *  of cycles kept in the [cycles] map (any status —
         *  PENDING, IN_FLIGHT, COMPLETE, ERRORED, SUPERSEDED).
         *  Distinct from [CYCLE_MAX_CONCURRENT] which only counts
         *  IN_FLIGHT cycles — the user can have 8 COMPLETE
         *  bubbles on screen with 0 currently processing.
         *
         *  When a new cycle is added and the map would exceed
         *  this count, the oldest entry is evicted (FIFO); if
         *  that entry is IN_FLIGHT, its coroutine is also
         *  cancelled so the LLM API call doesn't bill for a
         *  result that will never reach the user.  In normal
         *  usage the shutter button is disabled when
         *  cycles.size hits this cap, so the eviction path is
         *  defensive — but the safety net is important if a
         *  future code path bypasses the button gate.
         *
         *  8 = enough scrollback to find an earlier result in
         *  a multi-photo session, low enough that decodeScaled's
         *  400px thumbnail + the in-memory Bubble bytes don't
         *  blow the heap.  The shutter displays the
         *  (CYCLES_MAX_TOTAL - cycles.size) "remaining slots"
         *  count so the user has a hard answer to "how many
         *  more can I take before restart?"  Persisted bubble
         *  history is a separate concern (TODO: onStop → DataStore
         *  → onCreate rehydrate), tracked outside this constant. */
        const val CYCLES_MAX_TOTAL = 8
    }
}

/** [2026-07-14 Phase B — inversion v3.0] One cycle job's reactive
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
    // [2026-07-15] Cross-action missing-input list, mirrored from
    //  CycleJob.pendingInputs so downstream consumers (debug overlay,
    //  snapshot persistence, future API surface) read it from the
    //  snapshot instead of reaching back into the Android-only
    //  CycleJob.  Empty when validation is Complete.
    val pendingInputs: kotlinx.coroutines.flow.StateFlow<List<String>>,
    /** [2026-07-15 P1 fix] The captured frame's thumbnail JPEG
     *  bytes — the same bytes that land on the bubble's
     *  [Bubble.imageBytes] when the cycle emits.  Exposed here
     *  so the live UI can render the BubbleCard's thumbnail
     *  slot IMMEDIATELY when the user taps the shutter, instead
     *  of waiting for the first `emit_bubble` round.  Pre-fix
     *  the UI showed a separate, smaller `InFlightCard` while
     *  `bubble == null`, then swapped to the full BubbleCard
     *  shape when the bubble arrived — visible layout jump
     *  every cycle.  With this field, BubbleCard decodes once
     *  from `thumbnail` and stays the same shape end-to-end;
     *  only title / detail / actions / confidence slot change
     *  as the bubble fills in.
     *
     *  Memory cost: cycle.frame.thumbnail is already in memory
     *  (CycleJob holds it).  We're just exposing a reference,
     *  not copying. */
    val thumbnail: ByteArray,
)

/** [2026-07-14 Phase B] One cycle job's lifecycle status.  See
 *  [com.example.intentcam.CycleManager] for the transition rules.
 *  `SUPERSEDED` is the only soft state — a superseded job keeps
 *  running in the background and may eventually reach COMPLETE,
 *  but the user has already moved on to a newer cycle. */
enum class JobStatus {
    /** Newly registered in [com.example.intentcam.CycleManager.allJobs];
     *  ToolUseLoop has not started round 1 yet. */
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
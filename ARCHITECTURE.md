# IntentCam — Architecture

> Companion doc to [README.md](README.md) (quick start + at-a-glance).
> Tunable constants live in [CONFIG.md](CONFIG.md); the decision
> trail lives in [docs/adr/](docs/adr/README.md).  This document
> describes the system **as of v3.6 (2026-07-19)** — the action-first
> architecture with the `view_label` rendered-page action.

---

## 1. Big picture

IntentCam answers one question per captured frame: **"what can the
user do with this?"**  The answer is a `Bubble` — an LLM-written
summary plus a set of action chips — produced by a bounded
producer/consumer pipeline:

```
shutter → FrameAnalyzer (dual JPEG + OCR hint)
        → CycleManager (queue 8 / workers 2 / StateFlows)
        → ToolUseLoop (≤4 rounds, 4 tools, Anthropic-compatible SSE)
        → Bubble(title, detail, details[], confidence,
                 llmProposedActions, labelMarkdown?)
        → resolver + orchestrator + rescue → chips
        → ActionOutcome dispatch (Intent / Toast / args form / page)
```

Two modules:

- **`:app`** — everything that touches `android.*`: Compose UI,
  CameraX, HMS ML Kit OCR, the action registry + bodies, settings.
- **`:shared`** — pure JVM Kotlin: the whole recognition pipeline
  (tool-use loop, LLM client, models, input parsers, rescue, label
  renderer).  The **eval harness runs the same classes** the app
  ships — no parallel implementation to drift
  (ADR `2026-07-18-eval-prod-parity`).

---

## 2. Frame capture & OCR hint

`FrameAnalyzer` (CameraX `ImageAnalysis`) keeps the latest frame as
two JPEGs (`ImagePipeline` constants):

- **thumbnail** — 3200 px / q90: shown in bubbles and sent to the LLM.
- **fullRes** — 4096 px / q95: source for `zoom_in` crops.

On round 1 an **OCR hint** is prepended to the user message: on
device this is Huawei HMS ML Kit (offline zh+Latin model,
`AndroidOcrEngine`); the eval substitutes a JVM backend (§9).  The
hint carries text lines with normalized bboxes so the LLM can call
`zoom_in` on promising regions and can echo bboxes into
`details[].bbox` for the detail view's highlight dots.

There is **no free-standing OCR tool** (`read_text` was removed
2026-07-11): OCR runs automatically on round 1 and on every
`zoom_in` crop, so the model always sees text for the region it
just zoomed into.

---

## 3. CycleManager — bounded producer/consumer

Each shutter tap starts a **cycle** (`CycleJob`) that flows:
`PENDING → IN_FLIGHT → COMPLETE / SUPERSEDED / ERRORED`.

| Bound | Value | Effect |
|---|---|---|
| `CYCLE_QUEUE_DEPTH` | 8 | shutter rejects when saturated (backpressure) |
| `CYCLE_CONCURRENCY` | 2 | max concurrent LLM streams (API load + memory) |
| `CYCLES_MAX_TOTAL` | 8 | terminal jobs evicted FIFO |

Reactive surface for the UI (`UiState.cycles: Map<String,
CycleSnapshot>`) — each snapshot exposes `status` / `bubble` /
`nRounds` / `pendingInputs` as `StateFlow`s so one job's update
doesn't recompose the whole list.  `busy: StateFlow<Boolean>` is a
*derived* flow (focused job PENDING/IN_FLIGHT) — the single source
of truth for "camera busy".  A per-cycle soft cap
(`llmTimeoutMs = 90s`) marks hung cycles ERRORED while preserving
the last good bubble.

ADR: `2026-07-16-producer-consumer-pipeline`.

---

## 4. Tool-use protocol (ToolUseLoop)

Multi-round loop (≤4 rounds, `ActionOrchestrator.maxRounds`) over an
Anthropic-compatible streaming endpoint (`LlmClient`, SSE,
`temperature = 0.0`, `MAX_TOKENS = 8192`, overall HTTP timeout
180 s).  Four tools (`ToolImplementations.registerDefaultTools`):

| Tool | Purpose |
|---|---|
| `zoom_in` | crop a bbox region from the 4096 fullRes and attach it (auto-OCR'd) |
| `compare_text` | 模型读数 vs round-1 OCR hint 的端侧 diff(conflict 标记 + 建议动作,省一次 round-trip) |
| `extract_text` | 对某区域单跑一次高保真 OCR,**只回文字**(不附图,省 image token) |
| `emit_bubble` | **terminal** — produces the Bubble (required to end) |

`emit_bubble` fields: `content` (→ detail), `intent` (→ title, ≤30
chars free-form Chinese), optional `type` (legacy free-form label),
`details[]` (kind/label/value/bbox), `confidence`,
**`action_ids`** (the action-routing signal), and
**`label_markdown`** (optional; full label transcription — §7).

Endpoint quirks that shaped the client (kimi k3 era):

- **thinking is mandatory and counts against `max_tokens`** — the
  3072→8192 bump fixed the empty-bubble bug (`3c5d3a1`).
- **`tool_result` must lead the first user-message block** — an
  image-first message is a strict-400 on k3 (`9407ec5`).
- Vision input is base64-only, ≤4K per image.

The system prompt's `actions ∈ {...}` block is spliced from the
registry at runtime (`toolUseSystemPrompt(actionIds)`), so the
schema never drifts from the registered vocabulary.  `action_ids`
deliberately has **no `enum`** in the tool schema for the same
reason.

---

## 5. Action system (action-first)

### 5.1 Registry & resolution

Actions are **string-id-keyed** (`ActionRegistry` of `ActionDef`),
not a sealed enum — adding one = registering one `ActionDef`
(`ActionDecl.kt#registerDefaultActions`).  Routing:

```
Bubble.actions = llmProposedActions ∩ registered ∩ enabled   (ActionResolver)
              ∪  contentRescueActions                        (ActionRescue, add-only)
```

The intent taxonomy was retired 2026-07-17 (`f522053`): no
type→action table, no `applicableIntents` filter — the LLM's
`action_ids` is the sole routing signal, `Bubble.type` is a debug
label only.

### 5.2 The six actions

| id | label | accent | body |
|---|---|---|---|
| `open_in_maps` | 在地图中打开 | DELEGATE | `geo:0,0?q=…` + chooser (name-search ok) |
| `dial_number` | 拨号 | EXECUTE | `ACTION_DIAL` |
| `share` | 分享文本 | DELEGATE | `ACTION_SEND text/plain` |
| `view_label` | 查看标签 | DELEGATE | `ShowRenderedLabel` → LabelPageScreen |
| `scan_to_pay` | 扫码支付 | EXECUTE | guidance Toast (never auto-launches payment) |
| `redact_id` | 遮挡证件号 | EXECUTE | guidance Toast |

`requiredInputs` (`ActionInputSpec(key, label, parser)`) declare
what an action needs to fire; the parsers live in
`shared/InputParsers.kt` — **single source of truth** for prod and
eval (ADR `2026-07-16-input-parsers-drift-risk`).

**Dev phase (2026-07-19):** consent gates lifted —
`requiresConfirmation` / `userPrefKey` removed from all actions, so
chips are always visible and fire directly.  The mechanism
(`PendingConfirmation` dialog flow, `enabledIds` pref gate) is
dormant, documented for re-arming.

### 5.3 Validation & the finalize gate

After each `emit_bubble`, `ActionOrchestrator.validateInputs` runs
each chip's parsers and stamps `validatedInputs` / `pendingInputs`;
`shouldFinalize` decides:

- **FINALIZE** — all required inputs satisfied (or max rounds).
- **CONTINUE** — inject a "you still need: 手机号, 标签内容…" nudge
  and loop again (bounded retries).

### 5.4 Rescue (add-only safety net)

`ActionRescue.contentRescueActions` re-adds chips whose verifiable
content cue exists but which the LLM didn't propose:
`dial_number` (phone regex), `redact_id` (18-digit ID / keywords),
`scan_to_pay` (payment-QR vocabulary), **`view_label`** (a
`label_markdown` field exists — zero-precision-risk cue).
`open_in_maps` / `share` are deliberately NOT rescued (their parsers
are too lenient).  The same code runs in prod and eval
(`visibleActions`).

### 5.5 Chip states (ChipStateMapper)

- **Validated** — inputs satisfied; solid, tappable.
- **Spinner** — cycle still PENDING/IN_FLIGHT and not yet validated
  (`null` *or* computed `false` — the nudge round may still deliver;
  mid-flight Ghost was the 2026-07-19 `view_label` bug).  Non-tappable.
- **Ghost** — cycle terminal with inputs still missing; tappable,
  the body's own feedback explains ("未发现可拨打的号码").
- **Hidden** — reserved, not produced today.

### 5.6 ActionOutcome dispatch

`ActionDef.body` returns one of **five** variants; the exhaustive
`when` in `AppViewModel.executeAndDispatch` is the only dispatch
site (adding a variant is deliberately compiler-enforced):

1. `None` — no-op (reserved for pure UI-side effects).
2. `LaunchAndroidIntent` — `startActivity` (maps / dial / share).
3. `ShowUiFeedback` — Toast.
4. `RequestArgs` — park a form (`PendingAction`); resubmission
   re-invokes the body with the collected values.
5. `ShowRenderedLabel` — park a `RenderedLabel(title, markdown,
   bubbleId)` **copy** on `UiState.renderedLabel`; MainActivity
   overlays the label page (§7).  The copy survives bubble eviction.

---

## 6. Bubble & UI state

`Bubble` (shared/Models.kt): `id, cycleId, type, intent, title,
detail, confidence, imageBytes, details[], actions,
llmProposedActions, validatedInputs, pendingInputs, labelMarkdown`.

`UiState`: `phase` (NEED_PERMISSION / SCANNING / SHOWING_DETAIL /
SETTINGS), `cycles`, `activeCycleCount`, `error`, `debugEnabled`
(default OFF since 2026-07-19), `userInputRequest`, `pendingAction`,
`pendingConfirmation`, `renderedLabel`.

Screens (Compose, single Activity, phase-driven — no nav lib):
`PermissionScreen`, `CameraScreen` (preview + `IntentBubbles` cards),
`DetailScreen` (full image + extraction table + chips),
`SettingsScreen`, and the `LabelPageScreen` overlay (rendered above
whatever phase is underneath).

---

## 7. `view_label` — rendered label page

ADR: `2026-07-19-view-label-action`.  Flow:

1. **Contract** — the LLM transcribes the full label into
   `emit_bubble.label_markdown` (markdown subset: headings, `-`
   lists, GFM tables, `**bold**`, `---`; verbatim).  The field is
   `view_label`'s required input and the rescue cue.
2. **Render** — `LabelHtml` (shared, escape-first, zero deps)
   converts the subset to HTML inside a label-page template
   (`width=device-width` viewport → CSS px = dp).  A WebView
   (JavaScript off) displays it full-screen; height follows content,
   longer labels scroll.
3. **Share image** — the PNG is drawn from the **on-screen**
   WebView: `WebView.enableSlowWholeDocumentDraw()` (static, before
   load) lays out the whole document so below-fold content exists;
   capture temporarily resizes the view to full content height,
   `draw()`s into a Bitmap with a software layer, restores bounds in
   the same main-thread turn, and runs a blank-pixel guard.
   (Off-screen WebViews shipped two blank-PNG iterations — an
   unattached Chromium WebView does not reliably rasterize into an
   arbitrary Canvas.)  Shared via FileProvider (`cache/label_pages`)
   with a `ClipData` grant (微信 reads the grant from ClipData only).
4. **Share text** — the markdown source via `ACTION_SEND text/plain`.

No third-party markdown lib, no storage permission, one
FileProvider.  A DEBUG-only hook
(`am start --ez dev_label_page true`) opens the page with canned
content for emulator verification.

---

## 8. Settings & persistence

`SettingsStore` (SharedPreferences) holds: LLM config
(baseUrl/token/model), `debug_enabled`, `enabled_actions`, and
per-action permission booleans (dormant).

- **Baked default token**: `secrets.properties` (gitignored) →
  `BuildConfig.DEFAULT_AUTH_TOKEN`; used whenever prefs have no
  token key.  The Settings token field is always blank on screen.
- **Dirty-check save** (2026-07-19): leaving 设置 persists **only**
  if the user edited an LLM field.  A no-op visit writes nothing, so
  the baked token (which rotates with new APKs) keeps applying;
  once the user edits, they own the whole config.
- **调试日志** switch lives in 设置 (moved from the camera top bar);
  default OFF.

---

## 9. Eval harness (`:shared:eval`, JVM)

Same pipeline classes as prod; Android seams are substituted:

| Prod | Eval |
|---|---|
| `AndroidOcrEngine` (HMS ML Kit) | `JvmLocalOcrEngine` (PP-OCRv4 via `profiling/pp_ocrv4_runner.py` subprocess) → `JvmHuaweiCloudOcrEngine` → blind |
| CameraX frames | RCTW-171 train images (`--img-dir`) |
| `ActionRegistry` (app) | `EvalRunner.defaultActionIds` mirror (full vocabulary as "enabled" by design) |
| orchestrator markValidated | `EvalRunner.markValidated` mirror (same `ActionRescue` + `InputParsers`) |

**ScorerV3** (sole canonical since 2026-07-17):

```
composite = 0.55 · r_actions + 0.30 · r_text + 0.15 · r_inputs
r_actions = |expected ∩ visible| / |expected|   (plain set recall)
r_text    = hybridMatch over expected_description_keywords/details
r_inputs  = fraction of expected_inputs satisfied (InputParsers)
over_fire_rate (none suite only) = precision companion
```

Suites live in `profiling/ground_truth_*.json` (built by
`profiling/build_action_suites.py` — always with `--only <action>`;
a full rebuild clobbers hand curation).  `profiling/baselines.json`
registers per-suite baselines + threshold (0.05);
`scripts/run_regression.sh` drives them, `check_regression.py`
gates.  Measured on kimi k3 (`ANTHROPIC_MODEL=k3`,
`ANTHROPIC_BASE_URL=https://api.kimi.com/coding`) since the glm
token expired 2026-07-19.

---

## 10. Decision trail

Architecture decisions are ADR-ized in [docs/adr/](docs/adr/README.md) —
notably `2026-07-14-v3-inversion` (LLM authoritative),
`2026-07-16-producer-consumer-pipeline`,
`2026-07-16-input-parsers-drift-risk`, `2026-07-18-eval-prod-parity`,
`2026-07-19-view-label-action`.  Historical narrative (intent-era
phases, retired experiments, baseline chains) lives in
[CHANGELOG.md](CHANGELOG.md); this file intentionally documents only
the current architecture.

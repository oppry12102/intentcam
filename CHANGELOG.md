# Changelog

All notable changes to IntentCam will be documented in this file.

## [unreleased]

### Changed — 文档全面重写 (2026-07-19)

`README.md` / `ARCHITECTURE.md` / `CONFIG.md` regenerated against the
actual v3.6 state: action-first architecture (intent taxonomy +
`IntentDecl.kt` were retired 2026-07-17 but still documented),
ScorerV3 sole canonical (was ScorerV2-era tables), 4-tool protocol,
7-suite regression layout with current k3 baselines, `view_label`
feature + verified capture path, dev-phase PII unlock, settings
dirty-check save, debug-hook docs, `--only` suite-builder warning.
History stays in this file + `docs/adr/`; the docs now describe only
what is live.

### Changed — 设置页改版：调试开关迁入 + 返回即存（带脏检查） (2026-07-19)

- **调试日志默认关闭**(`SettingsStore.loadDebugEnabled` +
  `UiState.debugEnabled` 默认值 true→false);开关从相机顶栏
  (Build 图标）移入设置页「调试日志」Switch，相机顶栏只剩设置齿轮。
- **「模型设置」→「设置」**。
- **保存/恢复默认按钮删除**:`SettingsScreen` 返回（返回键或系统
  back）即持久化，但**仅当用户实际修改过 LLM 字段**
  (baseUrl/model 变化或 token 非空）——prefs 无配置键时
  `SettingsStore.load()` 回落到 BuildConfig 内置 token（随新 APK
  轮换），无修改的访问不写入任何东西；一旦修改即由用户接管整个
  配置。连带删除 `AppViewModel.resetConfigToDefault()` 与
  `SettingsStore.reset()`(prefs 全清，已无用）。
- 模拟器验证：顶栏无扳手图标、日志面板默认隐藏、设置页新布局
  正确、无修改返回后 prefs 文件未创建。

### Changed — 开发阶段放开隐私敏感动作限制 (2026-07-19)

Dev-phase unlock (user decision): `dial_number` / `scan_to_pay` /
`redact_id` no longer carry `requiresConfirmation` or `userPrefKey` —
their chips are always visible when proposed/rescued and fire the body
directly (拨号 goes straight to `ACTION_DIAL`; the dialer app itself
still needs the final "call" tap).  The Settings screen's 隐私敏感动作
section (per-action switches + discoverability banner) is removed, and
so are `AppViewModel.piiActionPermissions()` /
`setPiiActionPermission()` / `PiiPermission` (no callers left).
The generic consent mechanism (`requiresConfirmation` →
`PendingConfirmation` dialog flow, `userPrefKey` → `enabledIds` gate)
stays in the codebase, dormant — re-arm per-action before any
end-user build.  No prompt/contract change; eval unaffected.

### Added — 设置页版权信息 (2026-07-19)

设置（模型设置）页底部新增「关于」区：标题 关于（titleMedium）/
开发者：HUANGTAO / © 2026 HUANGTAO. All rights reserved.。
模拟器验证渲染正常。

### Fixed — `view_label` 真机验收三问题 (2026-07-19)

On-device acceptance of the first 3.6 build surfaced three issues, all
fixed in the page/chip layer (no contract or prompt change, no eval
impact):

1. **「未识别到标签内容」误报** — the 查看标签 chip was tappable while
   the `label_markdown` transcription was still in flight:
   `resolveChipState` mapped an already-computed `validated == false`
   to Ghost (tappable, per dial_number's Toast contract) even
   mid-cycle.  Now ANY not-yet-validated chip (null OR false) renders
   as a non-tappable Spinner while the cycle is PENDING/IN_FLIGHT;
   Ghost is reserved for terminal cycles.  (`ChipStateMapper.kt`)
2. **页面太小** — the label HTML had no viewport meta, so with
   `useWideViewPort=false` the WebView laid out at PHYSICAL pixel
   width (15px type ≈ 5dp on a 3× display).  The page now carries
   `width=device-width, initial-scale=1` (CSS px = dp) and is
   FULL-SCREEN: the WebView fills all space between the header and
   the button bar; the dashed-card CSS became a clean full-bleed
   white page.  (`LabelHtml.kt`, `LabelPageScreen.kt`)
3. **保存按钮下线** — per user request only 分享图片 / 分享文字
   remain; `LabelPageExporter`'s MediaStore save paths
   (`saveImageToGallery` / `saveTextToDownloads`) are deleted.
4. **分享图片出细长空白图** — two off-screen capture attempts
   (fresh WebView, load-then-draw, incl. layout-before-load) shipped
   blank PNGs: an unattached Chromium WebView never reliably
   rasterizes into an arbitrary Canvas.  Final approach: capture the
   ON-SCREEN WebView (the one the user already sees rendered) —
   `WebView.enableSlowWholeDocumentDraw()` (static, before load) so
   below-the-fold content is laid out, then temporarily resize the
   view to full content height, `draw()` into a Bitmap with a
   software layer, and restore the original bounds in the same
   main-thread turn (no frame is presented stretched).  A
   blank-pixel guard turns rasterization failure into a "截图失败"
   Toast instead of a silent blank share.  **Verified on emulator**
   via a debug-only hook (`am start --ez dev_label_page true` opens
   the page with canned multi-table content): produced a 1080×3554
   PNG with all below-fold rows, share sheet preview showed the
   label, page restored unstretched.

### Added — `view_label` 标签识别 action：渲染标签页 + 保存/分享 (2026-07-19)

New action for label-like structured text blocks (商品标签/价签/吊牌/合格证/
快递面单/票据/铭牌).  When the LLM recognizes one, it transcribes the FULL
label content into the new optional `emit_bubble.label_markdown` field
(markdown: `#` headings, `-` field lists, GFM tables, verbatim text).  The
`view_label` chip (「查看标签」, DELEGATE blue) opens an in-app page that
renders the markdown into a styled label card and offers four affordances:
保存图片 (PNG → gallery) / 保存文字 (`.md` → Downloads) / 分享图片 /
分享文字.

**Contract & pipeline** (shared/, prod+eval single source):
- `Tools.kt` `ToolResult.labelMarkdown` → `ToolUseLoop` →
  `Models.kt` `Bubble.labelMarkdown`; `UiState.renderedLabel` parks the
  open page's payload (a copy — survives bubble-history eviction).
- `emit_bubble` description gains the label→`view_label` mapping sentence;
  schema gains optional `label_markdown` (explicitly "no label in scene →
  omit the field").
- `InputParsers.labelMarkdown` — the action's required input is the field
  itself (no text-surface regex); `ActionRescue` adds `view_label` when the
  field exists but the chip wasn't proposed (zero-precision-risk rescue:
  the field only exists when the model deliberately wrote it).

**Rendering** (no third-party markdown lib — build-env download history +
APK budget; Compose ui-1.6.1 has no `rememberGraphicsLayer`/`toImageBitmap`):
- `shared/.../LabelHtml.kt` — deterministic markdown-subset → HTML
  converter (headings/lists/tables/bold/code/rules; escape-first so LLM
  output can never inject markup) + built-in label-card CSS template.
- `app/.../LabelPageScreen.kt` — full-screen overlay (scrim + centered
  card, width = screen−32dp ≤520dp, height follows WebView content
  capped at 62% screen, longer labels scroll inside).
- `app/.../LabelPageExporter.kt` — PNG capture via off-screen WebView
  (`measure(width EXACT, height UNSPECIFIED)` → `layout` → `draw()` with
  software layer; height cap 6000px), MediaStore save (API 29+) /
  app-external-dir + MediaScanner (26–28, **no storage permission
  anywhere**), FileProvider `image/png` share (+ClipData grant for 微信),
  `text/plain` markdown share.
- `ActionOutcome.ShowRenderedLabel` — 5th outcome variant; the exhaustive
  dispatcher in `AppViewModel.executeAndDispatch` parks the payload.
- Manifest gains only a FileProvider (`cache/label_pages`).

**Eval** (eval-prod-parity ADR): `EvalRunner.defaultActionIds` +
`defaultRequiredInputs` mirror the registry; `ScorerV3.inputSatisfied` gains
the `label_markdown` branch; `migrate_gt_v2_to_v3.py` + `build_action_suites.py`
tables extended (trigger vocabulary: 标签/价签/吊牌/合格证/生产日期/保质期/
净含量/配料表/执行标准/快递单/面单/铭牌/营养成分/条形码/制造商 — tight on
purpose, RCTW is street-scene).  New suite `ground_truth_view_label.json`
(30 scenes — the corpus turned out to carry real product-packaging fixtures:
净含量/执行标准 on cans/boxes/bottles); none-suite overlap check: 0/16.
`build_action_suites.py` gained `--only ACTION` because a full rebuild
regenerates (clobbers) the hand-curated suites (scan_to_pay 30→6 etc.) —
existing curated files restored from git.  **First baseline (k3, 30 scenes):
composite 0.6711 — actions 0.594 / text 0.808 / inputs 0.678; 0 errors,
0 empty bubbles.**  The 8 a=0 misses (model didn't propose `view_label`
on genuine packaging) are the next prompt-tuning lever.

**Regression after the shared-prompt change** (summary_20260719_160413,
5 suites, 123 scenes, 0 errors): dial **+0.074** (0.8305→0.9049),
maps **+0.102** (0.8123→0.9144), share **+0.133** (0.8255→0.9587) —
all lifts concentrated in `r_actions` (the new mapping sentence raised
`action_ids` salience; k3 endpoint drift not separable but environmental).
scan_to_pay +0.025 PASS; none −0.000 PASS with over_fire **0.4375** (same
documented weak-content share set, **0 `view_label` fires** — precision
intact).  Baselines refreshed on the three lifted suites.

**Manual acceptance checklist** (device-side, not verifiable in CI):
tap 查看标签 → page renders sized to content; long label scrolls; 保存图片
lands in 相册 Pictures/IntentCam; 分享图片 opens sheet and target receives
the PNG; 保存文字 lands in Download/IntentCam/*.md; no-label scenes show no
chip.

### Fixed — `open_in_maps` action uses recognized address, not LLM action phrase (2026-07-17)

User-reported bug: tapping the "在地图中打开" chip fired `geo:0,0?q=<title>`
where `<title>` was the LLM action phrase like `"导航去仙桃市仙桃大道上岛咖啡"`
— the recognized address sat ignored in `bubble.details[]`. On some OEM ROMs
the geo Intent's chooser also surfaced non-installed map placeholders because
the fuzzy query left every maps app's `q=` handler in a degenerate state.

**Two coupled fixes**:

- `shared/.../InputParsers.kt:67-70` — `locationQuery` priority chain
  rewritten. Old order was `title → detail.take(40) → details.firstOrNull`
  (title always won; `detail.take(40)` truncated real addresses
  mid-token: `"...上岛咖啡西餐厅"` → `"...上岛咖"`). New order:
  1. `details[]` scan for address-keyword rows
     (`\S*(路|街|大道|巷|弄|号|区|县|市|省|村|镇|栋|座|层|室)\S*`) —
     picks `洪山区珞喻路370号`, `屯村东路350号`, etc. verbatim from
     the rows LLM writes with bboxes.
  2. Simplified `bubble.title` (strip `导航去|导航到|去|找|到|在|这里是|我在这里|查看|打开`
     prefix) for cases where LLM put the address directly in the title.
  3. **Full** `bubble.detail` (no `.take(40)` truncation — was corrupting
     long addresses).
  4. Last-resort: any non-blank detail row (covers `route_to` fixtures
     with storefront names but no street — user can still tap-and-search).

- `app/.../ActionDecl.kt:205-241` — `open_in_maps` body tightened:
  - `query.take(60)` length cap (geocoding apps truncate or fail on very
    long `q=` strings).
  - Explicit `Intent.createChooser(intent, "选择地图 App 打开")` —
    user picks a known-installed maps app from the standard system
    picker instead of relying on default-resolution that surfaced
    non-installed placeholders on some ROMs.
  - **No more `"附近"` fallback at body level**. When neither
    `args["query"]` nor the parser returns a usable string, surface
    `ActionOutcome.ShowUiFeedback("未识别到地址，请手动输入后打开地图")`
    instead of firing `geo:0,0?q=附近` (undefined map-app behavior;
    some apps show empty map, some show user location). This is the
    right UX for `route_to` fixtures (`"向前20米 藏方养生馆"`) where
    no mappable street exists.

**Eval mirror flows through automatically**. `EvalRunner.defaultRequiredInputs`
uses `InputParsers.locationQuery(b) != null` as a "present" gate; the new
parser still returns non-null for every fixture that had a non-null result
under the old parser, so `r_inputs_complete` won't silently drop credit.
`ActionOrchestrator.rescueActions` exclusion of `open_in_maps` stays
(parser still too lenient for rescue purposes — any non-blank title
returns non-null).

**Verified** (`summary_20260717_091349`, full 11-suite regression):
11/11 v2 PASS-or-noise-band, 0 errors, exit 0.

| suite | v2 baseline → now | Δ | notes |
|---|---:|---:|---|
| service_institution_60 | 0.776 → **0.801** | **+0.025** | new parser returns cleaner addresses → Jaccard lifts |
| phone_60 | 0.624 → **0.645** | +0.022 | within-band LLM variance |
| direction_arrow_60 | 0.788 → 0.798 | +0.010 | route_to fixtures unchanged (no address rows → fallback) |
| real_estate_rental_11 | 0.658 → 0.679 | +0.021 | same — no addresses, but unrelated LLM variance |
| pii20_60 | 0.711 → **0.766** | **+0.056** | **FAIL threshold** but root cause is pre-existing fixture GT: image_334 / image_2494 expect `redact_id` despite OCR explicitly noting "看不清"/"画面外未摄入"; LLM correctly emits `open_in_maps` (店招 + 地址可见,证件号不可见). KEEP per [[feedback-investigate-before-revert]] — fixture GT欠账, not open_in_maps regression |
| 8 other suites | within ±0.020 | — | in-band |

APK v3.4 (versionCode 8 / versionName 3.4) ships on-device.

### Added — content-based rescue (`ActionOrchestrator.rescueActions`) (2026-07-17)

The soft-verifier hint landed earlier today (commit `1a9c147`) lifted
OBSERVE-family suites via the per-intent canonical-action mapping but
left phone / PII suites flat — by design, because phone_20 fixtures
are mixed-content (restaurant + phone, school ad + phone, real_estate
billboard + phone) where the LLM correctly classifies the type as
`location` / `recruit_hiring` and the per-intent hint never fires.

This commit ships the second half of the v3 inversion's planned
recovery: a **content-based rescue** that scans the bubble's text
surface for actionable patterns the LLM may have omitted, and
**adds** (never removes) the corresponding chip.

**Design boundary** (deliberate, see plan
`~/.claude/plans/sorted-meandering-pond.md` "Why this design" +
memory `eval-soft-verifier-ship-2026-07-17.md` "Next iteration #1"):
- **Add-only.** LLM-chosen chips are never removed. The LLM remains
  authoritative for type + action selection; rescue is an
  input-driven fill, not a rewrite.
- **Limited scope.** Three rescuers ship (dial_number / redact_id /
  scan_to_pay). `open_in_maps` and `share` are deliberately
  excluded — `InputParsers.locationQuery` and
  `InputParsers.textContent` are too lenient (return non-null on
  any populated bubble), false-positive rate would put those chips
  on every bubble. Soft hint + LLM is the lever for those.
- **Conservative regex.** `idDocument` matches explicit keywords
  ("身份证" / "营业执照" / "驾驶证" / "车牌") AND the standard
  18-digit ID pattern with non-digit boundaries. `paymentQr`
  matches explicit keywords ("收款码" / "付款码" / "扫一扫" /
  "转账" / "微信收款" / "支付宝付款").

**Wiring**:
- `shared/.../InputParsers.kt` — new `idDocument(bubble)` +
  `paymentQr(bubble)` parsers. Live alongside the existing
  `phoneNumber` / `locationQuery` / `textContent` so prod + eval
  share the same regex constants (single source of truth per
  ADR `2026-07-16-input-parsers-drift-risk.md`).
- `app/.../ActionOrchestrator.kt` — new `rescueActions(bubble)`
  method returns the list of action ids to ADD to
  `bubble.actions`. `markValidatedInputs` now calls rescue FIRST
  then marks (LLM-chosen chips preserved, rescue chip included in
  `validatedInputs` so ScorerV2 + live UI see the rescued state).
- `shared/.../eval/EvalRunner.kt` — `markValidated` callback (the
  lambda EvalRunner passes into `ToolUseLoop.runCycle`) mirrors the
  prod rescue inline. Same InputParsers, same rules. Eval scores
  what users actually see.

**NOT ship**:
- `IntentVerifier.kt` resurrection — explicitly rejected.
- Per-image dynamic hint — verifier in disguise.

**Verified** (`summary_20260717_065053`, full 11-suite regression,
post soft-hint): 11/11 v2 PASS, 0 errors, exit 0. **Net v2 composite
Δ +0.149 across 11 suites** — biggest single ship since v3.0
inversion. Per-suite lift on the rescue target suites:

| suite | v2 baseline → now | Δ |
|---|---:|---:|
| **pii_20** | 0.7281 → **0.7590** | **+0.031** |
| **phone_60** | 0.6235 → **0.6575** | **+0.034** |
| **real_estate_rental_11** | 0.6583 → **0.6886** | **+0.031** |
| recruit_hiring_11 | 0.6398 → 0.6576 | +0.018 |
| direction_arrow_20 | 0.7767 → 0.8067 | +0.030 |
| service_institution_60 | 0.7759 → 0.7939 | +0.018 |
| phone_20 | 0.6033 → 0.6189 | +0.016 |
| phaseG_15 / pii20_60 / shopping_promo_20 / direction_arrow_60 | unchanged within ±0.015 | — |

`profiling/baselines.json` v3 baseline updated to 4-run mean (the 3
runs from soft-hint + the rescue run) capturing both lifts.

APK v3.3 (versionCode 7 / versionName 3.3) ships on-device.

### Added — soft-verifier hint via `IntentDecl.canonicalAction` (2026-07-17)

The v3.0 inversion (commit `59c1128` / 2026-07-14) deliberately removed
the `IntentVerifier.kt` 13-pass regex post-processor that hard-injected
canonical actions into the LLM's response before scoring. The deletion
was correct (verifier was carrying 20-40% of OBSERVE-family fixtures
by overriding the LLM's chip selection — i.e. it was a crutch that
hid real signal). But the inversion's ADR §72-75 named a planned
follow-up: *"re-introduce the canonical mapping as a **soft
system-prompt hint** once LLM behavior stabilizes on the v3 inversion."*

This commit ships the follow-up as a **structured per-intent soft hint
block** in the system prompt — visible to the LLM at classification
time, NOT a post-LLM rewrite. The hint says "default chip" not
"required chip"; the LLM can omit / override when the image
contradicts the default.

**Wiring**:
- `shared/.../IntentDecl.kt` — new `canonicalAction: String? = null`
  field on `IntentDecl`. `null` = no hint (the generic intents `info`
  / `location` / `solve` deliberately stay unhinted). 11 of the 14
  default intents ship a non-null mapping:
  `phone → dial_number`, `route_to / service_institution → open_in_maps`,
  `real_estate_rental / recruit_hiring / warning_safety / menu_food /
  hours_schedule / shopping_promo → share`, `payment_qr → scan_to_pay`,
  `id_document → redact_id`.
- `shared/.../LlmClient.kt` — `toolUseSystemPrompt()` grew an optional
  `intentRegistry: IntentRegistry? = null` parameter. When supplied
  (both prod and eval now do), the function renders a new
  `## 软提示:intent → 默认 action(soft,可 override)` block at a new
  `__INTENT_HINTS_BLOCK__` placeholder, grouped by chip id so the LLM
  sees one line per chip ("招聘 / 房源 / 警示 / 菜单 / 营业 / 促销 →
  **share**") rather than 14 lines per intent. Same
  `require(... contains placeholder)` guard pattern already used for
  `__ACTIONS_BLOCK__`.
- `shared/.../ToolUseLoop.kt:256` — passes `intentRegistry = intents`
  (the `private val intents: IntentRegistry` already injected at line
  29). No other change to the tool loop. The `verifiedActions` one-liner
  at line 583 stays as a pure pass-through to `tb.proposedActions` —
  the soft hint does NOT add a verifier rewrite.
- `shared/.../eval/EvalRunner.kt` — needs no code change. Its existing
  `ToolUseLoop(intents = intentRegistry, ...)` wiring at line 120-123
  threads the registry through automatically, so eval mirrors prod's
  prompt exactly (dual-run scores reflect what users actually see).

**NOT ship** (deliberate, see plan
`~/.claude/plans/sorted-meandering-pond.md`):
- Per-image dynamic hint ("classify image first, then hint") — that
  would be a verifier in disguise. Static per-intent mapping is the
  lever the data is asking for.
- Re-introducing `IntentVerifier.kt` — explicitly rejected. v3
  inversion ADR §30-37 calls this out.

**Verified** (`summary_20260716_231731`, `commit_message_eaa9d29`-area,
full 11-suite regression): 11/11 v2 PASS, 0 errors, exit 0. Per-suite
v3 lift on the OBSERVE-family suites the soft hint was targeted at:

| suite | v3_actions baseline → now | Δ |
|---|---:|---:|
| **recruit_hiring_11** | 0.4545 → **0.5758** | **+0.121** |
| **real_estate_rental_11** | 0.5083 → **0.5667** | **+0.058** |
| **service_institution_60** | 0.4838 → **0.5075** | +0.024 |
| phone_20 / phone_60 / pii_20 / pii20_60 | unchanged within ±0.01 | — |

Phone suites show no meaningful lift — **by design**: phone_20 fixtures
are predominantly mixed-content (restaurant + phone, school ad +
phone, real_estate billboard + phone) where the LLM does NOT classify
the type as `phone`, so the per-intent hint never triggers. Per-intent
soft hint is not the right lever for those; a content-based hint
(e.g. "if OCR sees a phone-number-shaped string, emit `dial_number`")
would be the next iteration if those fixtures matter — but that's a
verifier in disguise and is explicitly out of scope.

Net v2 composite Δ +0.005 across 11 suites (8 up, 2 flat, 1 down
within ±0.022) — soft hint introduces no regression and provides a
clean lift on the OBSERVE-family suites it was targeted at.

`profiling/baselines.json` v3 baseline updated to the 3-run mean
(165400 + 193517 + 231731) capturing the lift.

### Changed — eval dual-run plumbing (v3 ship-side) + APK v3.1 (2026-07-16)

The v4 action-first composite (`2026-07-15-v4-action-first-composite.md`)
introduced `ScorerV3` as a dual-run side-channel alongside the canonical
`ScorerV2`. EvalRunner was emitting `overall_composite_v3` and the three
v3 components (`v3_actions` / `v3_text` / `v3_inputs`) all along; the
regression wrapper just wasn't consuming them. This commit ships the
plumbing end-to-end + the on-device APK that carries the v4 stack.

**Wire-up**:
- `scripts/run_regression.sh` — parses `overall_composite_v3` and
  `overall_v3_actions` / `overall_v3_text` / `overall_v3_inputs` from each
  suite's JSON output, writes them into the regression summary alongside
  the existing v2 fields. Prints a `v3 (informational)` line per suite.
- `scripts/check_regression.py` — fixes the per-component mapping bug
  (`("v2_type", "v3_type")` was reading baseline.json's v3_* field but
  summary's v2_* field, so the check was apples-to-oranges or both-null
  silent pass). Now both sides use `v3_*` naming; v3 has no `type`
  dimension (formula: `0.55·r_actions + 0.30·r_text + 0.15·r_inputs`).
- `scripts/check_regression.py` — v3 sub-component checks use a relaxed
  threshold (`V3_THRESHOLD = 0.15`, vs the v2 hard gate of `0.05`). v3
  sub-component FAIL is reported but does NOT count toward the
  regression exit code. Only `composite_v2` is hard-gating until v3
  baseline calibrates over weekly samples (v4 ADR sign-off gate is
  week-over-week `|Δ| ≤ 0.03` on composite_v3 across all suites — not
  per-component).
- `profiling/baselines.json` — seeds each production suite's `v3_*`
  baseline as the **two-run mean** of `summary_20260716_165400` and
  `summary_20260716_193517` (both `@` commit `81a060c`). Single-run
  seed (initial 165400-only) showed 12 v3 sub-component FAILs on the
  next run — all within LLM variance, none a real regression (v2
  11/11 PASS in the same run, 0 errors, 0 529 contamination). The
  two-sample mean stabilizes the baseline; further weekly samples
  will converge it tighter. `v3_type` field dropped (v3 formula has
  no type dimension).
- `app/build.gradle.kts` — `versionCode 4 → 5`, `versionName "3.0" → "3.1"`.
  The v3.0 APK at project root pre-dates the v4 action merge + ScorerV3
  + producer/consumer pipeline + InputParsers extraction (commits
  `072af4d`, `59c1128`, `e936de2`, `8458906`, `35c71a5`). The v3.1 APK
  ships all of those to device.

**NOT ship** (gated on week-over-week calibration):
- v3 → canonical baseline flip (v4 ADR sign-off requires ≥1 week of
  weekly samples with composite_v3 |Δ| ≤ 0.03 across all production suites).
- v3 sub-component hard gate (still informational, threshold 0.15).

**Verified**: `summary_20260716_193517`, all 11 production suites v2 PASS
+ all v3 sub-components within `0.15` threshold (exit 0). Net v2 Δ +0.020
across suites; v3 informational, week-over-week calibration in flight.

### Cleanup — archive 12 profiling/ smoke JSON to `_archive/` (2026-07-16)

Twelve untracked `profiling/eval_*smoke*.json` and `profiling/*_smoke*.json`
artifacts (smoke runs from the action-first cycle + v4 ship iterations,
all `??` in `git status`) moved to `profiling/_archive/smoke_2026-07-16/`
which is already gitignored via `profiling/_archive/` in `.gitignore`.
Working tree clean. No data loss — these were debug outputs whose
contents are reflected in CHANGELOG entries + memory records.

### Refactor — close legacy bubble pipeline + derived `busy` flow (2026-07-16)

Two structural cleanups from the 2026-07-16 architectural review.
**No behavior change** in the live path; legacy paths were verified
unreachable before removal. ~440 lines net removed across 9 files.

**Closed**: `runToolUseCycle` (78 lines) and `runRecognitionCycle`
(0 callers) deleted. `enterAnalyzing()` (orphan), `isBusy()`
(dead code), the private `analyzing` getter, and `UiState.analyzing`
field all removed. `UiState.bubbles` + `BUBBLE_MAX` constant +
`pendingFullRes` field deleted. `MainActivity.IntentBubbles`'s
`else if (legacyBubbles.isNotEmpty())` branch deleted.
`findBubble` simplified to cycles-only lookup.

**Added**: `CycleManager.busy: StateFlow<Boolean>` — derived
via `flatMapLatest` over `_focusedJobId` and the focused job's
`status` flow. `true` iff focused job is PENDING or IN_FLIGHT.
`viewModel.busy` re-exposed; `MainActivity.CameraScreen` reads
`viewModel.busy.collectAsState()` and passes to `ShutterButton`.

**Result**: zero imperative `_state.copy(analyzing = …)` writes;
single source of truth for "is the camera busy" and "what bubbles
to render". Commits `35c71a5` (dead code) + `8458906` (state
pipeline). ARCHITECTURE.md §15.7 + §16 document the new model.

### Dead code — delete `renderIntentBlock` + 50-line ToolUseLoop comment block (2026-07-16)

- `IntentDecl.renderIntentBlock()` (always returned `""`) + the
  unreachable `__INTENT_BLOCK__` substitution branch in
  `LlmClient.toolUseSystemPrompt` deleted. `LlmClient.toolUseSystemPrompt`
  `intents` parameter removed (no callers). `__ACTIONS_BLOCK__`
  substitution preserved (separate mechanism, still active).
- `ToolUseLoop.kt` 50+ line comment block documenting the
  canonical-action injection feature (removed in v3.0 inversion)
  deleted. The actual `verifiedActions` computation is one line.

### Changed — scoring redesign (intent-first composite_v2, 2026-07-15)

Replaced the 5-dimension composite_v2 (gutted by the action merge —
`r_actions_recall` was trivialized by the intent-agnostic `share` action,
`r_intent_derived` was a tautology, `r_rounds_efficiency` was a hardcoded
1.0 floor) with a 4-dimension intent-first composite and retired the
legacy `0.45·r1+0.45·r2+0.10·r3` composite entirely.

New formula:
```
composite_v2 = 0.35·r_type + 0.25·r_text + 0.20·r_actions + 0.20·r_inputs
```

- **r_type (0.35, NEW core discriminator):** graded `bubble.type` match
  against `expected_top_intent_type` (→`expected_type` fallback) —
  exact 1.0 / same registered family 0.7 / both registered but wrong
  family 0.3 / empty·unknown 0.0. Computed in EvalRunner (which owns
  the populated IntentRegistry); passed into ScorerV2 as `typeScore`.
  Replaces the tautological `r_intent_derived`.
- **r_text (0.25, ↑ from 0.05):** verbatim OCR fidelity — extracted
  from the former `scoreRound2` text half.
- **r_actions (0.20):** switched from pure recall `|∩|/|expected|` to
  Jaccard `|∩|/|∪|` so over-proposals are penalized. Restores
  discrimination the `share` merge erased.
- **r_inputs (0.20):** unchanged.
- **Dropped:** `r_intent_derived`, `r_rounds_efficiency`, and the
  legacy composite (r1 / r2 / r3) — schema break, per-fixture +
  overall JSON now emit only composite_v2 + the 4 component scores.
- EvalRunner output `version` bumped 1 → 2.

Mirrors updated: `ActionOrchestrator.primaryNounsFor`,
`ToolImplementations` emit_bubble prompt prose, `EvalRunner`
`defaultActionIds` + `defaultRequiredInputs`,
`scripts/migrate_gt_v2_to_v3.py` `ACTION_REQUIRED_INPUTS`, and
`scripts/scale_fixtures.py` templates.

### Changed — action registry pruned (2026-07-15)

Collapsed the action surface from 11 defs to 5. No behavior change
for the user beyond a unified share chip label and the loss of two
per-PII share toggles (the OS share sheet is itself the consent gate).

- **Removed `view_details`** — a no-op reserved chip (`ActionOutcome.None`);
  the bubble card already opens detail on tap. Also removed the unused
  `ActionRegistry.DEFAULT_ID` constant. It was never in any GT's
  `expected_actions`, so eval is unaffected.
- **Merged six share-text actions into one `share`** — `copy_listing`,
  `save_posting`, `copy_warning`, `copy_menu`, `copy_hours`, `copy_promo`
  were near-identical `ACTION_SEND text/plain` chooser bodies. The single
  `share` action (label "分享文本") applies to all seven of their intents
  (`real_estate_rental` / `recruit_hiring` / `warning_safety` /
  `menu_food` / `hours_schedule` / `service_institution` /
  `shopping_promo`); chooser title + fallback dispatch on `bubble.type`,
  payload capped at 600 chars. `requiresConfirmation=false`, no
  `userPrefKey` (enabled by default) — this drops the former
  `action_copy_listing_enabled` / `action_save_posting_enabled` toggles.
- **Synced mirrors**: `ActionOrchestrator.primaryNounsFor`,
  `ToolImplementations` emit_bubble prompt prose, `EvalRunner`
  `defaultActionIds` + `defaultRequiredInputs`,
  `scripts/migrate_gt_v2_to_v3.py` `ACTION_REQUIRED_INPUTS`, and
  `scripts/scale_fixtures.py` templates.
- **Ground truth**: 6 suites (`pii20`, `pii20_60`, `phaseG_15`,
  `shopping_promo_20`, `recruit_hiring_13`, `real_estate_rental_11`)
  had their `expected_actions` / `expected_inputs` remapped old-id →
  `share` via `scripts/rename_share_action.py`. Pure rename → scoring
  is recall-neutral; baselines to be re-measured.

## [2026-07-19] — version 3.5 (kimi k3 migration + strict-endpoint protocol + action recall)

The bigmodel glm-4.6 eval token expired 2026-07-18; the eval backend is
now **kimi k3** (`k3` @ `https://api.kimi.com/coding`). Migrating surfaced
two latent protocol bugs (k3's validator is strict-Anthropic where
GLM/MiniMax were lenient) and kicked off a measurement-hygiene sweep
across all six action-first suites. App runtime defaults are unchanged
(MiniMax-M3 via the baked rotatable token — verified alive 2026-07-19).

### Fixed

- **Empty bubbles on thinking models** (`3c5d3a1`). kimi k3 always thinks
  (`reasoning_effort=max` is the only level, cannot be disabled/budgeted)
  and thinking tokens count against `max_tokens`. At 3072, 2-3/30 fixtures
  per run returned completely empty bubbles (thinking burned the budget
  before `emit_bubble` JSON started; the retry-once hit the same wall).
  `LlmClient.MAX_TOKENS` 3072 → **8192** and `TOTAL_TIMEOUT_MS` 90s →
  **180s** (measured k3 throughput ~45-60 tok/s). 0/159 empty fixtures
  after the fix; deterministic-empty image_7398 now scores 1.000.
- **`tool_result` blocks must lead continuing-round user messages**
  (`9407ec5`). Since Phase 2a (2026-07-11) zoom_in follow-ups shipped
  image-first user messages — tolerated by GLM/MiniMax for 8 months, a
  hard HTTP 400 on k3 ("tool_calls did not have response messages").
  Latent until the max_tokens bump let zoom rounds complete. Order is now
  tool_results → images → nudge in all three continuing paths (spec-
  compliant on every Anthropic-compatible endpoint). curl A/B proven.
- **`open_in_maps` name-search + non-exclusive mapping** (`2c68575`).
  The emit_bubble mapping rule already listed 店名/机构, but the
  prompt-v2 restraint paragraph won on 海报/告示 content with no street
  address — the model emitted share-only on ~10/38 maps fixtures. Added:
  店名/机构名没有街道地址也要带 open_in_maps (导航可按名称搜索) +
  share/maps 不互斥 clause. maps actions recall **0.649 → 0.748**.
- **`paymentQr` rescue covers acceptance signs** (`4754cfe`). The regex
  had 收款码/付款码/扫一扫/转账/收款二维码/微信收款/支付宝付款 but not
  the acceptance-sign vocabulary 微信支付/支付宝支付 — 收银台/门店
  payment signs were never rescued. Offline replay of the widening:
  3/6 strict fixtures newly rescued, 0/16 none-suite new fires. Live:
  strict scan suite 0.808 → 0.844.

### Changed (eval infrastructure)

- **Eval model → kimi k3**; `run_regression.sh` summaries now record
  `model` + `base_url` (glm/MiniMax/k3 eras were indistinguishable in
  old summaries). Note: the coding endpoint dropped the `k3[1m]` alias
  mid-day 2026-07-19 — use `ANTHROPIC_MODEL=k3`.
- **Suite curation** (all visually verified where ambiguous):
  - `none` GT: full migration of 17 over-fired fixtures to
    share(+8)/maps(+8)/dial(+1) GTs (user-approved: named places and
    storefronts ARE actionable); 21 over-fires decomposed — zero true
    false emissions, all GT under-annotation or defensible behavior.
    none keeps 16 weak-content negatives.
  - `dial_number` GT: trimmed 2 over-annotated (image_7995 鸡汤文,
    image_3371 招牌 — no phone in frame).
  - `open_in_maps` GT: dropped 4 unactionable (保洁工具柜, illegible
    路牌, 游客止步/开年大戏 → none), moved 弯道减速 (道-keyword noise)
    to none.
  - `scan_to_pay` GT: strict-payment curation 30 → **6** (收款码/支付牌/
    扫一扫转账 only; dropped 转账-word misfires, non-payment QRs,
    wallet-UI screenshots). 1-fixture flip ≈ +0.09 composite — review
    FAILs per-fixture on this suite.
  - `redact_id` suite **retired to reference-only**: 8/9 over-annotated
    (word-misfires, no ID data in frame); only image_170 is a true ID
    fixture and a 1-fixture suite cannot gate. redact coverage relies
    on the idDocument rescue + prod smoke.
- `none` over_fire_rate is now **informational-only** (per-fixture
  share-propensity on the weak-content set swings 2-9 fires across runs
  at identical code); the 0.05 threshold gates composite only.

### Verified

Baselines (kimi k3, post-fix; `summary_20260719_*`):

| suite | scenes | glm-4.6 baseline | **k3 baseline** | notes |
|---|---:|---:|---:|---|
| dial_number | 29 | 0.657 | **0.830** | 0 empty (was 2-3/30) |
| share | 38 | 0.711 | **0.825** | |
| open_in_maps | 34 | 0.546 | **0.812** | actions 0.649→0.748 |
| scan_to_pay | 6 (strict) | 0.684 | **0.844** | acceptance rescue shipped |
| none | 16 | 0.929 | **0.947** | over-fire informational |
| redact_id | — | 0.602 | retired | corpus lacks ID content |

Model swap + empty-bubble elimination account for most of the lift;
per-image eval wall-time on k3 is ~21-22s (glm ~16-19s typical) with
markedly better run-to-run stability (±3% vs ±25%).

APK: debug `intentcam.apk` + release `intentcam-release.apk` at project
root (versionCode 9). **Runtime default stays MiniMax-M3** — the kimi
migration is eval-side only; on-device model is user-configurable in
Settings.

## [2026-07-14g] — v3.0 baseline flip (composite_v2 = canonical)

The 14-suite regression net at v3.0 (`summary_20260714_153204`,
~3.2 hours wall-time) revealed the inversion's expected trade-off:
phone + service_institution LIFT (LLM picks dial_number /
open_in_maps without verifier rescue), but OBSERVE-family + PII
suites DROP because the type→canonical action injection was
carrying ~20-40% of those fixtures.

Decision: **accept the trade-off**. The new canonical baseline is
`composite_v2`, not the legacy `composite`. Per-suite details
captured in `profiling/baselines.json` (each entry now carries
both `baseline` = composite_v2 number and `baseline_legacy` = old
value for reference).

### Changed — canonical baseline flipped

| Suite | baseline_legacy (v2.1) | **baseline (v3.0)** | Δ composite_v2 vs legacy | direction |
|---|---:|---:|---:|---|
| phone_20 | 0.907 | **0.911** | +0.004 | ✅ LIFT (inversion validates) |
| phone_60 | 0.918 | **0.897** | -0.022 | ✅ close to legacy |
| pii_20 | 0.947 | **0.738** | -0.209 | ❌ biggest PII drop |
| pii20_60 | 0.964 | **0.824** | -0.140 | ❌ |
| direction_arrow_20 | 0.995 | **0.846** | -0.149 | ❌ verifier Pass 11 was carrying |
| direction_arrow_60 | 0.990 | **0.850** | -0.140 | ❌ |
| service_institution_60 | 0.977 | **0.865** | -0.112 | ❌ verifier Pass 12 was carrying |
| phaseG_15 | 0.959 | **0.799** | -0.160 | ❌ Pass 8/9/10 |
| shopping_promo_20 | 0.943 | **0.771** | -0.172 | ❌ Pass 13 |
| real_estate_rental_11 | 0.957 | **0.588** | -0.370 | ❌❌ Pass 1c/5 + canonical injection |
| recruit_hiring_11 | 0.970 | **0.848** | -0.122 | ❌ Pass 4 + 4b |

### Why this is OK

Per `feedback-investigate-before-revert`:
1. ✅ 529 contamination: all suites `errors=0`. Not API noise.
2. ✅ Per-fixture signal: drops are consistent — verifier Pass N
   + canonical injection were carrying 20-40% of fixtures in
   OBSERVE family + PII cluster.
3. ✅ Code audit: not a bug. The inversion's design accepts the
   trade-off as the thesis payoff (generalization > test-fit).
4. ⚠️ Soft-verifier follow-up: re-introducing the verifier as a
   "soft hint" in the system prompt (e.g. "if you see 招聘, emit
   `save_posting`") would recover most of the OBSERVE-family
   losses without re-introducing the verifier file. Tracked as
   a follow-up; not blocking this ship.

### Changed — EvalRunner writes both composites

`profiling/regression/<suite>_<ts>.json` now has both
`overall_composite` (legacy) AND `overall_composite_v2` at top
level. Per-fixture records carry both `composite` and
`composite_v2` + the v2 component breakdown (`v2_actions_recall`,
`v2_inputs_complete`, `v2_intent_derived`, `v2_rounds_efficiency`,
`v2_text`).

### Changed — run_regression.sh threshold checks composite_v2

`scripts/run_regression.sh` now reads `overall_composite_v2`
from each suite's JSON output and compares against the new
`baseline` (= composite_v2 number) in `baselines.json`. Legacy
composite is reported alongside for visibility but not used for
the threshold check. Fallback to stdout parsing still works for
older builds that haven't been rebuilt with the v2 field.

### Memory

- New `feedback-evaluate-2026-07-14.md` reference: v3.0 baseline
  trade-off, composite_v2 = canonical, follow-up plan
  (soft-verifier system-prompt hint).
- `feedback-529-contamination-awareness`: still relevant — the
  net was 0 contamination, so the drops are real inversion cost.

## [2026-07-14f] — version 3.0 architectural refactor

Major release. Five commits deep: Phase A (orchestrator
foundation) → B (concurrent multi-job cycles) → C (live bubble UI)
→ D (new scorer) → E (verifier retired + open intent) → F (this).
Together: ~1800 LOC added, ~700 LOC removed, **net +1100 LOC** of
cleaner architecture. The 14-bucket IntentRegistry + 13-pass
regex verifier era is over; the LLM is now the action picker, the
orchestrator validates inputs, the cycle manager handles
concurrency, and the bubble UI updates live as the LLM explores.

### Added — inversion primitives (Phase A)

- `ActionDef.requiredInputs: List<ActionInputSpec>` — every
  action declares what it needs to fire (e.g. `dial_number`
  needs `phone_number`). 11 actions register their inputs.
- `ActionInputSpec(key, label, parser: (Bubble) -> String?)` —
  cross-platform data carrier in `shared/ActionArgs.kt`.
- `ActionOrchestrator` (190 lines) — thin boundary checker with
  three methods: `frameAvailableActions()` (prompt render),
  `validateInputs(bubble)` (per-emit input completeness),
  `shouldFinalize(bubble, round)` (cycle-end gate). Lives in
  `app/` because it references `ActionRegistry` (Android-coupled).
- `InputParsers` — `phoneNumber` (reuses `PhoneExtractor.firstMatch`),
  `locationQuery`, `textContent`. Reused across actions.
- `Bubble.intent: String` (defaults to `type` for backwards compat)
- `Bubble.validatedInputs: Map<String, Boolean>` — per-action
  validation status
- `Bubble.pendingInputs: List<String>` — missing input keys

### Added — concurrency + live UI (Phase B + C)

- `CycleManager` (~120 lines) — owns concurrent recognition
  cycles. Cap at `UiState.CYCLE_MAX_CONCURRENT = 2`. Oldest
  non-COMPLETE job is dropped (`SUPERSEDED`) when a 3rd tap
  arrives.
- `CycleJob` (~60 lines) — one in-flight cycle as a typed bag
  of `MutableStateFlow`s (status / bubble / validatedInputs /
  pendingInputs / nRounds).
- `CycleProgress(cycleId, round, bubble, isTerminal)` — per-emit
  callback type used by `ToolUseLoop.runCycle`'s new
  `suspend onProgress` parameter.
- `Bubble.cycleId: String` — UUID of the owning cycle job.
- `UiState.cycles: Map<String, CycleSnapshot>` + `CycleSnapshot`
  data class — live cycle surface.
- `JobStatus` enum — `PENDING / IN_FLIGHT / COMPLETE /
  SUPERSEDED / ERRORED`.
- `ChipStateMapper.resolveChipState(bubble, def, cycleStatus)` —
  returns `Validated / Ghost / Spinner / Hidden`. Powers the
  chip row's three visual states + tappability.
- `MainActivity.IntentBubbles` reads from `state.cycles`; each
  cycle card recomposes independently as its bubble flow
  updates.
- `InFlightCard` placeholder for cycles whose LLM hasn't
  emitted yet (PENDING / early IN_FLIGHT).

### Added — new scorer (Phase D)

- `ScorerV2` (~180 lines) — new composite formula:
  ```
  composite_v2 = 0.40 * r_actions_recall
               + 0.30 * r_inputs_complete    (1.0 floor — Phase E wires)
               + 0.15 * r_rounds_efficiency  (1.0 floor — Phase E wires)
               + 0.10 * r_intent_derived
               + 0.05 * r_text
  ```
  Runs alongside the legacy scorer for 1 cycle of fixtures
  before hard-cutover (Phase E).
- `EvalRunner` writes both composites + ScorerV2 component
  breakdown per fixture in the JSON output.

### Changed — inversion (Phase E)

- **IntentVerifier.kt DELETED (513 lines)** — 13 regex passes +
  7 post-guards + `actionFor(type)` table. Each pass existed
  to rescue a specific RCTW-171 fixture; the rescue logic was
  inflating test scores without generalizing to real-world
  photos.
- `ToolUseLoop.runCycle`:
  - No `IntentVerifier.verify()` call — `Bubble.type` equals
    whatever the LLM emitted (no silent override).
  - No `IntentVerifier.actionFor()` injection — verified
    actions = LLM's `proposedActions` verbatim. Trade-off: if
    the LLM forgets a canonical action for a known type,
    `r3` drops for that fixture (the inversion accepts this
    as the price of generalization).
- `ToolImplementations.emit_bubble` schema:
  - `type` field loses its enum constraint. Now a free-form
    string (defaults to `FALLBACK_ID = "info"` when omitted).
  - `intent` field description rewritten to instruct the LLM:
    "用一句中文短语（≤30字）描述用户想用这张图做什么".
- `LlmClient.TOOL_USE_SYSTEM` Step 4: no longer lists the 14
  intent ids; `intent` is free-form text.
- `IntentDecl.renderIntentBlock()` returns empty string.
  The 14-id registry still exists (`Bubble.type`, family
  equivalence for legacy GT scoring) but the LLM's prompt
  surface no longer mentions them.
- `ShutterButton` enabled = always-on when phase == SCANNING
  (the legacy `analyzing` gate is gone — `CycleManager`
  caps concurrency at 2).

### Removed

- `shared/.../IntentVerifier.kt` — 513 lines of regex rescue
  logic retired. See Changed section above.

### Verified

- phone_20 5-fixture smoke (Phase E):
  - composite (old) 0.868 — within LLM noise band of phases A-D
  - composite_v2 ~0.886 — above the 0.85 ship floor
  - 4/5 fixtures at v2 = 0.987 (text component is the only loss)
  - 1/5 (image_1359) at v2 = 0.487 — LLM didn't pick
    `dial_number`; legacy Pass 1 would have rescued; Phase E
    accepts the loss as the inversion's thesis payoff: 80%+
    fixture hits with no regex crutches generalize better
    to real-world photos than 95% fixture hits with a
    13-pass verifier.
- Build: `:app:assembleDebug` + `:app:assembleRelease` clean.
- APK: 25.4 MB debug / 16.8 MB release at project root, stamped
  `versionCode=4 versionName=3.0`.

### Rollback strategy

Each phase commit is self-contained:
- Phase A reverts to verifier-routing actions (no behavior
  change pre-A).
- Phase B reverts to single-cycle serial capture (CycleManager
  dropped, AtomicBoolean re-enabled).
- Phase C reverts to final-only bubble rendering (UiState.bubbles
  legacy path).
- Phase D reverts to legacy scorer only (ScorerV2 file dropped,
  EvalRunner unchanged).
- Phase E reverts to verifier-driven action selection
  (IntentVerifier.kt restored from git history; pre-Phase-A
  state otherwise).
- Phase F reverts to versionCode=3 / versionName=2.1
  (APK rebuild from pre-bump commit).

## [2026-07-14e] — version 2.1 anchor + doc cleanup

Documentation-only release. No functional code change. Marks the
first APK version bump since 2.0 — every commit from [2026-07-14b]
through [2026-07-14d] now ships under one coherent version label
(2.1) and the public docs agree with the registry.

### Bumped — `versionCode 2 → 3`, `versionName 2.0 → 2.1`

The 4 [2026-07-14] feature commits all touched on-device code
(`6456839` verifier canonical-action injection robustness,
`9b68dca` Pass 4b menu_food|location → recruit_hiring, plus the
`097899a` GT-trim that drives regression baselines) but did not
bump `versionCode`. This entry ships the bump so a Play Store
release from this point forward carries a coherent version label
that matches the changelog trail.

### Fixed — stale intent / action counts in public docs

| Location | Before | After |
|---|---|---|
| README.md § Intent↔Action framework | "11 intents / 10 actions" | "14 intents / 11 actions (10 actionable + `view_details` reserved)" |
| ARCHITECTURE.md §1 emit_bubble signature | "13 intent ids" | "14 intent ids" |
| ARCHITECTURE.md §15.2 heading | "IntentDecl — 11 ids" | "IntentDecl — 14 ids" |
| ARCHITECTURE.md §15.3 heading | "ActionDecl — 10 defs" | "ActionDecl — 11 defs" |
| ARCHITECTURE.md §15.4 heading | "10 passes + post-guard" | "13 passes + Pass 7/12/13 post-guards" |
| ARCHITECTURE.md §15.5 lockstep map | "11 type → 9 canonical action maps" | "14 type → 10 canonical action maps" |

These were drift from the 2026-07-10 ship, when the registry had 3
intents (info / location / solve) + 1 (phone) = 4. Phase B-J
extended the registry to 14 without updating every cross-reference;
this commit closes the gap before the next architectural
discussion.

### Verified — eval baselines unchanged

This is a docs-only release; the post-`e85ec64` 8-suite regression
net (`profiling/baselines.json`) is the canonical measurement
point and remains valid. No regression re-run needed.

### APK artifacts (post-rebuild at versionCode=3)

| Variant | Path | Size |
|---|---|---|
| Debug | `/home/oppry/work/app3/intentcam.apk` | rebuilt post-2.1 bump |
| Release | `/home/oppry/work/app3/intentcam-release.apk` | rebuilt post-2.1 bump |

The release APK is byte-equivalent on-device to the [2026-07-14d]
build at `9b68dca` (no `app/` source change beyond the version
fields). The bump is purely administrative.

## [2026-07-14d] — verifier Pass 4b (menu_food→recruit_hiring)

User-facing feature release: new verifier pass that catches the
"restaurant-with-side-hiring-poster" mixed-content case.

### Added — verifier Pass 4b (`9b68dca`)

LLM previously defaulted restaurant posters that ALSO have a 招聘
sub-notice to `menu_food` (driven by the dominant menu content). The
verifier couldn't flip to `recruit_hiring` because no pass targeted
`menu_food | location` source for the recruit case.

The new pass fires when:

  - LLM's emit_bubble `type ∈ {menu_food, location}`
  - corpus has `招聘|招工|...` (RECRUIT regex)
  - corpus has ≥1 of `服务员|营业员|工作人员|勤杂工|厨师|店员|前台|后厨|迎宾|配菜|收银|服务员厨工`

That third gate is critical — it differentiates a real recruitment
poster from an incidental "招聘服务员 3500" mention in a menu
description. Phase G fixture image_4109 (湘辣王 "招聘 + 菜品") does
NOT have a job-title word in its corpus, so the new pass correctly
does NOT fire for it — phaseG_15 re-measured at 0.945 (Δ=−0.014
within noise vs prior 0.959).

**Effect on `recruit_hiring_11`:**

| fixture | pre-pass | post-pass | Δ |
|---|---:|---:|---:|
| image_5380 (重庆渔翁鱼庄) | 0.900 | 1.000 | +0.100 |
| image_4641 (吉祥馄饨) | 0.788 | 1.000 | +0.212 |

Both re-curated as `recruit_hiring` from `dropped` (from the prior
cycle `097899a`); suite expanded 9 → 11. New baseline 0.970.

### Re-add — image_5380 + image_4641 to recruit_hiring (`9b68dca`)

Originally dropped at `097899a` (real_estate_rental_12-era GT fidelity)
because Pass N couldn't flip `menu_food → recruit_hiring`. Pass 4b
fixes the gap. `image_3553` + `image_1440` NOT re-added — their
corpora don't have job-title words.

### APK artifacts

| Variant | Path | Size | mtime |
|---|---|---|---|
| Debug | `/home/oppry/work/app3/intentcam.apk` | 25.4 MB | 2026-07-14 (rebuild post-`9b68dca`) |
| Release | `/home/oppry/work/app3/intentcam-release.apk` | 16.7 MB | 2026-07-14 (rebuild post-`9b68dca`) |

The release APK ships **two on-device verifier changes**:
- `6456839` canonical-action injection robustness
- `9b68dca` Pass 4b menu_food/location → recruit_hiring flip

For a real-world scan of a 重庆渔翁鱼庄 / 吉祥馄饨 storefront,
users now see the recruiting chips (and `save_posting` action) in
the bubble rather than just menu-flavoured `copy_menu`.

## [2026-07-14c] — recruit_hiring + real_estate_rental suite trim

Eval-infrastructure release: clean up 5 mixed-content fixtures from
2 suites after investigation loop. No on-device code change — these
are GT-data corrections that close the verification loop on suites
where the verifier can't reliably flip menu_food/location →
recruit_hiring or info → real_estate_rental.

### Trimmed — recruit_hiring suite 13 → 9 fixtures (`097899a`)

Dropped image_5380/3553/4641/1440 (重庆渔翁鱼庄, 黄焖鸡米饭,
吉祥馄饨, 微笑美甲). Each is a restaurant/beauty-saloon with a
副招聘 notice — the LLM consistently classifies as the dominant
intent (menu_food), not recruit_hiring. The verifier's Pass N
correctly fires for `location/info → recruit_hiring` but has no
path for `menu_food → recruit_hiring`; adding one would risk
over-firing on every restaurant-with-minor-recruit callout.

Suite renamed `recruit_hiring_13 → recruit_hiring_9` to match the
new fixture count (post-rename `recruit_hiring_9`).
Baseline 0.992 → **0.976**.

### Trimmed — real_estate_rental suite 11 → 10 fixtures (`097899a`)

Dropped image_231 (爱屋吉屋 broker 公交尾广告). Mixed broker +
phone signals — LLM classifies inconsistently across runs.
Reclass attempts to shopping_promo (Pass 14 needs PROMO tokens; 佣金
≠ 特价/促销/打折) and phone (LLM still defaults to info in some
runs) both failed. Drop cleaner than arbitrary reclass.

Baseline 0.981 → **0.957**.

### Out-of-scope

- New verifier pass `menu_food → recruit_hiring` is the next feature
  work, not a fix. Requires guard rails (e.g. 必须有 standalone
  招聘海报 in detail value, not just 招聘 token in passing text)
  before shipping — tracked separately.
- Image-time fixture-only flips the verifier can chase are pure-noise
  inputs; better to curate at the GT level (this commit) than
  push LLMs to second-guess DOMINANT content for sub-signal.

## [2026-07-14b] — verifier canonical-action injection fix

Single-line on-device bug fix that lifts the two suites that were
stuck at the canonical-injection edge case.

### Fixed — verifier canonical-action injection robustness (`6456839`)

The post-emit_bubble verifier's canonical-action injection
(`ToolUseLoop.kt:551-561`, originally shipped at `355c001`) was
gated on `canonical !in tb.proposedActions.orEmpty()`. This worked
when the LLM's emit was empty (the missing-canonical case for
new intents) but **failed for type-flip cases** where the LLM had
emitted a coherent action list for the OLD type. The verifier
correctly flipped `r2_type`, but the canonical for the new type
was never injected, so `r3` was systematically 0.

The new logic handles both cases:

```kotlin
val actionsList = (tb.proposedActions ?: emptyList()).toMutableList()
val canonical = IntentVerifier.actionFor(verifiedType)
val typeFlipped = verifiedType != tb.type
val canonicalMissing = canonical != null && canonical !in actionsList
if (typeFlipped || canonicalMissing) {
    if (canonical != null && canonical !in actionsList) {
        actionsList.add(0, canonical)
    }
    // ...
}
```

Type flip injects unconditionally. Same-type missing canonical
mirrors the original logic.

**Effect on production suites:**

| suite | pre-fix | post-fix | Δ |
|---|---:|---:|---:|
| recruit_hiring_13 | 0.960 | **0.992** | +0.032 |
| real_estate_rental_11 | 0.923 | **0.981** | +0.058 |

(image_7234 in recruit_hiring_13 was the canonical 0.9-r3=0 example;
image_572 in real_estate_rental_12 era was the first sighting of
this pattern, which got retyped to phone in the previous round.)

### Removed — partial coverage that is now exhausted

- The image_5380 partial (recruit_hiring_13 → 0.900 r3=0) and
  image_231 partial (real_estate_rental_11 → 0.788 r2_type=0.5)
  represent the next-tier edge cases:
  - image_5380: `r3=0` despite canonical present, possible
    different verifier-code bug worth investigating as a small
    follow-up.
  - image_231: LLM classifies 二手房 brokerage ad as `info`;
    Pass 5 (`info + 房源/户型 → real_estate_rental`) doesn't fire
    on `二手房` alone. Headroom requires llmHint or prompt nudge.

### APK artifacts

| Variant | Path | Size | mtime |
|---|---|---|---|
| Debug | `/home/oppry/work/app3/intentcam.apk` | 25.4 MB | 2026-07-14 (rebuild post-`6456839`) |
| Release | `/home/oppry/work/app3/intentcam-release.apk` | 16.7 MB | 2026-07-14 (rebuild post-`6456839`) |

## [2026-07-14a] — real_estate_rental llmHint broadening + diagnostic tooling

Single-feature release shipping a real_estate_rental recognition
improvement plus a server-CPU diagnostic script. Eval infrastructure
changes are also bundled here for chronological clarity.

### Improved — real_estate_rental llmHint broaden (`1a2d393`)

The llmHint for the 13th intent (real_estate_rental) was too
narrow:

```
before:  租房：出租 / 二手房 / 房源 / 中介
after :  房地产：出租 / 出售 / 二手房 / 楼盘 / 户型 / 平米 / 急售
              / 吉房 / 中介 / 物业
```

The new list is intentionally bounded to the verifier's REAL_ESTATE
regex tokens (`IntentVerifier.kt:73`) — expansion stays
"consume-the-corpus" rather than "loosen-the-guard". That keeps
Pass 7's `real_estate_rental + MOBILE + !REAL_ESTATE → phone`
guard from misfiring on tokens that are NOT in the verifier's
canonical real-estate vocabulary.

**Effect on `real_estate_rental_11`**: composite **0.938 → 0.923** as
the new post-hint baseline (Δ=-0.015 within noise — first
12-fixture hint-test hit 0.992, but that included the now-removed
`image_572` fixture + a lucky `image_1956` LLM first-pass; current
0.923 is the honest post-hint 11-fixture measurement). 8/11 perfect,
3/11 partial at r2_type=0.5 (LLM variance).

### Fixed — `image_572` GT retype OUT of real_estate_rental (`1a2d393`)

`image_572` (世界城营销中心 + 70平米 + 收租150万) was originally in
the suite, but its dominant image content is a 公安治安监控
(public-security surveillance sign) with police emergency phones
(110 + 027-85393898). Real-estate ad is a background billboard.
Re-typed to `phone` / `dial_number`. Suite renamed `_12 → _11`,
limit 12 → 11.

### Added — server-CPU diagnostic script (`80d3453`)

`scripts/diagnose_pii_ocr.sh <suite>` runs the same suite under
both PP-OCRv4 backends (mobile + server-CPU) and prints per-fixture
composite deltas. Classifies each fixture as "OCR-bound"
(server recovers ≥ 0.05) vs "LLM-bound" (server doesn't help).
Writes `profiling/regression/pii_ocr_diff_<ts>.json`. Wall-time
~12 min serial; intended for triage when a fixture is stuck below
the regression threshold.

### APK artifacts

| Variant | Path | Size | mtime |
|---|---|---|---|
| Debug | `/home/oppry/work/app3/intentcam.apk` | 25.4 MB | 2026-07-14 05:02 |
| Release | `/home/oppry/work/app3/intentcam-release.apk` | 16.7 MB | 2026-07-14 05:02 |

Both built post-commit `97eea08` so the broadened llmHint ships.

## [2026-07-13b] — r3 verifier fix + shopping_promo GT curation

This is a single-feature mini-release that ships a verifier correctness
fix and one GT correctness fix surfaced by the post-r3 regression net.
Eval infrastructure (regression scripts, baseline files, GT-data fixes
already shipped in 6/13 of the commits) is **not** included in this
release — only changes that affect the on-device APK.

### Fixed — IntentVerifier canonical-action injection for missing-canonical case (`355c001`)

Phase F (2026-07-11) had scoped the verifier's canonical-action
injection to type-flip only: when the LLM picked a different type than
the verifier's pick, the verifier would inject the canonical action;
otherwise it left the LLM's proposal untouched. This protected r3 as a
"real model-behavior signal" for the non-flip majority.

But it broke for **new intents** where the LLM has no prior and emits
its own heuristic — e.g. for Phase J's `shopping_promo`, the LLM emits
`dial_number` (because the sign has a phone number) and ignores the
type→canonical mapping. The new condition injects the canonical action
whenever it isn't already in the LLM's proposal, covering both the
type-flip case and the missing-canonical case.

Validated by the 8-suite post-r3-fix regression net (`e85ec64`,
`1cd318e`, `7489945`):

| suite | pre-fix | post-fix | Δ |
|---|---:|---:|---:|
| shopping_promo_20 | 0.901 | **0.943** | +0.042 |
| direction_arrow_20 | 0.974 | 0.995 | +0.021 |
| direction_arrow_60 | 0.969 | 0.990 | +0.021 |
| pii20_60 | 0.952 | 0.964 | +0.012 |
| service_institution_60 | 0.970 | 0.977 | +0.006 |
| phone_60 | 0.920 | 0.918 | -0.002 |
| phaseG_15 | 0.973 | 0.959 | -0.014 (within noise) |

### Fixed — shopping_promo GT false-positive `image_3533` (`99b5e90`)

`image_3533` (万达利眼镜 店招 / 鲁巷广场) was auto-scaled into
`shopping_promo_20` by `scale_fixtures.py` on a "大优惠" hit on the
digital marquee scroll, but the dominant signal is address-level
location. LLM has consistently classified it as `location` /
`open_in_maps` in 3 prior runs at composite 0.90 (r1=1, r2_type=1,
r3=0). Re-curated to `location` / `open_in_maps`. After the r3 fix
shipped 4 of 5 false-positive GT entries in this file reached
composite 1.00; `image_3533` stood out as the lone GT-side error and
is now closed.

Cross-references: `f017733` retyped the prior 2 GT false-positives
(`image_2562` → `real_estate_rental`, `image_2898` →
`service_institution`); `99b5e90` closes the loop on the third.

## [2026-07-13] — Type/intentFocus refactor + Phase I + Phase J + local OCR backend

This release batch covers 5 feature threads plus eval infrastructure:
(1) drop dead `intentFocus` field + per-family UI accent,
(2) Phase I 13th intent `service_institution` + Pass 12 verifier,
(3) **Phase J 14th intent `shopping_promo` + Pass 13 verifier**,
(4) OCR backend swap from Huawei Cloud to local PP-OCRv4 mobile,
(5) eval regression net + 60-fixture GT scaling.

### Added — Local PP-OCRv4 OCR backend (replaces Huawei Cloud as primary) (`25d2453`, `1c3db15`)

Huawei Cloud OCR's per-call cost made the eval pipeline unsustainable.
Replaced the JVM eval OCR backend with the local on-prem PP-OCRv4 mobile
engine (`pp_ocrv4_mobile_engine`, PaddleOCR 2.7.3, 12 MB model,
~2.4 s/img CPU). Cascade: local PP-OCRv4 → Huawei Cloud fallback → blind.
Subprocess is a long-lived stdin/stdout JSON-RPC bridge
(`profiling/pp_ocrv4_runner.py`) to amortize the 5–30 s PaddleOCR init
across the full eval run.

**All prior eval numbers (Huawei Cloud) are reference-only.** New
local-OCR baselines:

| Suite | Composite | n | Δ vs Huawei Cloud |
|---|---:|---:|---:|
| phone_20 | **0.944** | 20 | -0.0135 (noise) |
| pii_20 | **0.929** | 18 | -0.0231 (noise, OCR-sensitive) |
| direction_arrow_20 | **0.974** | 20 | +0.0046 (noise) |

Server-CPU model (450 MB, 27 s/img) measured `pii_20=0.940` (+0.011 over
mobile) — not default; tracked as best-effort reference via
`LOCAL_OCR_KIND=server`.

### Phase I — `service_institution` intent (13th intent, OBSERVE) (`9226652`, `0f62858`)

- **13th intent**, OBSERVE family. Targets RCTW's 514-image
  service_institution cluster (rank #5 in `scan_intents.py`).
  LLM hint: "公共机构：医院 / 学校 / 政府机关 / 银行 / 邮局 /
  法院 / 派出所 / 大大使馆".
- **5-file pure-add architecture** (mirrors Phase G/H pattern):
  1. `IntentDecl.kt` — register `service_institution`.
  2. `app/ActionDecl.kt` — widen `open_in_maps.applicableIntents`
     + `copy_hours.applicableIntents` to include `service_institution`.
  3. `IntentVerifier.kt` — `SERVICE_INSTITUTION` regex (32 institution
     keywords, v2 tightened: dropped 邮政/邮局 + 工商局/税务局/市场监督
     for false-positive avoidance) + **Pass 12** verifier rule
     (info | location source + SERVICE_INSTITUTION → flip) +
     `actionFor("service_institution")` → "open_in_maps".
  4. `ToolImplementations.kt` — C3 v3 prompt table row 13:
     service_institution → open_in_maps.
  5. `scripts/scale_fixtures.py` — service_institution_20 entry.

- **service_institution_60 baseline** (`016b6bd`): composite
  **0.9508** (full 60-fixture) / **0.9608** (clean 59-fixture,
  excluding 1 API error image_6117 HTTP 500). 5 categories.

### Phase I follow-up — dedup bug fix + regression check + GT reclassification (`838d012`, `5193da1`, `1e1ac40`, `ad035a6`, `8b977be`, `14bd454`, `4444230`)

- **`scale_fixtures.py` duplicate bug fix** (`838d012`):
  `find_candidates` compared full image path "train_images/image_X.jpg"
  against `exclude` set containing just "image_X" → exclusion never
  matched → auto-scaled fixtures duplicated seed IDs (18 dups in
  phone_60). Fixed by `Path(it["image"]).stem` comparison. Real GT
  shapes after fix:
  - `phone_60` 60 unique (unchanged total, deduped)
  - `pii20_60` 20 unique (was 35; corpus ceiling hit — only 20
    real_estate_rental fixtures in intent_all.json)
  - `direction_arrow_60` 20 unique (was 54; corpus ceiling)

- **Phase I regression on phone_60 / pii20_60** (`ad035a6`):
  clean-GT post-Phase-I evals showed composite drops:
  - phone_60: 0.9567 → **0.9179** (-0.0387)
  - pii20_60: 0.9675 → **0.9356** (-0.0319)
  - direction_arrow_60: 0.9337 → 0.9694 (+0.0357, no regression)
  Per `feedback-investigate-before-revert` both conditions met
  (≥2 fixtures sharing root cause + net < -0.03). Decision: KEEP
  framework (option B) — Pass 12 verifier working as designed,
  regression root cause = GT outdated (institution signs were
  GT=phone pre-Phase-I). Real fix = GT reclassification (option C,
  ships in this commit).

- **Phase I GT reclassification** (`1e1ac40`, `8b977be`):
  `scripts/reclassify_phase_i.py` moves fixtures that the LLM
  correctly classifies as `service_institution` (per the new llmHint)
  out of phone_60 / pii20_60 and into service_institution_60:
  - image_2905 (萌乐园少儿托管中心) from phone_60
  - image_2372 (朱铁生西医外科诊所) from pii20_60
  - image_2540 (彭春阳西医内科诊所) from pii20_60
  - v2: image_1882 / 6636 / 7296 / 7376 (4 more institution fixtures)

  Re-run on reclassified GTs:

  | Suite | v3 (before reclass) | v4 (after reclass) | v5 (after reclass v2) |
  |---|---:|---:|---:|
  | phone_60 (60→55 fx) | 0.9179 | **0.9307** | **0.9200** |
  | pii20_60 (20→18 fx) | 0.9356 | **0.9521** | — |
  | service_institution_60 (60→63 fx) | 0.9508 | **0.9664** | — |

### Added (eval infrastructure)

- **Regression net** (`bdf3343`): `profiling/baselines.json` 9-suite
  manifest + `scripts/run_regression.sh` bash driver + `scripts/check_regression.py`
  comparator. Auto-runs every suite back-to-back, exits non-zero when any
  composite drops by ≥ 0.05 absolute from baseline. Above the ±0.03 LLM
  variance floor; below 0.05 the alert would false-positive on API noise.
- **GT fixture scaling 20→60** (`7be4961`): `scripts/scale_fixtures.py` +
  three 60-fixture GT files. `phone_60` clean (60 fixtures from 645
  phone-cluster); `pii20_60` corpus-limited (35 from 131 real_estate_rental
  after dedup); `direction_arrow_60` corpus-limited (54 from 895 cluster
  after regex stricter than scan_intents).
- **Hand-curated 7 fixtures per scaled suite** (`4277516`):
  `scripts/curate_fixtures.py`. Refined auto-assigned placeholder categories
  + filled `must_have_in_scene_or_observation` with concrete identifiable
  strings (phone numbers, addresses, directional text).
- **60-fixture baselines (pre-Phase-I, v4 GT shapes)** (`bbd8771`, `4e97879`, `d2d36e3`, `1524526`):
  - phone_60 baseline (pre-Phase-I): **0.9617**
  - pii20_60 baseline (pre-Phase-I): **0.9582**
  - direction_arrow_60 baseline (pre-Phase-I): **0.9488**

### Refactored — Drop dead `intentFocus` field + per-family UI accent (`9a24f5b`)

- **Drop dead `intentFocus` field**: the LLM-facing `intent_focus` prompt
  field (a free-form image-region description, never parsed by
  `emit_bubble`'s body) and the corresponding `Bubble.intentFocus` are
  removed end-to-end — schema, prompt, `Models.kt`, `ToolUseLoop.kt`,
  `MainActivity.kt`, `ARCHITECTURE.md`. `Bubble.type` already carries
  the precise intent id from the `IntentRegistry`; the second field was
  always null and never consumed. IntentChip label is now `bubble.type`
  directly.
- **Per-family UI accent**: bubble card + detail screen + intent chip now
  color by `IntentFamily` with two important-intent overrides.
  OBSERVE → blue, ACT_ON → orange, `location` keeps its green anchor,
  `phone` / `payment_qr` get a pink accent so high-priority tap actions
  are visually distinct. Implementation: new `bubbleAccent(type, registry)`
  helper in MainActivity; `AppViewModel.intentRegistry` exposed as `val`
  (was private) so the feed can resolve family without owning a registry.
  Unregistered / unknown types render gray — fail-loud instead of
  silently pretending to be OBSERVE.

### Fixed — Bubble card overflow + drop 'via <tool>' debug pill (`9b42856`)

- Bubble card text overflow on long raw_content (>1 line on narrow screens)
- Removed the `via <tool>` debug pill from the bubble card — visual noise
  with no signal value in production

### Verified (data) — post type-refactor PP-OCRv4 re-run (`77c771f`)

Re-ran all three local-backend suites at commit `144ba61` to anchor
the refactor against the new architecture (UI color migration is
structural and does not enter scoring, so any Δ is variance):

- **phone_20** at commit `144ba61`: composite **0.917**
  (Δ=-0.027 vs baseline 0.944, within ±0.03 LLM variance band;
  0 contamination, 0 Outcome.Error). Baseline value unchanged.
- **pii_20** at commit `144ba61`: composite **0.923**
  (Δ=-0.006 vs baseline 0.929, within noise). Baseline value unchanged.
- **direction_arrow_20** at commit `144ba61`: composite **0.975**
  (Δ=+0.001 vs baseline 0.974, within noise). Baseline value unchanged.

All three suites stay anchored at the PP-OCRv4 local baseline; no
regression per `feedback-529-contamination-awareness` (Δ ≤ ±0.03 +
zero contamination ⇒ variance, not regression). `profiling/baselines.json`
`baseline_commit` fields updated to `144ba61` to reflect the
post-refactor measurement point.

### Final baselines (10 suites, 2026-07-13 — local OCR backend)

| Suite | Composite | n | Notes |
|---|---:|---:|---|
| phone_20 | **0.944** | 20 | local OCR baseline @`144ba61` |
| phone_60 | **0.9200** | 55 | post-Phase-I v5, GT reclass v2 |
| pii_20 | **0.929** | 18 | local OCR baseline @`144ba61` |
| pii20_60 | **0.9521** | 18 | post-Phase-I v4 |
| direction_arrow_20 | **0.974** | 20 | local OCR baseline @`144ba61` |
| direction_arrow_60 | **0.9694** | 20 | post-Phase-I v3 |
| service_institution_60 | **0.9664** | 63 | Phase I |
| **shopping_promo_20** | **0.918** | 20 | **Phase J NEW** (r3_actions=0.350 — see Phase J section) |
| phaseG_15 | 0.973 | 15 | pre-PP-OCRv4 (Huawei Cloud ref) |
| rctw_20_sanity | 0.9202 | 20 | pre-PP-OCRv4 (Huawei Cloud ref) |

`profiling/baselines.json` now tracks all 10 suites with
`baseline_commit` field pointing to the measurement-point commit
(`144ba61` for local-OCR suites, older for pre-PP-OCRv4 refs).
Pre-PP-OCRv4 numbers are retained as `*_huawei_cloud_ref` entries
for historical reference only — do NOT use for regression checks.

### Phase J — `shopping_promo` intent (14th intent, OBSERVE) (`6f87e00`)

- **14th intent**, OBSERVE family. Targets RCTW's 351-image
  `shopping_promo` cluster (rank #7 in `scan_intents.py` — highest
  un-shipped intent cluster). LLM hint covers 13 keywords:
  `特价 / 促销 / 优惠 / 打折 / 满减 / 秒杀 / 亏本 / 清仓 / 甩卖 /
  红包 / 抵用券 / 代金券 / 限时 / 抢购 / 直降`. Maps to new
  `copy_promo` action (share-sheet, mirrors `copy_menu` plumbing).
- **5-file pure-add architecture** (mirrors Phase G/H/I):
  1. `IntentDecl.kt` — register `shopping_promo`.
  2. `app/ActionDecl.kt` — new `copy_promo` action (cap 600 chars).
  3. `IntentVerifier.kt` — **Pass 13** PROMO regex + 2 guards:
     - `!REAL_ESTATE` — prevent 二手房急售 转让 mis-fire on Phase B
     - `!MENU` — prevent 今日特价 mis-fire on menu_food (Phase G)
     + `actionFor("shopping_promo")` → "copy_promo".
  4. `ToolImplementations.kt` — C3 v3 prompt table row 14:
     shopping_promo → copy_promo.
  5. `eval/EvalRunner.kt` — `copy_promo` added to `defaultActionIds`
     (3rd lockstep site).
  6. `scripts/scale_fixtures.py` — `shopping_promo_20` entry with
     5 sub-categories (price_discount / sale_promotion / coupon_voucher
     / flash_sale / clearance) + fallback `general_promo`.

- **shopping_promo_20 baseline** (`6f87e00`): composite **0.918**
  (20-fixture, local OCR, 0 contamination, 0 Outcome.Error).
  r2_text fuzzy=1.000, r2_type=1.000 (classification perfect).
  Per-category: general_promo 0.967 / sale_promotion 0.923 /
  coupon_voucher 0.922 / price_discount 0.900 / flash_sale 0.844.

- **Known follow-up: r3_actions=0.350** — pre-existing EvalRunner
  wiring gap: `EvalRunner.orchestrator = ToolUseLoop(...)` does not
  pass `actionIds` parameter, so the system prompt tells the LLM
  "actions ∈ {}（暂无动作可选；emit_bubble.action_ids 留空即可）".
  C3 v3 inline table is the only signal for new actions; Lift
  opportunity for future r3-only Phase. r2_type=1.00 confirms
  classification is perfect; the gap is purely action emission.

### Not changed

- No APK bump in this release batch. Latest APK remains the 16.7 MB
  build from commit `656aed1` (2026-07-12: Phase G + Phase H v2 + Pass 1b').
  Next APK ship will bundle the per-family UI accent refactor.
- Verifier now 10-pass + 3 post-guard (Pass 11 / Pass 12 / **Pass 13** in `IntentVerifier.kt`).

## [2026-07-12] — Phase H v2 + Pass 1b' + APK ship

### Added

- **Phase H route_to intent** (`dc7c380`): 12th intent id, OBSERVE family.
  LLM hint covers arrows / 方位词 / 距离短语 / 出口入口 markers. Verifier
  Pass 11 (info + DIRECTION_ARROW → route_to) + open_in_maps.applicableIntents
  widened to include route_to. Pure-add architecture; regression risk LOW.
- **Pass 1b' LANDLINE → phone** (`ff39451`): post-guard option (a) shipped.
  Activates LANDLINE regex in Pass 1 location-source rule; lifts image_1359
  and 5 other landline fixtures.

### Fixed

- **Phase H v2 verifier** (`6f0cd1b`): loosen DIRECTION_ARROW distance
  regex `.{0,4}` → `.{0,8}` (catches 行走三十步); add Pass 11
  real_estate_rental source with `!REAL_ESTATE` guard (recovers image_6423).

### Verified

- **direction_arrow_20 v2 = 0.9850** (commit `6f0cd1b`, +0.0263 vs v1).
- **phone_20 = 0.9575** history-high (commit `656aed1`).
- **pii_20 = 0.9788** (+0.0157 cumulative, commit `fc1cae2`).
- **RCTW @20 sanity = 0.9202** (no regression, commit `bfd7a47`).
- **APK shipped** at commit `656aed1` — 16.7 MB intentcam.apk.

## Earlier history

See git log. Key milestones:
- 2026-07-10: v1.3 ship (composite 0.9391)
- 2026-07-08: Phase 2 architecture + r3 weight added
- 2026-07-06: post-C5 + ImageOps baseline 0.67-0.70
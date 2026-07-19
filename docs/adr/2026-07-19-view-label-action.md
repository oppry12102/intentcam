# 2026-07-19 — view_label action: `label_markdown` contract + WebView-rendered label page

## Status
Accepted.  Ships in this working tree (app + shared + eval; see
CHANGELOG `[unreleased]` entry of the same date).

## Context
User-facing request: when the camera sees a label (商品标签/价签/吊牌/
合格证/快递面单/票据/铭牌), the app should recognize the FULL label
content, render it as a properly-sized styled page, and let the user
save / share that page as an image or as text.  Constraints:

- The existing action surface only hands off to external apps
  (`geo:`, `ACTION_DIAL`, share sheet) or Toasts — no in-app content
  page existed, no FileProvider / MediaStore / markdown infra at all.
- Compose BOM 2024.02.01 (ui 1.6.1) lacks
  `rememberGraphicsLayer` / `GraphicsLayer.toImageBitmap` (added in
  ui-1.7), so Composable→Bitmap capture has no supported API.
- Build environment has a dependency-download failure history (gradle
  wrapper, maven) — new third-party artifacts are a risk, and the APK
  has a size budget.

## Decision
1. **Contract**: new optional `emit_bubble.label_markdown` field — the
   LLM transcribes the full label as markdown (verbatim; `#` headings,
   `-` field lists, GFM tables).  `view_label` declares it as a
   requiredInput, so "proposed the chip but omitted the content" lands
   in the standard ghost-chip / missing-input path; `ActionRescue`
   adds the chip when the field exists unproposed.
2. **Render**: hand-rolled markdown-subset → HTML converter
   (`shared/LabelHtml.kt`, escape-first, no deps) + built-in CSS
   label-card template, displayed in a **WebView** inside a
   full-screen Compose overlay (`LabelPageScreen`).  Page width =
   card width (no viewport meta + `useWideViewPort=false`); height =
   measured content height, capped at 62% of the screen with internal
   scroll ("合适大小的页面").
3. **Image export**: re-render the same HTML into an **off-screen
   WebView**, `measure(width EXACT, height UNSPECIFIED)` → `layout` →
   `draw()` into a Bitmap (software layer), PNG to the FileProvider
   cache.  The saved image therefore covers the FULL content height,
   not just the scrolled viewport.
4. **Save/share plumbing**: MediaStore (API 29+) and
   app-external-dir + MediaScanner (API 26–28) — **no storage
   permission anywhere**; share via FileProvider + `ClipData` grant
   (微信 reads the grant from ClipData only).  Text payloads are the
   markdown source (`.md` / `text/plain`).
5. **Dispatch**: 5th `ActionOutcome` variant `ShowRenderedLabel`
   carrying a `RenderedLabel` *copy* (title + markdown + bubbleId), so
   the open page survives bubble-history eviction; parked on
   `UiState.renderedLabel`, overlaid from `AppRoot` above both camera
   and detail screens.

## Alternatives considered
- **A. Third-party markdown library (compose-markdown / Markwon /
  commonmark)** — native rendering for free.  **Rejected**: new maven
  artifacts in a build env with download-failure history; APK growth;
  and Compose-side bitmap capture still unsupported on ui-1.6.1.
- **B. Compose-native subset renderer** — best platform feel.
  **Rejected**: tables are painful, and image export would need
  off-screen composition hacks (no `toImageBitmap` on this BOM).
- **C. LLM outputs HTML directly** — skip the converter.
  **Rejected**: markdown doubles as the save/share-as-text payload and
  is safer to sanitize (escape-first converter ⇒ no markup injection);
  HTML output would need a sanitizer anyway.
- **D. `WRITE_EXTERNAL_STORAGE` (maxSdk 28) for gallery saves** —
  simpler code path below API 29.  **Rejected**: runtime-permission
  UX for a niche button; app-external-dir + MediaScanner achieves
  gallery visibility with zero permissions (same review-friendliness
  rationale as the rejected CALL_PHONE entry).

## Consequences
- Net win: full feature with zero new dependencies, zero new
  permissions, one FileProvider; the markdown contract is reusable for
  future rich-content actions (receipts, menus).
- Trade-offs: the markdown subset is deliberately small (no nested
  lists / images / fenced code blocks) — the prompt constrains the
  LLM to it; WebView rendering is a slight look-and-feel departure
  from native Compose; off-screen capture allocates a transient
  bitmap capped at 1440×6000 px.
- Eval: new `ground_truth_view_label.json` (30 scenes — RCTW carries
  real product-packaging fixtures); prompt change touches the shared
  `emit_bubble` description, so all suites re-baselined on the same
  run.  `build_action_suites.py` gained `--only ACTION` after a full
  rebuild was observed to clobber hand-curated suites.

## Migration
- Callers: none (additive contract field; old bubbles have
  `labelMarkdown = null` and behave unchanged).
- Eval mirrors updated in the same commit (`EvalRunner`, `ScorerV3`,
  `migrate_gt_v2_to_v3.py`) per
  [2026-07-18-eval-prod-parity.md](2026-07-18-eval-prod-parity.md).

## Related decisions
- [2026-07-18-eval-prod-parity.md](2026-07-18-eval-prod-parity.md)
- [2026-07-16-input-parsers-drift-risk.md](2026-07-16-input-parsers-drift-risk.md)
- [2026-07-14-v3-inversion.md](2026-07-14-v3-inversion.md)

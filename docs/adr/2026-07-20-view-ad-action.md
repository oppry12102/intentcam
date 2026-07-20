# 2026-07-20 — view_ad action: ad_markdown/ad_bbox contract + corrected ad-image page

## Status
Accepted.  Ships in this working tree (app + shared + eval; see
CHANGELOG `[unreleased]` entry of the same date).

## Context
Second content-page action after `view_label`: posted advertisements
(招生/促销/开业/社区告示/招商) deserve a richer treatment than a
text chip — the user asked for 图文复现: the ad's IMAGE region
cropped, perspective-corrected and enhanced, presented above its
transcription, shareable both as an image (corrected) and as a
composed page (image + text).  The companion chips for the ad's
phone/address/promo text stay on the bubble via the existing
`dial_number` / `open_in_maps` / `share` actions (prompt explicitly
non-exclusive).

## Decision
1. **Contract**: `emit_bubble.ad_markdown` (full transcription,
   required input + rescue cue) + `ad_bbox` (4-corner quad of the
   ad body — optional by design; the page falls back to the
   un-warped frame rather than gating the chip on framing quality).
2. **Correction/enhancement** (`AdImageCorrector`, app, zero new
   deps): `Matrix.setPolyToPoly` 4-point projective warp into a
   rectangle sized from the quad's average edges (cap 2200px);
   enhancement = low-contrast-only mean-preserving stretch
   (std < 35 → stretch toward 55, scale clamped [1.0, 2.0]) +
   saturation 1.12 + light 3×3 unsharp (edge 0.25).  First cut
   normalized mean→128 and turned white posters gray — the common
   case for ads; the stretch must never compress and never shift
   the mean.
3. **Page** (`AdPageScreen`): same WebView+LabelHtml pipeline as
   `view_label`, with the corrected JPEG embedded as a data-URI
   `<img>` above the transcription.  `RenderedAd` payload carries
   title + markdown + corrected JPEG **bytes** (copy) so the page
   and its shares never touch the evictable bubble.
4. **Shares**: 分享图片 = the corrected JPEG itself; 分享图文 =
   whole composed page via the verified visible-WebView capture.
5. **Eval**: `ad_markdown` mirrors `label_markdown` everywhere
   (EvalRunner / ScorerV3 / migrate script); new suite via
   `build_action_suites.py --only view_ad` with tight triggers
   (first pass with bare 社区/物业/宣传 pulled ~11/30 office-signage
   scenes — tightened to 社区宣/便民/招生/开业/招商 vocabulary).

## Alternatives considered
- **A. OpenCV for warp/enhance** — proper `getPerspectiveTransform`
  + filters.  **Rejected**: new native dependency (~10MB+ per ABI)
  for a 4-point warp `android.graphics.Matrix` already does.
- **B. Require `ad_bbox` as a required input** — guarantees the
  corrected image.  **Rejected**: framing is the model's weakest
  output; gating the chip on it would ghost the action exactly on
  hard scenes.  Transcription alone is already useful.
- **C. Crop from the 4096 full-res frame** — sharper crops.
  **Rejected**: the full-res frame is transient (not carried by
  `Bubble`); the 3200px thumbnail is always available and adequate
  for poster-sized regions.
- **D. Composite-image 分享图文 via off-screen renderer** —
  **Rejected**: reuses the verified visible-WebView capture path
  instead (off-screen WebViews shipped two blank-PNG iterations).

## Consequences
- Net win: full 图文复现 feature reusing the proven
  contract→page→capture shape; corrector is pure-Android.
- Trade-offs: data-URI embedding makes the HTML payload ~1MB (fine
  for loadDataWithBaseURL); enhancement is deliberately gentle —
  heavy restoration remains out of scope.
- The `view_ad` + `share` vocabularies overlap on promo posters by
  design (mixed scenes expect both chips); none-suite overlap is 0.

## Migration
Additive contract fields; old bubbles render unchanged.  Eval
mirrors updated in the same commit per
[2026-07-18-eval-prod-parity.md](2026-07-18-eval-prod-parity.md).

## Related decisions
- [2026-07-19-view-label-action.md](2026-07-19-view-label-action.md)
- [2026-07-18-eval-prod-parity.md](2026-07-18-eval-prod-parity.md)
- [2026-07-16-input-parsers-drift-risk.md](2026-07-16-input-parsers-drift-risk.md)

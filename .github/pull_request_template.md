<!--
Thanks for the PR!  Walk through this checklist — most items
auto-complete by typing `[x]`.  See CONTRIBUTING.md for details
on each section.
-->

## What does this PR do?

<!-- One-paragraph summary of the change.  Link to the relevant
ADR in docs/adr/ if this implements or supersedes one. -->

## ADR policy

<!-- Skip this section if your change has no architectural
decision (trivial bug fix, rename, refactor without new trade-offs). -->

- [ ] This PR introduces a new architectural decision →
      added a new ADR under `docs/adr/` + entry in
      `docs/adr/README.md` index
- [ ] This PR reverses / supersedes an existing decision →
      added a new ADR with `Status: Supersedes YYYY-MM-DD-<old>`
- [ ] This PR rejects an alternative the team might re-try →
      documented the rejection in the relevant existing/new ADR
- [ ] No architectural decision → skipped this section

## Build & test

- [ ] `:app:compileDebugKotlin :shared:compileKotlin` clean
- [ ] No new `[YYYY-MM-DD]` breadcrumbs in code comments (referenced the ADR instead)
- [ ] No scope creep — only touched the layers the change requires

## Eval

<!-- Fill out the suite(s) you ran.  Skip suites you didn't touch. -->

- [ ] Eval smoke (`./gradlew :shared:eval --args="--limit 5"`) passes
- [ ] `phone_20` composite_v2: `_____` (baseline: 0.9450) — within noise / improved / regressed
- [ ] `pii_20` composite_v2: `_____` (baseline: 0.9470) — within noise / improved / regressed
- [ ] `direction_arrow_20` composite_v2: `_____` (baseline: 0.9850) — within noise / improved / regressed
- [ ] `service_institution_60` composite_v2: `_____` (baseline: 0.9760) — within noise / improved / regressed
- [ ] `shopping_promo_20` composite_v2: `_____` (baseline: 0.9430) — within noise / improved / regressed
- [ ] Full regression net (`./scripts/run_regression.sh`) — exit code 0 / non-zero

## Baseline updates (if intentional shift)

<!-- Skip if composite unchanged. -->

- [ ] `profiling/baselines.json` updated with new composite_v2
- [ ] Δ rationale in commit body (e.g. "phone_20 Δ=+0.018 from Phase X")

## Manual on-device smoke (if MainActivity / CycleManager / FrameAnalyzer touched)

- [ ] Grant permission → tap shutter → bubble appears with chips
- [ ] Rapid 5-shot → all 5 cards visible, shutter counter ticks down/up correctly
- [ ] Cycle timeout (LLM hang) → ERRORED card flips to "识别超时, 请再拍一张"
- [ ] Cancel user-input dialog → BubbleCard clears, no stuck spinner

## Regression diagnosis (if composite dropped)

<!-- Per memory: investigate-before-revert — first check for API noise -->

- [ ] Checked `profiling/_*_<tag>.log` for `Outcome.Error / 529 / overload` markers
- [ ] Per-fixture signal inspected (which fixtures regressed, which lifted)
- [ ] Bug audit completed (no new logic bug identified)
- [ ] 1-2 single-var fixes attempted
- [ ] Decision: keep change (with retry) / single-var fix / revert

## Doc updates

- [ ] `CHANGELOG.md` `[unreleased]` section updated (Added / Changed / Fixed / Removed / Verified)
- [ ] `ARCHITECTURE.md` updated if the change touches architecture
- [ ] `CONFIG.md` updated if the change introduces new tunable constants

## Memory updates (if applicable)

<!-- Per `.claude/projects/.../memory/` — flag any new memories
that should be captured for future sessions. -->

- [ ] New memory file added (e.g. `feedback-architecture-review.md`)
- [ ] Existing memory file updated with new datapoint
- [ ] No memory update needed

---

**Checklist confidence**: ☐ high (every box checked) ☐ needs review

<!-- Reviewer: focus on the Decision section + Eval section.
If this PR introduces a new architectural decision, the ADR
must be merged alongside — not after. -->
# Contributing

Welcome!  This document explains how to land a change in
IntentCam and what the review process looks like.

## Architecture Decision Records (ADRs)

IntentCam uses **ADRs** to capture the *why* behind
architectural decisions.  They live in
[`docs/adr/`](docs/adr/README.md) (see the [README index](docs/adr/README.md))
and are immutable once shipped (corrections go in a `## Corrections`
section).

### When to write a new ADR

Add a new ADR to `docs/adr/` (and an entry in its README index)
if your change **any** of:

- Adds a new architectural component, data flow, or library
- Replaces or supersedes a previous decision
- Rejects a non-obvious alternative (so the team doesn't
  re-try it later)
- Explains a complex bug fix where "why" is non-obvious
- Locks in a magic number, timeout, or constant that took
  empirical tuning (e.g. `llmTimeoutMs = 90_000`)

Skip the ADR for trivial bug fixes, renames, and refactors
without new design trade-offs — those are commit-message
material.

### ADR template

```markdown
# YYYY-MM-DD — <decision title>

## Status
Accepted / Superseded / Rejected.  <commit hash(es)>.

## Context
<what problem / constraint drove the decision>

## Decision
<what we chose>

## Alternatives considered
- **A. <name>** — <description>. **Rejected**: <why>.

## Consequences
<net win + trade-offs>

## Migration
<how callers / configs / docs update, if any>

## Related decisions
<links to other ADRs in this folder>
```

### Referencing ADRs from code

When you implement an ADR'd decision, reference the ADR from
the relevant code with a one-line comment:

```kotlin
// See ADR docs/adr/2026-07-16-producer-consumer-pipeline.md
fun startCycle(frame: CapturedFrame): CycleJob? { ... }
```

This makes the ADR discoverable from the code itself and keeps
the *why* out of inline comments.

### Reversing an ADR

Don't edit a shipped ADR.  Ship a **new** ADR with
`Status: Supersedes YYYY-MM-DD-<old-slug>` and link to it
from the old one.

## Pull request checklist

When opening a PR, walk through this list:

### Decision

- [ ] Does this PR introduce a new architectural decision?
  → Add a new ADR (see above)
- [ ] Does this PR reverse or supersede an existing decision?
  → Add a new ADR that supersedes the old one
- [ ] Does this PR reject an alternative the team might re-try?
  → Document the rejection in the relevant ADR

### Code

- [ ] `:app:compileDebugKotlin :shared:compileKotlin` clean
- [ ] No new `[YYYY-MM-DD]` breadcrumbs in comments — reference
      the ADR instead
- [ ] No code-region or PII-scope creep (only touch the layers
      the change requires)

### Testing

- [ ] Eval smoke (`./gradlew :shared:eval --args="--limit 5"`)
      passes — at minimum, no fixture regresses below its
      baseline by > 0.05 absolute
- [ ] If the change is on a regression-tracked suite
      (`phone_20`, `pii_20`, `direction_arrow_20`,
      `service_institution_60`, `shopping_promo_20`),
      run that suite and verify composite within noise of
      `profiling/baselines.json`
- [ ] Manual on-device smoke if the change touches
      `MainActivity` / `CycleManager` / `FrameAnalyzer`

### Eval baseline updates

If your change **intentionally** shifts an eval score:

- [ ] Update `profiling/baselines.json` with the new
      `composite_v2` (and `composite_v3` if measured)
- [ ] Note the shift in the commit body and PR description
      with `Δ = X.XXX` and the rationale

If the shift is a **regression** (composite drops below
baseline):

- [ ] Per [memory: 529 contamination awareness] — first check
      `profiling/_*_<tag>.log` for `Outcome.Error / 529 / overload`
      markers.  B3 + Phase F both looked like real regressions
      but were API noise.  Keep change, retry calm.
- [ ] If the regression is real and you can't fix it in this
      PR, revert per [memory: investigate-before-revert] —
      debug per-fixture signal + bug audit + 1-2 single-var fixes

## Code conventions

- **Two-register lockstep for new intents** (per
  [`docs/adr/2026-07-10-intent-action-framework.md`](docs/adr/2026-07-10-intent-action-framework.md)):
  edits required in lockstep in both `IntentDecl` and
  `ActionDecl` registries.  Drift = silent chip miss.
- **StateFlow for derived UI state** — don't hand-manage
  fields in `UiState`.  If it's a derivation of cycle status
  (e.g. `busy`, `activeCycleCount`), derive it from
  `CycleManager`.  See
  [`docs/adr/2026-07-16-producer-consumer-pipeline.md`](docs/adr/2026-07-16-producer-consumer-pipeline.md).
- **Eval mirrors prod** — `EvalRunner.kt` calls the same
  `ToolUseLoop.runCycle` the Android app uses.  Don't fork
  the eval pipeline for a fix; fix prod and let the eval pick
  it up.

## Build & test

### Build

```bash
JAVA_HOME=/path/to/jdk17 /path/to/gradle clean :app:assembleDebug
```

The debug APK is copied to `./intentcam.apk` at the project
root for sideloading.

### Run eval smoke

```bash
JAVA_HOME=/path/to/jdk17 /path/to/gradle :shared:eval \
    --args="--limit 5 --gt profiling/ground_truth_phone_20.json"
```

First run takes 5-30 s for PP-OCRv4 to load weights; subsequent
calls reuse the cached engine.

### Run full regression net

```bash
./scripts/run_regression.sh          # all 9 suites, auto-compare
./scripts/run_regression.sh --no-build phone_20 pii_20   # subset
```

Exits non-zero if any suite drops ≥ 0.05 absolute from its
baseline in `profiling/baselines.json`.

## Where things live

| Concern | Location |
|---|---|
| **Architecture decisions** | [`docs/adr/`](docs/adr/README.md) |
| **Architecture deep-dive** | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| **Tunable constants** | [`CONFIG.md`](CONFIG.md) |
| **Change log** | [`CHANGELOG.md`](CHANGELOG.md) |
| **Eval JSON outputs** | `profiling/eval_*.json` |
| **Eval ground truth** | `profiling/ground_truth_*.json` |
| **Cycle / pipeline** | `app/src/main/java/com/example/intentcam/CycleManager.kt` |
| **Tool-use orchestrator** | `shared/src/main/kotlin/com/example/intentcam/ToolUseLoop.kt` |
| **LLM client** | `shared/src/main/kotlin/com/example/intentcam/LlmClient.kt` |
| **Eval pipeline** | `shared/src/main/kotlin/com/example/intentcam/eval/` |

## License

Public repository — no license file yet.  Until then, by
submitting a PR you agree to license your contribution under
the same terms.
# Regression Net

Auto-runs every intent-suite in `baselines.json` and alerts when any suite's
composite drops by **≥ 0.05** from its history-high baseline.

## Layout

| File | Purpose |
|---|---|
| `profiling/baselines.json` | Suite manifest: name / GT / limit / baseline composite / commit |
| `scripts/run_regression.sh` | Driver: builds once, runs each suite, writes summary JSON |
| `scripts/check_regression.py` | Comparator: latest summary → pass/fail per suite, exit 1 on alert |
| `profiling/regression/summary_<ts>.json` | Per-suite composite + Δ + gradle exit code |
| `profiling/regression/<suite>_<ts>.json` | Raw eval output for that run |

## Suites tracked

| Suite | Baseline | Last commit | Note |
|---|---:|---|---|
| `phone_20` | 0.9575 | `656aed1` | Pass 1b' + Phase H ship |
| `pii_20` | 0.9788 | `fc1cae2` | Phase H v2 cumulative |
| `direction_arrow_20` | 0.9850 | `6f0cd1b` | Phase H v2 |
| `phaseG_15` | 0.973 | `f7345dc` | Phase G mini-suite |
| `rctw_20_sanity` | 0.9202 | `bfd7a47` | RCTW all-info, regression sentinel |

## Running

```bash
export ANTHROPIC_AUTH_TOKEN=...   # required

# All suites (full run ≈ 30-40 min: 5 suites × 8-9 min each, parallel-safe
# if you background them, but default is sequential for log clarity)
./scripts/run_regression.sh

# Subset
./scripts/run_regression.sh phone_20 pii_20

# Skip gradle build (use latest evalJar)
./scripts/run_regression.sh --no-build phone_20

# Inspect latest summary
./scripts/check_regression.py

# Specific summary
./scripts/check_regression.py profiling/regression/summary_20260712_143000.json
```

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | All suites within threshold |
| 1 | One or more suites regressed by ≥ 0.05 |
| 2 | `ANTHROPIC_AUTH_TOKEN` missing — aborted before run |
| 3 | Gradle build failure |
| 4 | Suite selection matched nothing |

## Threshold rationale

0.05 absolute Δ on 20-fixture composite. The 0.03 band is the empirical
LLM-variance floor (cf `feedback-529-contamination-awareness`), so 0.05 is the
smallest threshold that doesn't false-positive on API noise while still
catching every observed true regression (every Phase A→H regression was ≥
0.05).

When the alert fires:
1. Check `profiling/regression/<suite>_<ts>.json` for `raw_content` containing
   `Outcome.Error` or `529` / `overload` strings — 529 contamination pattern.
2. Per-fixture signal: any fixture at 0.0 with `Outcome.Error` is API noise.
3. If clean, do a `bug audit` per
   `feedback-investigate-before-revert` (regex 紧度 / ordering / scope /
   symmetric) before reverting.
4. Net < -0.03 over ≥ 2 fixtures sharing a root cause → revert. Else keep
   the change and retry.

## CI / cron wiring (not yet active)

### Cron (manual nightly)

```cron
# Run nightly at 02:17 local (off the :00 mark) — adjust to your timezone.
17 2 * * *  cd /home/oppry/work/app3 && \
  ANTHROPIC_AUTH_TOKEN=*** ./scripts/run_regression.sh >/tmp/regression.log 2>&1 && \
  ./scripts/check_regression.py || echo "REGRESSION: $?" | mail -s "intentcam regression" you@example.com
```

### GitHub Actions (on push + nightly)

```yaml
name: regression
on:
  schedule: [{cron: '17 2 * * *'}]   # 02:17 UTC
  push: {branches: [main]}
jobs:
  eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: {distribution: 'temurin', java-version: '17'}
      - run: ./scripts/run_regression.sh
        env:
          ANTHROPIC_AUTH_TOKEN: ${{ secrets.ANTHROPIC_AUTH_TOKEN }}
      - uses: actions/upload-artifact@v4
        with:
          name: regression-summary
          path: profiling/regression/summary_*.json
      - run: ./scripts/check_regression.py
```

## Updating baselines

When a Phase ships and a new history-high composite is set, update
`profiling/baselines.json` in the same commit (or in the
`data(eval): <suite> ...` commit that documents the new high). Keep
`last_updated_commit` in sync so the regression net knows which tree the
baselines were measured against.

## Caveats

- **Sequential by default.** All suites run one-at-a-time to keep log output
  readable. To parallelize, background each `gradle :shared:eval` invocation
  in a separate script.
- **No mock / dry-run.** The eval always hits the real LLM API. There is no
  fake-data mode; the harness is meant to catch real regressions.
- **r3 weight changed 2026-07-12.** Baselines recorded before that date use
  the older 0.50*r1+0.50*r2 formula and may differ slightly. Keep separate
  baseline entries if you need historical comparability.
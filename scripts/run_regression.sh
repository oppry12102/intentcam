#!/usr/bin/env bash
# run_regression.sh — execute every suite in profiling/baselines.json,
# capture composite, write profiling/regression/summary_<ts>.json.
#
# Usage:
#   ./scripts/run_regression.sh                  # all suites
#   ./scripts/run_regression.sh phone_20 pii_20  # subset
#   ./scripts/run_regression.sh --no-build phone_20  # skip gradle build
#
# Env:
#   ANTHROPIC_AUTH_TOKEN   required (eval will crash otherwise)
#   ANTHROPIC_BASE_URL     optional, default https://api.minimaxi.com/anthropic
#   ANTHROPIC_MODEL        optional, default MiniMax-M3
#
# Exit code:
#   0  all suites ran; check summary for per-suite pass/fail
#   2  ANTHROPIC_AUTH_TOKEN missing — eval would crash; abort before running
#   3  gradle build failure
#   4  no suites matched
set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASELINES="$PROJECT_ROOT/profiling/baselines.json"
OUT_DIR="$PROJECT_ROOT/profiling/regression"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
SUMMARY="$OUT_DIR/summary_${TIMESTAMP}.json"

mkdir -p "$OUT_DIR"

# ── Arg parsing ────────────────────────────────────────────────────
NO_BUILD=0
SELECTED=()
for arg in "$@"; do
    case "$arg" in
        --no-build) NO_BUILD=1 ;;
        --help|-h)
            sed -n '2,20p' "$0"; exit 0 ;;
        -*)
            echo "Unknown flag: $arg" >&2; exit 1 ;;
        *)
            SELECTED+=("$arg") ;;
    esac
done

# ── API key check ──────────────────────────────────────────────────
if [[ -z "${ANTHROPIC_AUTH_TOKEN:-}" ]]; then
    echo "ERROR: ANTHROPIC_AUTH_TOKEN not set — eval would crash." >&2
    echo "  export ANTHROPIC_AUTH_TOKEN=... then retry." >&2
    exit 2
fi

# ── Gradle build (once, unless --no-build) ─────────────────────────
if [[ "$NO_BUILD" -eq 0 ]]; then
    echo ">> gradle :shared:evalJar (one-time build)"
    if ! (cd "$PROJECT_ROOT" && gradle :shared:evalJar --console=plain --no-daemon >"$OUT_DIR/build_${TIMESTAMP}.log" 2>&1); then
        echo "ERROR: gradle build failed; see $OUT_DIR/build_${TIMESTAMP}.log" >&2
        exit 3
    fi
fi

# ── Drive each suite ───────────────────────────────────────────────
echo ">> Running regression suites from $BASELINES"
python3 - "$BASELINES" "$OUT_DIR" "$TIMESTAMP" "${SELECTED[@]+"${SELECTED[@]}"}" <<'PY'
import json, os, re, subprocess, sys, time

baselines_path, out_dir, ts = sys.argv[1], sys.argv[2], sys.argv[3]
selected = set(sys.argv[4:]) if len(sys.argv) > 4 else set()

data = json.load(open(baselines_path))
threshold = data["_meta"]["threshold"]
suites = data["suites"]

# [2026-07-14 C-cleanup] Reference-only suites (suffixed `_ref` —
# historical Huawei Cloud numbers) and `*_server_cpu` (slow CPU-mode
# PP-OCRv4 reference) are excluded by default; the baselines.json
# `note` field on each says "reference only — do NOT use for
# regression checks".  They still appear in `baselines.json` so the
# reference data is recorded, but the regression script now honors
# that note.  Pass the suite name explicitly on the CLI to run one.
def is_reference(s):
    name = s["name"]
    note = (s.get("note") or "").lower()
    if name.endswith("_ref"):
        return True
    if name.endswith("_server_cpu"):
        return True
    if "reference only" in note and "do not" in note:
        return True
    return False

before_filter = len(suites)
if not selected:
    ref_skipped = [s["name"] for s in suites if is_reference(s)]
    suites = [s for s in suites if not is_reference(s)]
    if ref_skipped:
        print(f">> Skipping {len(ref_skipped)} reference suite(s) by default: {ref_skipped}")
        print("   (pass the suite name explicitly to run one)")

if selected:
    suites = [s for s in suites if s["name"] in selected]
if not suites:
    print(f"ERROR: no suites matched selection {selected}", file=sys.stderr)
    sys.exit(4)

print(f">> {len(suites)} suite(s) selected; threshold |Δ| ≥ {threshold}")

# We invoke gradle from project root so :shared:eval resolves.
# Gradle picks up the latest evalJar already built.
#
# [2026-07-13] fix project_root calc: when this Python runs via
#  heredoc (`python3 - <<'PY' ... PY`), __file__ is "<stdin>" and
#  os.path.abspath(__file__) returns the CWD, so the old
#  `os.path.dirname(os.path.dirname(...))` calc walked up TWO
#  levels from the project root and gradle ran in the wrong
#  directory (e.g. /home/oppry or /). Symptom: every suite
#  failed in 2.9s with "Run gradle init to create a new
#  Gradle build in this directory." Use os.getcwd() directly —
#  the parent bash script's CWD is the project root.
project_root = os.getcwd()

results = []
for cfg in suites:
    name = cfg["name"]
    json_out = f"{out_dir}/{name}_{ts}.json"
    args = (
        f'--ground-truth {cfg["gt"]} '
        f'--img-dir {data["_meta"]["image_dir"]} '
        f'--limit {cfg["limit"]} '
        f'--resize {cfg["resize"]} '
        f'--quality {cfg["quality"]} '
        f'--json-out {json_out}'
    )
    print(f"\n>> [{name}] starting (limit={cfg['limit']})")
    t0 = time.time()
    # [2026-07-13] fix `--args` quoting: gradle's `--args` flag
    #  requires the `=` form (--args=VALUE) — the space form
    #  (--args VALUE) is silently treated as a bare flag with no
    #  value, causing "No argument was provided for command-line
    #  option '--args'" exit-1.  The previous shell-invocation
    #  worked because the shell collapses `--args="..."` into one
    #  argv slot; subprocess.run does not.
    proc = subprocess.run(
        ["gradle", ":shared:eval", f"--args={args}", "--console=plain", "--no-daemon"],
        cwd=project_root,
        capture_output=True, text=True,
    )
    elapsed = time.time() - t0
    print(f"   gradle exit={proc.returncode}, elapsed={elapsed:.1f}s")

    # Parse composite from JSON output (preferred) or stdout fallback.
    composite = None
    err_count = 0
    composite_v2 = None
    composite_legacy = None
    if os.path.exists(json_out):
        try:
            j = json.load(open(json_out))
            composite_v2 = j.get("overall_composite_v2")
            composite_legacy = j.get("overall_composite")
            err_count = sum(1 for f in j.get("fixtures", []) if "Error" in str(f.get("raw_content", "")))
        except Exception as e:
            print(f"   WARN: could not parse {json_out}: {e}", file=sys.stderr)
    if composite_v2 is None:
        # Fall back to stdout parse (legacy path — for old builds
        # that haven't been rebuilt to write overall_composite_v2).
        m = re.search(r"average composite_v2:\s*([0-9.]+)", proc.stdout)
        if m:
            composite_v2 = float(m.group(1))
    if composite_v2 is None:
        m = re.search(r"average composite:\s*([0-9.]+)", proc.stdout)
        if m:
            composite_legacy = float(m.group(1))
            # Pre-v3.0 build — use legacy as the canonical.
            composite_v2 = composite_legacy

    if composite_v2 is None:
        print(f"   WARN: no composite found — gradle stdout tail:")
        print(proc.stdout[-800:])
        composite_v2 = float("nan")

    # [2026-07-14 v3.0] Threshold check uses composite_v2 against the
    #  new canonical baseline (= composite_v2 number). Legacy composite
    #  is reported for historical comparison.
    composite = composite_v2
    delta = composite - cfg["baseline"]
    flagged = abs(delta) >= threshold
    status = "FAIL" if flagged else "PASS"
    legacy_str = f"  legacy={composite_legacy:.3f}" if composite_legacy is not None else ""
    print(f"   composite_v2={composite:.3f}  baseline={cfg['baseline']:.3f}  Δ={delta:+.3f}{legacy_str}  → {status}")
    if err_count:
        print(f"   ({err_count} Outcome.Error in JSON — possible 529 contamination)")

    results.append({
        "name": name,
        "baseline": cfg["baseline"],
        "baseline_legacy": cfg.get("baseline_legacy"),
        "composite": composite,
        "composite_legacy": composite_legacy,
        "delta": round(delta, 4),
        "status": status,
        "elapsed_sec": round(elapsed, 1),
        "errors": err_count,
        "json_out": os.path.relpath(json_out, project_root),
        "gradle_exit": proc.returncode,
    })

summary = {
    "timestamp": ts,
    "threshold": threshold,
    "baseline_file": os.path.relpath(baselines_path, project_root),
    "suites": results,
}
with open(f"{out_dir}/summary_{ts}.json", "w") as f:
    json.dump(summary, f, indent=2)
print(f"\n>> Summary written to {out_dir}/summary_{ts}.json")
print(f">> Run scripts/check_regression.py to print pass/fail summary.")
PY
#!/usr/bin/env bash
# diagnose_pii_ocr.sh — distinguish OCR-bottleneck vs LLM-bottleneck
# fixtures in pii20_60 by running the suite under both PP-OCRv4
# backends and diffing per-fixture scores.
#
# Usage:
#   ./scripts/diagnose_pii_ocr.sh                # default suite (pii20_60)
#   ./scripts/diagnose_pii_ocr.sh <suite_name>   # custom
#
# Why: image_1391 (湘华名邸 promo) is OCR-bound — mobile drops
# a PII-token, server-CPU finds it, composite 0.89→1.00.
# image_2494 is LLM-bound — server doesn't help.
#
# The script writes a side-by-side comparison
# (./profiling/regression/pii_ocr_diff_<ts>.json) so a future cohort
# analysis / threshold tuning can pick which fixtures belong on a
# "needs server-CPU escalation" allowlist.
#
# Wall-time: ~6 min mobile + ~6 min server = 12 min serial.

set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUITE_NAME="${1:-pii20_60}"
TS="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="$PROJECT_ROOT/profiling/regression"
DIFF_OUT="$OUT_DIR/pii_ocr_diff_${TS}.json"
RUN_MOBILE="$OUT_DIR/.ocr_diff_mobile.log"
RUN_SERVER="$OUT_DIR/.ocr_diff_server.log"

export PATH="/home/oppry/toolchain/gradle-8.5/bin:$PATH"
export ANTHROPIC_AUTH_TOKEN="${ANTHROPIC_AUTH_TOKEN:-}"

if [[ -z "$ANTHROPIC_AUTH_TOKEN" ]]; then
    echo "ERROR: ANTHROPIC_AUTH_TOKEN not set — eval would crash." >&2
    exit 2
fi

if [[ ! -t 1 ]]; then
    echo "WARN: stdout isn't a tty; live progress will be in $RUN_MOBILE / $RUN_SERVER"
fi

echo "=== Stage 1/2: mobile PP-OCRv4 (default) for $SUITE_NAME ==="
"$PROJECT_ROOT/scripts/run_regression.sh" --no-build "$SUITE_NAME" \
    > "$RUN_MOBILE" 2>&1
MOBILE_EXIT=$?
echo "  mobile exit=$MOBILE_EXIT (log: $RUN_MOBILE)"
[[ $MOBILE_EXIT -ne 0 ]] && { echo "ERROR: mobile run failed" >&2; exit 3; }

echo "=== Stage 2/2: server-CPU PP-OCRv4 for $SUITE_NAME ==="
LOCAL_OCR_KIND=server "$PROJECT_ROOT/scripts/run_regression.sh" --no-build "$SUITE_NAME" \
    > "$RUN_SERVER" 2>&1
SERVER_EXIT=$?
echo "  server exit=$SERVER_EXIT (log: $RUN_SERVER)"
[[ $SERVER_EXIT -ne 0 ]] && { echo "ERROR: server run failed" >&2; exit 4; }

echo "=== Diff ==="
python3 - "$OUT_DIR" "$TS" "$DIFF_OUT" "$SUITE_NAME" <<'PY'
import json, sys, os
out_dir, ts, diff_out, suite = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

# Find the 2 most-recent per-suite JSON files
candidates = sorted(
    [f for f in os.listdir(out_dir)
     if f.startswith(f"{suite}_202") and f.endswith(".json")],
    key=lambda f: os.path.getmtime(os.path.join(out_dir, f)),
)
if len(candidates) < 2:
    print(f"ERROR: not enough {suite} JSONs in {out_dir}")
    sys.exit(5)

mobile_path = os.path.join(out_dir, candidates[-2])
server_path = os.path.join(out_dir, candidates[-1])
print(f"  mobile: {mobile_path}")
print(f"  server: {server_path}")

mobile = json.load(open(mobile_path))
server = json.load(open(server_path))

m = {f['id']: f for f in mobile.get('fixtures', [])}
s = {f['id']: f for f in server.get('fixtures', [])}

rows = []
for fid in sorted(set(m) | set(s)):
    mf, sf = m.get(fid, {}), s.get(fid, {})
    # [2026-07-15 redesign] composite_v2 is the canonical score;
    #  r2_text → v2_text, r2_type → v2_type.
    m_cmp = mf.get('composite_v2', 0.0)
    s_cmp = sf.get('composite_v2', 0.0)
    delta = s_cmp - m_cmp
    if delta > 0.05 and m_cmp > 0:
        bottleneck = 'OCR-bound (server recovers)'
    elif m_cmp > 0.5 and delta <= 0.05:
        bottleneck = 'LLM-bound (server no help)'
    else:
        bottleneck = 'mixed'
    rows.append({
        'id': fid,
        'mobile_composite_v2': round(m_cmp, 4),
        'server_composite_v2': round(s_cmp, 4),
        'delta': round(delta, 4),
        'mobile_v2_text': mf.get('v2_text'),
        'server_v2_text': sf.get('v2_text'),
        'mobile_v2_type': mf.get('v2_type'),
        'server_v2_type': sf.get('v2_type'),
        'bottleneck': bottleneck,
    })

print()
print(f"{'id':12s} {'mobile':>8s} {'server':>8s} {'Δ':>7s}  bottleneck")
for r in rows:
    print(f"{r['id']:12s} {r['mobile_composite_v2']:>8.3f} {r['server_composite_v2']:>8.3f} "
          f"{r['delta']:>+7.3f}  {r['bottleneck']}")

print()
ocr_lifts = [r for r in rows if 'OCR' in r['bottleneck']]
llm_only = [r for r in rows if 'LLM' in r['bottleneck']]
print(f"OCR-bound (server lifts >0.05): {len(ocr_lifts)}")
print(f"LLM-bound (server no help):     {len(llm_only)}")

with open(diff_out, 'w') as f:
    json.dump({
        'suite': suite,
        'mobile_composite_v2': mobile.get('overall_composite_v2'),
        'server_composite_v2': server.get('overall_composite_v2'),
        'mobile_json': mobile_path,
        'server_json': server_path,
        'ocr_bound': [r['id'] for r in ocr_lifts],
        'llm_bound': [r['id'] for r in llm_only],
        'fixture_diff': rows,
    }, f, indent=2)
print(f"\nDiff saved to {diff_out}")
PY

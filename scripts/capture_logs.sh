#!/usr/bin/env bash
# capture_logs.sh — wipe logcat, install fresh APK, capture filtered logs while
# the user exercises the app.  Output: stdout + ./intentcam.log.
#
# Usage:
#   ./scripts/capture_logs.sh           # capture one run
#   ./scripts/capture_logs.sh --no-install   # don't reinstall, just capture
#
# Requires: adb on PATH, phone connected with USB debugging on.
set -euo pipefail

APK="$(dirname "$0")/../intentcam.apk"
LOGFILE="$(dirname "$0")/../intentcam.log"

if [[ "${1:-}" != "--no-install" ]]; then
    if [[ ! -f "$APK" ]]; then
        echo "APK not found at $APK — build first" >&2
        exit 1
    fi
    echo ">> Installing $APK"
    adb install -r "$APK"
fi

echo ">> Clearing logcat"
adb logcat -c

echo ">> Capturing IntentCam + analyzer/recognition tags to $LOGFILE"
echo "   (Ctrl-C to stop)"

# *:S silences everything by default; then we re-enable the tags we care about.
# -v threadtime gives pid/tid + ms-precision timestamp per line.
adb logcat -v threadtime \
    *:S \
    IntentCam:V \
    AndroidRuntime:E \
    System.err:W \
    DEBUG:V \
    | tee "$LOGFILE"
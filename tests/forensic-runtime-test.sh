#!/usr/bin/env bash
# ============================================================================
# tests/forensic-runtime-test.sh -- host test for the forensic log writer
# ----------------------------------------------------------------------------
# Compiles the guard against the official android.jar plus two tiny stubs
# (android.os.Process, android.system.Os) and runs FpForensicsRuntimeTest on
# the local JVM.  It checks the real captor: manifest diff, report sections,
# uid/caller extraction and the file written to the forensic directory.
#
# Usage:
#   tests/forensic-runtime-test.sh
#
# Environment:
#   ANDROID_SDK_ROOT / ANDROID_HOME   SDK with platforms/android-36
# ============================================================================
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$PROJECT/.build-tests/forensic-runtime"
SDK_API="${SDK_API:-36}"

SDK=""
for cand in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
            "$HOME/src/android-sdk-mini" "/usr/local/lib/android/sdk" \
            "/tmp/opencode/android-sdk-mini/build/stage/sdk" \
            "/tmp/opencode/android-sdk-mini/sdk"; do
  [ -n "$cand" ] || continue
  for root in "$cand" "$cand/sdk" "$cand/build/stage/sdk"; do
    [ -f "$root/platforms/android-$SDK_API/android.jar" ] || continue
    SDK="$root"; break 2
  done
done
if [ -z "$SDK" ]; then
  for cand in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
              "$HOME/src/android-sdk-mini" "/usr/local/lib/android/sdk"; do
    [ -n "$cand" ] || continue
    for root in "$cand" "$cand/sdk" "$cand/build/stage/sdk"; do
      [ -d "$root/platforms" ] || continue
      API="$(ls "$root/platforms" 2>/dev/null | sed -n 's/^android-\([0-9][0-9]*\)$/\1/p' | sort -n | tail -1)"
      [ -n "$API" ] && [ -f "$root/platforms/android-$API/android.jar" ] || continue
      SDK="$root"; SDK_API="$API"; break 2
    done
  done
fi
[ -n "$SDK" ] || { echo "error: no Android SDK with android.jar found" >&2; exit 1; }
ANDROID_JAR="$SDK/platforms/android-$SDK_API/android.jar"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/fp-forensic-runtime.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

export FACTORYPOP_PKG="jp.konami.pesam"
export FACTORYPOP_STATE="$TMP/state"
export FACTORYPOP_FORENSIC_DIR="$TMP/forensic"
export FACTORYPOP_DATA_DIR="$TMP/data"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "==> javac (guard + host stubs)"
mapfile -t SOURCES < <(find "$PROJECT/src/java" -name '*.java' -type f | sort)
javac -source 8 -target 8 -Xlint:-options \
  -cp "$ANDROID_JAR" -d "$OUT" \
  "$PROJECT/tests/stubs/android/os/Process.java" \
  "$PROJECT/tests/stubs/android/system/Os.java" \
  "${SOURCES[@]}" \
  "$PROJECT/tests/FpForensicsRuntimeTest.java"

echo "==> run FpForensicsRuntimeTest"
java -cp "$OUT:$ANDROID_JAR" FpForensicsRuntimeTest

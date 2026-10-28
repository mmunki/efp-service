#!/usr/bin/env bash
# ============================================================================
# build.sh -- compile the FactoryPop protection service and package it as a
#            services.jar (dist/services.jar)
#
# The fp-service logic now lives in real Android sources instead of the shell
# script only: FpUninstallGuard + FpUninstallGuardService are packaged into
# dist/services.jar, which is what gets merged into the system as the services
# jar.  The protection then runs without any command in /system/bin.
#
# Usage:
#   ./build.sh
#
# Environment:
#   ANDROID_SDK_ROOT   checkout of mmunki/android-sdk-mini (default
#                      $HOME/src/android-sdk-mini), or an assembled toolkit
#   SDK_API            android API level (default 36)
#   SDK_BUILD_TOOLS    build-tools version (default 36.0.0)
# ============================================================================
set -euo pipefail

SDK_API="${SDK_API:-36}"
SDK_BUILD_TOOLS="${SDK_BUILD_TOOLS:-36.0.0}"

# Locate a usable SDK root.  Try the explicit env var first, then a few common
# locations so the build works both in the developer container and in CI.
SDK=""
for cand in "${ANDROID_SDK_ROOT:-}" "$HOME/src/android-sdk-mini" "/tmp/opencode/android-sdk-mini/build/stage/sdk" "/tmp/opencode/android-sdk-mini/sdk" "/usr/local/lib/android/sdk"; do
  [ -n "$cand" ] || continue
  for root in "$cand" "$cand/sdk" "$cand/build/stage/sdk"; do
    [ -d "$root" ] || continue
    if [ -f "$root/platforms/android-$SDK_API/android.jar" ] && [ -d "$root/build-tools/$SDK_BUILD_TOOLS" ]; then
      SDK="$root"
      break 2
    fi
  done
done
[ -n "$SDK" ] || { echo "error: no Android SDK found for API $SDK_API" >&2; exit 1; }

SDK_PLATFORM_DIR="$SDK/platforms/android-$SDK_API"
SDK_ANDROID_JAR="$SDK_PLATFORM_DIR/android.jar"
SDK_BUILD_TOOLS_DIR="$SDK/build-tools/$SDK_BUILD_TOOLS"
SDK_JAR_DIR="$SDK_BUILD_TOOLS_DIR/lib"
if [ -x "$SDK/bin/aapt2" ]; then
  AAPT2="$SDK/bin/aapt2"
elif [ -x "$SDK_BUILD_TOOLS_DIR/aapt2" ]; then
  AAPT2="$SDK_BUILD_TOOLS_DIR/aapt2"
else
  AAPT2="$(command -v aapt2)"
fi
if [ -x "$SDK/bin/d8" ]; then
  D8="$SDK/bin/d8"
elif [ -x "$SDK_BUILD_TOOLS_DIR/d8" ]; then
  D8="$SDK_BUILD_TOOLS_DIR/d8"
else
  D8="$(command -v d8)"
fi
JAVA="${JAVA:-java}"

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$PROJECT/.build-services"
OUT="${1:-dist/services.jar}"
rm -rf "$WORK"
mkdir -p "$WORK/compiled" "$WORK/gen" "$WORK/classes" "$WORK/dex"

echo "==> aapt2 compile resources"
( cd "$WORK/compiled" && zip -q resources.zip -n . 2>/dev/null || true )
RES=""
[ -f "$WORK/compiled/resources.zip" ] && RES="$WORK/compiled/resources.zip"

echo "==> aapt2 link"
"$AAPT2" link \
  -o "$WORK/base.apk" \
  -I "$SDK_ANDROID_JAR" \
  --manifest "$PROJECT/src/AndroidManifest.xml" \
  --java "$WORK/gen" \
  --min-sdk-version 24 \
  --target-sdk-version "$SDK_API" \
  $RES

echo "==> javac (boot classpath is the official android.jar)"
mapfile -t JAVA_SOURCES < <(find "$WORK/gen" "$PROJECT/src" -name '*.java' -type f | sort)
if [ ${#JAVA_SOURCES[@]} -eq 0 ]; then
  echo "error: no Java sources found" >&2; exit 1
fi
javac -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$SDK_ANDROID_JAR" -d "$WORK/classes" "${JAVA_SOURCES[@]}"

echo "==> d8 (dex)"
mapfile -t CLASS_FILES < <(find "$WORK/classes" -name '*.class' -type f | sort)
if [ ${#CLASS_FILES[@]} -eq 0 ]; then
  echo "error: javac produced no class files" >&2; exit 1
fi
"$D8" --min-api 24 --lib "$SDK_ANDROID_JAR" --output "$WORK/dex" "${CLASS_FILES[@]}"

mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
rm -f "$OUT"
( cd "$WORK/dex" && jar cf "$OUT" classes.dex )
chmod 644 "$OUT"

echo "==> built $OUT"
echo "bytes: $(wc -c <"$OUT")"
echo "sha256: $(sha256sum "$OUT" | cut -d' ' -f1)"

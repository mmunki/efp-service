#!/usr/bin/env bash
# ============================================================================
# build.sh -- compile the FactoryPop protection service and package it
# ----------------------------------------------------------------------------
# The fp-test-guard.sh daemon now lives in real Android sources:
# FpUninstallGuard + FpGuardLoop + FpUninstallGuardService +
# com.factorypop.efp.tool.{BlockUninstall,GuardDaemon}.
#
# Two outputs are possible:
#
#   * standalone guard jar (default without --base):
#       dist/services.jar  -- a small jar with only the guard dex
#
#   * merged services.jar (default when a base jar is given):
#       the given framework services.jar plus the guard dex appended as the
#       next free classesN.dex, every original entry untouched.  That is the
#       file to install as /system/framework/services.jar.
#
# Usage:
#   ./build.sh                                  # standalone guard jar
#   ./build.sh --base vendor/services.jar       # merged services.jar
#   ./build.sh --base services.jar --out dist/services.jar
#
# Environment:
#   ANDROID_SDK_ROOT      Android SDK (default: common locations)
#   SDK_API               android API level (default 36)
#   SDK_BUILD_TOOLS       build-tools version (default 36.0.0)
#   FACTORYPOP_BASE_JAR   same as --base
# ============================================================================
set -euo pipefail

SDK_API="${SDK_API:-36}"
SDK_BUILD_TOOLS="${SDK_BUILD_TOOLS:-36.0.0}"

OUT=""
BASE="${FACTORYPOP_BASE_JAR:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --base)   BASE="${2:?--base needs a value}"; shift 2 ;;
    --out)    OUT="${2:?--out needs a value}"; shift 2 ;;
    --standalone) BASE=""; shift ;;
    -h|--help) sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)       echo "error: unknown option: $1" >&2; exit 1 ;;
    *)        OUT="$1"; shift ;;
  esac
done
[ -n "$OUT" ] || OUT="dist/services.jar"

# Locate a usable SDK root.  Try the explicit env var first, then a few common
# locations so the build works both in the developer container and in CI.
SDK=""
for cand in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/src/android-sdk-mini" "/tmp/opencode/android-sdk-mini/build/stage/sdk" "/tmp/opencode/android-sdk-mini/sdk" "/usr/local/lib/android/sdk"; do
  [ -n "$cand" ] || continue
  for root in "$cand" "$cand/sdk" "$cand/build/stage/sdk"; do
    [ -d "$root" ] || continue
    if [ -f "$root/platforms/android-$SDK_API/android.jar" ] && [ -d "$root/build-tools/$SDK_BUILD_TOOLS" ]; then
      SDK="$root"
      break 2
    fi
  done
done
if [ -z "$SDK" ]; then
  # the requested versions are not installed, take a root that has both trees
  for cand in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/src/android-sdk-mini" "/tmp/opencode/android-sdk-mini/build/stage/sdk" "/tmp/opencode/android-sdk-mini/sdk" "/usr/local/lib/android/sdk"; do
    [ -n "$cand" ] || continue
    for root in "$cand" "$cand/sdk" "$cand/build/stage/sdk"; do
      if [ -d "$root/platforms" ] && [ -d "$root/build-tools" ]; then
        SDK="$root"
        break 2
      fi
    done
  done
fi
[ -n "$SDK" ] || { echo "error: no Android SDK found for API $SDK_API" >&2; exit 1; }

# fall back to the newest installed platform / build-tools
if [ ! -f "$SDK/platforms/android-$SDK_API/android.jar" ]; then
  SDK_API="$(ls "$SDK/platforms" 2>/dev/null | sed -n 's/^android-\([0-9][0-9]*\)$/\1/p' | sort -n | tail -1)"
  [ -n "$SDK_API" ] || { echo "error: no platform android.jar in $SDK/platforms" >&2; exit 1; }
fi
if [ ! -d "$SDK/build-tools/$SDK_BUILD_TOOLS" ]; then
  SDK_BUILD_TOOLS="$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)"
  [ -n "$SDK_BUILD_TOOLS" ] || { echo "error: no build-tools in $SDK/build-tools" >&2; exit 1; }
fi
echo "==> SDK $SDK (API $SDK_API, build-tools $SDK_BUILD_TOOLS)"

SDK_PLATFORM_DIR="$SDK/platforms/android-$SDK_API"
SDK_ANDROID_JAR="$SDK_PLATFORM_DIR/android.jar"
SDK_BUILD_TOOLS_DIR="$SDK/build-tools/$SDK_BUILD_TOOLS"
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
rm -rf "$WORK"
mkdir -p "$WORK/compiled" "$WORK/gen" "$WORK/classes" "$WORK/dex"

if [ -n "$BASE" ]; then
  # the final artefact is the merged framework jar
  DEX_JAR="$WORK/services-guard.jar"
else
  DEX_JAR="$OUT"
fi
OUT_DIR="$(dirname "$DEX_JAR")"
mkdir -p "$OUT_DIR"
DEX_JAR="$(cd "$OUT_DIR" && pwd)/$(basename "$DEX_JAR")"

RES=""
if [ -d "$PROJECT/src/res" ] && [ -n "$(find "$PROJECT/src/res" -type f -print -quit)" ]; then
  echo "==> aapt2 compile resources"
  "$AAPT2" compile --dir "$PROJECT/src/res" -o "$WORK/compiled/resources.zip"
  RES="$WORK/compiled/resources.zip"
fi

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

rm -f "$DEX_JAR"
( cd "$WORK/dex" && jar cf "$DEX_JAR" classes.dex )
chmod 644 "$DEX_JAR"

if [ -n "$BASE" ]; then
  echo "==> merging the guard into the given services.jar"
  bash "$PROJECT/merge-services.sh" --base "$BASE" --guard "$DEX_JAR" --out "$OUT"
  exit $?
fi

echo "==> built $DEX_JAR"
echo "bytes: $(wc -c <"$DEX_JAR")"
echo "sha256: $(sha256sum "$DEX_JAR" | cut -d' ' -f1)"
echo
echo "This is the boot-safe guard jar.  Package it with:"
echo "  ./make-module.sh dist/services-guard.jar <version>"
echo "Do not mount it over /system/framework/services.jar."

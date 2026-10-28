#!/usr/bin/env bash
# ============================================================================
# make-module.sh -- package the standalone guard jar as a Magisk module
# ----------------------------------------------------------------------------
# The module ships the guard as an ordinary jar in its own directory:
#
#   services.jar   (loaded by service.sh with app_process)
#
# It deliberately does NOT contain system/framework/services.jar.  Mounting a
# modified framework jar over the stock one leaves the device stuck on the boot
# animation, because services.jar is a boot class path file of the ROM.  This
# module also refuses a merged framework jar so that mistake cannot be shipped
# again.
#
# Usage:
#   ./make-module.sh [dist/services-guard.jar] [version]
#
# Build the guard first:
#   ./build.sh --standalone --out dist/services-guard.jar
#
# Output: dist/FactoryPop-<version>.zip
# ============================================================================
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${1:-$PROJECT/dist/services-guard.jar}"
VERSION="${2:-$(sed -n 's/^version=//p' "$PROJECT/module/module.prop")}"
[ -n "$VERSION" ] || VERSION="0.0.0"
# a PR ref (2/merge) or a branch name (test) is not a version: keep only safe
# characters so the sed and the zip name always work
VERSION="$(printf '%s' "$VERSION" | tr -c 'A-Za-z0-9._-' '-')"
VERSION="${VERSION#-}"; VERSION="${VERSION%-}"
[ -n "$VERSION" ] || VERSION="0.0.0"
case "$VERSION" in
  v*) ;;
  *) VERSION="v$VERSION" ;;
esac
VERSION_CODE="$(printf '%s' "$VERSION" | tr -cd '0-9' | cut -c1-8)"
[ -n "$VERSION_CODE" ] || VERSION_CODE=1

[ -s "$JAR" ] || {
  echo "error: guard jar not found: $JAR" >&2
  echo "       build it with: ./build.sh --standalone --out dist/services-guard.jar" >&2
  exit 1
}

# The standalone guard jar has exactly one dex.  A framework services.jar has
# several (the given one has classes.dex .. classes4.dex) and must never be
# mounted, so reject it here instead of creating a bootloop module.
DEX_COUNT="$(unzip -Z1 "$JAR" 2>/dev/null | grep -cE '^classes[0-9]*\.dex$' || true)"
if [ "${DEX_COUNT:-0}" -ne 1 ]; then
  echo "error: $JAR has ${DEX_COUNT:-0} dex files; that is a framework/merged jar" >&2
  echo "       make-module.sh only packages the standalone guard jar:" >&2
  echo "       ./build.sh --standalone --out dist/services-guard.jar" >&2
  exit 1
fi

WORK="$PROJECT/.build-module"
OUT="$PROJECT/dist/FactoryPop-${VERSION#v}.zip"
rm -rf "$WORK"
mkdir -p "$WORK" "$(dirname "$OUT")"
cp -a "$PROJECT/module/." "$WORK/"

# version fields of the module
sed -i \
  -e "s|^version=.*|version=$VERSION|" \
  -e "s|^versionCode=.*|versionCode=$VERSION_CODE|" \
  "$WORK/module.prop"

# The guard jar lives in the module directory, never in system/framework.
cp -f "$JAR" "$WORK/services.jar"
rm -rf "$WORK/system"

chmod 0755 "$WORK/service.sh" "$WORK/META-INF/com/google/android/update-binary"
chmod 0644 "$WORK/module.prop" "$WORK/services.jar"

rm -f "$OUT"
(
  cd "$WORK"
  zip -q -r "$OUT" . -x '.*'
)

# verify the module layout
unzip -Z1 "$OUT" > "$WORK/entries.txt"
for f in module.prop service.sh services.jar \
         META-INF/com/google/android/update-binary \
         META-INF/com/google/android/updater-script
do
  grep -qx "$f" "$WORK/entries.txt" || { echo "error: $f missing from the module" >&2; exit 1; }
done
if grep -qE '^system/' "$WORK/entries.txt"; then
  echo "error: the module must not contain a system/ tree (framework replacement)" >&2
  exit 1
fi
if unzip -p "$OUT" service.sh | grep -vE '^[[:space:]]*#' \
     | grep -q '/system/framework/services.jar'; then
  echo "error: service.sh still points at the framework jar" >&2
  exit 1
fi
unzip -t -q "$OUT" >/dev/null
rm -rf "$WORK"

echo "module: $OUT"
echo "bytes: $(wc -c < "$OUT")"
echo "sha256: $(sha256sum "$OUT" | cut -d' ' -f1)"

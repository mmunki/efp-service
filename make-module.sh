#!/usr/bin/env bash
# ============================================================================
# make-module.sh -- package the merged services.jar as a Magisk module
# ----------------------------------------------------------------------------
# The module mounts the merged jar as /system/framework/services.jar and starts
# the guard from it with app_process (module/service.sh).  No cmd wrapper in
# /system/bin is installed or needed.
#
# Usage:
#   ./make-module.sh [dist/services.jar] [version]
#
# Output: dist/FactoryPop-<version>.zip
# ============================================================================
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${1:-$PROJECT/dist/services.jar}"
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

[ -s "$JAR" ] || { echo "error: merged jar not found: $JAR" >&2; exit 1; }

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

mkdir -p "$WORK/system/framework"
cp -f "$JAR" "$WORK/system/framework/services.jar"

chmod 0755 "$WORK/service.sh" "$WORK/META-INF/com/google/android/update-binary"
chmod 0644 "$WORK/module.prop" "$WORK/system/framework/services.jar"

rm -f "$OUT"
(
  cd "$WORK"
  zip -q -r "$OUT" . -x '.*'
)

# verify the module layout
unzip -Z1 "$OUT" > "$WORK/entries.txt"
for f in module.prop service.sh system/framework/services.jar \
         META-INF/com/google/android/update-binary \
         META-INF/com/google/android/updater-script
do
  grep -qx "$f" "$WORK/entries.txt" || { echo "error: $f missing from the module" >&2; exit 1; }
done
unzip -t -q "$OUT" >/dev/null
rm -rf "$WORK"

echo "module: $OUT"
echo "bytes: $(wc -c < "$OUT")"
echo "sha256: $(sha256sum "$OUT" | cut -d' ' -f1)"

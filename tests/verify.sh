#!/usr/bin/env bash
# ============================================================================
# tests/verify.sh -- build the boot-safe guard and the Magisk module, and
#                    check that the module cannot replace the framework jar.
#
# Usage:
#   tests/verify.sh
#
# The old merge path is still available for experts; run it explicitly with:
#   FACTORYPOP_TEST_MERGE=1 tests/verify.sh [base-services.jar]
# ============================================================================
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$PROJECT/.build-tests"
BASE="${1:-${FACTORYPOP_BASE_JAR:-}}"
TEST_VERSION="0.4.0-test"
MODULE="$PROJECT/dist/FactoryPop-$TEST_VERSION.zip"

pass() { printf '  PASS  %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }

rm -rf "$OUT" "$MODULE"
mkdir -p "$OUT"

DEXDUMP=""
for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk; do
  [ -n "$cand" ] && [ -d "$cand" ] || continue
  DEXDUMP="$(find "$cand/build-tools" -name dexdump -type f 2>/dev/null | sort -V | tail -1)"
  [ -n "$DEXDUMP" ] && break
done
[ -n "$DEXDUMP" ] || fail "dexdump not found (install Android build-tools)"

classes() {  # classes <jar>/<dex> -> class descriptors
  "$DEXDUMP" -f "$1" 2>/dev/null | sed -n "s/^  Class descriptor  : '\(.*\)'$/\1/p"
}

# ---------------------------------------------------------------------------
step "build the standalone guard jar"
bash "$PROJECT/build.sh" --standalone --out "$OUT/services-guard.jar" >"$OUT/build-guard.log" 2>&1 \
  || { cat "$OUT/build-guard.log" >&2; fail "build.sh --standalone"; }
[ -s "$OUT/services-guard.jar" ] || fail "no guard jar produced"
unzip -p "$OUT/services-guard.jar" classes.dex > "$OUT/guard.dex"
pass "guard jar built ($(wc -c < "$OUT/services-guard.jar") bytes)"

for cls in \
    Lcom/factorypop/efp/FpUninstallGuard\; \
    Lcom/factorypop/efp/FpGuardLoop\; \
    Lcom/factorypop/efp/FpUninstallGuardService\; \
    Lcom/factorypop/efp/Platform\; \
    Lcom/factorypop/efp/tool/BlockUninstall\; \
    Lcom/factorypop/efp/tool/GuardDaemon\;
do
  classes "$OUT/guard.dex" | grep -qx "$cls" || fail "class missing from the guard dex: $cls"
done
pass "all guard classes are in the guard dex"

"$DEXDUMP" -c "$OUT/guard.dex" >/dev/null 2>&1 || fail "guard dex checksum"
pass "guard dex checksum is valid"

# The whole point of the change: nothing on the protection path may need
# /system/bin/cmd (pm and am are wrappers around it).
if unzip -p "$OUT/services-guard.jar" classes.dex | strings | grep -xE 'cmd|pm|am|/system/bin/cmd'; then
  fail "the guard still references cmd/pm/am"
fi
pass "the guard does not invoke cmd, pm or am"

# ---------------------------------------------------------------------------
step "package the boot-safe Magisk module"
unzip -Z1 "$OUT/services-guard.jar" | grep -qE '^classes[0-9]*\.dex$' \
  || fail "the guard jar has no dex"
DEX_COUNT="$(unzip -Z1 "$OUT/services-guard.jar" | grep -cE '^classes[0-9]*\.dex$')"
[ "$DEX_COUNT" -eq 1 ] || fail "the guard jar must have exactly one dex (found $DEX_COUNT)"

bash "$PROJECT/make-module.sh" "$OUT/services-guard.jar" "$TEST_VERSION" >"$OUT/make-module.log" 2>&1 \
  || { cat "$OUT/make-module.log" >&2; fail "make-module.sh"; }
[ -s "$MODULE" ] || fail "no module produced at $MODULE"
pass "module built ($(wc -c < "$MODULE") bytes)"

unzip -Z1 "$MODULE" > "$OUT/module-entries.txt"
for f in module.prop service.sh services.jar \
         META-INF/com/google/android/update-binary \
         META-INF/com/google/android/updater-script
do
  grep -qx "$f" "$OUT/module-entries.txt" || fail "$f missing from the module"
done
pass "module contains module.prop, service.sh, services.jar and the installer"

if grep -qE '^system/' "$OUT/module-entries.txt"; then
  fail "the module contains a system/ tree and would replace the framework jar"
fi
if grep -qE '^system/framework/services\.jar$' "$OUT/module-entries.txt"; then
  fail "the module still ships system/framework/services.jar"
fi
pass "module has no system/ tree (framework jar is never replaced)"

unzip -p "$MODULE" services.jar > "$OUT/module-services.jar"
a="$(sha256sum "$OUT/services-guard.jar" | cut -d' ' -f1)"
b="$(sha256sum "$OUT/module-services.jar" | cut -d' ' -f1)"
[ "$a" = "$b" ] || fail "the jar inside the module is not the built guard jar"
pass "module ships the exact guard jar (sha256 $b)"

if unzip -p "$MODULE" service.sh | grep -vE '^[[:space:]]*#' \
     | grep -q '/system/framework/services.jar'; then
  fail "service.sh points at the framework jar"
fi
unzip -p "$MODULE" service.sh | grep -q 'services.jar' \
  || fail "service.sh does not reference the module jar"
unzip -p "$MODULE" service.sh | grep -q 'com.factorypop.efp.tool.GuardDaemon' \
  || fail "service.sh does not start GuardDaemon"
unzip -p "$MODULE" service.sh | grep -q 'app_process /system/bin' \
  || fail "service.sh does not use app_process"
pass "service.sh starts GuardDaemon from the module jar, not from /system"

# ---------------------------------------------------------------------------
step "refuse a framework/merged jar as a module payload"
MERGED="$OUT/fake-merged.jar"
cp -f "$OUT/services-guard.jar" "$MERGED"
( cd "$OUT" && zip -q "$MERGED" guard.dex 2>/dev/null || true )
# create a second dex entry so the payload looks like a multidex framework jar
cp -f "$OUT/guard.dex" "$OUT/classes2.dex"
( cd "$OUT" && zip -q "$MERGED" classes2.dex )
if bash "$PROJECT/make-module.sh" "$MERGED" "$TEST_VERSION" >"$OUT/refuse.log" 2>&1; then
  fail "make-module.sh accepted a multi-dex framework jar"
fi
grep -q 'framework/merged jar' "$OUT/refuse.log" \
  || { cat "$OUT/refuse.log" >&2; fail "make-module.sh refused for the wrong reason"; }
pass "make-module.sh refuses a framework/merged jar"

# ---------------------------------------------------------------------------
if [ "${FACTORYPOP_TEST_MERGE:-0}" = 1 ]; then
  step "optional expert path: merge into the given services.jar"
  if [ -z "$BASE" ]; then
    BASE="$OUT/services.base.jar"
    curl -fL --retry 3 -o "$BASE.part" \
      https://github.com/mmunki/efp-service/releases/download/Jar/services.jar \
      || fail "downloading the given services.jar"
    mv -f "$BASE.part" "$BASE"
  fi
  [ -s "$BASE" ] || fail "base jar not found: $BASE"

  bash "$PROJECT/build.sh" --base "$BASE" --out "$OUT/services.jar" >"$OUT/build-merged.log" 2>&1 \
    || { cat "$OUT/build-merged.log" >&2; fail "build.sh --base"; }
  [ -s "$OUT/services.jar" ] || fail "no merged jar produced"
  pass "merged services.jar built ($(wc -c < "$OUT/services.jar") bytes)"

  python3 - "$BASE" "$OUT/services.jar" <<'PY' || fail "merged jar content"
import sys, zipfile
base, merged = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2])
b = {i.filename: i for i in base.infolist()}
m = {i.filename: i for i in merged.infolist()}
assert set(b) <= set(m), "entries disappeared from the base jar"
for n, i in b.items():
    j = m[n]
    assert (i.CRC, i.file_size, i.compress_type) == (j.CRC, j.file_size, j.compress_type), n
extra = sorted(set(m) - set(b))
assert len(extra) == 1 and extra[0].startswith("classes"), extra
print("  base entries preserved: %d" % len(b))
print("  merged entries        : %d" % len(m))
PY
  pass "every original entry is byte-identical, exactly one guard dex was added"

  unzip -p "$OUT/services.jar" classes5.dex > "$OUT/classes5.dex"
  "$DEXDUMP" -c "$OUT/classes5.dex" >/dev/null 2>&1 || fail "classes5.dex checksum"
  classes "$OUT/classes5.dex" | grep -qx 'Lcom/factorypop/efp/tool/GuardDaemon;' \
    || fail "GuardDaemon is not in the merged jar"
  pass "classes5.dex is valid and carries the daemon"

  unzip -t -q "$OUT/services.jar" >/dev/null || fail "merged jar is corrupt"
  pass "merged jar is a valid zip"
fi

# ---------------------------------------------------------------------------
step "result"
echo "  guard jar : $OUT/services-guard.jar"
echo "  module    : $MODULE"
echo "  sha256    : $(sha256sum "$OUT/services-guard.jar" | cut -d' ' -f1)"
echo

rm -f "$MODULE"
echo "ALL TESTS PASSED"

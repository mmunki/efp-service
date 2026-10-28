#!/usr/bin/env bash
# ============================================================================
# tests/verify.sh -- build the guard, merge it into the given services.jar and
#                    check the result.  Exits non-zero on the first failure.
#
# Usage:
#   tests/verify.sh [base-services.jar]
#
# Without an argument the base jar of the release "Jar" is downloaded (that is
# the services.jar that was given).
# ============================================================================
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$PROJECT/.build-tests"
BASE="${1:-${FACTORYPOP_BASE_JAR:-}}"

pass() { printf '  PASS  %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }

rm -rf "$OUT"
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
step "merge into the given services.jar"
if [ -z "$BASE" ]; then
  BASE="$PROJECT/.build-tests/services.base.jar"
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

# every framework dex of the base must be untouched
for i in "" 2 3 4; do
  n="classes$i.dex"
  a="$(unzip -p "$BASE" "$n" | sha256sum | cut -d' ' -f1)"
  b="$(unzip -p "$OUT/services.jar" "$n" | sha256sum | cut -d' ' -f1)"
  [ "$a" = "$b" ] || fail "$n changed during the merge"
done
pass "classes.dex..classes4.dex are unchanged (sha256)"

# the merged archive is a healthy zip and every dex slot is contiguous
unzip -t -q "$OUT/services.jar" >/dev/null || fail "merged jar is corrupt"
unzip -Z1 "$OUT/services.jar" > "$OUT/entries.txt"
for i in 1 2 3 4 5; do
  if [ "$i" = 1 ]; then n=classes.dex; else n="classes$i.dex"; fi
  grep -qx "$n" "$OUT/entries.txt" || fail "missing $n"
done
pass "merged jar is a valid zip with classes.dex..classes5.dex"

# ---------------------------------------------------------------------------
step "boot path does not need a command in /system/bin"
grep -q 'app_process /system/bin' "$PROJECT/module/service.sh" \
  || fail "module/service.sh does not start app_process"
grep -q 'com.factorypop.efp.tool.GuardDaemon' "$PROJECT/module/service.sh" \
  || fail "module/service.sh does not start GuardDaemon"
if grep -vE '^[[:space:]]*#' "$PROJECT/module/service.sh" \
   | grep -qE '/(system/)?bin/(cmd|pm|am)([^a-z]|$)'; then
  fail "module/service.sh still calls cmd/pm/am"
fi
pass "the boot script starts the guard from the jar without cmd/pm/am"

# ---------------------------------------------------------------------------
step "result"
echo "  guard jar : $OUT/services-guard.jar"
echo "  services  : $OUT/services.jar"
echo "  sha256    : $(sha256sum "$OUT/services.jar" | cut -d' ' -f1)"
echo
echo "ALL TESTS PASSED"

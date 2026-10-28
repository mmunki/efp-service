#!/usr/bin/env bash
# ============================================================================
# merge-services.sh -- merge the FactoryPop guard into the given services.jar
# ----------------------------------------------------------------------------
# The services jar of the ROM is a real framework jar with several dex files
# (classes.dex ... classesN.dex) plus framework resources.  Replacing it with
# the small guard jar would break the framework, so the guard is merged INTO
# the given jar instead:
#
#   * every original entry of the base jar is kept byte for byte
#     (same CRC-32, same size, same compression method);
#   * the guard dex is appended as the next free classesN.dex, which ART loads
#     together with the other dex files of the jar (multidex archive).
#
# No standalone services.jar is produced when a base jar is given.
#
# Usage:
#   ./merge-services.sh --base services.jar --guard dist/services-guard.jar \
#       --out dist/services.jar
#
#   ./merge-services.sh --base services.jar --build-guard --out services.jar
#
# Without --base the services.jar of the release "Jar" of this repository is
# used (that is the jar that was given):
#   https://github.com/mmunki/efp-service/releases/download/Jar/services.jar
# ============================================================================
set -euo pipefail

log() { printf '==> %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="${FACTORYPOP_BASE_JAR:-}"
GUARD=""
OUT="dist/services.jar"
BUILD_GUARD=0
BASE_URL="${FACTORYPOP_BASE_URL:-https://github.com/mmunki/efp-service/releases/download/Jar/services.jar}"

while [ $# -gt 0 ]; do
  case "$1" in
    --base)        BASE="${2:?--base needs a value}"; shift 2 ;;
    --guard)       GUARD="${2:?--guard needs a value}"; shift 2 ;;
    --out)         OUT="${2:?--out needs a value}"; shift 2 ;;
    --base-url)    BASE_URL="${2:?--base-url needs a value}"; shift 2 ;;
    --build-guard) BUILD_GUARD=1; shift ;;
    -h|--help)     usage 0 ;;
    -*)            die "unknown option: $1 (try --help)" ;;
    *)             OUT="$1"; shift ;;
  esac
done

command -v unzip >/dev/null 2>&1 || die "unzip is required"
command -v zip >/dev/null 2>&1 || die "zip is required"

dex_name() {
  if [ "$1" = 1 ]; then printf 'classes.dex'; else printf 'classes%s.dex' "$1"; fi
}

# ---- the base jar -----------------------------------------------------------
if [ -z "$BASE" ]; then
  CACHE="$PROJECT/.build-merge"
  BASE="$CACHE/services.base.jar"
  if [ ! -s "$BASE" ]; then
    mkdir -p "$CACHE"
    log "fetching the given services.jar: $BASE_URL"
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 -o "$BASE.part" "$BASE_URL" || die "download failed: $BASE_URL"
    elif command -v gh >/dev/null 2>&1; then
      ( cd "$CACHE" && gh release download Jar --repo mmunki/efp-service \
          --pattern services.jar --clobber ) || die "download failed: $BASE_URL"
    else
      die "curl or gh is required to fetch the base services.jar"
    fi
    [ -f "$BASE.part" ] && mv -f "$BASE.part" "$BASE"
  fi
fi
[ -f "$BASE" ] || die "base jar not found: $BASE"
BASE="$(cd "$(dirname "$BASE")" && pwd)/$(basename "$BASE")"

# ---- the guard jar ----------------------------------------------------------
if [ -z "$GUARD" ]; then
  GUARD="$PROJECT/dist/services-guard.jar"
  [ -f "$GUARD" ] || BUILD_GUARD=1
fi
if [ "$BUILD_GUARD" = 1 ] || [ ! -f "$GUARD" ]; then
  log "building the guard jar"
  ( cd "$PROJECT" && bash build.sh "$GUARD" )
fi
[ -s "$GUARD" ] || die "guard jar not found or empty: $GUARD"
GUARD="$(cd "$(dirname "$GUARD")" && pwd)/$(basename "$GUARD")"

WORK="$PROJECT/.build-merge/work"
rm -rf "$WORK"
mkdir -p "$WORK/guard"

unzip -o -q "$GUARD" 'classes*.dex' -d "$WORK/guard" 2>/dev/null \
  || die "the guard jar contains no dex"
GUARD_DEX="$(find "$WORK/guard" -maxdepth 1 -name 'classes*.dex' | sort -V | head -1)"
[ -s "$GUARD_DEX" ] || die "the guard jar contains no dex"
GUARD_DEX_COUNT="$(find "$WORK/guard" -maxdepth 1 -name 'classes*.dex' | wc -l)"
if [ "$GUARD_DEX_COUNT" != 1 ]; then
  log "warning: the guard jar has $GUARD_DEX_COUNT dex files, merging $GUARD_DEX"
fi

# ---- pick the next free dex slot -------------------------------------------
NEXT=1
while [ -n "$(unzip -Z1 "$BASE" "$(dex_name "$NEXT")" 2>/dev/null)" ]; do
  NEXT=$((NEXT + 1))
done
DEX_NAME="$(dex_name "$NEXT")"
log "base dex files: $((NEXT - 1)), the guard becomes $DEX_NAME"

mkdir -p "$WORK/add"
cp -f "$GUARD_DEX" "$WORK/add/$DEX_NAME"

# ---- merge: copy the base, append the guard dex ----------------------------
OUT_DIR="$(dirname "$OUT")"
mkdir -p "$OUT_DIR"
OUT="$(cd "$OUT_DIR" && pwd)/$(basename "$OUT")"
[ "$OUT" != "$BASE" ] || die "refusing to overwrite the base jar in place"

cp -f "$BASE" "$WORK/merged.jar"
(
  cd "$WORK/add"
  # -0 keeps the dex stored/uncompressed like the framework dex files, -X drops
  # extra attributes so the appended entry stays minimal
  zip -X -0 -q "$WORK/merged.jar" "$DEX_NAME"
) || die "appending $DEX_NAME failed"

# ---- verify ----------------------------------------------------------------
log "verifying the merged jar"
python3 - "$BASE" "$WORK/merged.jar" "$DEX_NAME" <<'PY'
import sys, zipfile, hashlib

base_path, merged_path, dex_name = sys.argv[1], sys.argv[2], sys.argv[3]
base = zipfile.ZipFile(base_path)
merged = zipfile.ZipFile(merged_path)

base_infos = {i.filename: i for i in base.infolist()}
merged_infos = {i.filename: i for i in merged.infolist()}

missing = sorted(set(base_infos) - set(merged_infos))
if missing:
    sys.exit("error: the merge dropped %d entries of the base jar: %s"
             % (len(missing), ", ".join(missing[:10])))

for name, i in base_infos.items():
    j = merged_infos[name]
    if i.CRC != j.CRC or i.file_size != j.file_size or i.compress_type != j.compress_type:
        sys.exit("error: base entry changed by the merge: %s" % name)

if dex_name not in merged_infos:
    sys.exit("error: the guard dex is not in the merged jar")

extra = sorted(set(merged_infos) - set(base_infos))
if extra != [dex_name]:
    sys.exit("error: unexpected extra entries: %s" % ", ".join(extra))

base_sha = hashlib.sha256(open(base_path, "rb").read()).hexdigest()
merged_sha = hashlib.sha256(open(merged_path, "rb").read()).hexdigest()
print("base entries      : %d (all preserved)" % len(base_infos))
print("merged entries    : %d (+ %s)" % (len(merged_infos), dex_name))
print("base sha256       : %s" % base_sha)
print("merged sha256     : %s" % merged_sha)
PY

unzip -t -q "$WORK/merged.jar" >/dev/null || die "the merged jar is corrupt"

DEXDUMP=""
for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk; do
  [ -n "$cand" ] && [ -d "$cand" ] || continue
  DEXDUMP="$(find "$cand/build-tools" -name dexdump -type f 2>/dev/null | sort -V | tail -1)"
  [ -n "$DEXDUMP" ] && break
done

if [ -n "$DEXDUMP" ]; then
  unzip -p "$WORK/merged.jar" "$DEX_NAME" > "$WORK/verify.dex"
  "$DEXDUMP" -c "$WORK/verify.dex" >/dev/null 2>&1 || die "$DEX_NAME failed the dex checksum check"
  "$DEXDUMP" -f "$WORK/verify.dex" > "$WORK/verify.txt"
  for cls in \
      'Lcom/factorypop/efp/FpUninstallGuard;' \
      'Lcom/factorypop/efp/FpGuardLoop;' \
      'Lcom/factorypop/efp/FpUninstallGuardService;' \
      'Lcom/factorypop/efp/Platform;' \
      'Lcom/factorypop/efp/tool/BlockUninstall;' \
      'Lcom/factorypop/efp/tool/GuardDaemon;'
  do
    grep -qF "Class descriptor  : '$cls'" "$WORK/verify.txt" \
      || die "class missing from $DEX_NAME: $cls"
  done
  log "dex verified with dexdump, all guard classes present"
else
  log "dexdump not found, skipping the dex class check"
fi

mv -f "$WORK/merged.jar" "$OUT"
chmod 644 "$OUT"
rm -rf "$WORK"

log "merged the guard into $BASE as $DEX_NAME"
echo "out: $OUT"
echo "bytes: $(wc -c <"$OUT")"
echo "sha256: $(sha256sum "$OUT" | cut -d' ' -f1)"

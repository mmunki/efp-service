#!/system/bin/sh
# ============================================================================
# FactoryPop boot start (boot-safe)
# ----------------------------------------------------------------------------
# The guard is an ordinary jar in the module directory:
#
#   /data/adb/modules/factorypop-efp/services.jar
#
# It is loaded with app_process on top of the stock framework.  This module
# must never mount a jar over /system/framework/services.jar: replacing that
# boot class path file with a jar from another ROM (or even the same ROM with
# an extra dex) is what leaves a device stuck on the boot animation.
#
# Nothing here needs a command in /system/bin: no cmd, no pm, no am, no
# wrapper.  The guard talks to PackageManagerService and ActivityManagerService
# through their binder interfaces.
# ============================================================================

export PATH=/system/bin:/system/xbin:/odm/bin:/vendor/bin:$PATH

MODDIR=${0%/*}
[ -d "$MODDIR" ] || MODDIR=/data/adb/modules/factorypop-efp
JAR="$MODDIR/services.jar"
STATE=/data/adb/factorypop
LOG="$STATE/boot.log"
FORENSIC=${FACTORYPOP_FORENSIC_DIR:-/data/adb/jp.konami.pes}
UNLOG="$FORENSIC/uninstall.log"
PKG=${FACTORYPOP_PKG:-jp.konami.pesam}

mkdir -p "$STATE" "$FORENSIC" 2>/dev/null
chmod 700 "$FORENSIC" 2>/dev/null
[ -f "$UNLOG" ] || : > "$UNLOG" 2>/dev/null
chmod 600 "$UNLOG" 2>/dev/null

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null
    echo "$(date '+%Y-%m-%d %H:%M:%S') [SERVICE] $*" >> "$UNLOG" 2>/dev/null
}

# A jar under /system/framework is the framework jar.  It does not carry the
# guard on a stock ROM, and a modified copy is exactly what prevents boot.
case "$JAR" in
    /system/*)
        log "ERROR: refusing to use a jar below /system: $JAR"
        exit 1
        ;;
esac

if [ ! -s "$JAR" ]; then
    log "ERROR: guard jar missing: $JAR"
    exit 1
fi

# already running?  the daemon has its own lock, this only avoids a second
# app_process during a soft restart of the service
if [ -f "$STATE/lock.lock/pid" ]; then
    PID=$(cat "$STATE/lock.lock/pid" 2>/dev/null)
    [ -n "$PID" ] && [ -d "/proc/$PID" ] && exit 0
fi

log "starting guard from $JAR (pkg $PKG, forensic $UNLOG)"
CLASSPATH="$JAR" app_process /system/bin \
    --nice-name=factorypop \
    com.factorypop.efp.tool.GuardDaemon --pkg "$PKG" --user 0 \
    >>"$LOG" 2>&1 &
log "app_process pid $!"

exit 0

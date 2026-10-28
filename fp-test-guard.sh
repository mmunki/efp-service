#!/system/bin/sh
# ============================================================
#  FactoryPop :: eFootball protection (3 layers)
#
#  LAYER 1 - uninstall lock
#    PackageManagerService.setBlockUninstallForUser -> every
#    delete request is rejected with DELETE_FAILED_OWNER_BLOCKED:
#    launcher, Settings, Play Store, pm, cmd, adb, root.
#
#  LAYER 2 - the guard lives in the services jar
#    FpGuardLoop / FpUninstallGuard are compiled into services.jar
#    (the framework jar, merged with the guard dex).  The daemon
#    reaches PackageManagerService and ActivityManagerService
#    directly, so the protection needs no cmd wrapper in bin/
#    and no pm/am call.  The old /system/bin/cmd wrapper is
#    optional and no longer part of the automatic path.
#
#  LAYER 3 - data safety net (snapshot + auto restore)
#    A wipe (Settings button, privileged service, root rm) is
#    detected within seconds and the snapshot is restored.
#
#  REINSTALL SAFE
#    firstInstallTime is tracked. After an uninstall + reinstall
#    the daemon re-applies the lock, archives the old snapshot
#    (data.old.tar) and takes a fresh one - it never pours the
#    previous install's data into the new install by accident.
#    An "unlock" is consumed by the uninstall, so the next
#    install is protected again automatically.
#
#  manual:
#    sh lock.sh --status
#    sh lock.sh --unlock | --lock
#    sh lock.sh --snapshot | --restore | --restore-old
#
#  test overrides: FACTORYPOP_PKG / STATE / MODDIR / SNAP_MAX / MIN_MB
# ============================================================

PKG=${FACTORYPOP_PKG:-jp.konami.pesam}
STATE=${FACTORYPOP_STATE:-/data/adb/factorypop}
MODDIR=${FACTORYPOP_MODDIR:-/data/adb/modules/FactoryPop}
JAR=${FACTORYPOP_JAR:-$MODDIR/services.jar}
# the guard now lives inside the framework services.jar; use it when the
# module does not ship its own copy
[ -s "$JAR" ] || JAR=/system/framework/services.jar
LOG=$STATE/lock.log
SNAP=$STATE/snapshot/data.tar
SNAP_TS=$STATE/snapshot/data.ts
STAMP=$STATE/install.stamp
SNAP_MAX=${FACTORYPOP_SNAP_MAX:-172800}
MIN_MB=${FACTORYPOP_MIN_MB:-50}
SENTINEL=.factorypop_guard
REAPPLY=${FACTORYPOP_REAPPLY:-300}

mkdir -p "$STATE" 2>/dev/null

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }
tool() { CLASSPATH="$JAR" app_process /system/bin com.factorypop.efp.tool.BlockUninstall "$PKG" "$1" 0 2>&1; }
installed() { tool get 2>/dev/null | grep -q 'installed=true'; }
app_running() { [ -n "$(pidof "$PKG" 2>/dev/null)" ]; }

app_uid() {
    # the owner of the data dir, no pm needed
    stat -c %u "/data/data/$PKG" 2>/dev/null
}

guard_active() {
    # the guard is active when the services jar carries it
    [ -s "$JAR" ]
}

install_stamp() {
    dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*firstInstallTime=//p' | head -1
}

# returns 0 when a brand new installation was detected
check_reinstall() {
    local cur old
    cur=$(install_stamp)
    [ -n "$cur" ] || return 1
    old=$(cat "$STAMP" 2>/dev/null)
    if [ -z "$old" ]; then
        echo "$cur" > "$STAMP"          # first run of this feature
        return 1
    fi
    [ "$cur" = "$old" ] && return 1

    log "NEW INSTALL detected (old: $old | new: $cur)"
    if [ -s "$SNAP" ]; then
        mv -f "$SNAP" "${SNAP%.tar}.old.tar" 2>/dev/null
        rm -f "$SNAP_TS"
        log "snapshot: previous install archived as data.old.tar"
    fi
    rm -f "/data/data/$PKG/$SENTINEL" 2>/dev/null
    rm -f "$STATE/need_restore"
    echo "$cur" > "$STAMP"
    if [ -f "$STATE/unlocked" ]; then
        rm -f "$STATE/unlocked"
        log "unlock flag cleared - the new install is protected"
    fi
    return 0
}

touch_sentinel() {
    local d=/data/data/$PKG u
    [ -d "$d" ] || return
    : > "$d/$SENTINEL" 2>/dev/null || return
    u=$(app_uid)
    [ -n "$u" ] && chown "$u:$u" "$d/$SENTINEL" 2>/dev/null
    chmod 0600 "$d/$SENTINEL" 2>/dev/null
    command -v restorecon >/dev/null 2>&1 && restorecon "$d/$SENTINEL" 2>/dev/null
}

snap_make() {
    local force=$1 d=/data/data/$PKG tmp=$SNAP.tmp free sz
    [ -d "$d" ] || return 0
    # never overwrite a good snapshot with a wiped/empty data dir
    if [ -s "$SNAP" ] && [ ! -e "$d/$SENTINEL" ]; then
        log "snapshot: skipped (data looks wiped - restore pending)"
        return 0
    fi
    if [ "$force" != 1 ] && [ -s "$SNAP" ] && [ -f "$SNAP_TS" ]; then
        [ $(( $(date +%s) - $(cat "$SNAP_TS" 2>/dev/null || echo 0) )) -lt "$SNAP_MAX" ] && return 0
    fi
    app_running && { [ "$force" = 1 ] && log "snapshot: skipped (app is running)"; return 0; }
    free=$(df -k /data 2>/dev/null | awk 'NR==2{print $4}')
    [ -n "$free" ] && [ "$free" -lt 5242880 ] && { log "snapshot: skipped (low space)"; return 1; }

    sz=$(du -sm "$d" 2>/dev/null | cut -f1)
    if [ "$force" != 1 ] && [ -n "$sz" ] && [ "$sz" -lt "$MIN_MB" ]; then
        if [ "$WAIT_LOGGED" != 1 ]; then
            log "snapshot: waiting for app data (${sz}MB < ${MIN_MB}MB)"
            WAIT_LOGGED=1
        fi
        return 0
    fi
    WAIT_LOGGED=0

    log "snapshot: creating (game is idle)..."
    mkdir -p "${SNAP%/*}" 2>/dev/null || return 1
    rm -f "$tmp"
    tar -cf "$tmp" -C /data/data "$PKG" 2>/dev/null
    if [ -s "$tmp" ]; then
        mv -f "$tmp" "$SNAP"
        date +%s > "$SNAP_TS"
        touch_sentinel
        log "snapshot: ready ($(du -sm "$SNAP" 2>/dev/null | cut -f1)MB)"
    else
        rm -f "$tmp"
        log "snapshot: FAILED"
        return 1
    fi
}

restore_data() {
    local d=/data/data/$PKG u
    rm -f "$STATE/need_restore"
    if [ ! -s "$SNAP" ]; then
        log "restore: no snapshot available"
        return 1
    fi
    log "restore: begin ($(du -sm "$SNAP" 2>/dev/null | cut -f1)MB)"
    for p in $(pidof "$PKG" 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
    sleep 1
    rm -rf "$d"
    tar -xpf "$SNAP" -C /data/data 2>/dev/null
    u=$(app_uid)
    [ -n "$u" ] && chown -R "$u:$u" "$d" 2>/dev/null
    command -v restorecon >/dev/null 2>&1 && restorecon -R "$d" 2>/dev/null
    touch_sentinel
    log "restore: done - data returned from snapshot"
}

# ---------------- manual control ----------------
case "$1" in
    --unlock)
        rm -f "$STATE/unlocked"
        touch "$STATE/unlocked"
        if installed; then
            out=$(tool false)
            log "unlock: manual -> $out"
            echo "$out"
        else
            echo "package not installed; will stay unlocked"
        fi
        echo "uninstall / clear are now ALLOWED for $PKG"
        echo "(the unlock is consumed automatically once the app is uninstalled)"
        exit 0
        ;;
    --lock)
        rm -f "$STATE/unlocked"
        if installed; then
            out=$(tool true)
            log "lock: manual -> $out"
            echo "$out"
        else
            echo "package not installed; lock will be applied when it appears"
        fi
        exit 0
        ;;
    --snapshot)
        snap_make 1
        ls -lh "$SNAP" 2>/dev/null
        exit $?
        ;;
    --restore)
        restore_data
        exit $?
        ;;
    --restore-old)
        old=${SNAP%.tar}.old.tar
        if [ ! -s "$old" ]; then
            echo "no previous-install snapshot ($old)"
            exit 1
        fi
        SNAP=$old
        restore_data
        exit $?
        ;;
    --status)
        if installed; then
            echo "installed : yes ($(install_stamp))"
            echo "locked    : $(tool get | tail -1)"
        else
            echo "installed : no"
        fi
        if guard_active; then echo "services  : $JAR"; else echo "services  : MISSING ($JAR)"; fi
        if [ -s "$SNAP" ]; then
            echo "snapshot  : $(du -sm "$SNAP" 2>/dev/null | cut -f1)MB, $(date -d "@$(cat "$SNAP_TS" 2>/dev/null)" '+%Y-%m-%d %H:%M' 2>/dev/null)"
        else
            echo "snapshot  : none (waiting for the app to be used)"
        fi
        [ -s "${SNAP%.tar}.old.tar" ] && echo "prev-install snapshot: $(du -sm "${SNAP%.tar}.old.tar" | cut -f1)MB (--restore-old)"
        [ -f "$STATE/unlocked" ] && echo "override  : UNLOCKED"
        echo "log       : $LOG"
        exit 0
        ;;
esac

# ---------------- daemon ----------------
[ -s "$JAR" ] || { log "ERROR: missing $JAR"; exit 1; }

LOCK=$STATE/lock.lock
if ! mkdir "$LOCK" 2>/dev/null; then
    OLD=$(cat "$LOCK/pid" 2>/dev/null)
    if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
        exit 0                                  # already running
    fi
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT
trap 'exit 0' INT TERM
trap '' HUP

echo -1000 > /proc/self/oom_score_adj 2>/dev/null

# ---- log watcher: uninstall attempts + data clears ----
watch_logs() {
    local lastkey="" key
    logcat -b all -v time -T 1 -s pm_critical_info PackageManager ActivityManager installer_clear_app_data_caller 2>/dev/null |
    while IFS= read -r line; do
        case "$line" in
            *"Uninstall pkg: $PKG "*)
                key=$(echo "$line" | sed -n 's/^\([0-9-]* [0-9:]*\).*from u\([0-9]*\)\/\([0-9]*\).*/\1 \2 \3/p')
                [ -n "$key" ] && [ "$key" = "$lastkey" ] && continue
                [ -n "$key" ] && lastkey=$key
                log "ATTEMPT uninstall: $line"
                sleep 2
                if installed; then
                    log "RESULT: blocked - $PKG is still installed"
                else
                    log "RESULT: REMOVED despite the lock!"
                    touch "$STATE/recheck"
                fi
                ;;
            *clearApplicationUserData*)
                case "$line" in
                    *"$PKG"*) log "ATTEMPT clear-data: $line" ;;
                esac
                ;;
            *"[2003,"*"$PKG"*)
                log "CLEAR CALLER: $line"
                ;;
        esac
    done
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 3
log "protection daemon: armed for $PKG"
log "guard jar: $JAR"
[ -s "$JAR" ] || log "WARNING: the guard jar is missing"

watch_logs & WATCHER=$!

applied=0
was=0
last=0
WAIT_LOGGED=0

while :; do
    if ! kill -0 "$WATCHER" 2>/dev/null; then
        watch_logs & WATCHER=$!
        log "log watcher restarted"
    fi

    # an unlock only ever covers one uninstall; consume it when the app is gone
    if [ -f "$STATE/unlocked" ] && ! installed; then
        rm -f "$STATE/unlocked"
        log "unlock consumed by uninstall - next install will be protected"
    fi

    if [ -f "$STATE/unlocked" ]; then
        [ "$applied" = 1 ] && log "standby: UNLOCKED flag present"
        applied=0
        sleep 15
        continue
    fi

    if installed; then
        now=$(date +%s)

        # ---- reinstall handling (before anything else) ----
        check_reinstall

        # ---- layer 1: uninstall lock ----
        if [ "$applied" != 1 ] || [ $((now - last)) -ge "$REAPPLY" ] || [ -f "$STATE/recheck" ]; then
            out=$(tool true)
            rm -f "$STATE/recheck"
            case "$out" in
                OK*)
                    [ "$applied" != 1 ] && log "LOCKED: $PKG cannot be uninstalled"
                    applied=1
                    ;;
                *)
                    log "ERROR: block-uninstall failed: $(echo "$out" | tail -1)"
                    applied=0
                    ;;
            esac
            last=$now
        fi

        # ---- layer 3: wipe detection & restore (before snapshot) ----
        wiped=0
        if [ -s "$SNAP" ] && [ ! -e "/data/data/$PKG/$SENTINEL" ]; then
            sleep 3
            [ -e "/data/data/$PKG/$SENTINEL" ] || wiped=1
        fi
        [ -f "$STATE/need_restore" ] && wiped=1
        if [ "$wiped" = 1 ]; then
            log "ALERT: data wipe detected"
            restore_data
        else
            snap_make
        fi
        was=1
    else
        if [ "$was" = 1 ]; then
            log "NOTE: $PKG is not installed"
            was=0
        fi
        applied=0
    fi
    sleep 15
done

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
#  LAYER 4 - forensic log
#    Every backup, uninstall / clear-data / delete attempt and
#    restore is written to
#    /data/adb/jp.konami.pes/uninstall.log together with the
#    logcat buffer/line, the caller uid/pid, a /proc scan and
#    the instructions for stopping the caller.
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
#    sh lock.sh --uninstall-log
#
#  test overrides: FACTORYPOP_PKG / STATE / MODDIR / SNAP_MAX / MIN_MB
#                  FACTORYPOP_FORENSIC_DIR
# ============================================================

PKG=${FACTORYPOP_PKG:-jp.konami.pesam}
STATE=${FACTORYPOP_STATE:-/data/adb/factorypop}
FORENSIC=${FACTORYPOP_FORENSIC_DIR:-/data/adb/jp.konami.pes}
UNLOG=$FORENSIC/uninstall.log
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

mkdir -p "$STATE" "$FORENSIC" 2>/dev/null
chmod 700 "$FORENSIC" 2>/dev/null
[ -f "$UNLOG" ] || : > "$UNLOG" 2>/dev/null
chmod 600 "$UNLOG" 2>/dev/null

log() {
    local line
    line="$(date '+%Y-%m-%d %H:%M:%S') $*"
    echo "$line" >> "$LOG"
    echo "$line [SHELL]" >> "$UNLOG"
}
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

# ------------------------------------------------------------------
#  forensics: who / which log / how to stop it
#  Everything goes to $UNLOG = /data/adb/jp.konami.pes/uninstall.log
# ------------------------------------------------------------------
forensic_process_scan() {
    local p pid cmd cwd uid fd tgt refs
    {
        echo "PROCESSES (shell scan $(date '+%Y-%m-%d %H:%M:%S')):"
        for p in /proc/[0-9]*; do
            pid=${p#/proc/}
            [ "$pid" = "$$" ] && continue
            cmd=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null)
            cwd=$(readlink "$p/cwd" 2>/dev/null)
            refs=""
            case "$cmd" in *"$PKG"*) refs="cmd";; esac
            case "$cwd" in *"/data/data/$PKG"*) refs="$refs cwd=$cwd";; esac
            for fd in "$p"/fd/*; do
                [ -L "$fd" ] || continue
                tgt=$(readlink "$fd" 2>/dev/null) || continue
                case "$tgt" in
                    *"/data/data/$PKG"*)
                        refs="$refs fd=${fd##*/}->$tgt"
                        ;;
                esac
            done
            [ -n "$refs" ] || continue
            uid=$(awk '/^Uid:/{print $2}' "$p/status" 2>/dev/null)
            echo "  pid=$pid uid=${uid:-?} comm=$(cat "$p/comm" 2>/dev/null) cmd=$cmd refs=$refs"
        done
    } >> "$UNLOG"
}

forensic_how_to_stop() {
    {
        echo "HOW TO STOP IT:"
        echo "  1. The caller uid/pid is in the ATTEMPT line above; the process list"
        echo "     shows a process holding an open file under /data/data/$PKG"
        echo "     (a \"(deleted)\" fd is direct evidence of the deleter)."
        echo "  2. uid 0 = root app/module: check /data/adb/modules, /data/adb/service.d,"
        echo "     /data/adb/post-fs-data.d and any Termux su session; remove the app."
        echo "  3. uid 2000 = adb/root shell: turn off USB debugging and Wireless"
        echo "     debugging, remove unknown automation scripts and modules."
        echo "  4. another uid: find the package that owns it in the process list,"
        echo "     freeze/uninstall it and revoke root/accessibility/device-admin."
        echo "  5. The guard restores the snapshot automatically; the timeline and"
        echo "     the exact logcat buffer/line stay in this file."
    } >> "$UNLOG"
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
    log "BACKUP start trigger=$([ "$force" = 1 ] && echo forced || echo auto) data=$d free=${free:-?}KB"
    mkdir -p "${SNAP%/*}" 2>/dev/null || return 1
    rm -f "$tmp"
    tar -cf "$tmp" -C /data/data "$PKG" 2>/dev/null
    if [ -s "$tmp" ]; then
        mv -f "$tmp" "$SNAP"
        date +%s > "$SNAP_TS"
        touch_sentinel
        log "snapshot: ready ($(du -sm "$SNAP" 2>/dev/null | cut -f1)MB)"
        log "BACKUP done file=$SNAP bytes=$(wc -c < "$SNAP" 2>/dev/null) sha256=$(sha256sum "$SNAP" 2>/dev/null | cut -d' ' -f1)"
    else
        rm -f "$tmp"
        log "snapshot: FAILED"
        log "BACKUP FAILED file=$SNAP"
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
    log "RESTORE start file=$SNAP bytes=$(wc -c < "$SNAP" 2>/dev/null)"
    for p in $(pidof "$PKG" 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
    sleep 1
    rm -rf "$d"
    tar -xpf "$SNAP" -C /data/data 2>/dev/null
    u=$(app_uid)
    [ -n "$u" ] && chown -R "$u:$u" "$d" 2>/dev/null
    command -v restorecon >/dev/null 2>&1 && restorecon -R "$d" 2>/dev/null
    touch_sentinel
    log "restore: done - data returned from snapshot"
    log "RESTORE done file=$SNAP"
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
        echo "forensic  : $UNLOG"
        exit 0
        ;;
    --uninstall-log|--forensics)
        [ -s "$UNLOG" ] || { echo "no forensic log yet ($UNLOG)"; exit 1; }
        tail -n 200 "$UNLOG"
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
    # the main/system/crash buffers, filtered by tag
    logcat -b all -v time -T 1 -s pm_critical_info PackageManager ActivityManager installer_clear_app_data_caller installd auditd 2>/dev/null | filter_lines
}

watch_events() {
    # the events buffer is where am_kill / am_force_stop / am_proc_died live
    logcat -b events -v time -T 1 2>/dev/null | filter_lines
}

filter_lines() {
    local lastkey="" key
    while IFS= read -r line; do
        case "$line" in
            *"Uninstall pkg: $PKG"*|*"Uninstall pkg : $PKG"*)
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
                forensic_process_scan
                forensic_how_to_stop
                ;;
            *clearApplicationUserData*"$PKG"*|*"$PKG"*clearApplicationUserData*)
                log "ATTEMPT clear-data: $line"
                touch "$STATE/need_restore"
                forensic_process_scan
                forensic_how_to_stop
                ;;
            *"Deleting package"*"$PKG"*)
                log "DELETE PACKAGE: $line"
                forensic_process_scan
                ;;
            *"[2003,"*"$PKG"*)
                log "CLEAR CALLER: $line"
                ;;
            *am_kill*"$PKG"*|*am_force_stop*"$PKG"*|*am_proc_died*"$PKG"*)
                log "EVENT: $line"
                ;;
        esac
    done
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 3
log "protection daemon: armed for $PKG"
log "guard jar: $JAR"
log "forensic log: $UNLOG"
[ -s "$JAR" ] || log "WARNING: the guard jar is missing"

watch_logs & WATCHER=$!
watch_events & WATCHER_EVENTS=$!

applied=0
was=0
last=0
WAIT_LOGGED=0

while :; do
    if ! kill -0 "$WATCHER" 2>/dev/null; then
        watch_logs & WATCHER=$!
        log "log watcher restarted"
    fi
    if ! kill -0 "$WATCHER_EVENTS" 2>/dev/null; then
        watch_events & WATCHER_EVENTS=$!
        log "event log watcher restarted"
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
            forensic_process_scan
            forensic_how_to_stop
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

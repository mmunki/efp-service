#!/system/bin/sh
# FactoryPop :: cmd guard
#
# /system/bin/cmd is the single binary behind `pm`, `am` and
# `cmd`.  Everything passes through untouched except destructive
# commands aimed at the protected package:
#
#   pm clear / uninstall / disable / hide / suspend
#   am force-stop / kill / stop-app
#
# Set the package in /data/adb/factorypop/protected_pkg

REAL=/system/bin/cmd.real
[ -x "$REAL" ] || REAL=/data/adb/factorypop/bin/cmd.real

PKG=$(cat /data/adb/factorypop/protected_pkg 2>/dev/null)
[ -n "$PKG" ] || PKG=jp.konami.pesam

grp=$1
act=$2

blocked=0
case "$grp:$act" in
    package:clear|package:uninstall|package:delete)
        blocked=1 ;;
    package:uninstall-system-updates|package:clear-app-data)
        blocked=1 ;;
    package:disable|package:disable-user|package:disable-until-used)
        blocked=1 ;;
    package:hide|package:suspend)
        blocked=1 ;;
    activity:force-stop|activity:kill|activity:kill-all)
        blocked=1 ;;
    activity:stop-app|activity:clear-external-storage)
        blocked=1 ;;
esac

if [ "$blocked" = 1 ]; then
    for a in "$@"; do
        if [ "$a" = "$PKG" ]; then
            caller=$(tr '\0' ' ' < "/proc/$PPID/cmdline" 2>/dev/null)
            echo "$(date '+%Y-%m-%d %H:%M:%S') REFUSED: '$grp $act $PKG' uid=$(id -u) ppid=$PPID caller=${caller:-?}" \
                >> /data/adb/factorypop/lock.log 2>/dev/null
            echo "FactoryPop: $PKG is protected - '$act' refused." >&2
            exit 1
        fi
    done
fi

exec "$REAL" "$@"

#!/system/bin/sh
# ============================================================================
# FactoryPop boot start
# ----------------------------------------------------------------------------
# Starts the guard that lives in the services jar.  Nothing here needs a
# command in /system/bin: no cmd, no pm, no am, no wrapper.  The guard talks
# to PackageManagerService and ActivityManagerService through their binder
# interfaces.
#
# The merged services.jar is mounted by Magisk as /system/framework/services.jar
# when the module contains system/framework/services.jar.  When it is not
# mounted, the same jar can sit in the module directory.
# ============================================================================

MODDIR=${0%/*}

# Prefer the merged framework jar; fall back to a jar shipped next to this
# script (or to the guard-only jar).
JAR=/system/framework/services.jar
[ -f "$MODDIR/services.jar" ] && JAR="$MODDIR/services.jar"

# already running?  the daemon has its own lock, this only avoids a second
# app_process during a soft restart of the service
if [ -f /data/adb/factorypop/lock.lock/pid ]; then
    PID=$(cat /data/adb/factorypop/lock.lock/pid 2>/dev/null)
    [ -n "$PID" ] && [ -d "/proc/$PID" ] && exit 0
fi

CLASSPATH="$JAR" app_process /system/bin \
    --nice-name=factorypop \
    com.factorypop.efp.tool.GuardDaemon --pkg jp.konami.pesam --user 0 &

exit 0

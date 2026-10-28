package com.factorypop.efp;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;

/**
 * FactoryPop :: platform access without /system/bin.
 *
 * Everything the guard needs from the platform is reached through the binder
 * interfaces of the system services (IPackageManager, IActivityManager), the
 * same objects that <code>pm</code> and <code>am</code> talk to.  The guard
 * therefore never executes <code>/system/bin/cmd</code>, <code>pm</code> or
 * <code>am</code> - and <code>pm</code>/<code>am</code> are nothing but shell
 * wrappers around <code>cmd</code>.
 *
 * The guard runs as root (app_process started by the boot script or the
 * Magisk service), so the platform grants it the system permissions these
 * calls need.
 */
public final class Platform {

    private static Object cachedPm;
    private static Object cachedAm;

    private Platform() {}

    /** IPackageManager - the binder object behind pm and `cmd package` */
    private static synchronized Object ipm() {
        if (cachedPm != null) { return cachedPm; }
        Object binder = service("package");
        if (binder != null) {
            try {
                cachedPm = asInterface("android.content.pm.IPackageManager$Stub", binder);
            } catch (Throwable ignored) {}
        }
        if (cachedPm == null) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                cachedPm = at.getMethod("getPackageManager").invoke(null);
            } catch (Throwable ignored) {}
        }
        return cachedPm;
    }

    /** IActivityManager - the binder object behind am and `cmd activity` */
    private static synchronized Object iam() {
        if (cachedAm != null) { return cachedAm; }
        Object binder = service("activity");
        if (binder != null) {
            try {
                cachedAm = asInterface("android.app.IActivityManager$Stub", binder);
            } catch (Throwable ignored) {}
        }
        if (cachedAm == null) {
            try {
                cachedAm = ActivityManager.class.getMethod("getService").invoke(null);
            } catch (Throwable ignored) {}
        }
        return cachedAm;
    }

    private static Object asInterface(String stubClass, Object binder) throws Exception {
        Class<?> stub = Class.forName(stubClass);
        Method asInterface = stub.getMethod("asInterface", android.os.IBinder.class);
        return asInterface.invoke(null, binder);
    }

    /**
     * Exempt this process from the hidden API blacklist, the way an
     * app_process tool runs anyway.  The guard calls platform internals
     * (IPackageManager / IActivityManager) that are hidden API, so make the
     * exemption explicit and tolerate a runtime that does not need it.
     */
    public static void exemptHiddenApis() {
        try {
            Class<?> vmrt = Class.forName("dalvik.system.VMRuntime");
            Object rt = vmrt.getMethod("getRuntime").invoke(null);
            Method set = vmrt.getMethod("setHiddenApiExemptions", String[].class);
            set.invoke(rt, (Object) new String[] { "L" });
        } catch (Throwable ignored) {}
    }

    private static Object service(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getMethod("getService", String.class);
            return get.invoke(null, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Packages owned by a uid - used by the forensic report to name the app
     * behind a "from uid: 10123" line.  IPackageManager.getPackagesForUid is
     * the same call the package manager shell uses; the Context fallback keeps
     * the service path working.
     */
    public static String[] packagesForUid(int uid) {
        Object pm = ipm();
        if (pm != null) {
            try {
                Method m = pm.getClass().getMethod("getPackagesForUid", int.class);
                Object r = m.invoke(pm, uid);
                if (r instanceof String[]) { return (String[]) r; }
            } catch (Throwable ignored) {}
        }
        Context c = FpUninstallGuard.acquireContext();
        if (c != null) {
            try { return c.getPackageManager().getPackagesForUid(uid); } catch (Throwable ignored) {}
        }
        return null;
    }

    public static boolean packageInstalled(String pkg, int user) {
        if (packageInfo(pkg, user) != null) { return true; }
        Context c = FpUninstallGuard.acquireContext();
        if (c != null) {
            try {
                c.getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static long firstInstallTime(String pkg, int user) {
        PackageInfo pi = packageInfo(pkg, user);
        return pi == null ? -1L : pi.firstInstallTime;
    }

    public static int appUid(String pkg, int user) {
        ApplicationInfo ai = applicationInfo(pkg, user);
        return ai == null ? -1 : ai.uid;
    }

    private static PackageInfo packageInfo(String pkg, int user) {
        Object pm = ipm();
        if (pm != null) {
            try {
                Method m = pm.getClass().getMethod(
                        "getPackageInfo", String.class, long.class, int.class);
                Object r = m.invoke(pm, pkg, 0L, user);
                if (r instanceof PackageInfo) { return (PackageInfo) r; }
            } catch (Throwable ignored) {}
            try {
                Method m = pm.getClass().getMethod(
                        "getPackageInfo", String.class, int.class, int.class);
                Object r = m.invoke(pm, pkg, 0, user);
                if (r instanceof PackageInfo) { return (PackageInfo) r; }
            } catch (Throwable ignored) {}
        }
        Context c = FpUninstallGuard.acquireContext();
        if (c != null) {
            try { return c.getPackageManager().getPackageInfo(pkg, 0); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static ApplicationInfo applicationInfo(String pkg, int user) {
        Object pm = ipm();
        if (pm != null) {
            try {
                Method m = pm.getClass().getMethod(
                        "getApplicationInfo", String.class, long.class, int.class);
                Object r = m.invoke(pm, pkg, 0L, user);
                if (r instanceof ApplicationInfo) { return (ApplicationInfo) r; }
            } catch (Throwable ignored) {}
        }
        Context c = FpUninstallGuard.acquireContext();
        if (c != null) {
            try { return c.getPackageManager().getApplicationInfo(pkg, 0); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * PackageManagerService.setBlockUninstallForUser through IPackageManager.
     * Returns true when the platform accepted the call.
     */
    public static boolean setBlockUninstall(String pkg, boolean block, int user) {
        Object pm = ipm();
        if (pm != null) {
            try {
                Method m = pm.getClass().getMethod(
                        "setBlockUninstallForUser", String.class, boolean.class, int.class);
                m.invoke(pm, pkg, block, user);
                return true;
            } catch (Throwable ignored) {}
        }
        // ApplicationPackageManager fallback (hidden method, no Context needed
        // for the user id 0 variant).
        Context c = FpUninstallGuard.acquireContext();
        if (c == null) { return false; }
        try {
            Object p = c.getPackageManager();
            try {
                Method m = p.getClass().getMethod(
                        "setBlockUninstallForUser", String.class, boolean.class, int.class);
                m.invoke(p, pkg, block, user);
                return true;
            } catch (Throwable ignored) {}
            try {
                Method m = p.getClass().getMethod(
                        "setBlockUninstallForUser", String.class, boolean.class);
                m.invoke(p, pkg, block);
                return true;
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    /** IActivityManager.forceStopPackage - the call behind <code>am force-stop</code>. */
    public static boolean forceStop(String pkg, int user) {
        Object am = iam();
        if (am != null) {
            try {
                Method m = am.getClass().getMethod("forceStopPackage", String.class, int.class);
                m.invoke(am, pkg, user);
                return true;
            } catch (Throwable ignored) {}
            try {
                Method m = am.getClass().getMethod("forceStopPackage", String.class);
                m.invoke(am, pkg);
                return true;
            } catch (Throwable ignored) {}
        }
        // Fallbacks that do not need cmd/am: the public ActivityManager call and,
        // as a last resort, the process signal.
        Context c = FpUninstallGuard.acquireContext();
        if (c != null) {
            try {
                Object svc = c.getSystemService(Context.ACTIVITY_SERVICE);
                if (svc instanceof ActivityManager) {
                    ((ActivityManager) svc).killBackgroundProcesses(pkg);
                }
            } catch (Throwable ignored) {}
        }
        int pid = pidOf(pkg);
        if (pid > 0) {
            try {
                android.system.Os.kill(pid, android.system.OsConstants.SIGKILL);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** pid of an app process, read from /proc (no pidof binary needed). */
    public static int pidOf(String pkg) {
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) { return -1; }
        String prefix = pkg + ":";
        for (File e : entries) {
            String name = e.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) { continue; }
            String cmdline = readFirstLine(new File(e, "cmdline"));
            if (cmdline == null) { continue; }
            cmdline = cmdline.replace('\0', ' ').trim();
            if (cmdline.equals(pkg) || cmdline.startsWith(prefix)) {
                try { return Integer.parseInt(name); } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private static String readFirstLine(File f) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            try { return br.readLine(); } finally { br.close(); }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** android.os.SystemProperties.get, reached reflectively (hidden API). */
    public static String getprop(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            if (v instanceof String && !((String) v).isEmpty()) { return (String) v; }
        } catch (Throwable ignored) {}
        return def;
    }

    public static boolean bootCompleted() {
        return "1".equals(getprop("sys.boot_completed", "0"));
    }

    /** true when the platform can serve package queries (system_server is up). */
    public static boolean packageServiceUp() {
        try {
            if (ipm() != null) { return true; }
        } catch (Throwable ignored) {}
        Context c = FpUninstallGuard.acquireContext();
        return c != null;
    }
}

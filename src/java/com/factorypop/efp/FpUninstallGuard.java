package com.factorypop.efp;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.app.ActivityManager;
import android.os.StatFs;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * FactoryPop :: eFootball (JP Konami) protection core.
 *
 * Java port of the daemon logic that used to live in fp-test-guard.sh.  It is
 * hosted inside services.jar (FpUninstallGuardService) so the protection keeps
 * working without any command in /system/bin: the system starts the service and
 * the service drives the platform itself.
 */
public class FpUninstallGuard {

    public static final String DEFAULT_PKG = "jp.konami.pesam";
    public static final String SENTINEL = ".factorypop_guard";

    private static Context appContext;

    private final Context ctx;
    public final String pkg;
    public final File stateDir;
    public final File modDir;
    public final File jarFile;
    public final long snapMax;
    public final long minMb;

    private boolean waitLogged = false;

    public FpUninstallGuard(Context ctx, String pkg) {
        this.ctx = ctx;
        String p = pkg;
        if (p == null || p.trim().isEmpty()) {
            String e = System.getenv("FACTORYPOP_PKG");
            p = (e != null && !e.trim().isEmpty()) ? e.trim() : DEFAULT_PKG;
        }
        this.pkg = p;

        String state = System.getenv("FACTORYPOP_STATE");
        if (state == null || state.trim().isEmpty()) {
            state = "/data/adb/factorypop";
        }
        this.stateDir = new File(state.trim());

        String mod = System.getenv("FACTORYPOP_MODDIR");
        if (mod == null || mod.trim().isEmpty()) {
            mod = "/data/adb/modules/FactoryPop";
        }
        this.modDir = new File(mod.trim());

        String jar = System.getenv("FACTORYPOP_JAR");
        if (jar == null || jar.trim().isEmpty()) {
            jar = new File(this.modDir, "services.jar").getAbsolutePath();
        }
        this.jarFile = new File(jar.trim());

        long sm = 172800L;
        String smEnv = System.getenv("FACTORYPOP_SNAP_MAX");
        if (smEnv != null) { try { sm = Long.parseLong(smEnv.trim()); } catch (NumberFormatException ignored) {} }
        this.snapMax = sm;

        long mm = 50L;
        String mmEnv = System.getenv("FACTORYPOP_MIN_MB");
        if (mmEnv != null) { try { mm = Long.parseLong(mmEnv.trim()); } catch (NumberFormatException ignored) {} }
        this.minMb = mm;
    }

    public static Context currentContext() { return appContext; }
    public static void attachContext(Context c) { appContext = c; }

    /** try to obtain a usable (system) context when none was attached */
    public static Context acquireContext() {
        if (appContext != null) { return appContext; }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = null;
            try { thread = at.getMethod("systemMain").invoke(null); } catch (Exception ignored) {}
            if (thread == null) {
                try { thread = at.getMethod("currentActivityThread").invoke(null); } catch (Exception ignored) {}
            }
            if (thread != null) {
                for (String m : new String[] { "getSystemContext", "getApplication" }) {
                    try {
                        Object r = at.getMethod(m).invoke(thread);
                        if (r instanceof Context) { return (Context) r; }
                    } catch (Exception ignored) {}
                }
                for (Field f : at.getDeclaredFields()) {
                    if (Context.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            Object r = f.get(thread);
                            if (r instanceof Context) { return (Context) r; }
                        } catch (Exception ignored) {}
                    }
                }
            }
            try {
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) { return (Context) app; }
            } catch (Exception ignored) {}
        } catch (ClassNotFoundException ignored) {}
        return null;
    }

    public Context ctx() { return ctx; }
    public String pkg() { return pkg; }

    private PackageManager pm() {
        Context c = ctx;
        if (c == null) { c = acquireContext(); }
        if (c == null) { return null; }
        try { return c.getPackageManager(); } catch (Exception e) { return null; }
    }

    public boolean isInstalled() {
        PackageManager p = pm();
        if (p == null) { return false; }
        try { p.getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** /data/data/<pkg> */
    public File dataDir() { return new File("/data/data", pkg); }

    public File snapshotDir() { return new File(stateDir, "snapshot"); }
    public File snapshotFile() { return new File(snapshotDir(), "data.tar"); }
    public File snapshotTsFile() { return new File(snapshotDir(), "data.ts"); }
    public File oldSnapshotFile() { return new File(snapshotDir(), "data.old.tar"); }
    public File stampFile() { return new File(stateDir, "install.stamp"); }
    public File unlockedFile() { return new File(stateDir, "unlocked"); }
    public File recheckFile() { return new File(stateDir, "recheck"); }
    public File needRestoreFile() { return new File(stateDir, "need_restore"); }
    public File lockedFile() { return new File(stateDir, "locked"); }
    public File logFile() { return new File(stateDir, "lock.log"); }
    public File sentinelFile() { return new File(dataDir(), SENTINEL); }

    public boolean isUnlocked() { return unlockedFile().isFile(); }
    public void setUnlocked(boolean v) {
        stateDir.mkdirs();
        if (v) { try { unlockedFile().createNewFile(); } catch (IOException ignored) {} }
        else { unlockedFile().delete(); }
    }

    public boolean readLocked() {
        if (!lockedFile().isFile()) { return true; }
        String s = readFile(lockedFile());
        return !"0".equals(s == null ? "" : s.trim());
    }
    public void writeLocked(boolean v) {
        stateDir.mkdirs();
        try { writeFile(lockedFile(), v ? "1" : "0"); } catch (Exception ignored) {}
    }

    public void log(String msg) {
        try {
            stateDir.mkdirs();
            FileWriter fw = new FileWriter(logFile(), true);
            fw.write(timestamp() + " " + msg + "\n");
            fw.close();
        } catch (IOException ignored) {}
        System.out.println(msg);
    }

    /** archives /data/data/<pkg> into the snapshot file */
    public void snapshot() { snapMake(true); }

    public boolean snapMake(boolean force) {
        File d = dataDir();
        if (d == null || !d.isDirectory()) { return true; }
        stateDir.mkdirs();
        snapshotDir().mkdirs();
        File snap = snapshotFile();
        File snapTs = snapshotTsFile();

        // never overwrite a good snapshot with a wiped/empty data dir
        if (snap.isFile() && snap.length() > 0 && !sentinelFile().exists()) {
            log("snapshot: skipped (data looks wiped - restore pending)");
            return true;
        }
        if (!force && snap.isFile() && snap.length() > 0 && snapTs.isFile()) {
            long ts = parseLong(readFile(snapTs));
            long now = now();
            if (ts > 0 && (now - ts) < snapMax) { return true; }
        }
        // app running check
        if (appRunning()) {
            if (force) { log("snapshot: skipped (app is running)"); }
            return true;
        }
        // low space
        long freeKb = freeSpaceKb("/data");
        if (freeKb > 0 && freeKb < 5242880L) {
            log("snapshot: skipped (low space)");
            return false;
        }
        // min size wait
        long sz = dirSizeMb(d);
        if (!force && sz >= 0 && sz < minMb) {
            if (!waitLogged) {
                log("snapshot: waiting for app data (" + sz + "MB < " + minMb + "MB)");
                waitLogged = true;
            }
            return true;
        }
        waitLogged = false;

        log("snapshot: creating (game is idle)...");
        File tmp = new File(snapshotDir(), "data.tar.tmp");
        tmp.delete();
        int rc = exec("tar", "-cf", tmp.getAbsolutePath(), "-C", "/data/data", pkg);
        if (rc == 0 && tmp.isFile() && tmp.length() > 0) {
            snap.delete();
            if (!tmp.renameTo(snap)) {
                // fallback copy
                try { copyFile(tmp, snap); } catch (IOException ignored) {}
                tmp.delete();
            }
            try { writeFile(snapTs, String.valueOf(now())); } catch (Exception ignored) {}
            touchSentinel();
            log("snapshot: ready (" + (snap.length() / (1024 * 1024)) + "MB)");
            return true;
        } else {
            tmp.delete();
            log("snapshot: FAILED");
            return false;
        }
    }

    /** restores /data/data/<pkg> from the snapshot file */
    public void restore() { restore(snapshotFile()); }

    public void restore(File snap) {
        if (snap == null || !snap.isFile() || snap.length() == 0) {
            log("restore: no snapshot available");
            return;
        }
        restoreData();
    }

    public boolean restoreData() {
        File snap = snapshotFile();
        needRestoreFile().delete();
        if (!snap.isFile() || snap.length() == 0) {
            log("restore: no snapshot available");
            return false;
        }
        log("restore: begin (" + (snap.length() / (1024 * 1024)) + "MB)");
        forceStopApp(pkg);
        sleep(1000);
        deleteRecursive(dataDir());
        int rc = exec("tar", "-xpf", snap.getAbsolutePath(), "-C", "/data/data");
        int uid = appUid();
        if (uid > 0) {
            exec("chown", "-R", uid + ":" + uid, dataDir().getAbsolutePath());
        }
        if (commandExists("restorecon")) {
            exec("restorecon", "-R", dataDir().getAbsolutePath());
        }
        touchSentinel();
        log("restore: done - data returned from snapshot");
        return rc == 0;
    }

    /**
     * one protection cycle: snapshot the app data and restore it when it got
     * wiped while the app was not running.
     */
    public void cycle() {
        if (!isInstalled()) { return; }
        if (snapshotFile().isFile() && snapshotFile().length() > 0) {
            File d = dataDir();
            boolean empty = d == null || !d.isDirectory()
                    || (d.listFiles() != null && d.listFiles().length == 0);
            if (empty) { restoreData(); }
        } else {
            snapMake(false);
        }
    }

    /**
     * asks the platform to block (or allow) the uninstall of the protected
     * package.
     */
    public boolean setUninstallBlocked(boolean block) {
        PackageManager p = pm();
        if (p == null) { return false; }
        try {
            Method m = p.getClass().getMethod(
                    "setBlockUninstallForUser",
                    String.class, int.class, boolean.class);
            m.invoke(p, pkg, 0, block);
            return true;
        } catch (Exception e) {
            // try alternate signature (boolean without userId)
            try {
                Method m = p.getClass().getMethod(
                        "setBlockUninstallForUser",
                        String.class, boolean.class);
                m.invoke(p, pkg, block);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    // ---- reinstall handling ----
    public boolean checkReinstall() {
        String cur = installStamp();
        if (cur == null || cur.isEmpty()) { return false; }
        String old = readFile(stampFile());
        if (old == null || old.trim().isEmpty()) {
            try { writeFile(stampFile(), cur); } catch (Exception ignored) {}
            return false;
        }
        if (cur.trim().equals(old.trim())) { return false; }

        log("NEW INSTALL detected (old: " + old.trim() + " | new: " + cur.trim() + ")");
        File snap = snapshotFile();
        if (snap.isFile() && snap.length() > 0) {
            File olds = oldSnapshotFile();
            olds.delete();
            snap.renameTo(olds);
            snapshotTsFile().delete();
            log("snapshot: previous install archived as data.old.tar");
        }
        // remove sentinel
        sentinelFile().delete();
        needRestoreFile().delete();
        try { writeFile(stampFile(), cur); } catch (Exception ignored) {}
        if (unlockedFile().isFile()) {
            unlockedFile().delete();
            log("unlock flag cleared - the new install is protected");
        }
        return true;
    }

    public String installStamp() {
        PackageManager p = pm();
        if (p == null) { return null; }
        try {
            PackageInfo pi = p.getPackageInfo(pkg, 0);
            return String.valueOf(pi.firstInstallTime);
        } catch (Exception e) {
            return null;
        }
    }

    public int appUid() {
        PackageManager p = pm();
        if (p == null) { return -1; }
        try {
            ApplicationInfo ai = p.getApplicationInfo(pkg, 0);
            return ai.uid;
        } catch (Exception e) {
            // fallback stat
            try {
                File d = dataDir();
                // no stat uid easily; return -1
            } catch (Exception ignored) {}
            return -1;
        }
    }

    public boolean appRunning() {
        // try ActivityManager
        Context c = ctx;
        if (c == null) { c = acquireContext(); }
        if (c != null) {
            try {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                    if (procs != null) {
                        for (ActivityManager.RunningAppProcessInfo ri : procs) {
                            if (ri == null) { continue; }
                            if (pkg.equals(ri.processName)) { return true; }
                            if (ri.pkgList != null) {
                                for (String pn : ri.pkgList) {
                                    if (pkg.equals(pn)) { return true; }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        // fallback pidof
        try {
            Process p = new ProcessBuilder("pidof", pkg).start();
            BufferedReader br = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null && !line.trim().isEmpty();
        } catch (Exception ignored) {}
        return false;
    }

    public boolean sentinelMissing() { return !sentinelFile().exists(); }

    public void touchSentinel() {
        File d = dataDir();
        if (d == null || !d.isDirectory()) { return; }
        File s = sentinelFile();
        try { s.createNewFile(); } catch (IOException ignored) { return; }
        int uid = appUid();
        if (uid > 0) {
            exec("chown", uid + ":" + uid, s.getAbsolutePath());
        }
        try { Os.chmod(s.getAbsolutePath(), 0600); } catch (Exception ignored) {}
        if (commandExists("restorecon")) {
            exec("restorecon", s.getAbsolutePath());
        }
    }

    // ---- helpers ----
    private void forceStopApp(String p) {
        Context c = ctx;
        if (c == null) { c = acquireContext(); }
        if (c != null) {
            try {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) { am.killBackgroundProcesses(p); }
            } catch (Exception ignored) {}
        }
        exec("am", "force-stop", p);
    }

    public static int exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            while (is.read(buf) >= 0) { /* drain */ }
            is.close();
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean commandExists(String name) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v " + name).start();
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[256];
            int n = is.read(buf);
            is.close();
            p.waitFor();
            return n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
    }

    private static long now() { return System.currentTimeMillis() / 1000L; }

    private static long parseLong(String s) {
        if (s == null) { return 0; }
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    public static String readFile(File f) {
        if (f == null || !f.isFile()) { return null; }
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) { sb.append("\n"); }
                sb.append(line);
            }
            br.close();
        } catch (IOException e) { return null; }
        return sb.toString();
    }

    public static void writeFile(File f, String content) throws IOException {
        if (f.getParentFile() != null) { f.getParentFile().mkdirs(); }
        FileWriter fw = new FileWriter(f);
        fw.write(content);
        fw.close();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) { return; }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) { for (File k : kids) { deleteRecursive(k); } }
        }
        f.delete();
    }

    private static void copyFile(File from, File to) throws IOException {
        if (from == null || !from.exists()) { return; }
        if (to.getParentFile() != null) { to.getParentFile().mkdirs(); }
        java.io.FileInputStream in = new java.io.FileInputStream(from);
        java.io.FileOutputStream out = new java.io.FileOutputStream(to);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
        in.close();
        out.close();
    }

    private long freeSpaceKb(String path) {
        try {
            StatFs st = new StatFs(path);
            return st.getAvailableBlocksLong() * st.getBlockSizeLong() / 1024L;
        } catch (Exception e) {
            return -1;
        }
    }

    private long dirSizeMb(File d) {
        try {
            Process p = new ProcessBuilder("du", "-sm", d.getAbsolutePath()).start();
            BufferedReader br = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            br.close();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0) { return Long.parseLong(parts[0]); }
            }
        } catch (Exception ignored) {}
        return -1;
    }
}

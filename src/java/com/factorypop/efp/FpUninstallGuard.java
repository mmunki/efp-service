package com.factorypop.efp;

import android.content.Context;
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
    public final File forensicDir;
    public final File dataRoot;
    public final long snapMax;
    public final long minMb;

    private FpForensics forensics;
    private boolean waitLogged = false;
    private boolean partialRestore = true;

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

        String fd = System.getenv("FACTORYPOP_FORENSIC_DIR");
        if (fd == null || fd.trim().isEmpty()) { fd = Forensic.DEFAULT_DIR; }
        this.forensicDir = new File(fd.trim());

        String pr = System.getenv("FACTORYPOP_PARTIAL_RESTORE");
        if (pr != null) { this.partialRestore = !"0".equals(pr.trim()); }

        String dr = System.getenv("FACTORYPOP_DATA_DIR");
        this.dataRoot = new File((dr == null || dr.trim().isEmpty()) ? "/data/data" : dr.trim());
    }

    /** forensic log under /data/adb/jp.konami.pes/uninstall.log */
    public FpForensics forensics() {
        if (forensics == null) { forensics = new FpForensics(this); }
        return forensics;
    }

    public File uninstallLogFile() { return new File(forensicDir, Forensic.LOG_NAME); }

    public File manifestFile() { return new File(snapshotDir(), Forensic.MANIFEST_NAME); }

    public boolean partialRestoreEnabled() { return partialRestore; }

    /** Loads the manifest written together with the last snapshot, if any. */
    public Forensic.Manifest readManifest() {
        File f = manifestFile();
        if (!f.isFile()) { return null; }
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) { sb.append(line).append('\n'); }
            br.close();
            return Forensic.Manifest.parse(sb.toString());
        } catch (IOException e) {
            return null;
        }
    }

    public void writeManifest(Forensic.Manifest m) {
        if (m == null) { return; }
        try {
            Forensic.writeText(manifestFile(), m.toTsv());
            forensics().manifestSaved(manifestFile(), m.files, m.totalBytes);
        } catch (IOException e) {
            forensics().warn("BACKUP", "manifest write failed: " + e);
        }
    }

    public static Context currentContext() { return appContext; }
    public static void attachContext(Context c) { appContext = c; }

    /**
     * A usable context when none was attached.
     *
     * In an app_process the guard has no Android application context at all;
     * that is fine, {@link Platform} talks to the system services directly.
     * Only an already existing ActivityThread is reused, the guard never
     * attaches itself to the system as a fake system process.
     */
    public static Context acquireContext() {
        if (appContext != null) { return appContext; }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = null;
            try { thread = at.getMethod("currentActivityThread").invoke(null); } catch (Exception ignored) {}
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
        // IPackageManager first, the same service pm queries - no /system/bin/pm
        return Platform.packageInstalled(pkg, 0);
    }

    /** /data/data/<pkg> */
    public File dataDir() { return new File(dataRoot, pkg); }

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
        try { forensics().mirror(msg); } catch (Throwable ignored) {}
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
        long started = System.currentTimeMillis();
        String trigger = force ? "forced" : "auto";

        // never overwrite a good snapshot with a wiped/empty data dir
        if (snap.isFile() && snap.length() > 0 && !sentinelFile().exists()) {
            log("snapshot: skipped (data looks wiped - restore pending)");
            forensics().backupSkipped("data looks wiped - restore pending");
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
            if (force) { forensics().backupSkipped("app is running"); }
            return true;
        }
        // low space
        long freeKb = freeSpaceKb("/data");
        if (freeKb > 0 && freeKb < 5242880L) {
            log("snapshot: skipped (low space)");
            forensics().backupSkipped("low space: " + freeKb + "KB free, need 5120MB");
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
        forensics().backupStart(trigger, d, freeKb);
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
            String sha = Forensic.sha256(snap);
            forensics().backupDone(snap, snap.length(),
                    System.currentTimeMillis() - started, sha);
            writeManifest(Forensic.Manifest.scan(d));
            return true;
        } else {
            tmp.delete();
            log("snapshot: FAILED");
            forensics().backupFailed("tar rc=" + rc
                    + " tmp=" + (tmp.isFile() ? tmp.length() : 0) + "bytes");
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

    public boolean restoreData() { return restoreData("requested"); }

    public boolean restoreData(String reason) {
        File snap = snapshotFile();
        needRestoreFile().delete();
        if (!snap.isFile() || snap.length() == 0) {
            log("restore: no snapshot available");
            forensics().restoreStart(reason + " (no snapshot)", snap);
            return false;
        }
        long started = System.currentTimeMillis();
        log("restore: begin (" + (snap.length() / (1024 * 1024)) + "MB)");
        forensics().restoreStart(reason, snap);
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
        forensics().restoreDone(rc == 0, System.currentTimeMillis() - started);
        writeManifest(Forensic.Manifest.scan(dataDir()));
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
        // PackageManagerService.setBlockUninstallForUser over IPackageManager:
        // the call that used to need `cmd package` in /system/bin.
        if (Platform.setBlockUninstall(pkg, block, 0)) { return true; }

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
            File mf = manifestFile();
            if (mf.isFile()) {
                File oldMf = new File(snapshotDir(), "manifest.old.tsv");
                oldMf.delete();
                mf.renameTo(oldMf);
            }
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
        long t = Platform.firstInstallTime(pkg, 0);
        return t > 0 ? String.valueOf(t) : null;
    }

    public int appUid() {
        return Platform.appUid(pkg, 0);
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
        // /proc scan, no pidof binary needed
        return Platform.pidOf(pkg) > 0;
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
        // IActivityManager.forceStopPackage is what `am force-stop` calls - and
        // `am` is only a shell wrapper around /system/bin/cmd.  Talk to the
        // service directly so the wipe restore needs no command in bin/.
        if (Platform.forceStop(p, 0)) { return; }
        Context c = ctx;
        if (c == null) { c = acquireContext(); }
        if (c != null) {
            try {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) { am.killBackgroundProcesses(p); }
            } catch (Exception ignored) {}
        }
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
        // look at the standard bin directories instead of running a shell
        String[] dirs = {
                "/system/bin", "/system/xbin", "/system_ext/bin", "/vendor/bin",
                "/product/bin", "/odm/bin", "/apex/com.android.runtime/bin",
                "/apex/com.android.toybox/bin", "/data/adb/ksu/bin", "/data/adb/magisk"
        };
        for (String d : dirs) {
            File f = new File(d, name);
            if (f.isFile()) { return true; }
        }
        return false;
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
        // walk the tree in Java; `du` is not needed (and nothing in bin/ is)
        if (d == null || !d.isDirectory()) { return -1; }
        long total = 0;
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<File>();
        stack.push(d);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 200000) {
            File f = stack.pop();
            File[] kids = f.listFiles();
            if (kids == null) { continue; }
            for (File k : kids) {
                try {
                    if (k.isDirectory()) {
                        stack.push(k);
                    } else {
                        total += k.length();
                    }
                } catch (Throwable ignored) {}
            }
        }
        return total / (1024L * 1024L);
    }
}

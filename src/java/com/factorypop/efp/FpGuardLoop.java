package com.factorypop.efp;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * FactoryPop :: protection loop.
 *
 * This is the daemon of <code>fp-test-guard.sh</code> written in Java.  The
 * shell script used to run the loop itself; now the same loop lives inside the
 * services jar and is driven by {@link FpUninstallGuardService} when the system
 * starts the service, or by {@code com.factorypop.efp.tool.GuardDaemon} when the
 * boot script starts the jar with app_process.
 *
 * The loop needs no command in /system/bin: the lock is set through
 * IPackageManager, the app is stopped through IActivityManager, the package
 * state comes from the platform, and the only binaries it runs are the toybox
 * helpers for tar/ownership/SELinux labels (tar, chown, restorecon) plus
 * logcat for the forensic log.
 *
 * Layer 3 is the forensic layer: every backup and every deletion event is
 * written to /data/adb/jp.konami.pes/uninstall.log together with the logcat
 * buffer/line, the /proc caller and the instructions for stopping it.
 */
public class FpGuardLoop {

    private static final long POLL_MS = 15000L;
    private static final long REAPPLY_S = 300L;
    private static final long HEARTBEAT_MS = 20L * 60L * 1000L;

    private final FpUninstallGuard guard;
    private volatile boolean running = true;

    private Thread thread;
    private Thread fastThread;
    private Process watcherMain;
    private Process watcherEvents;
    private Thread threadMain;
    private Thread threadEvents;

    private int applied = 0;
    private long lastApplied = 0L;
    private boolean was = false;

    private volatile Forensic.Manifest manifest;
    private Forensic.Manifest startupBefore;
    private Forensic.Diff startupDiff;
    private boolean startupChecked = false;
    private long lastHeartbeat = 0L;
    private long lastUninstallCapture = 0L;
    private volatile long lastCaptureAt = 0L;
    private volatile boolean restoring = false;

    public FpGuardLoop(FpUninstallGuard guard) {
        this.guard = guard;
    }

    public FpUninstallGuard guard() { return guard; }
    public boolean isRunning() { return running; }

    /** starts the loop on its own thread */
    public void start() {
        if (thread != null) { return; }
        thread = new Thread(new Runnable() {
            @Override
            public void run() { loop(); }
        }, "FpGuardLoop");
        thread.setDaemon(false);
        thread.start();
    }

    /** stops the loop and the log watchers */
    public void stop() {
        running = false;
        if (thread != null) { thread.interrupt(); thread = null; }
        if (fastThread != null) { fastThread.interrupt(); fastThread = null; }
        stopWatchers();
    }

    /** one protection cycle, used by the service and by --once */
    public void tick() {
        try {
            doTick();
        } catch (Throwable t) {
            guard.log("tick error: " + t);
        }
    }

    private void loop() {
        guard.forensics().sessionStart();
        guard.log("protection daemon: armed for " + guard.pkg());
        manifest = baseline();
        startWatchers();
        startFastWatchdog();
        while (running) {
            tick();
            if (!running) { break; }
            sleep(POLL_MS);
        }
        guard.log("protection daemon: stopped");
        stopWatchers();
    }

    private Forensic.Manifest baseline() {
        Forensic.Manifest stored = guard.readManifest();
        Forensic.Manifest current = Forensic.Manifest.scan(guard.dataDir());
        if (stored != null) {
            Forensic.Diff d = Forensic.diff(stored, current);
            guard.forensics().event("MANIFEST", "loaded " + stored.files + " files / "
                    + stored.totalBytes + " bytes from " + guard.manifestFile().getAbsolutePath()
                    + "; current=" + current.files + " files / " + current.totalBytes
                    + " bytes; startup_removed=" + d.removedPersistent);
            if (d.removedPersistent > 0) {
                guard.forensics().change(
                        "startup difference against the last snapshot (baseline only)",
                        d, false);
            }
            startupBefore = stored;
            startupDiff = d;
        } else {
            guard.forensics().event("MANIFEST", "initial scan " + current.files + " files / "
                    + current.totalBytes + " bytes");
        }
        return current;
    }

    private void doTick() {
        if (watcherMain == null || !watcherMain.isAlive()
                || watcherEvents == null || !watcherEvents.isAlive()) {
            startWatchers();
        }

        // an unlock is consumed by an uninstall: the next install is protected
        if (guard.isUnlocked() && !guard.isInstalled()) {
            guard.setUnlocked(false);
            guard.log("unlock consumed by uninstall - next install will be protected");
        }

        if (guard.isUnlocked()) {
            if (applied == 1) { guard.log("standby: UNLOCKED flag present"); }
            applied = 0;
            heartbeat("standby-unlocked");
            return;
        }

        if (guard.isInstalled()) {
            // ---- reinstall handling (before anything else) ----
            guard.checkReinstall();

            // ---- layer 1: uninstall lock ----
            long now = System.currentTimeMillis() / 1000L;
            File recheck = guard.recheckFile();
            if (applied != 1 || (now - lastApplied) >= REAPPLY_S || recheck.isFile()) {
                recheck.delete();
                boolean blocked = guard.setUninstallBlocked(true);
                if (blocked) {
                    if (applied != 1) { guard.log("LOCKED: " + guard.pkg() + " cannot be uninstalled"); }
                    applied = 1;
                } else {
                    guard.log("ERROR: block-uninstall failed");
                    applied = 0;
                }
                lastApplied = now;
            }

            // ---- layer 2 + 3: manifest diff, backup, wipe detection ----
            File data = guard.dataDir();
            Forensic.Manifest current = Forensic.Manifest.scan(data);
            if (manifest == null) { manifest = baseline(); }

            boolean snapshotReady = guard.snapshotFile().isFile()
                    && guard.snapshotFile().length() > 0;
            boolean wiped = guard.needRestoreFile().isFile();
            if (snapshotReady) {
                if (guard.sentinelMissing()) {
                    sleep(3000);
                    if (guard.sentinelMissing()) { wiped = true; }
                }
            }

            Forensic.Diff diff = Forensic.diff(manifest, current);
            boolean partial = !wiped && snapshotReady
                    && guard.partialRestoreEnabled()
                    && !guard.appRunning()
                    && diff.significant();
            boolean startupLarge = !wiped && !partial && !startupChecked && snapshotReady
                    && guard.partialRestoreEnabled()
                    && !guard.appRunning()
                    && startupDiff != null
                    && (startupDiff.removedPersistent >= 20
                        || (startupDiff.beforeBytes > 0
                            && startupDiff.removedPersistentBytes * 100L
                                    / startupDiff.beforeBytes >= 50L));
            boolean deletedNoSnapshot = !wiped && !snapshotReady
                    && !guard.appRunning() && diff.significant();

            if (wiped || partial || startupLarge || deletedNoSnapshot) {
                String reason = wiped
                        ? "sentinel missing or need_restore flag"
                        : partial
                            ? "persistent data removed while the game was stopped"
                            : startupLarge
                                ? "large removal since the last snapshot (startup check)"
                                : "persistent data removed before the first snapshot";
                Forensic.Manifest reportBefore = startupLarge && startupBefore != null
                        ? startupBefore : manifest;
                guard.log("ALERT: data wipe detected (" + reason + ")");
                restoring = true;
                try {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastCaptureAt > 15000L) {
                        lastCaptureAt = nowMs;
                        guard.forensics().capture(reason, guard.forensics().lastCaller(),
                                reportBefore, current, wiped, guard.snapshotFile(),
                                "removed=" + diff.removed.size()
                                        + " persistent=" + diff.removedPersistent
                                        + " bytes=" + diff.removedPersistentBytes);
                    }
                    if (snapshotReady) {
                        guard.restoreData(reason);
                    } else {
                        guard.log("restore: unavailable (no snapshot was taken yet)");
                        guard.forensics().restoreStart(reason + " (no snapshot)",
                                guard.snapshotFile());
                    }
                } finally {
                    restoring = false;
                }
                manifest = Forensic.Manifest.scan(data);
            } else {
                if (diff.removedPersistent > 0) {
                    guard.forensics().change("data changed while the game was "
                            + (guard.appRunning() ? "running" : "stopped"), diff, false);
                }
                guard.snapMake(false);
                manifest = current;
            }

            heartbeat("running");
            was = true;
            startupChecked = true;
            startupDiff = null;
            startupBefore = null;
        } else {
            if (was) {
                guard.log("NOTE: " + guard.pkg() + " is not installed");
                guard.forensics().event("STATE", "package removed - lock no longer applies");
                was = false;
            }
            applied = 0;
            heartbeat("not-installed");
        }
    }

    private void heartbeat(String state) {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat < HEARTBEAT_MS) { return; }
        lastHeartbeat = now;
        guard.forensics().heartbeat(state);
    }

    /**
     * Cheap 2 second watchdog for the sentinel.  The full manifest diff runs on
     * the 15 second loop; this thread only checks one file, so a root deletion
     * is noticed while the deleting process is still alive and can be captured
     * in /proc.  Without it a short "rm -rf" can finish before the next tick.
     */
    private void startFastWatchdog() {
        if (fastThread != null) { return; }
        fastThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    sleep(2000L);
                    if (!running) { break; }
                    try {
                        if (restoring || guard.isUnlocked()) { continue; }
                        if (!guard.isInstalled()) { continue; }
                        File snap = guard.snapshotFile();
                        if (!snap.isFile() || snap.length() == 0) { continue; }
                        if (!guard.sentinelMissing()) { continue; }
                        long now = System.currentTimeMillis();
                        if (now - lastCaptureAt < 30000L) { continue; }
                        lastCaptureAt = now;
                        guard.forensics().capture("sentinel missing (2s watchdog)",
                                guard.forensics().lastCaller(), manifest,
                                Forensic.Manifest.scan(guard.dataDir()), true, snap,
                                "detected within 2s, process table is close to the delete");
                        try { new File(guard.stateDir, "need_restore").createNewFile(); }
                        catch (Throwable ignored) {}
                    } catch (Throwable t) {
                        guard.log("watchdog error: " + t);
                    }
                }
            }
        }, "FpFastWatchdog");
        fastThread.setDaemon(true);
        fastThread.start();
    }

    // ------------------------------------------------------------------
    // logcat watchers
    // ------------------------------------------------------------------

    /** starts both watchers; the events buffer carries uid/pid rich am_* lines */
    private void startWatchers() {
        if (watcherEvents == null || !watcherEvents.isAlive()) {
            watcherEvents = startWatcher("events", new String[] {
                    "logcat", "-b", "events", "-v", "threadtime", "-T", "1"
            });
        }
        if (watcherMain == null || !watcherMain.isAlive()) {
            watcherMain = startWatcher("main", new String[] {
                    "logcat", "-b", "all", "-v", "threadtime", "-T", "1",
                    "-s", "pm_critical_info", "PackageManager", "ActivityManager",
                    "installer_clear_app_data_caller", "installd", "auditd"
            });
        }
    }

    private Process startWatcher(final String source, String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            final Process proc = pb.start();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader br = null;
                    try {
                        br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (!running) { break; }
                            watcherLine(source, line);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
                    }
                }
            }, "FpLogWatcher-" + source);
            t.setDaemon(true);
            t.start();
            if ("events".equals(source)) { threadEvents = t; } else { threadMain = t; }
            return proc;
        } catch (Throwable t) {
            guard.log("log watcher " + source + " not available: " + t);
            return null;
        }
    }

    /**
     * One line from logcat.  Every line that names the package is classified
     * and written to uninstall.log with its buffer, tag, uid and pid; the
     * destructive actions also drive the protection (recheck / restore).
     */
    private void watcherLine(String source, String line) {
        if (line == null) { return; }
        final String p = guard.pkg();
        boolean mentions = line.contains(p);
        if (!mentions && !line.contains("Uninstall pkg")) { return; }

        Forensic.Caller caller = Forensic.parseLine(source, line);
        if (caller != null && (caller.uid >= 0 || caller.pid >= 0
                || "events".equals(source) || isPlatformTag(caller.tag)
                || line.contains("Uninstall pkg")
                || line.contains("clearApplicationUserData"))) {
            guard.forensics().recordCaller(caller);
        }

        if (line.contains("Uninstall pkg") && mentions) {
            guard.log("ATTEMPT uninstall: [" + source + "] " + line);
            sleep(2000);
            if (guard.isInstalled()) {
                guard.log("RESULT: blocked - " + p + " is still installed");
            } else {
                guard.log("RESULT: REMOVED despite the lock!");
                try { new File(guard.stateDir, "recheck").createNewFile(); } catch (Throwable ignored) {}
                long now = System.currentTimeMillis();
                if (now - lastUninstallCapture > 5000L) {
                    lastUninstallCapture = now;
                    guard.forensics().capture("uninstall completed despite the lock",
                            caller, manifest, Forensic.Manifest.scan(guard.dataDir()),
                            true, guard.snapshotFile(),
                            "the package is gone; restore is not automatic after an uninstall");
                }
            }
        } else if (line.contains("clearApplicationUserData") && mentions) {
            guard.log("ATTEMPT clear-data: [" + source + "] " + line);
            try { new File(guard.stateDir, "need_restore").createNewFile(); } catch (Throwable ignored) {}
        } else if (line.contains("Deleting package") && mentions) {
            guard.log("DELETE PACKAGE: [" + source + "] " + line);
        } else if (line.contains("[2003,") && mentions) {
            guard.log("CLEAR CALLER: [" + source + "] " + line);
        }
    }

    private static boolean isPlatformTag(String tag) {
        if (tag == null) { return false; }
        return "PackageManager".equals(tag) || "ActivityManager".equals(tag)
                || "installd".equals(tag) || "auditd".equals(tag)
                || "pm_critical_info".equals(tag)
                || "installer_clear_app_data_caller".equals(tag)
                || tag.startsWith("am_");
    }

    private void stopWatchers() {
        if (watcherMain != null) { try { watcherMain.destroy(); } catch (Throwable ignored) {} watcherMain = null; }
        if (watcherEvents != null) { try { watcherEvents.destroy(); } catch (Throwable ignored) {} watcherEvents = null; }
        if (threadMain != null) { threadMain.interrupt(); threadMain = null; }
        if (threadEvents != null) { threadEvents.interrupt(); threadEvents = null; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}

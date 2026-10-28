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
 * helpers for tar/ownership/SELinux labels (tar, chown, restorecon).
 */
public class FpGuardLoop {

    private static final long POLL_MS = 15000L;
    private static final long REAPPLY_S = 300L;

    private final FpUninstallGuard guard;
    private volatile boolean running = true;

    private Thread thread;
    private Thread watcherThread;
    private Process watcher;

    private int applied = 0;
    private long lastApplied = 0L;
    private boolean was = false;

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

    /** stops the loop and the log watcher */
    public void stop() {
        running = false;
        if (thread != null) { thread.interrupt(); thread = null; }
        if (watcher != null) {
            try { watcher.destroy(); } catch (Throwable ignored) {}
            watcher = null;
        }
        if (watcherThread != null) { watcherThread.interrupt(); watcherThread = null; }
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
        guard.log("protection daemon: armed for " + guard.pkg());
        startWatcher();
        while (running) {
            tick();
            if (!running) { break; }
            sleep(POLL_MS);
        }
        guard.log("protection daemon: stopped");
    }

    private void doTick() {
        if (watcher == null || !watcher.isAlive()) { startWatcher(); }

        // an unlock is consumed by an uninstall: the next install is protected
        if (guard.isUnlocked() && !guard.isInstalled()) {
            guard.setUnlocked(false);
            guard.log("unlock consumed by uninstall - next install will be protected");
        }

        if (guard.isUnlocked()) {
            if (applied == 1) { guard.log("standby: UNLOCKED flag present"); }
            applied = 0;
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

            // ---- layer 3: wipe detection & restore (before snapshot) ----
            boolean wiped = false;
            if (guard.snapshotFile().isFile() && guard.snapshotFile().length() > 0
                    && guard.sentinelMissing()) {
                sleep(3000);
                if (guard.sentinelMissing()) { wiped = true; }
            }
            if (guard.needRestoreFile().isFile()) { wiped = true; }

            if (wiped) {
                guard.log("ALERT: data wipe detected");
                guard.restoreData();
            } else {
                guard.snapMake(false);
            }
            was = true;
        } else {
            if (was) {
                guard.log("NOTE: " + guard.pkg() + " is not installed");
                was = false;
            }
            applied = 0;
        }
    }

    /**
     * logcat watcher: records uninstall / clear-data attempts and asks for a
     * recheck when an uninstall went through despite the lock.
     */
    private void startWatcher() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "logcat", "-b", "all", "-v", "time", "-T", "1",
                    "-s", "pm_critical_info", "PackageManager", "ActivityManager",
                    "installer_clear_app_data_caller");
            pb.redirectErrorStream(true);
            watcher = pb.start();
            final BufferedReader br = new BufferedReader(new InputStreamReader(watcher.getInputStream()));
            final FpUninstallGuard g = guard;
            final String p = guard.pkg();
            final File state = guard.stateDir;
            watcherThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    String line;
                    try {
                        while ((line = br.readLine()) != null) {
                            if (line.contains("Uninstall pkg: " + p)) {
                                g.log("ATTEMPT uninstall: " + line);
                                sleep(2000);
                                if (g.isInstalled()) {
                                    g.log("RESULT: blocked - " + p + " is still installed");
                                } else {
                                    g.log("RESULT: REMOVED despite the lock!");
                                    try { new File(state, "recheck").createNewFile(); } catch (Throwable ignored) {}
                                }
                            } else if (line.contains("clearApplicationUserData") && line.contains(p)) {
                                g.log("ATTEMPT clear-data: " + line);
                            } else if (line.contains("[2003,") && line.contains(p)) {
                                g.log("CLEAR CALLER: " + line);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "FpLogWatcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
        } catch (Throwable t) {
            guard.log("log watcher not available: " + t);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}

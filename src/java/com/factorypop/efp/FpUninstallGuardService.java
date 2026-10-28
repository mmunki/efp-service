package com.factorypop.efp;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.BufferedReader;
import java.io.File;
import java.io.File;
import java.io.InputStreamReader;

/**
 * FactoryPop :: eFootball protection service.
 *
 * Runs the protection loop of fp-test-guard.sh as a real Android service that
 * lives in services.jar.  The system starts it and it keeps enforcing the
 * uninstall lock + data snapshot without any command in /system/bin.
 */
public class FpUninstallGuardService extends Service {

    public static final String ACTION = "fp.guard.START";

    private static final long POLL_MS = 15000L;
    private static final long REAPPLY_S = 300L;

    private FpUninstallGuard guard;
    private Handler handler;
    private HandlerThread thread;
    private volatile boolean running = true;

    private int applied = 0;
    private long lastApplied = 0L;
    private boolean was = false;

    private Process watcher;

    @Override
    public void onCreate() {
        super.onCreate();
        FpUninstallGuard.attachContext(getApplicationContext());

        String pkg = System.getenv("FACTORYPOP_PKG");
        if (pkg == null || pkg.trim().isEmpty()) {
            try {
                java.io.File pf = new java.io.File("/data/adb/factorypop/protected_pkg");
                if (pf.isFile()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(pf));
                    String l = br.readLine();
                    br.close();
                    if (l != null && !l.trim().isEmpty()) { pkg = l.trim(); }
                }
            } catch (Exception ignored) {}
        }
        if (pkg == null || pkg.trim().isEmpty()) { pkg = FpUninstallGuard.DEFAULT_PKG; }

        guard = new FpUninstallGuard(getApplicationContext(), pkg);
        guard.stateDir.mkdirs();

        thread = new HandlerThread("FpUninstallGuard");
        thread.start();
        handler = new Handler(thread.getLooper());

        // wait for boot to finish, then arm
        handler.postDelayed(new Runnable() {
            @Override
            public void run() { arm(); }
        }, 8000L);
    }

    private void arm() {
        if (!running) { return; }
        guard.log("protection daemon: armed for " + guard.pkg());
        tick();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION.equals(intent.getAction())) {
            if (handler != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() { tick(); }
                });
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) { handler.removeCallbacksAndMessages(null); }
        if (watcher != null) { try { watcher.destroy(); } catch (Exception ignored) {} }
        if (thread != null) { thread.quit(); }
        super.onDestroy();
    }

    private void tick() {
        if (!running) { return; }
        try { doTick(); } catch (Throwable t) {
            guard.log("tick error: " + t);
        }
        if (running && handler != null) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { tick(); }
            }, POLL_MS);
        }
    }

    private void doTick() {
        // restart log watcher if needed
        if (watcher == null || !watcher.isAlive()) {
            startWatcher();
        }

        // unlock is consumed by an uninstall
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
            guard.checkReinstall();

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

            // wipe detection
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
            new Thread(new Runnable() {
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
                                    try { new File(state, "recheck").createNewFile(); } catch (Exception ignored) {}
                                }
                            } else if (line.contains("clearApplicationUserData") && line.contains(p)) {
                                g.log("ATTEMPT clear-data: " + line);
                            } else if (line.contains("[2003,") && line.contains(p)) {
                                g.log("CLEAR CALLER: " + line);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }, "FpLogWatcher").start();
        } catch (Throwable t) {
            guard.log("log watcher not available: " + t);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}

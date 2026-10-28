package com.factorypop.efp;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * FactoryPop :: eFootball protection service.
 *
 * Runs the protection loop of {@link FpGuardLoop} (the daemon of
 * fp-test-guard.sh) as a real Android service that lives in services.jar.  The
 * system starts it and it keeps enforcing the uninstall lock and the data
 * snapshot without any command in /system/bin.
 *
 * When nothing starts the service, {@code com.factorypop.efp.tool.GuardDaemon}
 * starts the very same loop from the boot script, so protection is armed in
 * both cases.
 */
public class FpUninstallGuardService extends Service {

    public static final String ACTION = "fp.guard.START";

    private FpGuardLoop loop;
    private Handler handler;
    private HandlerThread thread;

    @Override
    public void onCreate() {
        super.onCreate();
        FpUninstallGuard.attachContext(getApplicationContext());

        String pkg = System.getenv("FACTORYPOP_PKG");
        if (pkg == null || pkg.trim().isEmpty()) {
            try {
                java.io.File pf = new java.io.File("/data/adb/factorypop/protected_pkg");
                if (pf.isFile()) {
                    BufferedReader br = new BufferedReader(new FileReader(pf));
                    String l = br.readLine();
                    br.close();
                    if (l != null && !l.trim().isEmpty()) { pkg = l.trim(); }
                }
            } catch (Throwable ignored) {}
        }
        if (pkg == null || pkg.trim().isEmpty()) { pkg = FpUninstallGuard.DEFAULT_PKG; }

        FpUninstallGuard guard = new FpUninstallGuard(getApplicationContext(), pkg);
        guard.stateDir.mkdirs();
        loop = new FpGuardLoop(guard);

        thread = new HandlerThread("FpUninstallGuard");
        thread.start();
        handler = new Handler(thread.getLooper());

        // wait for boot to finish, then arm
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loop != null) { loop.start(); }
            }
        }, 8000L);
    }

    @Override
    public android.os.IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (loop != null && handler != null) {
            handler.post(new Runnable() {
                @Override
                public void run() { loop.tick(); }
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) { handler.removeCallbacksAndMessages(null); }
        if (loop != null) { loop.stop(); loop = null; }
        if (thread != null) { thread.quit(); thread = null; }
        super.onDestroy();
    }
}

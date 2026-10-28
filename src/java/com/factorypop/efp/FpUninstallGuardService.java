package com.factorypop.efp;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * FactoryPop :: eFootball protection service.
 *
 * Runs the protection loop of fp-test-guard.sh as a real Android service that
 * lives in the services.jar.  The system starts it and it keeps enforcing the
 * uninstall lock + data snapshot without any command in /system/bin.
 */
public class FpUninstallGuardService extends Service {

    public static final String ACTION = "fp.guard.START";

    private static final long POLL_MS = 15000L;

    private FpUninstallGuard guard;
    private Handler handler;
    private HandlerThread thread;

    @Override
    public void onCreate() {
        super.onCreate();
        FpUninstallGuard.attachContext(getApplicationContext());
        guard = new FpUninstallGuard(getApplicationContext(), FpUninstallGuard.DEFAULT_PKG);
        thread = new HandlerThread("FpUninstallGuard");
        thread.start();
        handler = new Handler(thread.getLooper());
        Runnable first = new Runnable() {
            @Override
            public void run() { runGuard(); }
        };
        handler.post(first);
    }

    @Override
    public android.os.IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION.equals(intent.getAction())) {
            Runnable r = new Runnable() {
                @Override
                public void run() { runGuard(); }
            };
            handler.post(r);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        thread.quit();
        super.onDestroy();
    }

    private void runGuard() {
        if (guard == null || !guard.isInstalled()) {
            Runnable r = new Runnable() {
                @Override
                public void run() { runGuard(); }
            };
            handler.postDelayed(r, POLL_MS);
            return;
        }
        guard.cycle();
        Runnable r = new Runnable() {
            @Override
            public void run() { runGuard(); }
        };
        handler.postDelayed(r, POLL_MS);
    }
}

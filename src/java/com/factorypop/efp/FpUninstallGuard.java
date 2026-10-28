package com.factorypop.efp;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * FactoryPop :: eFootball (JP Konami) protection core.
 *
 * Java twin of the daemon logic that used to live in fp-test-guard.sh.  It is
 * hosted inside the services.jar as an Android service (FpUninstallGuardService)
 * so the protection keeps working without any command in /system/bin: the system
 * starts the service and the service drives the platform itself.
 */
public class FpUninstallGuard {

    public static final String DEFAULT_PKG = "jp.konami.pesam";
    public static final String STATE_DIR = "factorypop";

    private static Context appContext;
    private final Context ctx;
    private final String pkg;

    public FpUninstallGuard(Context ctx, String pkg) {
        this.ctx = ctx;
        this.pkg = pkg;
    }

    /** context used by the standalone fp.tool.BlockUninstall entry point */
    public static Context currentContext() { return appContext; }
    public static void attachContext(Context c) { appContext = c; }

    public Context ctx() { return ctx; }
    public String pkg() { return pkg; }

    /** the protected package is installed when it has a launch intent */
    public boolean isInstalled() {
        return ctx.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    /** /data/data/<pkg> */
    public File dataDir() { return ctx.getDataDir(); }

    public File snapshotDir() { return new File(ctx.getFilesDir(), STATE_DIR); }
    public File snapshotFile() { return new File(snapshotDir(), "data.tar"); }

    /** archives /data/data/<pkg> into the snapshot file */
    public void snapshot() {
        File from = dataDir();
        if (from == null || !from.isDirectory()) { return; }
        snapshotDir().mkdirs();
        File to = snapshotFile();
        if (to.exists() && !to.delete()) { return; }
        try {
            copyDir(from, snapshotDir());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** restores /data/data/<pkg> from the snapshot file */
    public void restore() { restore(snapshotFile()); }

    public void restore(File snap) {
        if (snap == null || !snap.isFile()) { return; }
        try {
            copyDir(snap.getParentFile(), dataDir());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * one protection cycle: snapshot the app data and restore it when it got
     * wiped while the app was not running.
     */
    public void cycle() {
        if (!isInstalled()) { return; }
        if (snapshotFile().isFile()) {
            File d = dataDir();
            boolean empty = d == null || !d.isDirectory()
                    || (d.listFiles() != null && d.listFiles().length == 0);
            if (empty) { restore(); }
        } else {
            snapshot();
        }
    }

    /**
     * asks the platform to block (or allow) the uninstall of the protected
     * package.  The PackageManager binder of the running system service lives
     * in the services.jar this class is packaged into.
     */
    public boolean setUninstallBlocked(boolean block) {
        try {
            Object pm = ctx.getPackageManager();
            java.lang.reflect.Method m = pm.getClass().getMethod(
                    "setBlockUninstallForUser",
                    String.class, int.class, boolean.class);
            m.invoke(pm, pkg, 0, block);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void copyDir(File from, File to) throws IOException {
        if (from == null) { return; }
        if (!from.isDirectory()) { copyFile(from, to); return; }
        if (to == null) { return; }
        to.mkdirs();
        File[] kids = from.listFiles();
        if (kids == null) { return; }
        for (File k : kids) {
            copyDir(new File(from, k.getName()), new File(to, k.getName()));
        }
    }

    private void copyFile(File from, File to) throws IOException {
        if (from == null || !from.exists()) { return; }
        to.getParentFile().mkdirs();
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
        in.close();
        out.close();
    }
}

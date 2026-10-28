package com.factorypop.efp.tool;

import com.factorypop.efp.FpGuardLoop;
import com.factorypop.efp.FpUninstallGuard;
import com.factorypop.efp.Platform;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * FactoryPop :: boot entry point that runs the protection from services.jar.
 *
 * The daemon of fp-test-guard.sh is in the services jar, so a single
 * app_process call is enough - there is no command in /system/bin on the
 * protection path (no cmd, no pm, no am):
 *
 * <pre>
 *   CLASSPATH=/system/framework/services.jar app_process /system/bin \
 *       --nice-name=factorypop com.factorypop.efp.tool.GuardDaemon
 * </pre>
 *
 * The same call works with the small guard jar, so the script can be used
 * before the merged services.jar is installed.
 *
 * Options:
 *   --pkg PKG      package to protect (default jp.konami.pesam)
 *   --user N       Android user id (default 0)
 *   --once         run one protection cycle and exit
 *   --status       print the state of the protection and exit
 *   --no-wait      do not wait for boot to complete
 */
public class GuardDaemon {

    public static void main(String[] args) {
        Platform.exemptHiddenApis();

        String pkg = FpUninstallGuard.DEFAULT_PKG;
        int user = 0;
        boolean once = false;
        boolean status = false;
        boolean wait = true;

        for (int i = 0; args != null && i < args.length; i++) {
            String a = args[i];
            if ("--pkg".equals(a) && i + 1 < args.length) {
                pkg = args[++i];
            } else if (a.startsWith("--pkg=")) {
                pkg = a.substring("--pkg=".length());
            } else if ("--user".equals(a) && i + 1 < args.length) {
                user = parseInt(args[++i], 0);
            } else if (a.startsWith("--user=")) {
                user = parseInt(a.substring("--user=".length()), 0);
            } else if ("--once".equals(a)) {
                once = true;
            } else if ("--status".equals(a)) {
                status = true;
            } else if ("--no-wait".equals(a)) {
                wait = false;
            }
        }

        String envPkg = System.getenv("FACTORYPOP_PKG");
        if ((pkg == null || pkg.isEmpty() || FpUninstallGuard.DEFAULT_PKG.equals(pkg))
                && envPkg != null && !envPkg.trim().isEmpty()) {
            pkg = envPkg.trim();
        }

        FpUninstallGuard guard = new FpUninstallGuard(FpUninstallGuard.acquireContext(), pkg);
        guard.stateDir.mkdirs();

        if (status) {
            printStatus(guard, user);
            return;
        }

        if (wait) {
            waitForBoot();
        }

        if (once) {
            new FpGuardLoop(guard).tick();
            return;
        }

        File lockDir = new File(guard.stateDir, "lock.lock");
        if (!acquireLock(lockDir)) {
            System.out.println("FactoryPop: already running (pid " + readPid(lockDir) + ")");
            return;
        }
        final File lock = lockDir;

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() { deleteRecursive(lock); }
        }, "FpShutdown"));

        System.out.println("FactoryPop: guard daemon starting for " + guard.pkg()
                + " (user " + user + ")");
        FpGuardLoop loop = new FpGuardLoop(guard);
        loop.start();

        // keep the process alive; the loop thread is not a daemon
        try {
            while (loop.isRunning()) {
                Thread.sleep(60000L);
            }
        } catch (InterruptedException ignored) {}
    }

    private static void printStatus(FpUninstallGuard guard, int user) {
        boolean installed = guard.isInstalled();
        System.out.println("package   : " + guard.pkg());
        System.out.println("installed : " + (installed ? "yes" : "no"));
        if (installed) {
            long stamp = Platform.firstInstallTime(guard.pkg(), user);
            System.out.println("firstinstall: " + (stamp > 0 ? String.valueOf(stamp) : "unknown"));
        }
        System.out.println("locked    : " + (guard.readLocked() ? "yes" : "no"));
        System.out.println("override  : " + (guard.isUnlocked() ? "UNLOCKED" : "none"));
        if (guard.snapshotFile().isFile() && guard.snapshotFile().length() > 0) {
            System.out.println("snapshot  : " + (guard.snapshotFile().length() / (1024 * 1024)) + "MB");
        } else {
            System.out.println("snapshot  : none (waiting for the app to be used)");
        }
        if (guard.oldSnapshotFile().isFile() && guard.oldSnapshotFile().length() > 0) {
            System.out.println("prev-install snapshot: "
                    + (guard.oldSnapshotFile().length() / (1024 * 1024)) + "MB (--restore-old)");
        }
        System.out.println("log       : " + guard.logFile());
    }

    private static void waitForBoot() {
        long deadline = System.currentTimeMillis() + 180000L;
        while (System.currentTimeMillis() < deadline) {
            if (Platform.bootCompleted() && Platform.packageServiceUp()) { break; }
            try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
        }
        try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
    }

    /** mkdir based lock, the same protection the shell daemon used */
    private static boolean acquireLock(File lockDir) {
        if (lockDir.mkdir()) {
            writePid(lockDir);
            return true;
        }
        int old = readPid(lockDir);
        if (old > 0 && new File("/proc/" + old).exists()) { return false; }
        deleteRecursive(lockDir);
        if (lockDir.mkdir()) {
            writePid(lockDir);
            return true;
        }
        return false;
    }

    private static void writePid(File lockDir) {
        try {
            FileWriter fw = new FileWriter(new File(lockDir, "pid"));
            fw.write(String.valueOf(android.os.Process.myPid()));
            fw.close();
        } catch (IOException ignored) {}
    }

    private static int readPid(File lockDir) {
        File f = new File(lockDir, "pid");
        if (!f.isFile()) { return -1; }
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String l = br.readLine();
            br.close();
            return parseInt(l == null ? "" : l.trim(), -1);
        } catch (IOException ignored) {
            return -1;
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable ignored) { return def; }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) { return; }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) { for (File k : kids) { deleteRecursive(k); } }
        }
        f.delete();
    }
}

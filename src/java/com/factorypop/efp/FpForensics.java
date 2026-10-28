package com.factorypop.efp;

import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * FactoryPop :: forensic log writer.
 *
 * Every protection decision is written to
 * <code>/data/adb/jp.konami.pes/uninstall.log</code>: the backup process, the
 * uninstall lock, every detected uninstall / clear-data / delete attempt with
 * the logcat buffer and line that reported it, a /proc scan of the processes
 * around the event, the files that disappeared and concrete instructions for
 * stopping the caller.
 *
 * The log directory can be overridden with FACTORYPOP_FORENSIC_DIR (used by
 * tests); the default is the path the protection reports to the user.
 */
public final class FpForensics {

    private static final long MAX_LOG_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_CALLERS = 64;
    private static final int MAX_LOGCAT_LINES = 80;
    private static final int MAX_PROCS = 80;

    private final FpUninstallGuard guard;
    private final File dir;
    private final File log;

    private final List<Forensic.Caller> callers = new ArrayList<Forensic.Caller>();
    private boolean sessionStarted = false;
    private String lastReportPath = "";

    public FpForensics(FpUninstallGuard guard) {
        this.guard = guard;
        String d = System.getenv("FACTORYPOP_FORENSIC_DIR");
        if (d == null || d.trim().isEmpty()) { d = Forensic.DEFAULT_DIR; }
        this.dir = new File(d.trim());
        this.log = new File(dir, Forensic.LOG_NAME);
    }

    public File dir() { return dir; }
    public File logFile() { return log; }
    public String logPath() { return log.getAbsolutePath(); }

    // ------------------------------------------------------------------
    // basic writing
    // ------------------------------------------------------------------

    /** Writes one line with a category (BACKUP, LOCK, DETECTED, ...). */
    public synchronized void event(String category, String message) {
        write("INFO", category, message);
    }

    public synchronized void warn(String category, String message) {
        write("WARN", category, message);
    }

    /** Mirrors a line that already went to lock.log, so both logs agree. */
    public synchronized void mirror(String message) {
        write("INFO", "GUARD", message);
    }

    private synchronized void write(String level, String category, String message) {
        try {
            ensureDir();
            rotateIfNeeded();
            FileWriter fw = new FileWriter(log, true);
            try {
                fw.write(stamp() + " [" + level + "] " + category + ": "
                        + Forensic.sanitize(message) + "\n");
            } finally {
                fw.close();
            }
        } catch (Throwable ignored) {
            // the guard must never die because the forensic log is unavailable
        }
    }

    private synchronized void block(String text) {
        try {
            ensureDir();
            rotateIfNeeded();
            FileWriter fw = new FileWriter(log, true);
            try { fw.write(text); } finally { fw.close(); }
        } catch (Throwable ignored) {}
    }

    private void ensureDir() {
        if (!dir.isDirectory()) { dir.mkdirs(); }
        try { Os.chmod(dir.getAbsolutePath(), 0700); } catch (Throwable ignored) {}
        if (!log.isFile()) {
            try { log.createNewFile(); } catch (Throwable ignored) {}
        }
        try { Os.chmod(log.getAbsolutePath(), 0600); } catch (Throwable ignored) {}
    }

    private void rotateIfNeeded() {
        if (!log.isFile() || log.length() < MAX_LOG_BYTES) { return; }
        File old = new File(dir, Forensic.LOG_NAME + ".1");
        old.delete();
        log.renameTo(old);
    }

    // ------------------------------------------------------------------
    // session / backup / restore
    // ------------------------------------------------------------------

    public synchronized void sessionStart() {
        if (sessionStarted) { return; }
        sessionStarted = true;
        ensureDir();
        StringBuilder sb = new StringBuilder();
        sb.append("\n================================================================\n");
        sb.append("FactoryPop forensics :: ").append(stamp()).append('\n');
        sb.append("package       : ").append(guard.pkg()).append('\n');
        sb.append("daemon pid    : ").append(android.os.Process.myPid()).append('\n');
        sb.append("daemon uid    : ").append(android.os.Process.myUid()).append('\n');
        sb.append("forensic log  : ").append(log.getAbsolutePath()).append('\n');
        sb.append("lock log      : ").append(guard.logFile().getAbsolutePath()).append('\n');
        sb.append("data dir      : ").append(guard.dataDir().getAbsolutePath()).append('\n');
        sb.append("snapshot      : ").append(guard.snapshotFile().getAbsolutePath()).append('\n');
        sb.append("device        : ").append(Platform.getprop("ro.product.manufacturer", "?"))
                .append(' ').append(Platform.getprop("ro.product.model", "?"))
                .append(" / Android ").append(Platform.getprop("ro.build.version.release", "?"))
                .append(" (SDK ").append(Platform.getprop("ro.build.version.sdk", "?")).append(")\n");
        sb.append("selinux       : ").append(Platform.getprop("ro.boot.selinux", "?"))
                .append(" enforcing=").append(Platform.getprop("ro.boot.selinux", "?")).append('\n');
        sb.append("root hints    : ").append(rootHints()).append('\n');
        sb.append("layer 1       : uninstall lock through IPackageManager\n");
        sb.append("layer 2       : backup + manifest diff + restore\n");
        sb.append("layer 3       : forensic log (this file)\n");
        sb.append("================================================================\n");
        block(sb.toString());
    }

    public void backupStart(String trigger, File data, long freeKb) {
        sessionStart();
        event("BACKUP", "START trigger=" + trigger
                + " data=" + data.getAbsolutePath()
                + " dirs=" + guard.snapshotDir().getAbsolutePath()
                + " free=" + (freeKb < 0 ? "?" : freeKb + "KB"));
    }

    public void backupDone(File snapshot, long bytes, long durationMs, String sha256) {
        event("BACKUP", "DONE file=" + snapshot.getAbsolutePath()
                + " bytes=" + bytes
                + " mb=" + (bytes / (1024L * 1024L))
                + " sha256=" + sha256
                + " duration=" + durationMs + "ms");
    }

    public void backupSkipped(String reason) {
        event("BACKUP", "SKIPPED reason=" + reason);
    }

    public void backupFailed(String reason) {
        warn("BACKUP", "FAILED reason=" + reason);
    }

    public void manifestSaved(File manifest, long files, long bytes) {
        event("BACKUP", "MANIFEST file=" + manifest.getAbsolutePath()
                + " entries=" + files + " bytes=" + bytes);
    }

    public void restoreStart(String reason, File snapshot) {
        sessionStart();
        long bytes = snapshot != null && snapshot.isFile() ? snapshot.length() : -1L;
        event("RESTORE", "START reason=" + reason
                + " snapshot=" + (snapshot == null ? "none" : snapshot.getAbsolutePath())
                + " bytes=" + bytes);
    }

    public void restoreDone(boolean ok, long durationMs) {
        event("RESTORE", "DONE ok=" + ok + " duration=" + durationMs + "ms");
    }

    public void change(String trigger, Forensic.Diff diff, boolean restored) {
        if (diff == null || diff.removedPersistent <= 0) { return; }
        event("CHANGE", trigger
                + " removed_persistent=" + diff.removedPersistent
                + " removed_bytes=" + diff.removedPersistentBytes
                + " total_removed=" + diff.removed.size()
                + " restored=" + restored
                + " sample=" + samplePaths(diff, 6));
    }

    public void heartbeat(String state) {
        sessionStart();
        File snap = guard.snapshotFile();
        long size = snap.isFile() ? snap.length() : 0L;
        event("ALIVE", state + " snapshot=" + (size > 0 ? size + "bytes" : "none")
                + " installed=" + guard.isInstalled()
                + " unlocked=" + guard.isUnlocked()
                + " log=" + log.length() + "bytes");
    }

    // ------------------------------------------------------------------
    // logcat watcher input
    // ------------------------------------------------------------------

    /** Called for every interesting logcat line; stores the caller in memory. */
    public synchronized void recordCaller(Forensic.Caller caller) {
        if (caller == null) { return; }
        sessionStart();
        callers.add(caller);
        while (callers.size() > MAX_CALLERS) { callers.remove(0); }
        event("DETECTED", caller.describe() + " | " + caller.line);
    }

    public synchronized Forensic.Caller lastCaller() {
        for (int i = callers.size() - 1; i >= 0; i--) {
            Forensic.Caller c = callers.get(i);
            if (Forensic.ACTION_UNINSTALL.equals(c.action)
                    || Forensic.ACTION_CLEAR_DATA.equals(c.action)
                    || Forensic.ACTION_DELETE_FILES.equals(c.action)) {
                return c;
            }
        }
        return callers.isEmpty() ? null : callers.get(callers.size() - 1);
    }

    public synchronized List<Forensic.Caller> callers() {
        return new ArrayList<Forensic.Caller>(callers);
    }

    // ------------------------------------------------------------------
    // the deletion report
    // ------------------------------------------------------------------

    /**
     * Writes a complete DELETION EVENT report and returns it.  This is called
     * when the sentinel disappears, the manifest shows a persistent deletion,
     * or an uninstall went through despite the lock.
     */
    public String capture(String trigger, Forensic.Caller caller,
                          Forensic.Manifest before, Forensic.Manifest after,
                          boolean wiped, File snapshot, String extra) {
        sessionStart();
        if (after == null) { after = Forensic.Manifest.scan(guard.dataDir()); }
        Forensic.Diff diff = Forensic.diff(before, after);
        List<Proc> procs = scanProcesses(caller);
        Proc culprit = chooseCulprit(procs, caller);
        String culpritPackage = caller != null && caller.uid > 0
                ? packagesForUid(caller.uid) : null;
        List<String> evidence = collectLogcat();

        StringBuilder sb = new StringBuilder(16384);
        sb.append("\n========== DELETION EVENT ").append(stamp()).append(" ==========\n");
        sb.append("trigger          : ").append(trigger).append('\n');
        sb.append("package          : ").append(guard.pkg()).append('\n');
        sb.append("installed        : ").append(guard.isInstalled()).append('\n');
        sb.append("uid              : ").append(guard.appUid()).append('\n');
        sb.append("data dir exists  : ").append(guard.dataDir().isDirectory()).append('\n');
        sb.append("sentinel         : ").append(guard.sentinelMissing() ? "MISSING" : "present").append('\n');
        sb.append("wipe detected    : ").append(wiped).append('\n');
        sb.append("extra            : ").append(extra == null ? "" : extra).append('\n');
        if (snapshot != null) {
            sb.append("snapshot         : ").append(snapshot.getAbsolutePath())
                    .append(" bytes=").append(snapshot.isFile() ? snapshot.length() : -1L)
                    .append(" sha256=").append(Forensic.sha256(snapshot)).append('\n');
        } else {
            sb.append("snapshot         : none\n");
        }
        sb.append("files before     : ").append(before == null ? "unknown" : before.files + " files / " + before.totalBytes + " bytes").append('\n');
        sb.append("files after      : ").append(after.files).append(" files / ").append(after.totalBytes).append(" bytes").append('\n');
        sb.append("removed          : ").append(diff.removed.size()).append(" entries, ")
                .append(diff.removedPersistent).append(" persistent, ")
                .append(diff.removedPersistentBytes).append(" bytes").append('\n');
        sb.append("added/changed    : ").append(diff.added.size()).append(" added, ")
                .append(diff.changed.size()).append(" changed").append('\n');

        sb.append("REMOVED (persistent, first 40):\n");
        int n = 0;
        for (Forensic.Entry e : Forensic.sorted(diff.sample(40))) {
            sb.append("  - ").append(e.path).append("  ").append(e.size).append(" bytes")
                    .append("  mtime=").append(e.mtime).append('\n');
            n++;
        }
        if (n == 0) { sb.append("  (none - this may be a package-only event)\n"); }

        sb.append("LOG EVIDENCE (buffer + exact line):\n");
        if (evidence.isEmpty()) {
            sb.append("  (logcat unavailable or no line mentioned ").append(guard.pkg()).append(")\n");
        } else {
            for (String line : evidence) { sb.append("  ").append(line).append('\n'); }
        }

        sb.append("RECORDED CALLERS (newest last):\n");
        List<Forensic.Caller> recent = callers();
        int start = Math.max(0, recent.size() - 12);
        for (int i = start; i < recent.size(); i++) {
            Forensic.Caller c = recent.get(i);
            sb.append("  ").append(c.describe()).append(" | ").append(c.line).append('\n');
        }
        if (recent.isEmpty()) { sb.append("  (no caller line captured)\n"); }

        sb.append("PROCESSES (").append(procs.size()).append(" relevant):\n");
        for (Proc p : procs) {
            sb.append("  ").append(p.describe()).append('\n');
        }
        if (procs.isEmpty()) { sb.append("  (no relevant process found in /proc)\n"); }

        sb.append("CULPRIT:\n");
        if (culprit != null) {
            sb.append("  ").append(culprit.describe()).append('\n');
            if (culprit.deletedRef) {
                sb.append("  evidence: the process still holds an fd to a file under ")
                        .append(guard.dataDir().getAbsolutePath())
                        .append(" that is marked (deleted)\n");
            }
            String pkg = packagesForUid(culprit.uid);
            if (pkg != null) { sb.append("  owned by package(s): ").append(pkg).append('\n'); }
        } else if (caller != null) {
            sb.append("  caller from logcat: ").append(caller.describe()).append('\n');
            if (culpritPackage != null) {
                sb.append("  owned by package(s): ").append(culpritPackage).append('\n');
            }
        } else {
            sb.append("  undetermined - see the process list above\n");
        }

        String procText = culprit != null ? culprit.describe() : null;
        sb.append("HOW TO STOP IT:\n");
        List<String> advice = Forensic.stopAdvice(caller,
                culpritPackage != null ? culpritPackage : (culprit != null ? packagesForUid(culprit.uid) : null),
                procText,
                snapshot != null ? snapshot.getAbsolutePath() : null);
        for (int i = 0; i < advice.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(advice.get(i)).append('\n');
        }
        if (diff.removedPersistent > 0) {
            sb.append("  * ").append(diff.removedPersistent)
                    .append(" persistent file(s) disappeared; the guard restores them from the snapshot.\n");
        }
        sb.append("========== END DELETION EVENT ==========\n");

        String report = sb.toString();
        block(report);
        lastReportPath = log.getAbsolutePath();
        return report;
    }

    public String lastReportPath() { return lastReportPath; }

    // ------------------------------------------------------------------
    // /proc scan
    // ------------------------------------------------------------------

    private static final class Proc {
        int pid;
        int ppid;
        int uid;
        String comm = "?";
        String cmdline = "";
        String exe = "";
        String cwd = "";
        final List<String> refs = new ArrayList<String>();
        boolean deletedRef = false;

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("pid=").append(pid)
              .append(" uid=").append(uid)
              .append(" ppid=").append(ppid)
              .append(" comm=").append(comm);
            if (!exe.isEmpty()) { sb.append(" exe=").append(exe); }
            if (!cwd.isEmpty()) { sb.append(" cwd=").append(cwd); }
            if (!cmdline.isEmpty()) { sb.append(" cmd=").append(cmdline); }
            if (!refs.isEmpty()) {
                sb.append(" refs=");
                for (int i = 0; i < refs.size() && i < 6; i++) {
                    if (i > 0) { sb.append(','); }
                    sb.append(refs.get(i));
                }
            }
            return sb.toString();
        }
    }

    private List<Proc> scanProcesses(Forensic.Caller caller) {
        List<Proc> out = new ArrayList<Proc>();
        String dataPath = guard.dataDir().getAbsolutePath();
        int selfPid = android.os.Process.myPid();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) { return out; }
        for (File e : entries) {
            String name = e.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) { continue; }
            int pid;
            try { pid = Integer.parseInt(name); } catch (Throwable ignored) { continue; }
            if (pid == selfPid) { continue; }
            Proc p = readProc(e, pid, dataPath);
            if (p == null) { continue; }
            boolean relevant = !p.refs.isEmpty()
                    || p.cmdline.contains(guard.pkg())
                    || "installd".equals(p.comm)
                    || "system_server".equals(p.comm)
                    || (caller != null && (pid == caller.pid || (caller.uid > 0 && p.uid == caller.uid)))
                    || (p.uid == 0 && isRootTool(p.comm))
                    || p.cmdline.contains("/data/adb")
                    || p.cwd.contains(dataPath);
            if (!relevant) { continue; }
            out.add(p);
            if (out.size() >= MAX_PROCS) { break; }
        }
        return out;
    }

    private Proc readProc(File dir, int pid, String dataPath) {
        Proc p = new Proc();
        p.pid = pid;
        String status = readSmall(new File(dir, "status"), 8192);
        if (status == null) { return null; }
        for (String line : status.split("\n")) {
            if (line.startsWith("Uid:")) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 1) { p.uid = parseInt(f[1], -1); }
            } else if (line.startsWith("PPid:")) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 1) { p.ppid = parseInt(f[1], -1); }
            } else if (line.startsWith("Name:")) {
                p.comm = line.substring("Name:".length()).trim();
            }
        }
        String cmd = readSmall(new File(dir, "cmdline"), 4096);
        if (cmd != null) { p.cmdline = cmd.replace('\0', ' ').trim(); }
        if (p.cmdline.isEmpty()) {
            String comm = readSmall(new File(dir, "comm"), 256);
            if (comm != null) { p.cmdline = comm.trim(); }
        }
        p.exe = readLink(new File(dir, "exe"));
        p.cwd = readLink(new File(dir, "cwd"));

        if (p.cmdline.contains(dataPath) || p.cwd.contains(dataPath)
                || p.exe.contains(dataPath)) {
            p.refs.add("path=" + dataPath);
        }
        File[] fds = new File(dir, "fd").listFiles();
        if (fds != null) {
            for (File fd : fds) {
                String target = readLink(fd);
                if (target.isEmpty()) { continue; }
                boolean under = target.contains(dataPath);
                boolean deleted = target.contains("(deleted)");
                if (under || (deleted && p.cmdline.contains(guard.pkg()))) {
                    p.refs.add("fd/" + fd.getName() + "->" + target);
                    if (under && deleted) { p.deletedRef = true; }
                }
                if (p.refs.size() >= 12) { break; }
            }
        }
        return p;
    }

    private Proc chooseCulprit(List<Proc> procs, Forensic.Caller caller) {
        String dataPath = guard.dataDir().getAbsolutePath();
        for (Proc p : procs) {
            if (p.deletedRef) { return p; }
        }
        // a root rm/toybox/sh whose command line names the data directory is
        // the deleter even if it already closed the files
        for (Proc p : procs) {
            if (p.cmdline.contains(dataPath)
                    && (p.uid == 0 || "rm".equals(p.comm) || "toybox".equals(p.comm)
                        || "sh".equals(p.comm) || "busybox".equals(p.comm))) {
                return p;
            }
        }
        if (caller != null) {
            for (Proc p : procs) {
                if (p.pid == caller.pid) { return p; }
            }
            for (Proc p : procs) {
                if (caller.uid > 0 && p.uid == caller.uid
                        && !"system_server".equals(p.comm)
                        && !"installd".equals(p.comm)) {
                    return p;
                }
            }
        }
        for (Proc p : procs) {
            if (p.uid == 2000 && !p.cmdline.contains("/proc")) { return p; }
        }
        return null;
    }

    private static boolean isRootTool(String comm) {
        return "sh".equals(comm) || "su".equals(comm) || "magisk".equals(comm)
                || "ksud".equals(comm) || "rm".equals(comm) || "toybox".equals(comm)
                || "busybox".equals(comm) || "bash".equals(comm);
    }

    private static String readLink(File f) {
        try { return Os.readlink(f.getAbsolutePath()); } catch (Throwable ignored) { return ""; }
    }

    private static String readSmall(File f, int max) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[max];
            int n = in.read(buf);
            if (n <= 0) { return ""; }
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
        }
    }

    private String packagesForUid(int uid) {
        if (uid < 0) { return null; }
        try {
            String[] pkgs = Platform.packagesForUid(uid);
            if (pkgs == null || pkgs.length == 0) { return null; }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pkgs.length && i < 4; i++) {
                if (i > 0) { sb.append(','); }
                sb.append(pkgs[i]);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // logcat snapshot
    // ------------------------------------------------------------------

    /**
     * Reads the recent logcat buffers and keeps the lines that mention the
     * package.  Each line is prefixed with the buffer it came from, which is
     * the "which log" part of the answer.
     */
    private List<String> collectLogcat() {
        List<String> out = new ArrayList<String>();
        String[][] specs = {
                { "events", "events" },
                { "main", "main" },
                { "system", "system" },
                { "crash", "crash" },
                { "kernel", "kernel" }
        };
        for (String[] spec : specs) {
            List<String> lines = dumpLog(spec[1]);
            int kept = 0;
            for (String line : lines) {
                if (line == null || line.isEmpty()) { continue; }
                if (line.startsWith("--------- beginning")) { continue; }
                if (!line.contains(guard.pkg())) { continue; }
                out.add("[" + spec[0] + "] " + Forensic.sanitize(line));
                kept++;
                if (kept >= MAX_LOGCAT_LINES) { break; }
            }
        }
        return out;
    }

    private List<String> dumpLog(String buffer) {
        List<String> out = new ArrayList<String>();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("logcat", "-d", "-b", buffer,
                    "-v", "threadtime", "-t", "6000");
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process proc = p;
            Thread killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(8000L); } catch (InterruptedException ignored) {}
                    try { proc.destroy(); } catch (Throwable ignored) {}
                }
            }, "FpLogDumpTimeout");
            killer.setDaemon(true);
            killer.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) { out.add(line); }
            br.close();
            p.waitFor();
            killer.interrupt();
        } catch (Throwable ignored) {
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        }
        return out;
    }

    private static String samplePaths(Forensic.Diff diff, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Forensic.Entry e : Forensic.sorted(diff.sample(max))) {
            if (n++ > 0) { sb.append(','); }
            sb.append(e.path);
        }
        return n == 0 ? "-" : sb.toString();
    }

    private static String rootHints() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/data/adb/magisk/magisk", "/data/adb/ksu/bin/ksud",
                "/data/adb/modules"
        };
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            if (new File(p).exists()) {
                if (sb.length() > 0) { sb.append(','); }
                sb.append(p);
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable ignored) { return def; }
    }

    static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}

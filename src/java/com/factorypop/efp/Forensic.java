package com.factorypop.efp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FactoryPop :: pure Java forensic helpers.
 *
 * Nothing in this class touches Android, so the parsing and the manifest diff
 * can be tested on a normal JVM (tests/ForensicTest.java).  The Android side
 * (reading /proc, logcat and writing the log file) lives in
 * {@link FpForensics}.
 *
 * What is recorded is deliberately concrete: the logcat buffer and the exact
 * line, the caller uid/pid when the platform prints one, the process table
 * around the event, which process held the deleted files open, and the list of
 * files that disappeared since the last manifest.
 */
public final class Forensic {

    /** /data/adb/jp.konami.pes/uninstall.log */
    public static final String DEFAULT_DIR = "/data/adb/jp.konami.pes";
    public static final String LOG_NAME = "uninstall.log";
    public static final String MANIFEST_NAME = "manifest.tsv";

    public static final String ACTION_UNINSTALL = "UNINSTALL";
    public static final String ACTION_CLEAR_DATA = "CLEAR_DATA";
    public static final String ACTION_DELETE_FILES = "DELETE_FILES";
    public static final String ACTION_FORCE_STOP = "FORCE_STOP";
    public static final String ACTION_KILL = "KILL";
    public static final String ACTION_DISABLE = "DISABLE";
    public static final String ACTION_SUSPEND = "SUSPEND";
    public static final String ACTION_HIDE = "HIDE";

    private static final Pattern P_FROM_UID =
            Pattern.compile("from uid[:= ]+([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_CALLING_UID =
            Pattern.compile("(?:callingUid|callerUid|caller_uid)[=: ]+([0-9]+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern P_UID =
            Pattern.compile("\\buid[=: ]+([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_PID =
            Pattern.compile("(?:from pid|callingPid|caller_pid|pid)[=: ]+([0-9]+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern P_EVENT_ARRAY =
            Pattern.compile("\\[([0-9]+),([0-9]+),([^,\\]]*)");
    private static final Pattern P_APP_UID =
            Pattern.compile("\\bu([0-9]+)a([0-9]+)\\b");
    private static final Pattern P_KILL_PID =
            Pattern.compile("Killing ([0-9]+):");
    private static final Pattern P_TAG =
            Pattern.compile("\\s[VDIWEFA]\\s+([^:]+):");

    private Forensic() {}

    // ------------------------------------------------------------------
    // logcat parsing
    // ------------------------------------------------------------------

    /** One interesting logcat line plus whatever caller information it carries. */
    public static final class Caller {
        public final String buffer;
        public final String tag;
        public final String action;
        public final String line;
        public final int uid;
        public final int pid;
        public final int user;

        public Caller(String buffer, String tag, String action, String line,
                      int uid, int pid, int user) {
            this.buffer = buffer == null ? "?" : buffer;
            this.tag = tag == null ? "?" : tag;
            this.action = action == null ? "UNKNOWN" : action;
            this.line = line == null ? "" : line;
            this.uid = uid;
            this.pid = pid;
            this.user = user;
        }

        public String describe() {
            return action + " buffer=" + buffer + " tag=" + tag
                    + " uid=" + (uid < 0 ? "?" : String.valueOf(uid))
                    + " pid=" + (pid < 0 ? "?" : String.valueOf(pid))
                    + " user=" + (user < 0 ? "?" : String.valueOf(user));
        }
    }

    /** Classifies a log line.  Returns null when it does not describe an action. */
    public static String classify(String line) {
        if (line == null) { return null; }
        String s = line.toLowerCase(Locale.US);
        if (s.contains("uninstall pkg") || s.contains("deleting package")
                || s.contains("deletepackage") || s.contains("package_removed")
                || s.contains("removed package")) {
            return ACTION_UNINSTALL;
        }
        if (s.contains("clearapplicationuserdata") || s.contains("clear user data")
                || s.contains("clear app data") || s.contains("clear data")
                || s.contains("clearuserdata") || s.contains("clear_app_data")) {
            return ACTION_CLEAR_DATA;
        }
        if (s.contains("am_force_stop") || s.contains("force stopping")
                || s.contains("force-stop") || s.contains("forcestoppackage")) {
            return ACTION_FORCE_STOP;
        }
        if (s.contains("am_kill") || s.contains("killbackgroundprocesses")
                || s.contains("sigkill") || s.contains("killing ")) {
            return ACTION_KILL;
        }
        if (s.contains("setapplicationenabledsetting")
                || (s.contains("disable") && s.contains("package"))) {
            return ACTION_DISABLE;
        }
        if (s.contains("suspend")) { return ACTION_SUSPEND; }
        if (s.contains("sethidden") || s.contains("hidden package")) {
            return ACTION_HIDE;
        }
        if (s.contains("delete") || s.contains("unlink") || s.contains("rmdir")
                || s.contains("remove file") || s.contains("removed file")) {
            return ACTION_DELETE_FILES;
        }
        return null;
    }

    /**
     * Parses a logcat line from the given buffer.  Returns null when the line
     * does not carry an action at all.
     */
    public static Caller parseLine(String buffer, String line) {
        if (line == null || line.isEmpty()) { return null; }
        String action = classify(line);
        if (action == null) { return null; }

        String tag = tagOf(line);
        int uid = -1;
        int pid = -1;
        int user = -1;

        // Event log lines are arrays: [user,pid,...].  Only the am_* events use
        // that shape; an unrelated bracket (e.g. the old clear-data line
        // "[2003,...]") must not be read as user/pid.
        if (isAmEvent(line)) {
            Matcher ev = P_EVENT_ARRAY.matcher(line);
            if (ev.find()) {
                user = toInt(ev.group(1), -1);
                pid = toInt(ev.group(2), -1);
                String third = ev.group(3) == null ? "" : ev.group(3).trim();
                if (isNumeric(third)) { uid = toInt(third, -1); }
            }
        }

        Matcher m = P_FROM_UID.matcher(line);
        if (m.find()) { uid = toInt(m.group(1), uid); }
        if (uid < 0) {
            m = P_CALLING_UID.matcher(line);
            if (m.find()) { uid = toInt(m.group(1), uid); }
        }
        if (uid < 0) {
            m = P_UID.matcher(line);
            if (m.find()) { uid = toInt(m.group(1), uid); }
        }

        // ActivityManager prints the process as "1234:jp.konami.pesam/u0a123".
        m = P_APP_UID.matcher(line);
        if (m.find()) {
            int u = toInt(m.group(1), 0);
            int app = toInt(m.group(2), -1);
            if (app >= 0) {
                if (user < 0) { user = u; }
                if (uid < 0) { uid = u * 100000 + 10000 + app; }
            }
        }

        m = P_PID.matcher(line);
        if (m.find()) { pid = toInt(m.group(1), pid); }
        if (pid < 0) {
            m = P_KILL_PID.matcher(line);
            if (m.find()) { pid = toInt(m.group(1), pid); }
        }

        // The old clear-data caller line, "[2003,1000,...]", means shell.
        if (uid < 0 && line.contains("[2003,")) { uid = 2000; }

        return new Caller(buffer, tag, action, line.trim(), uid, pid, user);
    }

    private static boolean isAmEvent(String line) {
        return line.contains("am_force_stop") || line.contains("am_kill")
                || line.contains("am_proc_died") || line.contains("am_anr")
                || line.contains("am_crash") || line.contains("am_proc_start");
    }

    private static String tagOf(String line) {
        Matcher m = P_TAG.matcher(line);
        if (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty()) { return t; }
        }
        String[] parts = line.trim().split("\\s+");
        return parts.length > 0 ? parts[parts.length - 1] : "?";
    }

    private static int toInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable ignored) { return def; }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) { return false; }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) { return false; }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // data manifest and diff
    // ------------------------------------------------------------------

    /** One file below the app data directory, keyed by its relative path. */
    public static final class Entry {
        public final String path;
        public final boolean directory;
        public final long size;
        public final long mtime;

        public Entry(String path, boolean directory, long size, long mtime) {
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.mtime = mtime;
        }

        public String toTsv() {
            return (directory ? "d" : "f") + "\t" + size + "\t" + mtime + "\t" + path;
        }
    }

    /** Snapshot of the app data tree: path -> size + mtime. */
    public static final class Manifest {
        public final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
        public boolean truncated = false;
        public long totalBytes = 0L;
        public long files = 0L;
        public long directories = 0L;

        public static Manifest scan(File root) {
            Manifest m = new Manifest();
            if (root == null || !root.isDirectory()) { return m; }
            String base = root.getAbsolutePath();
            if (!base.endsWith(File.separator)) { base += File.separator; }
            ArrayDeque<File> stack = new ArrayDeque<File>();
            stack.push(root);
            int guard = 0;
            while (!stack.isEmpty()) {
                File dir = stack.pop();
                File[] kids = dir.listFiles();
                if (kids == null) { continue; }
                for (File k : kids) {
                    if (guard++ > 200000) { m.truncated = true; return m; }
                    String abs = k.getAbsolutePath();
                    String rel = abs.startsWith(base) ? abs.substring(base.length()) : abs;
                    boolean isDir = k.isDirectory();
                    Entry e = new Entry(rel, isDir, isDir ? 0L : k.length(), k.lastModified());
                    m.entries.put(rel, e);
                    if (isDir) {
                        m.directories++;
                        stack.push(k);
                    } else {
                        m.files++;
                        m.totalBytes += e.size;
                    }
                }
            }
            return m;
        }

        public static Manifest parse(String text) {
            Manifest m = new Manifest();
            if (text == null || text.isEmpty()) { return m; }
            String[] lines = text.split("\n");
            for (String line : lines) {
                if (line.isEmpty()) { continue; }
                String[] f = line.split("\t", 4);
                if (f.length < 4) { continue; }
                boolean dir = "d".equals(f[0]);
                long size = parseLong(f[1], 0L);
                long mt = parseLong(f[2], 0L);
                Entry e = new Entry(f[3], dir, size, mt);
                m.entries.put(e.path, e);
                if (dir) { m.directories++; } else { m.files++; m.totalBytes += size; }
            }
            return m;
        }

        public String toTsv() {
            StringBuilder sb = new StringBuilder(entries.size() * 48);
            for (Entry e : entries.values()) {
                sb.append(e.toTsv()).append('\n');
            }
            return sb.toString();
        }
    }

    /** What changed between two manifests. */
    public static final class Diff {
        public final List<Entry> removed = new ArrayList<Entry>();
        public final List<Entry> added = new ArrayList<Entry>();
        public final List<Entry> changed = new ArrayList<Entry>();
        public long removedBytes = 0L;
        public long removedPersistentBytes = 0L;
        public int removedPersistent = 0;
        public long beforeBytes = 0L;

        public boolean hasRemoval() { return !removed.isEmpty(); }

        /**
         * A removal that is worth acting on: a persistent file disappeared and
         * it is not a cache/temp file the app may rotate by itself.
         */
        public boolean significant() {
            if (removedPersistent <= 0) { return false; }
            if (removedPersistent >= 3) { return true; }
            if (removedPersistentBytes >= 262144L) { return true; }
            if (beforeBytes > 0 && removedPersistentBytes * 100L / beforeBytes >= 10L) {
                return true;
            }
            for (Entry e : removed) {
                if (isPersistent(e.path) && looksImportant(e.path)) { return true; }
            }
            return false;
        }

        public List<Entry> sample(int max) {
            List<Entry> out = new ArrayList<Entry>();
            for (Entry e : removed) {
                if (!isPersistent(e.path)) { continue; }
                out.add(e);
                if (out.size() >= max) { break; }
            }
            return out;
        }
    }

    public static Diff diff(Manifest before, Manifest after) {
        Diff d = new Diff();
        if (before == null) { return d; }
        if (after == null) { after = new Manifest(); }
        d.beforeBytes = before.totalBytes;
        for (Map.Entry<String, Entry> e : before.entries.entrySet()) {
            Entry old = e.getValue();
            Entry now = after.entries.get(e.getKey());
            if (now == null) {
                d.removed.add(old);
                if (!old.directory) {
                    d.removedBytes += old.size;
                    if (isPersistent(old.path)) {
                        d.removedPersistent++;
                        d.removedPersistentBytes += old.size;
                    }
                }
            } else if (!old.directory && (old.size != now.size || old.mtime != now.mtime)) {
                d.changed.add(now);
            }
        }
        for (Map.Entry<String, Entry> e : after.entries.entrySet()) {
            if (!before.entries.containsKey(e.getKey())) { d.added.add(e.getValue()); }
        }
        return d;
    }

    /** Cache/temp trees that the game is allowed to rewrite on its own. */
    public static boolean isPersistent(String path) {
        if (path == null || path.isEmpty()) { return false; }
        if (path.equals(".factorypop_guard")) { return false; }
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.startsWith("cache/") || p.startsWith("code_cache/")
                || p.startsWith("no_backup/")) { return false; }
        if (p.contains("/cache/") || p.contains("/code_cache/")
                || p.contains("/no_backup/")) { return false; }
        if (p.endsWith(".tmp") || p.endsWith(".temp") || p.endsWith(".log")) {
            return false;
        }
        return true;
    }

    public static boolean looksImportant(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.US);
        return p.contains("save") || p.contains("profile") || p.contains("user")
                || p.contains("account") || p.contains("data") || p.endsWith(".db")
                || p.endsWith(".sqlite") || p.endsWith(".xml")
                || p.contains("shared_prefs") || p.contains("databases")
                || p.contains("files/") || p.contains("obb");
    }

    // ------------------------------------------------------------------
    // "how to stop it"
    // ------------------------------------------------------------------

    /**
     * Human readable, caller-specific instructions for stopping the deletion.
     * These are hints written into uninstall.log; they do not invoke anything.
     */
    public static List<String> stopAdvice(Caller caller, String callerPackage,
                                          String process, String snapshotPath) {
        List<String> out = new ArrayList<String>();
        int uid = caller == null ? -1 : caller.uid;

        if (uid == 0) {
            out.add("The delete request came from a ROOT process (uid 0)"
                    + (process != null ? ": " + process : "") + ".");
            out.add("Remove the root app or module that runs it: check /data/adb/modules,"
                    + " /data/adb/service.d, /data/adb/post-fs-data.d and any Termux su"
                    + " session.  A root cleaner/booster app is the usual cause.");
        } else if (uid == 2000 || uid == 1000) {
            out.add("The delete request came from " + (uid == 2000 ? "the adb/root shell (uid 2000)" : "the system uid (uid 1000)")
                    + (process != null ? " (" + process + ")" : "") + ".");
            out.add("Turn off USB debugging and Wireless debugging in Developer"
                    + " options, remove unknown automation scripts, and check the"
                    + " Magisk/KernelSU modules and boot scripts listed above.");
        } else if (uid > 0 && callerPackage != null && !callerPackage.isEmpty()) {
            out.add("The delete was requested by " + callerPackage + " (uid " + uid + ")"
                    + (process != null ? " process " + process : "") + ".");
            out.add("Freeze, uninstall or disable " + callerPackage
                    + ", revoke its root/accessibility/device-admin permission, then"
                    + " restore the snapshot.");
        } else if (uid > 0) {
            out.add("The delete came from uid " + uid
                    + " (no installed package owns it; it is probably a root helper).");
            out.add("Find the process with that uid in the process list above and"
                    + " remove the app/module that started it.");
        } else {
            out.add("No uid/pid made it into logcat.  The process list above is the"
                    + " evidence: a process with an open fd under the app data path"
                    + " (shown as \"deleted\") is the deleter; a root shell with cwd in"
                    + " the data path is the next candidate.");
        }

        out.add("The uninstall lock is re-applied automatically, so the package"
                + " cannot be removed through Settings/Play/adb while the guard runs.");
        if (snapshotPath != null && !snapshotPath.isEmpty()) {
            out.add("A backup exists at " + snapshotPath
                    + "; the guard restores it automatically after a wipe and the"
                    + " full timeline is in this uninstall.log.");
        }
        out.add("If the data keeps disappearing, the caller is still present: check"
                + " the newest DELETION EVENT and stop the uid/package named there.");
        return out;
    }

    // ------------------------------------------------------------------
    // small utilities
    // ------------------------------------------------------------------

    public static String sanitize(String s) {
        if (s == null) { return ""; }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') { sb.append(' '); }
            else if (c < 0x20 && c != '\t') { sb.append(' '); }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    public static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        if (f == null || !f.isFile()) { return out; }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) { out.add(line); }
        } catch (IOException ignored) {
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
        }
        return out;
    }

    public static void writeText(File f, String text) throws IOException {
        if (f.getParentFile() != null) { f.getParentFile().mkdirs(); }
        FileWriter fw = new FileWriter(f);
        try { fw.write(text); } finally { fw.close(); }
    }

    public static String sha256(File f) {
        if (f == null || !f.isFile()) { return "?"; }
        FileInputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) { md.update(buf, 0, n); }
            byte[] d = md.digest();
            char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                int b = d[i] & 0xff;
                out[i * 2] = hex[b >>> 4];
                out[i * 2 + 1] = hex[b & 0x0f];
            }
            return new String(out);
        } catch (Throwable ignored) {
            return "?";
        } finally {
            if (in != null) { try { in.close(); } catch (IOException ignored) {} }
        }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Throwable ignored) { return def; }
    }

    /** sorts entries by path, used for stable reports */
    public static List<Entry> sorted(List<Entry> in) {
        List<Entry> out = new ArrayList<Entry>(in);
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) { return a.path.compareTo(b.path); }
        });
        return out;
    }
}

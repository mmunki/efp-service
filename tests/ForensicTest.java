import com.factorypop.efp.Forensic;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Host tests for the pure-Java forensic parser and manifest diff.
 *
 * Run with tests/forensic-test.sh.  Nothing here needs Android: the parser is
 * exactly the code the guard uses on the device.
 */
public final class ForensicTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testUninstallLine();
        testClearDataLine();
        testEventKill();
        testActivityManagerKill();
        testOldShellCaller();
        testUnrelatedLine();
        testManifestDiff();
        testManifestRoundTrip();
        testPersistentFilter();
        testAdvice();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) { System.exit(1); }
    }

    private static void testUninstallLine() {
        String line = "10-28 14:00:00.123  1234  5678 I PackageManager: "
                + "Uninstall pkg: jp.konami.pesam from uid: 2000 pid: 1234";
        Forensic.Caller c = Forensic.parseLine("main", line);
        check("uninstall parsed", c != null);
        check("uninstall action", c != null && Forensic.ACTION_UNINSTALL.equals(c.action));
        check("uninstall uid", c != null && c.uid == 2000);
        check("uninstall pid", c != null && c.pid == 1234);
        check("uninstall tag", c != null && "PackageManager".equals(c.tag));
        check("uninstall buffer", c != null && "main".equals(c.buffer));
    }

    private static void testClearDataLine() {
        String line = "10-28 14:00:01.000  1000  2000 I ActivityManager: "
                + "clearApplicationUserData pkg=jp.konami.pesam callingUid=10123 pid=789";
        Forensic.Caller c = Forensic.parseLine("system", line);
        check("clear parsed", c != null);
        check("clear action", c != null && Forensic.ACTION_CLEAR_DATA.equals(c.action));
        check("clear uid", c != null && c.uid == 10123);
        check("clear pid", c != null && c.pid == 789);
    }

    private static void testEventKill() {
        String line = "10-28 14:00:02.000  1000  2000 I am_force_stop: "
                + "[0,4321,jp.konami.pesam,1]";
        Forensic.Caller c = Forensic.parseLine("events", line);
        check("force stop parsed", c != null);
        check("force stop action", c != null && Forensic.ACTION_FORCE_STOP.equals(c.action));
        check("force stop pid", c != null && c.pid == 4321);
        check("force stop user", c != null && c.user == 0);
        check("force stop buffer", c != null && "events".equals(c.buffer));
    }

    private static void testActivityManagerKill() {
        String line = "10-28 14:00:03.000  1000  2000 I ActivityManager: "
                + "Killing 4321:jp.konami.pesam/u0a123 (adj 200): empty #17";
        Forensic.Caller c = Forensic.parseLine("main", line);
        check("kill parsed", c != null);
        check("kill action", c != null && Forensic.ACTION_KILL.equals(c.action));
        check("kill pid", c != null && c.pid == 4321);
        check("kill app uid", c != null && c.uid == 10123);
    }

    private static void testOldShellCaller() {
        String line = "10-28 14:00:04.000  1000  2000 I ActivityManager: "
                + "clearApplicationUserData [2003,1000,16] jp.konami.pesam";
        Forensic.Caller c = Forensic.parseLine("main", line);
        check("old caller parsed", c != null);
        check("old caller action", c != null && Forensic.ACTION_CLEAR_DATA.equals(c.action));
        check("old caller is shell", c != null && c.uid == 2000);
    }

    private static void testUnrelatedLine() {
        String line = "10-28 14:00:05.000  1000  2000 I ActivityManager: "
                + "Start proc 9999:com.example/u0a1 for activity";
        check("unrelated line ignored", Forensic.parseLine("main", line) == null);
    }

    private static void testManifestDiff() throws Exception {
        File root = tempDir("manifest");
        File files = new File(root, "files");
        File cache = new File(root, "cache");
        files.mkdirs();
        cache.mkdirs();
        write(new File(files, "save.dat"), 300000);
        write(new File(cache, "tmp.bin"), 1024);
        write(new File(root, "shared_prefs"), 0);

        Forensic.Manifest before = Forensic.Manifest.scan(root);
        check("manifest sees files", before.files >= 3);
        check("manifest bytes", before.totalBytes >= 301024L);

        new File(files, "save.dat").delete();
        new File(cache, "tmp.bin").delete();

        Forensic.Manifest after = Forensic.Manifest.scan(root);
        Forensic.Diff diff = Forensic.diff(before, after);
        check("diff sees two removals", diff.removed.size() == 2);
        check("diff counts one persistent", diff.removedPersistent == 1);
        check("diff persistent bytes", diff.removedPersistentBytes == 300000L);
        check("diff is significant", diff.significant());
        List<Forensic.Entry> sample = diff.sample(10);
        check("diff sample path", sample.size() == 1
                && "files/save.dat".equals(sample.get(0).path));

        Forensic.Manifest round = Forensic.Manifest.parse(before.toTsv());
        check("manifest roundtrip files", round.files == before.files);
        check("manifest roundtrip bytes", round.totalBytes == before.totalBytes);
    }

    private static void testManifestRoundTrip() throws Exception {
        File root = tempDir("roundtrip");
        write(new File(root, "files/a.bin"), 10);
        Forensic.Manifest m = Forensic.Manifest.scan(root);
        Forensic.Manifest p = Forensic.Manifest.parse(m.toTsv());
        check("roundtrip keeps one file", p.files == 1 && p.totalBytes == 10L);
        check("roundtrip path", p.entries.containsKey("files/a.bin"));
    }

    private static void testPersistentFilter() {
        check("cache is not persistent", !Forensic.isPersistent("cache/foo"));
        check("code_cache is not persistent", !Forensic.isPersistent("code_cache/foo"));
        check("files is persistent", Forensic.isPersistent("files/save.dat"));
        check("databases is persistent", Forensic.isPersistent("databases/game.db"));
        check("sentinel is not persistent", !Forensic.isPersistent(".factorypop_guard"));
    }

    private static void testAdvice() {
        Forensic.Caller shell = new Forensic.Caller("main", "PackageManager",
                Forensic.ACTION_UNINSTALL, "x", 2000, 12, 0);
        List<String> a = Forensic.stopAdvice(shell, null, "pid=12 uid=2000 comm=sh",
                "/data/adb/factorypop/snapshot/data.tar");
        check("shell advice mentions adb", contains(a, "adb"));
        check("advice mentions snapshot", contains(a, "data.tar"));

        Forensic.Caller pkg = new Forensic.Caller("main", "PackageManager",
                Forensic.ACTION_CLEAR_DATA, "y", 10123, 13, 0);
        List<String> b = Forensic.stopAdvice(pkg, "com.example.cleaner", null, null);
        check("package advice names package", contains(b, "com.example.cleaner"));

        Forensic.Caller root = new Forensic.Caller("main", "installd",
                Forensic.ACTION_DELETE_FILES, "z", 0, 14, 0);
        List<String> c = Forensic.stopAdvice(root, null, null, null);
        check("root advice is explicit", contains(c, "ROOT"));

        Forensic.Caller unknown = new Forensic.Caller("main", "?",
                Forensic.ACTION_DELETE_FILES, "z", -1, -1, -1);
        check("unknown advice still useful",
                !Forensic.stopAdvice(unknown, null, null, null).isEmpty());
    }

    private static boolean contains(List<String> list, String needle) {
        for (String s : list) {
            if (s.contains(needle)) { return true; }
        }
        return false;
    }

    private static File tempDir(String name) throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"),
                "fp-forensic-test-" + name + "-" + System.nanoTime());
        if (!d.mkdirs()) { throw new IllegalStateException("cannot create " + d); }
        d.deleteOnExit();
        return d;
    }

    private static void write(File f, int bytes) throws Exception {
        if (f.getParentFile() != null) { f.getParentFile().mkdirs(); }
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(new byte[bytes]); } finally { out.close(); }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }
}

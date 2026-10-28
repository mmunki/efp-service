import com.factorypop.efp.Forensic;
import com.factorypop.efp.FpForensics;
import com.factorypop.efp.FpUninstallGuard;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Host test for the Android-side forensic writer.
 *
 * tests/forensic-runtime-test.sh compiles the guard sources against the
 * official android.jar together with two tiny stubs (android.os.Process and
 * android.system.Os) and runs this class on the normal JVM.  That exercises
 * the real report writer, the manifest diff, the /proc scan and the log file.
 */
public final class FpForensicsRuntimeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        FpUninstallGuard guard = new FpUninstallGuard(null, "jp.konami.pesam");
        File data = guard.dataDir();
        File files = new File(data, "files");
        files.mkdirs();
        write(new File(files, "save.dat"), 300000);
        write(new File(data, ".factorypop_guard"), 1);

        File snap = guard.snapshotFile();
        snap.getParentFile().mkdirs();
        write(snap, 4096);

        Forensic.Manifest before = Forensic.Manifest.scan(data);
        new File(files, "save.dat").delete();
        Forensic.Manifest after = Forensic.Manifest.scan(data);

        Forensic.Caller caller = new Forensic.Caller("main", "PackageManager",
                Forensic.ACTION_UNINSTALL,
                "10-28 14:00:00 I PackageManager: Uninstall pkg: "
                        + "jp.konami.pesam from uid: 2000 pid: 1234",
                2000, 1234, 0);

        FpForensics forensics = guard.forensics();
        String report = forensics.capture("runtime-test", caller, before, after,
                true, snap, "host render test");

        check("report has event header", report.contains("DELETION EVENT"));
        check("report has trigger", report.contains("trigger          : runtime-test"));
        check("report lists removed file", report.contains("files/save.dat"));
        check("report has log evidence section", report.contains("LOG EVIDENCE"));
        check("report has process section", report.contains("PROCESSES"));
        check("report has culprit section", report.contains("CULPRIT"));
        check("report has stop section", report.contains("HOW TO STOP IT"));
        check("report carries caller uid", report.contains("uid=2000"));
        check("report says how to stop shell", report.contains("adb"));
        check("report has restore hint", report.contains("restore"));

        check("uninstall.log exists", forensics.logFile().isFile());
        String log = readAll(forensics.logFile());
        check("log has session header", log.contains("FactoryPop forensics"));
        check("log has event", log.contains("DELETION EVENT"));
        check("log has removed file", log.contains("files/save.dat"));
        check("log has trigger", log.contains("runtime-test"));
        check("log path ends with uninstall.log",
                forensics.logFile().getAbsolutePath().endsWith("/uninstall.log"));
        check("default forensic dir is the requested path",
                "/data/adb/jp.konami.pes".equals(Forensic.DEFAULT_DIR));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) { System.exit(1); }
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = br.readLine()) != null) { sb.append(line).append('\n'); }
        } finally {
            br.close();
        }
        return sb.toString();
    }

    private static void write(File f, int bytes) throws Exception {
        if (f.getParentFile() != null) { f.getParentFile().mkdirs(); }
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
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

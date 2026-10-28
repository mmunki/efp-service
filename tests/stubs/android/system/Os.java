package android.system;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Host-test stub for android.system.Os (tests/forensic-runtime-test.sh). */
public final class Os {
    public static void chmod(String path, int mode) {}

    public static String readlink(String path) {
        try {
            return Files.readSymbolicLink(Paths.get(path)).toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void kill(int pid, int signal) {}
}

package com.factorypop.efp.tool;

import com.factorypop.efp.FpUninstallGuard;

/**
 * FactoryPop :: eFootball protection tool, the entry point that the shell uses
 * through the Android runtime:
 *
 *   CLASSPATH=<services.jar> app_process /system/bin com.factorypop.efp.tool.BlockUninstall \
 *       <pkg> <true|false|get> 0
 */
public class BlockUninstall {

    public static void main(String[] args) {
        com.factorypop.efp.Platform.exemptHiddenApis();

        String pkg = (args != null && args.length > 0) ? args[0] : null;
        if (pkg == null || pkg.trim().isEmpty()) {
            String e = System.getenv("FACTORYPOP_PKG");
            pkg = (e != null && !e.trim().isEmpty()) ? e.trim() : FpUninstallGuard.DEFAULT_PKG;
        }
        String op = (args != null && args.length > 1) ? args[1] : "get";

        FpUninstallGuard guard = new FpUninstallGuard(FpUninstallGuard.acquireContext(), pkg);

        boolean installed = guard.isInstalled();
        boolean blocked;
        if ("true".equals(op)) {
            blocked = guard.setUninstallBlocked(true);
            guard.writeLocked(true);
        } else if ("false".equals(op)) {
            blocked = guard.setUninstallBlocked(false);
            guard.writeLocked(false);
        } else {
            blocked = guard.readLocked();
        }

        System.out.println("installed=" + installed + " blocked=" + blocked + " pkg=" + pkg);
    }
}

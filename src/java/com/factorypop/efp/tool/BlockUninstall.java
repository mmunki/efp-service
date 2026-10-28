package com.factorypop.efp.tool;

import com.factorypop.efp.FpUninstallGuard;

/**
 * FactoryPop :: eFootball protection tool, the entry point that the shell uses
 * through the Android runtime:
 *
 *   CLASSPATH=<services.jar> app_process /system/bin com.factorypop.efp.tool.BlockUninstall \
 *       <pkg> <true|false> 0
 *
 * <pkg>   protected package (default jp.konami.pesam)
 * <op>    true  -> block the uninstall
 *         false -> allow the uninstall (consumed by an unlock)
 *         get   -> report the current lock state
 */
public class BlockUninstall {

    public static void main(String[] args) {
        if (args == null || args.length < 1) { return; }
        String pkg = args[0];
        String op = args.length > 1 ? args[1] : "get";
        boolean lock = "true".equals(op);

        FpUninstallGuard guard = new FpUninstallGuard(FpUninstallGuard.currentContext(), pkg);
        boolean installed = guard.isInstalled();
        boolean blocked = guard.setUninstallBlocked(lock);
        String out = "installed=" + installed + " blocked=" + blocked + " pkg=" + pkg;
        System.out.println(out);
    }
}

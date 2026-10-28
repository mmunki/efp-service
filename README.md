# efp-service

FactoryPop :: eFootball (JP Konami `jp.konami.pesam`) protection.

## What this is

Three layers of protection for the eFootball data:

1. **Uninstall lock** - the platform level block (`PackageManager` block
   uninstall for the user).  Enforced by the Java code in the services jar.
2. **Command guard** - `/system/bin/cmd` shell wrapper that refuses
   `pm` / `am` / `cmd` clear / uninstall / disable / hide / suspend and
   force-stop / kill calls against the protected package.
3. **Data safety net** - snapshot of `/data/data/<pkg>` + auto restore when a
   wipe is detected.

## The service (services.jar)

The daemon that used to live only as the shell script (`fp-test-guard.sh`) is
now implemented as real Android sources:

```
src/
  AndroidManifest.xml
  java/com/factorypop/efp/FpUninstallGuard.java
  java/com/factorypop/efp/FpUninstallGuardService.java
  java/com/factorypop/efp/tool/BlockUninstall.java
build.sh
dist/services.jar
```

`FpUninstallGuard` holds the protection state machine (installed check, data
snapshot, data restore, uninstall block) and `FpUninstallGuardService` runs it
as an Android service on a `HandlerThread`.  The tool
`com.factorypop.efp.tool.BlockUninstall` is the entry point the shell calls
through the Android runtime:

```
CLASSPATH=<services.jar> app_process /system/bin \
    com.factorypop.efp.tool.BlockUninstall <pkg> <true|false> 0
```

`build.sh` compiles the sources with the official Android build toolchain
(`android-sdk-mini`: aapt2, javac, d8) against the official `android.jar` and
packages the result as `dist/services.jar`.

The service runs from the services jar, so the protection keeps working without
any command in `/system/bin`: the system starts the service and the service
talks to the platform itself.  The `cmd` shell wrapper is therefore no longer part
of the automatic protection path.

## Manual operations

The shell daemon is kept for manual operations:

```
sh fp-test-guard.sh --status
sh fp-test-guard.sh --unlock
sh fp-test-guard.sh --lock
sh fp-test-guard.sh --snapshot
sh fp-test-guard.sh --restore
sh fp-test-guard.sh --restore-old
```

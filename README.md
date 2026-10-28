# efp-service

FactoryPop :: eFootball (JP Konami `jp.konami.pesam`) protection.

This release is **boot-safe**.  It ships the guard as an ordinary jar in the
Magisk module directory and loads it with `app_process` on top of the stock
framework.  It does **not** mount anything over
`/system/framework/services.jar`.

That last point is the fix for the bootloop on the Redmi Note 14 4G: the old
0.3.0 module replaced the ROM's framework `services.jar` with a jar built from
a different/merged copy.  `services.jar` is a boot class path file of the ROM,
so a mismatch there makes `system_server` fail and the device stays on the boot
animation.  The stock file is never modified on disk; removing the module
restores boot immediately.

## What is inside

```
src/
  AndroidManifest.xml
  java/com/factorypop/efp/Platform.java              platform access (no cmd)
  java/com/factorypop/efp/FpUninstallGuard.java      state machine
  java/com/factorypop/efp/FpGuardLoop.java           the fp-test-guard daemon
  java/com/factorypop/efp/FpForensics.java           uninstall.log writer + /proc scan
  java/com/factorypop/efp/Forensic.java              pure Java parser + manifest diff
  java/com/factorypop/efp/FpUninstallGuardService.java  service host
  java/com/factorypop/efp/tool/BlockUninstall.java   manual lock/unlock tool
  java/com/factorypop/efp/tool/GuardDaemon.java      boot entry point
build.sh             compile the guard jar (standalone by default)
make-module.sh       package the guard jar as a boot-safe Magisk module
tests/verify.sh      build + module layout checks (fails on any regression)
tests/forensic-test.sh  host tests for the parser and manifest diff
tests/forensic-runtime-test.sh  host test for the log writer and report
module/              module.prop, service.sh, installer stub
merge-services.sh    expert path only: merge the guard into a framework jar
fp-test-guard.sh     manual operations (status/unlock/lock/snapshot/restore)
cmd, cmd.real        the old cmd wrapper, kept for reference - not needed
```

## How the protection works

The guard runs as root through `app_process` and reaches the platform through
the same binder interfaces `cmd` / `pm` / `am` use:

* uninstall lock: `IPackageManager.setBlockUninstallForUser`
* force-stop: `IActivityManager.forceStopPackage`
* package state / first install time / uid: `IPackageManager`
* process state: `/proc`
* data snapshot / restore: toybox `tar`, `chown`, `restorecon`

No command in `/system/bin` is installed or called on the protection path.
There is no `cmd` wrapper, no `pm`, no `am`.

## The forensic log: backup + who deleted it

Starting with 0.5.0 the guard keeps a forensic log at:

```
/data/adb/jp.konami.pes/uninstall.log
```

It is written even when `lock.log` has already scrolled away and it contains
both halves of the request:

**The backup process**

* `BACKUP START` - trigger, data path, free space.
* `BACKUP DONE` - bytes, size in MB, SHA-256, duration.
* `BACKUP SKIPPED` / `BACKUP FAILED` - the exact reason.
* `MANIFEST` - the file list saved next to the snapshot
  (`/data/adb/factorypop/snapshot/manifest.tsv`), which is what makes a
  precise diff possible.

**The deletion process**

Every tick compares the data directory with the last manifest.  When the
sentinel disappears, the package is removed, or persistent files are deleted
while the game is stopped, the guard writes a `DELETION EVENT` block:

* the trigger and the exact files that disappeared (path + size + mtime);
* the logcat **buffer** and **exact line** that reported the attempt, for the
  `events`, `main`, `system`, `crash` and `kernel` buffers;
* every recorded caller line with uid, pid and user (`Uninstall pkg ... from
  uid:`, `clearApplicationUserData ... callingUid=`, `am_force_stop: [user,pid]`,
  `Killing pid:pkg/u0aNN`);
* a `/proc` scan: pid, uid, ppid, comm, exe, cwd, cmdline and every file
  descriptor that points below `/data/data/jp.konami.pesam`.  A descriptor
  marked `(deleted)` is direct evidence of the process that deleted the data;
* the likely culprit and the package that owns its uid;
* a **HOW TO STOP IT** section tailored to the caller: root app/module,
  adb/root shell, a specific app uid, or an unknown process with open deleted
  files, plus where the backup is and how the lock comes back;
* `RESTORE START` / `RESTORE DONE` when the snapshot is put back.

The log is mode 0600 in a 0700 directory and rotates to `uninstall.log.1` at
8 MB.  View it from a root shell:

```bash
sh fp-test-guard.sh --uninstall-log
```

or open `/data/adb/jp.konami.pes/uninstall.log` directly.  `--status` now
prints the path too.

The write path is best effort on purpose: if `/data/adb` is not writable the
protection keeps running and only the forensic entry is lost.

## Build

```bash
# the guard jar (small, only the guard classes)
./build.sh --standalone --out dist/services-guard.jar

# build + module layout checks
tests/verify.sh

# Magisk module around the guard jar
./make-module.sh dist/services-guard.jar 0.4.0
```

`./build.sh` needs `ANDROID_SDK_ROOT` with the platform `android.jar` and
`build-tools` (`d8`, `dexdump`).  The toolkit
[`mmunki/android-sdk-mini`](https://github.com/mmunki/android-sdk-mini) works,
the standard Android SDK does too.

## Install

`make-module.sh` produces `dist/FactoryPop-<version>.zip` with this layout:

```
module.prop
service.sh
services.jar                     <- the guard jar, in the module directory
META-INF/com/google/android/...  <- Magisk installer
```

There is deliberately no `system/` tree.  `service.sh` starts:

```sh
CLASSPATH=$MODDIR/services.jar app_process /system/bin \
    --nice-name=factorypop com.factorypop.efp.tool.GuardDaemon --pkg jp.konami.pesam
```

## If the device is already stuck on the boot animation

The previous 0.3.0 module mounted a modified framework jar.  Nothing was
written to the stock partition, so disabling/removing the module is enough:

1. **Magisk safe mode**: hold **Volume Down** while the device boots (start
   holding as soon as the boot logo appears, keep holding until the lock
   screen / home screen).  Magisk disables all modules for that boot.
2. Once Android is up, delete the module directory (root shell):

   ```sh
   su -c 'rm -rf /data/adb/modules/factorypop-efp'
   ```

   or disable it for the next boot:

   ```sh
   su -c 'touch /data/adb/modules/factorypop-efp/disable'
   ```

3. Reboot and confirm the phone starts normally.
4. Flash the 0.4.0 module.  It only adds `services.jar` inside its own module
   directory and cannot affect `system_server`.

If recovery (TWRP / OrangeFox / Magisk recovery) is available, the same two
commands work through its terminal or `adb shell` while `/data` is mounted.

## Manual operations

```bash
sh fp-test-guard.sh --status
sh fp-test-guard.sh --unlock
sh fp-test-guard.sh --lock
sh fp-test-guard.sh --snapshot
sh fp-test-guard.sh --restore
sh fp-test-guard.sh --restore-old
sh fp-test-guard.sh --uninstall-log
```

The script uses `$MODDIR/services.jar` (the guard jar in the module directory),
and its status/lock calls go through
`com.factorypop.efp.tool.BlockUninstall` - no `pm`, no `am`, no `cmd`.

## Expert path: merged framework jar

`merge-services.sh` and `build.sh --base` still exist for people who explicitly
want the guard inside a framework jar.  `make-module.sh` refuses such a jar and
will not package it, because replacing `/system/framework/services.jar` is what
caused the bootloop.  If you use that path, you own the ROM matching problem:
the base jar must come from the exact ROM/build on the device, and the
pre-optimised `services.odex` / `services.vdex` can still reject the change.

## Verified

`tests/verify.sh` checks:

1. the guard jar builds with `aapt2` + `javac` + `d8` against API 36,
2. all eight guard classes are in the dex and the dex checksum is valid,
3. the guard dex contains no `cmd`, `pm` or `am` invocation,
4. the guard dex carries `/data/adb/jp.konami.pes` and `uninstall.log`,
5. the pure-Java forensic parser and manifest diff pass their host tests
   (`tests/forensic-test.sh`: uninstall/clear/force-stop event parsing, uid/pid
   extraction, manifest diff, advice generation),
6. the real forensic writer runs on the host (`tests/forensic-runtime-test.sh`:
   report sections, caller uid, removed file list, `/proc` and log file path),
7. the guard jar has exactly one dex and is the jar packaged in the module,
8. the module has **no `system/` tree** and therefore never replaces
   `/system/framework/services.jar`,
9. `service.sh` starts `GuardDaemon` from the module jar with `app_process`,
10. `make-module.sh` refuses a multi-dex framework/merged jar.

The dex format is architecture independent, so there is no ABI matrix for pure
Java code.  The device-side boot itself cannot be run in CI; the boot-safety
comes from not touching any boot class path file at all.

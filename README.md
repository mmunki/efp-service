# efp-service

FactoryPop :: eFootball (JP Konami `jp.konami.pesam`) protection, packaged as a
**merged services.jar**.

The daemon that used to live as the shell script `fp-test-guard.sh` is now Java
code inside the services jar.  The jar in this project is the *given* framework
`services.jar` with the guard dex appended - not a new, small jar that replaces
it:

```
classes.dex      } unchanged framework dex files
classes2.dex     }
classes3.dex     }
classes4.dex     }
classes5.dex     <-- the guard (FpUninstallGuard, FpGuardLoop,
                     FpUninstallGuardService, BlockUninstall, GuardDaemon)
META-INF, ...    unchanged framework resources
```

Every original entry of the given jar is preserved byte for byte (same CRC-32,
same size, same compression method); the guard is one additional dex file that
ART loads together with the others.

## Why the merge

A framework `services.jar` contains the whole `com.android.server` framework.  A
jar with only the guard classes cannot replace it - the system would lose its
framework.  At the same time the old setup needed a `cmd` wrapper in
`/system/bin` (plus `pm`/`am`, which are only shell wrappers around `cmd`) to
apply the lock.  The merge removes both problems:

* the protections still run from `services.jar`, the system file keeps working,
* the guard reaches the platform through `IPackageManager` and
  `IActivityManager` - the same binder interfaces `cmd`/`pm`/`am` use - so no
  command in `/system/bin` is needed.

`Platform.java` is that layer: `setBlockUninstallForUser` through
`IPackageManager`, `forceStopPackage` through `IActivityManager`, package state
through the platform, process state from `/proc`.

## What is inside

```
src/
  AndroidManifest.xml
  java/com/factorypop/efp/Platform.java              platform access (no cmd)
  java/com/factorypop/efp/FpUninstallGuard.java      state machine
  java/com/factorypop/efp/FpGuardLoop.java           the fp-test-guard daemon
  java/com/factorypop/efp/FpUninstallGuardService.java  service host
  java/com/factorypop/efp/tool/BlockUninstall.java   manual lock/unlock tool
  java/com/factorypop/efp/tool/GuardDaemon.java      boot entry point
build.sh             compile + (with --base) merge
merge-services.sh    merge the guard into a given services.jar
make-module.sh       package the merged jar as a Magisk module
tests/verify.sh      build + merge + verify (fails on any regression)
module/              module.prop, service.sh, installer stub
fp-test-guard.sh     manual operations (status/unlock/lock/snapshot/restore)
cmd, cmd.real        the old cmd wrapper, kept for reference - not needed
```

## Build

```bash
# merged services.jar (the deliverable)
./build.sh --base services.jar --out dist/services.jar

# standalone guard jar, useful for tests
./build.sh --standalone --out dist/services-guard.jar

# build + merge + verify, exits non-zero on any failure
tests/verify.sh services.jar

# Magisk module around the merged jar
./make-module.sh dist/services.jar 0.3.0
```

`./build.sh --base ...` needs `ANDROID_SDK_ROOT` with the platform `android.jar`
and `build-tools` (`d8`, `dexdump`).  The toolkit
[`mmunki/android-sdk-mini`](https://github.com/mmunki/android-sdk-mini) works,
the standard Android SDK does too.

## Install

### Magisk module (recommended)

`make-module.sh` produces `dist/FactoryPop-<version>.zip`:

* `system/framework/services.jar` - the merged jar, mounted by Magisk over the
  framework jar,
* `service.sh` - starts the guard from that jar at boot:

  ```sh
  CLASSPATH=/system/framework/services.jar app_process /system/bin \
      --nice-name=factorypop com.factorypop.efp.tool.GuardDaemon
  ```

No `cmd` wrapper, no `pm`, no `am` on the protection path.

### Manual

```bash
# one protection cycle, useful to check the setup
CLASSPATH=/system/framework/services.jar app_process /system/bin \
    com.factorypop.efp.tool.GuardDaemon --status

# run it (root)
CLASSPATH=/system/framework/services.jar app_process /system/bin \
    --nice-name=factorypop com.factorypop.efp.tool.GuardDaemon &
```

`GuardDaemon` waits for `sys.boot_completed`, takes a pid lock in
`/data/adb/factorypop/lock.lock`, runs the same loop as the old shell daemon and
keeps a log in `/data/adb/factorypop/lock.log`.

> **Note on replacing the framework jar.** Mounting a modified
> `/system/framework/services.jar` changes a boot class path file, so ART sees
> a checksum mismatch for the pre-optimised code and falls back to the dex on
> first boot (a slower first boot).  If a device refuses it, disable the module
> from recovery (or with the volume keys during boot) - the stock
> `services.jar` is not modified on disk.
>
> The protection itself does **not** require the framework file to be
> replaced: the same merged jar can live in the module directory and be used as
> `CLASSPATH` only.

## Manual operations

```bash
sh fp-test-guard.sh --status
sh fp-test-guard.sh --unlock
sh fp-test-guard.sh --lock
sh fp-test-guard.sh --snapshot
sh fp-test-guard.sh --restore
sh fp-test-guard.sh --restore-old
```

The script now uses the guard in the services jar (`$MODDIR/services.jar`, or
`/system/framework/services.jar`), and its status/lock calls go through
`com.factorypop.efp.tool.BlockUninstall` - no `pm`, no `am`, no `cmd`.

## Verified

`tests/verify.sh` checks, against the given services.jar:

1. the guard jar builds with `aapt2` + `javac` + `d8` against API 36,
2. all six guard classes are in the dex and the dex checksum is valid,
3. the guard dex contains no `cmd`, `pm` or `am` invocation,
4. the merge preserves all 194 original entries (CRC-32, size, compression),
5. `classes.dex`..`classes4.dex` keep their exact SHA-256,
6. `classes5.dex` is valid, stored uncompressed, and contains `GuardDaemon`,
7. the merged archive is a healthy zip with contiguous dex slots.

The dex format is architecture independent, so there is no ABI matrix to run
for Java code; the merge and the dex checks run in CI on every push.

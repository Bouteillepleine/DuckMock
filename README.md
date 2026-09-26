# DuckMock

Hides mock location from every app on the device, with hooks that live **only inside
system_server**. No LSPosed, and no injection into any app process.

Replaces [HideMockLocation](https://github.com/auag0/HideMockLocation) for people who want
the behaviour without keeping an Xposed framework installed.

## Why system_server only

HideMockLocation hooks `android.location.Location` in every scoped app, because that is
where an app reads `isMock()`. But the bit it reads is **stamped by system_server**, in
`MockLocationProvider`, before the `Location` is parcelled out. Clearing it at that single
point covers every app at once:

| Vector | Where DuckMock handles it |
|---|---|
| `Location.isMock()` / `isFromMockProvider()` | `Location.setMock` / `setIsFromMockProvider` forced to `false` in system_server, so the field mask never carries `HAS_MOCK_PROVIDER_MASK` |
| Odd mock provider names | renamed to `gps` at the same interception point |
| `android:mock_location` app-op | `AppOpsService.checkOperationImpl`, gated on `Binder.getCallingUid()` |
| `Settings.Secure.mock_location` | `SettingsProvider.call`, plus the `query` cursor path |

The app-op gate is the part that cannot be done from inside an app: a client-side hook
cannot tell *who* is asking, so it also lies to the spoofer and breaks it. That is why
upstream disabled its own app-ops hooks in v2.3.1. From system_server the caller is known,
so the system and your spoofer keep the truth while everyone else is told the op is held by
nobody.

The cost of zero injection: nothing runs in the target app, so there is nothing for a
memory or linker-namespace scan to find.

## What actually changes

Measured on a OnePlus 15 (CPH2747, Android 16 / SDK 36, kernel 6.12.58, KernelSU + ReZygisk),
with a fake position injected from the shell:

```
                     module inactive          module active
gps                  isMock=true              isMock=false
fused                isMock=true              isMock=false
passive              isMock=true              isMock=false
secure/mock_location 0                        0
app-op (own)         ERRORED                  ERRORED
```

Two honest notes from that table:

* `Settings.Secure.mock_location` has read `0` since Android 6 whatever you do. The switch
  is there for old detectors only.
* An app may only ask app-ops about **itself** — `AppOpsService.verifyIncomingUid` refuses
  any other uid without `UPDATE_APP_OPS_STATS`. So the app-op gate matters for an app
  checking whether it is itself the mock app, and for not leaking your spoofer. It is not
  the main vector.

**The location marker is the vector that matters**, and it is the one that needed per-app
injection until now.

### Known residuals

* GNSS-level detection. An app that watches `GnssStatus` can notice a fix arriving with no
  satellites behind it. DuckMock does not touch that.
* The `mockLocation` bundle extra is a Google Play services convention, not a framework one
  (the string does not appear in `framework.jar`). GMS only adds it after seeing a marked
  location, so clearing the marker covers it transitively — but it is not clipped directly
  in third-party processes.

## Joystick

The manager can walk the fake position instead of pinning it. A thumbstick floats over
whatever app is in front; pushing it moves the position in that direction, and the fix then
carries a matching `bearing` and `speed` instead of the zeroes a stationary spoof reports.

| Control | What it does |
|---|---|
| Stick | Direction, and by how far you push it, the fraction of the pace |
| Pace chip | Stroll 0.8, Walk 1.4, Jog 3.1, Cycle 6.0, Drive 13.9 m/s |
| Handle | Drags the window anywhere; the spot is remembered |
| `–` | Folds it to a bar, which also stops the walk |
| Latch | Keeps walking after your thumb leaves the stick |

The same pad is in the Position tab for use without the overlay. The stick belongs to the
spoof service, so it arrives when spoofing starts and leaves when it stops. While you are
moving, the wander jitter is off — movement already varies the fix — and each step is
written back to the target, so stopping leaves you where you walked to instead of snapping
back to where you set off.

The overlay needs `SYSTEM_ALERT_WINDOW`, which Android grants by hand. One honest note: a
window sitting on top of another app shows up to that app as an obscured touch
(`MotionEvent.FLAG_WINDOW_IS_OBSCURED`) when it covers where you tap, and a few apps refuse
input in that case. Park it to one side, or fold it away before tapping.

## Layout

```
common/   config, AIDL, shared constants
zygote/   the Zygisk module: system_server hooks, native LSPlant glue, flashable zip
app/      the manager (Material 3, root, talks to the module over a binder bridge)
probe/    a plain unprivileged app that reports what a detector would see
tools/    verify.sh, the on-device before/after check
external/ LSPlant, Dobby, xz-embedded (submodules, pinned)
```

## Building

Needs the Android SDK with NDK 29.0.14206865 and CMake 3.31.6 (LSPlant needs CMake >= 3.28
for C++23 modules), plus JDK 21.

```
git submodule update --init --recursive
./gradlew :zygote:assembleRelease :app:assembleRelease :probe:assembleDebug
```

Outputs:

* `zygote/build/outputs/magisk/release/DuckMock-<version>-release.zip` — flash in KernelSU,
  Magisk or APatch
* `app/build/outputs/apk/release/app-release.apk` — the manager
* `probe/build/outputs/apk/debug/probe-debug.apk` — the probe

`key.properties` and `duckmock.jks` sign the manager. They are gitignored; drop your own in
the repo root, or set `DUCKMOCK_STORE_FILE` / `DUCKMOCK_STORE_PASSWORD` /
`DUCKMOCK_KEY_ALIAS` / `DUCKMOCK_KEY_PASSWORD`.

## Safety

Hooking system_server can cost a boot. Three guards:

* `post-fs-data.sh` counts boots and writes `disable_hooks` after three failures
* `service.sh` waits for `sys.boot_completed`; if it never arrives within 150 s it writes
  `disable_hooks` and reboots
* hooks arm only after `sys.boot_completed` plus five seconds, never during boot

Last resort is KernelSU safe mode: hold volume down while booting, which disables every
module.

## Verifying

```
adb push tools/verify.sh /data/local/tmp/verify.sh
adb shell su -c 'sh /data/local/tmp/verify.sh'
```

It prints the module state, what system_server logged, injects a fake position, asks the
probe what it sees, and removes the fake provider again.

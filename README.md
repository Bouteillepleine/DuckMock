# DuckMock

A Zygisk module that hides mock location from every app on the device, with hooks that live
**only inside system_server** — no LSPosed, and nothing injected into any app process. The
manager app is also the spoofer: fixed position, joystick, or a route.

## Why system_server only

The `isMock` bit an app reads is stamped by system_server, in `MockLocationProvider`, before
the `Location` is parcelled out. Clearing it there covers every app at once.

| Vector | Handled in |
|---|---|
| `Location.isMock()` / `isFromMockProvider()` | `Location.setMock` / `setIsFromMockProvider` forced false, so the field mask never carries `HAS_MOCK_PROVIDER_MASK` |
| Odd mock provider names | renamed to `gps` at the same point |
| `android:mock_location` app-op | `AppOpsService.checkOperationImpl`, gated on `Binder.getCallingUid()` |
| `Settings.Secure.mock_location` | `SettingsProvider.call` and the `query` cursor path |

The app-op gate cannot be done from inside an app: a client-side hook cannot tell who is
asking, so it lies to the spoofer too and breaks it. From system_server the caller is known.
And with nothing running in the target app, there is nothing for a memory or
linker-namespace scan to find.

Measured on a OnePlus 15 (Android 16, kernel 6.12.58, KernelSU + ReZygisk): `gps`, `fused`
and `passive` all go from `isMock=true` to `isMock=false` with the module active.

Two honest notes: `Settings.Secure.mock_location` has read `0` since Android 6 whatever you
do, and an app may only ask app-ops about itself. The location marker is the vector that
matters.

**Residuals:** GNSS-level detection — an app watching `GnssStatus` can notice a fix with no
satellites behind it (the synthetic sky switch covers the counts, not ephemeris). And the
`mockLocation` bundle extra, a GMS convention, is only cleared transitively.

## Moving the position

The **Joystick** tab holds a thumbstick that floats over whatever app is in front. Pushing
it walks the position at the chosen pace (Stroll 0.8 → Drive 13.9 m/s), and the fix carries
a real `bearing` and `speed` instead of zeroes. Drag the handle to move the window, tap the
pace chip to change it, `–` to fold it away, latch to keep walking after your thumb leaves.

Routes live on the same tab. Record the path you drive with the stick, or import a GPX file
(`<trkpt>`, else `<rtept>`, else `<wpt>`); position, bearing and altitude are interpolated
along each leg. `Stop`, `Loop` or `Bounce` at the last point. Export writes GPX 1.1 back
out. Routes are JSON under the manager's `files/routes/` and go when it is uninstalled.

The overlay needs `SYSTEM_ALERT_WINDOW`. A window over another app reads as an obscured
touch (`MotionEvent.FLAG_WINDOW_IS_OBSCURED`) where it covers your tap, and some apps refuse
input then — park it aside or fold it. Wander jitter is off while moving, since motion
already varies the fix.

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

Needs the Android SDK with NDK 29.0.14206865 and CMake 3.31.6 (LSPlant needs CMake >= 3.28),
plus JDK 21.

```
git submodule update --init --recursive
./gradlew :zygote:assembleRelease :app:assembleRelease :probe:assembleDebug
```

Outputs: `zygote/build/outputs/magisk/release/DuckMock-<version>-release.zip` to flash in
KernelSU, Magisk or APatch, and `app/build/outputs/apk/release/app-release.apk`.

`key.properties` and `duckmock.jks` sign the manager and are gitignored; drop your own in the
repo root, or set `DUCKMOCK_STORE_FILE` / `DUCKMOCK_STORE_PASSWORD` / `DUCKMOCK_KEY_ALIAS` /
`DUCKMOCK_KEY_PASSWORD`.

## Safety

Hooking system_server can cost a boot, so `post-fs-data.sh` writes `disable_hooks` after
three failed boots, `service.sh` does the same if `sys.boot_completed` never arrives within
150 s, and hooks arm only five seconds after boot completes. Last resort is KernelSU safe
mode: hold volume down while booting.

## Verifying

```
adb push tools/verify.sh /data/local/tmp/verify.sh
adb shell su -c 'sh /data/local/tmp/verify.sh'
```

It prints the module state, injects a fake position, asks the probe what it sees, and
removes the fake provider again.

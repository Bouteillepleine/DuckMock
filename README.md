# DuckMock

A Zygisk module that hides mock location from every app on the device, with hooks that live
**only inside system_server**. The manager app can also be used as a spoofer, with a fixed
position, a joystick or a route. Any other GPS app can be declared the spoofer instead, and
it alone keeps being told the truth.

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

**Residuals:** GNSS-level detection, where an app watching `GnssStatus` can notice a fix
with no satellites behind it (the synthetic sky switch covers the counts, not ephemeris).
And the `mockLocation` bundle extra, a GMS convention, is only cleared transitively.

## Moving the position

The **Joystick** tab holds a thumbstick that floats over whatever app is in front. Pushing it
walks the position at the chosen pace (Stroll 0.8 to Drive 13.9 m/s), and the fix carries a
real `bearing` and `speed` instead of zeroes. Drag the handle to move it, tap the pace to
change it, `⤡` to resize, `–` to fold, `✕` to close, latch to keep walking after your thumb
leaves. It is off until you turn it on, and steps aside while DuckMock itself is open.

Routes live on the same tab. Record the path you drive with the stick, or import a GPX file
(`<trkpt>`, else `<rtept>`, else `<wpt>`); position, bearing and altitude are interpolated
along each leg. `Stop`, `Loop` or `Bounce` at the last point. Export writes GPX 1.1 back
out. Routes are JSON under the manager's `files/routes/` and go when it is uninstalled.

The overlay needs `SYSTEM_ALERT_WINDOW`, and a window over another app reads as an obscured
touch (`MotionEvent.FLAG_WINDOW_IS_OBSCURED`) where it covers your tap, which a few apps
refuse. Wander jitter is off while moving, since motion already varies the fix.

## Layout

```
common/   config, AIDL, shared constants
zygote/   the Zygisk module: system_server hooks, native LSPlant glue, flashable zip
app/      the manager (Material 3, root, talks to the module over a binder bridge)
probe/    a plain unprivileged app that reports what a detector would see
tools/    verify.sh, plus the release-key and CI-secret scripts
external/ LSPlant, Dobby, xz-embedded (submodules, pinned)
```

## Safety

Hooking system_server can cost a boot, so `post-fs-data.sh` writes `disable_hooks` after
three failed boots, `service.sh` does the same if `sys.boot_completed` never arrives within
150 s, and hooks arm only five seconds after boot completes. Last resort is KernelSU safe
mode: hold volume down while booting.

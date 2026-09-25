#!/system/bin/sh
# Push to /data/local/tmp and run as root. Injects a fake position from the
# shell, asks DuckProbe what it sees, then removes the fake provider again.
#
#   adb push tools/verify.sh /data/local/tmp/verify.sh
#   adb shell su -c 'sh /data/local/tmp/verify.sh'

MODDIR=/data/adb/modules/duckmock
PROBE=com.strawing.duckprobe

echo "=== module ==="
if [ -d "$MODDIR" ]; then
    grep -m1 '^description=' "$MODDIR/module.prop"
    [ -f "$MODDIR/disable_hooks" ] && echo "kill switch: ON" || echo "kill switch: off"
    [ -f "$MODDIR/boot_attempts" ] && echo "boot attempts: $(cat "$MODDIR/boot_attempts")"
    echo "--- config ---"
    cat "$MODDIR/config.json" 2>/dev/null
else
    echo "not installed at $MODDIR"
    [ -d /data/adb/modules_update/duckmock ] && echo "staged in modules_update, reboot to activate"
fi

echo
echo "=== what system_server logged this boot ==="
logcat -d -s DuckMock:I DuckMock:E | tail -25

echo
echo "=== injecting a fake position ==="
appops set com.android.shell android:mock_location allow
cmd location providers add-test-provider gps
cmd location providers set-test-provider-enabled gps true
cmd location providers set-test-provider-location gps --location 48.8566,2.3522
sleep 1

echo
echo "=== the framework's own view ==="
dumpsys location 2>/dev/null | grep -m2 'last location=Location\[gps'

echo
echo "=== what an ordinary app sees ==="
logcat -c
am broadcast -a $PROBE.RUN -n $PROBE/.ProbeReceiver >/dev/null 2>&1
sleep 2
logcat -d -s DuckProbe:I | sed 's/^.*DuckProbe: //'

echo
echo "=== cleaning up ==="
cmd location providers remove-test-provider gps
echo "test provider removed"

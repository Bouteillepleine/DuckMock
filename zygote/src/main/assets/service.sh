MODDIR=${0%/*}
LIMIT=150
SETTLE=180

i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    i=$((i + 1))
    if [ "$i" -ge "$LIMIT" ]; then
        touch "$MODDIR/disable_hooks"
        chmod 0644 "$MODDIR/disable_hooks"
        log -t DuckMock "boot did not complete in ${LIMIT}s, hooks disabled"
        setprop sys.powerctl reboot
        exit 0
    fi
    sleep 1
done

sleep "$SETTLE"
rm -f "$MODDIR/boot_attempts"
log -t DuckMock "up for ${SETTLE}s, attempt counter cleared"

sh "$MODDIR/describe.sh" 2>/dev/null

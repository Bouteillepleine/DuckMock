MODDIR=${0%/*}
PROP="$MODDIR/module.prop"
CONFIG="$MODDIR/config.json"

[ -f "$PROP" ] || exit 0

flag() {
    [ -f "$CONFIG" ] || return 1
    grep -q "\"$1\"[[:space:]]*:[[:space:]]*true" "$CONFIG"
}

if [ -f "$MODDIR/disable_hooks" ]; then
    STATUS="⛔ Hooks disabled"
    DETAIL="Every app reads the truth. Clear the kill switch, then reboot"
elif ! [ -f "$CONFIG" ]; then
    STATUS="⚠️ No configuration"
    DETAIL="Open the manager once to write one"
elif flag paused; then
    STATUS="⏸️ Paused"
    DETAIL="Hooks are loaded but every app reads the truth"
else
    PARTS=""
    flag hideLocationFlag && PARTS="mock flag"
    if flag hideAppOps; then
        [ -n "$PARTS" ] && PARTS="$PARTS, app-ops" || PARTS="app-ops"
    fi
    if flag hideSettingsKey; then
        [ -n "$PARTS" ] && PARTS="$PARTS, settings key" || PARTS="settings key"
    fi
    if [ -z "$PARTS" ]; then
        STATUS="⚠️ Nothing enabled"
        DETAIL="Every switch is off"
    else
        STATUS="✅"
        DETAIL="Hiding $PARTS from every app"
        flag grantMockOp && DETAIL="$DETAIL, mock op granted without the picker"
    fi
fi

DESC="[$STATUS] $DETAIL. Checked $(date '+%d %b %H:%M')."

TMP="$MODDIR/.module.prop.new"
awk -v d="$DESC" '/^description=/ { print "description=" d; found = 1; next } { print }
    END { if (!found) print "description=" d }' "$PROP" > "$TMP" 2>/dev/null || exit 0
[ -s "$TMP" ] || { rm -f "$TMP"; exit 0; }
cat "$TMP" > "$PROP"
rm -f "$TMP"

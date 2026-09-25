# Keep the user's config across updates, create defaults on a fresh install.

OLD_CONFIG="/data/adb/modules/duckmock/config.json"

if [ -f "$OLD_CONFIG" ]; then
    ui_print "- Keeping the existing configuration"
    cp -af "$OLD_CONFIG" "$MODPATH/config.json"
else
    ui_print "- Writing the default configuration"
    cat > "$MODPATH/config.json" <<'JSON'
{
  "version": 1,
  "paused": false,
  "hideLocationFlag": true,
  "normalizeProvider": true,
  "hideSettingsKey": true,
  "coverQueryPath": true,
  "hideAppOps": true,
  "grantMockOp": false,
  "verboseLog": false,
  "spoofers": [],
  "exempt": []
}
JSON
fi

rm -f "$MODPATH/boot_attempts" "$MODPATH/disable_hooks"

set_perm "$MODPATH/config.json" 0 0 0644

MODDIR=${0%/*}
PKG=com.strawing.duckmock

sh "$MODDIR/describe.sh" 2>/dev/null

if am start -n "$PKG/.MainActivity" --user 0 >/dev/null 2>&1; then
    echo "Opening DuckMock"
    exit 0
fi

if monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; then
    echo "Opening DuckMock"
    exit 0
fi

echo "The DuckMock manager is not installed. Install the app, or open it from the launcher."
exit 1

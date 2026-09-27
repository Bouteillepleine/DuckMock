#!/usr/bin/env bash
set -euo pipefail

REPO="${1:-Bouteillepleine/DuckMock}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

for f in duckmock.jks key.properties; do
  [ -f "$f" ] || { echo "missing $f in $ROOT"; exit 1; }
done

gh auth status >/dev/null 2>&1 || { echo "gh is not logged in: gh auth login"; exit 1; }

value() {
  sed -n "s/^$1=//p" key.properties | tr -d '\r\n'
}

for k in storePassword keyAlias keyPassword; do
  [ -n "$(value "$k")" ] || { echo "key.properties has no $k"; exit 1; }
done

if base64 -w0 duckmock.jks >/dev/null 2>&1; then
  base64 -w0 duckmock.jks | gh secret set DUCKMOCK_KEYSTORE_BASE64 --repo "$REPO"
else
  base64 duckmock.jks | tr -d '\n' | gh secret set DUCKMOCK_KEYSTORE_BASE64 --repo "$REPO"
fi
value storePassword | gh secret set DUCKMOCK_STORE_PASSWORD --repo "$REPO"
value keyAlias      | gh secret set DUCKMOCK_KEY_ALIAS      --repo "$REPO"
value keyPassword   | gh secret set DUCKMOCK_KEY_PASSWORD   --repo "$REPO"

echo
echo "$REPO now holds:"
gh secret list --repo "$REPO"

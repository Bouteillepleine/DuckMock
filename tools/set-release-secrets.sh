#!/usr/bin/env bash
set -euo pipefail

REPO="${1:-Bouteillepleine/DuckMock}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

[ -f key.properties ] || { echo "missing key.properties in $ROOT"; exit 1; }

gh auth status >/dev/null 2>&1 || { echo "gh is not logged in: gh auth login"; exit 1; }

value() {
  sed -n "s/^$1=//p" key.properties | tr -d '\r\n'
}

for k in storeFile storePassword keyAlias keyPassword; do
  [ -n "$(value "$k")" ] || { echo "key.properties has no $k"; exit 1; }
done

# Whatever key.properties points at, never a fixed filename: the keystore the passwords
# belong to is the only one worth uploading.
STORE="$(value storeFile)"
if command -v cygpath >/dev/null 2>&1; then
  STORE="$(cygpath -u "$STORE")"
fi
[ -f "$STORE" ] || { echo "keystore not found: $(value storeFile)"; exit 1; }
echo "uploading $(basename "$STORE")"

if base64 -w0 "$STORE" >/dev/null 2>&1; then
  base64 -w0 "$STORE" | gh secret set DUCKMOCK_KEYSTORE_BASE64 --repo "$REPO"
else
  base64 "$STORE" | tr -d '\n' | gh secret set DUCKMOCK_KEYSTORE_BASE64 --repo "$REPO"
fi
value storePassword | gh secret set DUCKMOCK_STORE_PASSWORD --repo "$REPO"
value keyAlias      | gh secret set DUCKMOCK_KEY_ALIAS      --repo "$REPO"
value keyPassword   | gh secret set DUCKMOCK_KEY_PASSWORD   --repo "$REPO"

echo
echo "$REPO now holds:"
gh secret list --repo "$REPO"

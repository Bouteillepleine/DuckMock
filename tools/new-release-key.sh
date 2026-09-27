#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE="${DUCKMOCK_KEYSTORE:-$HOME/duckmock-release.jks}"
ALIAS="${DUCKMOCK_ALIAS:-duckmock}"
PROPS="${DUCKMOCK_PROPS:-$ROOT/key.properties}"
DNAME="${DUCKMOCK_DNAME:-CN=DuckMock, O=strawing}"

find_keytool() {
  for c in \
    "${JAVA_HOME:-}/bin/keytool" \
    "/c/Program Files/Android/Android Studio/jbr/bin/keytool.exe" \
    "$(command -v keytool 2>/dev/null || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}

KEYTOOL="$(find_keytool)" || { echo "no keytool; install a JDK or Android Studio"; exit 1; }

if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists."
  echo "Refusing to overwrite it: replacing a signing key breaks upgrades for anyone on the old one."
  exit 1
fi

CREATED=0
DONE=0
cleanup() {
  if [ "$CREATED" = 1 ] && [ "$DONE" != 1 ]; then
    rm -f "$KEYSTORE"
    echo "removed the half-made keystore, so you can run this again"
  fi
}
trap cleanup EXIT

echo "Creating a release key at $KEYSTORE"
echo "Choose a password. It is never shown, never committed, and cannot be recovered."
printf 'Password: '
read -rs PASS
echo
printf 'Again:    '
read -rs PASS2
echo
[ "$PASS" = "$PASS2" ] || { echo "they do not match"; exit 1; }
[ "${#PASS}" -ge 12 ] || { echo "use at least 12 characters"; exit 1; }
unset PASS2

# Fed through stdin rather than -storepass, which would put the password in the process list.
# The trailing blank line answers "key password (RETURN if same as keystore password)".
printf '%s\n%s\n\n' "$PASS" "$PASS" | "$KEYTOOL" -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "$DNAME" >/dev/null

[ -s "$KEYSTORE" ] || { echo "keytool wrote nothing"; exit 1; }
CREATED=1

# Matched as 32 colon-separated bytes rather than by label: keytool prints "SHA256:" or
# "SHA 256:" depending on its locale, and SHA-1 is too short to collide with this.
FINGERPRINT="$(printf '%s\n' "$PASS" \
  | "$KEYTOOL" -list -v -keystore "$KEYSTORE" -alias "$ALIAS" 2>/dev/null \
  | grep -oiE '([0-9A-F]{2}:){31}[0-9A-F]{2}' | head -1)"
[ -n "$FINGERPRINT" ] || { echo "could not read the fingerprint back"; exit 1; }

if [ -f "$PROPS" ]; then
  cp "$PROPS" "$PROPS.release-backup"
  echo "kept your old key.properties as $(basename "$PROPS").release-backup"
fi

# Gradle is a Windows JVM under Git Bash, and it reads /c/Users/... as drive-relative, so
# the path it is handed has to be the mixed C:/Users/... form.
if command -v cygpath >/dev/null 2>&1; then
  STORE_PATH="$(cygpath -m "$KEYSTORE")"
else
  STORE_PATH="$KEYSTORE"
fi

umask 077
cat > "$PROPS" <<PROPERTIES
storeFile=$STORE_PATH
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
PROPERTIES
unset PASS

echo "wrote $PROPS"
echo
echo "Certificate SHA-256 (safe to publish):"
echo "  $FINGERPRINT"
echo

DONE=1

if [ "${DUCKMOCK_SKIP_SECRETS:-0}" = "1" ]; then
  echo "skipping the CI secrets"
else
  bash "$ROOT/tools/set-release-secrets.sh"
fi

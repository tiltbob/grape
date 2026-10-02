#!/usr/bin/env bash
# Creates the release signing keystore once and prints the GitHub secrets the
# release workflow needs. Keep the .jks file and its password somewhere safe:
# Android will only install updates that are signed with this same key.
#
# Usage: scripts/make-keystore.sh [path/to/grape-release.jks]
set -euo pipefail

KEYSTORE="${1:-grape-release.jks}"
ALIAS="grape"

if [ -e "$KEYSTORE" ]; then
  echo "refusing to overwrite existing $KEYSTORE" >&2
  exit 1
fi

read -r -s -p "Choose a keystore/key password: " PASSWORD; echo
read -r -s -p "Repeat it: " PASSWORD2; echo
[ "$PASSWORD" = "$PASSWORD2" ] || { echo "passwords differ" >&2; exit 1; }
[ "${#PASSWORD}" -ge 8 ] || { echo "use at least 8 characters" >&2; exit 1; }

keytool -genkeypair -v \
  -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=Grape release, O=Grape"

B64="$(base64 < "$KEYSTORE" | tr -d '\n')"

cat <<MSG

Keystore written to $KEYSTORE (alias: $ALIAS). Back it up.

Add these repository secrets (Settings > Secrets and variables > Actions),
or run the gh commands below from the repository root:

  gh secret set KEYSTORE_BASE64   --body '$B64'
  gh secret set KEYSTORE_PASSWORD --body '<the password you just chose>'
  gh secret set KEY_ALIAS         --body '$ALIAS'
  gh secret set KEY_PASSWORD      --body '<the password you just chose>'

Then publish a release by pushing a tag:

  git tag v0.1.0 && git push origin v0.1.0
MSG

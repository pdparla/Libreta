#!/usr/bin/env bash
# Hands the CA that issued your Nextcloud server's certificate to Libreta over adb (docs/setup/nextcloud.md). With
# it, Libreta trusts only these certificates for Nextcloud; without it, Android's own ones.
#
#   scripts/nextcloud-ca.sh ca.pem       # import (replaces any CA imported before); the file may hold several
#   scripts/nextcloud-ca.sh --remove     # go back to Android's certificates
#
# Only the public certificate: never the CA's private key. ADB can point at another adb binary, e.g. Windows'
# adb.exe from WSL.
set -euo pipefail
ARG=${1:?usage: $0 ca.pem | --remove}
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib/adb-import.sh"

if [ "$ARG" = --remove ]; then
  : > "$WORK/ca.pem"
else
  [ -f "$ARG" ] || { echo "No such file: $ARG" >&2; exit 2; }
  grep -q 'PRIVATE KEY' "$ARG" && { echo "$ARG holds a private key; pass only the CA certificate." >&2; exit 2; }
  grep -q 'BEGIN CERTIFICATE' "$ARG" || { echo "$ARG is not a PEM certificate." >&2; exit 2; }
  cp "$ARG" "$WORK/ca.pem"
fi
adb_import "$WORK/ca.pem" /data/local/tmp/libreta-nextcloud-ca.pem "nextcloud ca"
if [ "$ARG" = --remove ]; then
  echo "CA removed: Libreta uses Android's certificates for Nextcloud."
else
  echo "CA imported: Libreta trusts only it for Nextcloud."
fi

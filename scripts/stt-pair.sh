#!/usr/bin/env bash
# Pairs the tablet with the dictation service on the server (docs/setup/dictation-server.md): reads the service's
# token and certificate over SSH and hands them to Libreta over adb. Run it after server/stt/setup.sh, and again if
# the token or the certificate change.
#
#   SERVER=alice@192.0.2.10 STT_DIR=/srv/libreta/stt scripts/stt-pair.sh
#
# SERVER is the SSH destination and STT_DIR the server/stt folder there. STT_URL is the address the tablet uses
# (default https://<SERVER's host>:8443). ADB can point at another adb binary, e.g. Windows' adb.exe from WSL.
set -euo pipefail
SERVER=${SERVER:?set SERVER to the SSH destination of the dictation server, e.g. alice@192.0.2.10}
STT_DIR=${STT_DIR:?set STT_DIR to the server/stt folder on the server}
STT_URL=${STT_URL:-https://${SERVER#*@}:8443}
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib/adb-import.sh"

token=$(ssh -o BatchMode=yes "$SERVER" "cat $STT_DIR/token")
cert=$(ssh -o BatchMode=yes "$SERVER" "cat $STT_DIR/tls/cert.pem")
python3 - "$STT_URL" "$token" "$cert" > "$WORK/stt.json" <<'PY'
import json, sys
print(json.dumps({"url": sys.argv[1], "token": sys.argv[2], "cert": sys.argv[3]}))
PY
chmod 600 "$WORK/stt.json"

adb_import "$WORK/stt.json" /data/local/tmp/libreta-stt.json "stt settings"
echo "Dictation set up on the tablet: $STT_URL"

#!/usr/bin/env bash
# Pairs the tablet with the dictation service on the server (docs/07-ia.md, part 2): reads the service's token and
# certificate over SSH and hands them to Libreta over adb. Run it after server/stt/setup.sh, and again if the
# token or the certificate change.
#
#   scripts/stt-pair.sh
#
# SERVER (SSH destination), STT_DIR (the server/stt folder there) and STT_URL can be overridden; ADB can point at
# another adb binary, e.g. Windows' adb.exe from WSL.
set -euo pipefail
export MSYS_NO_PATHCONV=1
SERVER=${SERVER:-alice@192.0.2.10}
STT_DIR=${STT_DIR:-/srv/docker/libreta/stt}
STT_URL=${STT_URL:-https://192.0.2.10:8443}
ADB=${ADB:-adb}
REMOTE=/data/local/tmp/libreta-stt.json
TAG=LibretaImport

native() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) cygpath -w "$2" ;;
    *) if [[ "$1" == *.exe ]]; then wslpath -w "$2"; else echo "$2"; fi ;;
  esac
}

"$ADB" get-state > /dev/null 2>&1 || { echo "No hay ninguna tablet conectada por adb." >&2; exit 1; }

WORK=$(mktemp -d)
cleanup() {
  rm -rf "$WORK"
  "$ADB" shell rm -f "$REMOTE" < /dev/null > /dev/null 2>&1 || true
}
trap cleanup EXIT
chmod 700 "$WORK"

token=$(ssh -o BatchMode=yes "$SERVER" "cat $STT_DIR/token")
cert=$(ssh -o BatchMode=yes "$SERVER" "cat $STT_DIR/tls/cert.pem")
python3 - "$STT_URL" "$token" "$cert" > "$WORK/stt.json" <<'PY'
import json, sys
print(json.dumps({"url": sys.argv[1], "token": sys.argv[2], "cert": sys.argv[3]}))
PY
chmod 600 "$WORK/stt.json"

"$ADB" push "$(native "$ADB" "$WORK/stt.json")" "$REMOTE" > /dev/null 2>&1 || { echo "No se pudo copiar a la tablet." >&2; exit 1; }
"$ADB" shell chmod 644 "$REMOTE" < /dev/null
"$ADB" logcat -c < /dev/null
"$ADB" shell am start -n com.parla.libreta/.ui.AdbImportActivity < /dev/null > /dev/null
for _ in $(seq 1 20); do
  answer=$("$ADB" logcat -d -s "$TAG:*" < /dev/null | tr -d '\r' | grep -E "stt settings (imported|import failed)" | tail -1 || true)
  if [[ "$answer" == *"stt settings imported"* ]]; then
    echo "Dictado configurado en la tablet: $STT_URL"
    exit 0
  elif [[ -n "$answer" ]]; then
    echo "La tablet no aceptó los ajustes: ${answer#*: }" >&2
    exit 1
  fi
  sleep 1
done
echo "La tablet no respondió. ¿Está Libreta instalada y la pantalla desbloqueada?" >&2
exit 1

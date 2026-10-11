#!/usr/bin/env bash
# Signs in with ChatGPT in this PC's browser and hands the session to Libreta on the tablet over adb
# (docs/07-ia.md). Needed once, and again only after 30 days without using the AI or after signing out.
#
#   scripts/chatgpt-login.sh
#
# JAVA (Java 11 or later) and ADB can point at other binaries. The browser's redirect goes to 127.0.0.1 on the
# machine running Java: from WSL, use Windows' java.exe and adb.exe, e.g.
#   JAVA=/mnt/c/Users/me/.jdks/corretto-21/bin/java.exe ADB=/mnt/c/Users/me/Android/platform-tools/adb.exe scripts/chatgpt-login.sh
# Otherwise paste in the terminal the address the browser ends up at.
set -euo pipefail
export MSYS_NO_PATHCONV=1
JAVA=${JAVA:-java}
ADB=${ADB:-adb}
HERE=$(cd "$(dirname "$0")" && pwd)
REMOTE=/data/local/tmp/libreta-chatgpt.json
TAG=LibretaAI

# $2 as the path that binary $1 understands: Windows programs need Windows paths, from Git Bash or from WSL.
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
SESSION="$WORK/chatgpt-session.json"
chmod 700 "$WORK"

# UTF-8 output: Windows' Java would otherwise write in the console's code page.
"$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "$(native "$JAVA" "$HERE/ChatGptLogin.java")" "$(native "$JAVA" "$SESSION")"
chmod 600 "$SESSION"

# Only the shell user can write in /data/local/tmp; the app reads the file there and keeps it in its own storage.
"$ADB" push "$(native "$ADB" "$SESSION")" "$REMOTE" > /dev/null 2>&1 || { echo "No se pudo copiar la sesión a la tablet." >&2; exit 1; }
"$ADB" shell chmod 644 "$REMOTE" < /dev/null
"$ADB" logcat -c < /dev/null
"$ADB" shell am start -n com.parla.libreta/.ui.ai.ChatGptImportActivity < /dev/null > /dev/null
for _ in $(seq 1 20); do
  answer=$("$ADB" logcat -d -s "$TAG:*" < /dev/null | tr -d '\r' | grep -E "session (imported|import failed)" | tail -1 || true)
  if [[ "$answer" == *"session imported"* ]]; then
    echo "Sesión guardada en la tablet."
    exit 0
  elif [[ -n "$answer" ]]; then
    echo "La tablet no aceptó la sesión: ${answer#*: }" >&2
    exit 1
  fi
  sleep 1
done
echo "La tablet no respondió. ¿Está Libreta instalada y la pantalla desbloqueada?" >&2
exit 1

#!/usr/bin/env bash
# Signs in with ChatGPT in this PC's browser and hands the session to Libreta on the tablet over adb
# (docs/setup/chatgpt.md). Needed once, and again only after 30 days without using the AI or after signing out.
#
#   scripts/chatgpt-login.sh
#
# JAVA (Java 11 or later) and ADB can point at other binaries. The browser's redirect goes to 127.0.0.1 on the
# machine running Java: from WSL, use Windows' java.exe and adb.exe, e.g.
#   JAVA=/mnt/c/path/to/jdk-21/bin/java.exe ADB=/mnt/c/path/to/platform-tools/adb.exe scripts/chatgpt-login.sh
# Otherwise paste in the terminal the address the browser ends up at.
set -euo pipefail
JAVA=${JAVA:-java}
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib/adb-import.sh"
SESSION="$WORK/chatgpt-session.json"

# UTF-8 output: Windows' Java would otherwise write in the console's code page.
"$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "$(native "$JAVA" "$HERE/ChatGptLogin.java")" "$(native "$JAVA" "$SESSION")"
chmod 600 "$SESSION"

adb_import "$SESSION" /data/local/tmp/libreta-chatgpt.json "chatgpt session"
echo "Session saved on the tablet."

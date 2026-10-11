#!/usr/bin/env bash
# Hides or shows again the packages of groups A-F in docs/05-debloat.md on the tablet (Android 4.4 `pm block`).
#
#   scripts/tablet-debloat.sh block      # hide them
#   scripts/tablet-debloat.sh unblock    # restore them
#
# Hidden packages keep their data and do not show up anywhere, not even in Ajustes > Aplicaciones, so this script
# (or a factory reset) is the way back. ADB can point at another adb binary.
set -uo pipefail
export MSYS_NO_PATHCONV=1
ACTION=${1:?usage: $0 block|unblock}
[ "$ACTION" = block ] || [ "$ACTION" = unblock ] || { echo "usage: $0 block|unblock" >&2; exit 2; }
ADB=${ADB:-adb}
DOC="$(dirname "$0")/../docs/05-debloat.md"
EXPECTED=$([ "$ACTION" = block ] && echo true || echo false)

# Package names inside the plain ``` blocks under the "### A." .. "### F." headings only.
packages=$(awk '/^### [A-F]\./{g=1; next} /^###? /{g=0} /^```$/{if (g) inb=!inb; next} inb{for(i=1;i<=NF;i++) print $i}' "$DOC")

ok=0; failed=0
for pkg in $packages; do
  # </dev/null: otherwise adb shell reads stdin. On this ROM a failing pm also triggers a 1-minute dumpstate.
  answer=$("$ADB" shell pm "$ACTION" "$pkg" < /dev/null 2>&1 | tr -d '\r\000' | grep -E 'blocked state|Exception' | head -1)
  if [[ "$answer" == *"new blocked state: $EXPECTED"* ]]; then
    ok=$((ok + 1))
  else
    failed=$((failed + 1))
    echo "$pkg: ${answer:-no answer}"
  fi
done
echo "$ACTION: $ok ok, $failed failed"

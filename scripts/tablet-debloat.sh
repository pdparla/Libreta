#!/usr/bin/env bash
# Hides or shows again unneeded system packages on the tablet with Android 4.4's `pm block`
# (docs/setup/kiosk-mode.md explains each group and its risk).
#
#   scripts/tablet-debloat.sh block [GROUP...]     # hide them; all groups (A-F) when none is given
#   scripts/tablet-debloat.sh unblock [GROUP...]   # restore them
#
# Hidden packages keep their data and do not show up anywhere, not even in Settings > Apps, so this script (or a
# factory reset) is the way back. The lists are for a Samsung Galaxy Tab 3 Lite (SM-T113); packages missing on
# another device are reported and skipped. ADB can point at another adb binary.
set -uo pipefail
export MSYS_NO_PATHCONV=1
usage() { echo "usage: $0 block|unblock [A B C D E F]" >&2; exit 2; }
ACTION=${1:-}
[ "$ACTION" = block ] || [ "$ACTION" = unblock ] || usage
shift
ADB=${ADB:-adb}
EXPECTED=$([ "$ACTION" = block ] && echo true || echo false)

# A. Samsung and third-party bloat (low risk)
GROUP_A=(
  com.hancom.androidpc.appwidget com.hancom.androidpc.hanupdater com.hancom.androidpc.launcher.shared
  com.hancom.office.hcell.viewer.hcell_viewer_apk com.hancom.office.hshow.viewer.hshow_viewer_apk
  com.hancom.office.hword.viewer.hword_apk com.sec.android.app.samsungapps com.sec.android.app.billing
  com.sec.android.widgetapp.at.hero.accuweather com.sec.android.widgetapp.dualclockdigital
  com.sec.android.widgetapp.SPlannerAppWidget com.sec.android.widgetapp.webmanual
  com.sec.android.app.FlashBarService com.sec.android.Kies com.wssnps com.sec.android.allshare.service.fileshare
  com.sec.android.app.FileShareClient com.sec.android.app.FileShareServer com.samsung.android.app.memo
  com.sec.android.app.sysscope com.sec.android.diagmonagent com.samsung.safetyinformation
)
# B. Samsung services: updates, account, Find My Mobile, Knox (low to medium risk)
GROUP_B=(
  com.LocalFota com.sec.android.fotaclient com.wssyncmldm com.osp.app.signin com.sec.pcw.device com.fmm.dm
  com.fmm.ds com.sec.dsm.system com.samsung.klmsagent com.sec.esdk.elm com.sec.enterprise.mdm.services.simpin
  com.sec.enterprise.mdm.vpn
)
# C. Google apps (low risk)
GROUP_C=(
  com.google.android.music com.google.android.youtube com.google.android.videos com.google.android.play.games
  com.google.android.street com.google.android.gm com.google.android.googlequicksearchbox com.android.chrome
)
# D. Google services and Play Store (largest RAM saving, medium risk)
GROUP_D=(
  com.google.android.gms com.google.android.gsf com.google.android.gsf.login com.android.vending
  com.google.android.backuptransport com.google.android.configupdater com.google.android.feedback
  com.google.android.onetimeinitializer com.google.android.partnersetup com.google.android.syncadapters.calendar
  com.google.android.syncadapters.contacts com.google.android.tts
)
# E. Media and utilities (optional)
GROUP_E=(
  com.sec.android.app.camera com.sec.android.gallery3d com.sec.android.app.videoplayer com.samsung.video
  com.sec.android.app.soundalive com.sec.android.mmapp com.sec.android.app.popupcalculator com.android.email
  com.android.exchange com.android.calendar
)
# F. Everything else that is not essential
GROUP_F=(
  com.android.contacts com.android.dreams.basic com.android.dreams.phototable com.android.wallpaper.livepicker
  com.android.printspooler com.android.stk com.android.htmlviewer com.google.android.marvin.talkback
  com.samsung.android.sdk.spenv10 com.sec.android.app.myfiles com.sec.android.app.clockpackage
  com.sec.android.app.chromecustomizations com.sec.android.emergencymode.service com.sec.android.pagebuddynotisvc
  com.sec.android.app.ringtoneBR com.sec.android.preloadinstaller
)

packages=()
for group in "${@:-A B C D E F}"; do
  for g in $group; do
    case "$g" in
      [A-F]) list="GROUP_$g[@]"; packages+=("${!list}") ;;
      *) usage ;;
    esac
  done
done

ok=0; failed=0
for pkg in "${packages[@]}"; do
  # </dev/null: otherwise adb shell reads stdin. On Samsung's ROM a failing pm also triggers a 1-minute dumpstate.
  answer=$("$ADB" shell pm "$ACTION" "$pkg" < /dev/null 2>&1 | tr -d '\r\000' | grep -E 'blocked state|Exception' | head -1)
  if [[ "$answer" == *"new blocked state: $EXPECTED"* ]]; then
    ok=$((ok + 1))
  else
    failed=$((failed + 1))
    echo "$pkg: ${answer:-no answer}"
  fi
done
echo "$ACTION: $ok ok, $failed failed"

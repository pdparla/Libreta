# Shared by the scripts that hand something to Libreta over adb (docs/reference/scripts.md). Source it after
# setting ADB. The app's AdbImportActivity reads what was pushed to /data/local/tmp, keeps it in its own storage
# and reports on logcat, and the pushed copy is deleted on exit.

export MSYS_NO_PATHCONV=1
ADB=${ADB:-adb}

# $2 as the path that binary $1 understands: Windows programs need Windows paths, from Git Bash or from WSL.
native() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) cygpath -w "$2" ;;
    *) if [[ "$1" == *.exe ]]; then wslpath -w "$2"; else echo "$2"; fi ;;
  esac
}

"$ADB" get-state > /dev/null 2>&1 || { echo "No tablet connected over adb." >&2; exit 1; }

# A private folder for the files to push, deleted on exit together with the copies pushed to the tablet.
WORK=$(mktemp -d)
chmod 700 "$WORK"
PUSHED=()
cleanup() {
  rm -rf "$WORK"
  for remote in "${PUSHED[@]}"; do "$ADB" shell rm -f "$remote" < /dev/null > /dev/null 2>&1 || true; done
}
trap cleanup EXIT

# Pushes $1 to $2 on the tablet and has Libreta import it; $3 is what the app logs it as ("stt settings"...).
# Returns once the app reports it imported, exits with an error otherwise.
adb_import() {
  local file=$1 remote=$2 what=$3 answer
  PUSHED+=("$remote")
  # Only the shell user can write in /data/local/tmp, so the app only ever imports what adb pushed.
  "$ADB" push "$(native "$ADB" "$file")" "$remote" > /dev/null 2>&1 || { echo "Could not copy $what to the tablet." >&2; exit 1; }
  "$ADB" shell chmod 644 "$remote" < /dev/null
  "$ADB" logcat -c < /dev/null
  "$ADB" shell am start -n com.parla.libreta/.ui.AdbImportActivity < /dev/null > /dev/null
  for _ in $(seq 1 20); do
    answer=$("$ADB" logcat -d -s "LibretaImport:*" < /dev/null | tr -d '\r' | grep -E "$what (imported|import failed)" | tail -1 || true)
    if [[ "$answer" == *"$what imported"* ]]; then
      return 0
    elif [[ -n "$answer" ]]; then
      echo "The tablet did not accept the $what: ${answer#*: }" >&2
      exit 1
    fi
    sleep 1
  done
  echo "The tablet did not answer. Is Libreta installed and the screen unlocked?" >&2
  exit 1
}

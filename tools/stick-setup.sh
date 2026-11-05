#!/bin/bash
# First-time setup of a stick: Android's privacy settings, and which apps to keep. install.sh runs it
# (README "Stick setup"); it also runs by itself.
# Usage: STICK=<host:port> tools/stick-setup.sh --privacy   turn off the settings below (no questions)
#        STICK=<host:port> tools/stick-setup.sh --apps      ask about each app: keep, disable or remove
#        STICK=<host:port> tools/stick-setup.sh --undo      put back everything this stick's record holds
# Every change is read back; one that did not take is named and the script exits non-zero.
# dist/setup-<stick>.undo records each old value and app (<stick>: stickid in tools/adb-lib.sh), and
# --undo walks it backwards. A removed app cannot be put back from here: --undo names it.
# Never offered, whatever a stick reports: PROTECTED below. Removing is offered only for installed apps;
# a system app can only be disabled. Questions read stdin, and end of input keeps every app.
set -euo pipefail
cd "$(dirname "$0")/.."
usage() { echo "usage: STICK=<host:port> $0 --privacy | --apps | --undo" >&2; exit 2; }
[ $# = 1 ] || usage
MODE=$1
case $MODE in --privacy|--apps|--undo) ;; *) usage ;; esac
S=${STICK:-}
[ -n "$S" ] || { echo "refusing: set STICK=<host:port>; there is no default stick" >&2; exit 2; }
ADB_TIMEOUT=${ADB_TIMEOUT:-60}
command -v timeout >/dev/null || { echo "refusing: no timeout(1) (coreutils) to bound adb calls" >&2; exit 1; }
ADB_TIMEOUT_LOG=
[ -f tools/adb-lib.sh ] || { echo "refusing: tools/adb-lib.sh is missing; is this a full checkout?" >&2; exit 1; }
# shellcheck source=tools/adb-lib.sh
. tools/adb-lib.sh
APPS=tools/stick-apps.tsv
CONN=$(adbt "$ADB_TIMEOUT" connect "$S" 2>&1 || true)   # exits 0 even when it fails; get-state decides
[ "$(a get-state 2>/dev/null || true)" = device ] || { echo "refusing: $S unreachable over adb (${CONN//$'\n'/ })" >&2; exit 1; }
SID=$(stickid)
[ -n "$SID" ] || { echo "refusing: could not read the stick's identity (ro.product.model, ro.serialno) off $S" >&2; exit 1; }
mkdir -p dist
UNDO=dist/setup-$SID.undo

# <namespace> <key> <off value>. Each is a switch Android itself reads; none is needed to watch anything.
# Left on purpose: Google Play Protect (security, not privacy) and voice search (YouTube uses it).
SETTINGS=(
  "global send_action_app_error 0"             # crash reports sent to the app's maker
  "secure send_action_app_error 0"
  "global assisted_gps_enabled 0"              # asks a server to help find the location
  "global wifi_scan_always_enabled 0"          # Wi-Fi scanning for location, with Wi-Fi off
  "global ble_scan_always_enabled 0"           # Bluetooth scanning for location; a paired remote is not affected
  "global wifi_wakeup_enabled 0"               # turns Wi-Fi back on near known networks, from location
  "global network_recommendations_enabled 0"   # network rating through Google
  "global upload_apk_enable 0"                 # sends unknown apps to Google for checking
  "global package_verifier_user_consent -1"    # the same, the "improve detection" consent
  "secure assist_structure_enabled 0"          # lets the assistant read what is on screen
  "secure assist_screenshot_enabled 0"         # lets the assistant take a screenshot
)
# Never disable or remove: Home itself, what keeps the stick and its apps working, and the first-boot
# wizard (Android TV can stop booting without it).
PROTECTED='^(home\.cleanframe\.tv(\.staging)?|android|com\.android\.(systemui|settings|tv\.settings|webview|vending|packageinstaller|providers\..*|bluetooth|shell)|com\.google\.android\.(gms|gsf|webview|katniss|backdrop|tv\.remote\.service|tv\.frameworkpackagestubs|ext\..*)|com\.mitv\.videoplayer|.*setupwraith.*)$'

get() { a shell settings get "$1" "$2" | tr -d '\r' | first || true; }
# Each old value goes in the record once: a second run must not record its own 0 as the original.
remember() { [ -f "$UNDO" ] && grep -qxF -- "$1" "$UNDO" && return 0; printf '%s\n' "$1" >> "$UNDO"; }
recorded() { [ -f "$UNDO" ] && grep -q -- "^$1 " "$UNDO"; }
FAILED=()

privacy() {
  local e ns key want old got changed=0 total=0
  for e in "${SETTINGS[@]}"; do
    read -r ns key want <<<"$e"; total=$((total + 1))
    old=$(get "$ns" "$key")
    if [ -z "$old" ]; then FAILED+=("$ns $key: could not be read"); continue; fi
    [ "$old" != "$want" ] || continue
    recorded "setting $ns $key" || remember "setting $ns $key $old"
    a shell settings put "$ns" "$key" "$want" || true
    got=$(get "$ns" "$key")
    if [ "$got" = "$want" ]; then changed=$((changed + 1)); else FAILED+=("$ns $key: want $want, reads '$got'"); fi
  done
  # Location is a switch of its own on Android 10+: writing location_mode alone does not move it.
  total=$((total + 1)); old=$(get secure location_mode)
  if [ -z "$old" ]; then FAILED+=("location: could not be read")
  elif [ "$old" != 0 ]; then
    recorded location || remember "location $old"
    a shell cmd location set-location-enabled false || true
    got=$(get secure location_mode)
    if [ "$got" = 0 ]; then changed=$((changed + 1)); else FAILED+=("location: want off, location_mode reads '$got'"); fi
  fi
  total=$((total + 1)); got=$(a shell bmgr enabled | tr -d '\r' | first || true)
  if [[ $got != *disabled* ]]; then
    recorded backup || remember "backup enabled"
    a shell bmgr enable false >/dev/null || true
    got=$(a shell bmgr enabled | tr -d '\r' | first || true)
    if [[ $got == *disabled* ]]; then changed=$((changed + 1)); else FAILED+=("backup: reads '$got'"); fi
  fi
  echo "privacy: $total settings off ($changed changed now)"
  echo "privacy: two more only change on the TV: Settings > Privacy > Usage & diagnostics, and > Ads"
}

# pkgs <flags>: package names, one a line, without "package:".
pkgs() { a shell pm list packages "$@" | tr -d '\r' | sed -n 's/^package://p' || true; }
note_for() { sed -n "s/^$1"$'\t'"[a-z]*"$'\t'"//p" "$APPS" | first; }
advice_for() { sed -n "s/^$1"$'\t'"\([a-z]*\)"$'\t'".*/\1/p" "$APPS" | first; }

apps() {
  local enabled user p name advice note ans choice to_disable=() to_remove=()
  [ -f "$APPS" ] || { echo "refusing: $APPS is missing; is this a full checkout?" >&2; exit 1; }
  enabled=$(pkgs -e); user=$(pkgs -3)
  [ -n "$enabled" ] || { echo "refusing: could not read the stick's package list" >&2; exit 1; }
  echo "Which apps stay? k = keep, d = disable (undo with --undo), r = remove (installed apps only; its data goes too)."
  echo "Press Enter for the choice in capitals. Anything not asked about stays as it is."
  # Known apps first, in the list's order, then the stick's other installed apps.
  # The list comes in on fd 3: stdin is the person answering.
  while read -r p <&3; do
    [ -n "$p" ] || continue
    if [[ $p =~ $PROTECTED ]]; then continue; fi
    grep -qxF -- "$p" <<<"$enabled" || continue
    advice=$(advice_for "${p//./\\.}"); advice=${advice:-keep}
    note=$(note_for "${p//./\\.}"); note=${note:-installed app}
    while :; do
      if grep -qxF -- "$p" <<<"$user"; then
        if [ "$advice" = disable ]; then choice="k/D/r"; else choice="K/d/r"; fi
      else
        if [ "$advice" = disable ]; then choice="k/D"; else choice="K/d"; fi
      fi
      printf '  %s (%s) [%s]: ' "$p" "$note" "$choice"
      if ! read -r ans; then echo; echo "apps: end of input, every app kept"; to_disable=(); to_remove=(); ANSWERED=0; return 0; fi
      [ -n "$ans" ] || { [ "$advice" = disable ] && ans=d || ans=k; }
      case $ans in
        k|K) break ;;
        d|D) to_disable+=("$p"); break ;;
        r|R) if [[ $choice == *r* ]]; then to_remove+=("$p"); break; fi; echo "    a system app can only be disabled" ;;
        *) echo "    k, d or r" ;;
      esac
    done
  done 3< <(cut -f1 "$APPS"; grep -vxFf <(cut -f1 "$APPS") <<<"$user" || true)
  if [ ${#to_disable[@]} = 0 ] && [ ${#to_remove[@]} = 0 ]; then echo "apps: nothing to change"; ANSWERED=1; return 0; fi
  echo "Disable: ${to_disable[*]:-none}"
  echo "Remove (its data goes too): ${to_remove[*]:-none}"
  printf 'Type yes to go ahead: '
  if ! read -r ans || [ "$ans" != yes ]; then echo "apps: not confirmed, every app kept"; ANSWERED=1; return 0; fi
  for p in ${to_disable[@]+"${to_disable[@]}"}; do
    a shell pm disable-user --user 0 "$p" >/dev/null || true
    remember "disabled $p"
  done
  for p in ${to_remove[@]+"${to_remove[@]}"}; do
    a shell pm uninstall "$p" >/dev/null || true
    remember "removed $p"
  done
  if [ ${#to_disable[@]} != 0 ]; then
    # Android writes a disable to disk lazily, and a reboot within about 30 s loses it (seen on the Xiaomi).
    echo "apps: waiting 45 s so the disables survive a reboot"; sleep 45
  fi
  enabled=$(pkgs -e); local all; all=$(pkgs)
  [ -n "$all" ] || { FAILED+=("apps: could not read the package list back"); ANSWERED=1; return 0; }
  for p in ${to_disable[@]+"${to_disable[@]}"}; do
    if grep -qxF -- "$p" <<<"$enabled"; then FAILED+=("$p: still enabled"); fi
  done
  for p in ${to_remove[@]+"${to_remove[@]}"}; do
    if grep -qxF -- "$p" <<<"$all"; then FAILED+=("$p: still installed"); fi
  done
  echo "apps: ${#to_disable[@]} disabled, ${#to_remove[@]} removed"
  ANSWERED=1
}

undo() {
  [ -f "$UNDO" ] || { echo "nothing to undo: no $UNDO"; return 0; }
  local kind a1 a2 a3 got
  while read -r kind a1 a2 a3; do
    case $kind in
      setting)
        if [ "$a3" = null ]; then a shell settings delete "$a1" "$a2" >/dev/null || true
        else a shell settings put "$a1" "$a2" "$a3" || true; fi
        got=$(get "$a1" "$a2")
        [ "$got" = "$a3" ] || FAILED+=("$a1 $a2: want $a3, reads '$got'") ;;
      location)
        a shell cmd location set-location-enabled true || true
        got=$(get secure location_mode)
        [ "$got" != 0 ] && [ -n "$got" ] || FAILED+=("location: still off") ;;
      backup)
        a shell bmgr enable true >/dev/null || true
        got=$(a shell bmgr enabled | tr -d '\r' | first || true)
        [[ $got == *enabled* ]] || FAILED+=("backup: reads '$got'") ;;
      disabled)
        a shell pm enable --user 0 "$a1" >/dev/null || true
        grep -qxF -- "$a1" <<<"$(pkgs -e)" || FAILED+=("$a1: still disabled") ;;
      removed) echo "undo: $a1 was removed; install it again from the store" ;;
      apps-asked) ;;
      *) FAILED+=("record line not understood: $kind $a1") ;;
    esac
  done < <(tac "$UNDO")
  if [ ${#FAILED[@]} = 0 ]; then mv "$UNDO" "$UNDO.undone.$(date +%Y%m%dT%H%M%S)"; echo "undo: done"; fi
}

ANSWERED=0
case $MODE in
  --privacy) privacy ;;
  --apps) apps; [ "$ANSWERED" = 0 ] || remember "apps-asked" ;;
  --undo) undo ;;
esac
if [ ${#FAILED[@]} != 0 ]; then
  echo "STICK_SETUP_FAIL: these did not take:" >&2
  printf '  %s\n' "${FAILED[@]}" >&2
  exit 1
fi
echo "STICK_SETUP_OK $MODE"

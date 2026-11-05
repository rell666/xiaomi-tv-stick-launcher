#!/bin/bash
# Offline tests of tools/stick-setup.sh and tools/stick-apps.tsv: the privacy settings and their read-back,
# the app questions (keep, disable, remove, end of input, no confirmation), what is never offered, and
# --undo. No device, no network: a fake adb driven by state files, in a temp copy of the scripts.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAILS=0; PASSES=0
pass() { echo "PASS $1"; PASSES=$((PASSES + 1)); }
fail() { echo "FAIL $1: $2"; FAILS=$((FAILS + 1)); }
SERIAL=fake-stick.invalid:5555
F=$T/fake; BIN=$T/bin; R=$T/repo
mkdir -p "$BIN"
cat > "$BIN/adb" <<'EOF'
#!/bin/bash
# State: $F/set/<ns>.<key> (absent = null), $F/{sys,user,disabled,location,backup}; *_ignored files make
# that change say nothing wrong and not take. Every call is logged.
F=$FAKE_DIR; echo "$*" >> "$F/adb.log"
[ "$1" != connect ] || { echo "connected to $2"; exit 0; }
[ "$1" = -s ] && [ "$2" = "$FAKE_SERIAL" ] || { echo "fake adb: unexpected args $*" >&2; exit 97; }
shift 2
case "$1 ${2:-} ${3:-}" in
  "get-state "*) echo device ;;
  "shell getprop ro.product.model") echo FakeBox ;;
  "shell getprop ro.serialno") echo S1 ;;
  "shell settings get")
    if [ "$4 $5" = "secure location_mode" ]; then cat "$F/location"
    elif [ -f "$F/read_fails/$4.$5" ]; then exit 1
    elif [ -f "$F/set/$4.$5" ]; then cat "$F/set/$4.$5"; else echo null; fi ;;
  "shell settings put") [ -f "$F/settings_ignored" ] || echo "$6" > "$F/set/$4.$5" ;;
  "shell settings delete") rm -f "$F/set/$4.$5" ;;
  "shell cmd location")
    [ ! -f "$F/location_ignored" ] || exit 0
    if [ "$5" = false ]; then echo 0 > "$F/location"; else echo 3 > "$F/location"; fi ;;
  "shell bmgr enabled") echo "Backup Manager currently $(cat "$F/backup")" ;;
  "shell bmgr enable") if [ "$4" = true ]; then echo enabled > "$F/backup"; else echo disabled > "$F/backup"; fi ;;
  "shell pm list")
    case "${5:-}" in
      -e) cat "$F/sys" "$F/user" | grep -vxFf "$F/disabled" ;;
      -3) cat "$F/user" ;;
      *) cat "$F/sys" "$F/user" ;;
    esac | sed 's/^/package:/' ;;
  "shell pm disable-user") [ -f "$F/disable_ignored" ] || echo "$6" >> "$F/disabled" ;;
  "shell pm enable") grep -vxF "$6" "$F/disabled" > "$F/d.new" || true; mv "$F/d.new" "$F/disabled" ;;
  "shell pm uninstall") grep -vxF "$4" "$F/user" > "$F/u.new" || true; mv "$F/u.new" "$F/user" ;;
  *) echo "fake adb: unhandled: $*" >&2; exit 98 ;;
esac
EOF
chmod +x "$BIN/adb"; printf '#!/bin/sh\nexit 0\n' > "$BIN/sleep"; chmod +x "$BIN/sleep"

fresh() {
  rm -rf "$F" "$R"; mkdir -p "$F/set" "$F/read_fails" "$R/tools"
  cp "$ROOT/tools/stick-setup.sh" "$ROOT/tools/stick-apps.tsv" "$ROOT/tools/adb-lib.sh" "$R/tools/"
  echo 1 > "$F/set/global.send_action_app_error"; echo 1 > "$F/set/secure.send_action_app_error"
  echo 1 > "$F/set/global.assisted_gps_enabled"; echo -1 > "$F/set/global.package_verifier_user_consent"
  echo 3 > "$F/location"; echo enabled > "$F/backup"
  printf '%s\n' android com.android.tv.settings com.android.printspooler com.miui.tv.analytics com.google.android.gms \
    com.google.android.tungsten.setupwraith com.google.android.katniss com.netflix.ninja com.android.unlisted > "$F/sys"
  printf '%s\n' home.cleanframe.tv home.cleanframe.tv.staging tv.emby.embyatv com.some.game com.android.vending > "$F/user"
  : > "$F/disabled"; : > "$F/adb.log"
}
run() {   # run <mode> [answers]: the answers are stdin, one a line
  local mode=$1 answers=${2-}
  ( cd "$T" && PATH=$BIN:$PATH FAKE_DIR=$F FAKE_SERIAL=$SERIAL STICK=$SERIAL \
      bash "$R/tools/stick-setup.sh" "$mode" ) <<<"$answers" > "$T/out" 2>&1
  RC=$?
}
has() { grep -qF -- "$1" "$T/out"; }
val() { cat "$F/set/$1" 2>/dev/null || echo null; }
is_disabled() { grep -qxF "$1" "$F/disabled"; }
logged() { grep -qF -- "$1" "$F/adb.log"; }
check() {   # check <name> <want rc: 0|nz> <test command...>
  local name=$1 want=$2; shift 2
  if [ "$want" = 0 ] && [ $RC != 0 ]; then fail "$name" "exit $RC, want 0: $(tail -3 "$T/out" | tr '\n' ' ')"
  elif [ "$want" = nz ] && [ $RC = 0 ]; then fail "$name" "exit 0, want non-zero"
  elif ! "$@"; then fail "$name" "failed: $* :: $(tail -4 "$T/out" | tr '\n' ' ')"
  else pass "$name"; fi
}
UNDO=$R/dist/setup-FakeBox-S1.undo

# ---------------------------------------------------------------- privacy
fresh; run --privacy
priv_off() {
  [ "$(val global.send_action_app_error)" = 0 ] && [ "$(val secure.send_action_app_error)" = 0 ] \
    && [ "$(val global.assisted_gps_enabled)" = 0 ] && [ "$(val global.wifi_scan_always_enabled)" = 0 ] \
    && [ "$(val global.ble_scan_always_enabled)" = 0 ] && [ "$(val secure.assist_structure_enabled)" = 0 ] \
    && [ "$(cat "$F/location")" = 0 ] && [ "$(cat "$F/backup")" = disabled ] && has STICK_SETUP_OK
}
check privacy-off 0 priv_off
tells_tv_only() { has "Usage & diagnostics"; }
check privacy-names-the-two-tv-only-settings 0 tells_tv_only
# A second run changes nothing and must not record its own 0 as the original value.
cp "$UNDO" "$T/undo1"; run --privacy
same_record() { cmp -s "$UNDO" "$T/undo1" && has "(0 changed now)"; }
check privacy-second-run-keeps-the-original 0 same_record
fresh; : > "$F/settings_ignored"; run --privacy
not_taken() { has STICK_SETUP_FAIL && has "global send_action_app_error: want 0"; }
check privacy-setting-not-taken nz not_taken
fresh; : > "$F/location_ignored"; run --privacy
loc_not_taken() { has "location: want off"; }
check privacy-location-not-taken nz loc_not_taken
fresh; : > "$F/read_fails/global.assisted_gps_enabled"; run --privacy
unread() { has "global assisted_gps_enabled: could not be read"; }
check privacy-unreadable-is-a-failure nz unread

# ---------------------------------------------------------------- apps
# Questions in order: known apps by the list's order, then the other installed apps.
fresh; run --apps $'\n\n\n\n\nyes'
defaults() {
  is_disabled com.android.printspooler && is_disabled com.miui.tv.analytics && ! is_disabled com.netflix.ninja \
    && ! is_disabled tv.emby.embyatv && ! is_disabled com.some.game && has "apps: 2 disabled, 0 removed" && has STICK_SETUP_OK
}
check apps-enter-takes-the-advice 0 defaults
offered() { has "com.netflix.ninja (Netflix)" && has "tv.emby.embyatv (installed app)"; }
check apps-names-each-one 0 offered
never() {
  local p
  for p in android com.android.tv.settings com.google.android.gms home.cleanframe.tv home.cleanframe.tv.staging \
    com.google.android.tungsten.setupwraith com.google.android.katniss com.android.vending; do
    if grep -qF "  $p (" "$T/out"; then echo "offered $p"; return 1; fi
  done
  ! grep -qF "com.android.unlisted" "$T/out"
}
check apps-never-offers-protected-or-unlisted-system 0 never
# analytics=d, printspooler=keep, netflix=d, emby=remove, game=keep.
fresh; run --apps $'d\nk\nd\nr\nk\nyes'
chosen() {
  is_disabled com.miui.tv.analytics && ! is_disabled com.android.printspooler && is_disabled com.netflix.ninja \
    && ! grep -qxF tv.emby.embyatv "$F/user" && grep -qxF com.some.game "$F/user" \
    && has "apps: 2 disabled, 1 removed" && logged "pm uninstall tv.emby.embyatv" && logged "pm disable-user --user 0 com.netflix.ninja"
}
check apps-keep-disable-remove 0 chosen
# A system app cannot be removed: r asks again.
fresh; run --apps $'r\nd\nk\nk\nk\nk\nyes'
sys_no_remove() { has "a system app can only be disabled" && is_disabled com.miui.tv.analytics && not_uninstalled; }
not_uninstalled() { ! logged "pm uninstall"; }
check apps-system-app-cannot-be-removed 0 sys_no_remove
fresh; run --apps $'x\nk\nk\nk\nk\nk'
reasks() { has "k, d or r" && has "apps: nothing to change"; }
check apps-bad-answer-asks-again 0 reasks
# End of input, and anything but yes at the end, change nothing.
fresh; run --apps $'d'
eof_keeps() { has "end of input, every app kept" && [ ! -s "$F/disabled" ] && not_uninstalled; }
check apps-end-of-input-keeps-all 0 eof_keeps
no_asked_record() { [ ! -f "$UNDO" ] || ! grep -q apps-asked "$UNDO"; }
check apps-end-of-input-is-not-an-answer 0 no_asked_record
fresh; run --apps $'d\nd\nd\nr\nr\nno'
no_keeps() { has "not confirmed, every app kept" && [ ! -s "$F/disabled" ] && not_uninstalled; }
check apps-no-confirmation-keeps-all 0 no_keeps
fresh; : > "$F/disable_ignored"; run --apps $'d\nk\nk\nk\nk\nyes'
dis_not_taken() { has "com.miui.tv.analytics: still enabled" && has STICK_SETUP_FAIL; }
check apps-disable-not-taken nz dis_not_taken
fresh; run --apps $'d\nk\nk\nk\nk\nyes'
waited() { has "waiting 45 s"; }
check apps-waits-before-a-reboot-can-lose-it 0 waited
# Already disabled: not asked again.
fresh; echo com.miui.tv.analytics > "$F/disabled"; run --apps $'k\nk\nk\nk'
skip_disabled() { ! grep -qF "  com.miui.tv.analytics (" "$T/out" && has "apps: nothing to change"; }
check apps-already-disabled-not-asked 0 skip_disabled
# The list's own lines: three columns, a known advice, nothing protected.
list_ok() { awk -F'\t' 'NF!=3 || ($2!="keep" && $2!="disable") {print; bad=1} END {exit bad}' "$ROOT/tools/stick-apps.tsv"; }
RC=0; check apps-list-well-formed 0 list_ok

# ---------------------------------------------------------------- undo
fresh; run --privacy; run --apps $'d\nk\nk\nr\nk\nyes'; run --undo
undone() {
  [ "$(val global.send_action_app_error)" = 1 ] && [ "$(val global.assisted_gps_enabled)" = 1 ] \
    && [ "$(val global.wifi_scan_always_enabled)" = null ] && [ "$(cat "$F/location")" = 3 ] \
    && [ "$(cat "$F/backup")" = enabled ] && [ ! -s "$F/disabled" ] && has "tv.emby.embyatv was removed" \
    && has "undo: done" && [ ! -f "$UNDO" ]
}
check undo-puts-back 0 undone
fresh; run --undo
nothing() { has "nothing to undo"; }
check undo-without-a-record 0 nothing

# ---------------------------------------------------------------- refusals
fresh; ( cd "$T" && PATH=$BIN:$PATH FAKE_DIR=$F FAKE_SERIAL=$SERIAL bash "$R/tools/stick-setup.sh" --privacy ) > "$T/out" 2>&1; RC=$?
no_stick() { has "set STICK=" && [ ! -s "$F/adb.log" ]; }
check refuses-without-stick nz no_stick
fresh; run --bogus
bad_mode() { has usage; }
check refuses-unknown-mode nz bad_mode

if [ $FAILS = 0 ]; then echo "STICK_SETUP_TEST_OK $PASSES"; exit 0; fi
echo "STICK_SETUP_TEST_FAIL $FAILS"; exit 1

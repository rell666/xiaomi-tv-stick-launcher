#!/bin/bash
# Install a built APK on the Xiaomi stick and read its provenance back off the device.
# Usage: ./install.sh dist/cleanframe-tv-<sha>-staging.apk              staging build (never Home)
#        ./install.sh dist/cleanframe-tv-<sha>.apk [--make-home]        release build (the live Home)
#        ./install.sh dist/cleanframe-tv-<sha>.apk [--make-home] --skip-staging
# A staging APK never touches Home. A release APK is the live Home's own package, so installing it
# REPLACES the running Home in place with or without --make-home; --make-home additionally sets it as
# the Home activity, presses Home and fails unless Home resolves to it and it is the resumed activity.
#
# Promotion gate: a release APK for <sha> is refused unless dist/cleanframe-tv-<sha>.<stick>.staging-ok
# exists for THIS stick. Only `tools/stick-test.sh --staging` writes it, after a full pass of the
# staging build of that same commit (SHA read off the stick) on that stick. So: ./build.sh --staging,
# install it, stick-test it, then ./build.sh and install the release on the same stick. The marker
# must record the tested APK's sha256 and stick_id=<stick>. <stick> is the stick's identity read off
# it (stickid in tools/adb-lib.sh: ro.product.model-ro.serialno), not its address: STICK= picks the
# stick (required: there is no default address), and a pass on one
# never unlocks the other, while passes on both for the same SHA stand side by side. An unreadable
# identity, or a marker without stick_id=, is refused.
# Recovery gate: when `git diff --name-only <base>..<sha>` touches android/ (all of it is the native
# safety net) or web/device.js, the release is also refused without dist/cleanframe-tv-<sha>.<stick>.recovery-ok,
# which only a full pass of `tools/stick-test.sh --recovery` on that staging build writes (same APK
# sha256 and stick as the staging-ok marker). <base> is the SHA installed as Home, read off the stick
# (home.cleanframe.tv's versionName); if it cannot be read or is not a commit here, the recovery
# pass is required.
# Every APK must be signed by the pinned key (tools/signer-cert.sha256: apksigner's certificate
# SHA-256), and a release APK's sha256 must equal the one ./build.sh recorded in <apk>.sha256.
# --skip-staging bypasses the staging and recovery gates (not the signer or sha256 checks). EMERGENCIES ONLY (e.g. Home is broken and the fix cannot wait,
# or the staging build cannot run); say so in the commit log when you use it.
# A release APK must be a clean commit (versionName +<12-hex sha>): a -dirty one is refused with or
# without --make-home/--skip-staging, since it would replace Home (docs/RULES.md rule 4). Dirty staging
# builds are fine. A release APK's commit must also be on origin/main (pushed; build.sh refuses to build
# any other), whatever the flags; a staging APK may be any commit (a feature branch). After EVERY install (staging or release, with or without --make-home) HOME must
# still resolve to home.cleanframe.tv, or the install fails (printing the pm set-home-activity command
# that fixes it). A release install that lost Home runs that command itself once and re-checks first; so
# does a --make-home that did not take: every way of ending without Home goes through that one path.
# Every install also grants what the app cannot ask for itself (Home never shows a dialog): the app-op
# WRITE_SETTINGS (Sleep after, Sleep now) and, on Android 12+, BLUETOOTH_CONNECT (Settings > Bluetooth).
# Both are read back; one that did not take fails the install, after the Home checks have run.
set -euo pipefail
# The APK argument is read where the caller is, before the cd below: a path that exists from the
# caller's directory (relative or not) is made absolute; any other is taken from the repo as before
# (./install.sh dist/... from anywhere).
APK_ARG=; if [ $# -ge 1 ] && [ -e "$1" ]; then APK_ARG=$(realpath -- "$1"); fi
cd "$(dirname "$0")"
DONE=0
on_exit() { local rc=$?; [ $DONE = 1 ] || [ "$rc" = 0 ] || echo "INSTALL_FAIL (exit $rc): no INSTALL_OK; see the error above" >&2; }
trap on_exit EXIT   # also covers an adb step dying under pipefail, which used to exit silently
usage() { echo "usage: $0 dist/cleanframe-tv-<sha>[-staging].apk [--make-home] [--skip-staging]" >&2; exit 2; }
[ $# -ge 1 ] || usage
APK=${APK_ARG:-$1}; shift; MAKE_HOME=0; SKIP_STAGING=0
for arg in "$@"; do
  case $arg in --make-home) MAKE_HOME=1 ;; --skip-staging) SKIP_STAGING=1 ;; *) usage ;; esac
done
S=${STICK:-}
[ -n "$S" ] || { echo "refusing: set STICK=<host:port>; there is no default stick" >&2; exit 2; }
# Every adb call has a deadline: on a half-dead connection adb waits forever, and an install that
# never returns is worse than one that fails. ADB_TIMEOUT (default 60 s) per call; the APK push
# gets ADB_INSTALL_TIMEOUT (default 300 s). A timeout prints ADB TIMEOUT and fails the step, so
# set -e / pipefail end the run with INSTALL_FAIL.
ADB_TIMEOUT=${ADB_TIMEOUT:-60}; ADB_INSTALL_TIMEOUT=${ADB_INSTALL_TIMEOUT:-300}
command -v timeout >/dev/null || { echo "refusing: no timeout(1) (coreutils) to bound adb calls" >&2; exit 1; }
# adbt, a, first, homepkg, top: tools/adb-lib.sh (shared with tools/stick-test.sh). Timeout switch
# off: a timeout prints ADB TIMEOUT and fails the call, nothing is logged to a file.
ADB_TIMEOUT_LOG=
ADB_LIB=$PWD/tools/adb-lib.sh
[ -f "$ADB_LIB" ] || { echo "refusing: $ADB_LIB is missing (the shared adb helpers); is this a full checkout?" >&2; exit 1; }
# shellcheck source=tools/adb-lib.sh
. "$ADB_LIB"
BT=${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}/sdk/build-tools/34.0.0
AAPT2=$BT/aapt2
BADGING=$("$AAPT2" dump badging "$APK")
PKG=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING")
VN=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | first)
NEW=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | first)
NAME=$(basename "$APK" .apk); NAME=${NAME#cleanframe-tv-}
case $PKG in
  home.cleanframe.tv)
    [[ $NAME != *-staging ]] || { echo "refusing: file is named staging but the package is $PKG" >&2; exit 1; }
    WANT=$NAME ;;
  home.cleanframe.tv.staging)
    [[ $NAME == *-staging ]] || { echo "refusing: package is $PKG but the file is not named *-staging.apk" >&2; exit 1; }
    WANT=${NAME%-staging}
    [ $MAKE_HOME = 0 ] || { echo "refusing: a staging APK is never made Home" >&2; exit 1; } ;;
  *) echo "refusing: unexpected package '$PKG' in $APK" >&2; exit 1 ;;
esac
[ "${VN#*+}" = "$WANT" ] || { echo "refusing: APK versionName $VN does not match its file name ($WANT)" >&2; exit 1; }
[ -n "$NEW" ] || { echo "refusing: no versionCode in $APK" >&2; exit 1; }
# Rule 4: a dirty build is never Home. The release package replaces Home in place, so no flag
# (not --skip-staging, not leaving out --make-home) lets one through. Fail closed on anything but a SHA.
if [ "$PKG" = home.cleanframe.tv ] && ! [[ $WANT =~ ^[0-9a-f]{12}$ ]]; then
  echo "refusing: release APK $WANT is not a clean commit (dirty builds are for the staging app only;" >&2
  echo "commit, then ./build.sh, or ./build.sh --staging --dirty to test on the stick)" >&2
  exit 1
fi
# The signer is the pinned release key (a different key could not update the app in place anyway,
# but a mix-up should be named here, not by the stick).
CERTS=$("$BT/apksigner" verify --print-certs "$APK" 2>&1) || { echo "refusing: apksigner verify failed for $APK: ${CERTS//$'\n'/ }" >&2; exit 1; }
GOTCERT=$(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' <<<"$CERTS")
WANTCERT=$(tr -d '[:space:]' < tools/signer-cert.sha256 2>/dev/null || true)
if ! [[ $WANTCERT =~ ^[0-9a-f]{64}$ ]] || [ "$GOTCERT" != "$WANTCERT" ]; then
  echo "refusing: $APK is signed by '${GOTCERT//$'\n'/ }', not the pinned key ('${WANTCERT:-no tools/signer-cert.sha256}')" >&2; exit 1
fi
# A release APK is byte for byte the one build.sh made (its recorded sha256).
if [ "$PKG" = home.cleanframe.tv ]; then
  GOTSUM=$(sha256sum "$APK" | sed -n 's/^\([0-9a-f]\{64\}\)[[:space:]].*/\1/p')
  WANTSUM=$(sed -n 's/^\([0-9a-f]\{64\}\)[[:space:]].*/\1/p' "$APK.sha256" 2>/dev/null | first || true)
  if [ -z "$WANTSUM" ] || [ "$GOTSUM" != "$WANTSUM" ]; then
    echo "refusing: $APK has sha256 $GOTSUM, but build.sh recorded '${WANTSUM:-nothing}' ($APK.sha256): rebuild it with ./build.sh" >&2; exit 1
  fi
  # The release (the live Home) is a commit on origin/main, i.e. pushed (build.sh refuses to build any
  # other; this catches an APK built before that rule, or by hand). Whatever the flags: --skip-staging too.
  if ! git cat-file -e "$WANT^{commit}" 2>/dev/null; then
    echo "refusing: release APK $WANT is not a commit in this repo ($(pwd -P)); install releases from the authority clone that built them" >&2; exit 1
  fi
  if ! git rev-parse -q --verify 'refs/remotes/origin/main^{commit}' >/dev/null || ! git merge-base --is-ancestor "$WANT" refs/remotes/origin/main; then
    echo "refusing: release APK $WANT is not on origin/main (unpushed, another branch, or no origin/main here): only main's pushed" >&2
    echo "history becomes Home. Merge it to main, git push, rebuild with ./build.sh; or test it as the staging app (./build.sh --staging)." >&2
    exit 1
  fi
fi

if [ "$PKG" = home.cleanframe.tv ] && [ $SKIP_STAGING = 1 ]; then
  echo "!!!!!!!! --skip-staging: installing release $WANT over the live Home WITHOUT a staging pass !!!!!!!!" >&2
fi

CONN=$(adbt "$ADB_TIMEOUT" connect "$S" 2>&1 || true)   # exits 0 even when it fails; get-state decides
[ "$(a get-state 2>/dev/null || true)" = device ] || { echo "refusing: $S unreachable over adb (${CONN//$'\n'/ })" >&2; exit 1; }
# Promotion gate (header): the markers are per stick. A pass on one stick never unlocks another (two
# sticks, STICK=), so the marker for THIS stick is named by its identity (stickid, tools/adb-lib.sh:
# model + serial read off the device, not the adb address) and must record it as stick_id=.
if [ "$PKG" = home.cleanframe.tv ] && [ $SKIP_STAGING = 0 ]; then
  SID=$(stickid)
  [ -n "$SID" ] || { echo "refusing: could not read the stick's identity (ro.product.model, ro.serialno) off $S; the staging pass is per stick" >&2; exit 1; }
  OK=dist/cleanframe-tv-$WANT.$SID.staging-ok
  if [ ! -f "$OK" ] || ! grep -qx "sha=$WANT" "$OK" || ! grep -qx "stick_id=$SID" "$OK" || ! grep -qxE 'apk_sha256=[0-9a-f]{64}' "$OK"; then
    OTHERS=$(compgen -G "dist/cleanframe-tv-$WANT.*.staging-ok" || true)
    echo "refusing: no staging pass for $WANT on this stick ($SID at $S: $OK missing, without stick_id=$SID, or without the tested APK's sha256)." >&2
    [ -z "$OTHERS" ] || echo "(a pass on another stick does not count: ${OTHERS//$'\n'/ })" >&2
    echo "Run ./build.sh --staging, install the staging APK on this stick, then tools/stick-test.sh --staging. Emergency only: --skip-staging." >&2
    exit 1
  fi
  echo "staging pass: $(tr '\n' ' ' < "$OK")"
fi
# The stick refuses an older versionCode (user build, app not debuggable); say how to roll back instead.
OLD=$(a shell dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | first | tr -d '\r')
if [ -n "$OLD" ] && [ "$NEW" -lt "$OLD" ]; then
  echo "refusing: versionCode $NEW is older than the stick's $OLD for $PKG. Roll back with tools/rollback.sh <sha>." >&2; exit 1
fi
# Recovery gate (header): only for a release, and only when staging is not skipped.
if [ "$PKG" = home.cleanframe.tv ] && [ $SKIP_STAGING = 0 ]; then
  BASE=$(a shell dumpsys package "$PKG" | sed -n 's/.*versionName=[^+ ]*+\([^ ]*\).*/\1/p' | first | tr -d '\r')
  NEED=; CHANGED=
  if ! [[ $BASE =~ ^[0-9a-f]{12}$ ]] || ! git cat-file -e "$BASE^{commit}" 2>/dev/null || ! git cat-file -e "$WANT^{commit}" 2>/dev/null; then
    NEED="the SHA installed as Home is unknown ('${BASE:-none}' is not a commit here)"
  elif ! CHANGED=$(git diff --name-only "$BASE..$WANT" -- android web/device.js); then
    NEED="git diff $BASE..$WANT failed"
  elif [ -n "$CHANGED" ]; then
    NEED="$BASE..$WANT changes $(wc -l <<<"$CHANGED" | tr -d ' ') file(s) under android/ or web/device.js, e.g. $(first <<<"$CHANGED")"
  fi
  if [ -z "$NEED" ]; then
    echo "recovery pass not required: $BASE..$WANT changes nothing under android/ or web/device.js"
  else
    ROK=dist/cleanframe-tv-$WANT.$SID.recovery-ok
    RSUM=$(sed -n 's/^apk_sha256=\([0-9a-f]\{64\}\)$/\1/p' "$ROK" 2>/dev/null | first || true)
    if [ ! -f "$ROK" ] || ! grep -qx "sha=$WANT" "$ROK" || ! grep -qx "stick_id=$SID" "$ROK" || [ -z "$RSUM" ] \
       || [ "$RSUM" != "$(sed -n 's/^apk_sha256=//p' "$OK" | first)" ]; then
      echo "refusing: no --recovery pass for $WANT on this stick ($ROK missing, without stick_id=$SID, or for another staging APK than $OK)," >&2
      echo "and it is required: $NEED. Run tools/stick-test.sh --recovery on the staging build. Emergency only: --skip-staging." >&2
      exit 1
    fi
    echo "recovery pass ($NEED): $(tr '\n' ' ' < "$ROK")"
  fi
fi
adbt "$ADB_INSTALL_TIMEOUT" -s "$S" install -r "$APK"
GOT=$(a shell dumpsys package "$PKG" | sed -n 's/.*versionName=[^+ ]*+\([^ ]*\).*/\1/p' | first | tr -d '\r')
echo "on stick: $PKG $GOT  wanted: $WANT"
[ "$GOT" = "$WANT" ] || { echo "PROVENANCE MISMATCH" >&2; exit 1; }
# Grant before Home is pressed, so the app's first start has them. A failure is only recorded here: the
# Home checks below must still run, or a failed grant could end the install without Home.
GRANT_FAIL=
BTPERM=android.permission.BLUETOOTH_CONNECT
a shell appops set "$PKG" WRITE_SETTINGS allow || true
if [[ $(a shell appops get "$PKG" WRITE_SETTINGS | tr -d '\r' | first || true) == "WRITE_SETTINGS: allow"* ]]; then
  echo "granted: WRITE_SETTINGS"
else
  GRANT_FAIL="adb -s $S shell appops set $PKG WRITE_SETTINGS allow"
fi
SDK=$(a shell getprop ro.build.version.sdk | tr -d '\r' | first || true)
if ! [[ $SDK =~ ^[0-9]+$ ]]; then
  GRANT_FAIL+="${GRANT_FAIL:+; }adb -s $S shell pm grant $PKG $BTPERM (Android 12+ only: could not read the stick's SDK level, got '$SDK')"
elif [ "$SDK" -ge 31 ]; then
  a shell pm grant "$PKG" "$BTPERM" || true
  if [[ $(a shell dumpsys package "$PKG" || true) == *"$BTPERM: granted=true"* ]]; then
    echo "granted: BLUETOOTH_CONNECT"
  else
    GRANT_FAIL+="${GRANT_FAIL:+; }adb -s $S shell pm grant $PKG $BTPERM"
  fi
fi
in_front() { [ "$TOP" = "$PKG/.MainActivity" ] || [ "$TOP" = "$PKG/$PKG.MainActivity" ]; }
NOT_SWITCHED=0
if [ $MAKE_HOME = 1 ]; then
  # A failed adb call here does not end the install: the checks decide, and the path below sets Home back.
  a shell pm set-home-activity "$PKG/.MainActivity" || true
  a shell input keyevent KEYCODE_HOME || true; sleep 3
  # Home must now BE this app: HOME resolves to it (not the chooser, not FallbackHome) and it is in front.
  HOMEPKG=$(homepkg)
  TOP=$(top)
  echo "HOME resolves to: ${HOMEPKG:-nothing}  resumed: ${TOP:-nothing}"
  if [ "$HOMEPKG" != "$PKG" ] || ! in_front; then
    echo "HOME NOT SWITCHED: want HOME -> $PKG and $PKG/.MainActivity resumed" >&2; NOT_SWITCHED=1
  fi
fi
# Every install, staging or release, with or without --make-home: HOME must resolve to the live
# CleanFrame afterwards (docs/RULES.md rule 6). A staging install must leave Home alone; a release
# install replaces Home in place, and Android may drop it as Home (chooser, FallbackHome) then.
# (Which activity is in front is checked only with --make-home, which presses Home first; without
# it the person may be watching another app, and that is not a failure.)
# A release install that lost Home sets it back ONCE (the package it just installed is the live Home)
# and checks again; a staging install never touches Home, so it only fails. Either way a Home that is
# still wrong fails the install, with the command that fixes it.
HOMEPKG=$(homepkg)
echo "HOME resolves to: ${HOMEPKG:-nothing}"
FIX_HOME="adb -s $S shell pm set-home-activity home.cleanframe.tv/.MainActivity"
if [ "$HOMEPKG" != home.cleanframe.tv ] && [ "$PKG" = home.cleanframe.tv ]; then
  echo "HOME LOST after the release install; setting it back once: pm set-home-activity home.cleanframe.tv/.MainActivity" >&2
  a shell pm set-home-activity home.cleanframe.tv/.MainActivity || true   # the re-check decides
  HOMEPKG=$(homepkg)
  echo "HOME resolves to (after set-home-activity): ${HOMEPKG:-nothing}"
fi
if [ "$HOMEPKG" != home.cleanframe.tv ]; then
  echo "HOME CHANGED: want HOME -> home.cleanframe.tv after installing $PKG (it resolves to '${HOMEPKG:-nothing}')" >&2
  echo "fix: $FIX_HOME" >&2
  exit 1
fi
# --make-home that did not take, with HOME now right (it may just have been set back): it must also come to
# the front when Home is pressed, or the install fails with the same command.
if [ $NOT_SWITCHED = 1 ]; then
  a shell input keyevent KEYCODE_HOME || true; sleep 3
  TOP=$(top)
  echo "resumed (Home pressed again): ${TOP:-nothing}"
  if ! in_front; then
    echo "HOME NOT SWITCHED: HOME resolves to $PKG, but $PKG/.MainActivity is not resumed" >&2
    echo "fix: $FIX_HOME (then press Home)" >&2
    exit 1
  fi
fi
if [ -n "$GRANT_FAIL" ]; then
  echo "GRANT FAILED: the app is installed, but these did not take. fix: $GRANT_FAIL" >&2
  exit 1
fi
DONE=1
echo INSTALL_OK

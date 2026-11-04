#!/bin/bash
# Drive CleanFrame on the stick with the remote and assert each step (docs/RULES.md rule 6).
# Usage: tools/stick-test.sh              test the live Home build (home.cleanframe.tv)
#        tools/stick-test.sh --staging    test the staging build (home.cleanframe.tv.staging)
#        PKG=<package> tools/stick-test.sh
#        tools/stick-test.sh --recovery [--slow]
#                                         staging only (refused for the live Home): crash, drop and
#                                         kill the page on purpose and check the native fallback
#                                         and its recovery paths (tools/stick-lib.sh); --slow adds
#                                         the 60 s wait for Home to retry a failure fallback
#        tools/stick-test.sh --reboot     live Home only: reboot the stick and check it comes back
#                                         to CleanFrame with the page drawn; prints the boot time
# Markers are per stick: dist/cleanframe-tv-<sha>.<stick>.{staging,recovery}-ok, <stick> being the stick's
# identity read off it (stickid, tools/adb-lib.sh: model + serial, not the STICK= address), recorded as
# stick_id=; a staging or --recovery run of a clean commit refuses to start if it cannot read it.
# A pass on one stick (STICK=) never touches or stands in for another stick's markers.
# --reboot neither reads nor writes a marker. --recovery deletes the SHA's recovery-ok marker first;
# a full pass of a clean staging commit writes dist/cleanframe-tv-<sha>.<stick>.recovery-ok (install.sh needs
# it to promote a change to android/ or web/device.js), and a recovery FAIL or abort deletes that
# SHA's staging-ok marker as well (the staging build failed a test: stage it again).
# Prints PASS/FAIL per check and ends with STICK_TEST_OK (exit 0) or STICK_TEST_FAIL <n> (exit 1).
# Needs the app installed; reads page state through WebView DevTools (tools/devtools-eval.mjs).
# Only the Home build can be tested with the Home key: for staging "Home" is simulated by starting
# the staging activity with the HOME category (the same onNewIntent path) and Home-key-only checks
# are SKIPped. "Home closes sheet/panel" first opens All apps and the appearance panel through the
# page's own QA hook (ShoreQA.scene), checks each is open, then presses Home and checks it shut.
# Both builds check that HOME still resolves to home.cleanframe.tv (staging must never take Home).
# Promotion gate: with --staging, the SHA's old dist/cleanframe-tv-<sha>.<stick>.staging-ok is deleted as
# soon as the SHA is read off the stick, and only a full pass for a clean commit writes it again,
# so a failed re-run never leaves an earlier pass standing. ./install.sh refuses the release APK for
# <sha> without it. Both markers record the tested APK's sha256, read off the stick (pm path +
# sha256sum), which must equal the one ./build.sh recorded in dist/cleanframe-tv-<sha>-staging.apk.sha256.
# Photo groups need Background = Photo: a run that finds it flat switches it to Photo through
# DevTools, runs the section, then restores the type and wallpaper (also from the EXIT trap, so a
# step that aborts mid-section still leaves the stick as found). A section that cannot run FAILs.
# SKIPs. A SKIP is not a FAIL, so a run whose only non-passes are SKIPs still writes its marker.
# These are the kinds, and each SKIP line says why:
#   - Home-key-only checks on the staging build (it is not Home; "Home" is simulated by intent);
#   - typing a name with the stick's keyboard when it has none (no default input method): the name
#     is set through DevTools instead and every check after it still runs;
#   - a whole feature section the APP PREDATES. tools/rollback.sh keeps this script at HEAD while the
#     app goes back to an older SHA, so before each section newer than the staging build (Home
#     additions, Screen and Sleep, Long press, Rename and Change icon, Photo groups, Photo groups straight
#     to the stick, Random rotation, Bluetooth, Bluetooth Unpair, Uninstall) feature() asks
#     the page through DevTools whether it loads the feature's script and has its API. The section
#     is SKIPped ("app predates <feature>") only if the page lacks it AND so does the tested commit's
#     own web/ (git, at the clean SHA read from versionName: index.html does not load the script, or
#     the script does not export the API). A page without a feature its commit has, a script that
#     loaded without its API, an unreadable probe, or a SHA that is not a clean commit here is a
#     FAIL. A feature the page has always runs its whole section, and any misbehaviour FAILs;
#   - one check this stick gives nothing to check on: the dock tile's mark when no library mark lists
#     that app; Bluetooth's "stays paired" reason when no listed device stays paired; Unpair's question
#     when no device can be unpaired;
#   - in --recovery (tools/stick-lib.sh): Home's 60 s retry without --slow, and a section for native
#     behaviour the tested commit's own code predates (native_lacks).
# Nothing else is a SKIP: a flat Background is switched to Photo for the photo groups, as above.
# Logs: only the buffers read (main, system, crash, events) are cleared at the start; they are read
# after the app launch, before each feature section (feature(), so also at the end of the one before)
# and at the end, because the 256 KiB main buffer wraps in minutes and a renderer death whose page
# rebuilds can scroll out mid-run; an adb or logcat failure is a FAIL, never "0 crashes". Counted over
# all those reads, for the package under test:
#   Java crash    crash buffer  "Process: <pkg>, PID:"
#   native crash  tombstone     ">>> <pkg>[:proc] <<<" or "Cmdline: <pkg>[:proc]"
#   renderer      WebView (aw_browser_terminator.cc), logged by OUR app's PIDs only:
#                 "Renderer process (<pid>) crash detected", "Render process (<pid>)'s crash
#                 wasn't handled", "Render process (<pid>) kill (OOM or update) wasn't handed".
#                 Chromium logs the first for every abnormal renderer exit (crash or kill); a
#                 normal exit (the WebView destroyed by the Back-hold list) logs nothing. The
#                 renderer's own tombstone names com.google.android.webview:sandboxed_process*,
#                 not our package, so it is caught through this line instead.
#   ANR           "ANR in <pkg>" or events "am_anr  : [..,<pkg>," (tag padded to 8 columns)
# Unknown arguments are refused; an unreachable stick or any aborted step ends STICK_TEST_FAIL.
set -euo pipefail
cd "$(dirname "$0")/.."
S=${STICK:-}
[ -n "$S" ] || { echo "refusing: set STICK=<host:port>; there is no default stick" >&2; exit 2; }
PKG_SET=${PKG:-}
PKG=${PKG:-home.cleanframe.tv}
MODE=normal; SLOW=0
usage() { echo "usage: $0 [--staging | --recovery [--slow] | --reboot]" >&2; exit 2; }
for arg in "$@"; do
  case $arg in
    --staging) PKG=home.cleanframe.tv.staging ;;
    --recovery|--reboot) [ $MODE = normal ] || usage; MODE=${arg#--} ;;
    --slow) SLOW=1 ;;
    *) usage ;;
  esac
done
[ $SLOW = 0 ] || [ $MODE = recovery ] || usage
# --recovery crashes the page on purpose: it defaults to staging and refuses the live Home.
[ $MODE != recovery ] || [ -n "$PKG_SET" ] || PKG=home.cleanframe.tv.staging
case $PKG in
  home.cleanframe.tv) STAGING=0; ACT=$PKG/.MainActivity ;;
  home.cleanframe.tv.staging) STAGING=1; ACT=$PKG/home.cleanframe.tv.MainActivity ;;
  *) echo "unknown PKG $PKG" >&2; exit 2 ;;
esac
if [ $MODE = recovery ] && [ $STAGING = 0 ]; then
  echo "--recovery crashes the page on purpose: staging only, refused for $PKG" >&2; exit 2
fi
if [ $MODE = reboot ] && [ $STAGING = 1 ]; then
  echo "--reboot checks the live Home after a reboot: refused for $PKG (staging is never Home)" >&2; exit 2
fi
PORT=${DEVTOOLS_PORT:-9333}; export DEVTOOLS_PORT=$PORT
FAILS=0; PASSES=0
# Every adb call has a deadline: on a half-dead connection adb waits forever. ADB_TIMEOUT (default
# 60 s) per call. A timeout prints "FAIL  adb timeout: ..." at once (stderr: a call's stdout is often
# captured) and is logged to $ADB_TIMEOUT_LOG, which the end of the run counts as a FAIL, so a timeout
# swallowed by "|| true" or a capture still fails the run instead of passing silently.
ADB_TIMEOUT=${ADB_TIMEOUT:-60}
ADB_LIB=$PWD/tools/adb-lib.sh
[ -f "$ADB_LIB" ] || { echo "$ADB_LIB is missing (the shared adb helpers); is this a full checkout?" >&2; exit 2; }
command -v timeout >/dev/null || { echo "no timeout(1) (coreutils) to bound adb calls" >&2; exit 2; }
mkdir -p "$HOME/scratch"   # never /tmp; removed by cleanup on exit
# adbt, a, first, homepkg, top, ui: tools/adb-lib.sh (shared with install.sh). Timeout switch ON: every
# timeout is appended to this file as well as printed.
ADB_TIMEOUT_LOG=$(mktemp "$HOME/scratch/cf-stick-adb-timeouts.XXXXXX")
# tools/devtools-eval.mjs's stderr (why a DevTools call gave DT_ERROR: no page, refused socket, an
# error reply, a thrown exception's message), one "DT_ERROR for: <expression>" line after each failed
# call. Kept after the run when not empty (its path is printed), else removed.
DT_LOG=$(mktemp "$HOME/scratch/cf-stick-devtools.XXXXXX")
# shellcheck source=tools/adb-lib.sh
. "$ADB_LIB"
# Fold the adb timeouts into the result: one FAIL listing them (called before the totals).
check_adb_timeouts() {
  local n; n=$(grep -c . "$ADB_TIMEOUT_LOG" 2>/dev/null || true)
  if [ "${n:-0}" -gt 0 ]; then fail "adb calls answered in time" "$n timed out: $(head -3 "$ADB_TIMEOUT_LOG" | tr '\n' ';')" "0 (ADB_TIMEOUT=$ADB_TIMEOUT s)"
  else pass "adb calls answered in time" "0 timeouts"; fi
}
k() { a shell input keyevent "$1" >/dev/null; sleep "${2:-1}"; }
# Where the DevTools errors went (info, not a check: a cold start's waits are DT_ERRORs too).
dt_log_note() { [ ! -s "$DT_LOG" ] || echo "NOTE  devtools errors: $(grep -c . "$DT_LOG") lines kept in $DT_LOG"; }
# Evaluate JS in the page; prints DT_ERROR when DevTools fails, answers nothing or undefined, or the
# page throws.
dt() {
  local pid out
  pid=$(a shell pidof "$PKG" | tr -d '\r' || true)
  [ -n "$pid" ] || { echo DT_ERROR; return 0; }
  a forward "tcp:$PORT" "localabstract:webview_devtools_remote_$pid" >/dev/null || { echo DT_ERROR; return 0; }
  out=$(node tools/devtools-eval.mjs "$1" 2>>"$DT_LOG") || out=
  # An expression that gives undefined is no answer either (expect_set/expect_not would pass on it):
  # tools/devtools-eval.mjs exits non-zero for it, and an older one's {"result":{"type":"undefined"}}
  # is refused here too.
  case $out in
    ''|*'"exceptionDetails"'*|'{"result":{"type":"undefined"'*)
      printf 'DT_ERROR for: %s\n' "${1:0:160}" >> "$DT_LOG"; echo DT_ERROR ;;
    *) echo "$out" ;;
  esac
}
pass() { PASSES=$((PASSES + 1)); echo "PASS  $1: $2"; }
fail() { FAILS=$((FAILS + 1)); echo "FAIL  $1: got '$2', want $3"; }
skip() { echo "SKIP  $1: $2"; }
# expect <name> <got> <want>: exact match; an empty or DT_ERROR answer always fails.
expect() {
  if [ -n "$2" ] && [ "$2" != DT_ERROR ] && [ "$2" = "$3" ]; then pass "$1" "$2"; else fail "$1" "$2" "'$3'"; fi
}
expect_ge() {
  if [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -ge "$3" ]; then pass "$1" "$2"; else fail "$1" "$2" ">= $3"; fi
}
expect_set() {   # any real answer other than null
  if [ -n "$2" ] && [ "$2" != DT_ERROR ] && [ "$2" != null ] && [[ $2 != *'"subtype":"null"'* ]]; then pass "$1" "$2"; else fail "$1" "$2" "a value"; fi
}
expect_not() {   # a real answer, other than null (focus lost is not focus moved), that differs from <unwanted>
  if [ -n "$2" ] && [ "$2" != DT_ERROR ] && [ "$2" != null ] && [[ $2 != *'"subtype":"null"'* ]] && [ "$2" != "$3" ]; then pass "$1" "$2"; else fail "$1" "$2" "a value, not '$3'"; fi
}
# feature <id> <label> <file> <global> [<member>...]: does the page under test have this feature?
# The page (DevTools, /*feature:<id>*/) answers "has" when it loads web/<file> (a <script src>) and
# window.<global> is an object with every <member>; "absent:<why>" when no script loads <file>, or
# <global> lacks a member (an older version of that module); "broken:<why>" when <file> is loaded but
# <global> is missing (the script failed). Returns 0 (run the section) only for "has". "absent" SKIPs
# the section only when the tested commit agrees (commit_lacks); anything else FAILs. Either way a
# non-zero return skips the section: its checks would only repeat the same FAIL. Reads the logs first
# (snap): every section starts here, so the crash and renderer counts cover the whole run, whatever a
# section is called or how it ends.
feature() {
  local id=$1 label=$2 file=$3 global=$4 got m js rc=0; shift 4
  snap
  js="var f=\"$file\",g=window.$global,s=document.querySelectorAll(\"script[src]\"),l=false,i;"
  js+="for(i=0;i<s.length;i++)if(s[i].getAttribute(\"src\")===f)l=true;if(!l)return \"absent:the page loads no \"+f;"
  js+="if(!g||typeof g!==\"object\")return \"broken:\"+f+\" is loaded but window.$global is \"+(g===null?\"null\":typeof g);"
  for m; do js+="if(typeof g.$m===\"undefined\")return \"absent:window.$global has no $m\";"; done
  got=$(dt "/*feature:$id*/(function(){${js}return \"has\"})()"); got=${got#\"}; got=${got%\"}
  case $got in
    has) return 0 ;;
    absent:*)
      commit_lacks "$file" "$@" || rc=$?
      if [ $rc = 0 ]; then skip "$label" "app predates $label (${got#absent:}; $SHA's web/ has none either)"
      elif [ $rc = 1 ]; then fail "$label: in the page" "${got#absent:}" "the feature ($SHA's web/ has it)"
      elif [ $rc = 3 ]; then fail "$label: in the page" "${got#absent:}" "the feature, or a commit that predates it (git could not read $SHA's web/)"
      else fail "$label: in the page" "${got#absent:}" "the feature, or a clean commit here that predates it ('$SHA' is not one)"; fi ;;
    *) fail "$label: in the page" "$got" "'has' (web/$file loaded, window.$global${*:+ with $*})" ;;
  esac
  return 1
}
# commit_lacks <file> [<member>...]: 0 when the tested commit's web/ lacks the feature (index.html
# loads no <file>, or <file> exports no "<member>: "), 1 when it has it, 2 when it cannot tell ($SHA
# is not a clean commit in this repo), 3 when git grep neither found nor missed (exit other than 0 or 1,
# an unreadable blob): that is no answer, never "the commit lacks it". git grep, never a pipeline (tests/tools.test.sh).
commit_lacks() {
  local file=$1 m rc; shift
  clean_sha && git cat-file -e "$SHA^{commit}" 2>/dev/null || return 2
  rc=0; git grep -q -F -e "src=\"$file\"" "$SHA" -- web/index.html 2>/dev/null || rc=$?
  case $rc in 0) ;; 1) return 0 ;; *) return 3 ;; esac
  for m; do
    rc=0; git grep -q -F -e "$m: " "$SHA" -- "web/$file" 2>/dev/null || rc=$?
    case $rc in 0) ;; 1) return 0 ;; *) return 3 ;; esac
  done
  return 1
}
DONE=0
SHA=; SID=; APK_SHA256=; PG_STARTED=0; HX_ORIG=; HX_SLEEP=; HX_NAP=0; AE_STARTED=0; ROT_ORIG=
mk() { echo "dist/cleanframe-tv-$SHA.$SID.$1-ok"; }   # mk staging|recovery: that marker for $SHA on this stick ($SID)
clean_sha() { [[ $SHA =~ ^[0-9a-f]{12}$ ]]; }
cleanup() {
  local rc=$?
  if [ $DONE = 0 ]; then
    # An aborted step: leave the stick as found (photo groups), and an aborted --recovery is a FAIL.
    [ $PG_STARTED = 0 ] || pg_leave_as_found || true
    [ -z "$HX_ORIG" ] || hx_restore >/dev/null || true
    [ $HX_NAP = 0 ] || hx napoff 'FrameDevice.sleepCancel()' >/dev/null || true
    [ -z "$HX_SLEEP" ] || hx sleepback "CleanFrameExtras.set(\"screenSleep\",\"$HX_SLEEP\")" >/dev/null || true
    [ $AE_STARTED = 0 ] || ae_restore >/dev/null || true
    [ -z "$ROT_ORIG" ] || rot_restore >/dev/null || true
    if [ "$MODE" = recovery ] && clean_sha; then rm -f "$(mk recovery)" "$(mk staging)"; echo "recovery aborted: deleted $(mk staging) and $(mk recovery)"; fi
  fi
  timeout -k 5 "$ADB_TIMEOUT" adb -s "$S" forward --remove "tcp:$PORT" </dev/null >/dev/null 2>&1 || true
  rm -f "$ADB_TIMEOUT_LOG"
  if [ -s "$DT_LOG" ]; then [ $DONE = 1 ] || dt_log_note; else rm -f "$DT_LOG"; fi
  [ -z "${PG_TMP:-}" ] || rm -rf "$PG_TMP"
  [ $DONE = 1 ] || { echo "FAIL  aborted: a step failed (exit $rc)"; echo "STICK_TEST_FAIL aborted"; exit 1; }
}
trap cleanup EXIT
# Home: the real key for the Home build; for staging, the same HOME-category intent, explicitly.
home() {
  if [ $STAGING = 0 ]; then k HOME 3
  else a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "$ACT" >/dev/null; sleep 3; fi
}

# wait_ready: after an `am start`, poll READY about once a second until the page answers true, for
# up to READY_WAIT s (default 60; also at most READY_WAIT polls). PASS "page ready" with the wait, or
# FAIL with the last answer, and carry on (the FAIL fails the run). A fixed sleep 4 raced the cold
# start after a fresh install (2026-11-01, 6db785b staging: DT_ERROR for the first two checks).
READY='document.querySelector("[data-action]")!==null&&typeof window.ShoreQA==="object"'
READY_WAIT=${READY_WAIT:-60}
wait_ready() {
  local t0=$SECONDS n=0 r=
  while :; do
    n=$((n + 1)); r=$(dt "$READY")
    if [ "$r" = true ]; then pass "page ready" "after $((SECONDS - t0)) s ($n polls)"; return 0; fi
    if [ $n -ge "$READY_WAIT" ] || [ $((SECONDS - t0)) -ge "$READY_WAIT" ]; then
      fail "page ready" "$r after $((SECONDS - t0)) s ($n polls)" "true within $READY_WAIT s"; return 0
    fi
    sleep 1
  done
}

FOC='(function(){var e=document.querySelector(".is-focus[data-action]");return e?e.getAttribute("data-action"):null})()'
SHEET='!document.getElementById("sheet").hidden'
PAGE='document.querySelector("[data-action]")!==null'
PANEL='!document.getElementById("appearance").hidden'
VIEW='ShoreQA.state().view'
CONN=$(adbt "$ADB_TIMEOUT" connect "$S" 2>&1 || true)   # exits 0 even when it fails; get-state decides
if [ "$(a get-state 2>/dev/null || true)" != device ]; then
  echo "FAIL  adb: $S unreachable (${CONN//$'\n'/ })"; DONE=1; echo "STICK_TEST_FAIL 1"; exit 1
fi
VN=$(a shell dumpsys package "$PKG" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | first | tr -d '\r')
[ -n "$VN" ] || { echo "$PKG is not installed on $S"; DONE=1; echo "STICK_TEST_FAIL 1"; exit 1; }
SHA=${VN#*+}
if [ $STAGING = 1 ] && clean_sha; then   # the markers are per stick: no identity, no run (nothing to write or delete)
  SID=$(stickid)
  if [ -z "$SID" ]; then
    echo "FAIL  stick identity: could not read ro.product.model / ro.serialno off $S (the markers are per stick)"; DONE=1; echo "STICK_TEST_FAIL 1"; exit 1
  fi
fi
if [ $STAGING = 1 ] && clean_sha; then   # before any check: only a full pass re-creates it
  if [ $MODE = normal ]; then rm -f "$(mk staging)"; elif [ $MODE = recovery ]; then rm -f "$(mk recovery)"; fi
fi
# tested_apk: a check that the installed APK is the one ./build.sh made for this commit (its sha256,
# read off the stick, = dist/cleanframe-tv-<sha>-staging.apk.sha256); sets APK_SHA256 for the marker.
# Staging builds of a clean commit only (the ones that get a marker).
tested_apk() {
  local p h want f="dist/cleanframe-tv-$SHA-staging.apk.sha256"
  p=$(a shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | first || true)
  h=; [ -z "$p" ] || h=$(a shell sha256sum "$p" | tr -d '\r' | sed -n 's/^\([0-9a-f]\{64\}\)[[:space:]].*/\1/p' | first || true)
  want=$(sed -n 's/^\([0-9a-f]\{64\}\)[[:space:]].*/\1/p' "$f" 2>/dev/null | first || true)
  if [ -n "$h" ] && [ "$h" = "$want" ]; then APK_SHA256=$h; pass "installed APK is the one build.sh recorded" "sha256 $h"
  else fail "installed APK is the one build.sh recorded" "${h:-no sha256 for ${p:-<pm path empty>}}" "'${want:-<no $f>}'"; fi
}
# markers_after_run: after the totals. A pass of a clean staging commit writes its mode's marker
# (normal: staging-ok, recovery: recovery-ok); a recovery FAIL deletes both.
markers_after_run() {
  local kind label
  [ $STAGING = 1 ] || return 0
  case $MODE in normal) kind=staging; label=promotion ;; recovery) kind=recovery; label=recovery ;; *) return 0 ;; esac
  if [ "$FAILS" -ne 0 ]; then
    if [ $MODE = recovery ] && clean_sha; then rm -f "$(mk recovery)" "$(mk staging)"; echo "recovery FAIL: deleted $(mk staging) and $(mk recovery)"; fi
    return 0
  fi
  if clean_sha && [[ $APK_SHA256 =~ ^[0-9a-f]{64}$ ]]; then
    mkdir -p dist
    printf 'sha=%s\napk_sha256=%s\npackage=%s\nversionName=%s\nstick_id=%s\nstick=%s\nmode=%s\npassed=%s\nat=%s\n' \
      "$SHA" "$APK_SHA256" "$PKG" "$VN" "$SID" "$S" "$MODE" "$PASSES" "$(date -Is)" > "$(mk $kind)"
    echo "$label marker: $(mk $kind)"
  else
    echo "no $label marker: staging versionName '$VN' is not a clean commit"
  fi
}
echo "testing $PKG $VN on $S${SID:+ ($SID)}"
if [ $MODE != normal ]; then
  # shellcheck source=tools/stick-lib.sh
  . tools/stick-lib.sh
  if [ $MODE = recovery ]; then recovery_test; ! clean_sha || tested_apk; else reboot_test; fi
  finish   # tools/stick-lib.sh: totals, then markers_after_run
fi
[ $STAGING = 1 ] && echo "staging build: Home-key checks are skipped (it is not Home); Home is simulated by intent"
RE=${PKG//./\\.}
PIDS=" $(a shell pidof "$PKG" | tr -d '\r' || true) "   # our app's PIDs; more come from am_proc_start
LOG=; LOGERR=
snap() {   # append the buffers we read; a failed or empty read is recorded, never taken as "no crashes"
  local out
  if out=$(a logcat -d -v threadtime -b main,system,crash,events 2>&1) && [ -n "$out" ]; then LOG+=$out$'\n'
  else LOGERR+="${out:0:80} "; fi
  PIDS+=" $(a shell pidof "$PKG" | tr -d '\r' || true) "
}
a logcat -b main,system,crash,events -c   # only what snap() reads; kernel/radio stay for other diagnosis

a shell am start -n "$ACT" >/dev/null
wait_ready   # a cold start (e.g. just after install.sh) can take well over a fixed 4 s
# Open each first (through the page's QA hook, so a D-pad slip cannot make it vacuous), then Home.
expect "All apps open for Home"  "$(dt 'ShoreQA.scene("apps").view')" '"apps"'
expect "sheet shown"             "$(dt "$SHEET")" true
home
expect "Home closes sheet"       "$(dt "$SHEET")" false
expect "Home back at dock"       "$(dt "$VIEW")" '"home"'
expect "panel open for Home"     "$(dt 'ShoreQA.scene("appearance").view')" '"appearance"'
expect "panel shown"             "$(dt "$PANEL")" true
home
expect "Home closes panel"       "$(dt "$PANEL")" false
expect "Home closes panel sheet" "$(dt "$SHEET")" false
expect "Home back at dock again" "$(dt "$VIEW")" '"home"'
# The stage is 1920x1080 scaled to the viewport (main.js fit()): 0.5 on the Xiaomi (960x540 CSS),
# ~0.75 on the Hugsun (density 213, ~1440x810). The style string is rounded (scale(0.751563) for
# 0.7515625), so the numbers are compared.
expect "stage scale fits the viewport" "$(dt '(function(){var d=document.documentElement,w=d.clientWidth||innerWidth,h=d.clientHeight||innerHeight,t=document.getElementById("stage").style.transform,want=Math.min(w/1920,h/1080),m=/^scale\(([0-9.]+)\)$/.exec(t);return m&&Math.abs(+m[1]-want)<1e-4?"fit":t+" vs scale("+want+")"})()')" '"fit"'
F0=$(dt "$FOC");          expect_set "initial focus" "$F0"
k DPAD_LEFT;              F1=$(dt "$FOC")   # LEFT, or RIGHT if focus was already leftmost
[ "$F1" = "$F0" ] && { k DPAD_RIGHT; F1=$(dt "$FOC"); }
                          expect_not "D-pad moves focus" "$F1" "$F0"
# The package OK should open: the focused dock app's appId (saved record, else the page's own
# PRIMARY_APPS, else a catalog id, which is the package itself). Anything else, FallbackHome or
# the chooser included, fails.
WANT_APP=$(dt '(async function(){var e=document.querySelector(".is-focus[data-action]"),a=e&&e.getAttribute("data-action");
  if(!a||a.indexOf("home.app.")!==0)return null;var id=a.slice(9),r=(ShoreQA.state().appRecords||[]).filter(function(x){return x.id===id})[0];
  if(r)return r.appId;var l=await new Promise(function(ok){FrameDevice.apps(function(e,v){ok(e?[]:v||[])})}),b=l.filter(function(x){return x.brand===id})[0];
  if(b)return b.appId;var s=document.querySelector("script[src*=\"main.js\"]"),t=s?await (await fetch(s.src)).text():"",
  m=t.match(new RegExp("\\{ id: \x27"+id+"\x27, name: \x27[^\x27]*\x27, mark: (?:true|false), appId: \x27([A-Za-z0-9._]+)\x27"));
  return m?m[1]:id.indexOf(".")>0?id:null})()')
[[ $WANT_APP =~ ^\"[A-Za-z0-9_.]+\"$ ]] && WANT_APP=${WANT_APP//\"/} || WANT_APP=
k DPAD_CENTER 6;          T=$(top)
if [ -n "$WANT_APP" ] && [ "${T%%/*}" = "$WANT_APP" ]; then pass "OK launches the focused app" "$T"
else fail "OK launches the focused app" "$T" "'${WANT_APP:-<focused home.app.* appId unreadable>}/...'"; fi
# An app's splash can open its next screen seconds later, over Home if Home was pressed meanwhile
# (Skyglass: SplashActivity, then NewDashboardActivity; "BACK stays" FAILed 2 release runs in 3,
# 2026-11-01). Wait until the front activity is unchanged for 3 looks 2 s apart (at most 15 looks).
P=$T; N=0
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  sleep 2; C=$(top)
  if [ "$C" = "$P" ]; then N=$((N + 1)); [ $N -lt 3 ] || break; else N=0; P=$C; fi
done
if [ $STAGING = 0 ]; then k HOME 3; expect "Home returns" "$(top)" "$ACT"
else home; skip "Home key returns" "staging is not Home"; expect "back to staging" "$(top)" "$ACT"; fi
snap
k BACK 2;                 expect "BACK stays" "$(top)" "$ACT"
                          expect "BACK leaves sheet shut" "$(dt "$SHEET")" false
k DPAD_UP;                expect_set "UP focus" "$(dt "$FOC")"
home; k DPAD_UP; k DPAD_RIGHT
k DPAD_CENTER 3;          expect "All apps opens" "$(dt "$SHEET")" true
                          expect_ge "All apps native icons" "$(dt 'document.querySelectorAll("img[data-native-icon]").length')" 1
k BACK 2;                 expect "BACK closes All apps" "$(dt "$SHEET")" false
a shell input keyevent --longpress KEYCODE_BACK >/dev/null; sleep 2
UI=$(ui)   # tools/adb-lib.sh: retries an empty dump (UI_TRIES)
if [[ $UI == *"Back to CleanFrame"* ]]; then pass "Back-hold shows native list" "Back to CleanFrame"
else fail "Back-hold shows native list" "${UI:0:60}" "'Back to CleanFrame' in uiautomator dump"; fi
k BACK 3;                 expect "Back returns to page" "$(dt "$PAGE")" true
                          expect "still in front" "$(top)" "$ACT"

# Home additions (web/home-extras.js, 2026-11-01). Every expression starts with /*hx:<step>*/
# (tests/tools.test.sh's fake page answers by that).
#   marked   Up to the top-right controls: focus is on them and no dock app or list row keeps a
#            marker (is-sel, the plate's on-sel); Down returns focus to the dock
#   rows     every Settings row fits above the hint
#   hold     see "Long press" below
#   clock    the remote opens Settings > Clock (focus on the applied format), picks 24-hour, which is
#            saved and drawn on Home; seconds tick; Hide empties the clock. The clock options the run
#            found are put back at the end (also from the EXIT trap).
hx() { local v; v=$(dt "/*hx:$1*/$2"); v=${v#\"}; echo "${v%\"}"; }
hx_restore() { hx restore "(function(){var X=CleanFrameExtras,o=\"$HX_ORIG\".split(\"|\");return X.set(\"clockFormat\",o[0])&&X.set(\"clockSeconds\",o[1])&&X.set(\"clockShow\",o[2])?\"restored\":\"failed\"})()"; }
# hx_focus <step> <key> <action>: press <key> until <action> has focus (at most 9 presses); prints it.
hx_focus() { local i f; for i in 1 2 3 4 5 6 7 8 9; do f=$(hx "$1" "ShoreQA.where().focusAction"); [ "$f" = "$3" ] && break; k "$2"; done; echo "$f"; }
CLOCK_TEXT='document.querySelector("#caption .clock").textContent'
if feature extras "Home additions" home-extras.js CleanFrameExtras; then
home
k DPAD_UP;                expect "UP: no dock app stays marked" "$(hx marked "(function(){var n=document.querySelectorAll(\"#track .tile.is-sel,#phRows .is-sel,.plate.on-sel,.ph-plate.on-sel\").length;return n+\"|\"+ShoreQA.where().zone})()")" "0|util"
k DPAD_DOWN;              expect "DOWN: back on the dock" "$(hx zone 'ShoreQA.where().zone')" apps
# Nine rows since Settings > Bluetooth (web/bluetooth.js, 2026-11-03), eight on a build without it.
HX_ROWS=8; [ "$(hx rowswant 'typeof window.CleanFrameBluetooth')" != object ] || HX_ROWS=9
expect "Settings rows fit above the hint" "$(hx rows '(function(){ShoreQA.scene("settings");var r=document.querySelectorAll(".settings-group"),l=r[r.length-1].getBoundingClientRect(),h=document.querySelector(".settings-overview-hint").getBoundingClientRect(),n=r.length;ShoreQA.scene("home");return n+"|"+(l.bottom<=h.top?"fits":"overlaps")})()')" "$HX_ROWS|fits"
HX_ORIG=$(hx orig '(function(){var p=CleanFrameExtras.all();return p.clockFormat+"|"+p.clockSeconds+"|"+p.clockShow})()')
if [[ $HX_ORIG =~ ^(12|12a|24)\|(on|off)\|(on|off)$ ]]; then pass "clock options read" "$HX_ORIG"
else fail "clock options read" "$HX_ORIG" "'<format>|<seconds>|<show>'"; HX_ORIG=; fi
expect "clock options reset" "$(hx reset '(function(){var X=CleanFrameExtras;return X.set("clockFormat","12")&&X.set("clockSeconds","off")&&X.set("clockShow","on")?"ok":"failed"})()')" ok
home
k DPAD_UP;                expect "focus on Settings" "$(hx_focus focus:util.settings DPAD_RIGHT util.settings)" util.settings
k DPAD_CENTER 2;          expect "Settings opens" "$(hx view 'ShoreQA.state().view')" settings
                          expect "Clock row" "$(hx_focus focus:settings.group.clock DPAD_DOWN settings.group.clock)" settings.group.clock
k DPAD_CENTER 2;          expect "Clock page, focus on the applied format" "$(hx page 'ShoreQA.state().settingsGroup+"|"+ShoreQA.where().focusAction')" "clock|pref.clockFormat.12"
                          expect "24-hour focused" "$(hx_focus focus:pref.clockFormat.24 DPAD_DOWN pref.clockFormat.24)" pref.clockFormat.24
k DPAD_CENTER;            expect "24-hour saved" "$(hx saved 'CleanFrameExtras.get("clockFormat")+"|"+(document.querySelector("#appearance .is-focus[data-action] .setting-tick .ico")!==null)')" "24|true"
k BACK; k BACK;           expect "back on Home" "$(hx home 'ShoreQA.state().view')" home
expect "Home clock is 24-hour" "$(hx clock24 "/^[0-2][0-9]:[0-5][0-9]\$/.test($CLOCK_TEXT)?\"24h\":$CLOCK_TEXT")" 24h
# (the clock is re-read each time: every redraw replaces its element)
expect "seconds tick" "$(hx seconds "(async function(){function c(){return $CLOCK_TEXT}CleanFrameExtras.set(\"clockSeconds\",\"on\");var a=c();await new Promise(function(r){setTimeout(r,2200)});var b=c();return /^[0-2][0-9]:[0-5][0-9]:[0-5][0-9]\$/.test(a)&&/^[0-2][0-9]:[0-5][0-9]:[0-5][0-9]\$/.test(b)&&a!==b?\"ticking\":a+\">\"+b})()")" ticking
expect "Hide empties the clock" "$(hx hidden "(function(){CleanFrameExtras.set(\"clockShow\",\"off\");return $CLOCK_TEXT===\"\"?\"hidden\":$CLOCK_TEXT})()")" hidden
if [ -n "$HX_ORIG" ]; then expect "clock options put back" "$(hx_restore)" restored; HX_ORIG=; fi
fi   # feature extras
# Screen and Sleep (web/home-extras.js; SleepControl on the Android side). The stick must never sleep
# during a test: asleep it drops off Wi-Fi and only the remote wakes it, so
# nothing here waits for a timeout, Sleep now is cancelled by a key within seconds (Android's short
# timeout is 15 s; the EXIT trap cancels it too), and Sleep after is changed once and put back.
#   screen*  Settings > Screen by remote: opens on Dim after; OK opens its list on the applied value,
#            whose Back row says Screen; Back returns to Screen
#   policy   the stick's screen_off_timeout is Dim after + Sleep after (the app holds WRITE_SETTINGS)
#   dim*     CleanFrameExtras.dimNow() dims Home; the next key (Right) only brightens it: focus unchanged
#   sleep*   Sleep (util.art) opens its menu on the applied Sleep after (2026-11-01: the Sleep
#            button and Settings > Screen > Sleep after are one setting); the next choice right is saved
#            as Sleep after and written to the stick, then put back; nothing shows under the clock
#   nap*     Sleep now (the menu's first button, by remote): Home goes black and the stick's timeout is
#            15 s while Sleep after stays saved as it was; the next key (Right) only wakes Home and the
#            timeout is Dim after + Sleep after again ("keeps the original setting after")
DIM_STATE='(function(){var e=document.querySelector(".cf-dim");return (e&&/(^| )is-on( |$)/.test(e.className)?"dim":"bright")+"|"+ShoreQA.where().focusAction})()'
NAP_STATE='(function(){var e=document.querySelector(".cf-dim");return (e&&/ is-asleep/.test(e.className)?"black":e&&/ is-on/.test(e.className)?"dim":"bright")+"|"+CleanFrameExtras.get("screenSleep")})()'
if feature sleep "Screen and Sleep" home-extras.js CleanFrameExtras dimNow sleepMenu napping; then
home
k DPAD_UP;                expect "focus on Settings" "$(hx_focus focus:util.settings DPAD_RIGHT util.settings)" util.settings
k DPAD_CENTER 2;          expect "Screen row" "$(hx_focus focus:settings.group.screen DPAD_DOWN settings.group.screen)" settings.group.screen
k DPAD_CENTER 2;          expect "Screen page opens on Dim after" "$(hx screenpage 'ShoreQA.state().settingsGroup+"|"+ShoreQA.where().focusAction')" "screen|screen.open.screenDim"
k DPAD_CENTER 2;          expect "Dim after list: applied value, Back says Screen" "$(hx dimpage '(function(){var b=document.querySelector("#appearance .setting-back");return ShoreQA.state().settingsGroup+"|"+(ShoreQA.where().focusAction==="pref.screenDim."+CleanFrameExtras.get("screenDim"))+"|"+(b?b.textContent.trim():"")})()')" "screenDim|true|Screen"
k BACK;                   expect "Back returns to Screen" "$(hx screenback 'ShoreQA.state().settingsGroup')" screen
k BACK; k BACK;           expect "back on Home" "$(hx screenhome 'ShoreQA.state().view')" home
WANT_JS='(function(){var p=CleanFrameExtras.all(),d=p.screenDim==="off"?0:+p.screenDim,s=p.screenSleep==="off"?0:+p.screenSleep;return s?String((d+s)*60000):"2147483647"})()'
screen_to() { a shell settings get system screen_off_timeout | tr -d '\r'; }
WANT_TO=$(hx policy "$WANT_JS")
expect "screen-off timeout is Dim after + Sleep after" "$(screen_to)" "$WANT_TO"
home
F_DIM=$(hx dimfocus 'ShoreQA.where().focusAction')
expect "Home dims" "$(hx dim "(function(){CleanFrameExtras.dimNow();return $DIM_STATE})()")" "dim|$F_DIM"
k DPAD_RIGHT;             expect "the first key only brightens" "$(hx undim "$DIM_STATE")" "bright|$F_DIM"
home
S0=$(hx sleepnow 'CleanFrameExtras.get("screenSleep")')
S1=$(hx sleepnext '(function(){var a=CleanFrameExtras.sleepMenu().actions,i=CleanFrameExtras.sleepIndex();return a[i+1<a.length?i+1:i-1][0]})()')
k DPAD_UP;                expect "focus on Sleep" "$(hx_focus focus:util.art DPAD_LEFT util.art)" util.art
k DPAD_CENTER 2;          expect "Sleep opens on the applied Sleep after" "$(hx sleepmenu '(function(){var t=document.querySelector(".msg-title");return ShoreQA.state().view+"|"+(t?t.textContent:"")+"|"+ShoreQA.where().focusAction})()')" "message|Sleep|message.$S0"
                          expect "Sleep now is the first button" "$(hx sleepfirst 'document.querySelector("[data-action^=\"message.\"]").getAttribute("data-action")')" message.now
HX_SLEEP=$S0
                          expect "the next Sleep after focused" "$(hx_focus focus:message.$S1 DPAD_RIGHT message.$S1)" "message.$S1"
k DPAD_CENTER 2;          expect "the Sleep button saves Sleep after" "$(hx sleepsaved 'CleanFrameExtras.get("screenSleep")+"|"+(document.querySelector("#caption .sleep-line")?"shown":"none")')" "$S1|none"
expect "and the stick's timeout follows" "$(screen_to)" "$(hx policy1 "$WANT_JS")"
expect "Sleep after put back" "$(hx sleepback "(function(){return CleanFrameExtras.set(\"screenSleep\",\"$S0\")?CleanFrameExtras.get(\"screenSleep\"):\"failed\"})()")" "$S0"
HX_SLEEP=
sleep 1;                  expect "the stick's timeout is back" "$(screen_to)" "$WANT_TO"
home
k DPAD_UP;                expect "focus on Sleep for Sleep now" "$(hx_focus focus:util.art DPAD_LEFT util.art)" util.art
k DPAD_CENTER 2;          expect "Sleep now focused" "$(hx_focus focus:message.now DPAD_LEFT message.now)" message.now
HX_NAP=1
k DPAD_CENTER 1;          expect "Sleep now: Home black, Sleep after kept" "$(hx nap "$NAP_STATE")" "black|$S0"
                          expect "Sleep now: the stick's timeout is 15 s" "$(screen_to)" 15000
k DPAD_RIGHT;             expect "the next key only wakes Home" "$(hx napwake "$NAP_STATE")" "bright|$S0"
HX_NAP=0
                          expect "Sleep now: the timeout is Sleep after's again" "$(screen_to)" "$WANT_TO"
fi   # feature sleep
# Long press (web/home-extras.js X.Hold): OK held on a dock app opens its menu on Move. The hold is
# sent as DevTools key events 800 ms apart: `input keyevent --longpress` releases after the system
# long-press timeout, which is shorter than the page's 600 ms. A short real OK still launches (the
# "OK launches the focused app" check above). Back closes the menu and the dock is unchanged; the
# remote's OK on Move opens Apps on Home with that app picked up, and Back twice leaves the order as
# it was; App info (four Rights past Rename, Change icon and Remove, OK) opens Android's page for that
# app, named on it: by the menu's title, or by its package (Android shows it on the page), since a name
# given in Rename is CleanFrame's own and Android's page keeps the app's (release on the Xiaomi,
# 2026-11-03: Sky Glass+ renamed IPTV).
HOLD_JS='(async function(){function k(t){var e=document.createEvent("Event");e.initEvent(t,true,true);e.keyCode=e.which=13;document.dispatchEvent(e)}k("keydown");await new Promise(function(r){setTimeout(r,800)});k("keyup");return ShoreQA.state().view+"|"+ShoreQA.where().focusAction})()'
VIEW_DOCK='ShoreQA.state().view+"|"+ShoreQA.state().dock.join(",")'
if feature hold "Long press" home-extras.js CleanFrameExtras Hold appMenu; then
home
HX_ORDER=$(hx order 'ShoreQA.state().dock.join(",")'); HX_SEL=$(hx sel 'ShoreQA.state().selected')
expect "OK held opens the app menu" "$(hx hold "$HOLD_JS")" "message|message.move"
k BACK;                   expect "Back closes the menu" "$(hx menuback "$VIEW_DOCK")" "home|$HX_ORDER"
expect "held again" "$(hx hold2 "$HOLD_JS")" "message|message.move"
k DPAD_CENTER 2;          expect "Move picks the app up" "$(hx move '(function(){var s=ShoreQA.state();return s.view+"|"+(s.draft?s.draft.id:"none")})()')" "editor|$HX_SEL"
k BACK; k BACK;           expect "Back twice: dock unchanged" "$(hx moveback "$VIEW_DOCK")" "home|$HX_ORDER"
expect "held a third time" "$(hx hold3 "$HOLD_JS")" "message|message.move"
HX_TITLE=$(hx title 'document.querySelector(".msg-title").textContent')
HX_PKG=$(hx pkg '(function(){var a=ShoreQA.state().selectedApp;return a&&a.appId||""})()')
k DPAD_RIGHT; k DPAD_RIGHT; k DPAD_RIGHT; k DPAD_RIGHT
                          expect "App info focused" "$(hx infofocus 'ShoreQA.where().focusAction')" message.info
k DPAD_CENTER 4;          T=$(top)
if [ "${T%%/*}" = com.android.tv.settings ]; then pass "App info opens Android's page" "$T"
else fail "App info opens Android's page" "$T" "'com.android.tv.settings/...'"; fi
# Android draws the page after it resumes: an early dump has only empty text= nodes (seen 2 runs in 3,
# 2026-11-01), so look again for up to ~10 s.
named() { [ -n "$HX_TITLE" ] && [[ $UI == *"text=\"$HX_TITLE\""* ]] || { [ -n "$HX_PKG" ] && [[ $UI == *"text=\"$HX_PKG\""* ]]; }; }
for _ in 1 2 3 4 5; do UI=$(ui); { [ -z "$HX_TITLE$HX_PKG" ] || named; } && break; sleep 2; done
if named; then pass "App info names the app" "$HX_TITLE ($HX_PKG)"
else fail "App info names the app" "$(grep -oE 'text="[^"]+"' <<<"$UI" | sed -n 1,8p | tr '\n' ' ')" "'text=\"${HX_TITLE:-<menu title unreadable>}\"' or 'text=\"${HX_PKG:-<package unreadable>}\"' in uiautomator dump"; fi
home;                     expect "back to Home after App info" "$(hx infoback 'ShoreQA.state().view')" home
fi   # feature hold

# Rename and Change icon (web/app-edit.js; 2026-11-01). The selected dock app is renamed and given
# the IPTV mark through its long-press menu by remote, and both are put back by remote (Original name,
# App's own icon). Any override the app had is cleared through DevTools first and put back at the end
# (also from the EXIT trap). With no choice, an app whose package a library mark lists shows that mark
# (web/app-marks.js packages; 2026-11-02); "App's own icon" is saved as an explicit choice (mark '')
# that keeps the app's own icon even then, so the run starts from that choice, set through DevTools.
# With a keyboard (an IME is set) the name is typed with `input text`, as Add
# group's is; without one that part is SKIPped and the name is put in the text box through DevTools.
# Save, Original name and every menu and picker choice are pressed by remote.
#   aeorig    the dock app's id, package and any override it had (name URI-encoded, mark id or @own)
#   aeauto    with no choice, the dock tile draws the mark its package has (SKIP when no mark lists it)
#   aeownset  App's own icon chosen for the run (DevTools): what the tile draws then is its own icon
#   aeun      can this app be uninstalled? (the Uninstall section tests the answer; here it only sets what the menu has)
#   aemenu    OK held: the menu is Move, Rename, Change icon, Remove from Home, App info, Uninstall when aeun says so, Cancel, and
#   aefit     all its buttons fit on the stage (the last one ends inside it)
#   aename    Rename (one Right, OK): the name screen, focus on the text box, prefilled with the name
#   aesaved   Down, OK (Save): saved for that package; the dock tile's name is the new one; Home order unchanged
#   aeorigname  Rename again, then Down, Right, Right, OK (Original name): no override, the own name again
#   aeicon    Change icon (two Rights, OK): the picker opens on App's own icon
#   aeiptv    Right (IPTV is the first mark), OK: saved; the dock tile draws the iptv mark; order unchanged
#   aeicon2   Change icon again opens on IPTV; Left, OK (App's own icon): saved as App's own icon, the tile
#   aeown     draws its own icon again, as at aeownset (Apps set to Text draws no marks: then only the
#             override is checked)
AE_HOLD='(async function(){function k(t){var e=document.createEvent("Event");e.initEvent(t,true,true);e.keyCode=e.which=13;document.dispatchEvent(e)}k("keydown");await new Promise(function(r){setTimeout(r,800)});k("keyup");return ShoreQA.state().view+"|"+ShoreQA.where().focusAction})()'
# view|shown name|the dock tile's aria-label|the name override|Home order
AE_NAMES='(function(){var s=ShoreQA.state(),a=s.selectedApp,t=a&&document.querySelector("#home [data-action=\"home.app."+a.id+"\"]"),e=a?CleanFrameAppEdit.get(a.appId):{};return s.view+"|"+(a?a.name:"none")+"|"+(t?t.getAttribute("aria-label"):"no tile")+"|"+(e.name||"none")+"|"+s.dock.join(",")})()'
# view|the mark override ("own" = App's own icon chosen, "none" = no choice)|what the dock tile draws (its
# data-mark, "monogram", or "text" when Apps is Text)|Home order
AE_MARKS='(function(){var s=ShoreQA.state(),a=s.selectedApp,t=a&&document.querySelector("#home [data-action=\"home.app."+a.id+"\"]"),m=t&&t.querySelector(".mark"),e=a?CleanFrameAppEdit.get(a.appId):{};return s.view+"|"+(e.mark===""?"own":e.mark||"none")+"|"+(s.look.apps==="text"?"text":m?m.getAttribute("data-mark")||"monogram":"no mark")+"|"+s.dock.join(",")})()'
ae_restore() { hx aerestore "(function(){var A=CleanFrameAppEdit,n=decodeURIComponent(\"$AE_N0\"),m=\"$AE_M0\";return A.setName(\"$AE_PKG\",n||null).ok&&A.setMark(\"$AE_PKG\",m===\"@own\"?\"\":m||null).ok?\"restored\":\"failed\"})()"; }
AE_NAME='CF Test TV'
if feature appedit "Rename and Change icon" app-edit.js CleanFrameAppEdit; then
home
AE0=$(hx aeorig '(function(){var a=ShoreQA.state().selectedApp,e=a?CleanFrameAppEdit.get(a.appId):{};return a?a.id+"|"+a.appId+"|"+encodeURIComponent(e.name||"").replace(/[!'"'"'()*~]/g,function(c){return "%"+c.charCodeAt(0).toString(16)})+"|"+(e.mark===""?"@own":e.mark||""):"none"})()')
if [[ $AE0 =~ ^([A-Za-z0-9._-]+)\|([A-Za-z0-9._-]+)\|([A-Za-z0-9%._-]*)\|(@own|[a-z0-9]*)$ ]]; then
  AE_PKG=${BASH_REMATCH[2]}; AE_N0=${BASH_REMATCH[3]}; AE_M0=${BASH_REMATCH[4]}
  pass "app overrides read" "$AE0"
  AE_STARTED=1
  expect "app overrides cleared for the run" "$(hx aereset "(function(){var A=CleanFrameAppEdit;return A.setName(\"$AE_PKG\",null).ok&&A.setMark(\"$AE_PKG\",null).ok?\"ok\":\"failed\"})()")" ok
  AE_OWN=$(hx aeownname 'ShoreQA.state().selectedApp.name'); AE_ORDER=$(hx aeorder 'ShoreQA.state().dock.join(",")')
  AE_AUTO=$(hx aeautoid "CleanFrameAppEdit.autoMark(\"$AE_PKG\")")
  AE_TEXT=$(hx aetext 'ShoreQA.state().look.apps')
  if ! [[ $AE_AUTO =~ ^[a-z0-9]*$ ]]; then fail "the mark by package" "$AE_AUTO" "a mark id or nothing"
  elif [ -z "$AE_AUTO" ]; then skip "the dock tile draws its package's mark" "no library mark lists $AE_PKG"
  else
    if [ "$AE_TEXT" = text ]; then AE_AUTODRAWN=text; else AE_AUTODRAWN=$AE_AUTO; fi
    expect "with no choice the dock tile draws its package's mark" "$(hx aeauto "$AE_MARKS")" "home|none|$AE_AUTODRAWN|$AE_ORDER"
  fi
  expect "App's own icon chosen for the run" "$(hx aeownset "(function(){return CleanFrameAppEdit.setMark(\"$AE_PKG\",\"\").ok?\"ok\":\"failed\"})()")" ok
  AE_DRAWN=$(hx aedrawn "$AE_MARKS"); AE_DRAWN=${AE_DRAWN#*|*|}; AE_DRAWN=${AE_DRAWN%%|*}
  AE_UN=; AE_BTNS=6
  if [ "$(hx aeun '(function(){var a=ShoreQA.state().selectedApp,F=FrameDevice;return a&&F.canUninstall&&F.canUninstall(a.appId)?"can":"no"})()')" = can ]; then AE_UN=,message.uninstall; AE_BTNS=7; fi
  expect "OK held: the menu has Rename and Change icon" "$(hx aemenu "(async function(){var r=await $AE_HOLD;return r+\"|\"+ShoreQA.actions().join(\",\")})()")" \
    "message|message.move|message.move,message.rename,message.icon,message.remove,message.info$AE_UN,message.back"
  expect "the menu's buttons fit on the stage" "$(hx aefit '(function(){var b=document.querySelectorAll(".msg-acts .btn"),s=document.getElementById("stage").getBoundingClientRect(),r=b[b.length-1].getBoundingClientRect();return b.length+"|"+(r.right<=s.right&&r.left>=s.left?"fits":"overflows")})()')" "$AE_BTNS|fits"
  k DPAD_RIGHT;             expect "Rename focused" "$(hx aerenamefocus 'ShoreQA.where().focusAction')" message.rename
  k DPAD_CENTER 2;          expect "Rename opens on the text box with the name" "$(hx aename '(function(){var e=document.getElementById("appNameInput");return ShoreQA.state().view+"|"+ShoreQA.where().focusAction+"|"+(e?e.value:"")})()')" "appname|appname.field|$AE_OWN"
  # The literal null is "no keyboard" (SKIP); an empty answer is an adb read that failed (FAIL), never a skip.
  IME=$(a shell settings get secure default_input_method | tr -d '\r' || true)
  if [ -z "$IME" ] || [ "$IME" = null ]; then
    if [ "$IME" = null ]; then skip "Rename typing" "no keyboard (no default input method on $S)"
    else fail "Rename typing: default input method read" "" "an input method or null (adb settings get failed?)"; fi
    hx aesetname "(function(){var e=document.getElementById(\"appNameInput\");e.value=\"$AE_NAME\";e.dispatchEvent(new Event(\"input\"));return e.value})()" >/dev/null
  else
    k DPAD_CENTER 2
    expect "OK puts the name box in typing" "$(hx aetyping 'document.activeElement&&document.activeElement.id')" appNameInput
    hx aeclear '(function(){var e=document.getElementById("appNameInput");e.value="";e.dispatchEvent(new Event("input"));return 1})()' >/dev/null
    a shell input text "${AE_NAME// /%s}" >/dev/null || true; sleep 1
    k BACK 1   # hides the keyboard; the text box keeps the text
    expect "the keyboard returns the app name" "$(hx aetyped 'document.getElementById("appNameInput").value')" "$AE_NAME"
  fi
  k DPAD_DOWN; k DPAD_CENTER 2
  expect "Save renames the dock app, order unchanged" "$(hx aesaved "$AE_NAMES")" "home|$AE_NAME|$AE_NAME|$AE_NAME|$AE_ORDER"
  expect "held again: Rename" "$(hx aehold2 "$AE_HOLD")" "message|message.move"
  k DPAD_RIGHT; k DPAD_CENTER 2
  k DPAD_DOWN; k DPAD_RIGHT; k DPAD_RIGHT
                            expect "Original name focused" "$(hx aeorigfocus 'ShoreQA.where().focusAction')" appname.original
  k DPAD_CENTER 2;          expect "Original name restores the name" "$(hx aeorigname "$AE_NAMES")" "home|$AE_OWN|$AE_OWN|none|$AE_ORDER"
  expect "held again: Change icon" "$(hx aehold3 "$AE_HOLD")" "message|message.move"
  k DPAD_RIGHT; k DPAD_RIGHT
                            expect "Change icon focused" "$(hx aeiconfocus 'ShoreQA.where().focusAction')" message.icon
  k DPAD_CENTER 2;          expect "Change icon opens on App's own icon" "$(hx aeicon 'ShoreQA.state().view+"|"+ShoreQA.where().focusAction')" "appicon|appicon.own"
  k DPAD_RIGHT;             expect "IPTV focused" "$(hx aeiptvfocus 'ShoreQA.where().focusAction')" appicon.iptv
  if [ "$AE_DRAWN" = text ]; then AE_IPTV=text; else AE_IPTV=iptv; fi
  k DPAD_CENTER 2;          expect "the dock tile draws the IPTV mark" "$(hx aeiptv "$AE_MARKS")" "home|iptv|$AE_IPTV|$AE_ORDER"
  expect "held again: Change icon back" "$(hx aehold4 "$AE_HOLD")" "message|message.move"
  k DPAD_RIGHT; k DPAD_RIGHT; k DPAD_CENTER 2
                            expect "Change icon opens on IPTV, the current choice" "$(hx aeicon2 'ShoreQA.state().view+"|"+ShoreQA.where().focusAction')" "appicon|appicon.iptv"
  k DPAD_LEFT;              expect "App's own icon focused" "$(hx aeownfocus 'ShoreQA.where().focusAction')" appicon.own
  k DPAD_CENTER 2;          expect "App's own icon draws the app's own icon again" "$(hx aeown "$AE_MARKS")" "home|own|$AE_DRAWN|$AE_ORDER"
  expect "app overrides put back" "$(ae_restore)" restored
  AE_STARTED=0
else
  fail "app overrides read" "$AE0" "'<id>|<package>|<name>|<mark>'"
fi
fi   # feature appedit

# Uninstall on the long-press menu (web/device.js; 2026-11-04). NOTHING IS EVER UNINSTALLED HERE:
# they are the stick's real apps. Android's dialog is opened on the selected dock app and left with BACK and
# nothing else: OK is sent once, only when the page says Uninstall has the focus, and there is no OK of any
# kind from the un-dialog step to the end of the section (tests/tools.test.sh fails on one, and on any
# uninstall call in this file). An app the TV will not let go SKIPs the dialog, with the reason.
#   self, system  canUninstall is false for CleanFrame and for a system package
#   app       the selected dock app's id and package, and what canUninstall says about it
#   menu      OK held: the menu has Uninstall exactly when canUninstall said so
#   focus     Rights until Uninstall has the focus (the page's own answer)
#   kept      after BACK: Home, the dock unchanged, the app still listed; and pm still has the package
un() { local v; v=$(dt "/*un:$1*/$2"); v=${v#\"}; echo "${v%\"}"; }
if feature uninstall "Uninstall" device.js FrameDevice canUninstall uninstall; then
home
expect "CleanFrame cannot be uninstalled from its menu" "$(un self 'String(FrameDevice.canUninstall("home.cleanframe.tv"))')" false
expect "a system package cannot be uninstalled from the menu" "$(un system 'String(FrameDevice.canUninstall("com.android.tv.settings"))')" false
UN_APP=$(un app '(function(){var a=ShoreQA.state().selectedApp;return a?a.id+"|"+a.appId+"|"+FrameDevice.canUninstall(a.appId):""})()')
if ! [[ $UN_APP =~ ^([A-Za-z0-9._-]+)\|([A-Za-z0-9._-]+)\|(true|false)$ ]]; then fail "Uninstall: the selected app" "$UN_APP" "'<id>|<package>|true or false'"
else
  UN_ID=${BASH_REMATCH[1]}; UN_PKG=${BASH_REMATCH[2]}; UN_CAN=${BASH_REMATCH[3]}
  UN_ORDER=$(un order 'ShoreQA.state().dock.join(",")')
  UN_ITEM=; [ "$UN_CAN" = false ] || UN_ITEM=,message.uninstall
  UN_MENU=$(un menu "(async function(){var r=await $HOLD_JS;return r+\"|\"+ShoreQA.actions().join(\",\")})()")
  expect "the menu has Uninstall only if the TV says so ($UN_PKG: $UN_CAN)" "$UN_MENU" "message|message.move|message.move,message.rename,message.icon,message.remove,message.info$UN_ITEM,message.back"
  if [ "$UN_CAN" = false ]; then
    skip "Uninstall opens Android's dialog" "the TV will not let $UN_PKG go (the selected dock app is a system or core app)"
    k BACK 1
  elif [ "$UN_MENU" != "message|message.move|message.move,message.rename,message.icon,message.remove,message.info,message.uninstall,message.back" ]; then
    k BACK 1   # the failure above; nothing is pressed on a menu that is not the one expected
  else
    UN_F=; for _ in 1 2 3 4 5 6; do UN_F=$(un focus 'ShoreQA.where().focusAction'); [ "$UN_F" = message.uninstall ] && break; k DPAD_RIGHT; done
    if [ "$UN_F" != message.uninstall ]; then fail "Uninstall focused" "$UN_F" "message.uninstall"; k BACK 1
    else
      pass "Uninstall focused" "$UN_F"
      k DPAD_CENTER 4
      # un-dialog: from here to the end of the section, BACK is the only key.
      T=$(top)
      if [[ $T == *UninstallerActivity* ]]; then pass "Uninstall opens Android's dialog" "$T"
      else fail "Uninstall opens Android's dialog" "$T" "'.../...UninstallerActivity'"; fi
      k BACK 3
      for _ in 1 2; do [ "$(top)" = "$ACT" ] && break; k BACK 3; done
      expect "Back leaves Android's dialog" "$(top)" "$ACT"
      expect "Back changes nothing" "$(un kept "(function(){var s=ShoreQA.state();return s.view+\"|\"+s.dock.join(\",\")+\"|\"+(s.catalog.ids.indexOf(\"$UN_ID\")>=0?\"listed\":\"gone\")})()")" "home|$UN_ORDER|listed"
      UN_PM=$(a shell pm path "$UN_PKG" | tr -d '\r' || true)
      if [[ $UN_PM == package:* ]]; then pass "the app is still installed" "$UN_PKG"
      else fail "the app is still installed" "$UN_PM" "'package:...' for $UN_PKG"; fi
    fi
  fi
fi
home
fi   # feature uninstall

# Photo groups (Settings > Background > Groups; docs/PHOTO-GROUPS.md, web/photo-groups.js).
# First run on the stick 2026-10-31 (DEVICE-CHECK 3). In order:
#   groups   the Groups rows start Scenery, Space and the tick is on the applied wallpaper's group;
#            the remote opens Space's page and applies a Space photo, which survives a reload
#   add      + Add group opens the name screen prefilled "Group N"; with a keyboard (an IME is
#            set) OK on the field must bring it up and `input text` types the name (DEVICE-CHECK 2),
#            else that part is SKIPped and the name is set through DevTools; Down, OK creates the
#            group "CF stick test" and opens its page on Add photo
#   upload   OK opens Add photo: a drawn QR and a 6-digit code. Nothing reads or writes R over adb:
#            on this Android 11 user build adb shell is refused /sdcard/Android/data/<pkg>/ (DEVICE-CHECK
#            1), so the checks go through the app's own server with curl, as a phone would, and the
#            page's user/ route (DevTools). Straight to the stick (contract 4 v2, DEVICE-CHECK 10): the
#            QR's URL is the stick's own http://<ip>:8097/u/<token>, its code page answers and the code
#            on screen redirects to the QR's page; this machine sends a small JPEG with a planted GPS tag with
#            curl, the stick prepares it, the page draws its materials and it appears in the group
#            within UPLOAD_WAIT s ("1 photo added"), all seven files served, its meta.json read, and no
#            planted string in wall.jpg, thumb.jpg or the server's thumbnail; a second, plain upload
#            stays for "remove"; the first is deleted through the same API and leaves the group. Back
#            closes Add photo and the port then refuses connections (neither the token nor the
#            pairing code is ever printed)
#   remove   Remove photos, OK on the photo asks Yes/No on No; OK (No) keeps the photo
#   delete   Delete group, Yes: back on Background, the group gone from the page and the stick
#   restore  the wallpaper the run started with is applied again (and so its group), then the
#            Background type if the run switched it; a test group left by a failed step is deleted
#            through FrameDevice. The EXIT trap does the same when a step aborts mid-section.
# Background not Photo (Groups are disabled then): the run switches it to Photo with ShoreQA.look and
# checks the switch took; if it did not, or the type cannot be read, the section FAILs (never a skip).
# Every expression starts with /*pg:<step>*/ (tests/tools.test.sh's fake page answers by that;
# focus:<action> names the focus it waits for).
pg() { local v; v=$(dt "/*pg:$1*/$2"); v=${v#\"}; echo "${v%\"}"; }
# curlk <url> [--data-cfg <data>] [curl options...]: curl with the URL (and form data) in a config read
# from stdin, never in argv: the upload token is in the URL and the pairing code in the data, and any
# user on this machine can read a process's arguments (ps). The answer goes to stdout as with curl.
curlk() {
  local cfg="url = \"$1\""; shift
  if [ "${1:-}" = --data-cfg ]; then cfg+=$'\n'"data = \"$2\""; shift 2; fi
  curl "$@" --config - <<<"$cfg"
}
PG_NAME="CF stick test"; PG_GID=; PG_BYNAME=0; PG_PICK=; KEEPPID=
# The photo id in an upload's answer ({"ok":true,"photo":{"id":...}}), or nothing.
pid_of() { python3 -I -c 'import json, sys
try: print(json.loads(sys.stdin.read())["photo"]["id"])
except Exception: print("")' <<<"$1"; }
# JS: each of a photo's seven files through the page's user/ route: "<name>:<status>:<jpeg|x>" each.
user_files_js() { echo "(async function(){var b=\"https://cleanframe.local/home/user/$1/$2/\",f=[\"wall\",\"thumb\",\"panel\",\"field-blur\",\"field-clear\",\"field-frost\",\"study-soft\"],o=[];
  for(var i=0;i<f.length;i++){var r=await fetch(b+f[i]+\".jpg\",{cache:\"no-store\"}),a=new Uint8Array(await r.arrayBuffer());o.push(f[i]+\":\"+r.status+\":\"+(a[0]===255&&a[1]===216?\"jpeg\":\"x\"))}return o.join(\" \")})()"; }
# JS (Crop and Adjust, 2026-11-02): "1920x1080 match" when the photo's wall.jpg is its listed crop of its
# src.jpg, drawn here with the page's own crop arithmetic (CleanFramePhotoCrop.place), to within a mean
# difference of 8/255 per channel (JPEG q87 and two resamplers); else what differs.
crop_js() { echo "(async function(){var C=window.CleanFramePhotoCrop,b=\"https://cleanframe.local/home/user/$1/$2/\";
  async function im(u){var r=await fetch(u,{cache:\"no-store\"});if(r.status!==200)throw new Error(u.split(\"/\").pop()+\" \"+r.status);return await createImageBitmap(await r.blob())}
  try{var s=await im(b+\"src.jpg\"),w=await im(b+\"wall.jpg\"),l=FrameDevice.libraryNow(),p=l&&l.photos.filter(function(p){return p.id===\"$2\"})[0];
  if(!p||!p.crop)return \"no crop listed\";if(w.width!==1920||w.height!==1080)return \"wall \"+w.width+\"x\"+w.height;
  var q=C.place(s.width,s.height,p.crop),c1=document.createElement(\"canvas\"),c2=document.createElement(\"canvas\");c1.width=c2.width=1920;c1.height=c2.height=1080;
  var x1=c1.getContext(\"2d\"),x2=c2.getContext(\"2d\");x1.fillStyle=\"#000\";x1.fillRect(0,0,1920,1080);x1.drawImage(s,q[0],q[1],q[2],q[3],q[4],q[5],q[6],q[7]);x2.drawImage(w,0,0);
  var a=x1.getImageData(0,0,1920,1080).data,d=x2.getImageData(0,0,1920,1080).data,t=0,n=0;
  for(var i=0;i<a.length;i+=4*97){t+=Math.abs(a[i]-d[i])+Math.abs(a[i+1]-d[i+1])+Math.abs(a[i+2]-d[i+2]);n+=3}
  return \"1920x1080 \"+(t/n<8?\"match\":\"differ \"+(t/n).toFixed(1))}catch(e){return String(e.message||e)}})()"; }
# JS: the photo's listed crop and edited, "x|z|edited" ("none" before any)
cropof_js() { echo "(function(){var l=FrameDevice.libraryNow(),p=l&&l.photos.filter(function(p){return p.id===\"$1\"})[0];
  return p&&p.crop?p.crop.x+\"|\"+p.crop.z+\"|\"+(p.edited>0?\"edited\":\"new\"):\"none\"})()"; }
# JS: "none" unless wall.jpg or thumb.jpg as the stick serves them hold the planted string.
planted_js() { echo "(async function(){var b=\"https://cleanframe.local/home/user/$1/$2/\",n=[],f=[\"wall\",\"thumb\",\"src\"];
  for(var j=0;j<f.length;j++){var r=await fetch(b+f[j]+\".jpg\",{cache:\"no-store\"}),a=new Uint8Array(await r.arrayBuffer()),s=\"\";
  if(r.status!==200){n.push(f[j]+\":\"+r.status);continue}for(var i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);if(s.indexOf(\"PLANTED\")>=0)n.push(f[j])}return n.length?n.join(\" \"):\"none\"})()"; }
PG_OPEN_JS='(function(){var s=ShoreQA.state();if(s.look.bg!=="photo")return "flat@"+s.wallpaper;
  ShoreQA.scene("appearance");var r=document.querySelectorAll(".section-groups .setting-choice .setting-name");
  if(!r.length)return "nogroups@"+s.wallpaper;return Array.prototype.map.call(r,function(e){return e.firstChild.textContent}).join("|")+"@"+s.wallpaper})()'
PG_BG=; PG_ORIG=; PG_LEFT=0   # PG_BG: the Background type the run found, when it switched it to Photo
# look_to <step> <name> <bg> <wall>: ShoreQA.look({bg}) applies asynchronously: a Photo look first loads
# its wallpaper bundle (setLook: S.pending, loadBundle), and only then commits, so the call itself still
# answers the old type (seen on the stick 2026-11-01: "light", pending, then photo ~0.7 s later). Ask
# once, then poll <bg>|<wall>|ready (ShoreQA.ready(): nothing pending, shown = committed) for up to
# LOOK_WAIT s (default 20); PASS with <bg>, or FAIL with the last answer.
LOOK_STATE='(function(){var s=ShoreQA.state();return s.look.bg+"|"+s.wallpaper+"|"+(ShoreQA.ready()?"ready":"pending")})()'
look_to() {
  local got=
  pg "$1" "ShoreQA.look({bg:\"$3\"}).look.bg" >/dev/null
  for _ in $(seq 1 "${LOOK_WAIT:-20}"); do got=$(pg "$1wait" "$LOOK_STATE"); [ "$got" = "$3|$4|ready" ] && break; sleep 1; done
  if [ "$got" = "$3|$4|ready" ]; then pass "$2" "$3"; else fail "$2" "$got" "'$3|$4|ready' within ${LOOK_WAIT:-20} s"; fi
}
# Leave the stick as found: a test group a failed step left behind, the original wallpaper, then the
# original Background type. Once; from the end of the section or from the EXIT trap (cleanup).
pg_leave_as_found() {
  [ $PG_LEFT = 0 ] || return 0
  PG_LEFT=1
  if [ -n "$PG_GID" ]; then
    pg cleanup "(function(){var l=FrameDevice.libraryNow(),n=l?l.groups.filter(function(g){return g.id===\"$PG_GID\"}).length:0;if(n)FrameDevice.deleteGroup(\"$PG_GID\",function(){});return n})()" >/dev/null
  elif [ "$PG_BYNAME" = 1 ]; then   # the created group's id was unusable: delete every user group named $PG_NAME
    pg cleanupname "(function(){var l=FrameDevice.libraryNow(),g=l?l.groups.filter(function(g){return !g.builtin&&g.name===\"$PG_NAME\"}):[];g.forEach(function(x){FrameDevice.deleteGroup(x.id,function(){})});return g.length})()" >/dev/null
  fi
  pg home 'ShoreQA.scene("home").view' >/dev/null
  if [[ $PG_ORIG =~ ^[A-Za-z0-9_-]+$ ]] && [ -n "$PG_PICK" ]; then
    pg restore "ShoreQA.look({wall:\"$PG_ORIG\"}).wallpaper" >/dev/null
    GOT=; for _ in $(seq 1 15); do GOT=$(pg restored 'ShoreQA.state().wallpaper'); [ "$GOT" = "$PG_ORIG" ] && break; sleep 1; done
    expect "original wallpaper restored" "$GOT" "$PG_ORIG"
  fi
  if [ -n "$PG_BG" ]; then
    look_to restorebg "original Background type restored" "$PG_BG" "$PG_ORIG"
  fi
}
# The section's Add photo steps need photos straight to the stick (FrameDevice.materialJobs, e782aec):
# a photo-groups build from before it is SKIPped as a whole.
if feature photogroups "Photo groups" photo-groups.js CleanFramePhotoGroups \
   && feature upload "Photo groups straight to the stick" device.js FrameDevice materialJobs; then
PG_STARTED=1   # from here on the EXIT trap runs pg_leave_as_found too
PG_OPEN=$(pg open "$PG_OPEN_JS")
if [[ $PG_OPEN == flat@* ]]; then
  GOT=$(pg bg 'ShoreQA.state().look.bg')
  if [[ $GOT =~ ^(dark|light)$ ]]; then
    PG_BG=$GOT
    look_to tophoto "Background switched to Photo for the photo groups" photo "${PG_OPEN##*@}"
    PG_OPEN=$(pg open "$PG_OPEN_JS")
  else fail "Background type read" "$GOT" "dark or light"; fi
fi
PG_LABELS=${PG_OPEN%@*}; PG_ORIG=${PG_OPEN##*@}
[[ $PG_OPEN == *@* ]] || PG_ORIG=
# pg_down_to <action>: Down until that row has focus (at most 30 presses); prints the focused action.
pg_down_to() {
  local f=; for _ in $(seq 1 30); do f=$(pg "focus:$1" "$FOC"); [ "$f" = "$1" ] && break; k DPAD_DOWN 0.4; done; echo "$f"
}
case $PG_LABELS in
  flat) fail "photo groups" "Background is still not Photo (Groups are disabled)" "Photo (the section cannot run)" ;;
  Scenery\|Space*)
    pass "Background groups" "$PG_LABELS"
    expect "tick on the applied wallpaper's group" "$(pg tick '(function(){var t=document.querySelector(".section-groups .setting-tick .ico"),b=t&&t.parentNode.parentNode,G=window.CleanFrameWallpaperGroups,s=ShoreQA.state(),w=(window.CleanFrameWallpapers||[]).filter(function(x){return x.id===s.wallpaper})[0];
      return (w?G.groupOf(w):(s.wallpaper.split("-")[1]||"scenery"))+"|"+(b?b.getAttribute("data-action").replace("background.group.",""):"none")})()' | sed 's/^\(.*\)|\1$/same/')" same
    k DPAD_DOWN
    expect "remote reaches the Space row" "$(pg_down_to background.group.space)" background.group.space
    k DPAD_CENTER 2
    expect "OK opens Space's page" "$(pg page '(function(){var p=ShoreQA.state().wallpaperPicker;return p?ShoreQA.state().view+"|"+p.group+"|"+p.zone:null})()')" "wallpaper|space|photo"
    PG_PICK=$(pg cand '(function(){var p=ShoreQA.state().wallpaperPicker;return p&&p.zone==="photo"?p.candidate:null})()')
    if [ "$PG_PICK" = "$PG_ORIG" ]; then k DPAD_DOWN; PG_PICK=$(pg cand '(function(){var p=ShoreQA.state().wallpaperPicker;return p&&p.zone==="photo"?p.candidate:null})()'); fi
    if [[ $PG_PICK =~ ^[A-Za-z0-9_-]+$ ]] && [ "$PG_PICK" != null ]; then
      k DPAD_CENTER
      GOT=; for _ in $(seq 1 15); do GOT=$(pg applied 'ShoreQA.state().wallpaper'); [ "$GOT" = "$PG_PICK" ] && break; sleep 1; done
      expect "Space photo applied" "$GOT" "$PG_PICK"
      pg reload '(setTimeout(function(){location.reload()},100),"reloading")' >/dev/null; sleep 6
      AFTER=$(pg after '(function(){var s=ShoreQA.state(),G=window.CleanFrameWallpaperGroups,w=(window.CleanFrameWallpapers||[]).filter(function(x){return x.id===s.wallpaper})[0];return s.wallpaper+"|"+(w&&G?G.groupOf(w):"none")+"|"+s.mode})()')
      # Any rotating mode starts on a random photo of the group at start-up since 2026-11-03 (only
      # "Each time Home opens" before): then only the group must survive.
      if [ "${AFTER##*|}" != fixed ]; then expect "Space choice survives reload" "$(cut -d'|' -f2 <<<"$AFTER")" space
      else expect "Space choice survives reload" "${AFTER%|*}" "$PG_PICK|space"; fi
    else fail "Space photo" "$PG_PICK" "a focused Space photo"; PG_PICK=; fi

    # + Add group
    pg scene 'ShoreQA.scene("appearance").view' >/dev/null; k DPAD_DOWN
    expect "remote reaches + Add group" "$(pg_down_to background.addgroup)" background.addgroup
    k DPAD_CENTER 2
    NAMEV=$(pg name '(function(){var e=document.getElementById("groupNameInput");return ShoreQA.state().view+"|"+(e?e.value:"")})()')
    if [[ $NAMEV =~ ^groupname\|Group\ [0-9]+$ ]]; then pass "Add group prefilled" "$NAMEV"; else fail "Add group prefilled" "$NAMEV" "'groupname|Group N'"; fi
    # null: no keyboard, SKIP; empty: the adb read failed, FAIL (the name is still set through DevTools).
    IME=$(a shell settings get secure default_input_method | tr -d '\r' || true)
    if [ -z "$IME" ] || [ "$IME" = null ]; then
      if [ "$IME" = null ]; then skip "Add group typing" "no keyboard (no default input method on $S)"
      else fail "Add group typing: default input method read" "" "an input method or null (adb settings get failed?)"; fi
      pg setname "(function(){var e=document.getElementById(\"groupNameInput\");e.value=\"$PG_NAME\";e.dispatchEvent(new Event(\"input\"));return e.value})()" >/dev/null
    else
      k DPAD_CENTER 2
      expect "OK puts the text box in typing" "$(pg typing 'document.activeElement&&document.activeElement.id')" groupNameInput
      SHOWN=$(a shell dumpsys input_method | grep -c 'mInputShown=true' || true)
      expect "the keyboard comes up" "$SHOWN" 1
      pg clear '(function(){var e=document.getElementById("groupNameInput");e.value="";e.dispatchEvent(new Event("input"));return 1})()' >/dev/null
      a shell input text "${PG_NAME// /%s}" >/dev/null || true; sleep 1
      k BACK 1   # hides the keyboard; the text box keeps the text
      expect "the keyboard returns the name" "$(pg typed 'document.getElementById("groupNameInput").value')" "$PG_NAME"
    fi
    k DPAD_DOWN; k DPAD_CENTER 3
    CREATED=$(pg created '(function(){var s=ShoreQA.state(),p=s.wallpaperPicker;return p?s.view+"|"+document.getElementById("appearanceTitle").textContent+"|"+p.zone+"|"+p.group:s.view})()')
    PG_GID=${CREATED##*|}
    expect "Create opens the new group on Add photo" "${CREATED%|*}" "wallpaper|$PG_NAME|acts"
    # An id that is not g + 8 [0-9a-z] skips every step below (upload, Adjust, Remove, Delete): that is a
    # FAIL, never a quiet pass. It is not put into any expression; a group the page did create is
    # deleted by its name instead (PG_BYNAME, pg_leave_as_found).
    if ! [[ $PG_GID =~ ^g[0-9a-z]{8}$ ]]; then
      fail "the new group's id" "${PG_GID:0:40}" "g + 8 of [0-9a-z] (the steps that need it are not run)"
      PG_GID=; PG_BYNAME=1
    fi

    if [ -n "$PG_GID" ]; then
      # Add photo, straight to the stick (contract 4 v2). Only the app can read or write R: adb shell gets
      # "Permission denied" on /sdcard/Android/data/<pkg>/ on this Android 11 user build (DEVICE-CHECK 1,
      # 2026-10-31), so nothing here touches R over adb. Every check goes through the app's own upload
      # server (curl from here, like a phone) or the page (DevTools, the user/ route the page draws from).
      PG_TMP=$(mktemp -d "$HOME/scratch/cf-stick-photo.XXXXXX"); W=web/assets/wallpapers
      k DPAD_CENTER 3
      UP=$(pg upload '(function(){var c=document.querySelector(".upload-qr"),t=document.querySelector(".upload-code");return ShoreQA.state().view+"|"+(t?t.textContent.replace(/\D/g,""):"")+"|"+(c&&c._url?1:0)})()')
      CODE=$(cut -d'|' -f2 <<<"$UP")
      # The pairing code is a credential like the token: never printed, PASS or FAIL.
      UPSHOW=$UP; [[ ! $CODE =~ ^[0-9]+$ ]] || UPSHOW=${UP//$CODE/<code>}
      if [[ $UP =~ ^upload\|[0-9]{6}\|1$ ]]; then pass "Add photo shows a QR and a code" "$UPSHOW"; else fail "Add photo shows a QR and a code" "$UPSHOW" "'upload|<6 digits>|1'"; fi
      UPURL=$(pg upurl '(function(){var c=document.querySelector(".upload-qr");return c&&c._url||""})()')
      UPHOST=${UPURL#http://}; UPHOST=${UPHOST%%:*}; UPBASE=
      STICKIPS=$(a shell ip -4 -o addr show | tr -d '\r' || true)   # the stick's own addresses
      if [[ $UPURL =~ ^http://[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:8097/u/[0-9a-f]{32}$ ]] && [[ $STICKIPS == *"inet $UPHOST/"* ]]; then
        UPBASE=${UPURL%/u/*}; UPTOK=${UPURL##*/u/}
        pass "Add photo URL is the stick's own" "$UPBASE/u/<token>"
        expect "the stick serves the code page" "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$UPBASE/" || true)" 200
        # A phone that cannot scan types the address and the code: it must lead to the QR's own page.
        GOT=$(curlk "$UPBASE/" --data-cfg "code=$CODE" -s -m 10 -o /dev/null -w '%{http_code} %{redirect_url}' || true)
        if [ "$GOT" = "303 $UPURL" ]; then pass "the code on screen opens the QR page" "303 to $UPBASE/u/<token>"
        else fail "the code on screen opens the QR page" "${GOT//$UPTOK/<token>}" "'303 $UPBASE/u/<token>'"; fi
        expect "the QR's page answers" "$(curlk "$UPURL" -s -m 10 -o /dev/null -w '%{http_code}' || true)" 200
        # This machine sends a JPEG with a planted GPS/EXIF string (DEVICE-CHECK 10); the stick prepares it, the page
        # draws its materials, and it must appear in the group with all seven files and no planted string.
        UPJPG=$PG_TMP/upload.jpg
        python3 -I -c 'import sys; b = open(sys.argv[1], "rb").read(); g = b"Exif\0\0MM\0*PLANTED-GPS-cfstick"
open(sys.argv[2], "wb").write(b[:2] + b"\xff\xe1" + (len(g) + 2).to_bytes(2, "big") + g + b[2:])' "$W/space-moon-thumb.jpg" "$UPJPG" || : > "$UPJPG"
        UPANS=$(curlk "$UPBASE/api/$UPTOK/photos" -s -m 90 -F "group=$PG_GID" -F "photo=@$UPJPG;type=image/jpeg;filename=Stick upload.jpg" || true)
        UPPID=$(pid_of "$UPANS")
        if [[ $UPPID =~ ^p[0-9a-z]{10}$ ]]; then pass "curl uploads a photo to the stick" "$UPPID"
        else fail "curl uploads a photo to the stick" "${UPANS:0:120}" "{\"ok\":true,\"photo\":{...}}"; UPPID=; fi
        if [ -n "$UPPID" ]; then
          UPIN="(function(){var l=FrameDevice.libraryNow();return l?l.photos.filter(function(p){return p.group===\"$PG_GID\"&&p.id===\"$UPPID\"}).length:-1})()"
          GOT=; for _ in $(seq 1 "${UPLOAD_WAIT:-90}"); do GOT=$(pg upwait "$UPIN"); [ "$GOT" = 1 ] && break; sleep 1; done
          expect "the upload appears in the group (materials drawn by the page)" "$GOT" 1
          expect "the photo appears while Add photo is open" "$(pg added 'document.querySelector(".upload-added").textContent')" "1 photo added to $PG_NAME"
          expect "the stick serves its seven files" "$(pg upfiles "$(user_files_js "$PG_GID" "$UPPID")")" "wall:200:jpeg thumb:200:jpeg panel:200:jpeg field-blur:200:jpeg field-clear:200:jpeg field-frost:200:jpeg study-soft:200:jpeg"
          expect "its meta.json is read (name from the upload)" "$(pg upmeta "(function(){var l=FrameDevice.libraryNow(),p=l&&l.photos.filter(function(p){return p.id===\"$UPPID\"})[0];return p?p.name:\"\"})()")" "Stick upload"
          SRVTHUMB=$(curlk "$UPBASE/api/$UPTOK/thumbs/$PG_GID/$UPPID.jpg" -s -m 10 -o "$PG_TMP/server-thumb.jpg" -w '%{http_code}' || true)
          SRVTHUMB=$SRVTHUMB:$(grep -ac PLANTED "$PG_TMP/server-thumb.jpg" 2>/dev/null || true)
          expect "no planted metadata reached the stick" "$(pg upleak "$(planted_js "$PG_GID" "$UPPID")")|server thumb $SRVTHUMB" "none|server thumb 200:0"
          expect "its wall is the centred 16:9 of the copy it kept (no crop sent)" "$(pg upcrop "$(crop_js "$PG_GID" "$UPPID")")" "1920x1080 match"
          # A second photo, framed (crop 0,0,2: zoomed 2x into its top-left), stays in the group for the
          # Adjust and Remove photos steps below.
          KEEPANS=$(curlk "$UPBASE/api/$UPTOK/photos" -s -m 90 -F "group=$PG_GID" -F "crop=0,0,2" -F "photo=@$W/space-moon-thumb.jpg;type=image/jpeg;filename=Stick keep.jpg" || true)
          KEEPPID=$(pid_of "$KEEPANS")
          if [[ $KEEPPID =~ ^p[0-9a-z]{10}$ ]]; then
            KIN="(function(){var l=FrameDevice.libraryNow();return l?l.photos.filter(function(p){return p.group===\"$PG_GID\"&&p.id===\"$KEEPPID\"}).length:-1})()"
            GOT=; for _ in $(seq 1 "${UPLOAD_WAIT:-90}"); do GOT=$(pg keepwait "$KIN"); [ "$GOT" = 1 ] && break; sleep 1; done
            expect "a second upload appears too" "$GOT" 1
            # 512x341 at zoom 2: a 256x144 frame, its centre kept on the photo: x 0.25
            expect "the framed upload lists its crop" "$(pg keepcrop "$(cropof_js "$KEEPPID")")" "0.25|2|new"
            expect "its wall is the frame it was sent with" "$(pg keepwall "$(crop_js "$PG_GID" "$KEEPPID")")" "1920x1080 match"
            # Adjust through the bridge: re-framed under the same id, then its materials are drawn again.
            pg recrop "(function(){FrameDevice.recrop(\"$PG_GID\",\"$KEEPPID\",\"1,1,2\",function(e){window.__cfRecrop=e?(e.detail||e.message):\"ok\"});return 1})()" >/dev/null
            RST="(async function(){return await new Promise(function(r){FrameDevice.recropState(\"$PG_GID\",\"$KEEPPID\",function(e,s){r(e?\"error\":s.state+(s.reason?\":\"+s.reason:\"\"))})})})()"
            GOT=; for _ in $(seq 1 "${UPLOAD_WAIT:-90}"); do GOT=$(pg recropstate "$RST"); [ "$GOT" = 'done' ] && break; case $GOT in failed*|error) break ;; esac; sleep 1; done
            expect "Adjust re-frames the photo (recrop, then its materials)" "$GOT|$(pg recropanswer 'window.__cfRecrop')" "done|ok"
            GOT=; for _ in $(seq 1 10); do GOT=$(pg keepcrop2 "$(cropof_js "$KEEPPID")"); [ "$GOT" = "0.75|2|edited" ] && break; sleep 1; done
            expect "the same photo lists its new crop and when it was adjusted" "$GOT" "0.75|2|edited"
            expect "its wall is the new frame" "$(pg keepwall2 "$(crop_js "$PG_GID" "$KEEPPID")")" "1920x1080 match"
          else fail "a second upload appears too" "${KEEPANS:0:120}" "{\"ok\":true,\"photo\":{...}}"; KEEPPID=; fi
          expect "the API deletes the first" "$(curlk "$UPBASE/api/$UPTOK/photos/$PG_GID/$UPPID" -s -m 10 -X DELETE || true)" '{"ok":true}'
          GOT=; for _ in $(seq 1 10); do GOT=$(pg upgone "$UPIN"); [ "$GOT" = 0 ] && break; sleep 1; done
          expect "and it leaves the group" "$GOT" 0
        fi
      else fail "Add photo URL is the stick's own" "${UPURL%/u/*}" "'http://<an address of the stick>:8097/u/<token>'"; fi
      k BACK 2
      expect "Back closes Add photo" "$(pg view 'ShoreQA.state().view')" wallpaper
      # Closing the screen stops the server (DEVICE-CHECK 9's other half): the port refuses connections.
      # Only a refused connection (curl exit 7) passes: a timeout (28), a reset (56) or any answer also
      # print code 000 or another code, and none of them shows the port is closed.
      if [ -n "$UPBASE" ]; then
        CRC=0; GOT=$(curl -s -m 5 -o /dev/null -w 'closed:%{http_code}' "$UPBASE/") || CRC=$?
        if [ "$CRC" = 7 ]; then pass "closing Add photo stops the upload server" "connection refused (curl exit 7)"
        else fail "closing Add photo stops the upload server" "curl exit $CRC, $GOT" "a refused connection (curl exit 7)"; fi
      else fail "closing Add photo stops the upload server" "no upload URL" "a refused connection"; fi

      # Remove photos: OK on the photo asks; No keeps it.
      k DPAD_RIGHT; k DPAD_CENTER; k DPAD_DOWN; k DPAD_CENTER
      expect "Remove asks Yes/No on No" "$(pg confirmphoto '(function(){var p=ShoreQA.state().wallpaperPicker;return p.zone+"|"+p.confirm+"|"+(document.querySelector(".group-confirm-button.is-focus")||{}).textContent})()')" "confirm|photo|No"
      k DPAD_CENTER
      expect "No keeps the photo" "$(pg kept '(function(){var p=ShoreQA.state().wallpaperPicker;return p.zone+"|"+document.querySelectorAll(".wallpaper-choice").length+"|"+p.removing})()')" "photo|1|true"

      # Adjust by remote: Back ends Remove, Up to the actions, Right to Adjust, OK, Down to the photo, OK
      # opens the Adjust screen with the photo drawn full screen; Back to its buttons, Back cancels.
      k BACK; k DPAD_UP; k DPAD_RIGHT; k DPAD_CENTER; k DPAD_DOWN; k DPAD_CENTER
      GOT=; for _ in $(seq 1 20); do GOT=$(pg adjustopen '(function(){var e=document.querySelector("#photoAdjust:not([hidden]) .pa-img");return ShoreQA.state().view+"|"+(e&&!e.hidden&&e.naturalWidth>0?"drawn":"no")})()'); [ "$GOT" = "adjust|drawn" ] && break; sleep 0.5; done
      expect "Adjust opens the photo full screen by remote" "$GOT" "adjust|drawn"
      k BACK; k BACK
      expect "Back, Back leaves Adjust without saving" "$(pg adjustleft '(function(){var p=ShoreQA.state().wallpaperPicker;return ShoreQA.state().view+"|"+(p&&p.zone)})()')" "wallpaper|photo"
      expect "nothing was saved" "$(pg keepcrop3 "$(cropof_js "$KEEPPID")")" "0.75|2|edited"

      # Delete group: Yes removes it from the page and the stick.
      k BACK; k DPAD_UP; k DPAD_RIGHT; k DPAD_RIGHT; k DPAD_RIGHT
      expect "remote reaches Delete" "$(pg focus:group.delete "$FOC")" group.delete
      k DPAD_CENTER
      expect "Delete asks Yes/No on No" "$(pg confirmgroup '(function(){var p=ShoreQA.state().wallpaperPicker;return p.zone+"|"+p.confirm+"|"+(document.querySelector(".group-confirm-button.is-focus")||{}).textContent})()')" "confirm|group|No"
      k DPAD_LEFT; k DPAD_CENTER 3
      expect "Yes deletes the group" "$(pg deleted "(function(){return ShoreQA.state().view+\"|\"+document.querySelectorAll('[data-action=\"background.group.$PG_GID\"]').length})()")" "appearance|0"
      # Gone from the stick too: the app no longer lists it, and its photo's files are no longer served.
      GONE=$(pg gone "(async function(){var l=FrameDevice.libraryNow(),n=l?l.groups.filter(function(g){return g.id===\"$PG_GID\"}).length:-1;
        var r=\"$KEEPPID\"?(await fetch(\"https://cleanframe.local/home/user/$PG_GID/$KEEPPID/wall.jpg\",{cache:\"no-store\"})).status:404;return n+\"|\"+r})()")
      expect "the group is gone from the stick" "$GONE" "0|404"
    fi ;;
  *) fail "Background groups" "$PG_LABELS" "'Scenery|Space...' (a Groups section on Settings > Background)" ;;
esac
pg_leave_as_found
fi   # feature photogroups + upload

# Random rotation and its tick (web/wallpaper-groups.js WG.random and the patched main.js;
# 2026-11-03: "it starts on a random photo"; "there should be a tick on rotate not a selected photo").
# Every expression starts with /*rot:<step>*/ (tests/tools.test.sh's fake page answers by that).
#   rotorig   the mode, wallpaper and Background type the run found (put back at the end, also from
#             the EXIT trap); a flat background is switched to Photo for the section
#   rot*      by remote: Settings > Background > Rotation; Fixed first (when it was not), then Every
#             hour (no timed change can come during the run): the wallpaper moves at once to ANOTHER
#             photo of the same group (the same one only when the group has one)
#   rottick   Back on Background: the Rotation row is ticked and the rotating group's row says Rotating
#   rotpage   that group's page ticks no photo (no is-applied, no tick drawn) and marks the one on
#             screen is-showing, "Now showing"
ROT_STATE='(function(){var s=ShoreQA.state();return s.mode+"|"+s.wallpaper+"|"+s.look.bg})()'
# rot_group: the group of the wallpaper on screen, and how many photos it has ("<group>|<n>")
ROT_GROUP='(function(){var s=ShoreQA.state(),G=CleanFrameWallpaperGroups,a=document.querySelectorAll(".section-groups .setting-choice[data-action]"),i,t;
  for(i=0;i<a.length;i++){t=a[i];if(t.querySelector(".setting-tick .ico"))return t.getAttribute("data-action").replace("background.group.","")+"|"+(t.querySelector(".setting-note")||{}).textContent}return "none|"})()'
rot() { local v; v=$(dt "/*rot:$1*/$2"); v=${v#\"}; echo "${v%\"}"; }
rot_focus() { local i f; for i in $(seq 1 12); do f=$(rot "$1" "$FOC"); [ "$f" = "$3" ] && break; k "$2" 0.5; done; echo "$f"; }
# rot_ready <step>: poll (at most ROT_WAIT, default 20, times a second apart) until the page has
# nothing pending; prints "<mode>|<wallpaper>" and returns 0. Never settled: prints the last answer
# whole ("...|pending", DT_ERROR) and returns 1, so a timeout can never read as a settled page; every
# caller FAILs on it (it runs in $(...), where a fail() here would be lost).
rot_ready() {
  local got=
  for _ in $(seq 1 "${ROT_WAIT:-20}"); do
    got=$(rot "$1" '(function(){var s=ShoreQA.state();return s.mode+"|"+s.wallpaper+"|"+(ShoreQA.ready()?"ready":"pending")})()')
    [[ $got == *'|ready' ]] && { echo "${got%|*}"; return 0; }
    sleep 1
  done
  echo "$got"; return 1
}
# rot_settled <step> <var>: rot_ready into <var>; never settled: FAIL "rotation: the page settled
# (<step>)" and <var> is "unsettled: <last answer>", which no later check of it can take for a state.
rot_settled() {
  local v
  if v=$(rot_ready "$1"); then printf -v "$2" '%s' "$v"
  else printf -v "$2" 'unsettled: %s' "$v"; fail "rotation: the page settled ($1)" "$v" "'<mode>|<wallpaper>|ready' within ${ROT_WAIT:-20} polls"; fi
}
rot_restore() {
  # Every step is attempted; a step that never settles makes the answer "unsettled(<step>): ..." so
  # the caller's expect (or nobody, from the EXIT trap) cannot take it for the state put back.
  local m=${ROT_ORIG%%|*} rest=${ROT_ORIG#*|} w b bad= v
  w=${rest%%|*}; b=${rest#*|}
  rot restore "(function(){ShoreQA.scene(\"appearance\");ShoreQA.activate(\"background.rotation\");ShoreQA.activate(\"rotation.$m\");ShoreQA.scene(\"home\");ShoreQA.look({wall:\"$w\"});return ShoreQA.state().mode})()" >/dev/null
  v=$(rot_ready restoredwall) || bad="unsettled(restoredwall): $v; "
  if [ "$b" != photo ]; then
    rot restorebg "ShoreQA.look({bg:\"$b\"}).look.bg" >/dev/null
    v=$(rot_ready restoredbg) || bad+="unsettled(restoredbg): $v; "
  fi
  ROT_ORIG=
  echo "$bad$(rot restored "$ROT_STATE")"
}
if feature rotation "Random rotation" wallpaper-groups.js CleanFrameWallpaperGroups random; then
home
ROT_ORIG=$(rot rotorig "$ROT_STATE")
if [[ $ROT_ORIG =~ ^(fixed|open|5|15|60)\|[A-Za-z0-9_-]+\|(photo|dark|light)$ ]]; then pass "rotation: what the run found" "$ROT_ORIG"
else fail "rotation: what the run found" "$ROT_ORIG" "'<mode>|<wallpaper>|<bg>'"; ROT_ORIG=; fi
if [ -n "$ROT_ORIG" ]; then
  if [ "${ROT_ORIG##*|}" != photo ]; then rot tophoto 'ShoreQA.look({bg:"photo"}).look.bg' >/dev/null; rot_settled tophotowait ROT_BGW; fi
  ROT_W0=$(cut -d'|' -f2 <<<"$ROT_ORIG")
  ROT_G=$(rot group "(function(){ShoreQA.scene(\"appearance\");return $ROT_GROUP})()"); ROT_GID=${ROT_G%%|*}
  [[ $ROT_GID =~ ^[a-z0-9-]+$ ]] && [ "$ROT_GID" != none ] && pass "rotation: the applied group" "$ROT_G" || fail "rotation: the applied group" "$ROT_G" "'<group>|<n> photos' (a ticked group row)"
  ROT_N=$(rot count "(function(){var n=0,W=window.CleanFrameWallpapers||[],G=CleanFrameWallpaperGroups,l=FrameDevice.libraryNow&&FrameDevice.libraryNow();
    return String(G.members(G.merge(G.clean(W),l),\"$ROT_GID\").length)})()")
  expect "rotation: Background's Rotation row" "$(rot_focus focus:background.rotation DPAD_DOWN background.rotation)" background.rotation
  k DPAD_CENTER 2;          expect "rotation: the Rotation page" "$(rot page 'ShoreQA.state().settingsGroup')" rotation
  if [ "${ROT_ORIG%%|*}" != fixed ]; then
    expect "rotation: Fixed focused" "$(rot_focus focus:rotation.fixed DPAD_UP rotation.fixed)" rotation.fixed
    k DPAD_CENTER 2;        expect "rotation: Fixed saved" "$(rot fixed 'ShoreQA.state().mode')" fixed
    rot_settled fixedwall ROT_W0; ROT_W0=${ROT_W0#*|}
  fi
  expect "rotation: Every hour focused" "$(rot_focus focus:rotation.60 DPAD_DOWN rotation.60)" rotation.60
  k DPAD_CENTER 2
  rot_settled onwall ROT_ON
  ROT_W1=${ROT_ON#*|}
  ROT_G1=$(rot ongroup "(function(){var G=CleanFrameWallpaperGroups,W=G.merge(G.clean(window.CleanFrameWallpapers||[]),FrameDevice.libraryNow&&FrameDevice.libraryNow());return G.groupOf(G.find(W,\"$ROT_W1\"))})()")
  if [ "${ROT_ON%%|*}" = 60 ] && [ "$ROT_G1" = "$ROT_GID" ] \
     && { { [ "$ROT_N" = 1 ] && [ "$ROT_W1" = "$ROT_W0" ]; } || { [[ $ROT_N =~ ^[0-9]+$ ]] && [ "$ROT_N" -gt 1 ] && [ "$ROT_W1" != "$ROT_W0" ]; }; }; then
    pass "rotation: turned on, another photo of the group at once" "$ROT_W0 -> $ROT_W1 ($ROT_GID, $ROT_N photos)"
  else fail "rotation: turned on, another photo of the group at once" "$ROT_ON in $ROT_G1 (from $ROT_W0, $ROT_N photos)" "'60|<another $ROT_GID photo>'"; fi
  k BACK
  expect "rotation: the tick is on Rotation" "$(rot tick "(function(){var r=document.querySelector(\".section-rotation .setting-choice\");
    return ShoreQA.state().settingsGroup+\"|\"+(r&&r.querySelector(\".setting-tick .ico\")?\"ticked\":\"no tick\")})()")" "background|ticked"
  expect "rotation: the group's row" "$(rot_focus "focus:background.group.$ROT_GID" DPAD_UP "background.group.$ROT_GID")" "background.group.$ROT_GID"
  # Read once focused: the Groups list shows a window of rows, and with more groups (the Hugsun's, release
  # run 2026-11-03) the applied group's row was scrolled out and not in the page at all.
  expect "rotation: the group's row says Rotating" "$(rot grouprot '(function(){var g=document.querySelector(".section-groups .setting-choice.is-focus");return g?(/Rotating/.test(g.textContent)?"rotating":"no: "+g.textContent):"no row"})()')" rotating
  k DPAD_CENTER 2
  expect "rotation: the group page ticks no photo, marks the one on screen" "$(rot page2 '(function(){var a=document.querySelectorAll("#appearance .wallpaper-choice"),ap=0,tk=0,sh=0,i,n=document.querySelector(".wallpaper-preview-state");
    for(i=0;i<a.length;i++){if(/ is-applied/.test(a[i].className))ap++;if(a[i].querySelector(".setting-tick .ico"))tk++;if(/ is-showing/.test(a[i].className))sh++}
    return ShoreQA.state().view+"|"+ap+"|"+tk+"|"+sh+"|"+(n?n.textContent:"")})()')" "wallpaper|0|0|1|Now showing"
  k BACK; k BACK; k BACK
  expect "rotation: put back as found" "$(rot_restore)" "$ROT_ORIG"
  ROT_ORIG=   # (rot_restore ran in a subshell)
  home
fi
fi   # feature rotation

# Settings > Bluetooth (web/bluetooth.js; 2026-11-03). By remote: Settings, Down to Bluetooth (its
# row shows what the stick answered, never "Checking…" once it has), OK opens its page: the paired
# devices with their state, or the reason the stick gave; Down to Pair new device, OK: Android TV's
# own "Pair accessory" page comes to the front; Back returns to CleanFrame, still on the Bluetooth
# page; Home leaves it. Nothing is paired, unpaired or changed. Every expression starts with /*bt:<step>*/.
bt() { local v; v=$(dt "/*bt:$1*/$2"); v=${v#\"}; echo "${v%\"}"; }
# The page's own focus, as hx_focus reads it: "$FOC" takes the DOM's first .is-focus, which on the stick
# was Home's still-marked Settings button behind the open Settings panel (8af9984 staging run, 2026-11-03).
bt_focus() { local i f; for i in $(seq 1 12); do f=$(bt "$1" "ShoreQA.where().focusAction"); [ "$f" = "$3" ] && break; k "$2" 0.5; done; echo "$f"; }
BT_PAIR=com.android.tv.settings/.accessories.AddAccessoryActivity
if feature bluetooth "Bluetooth" bluetooth.js CleanFrameBluetooth; then
home
k DPAD_UP;                expect "focus on Settings" "$(hx_focus focus:util.settings DPAD_RIGHT util.settings)" util.settings
k DPAD_CENTER 2;          expect "Bluetooth row" "$(bt_focus focus:settings.group.bluetooth DPAD_DOWN settings.group.bluetooth)" settings.group.bluetooth
GOT=; for _ in $(seq 1 10); do GOT=$(bt row 'document.querySelector(".settings-group.is-focus .settings-group-value").textContent'); [ "$GOT" != "Checking…" ] && break; sleep 0.5; done
expect_not "Bluetooth row: the stick's answer" "$GOT" "Checking…"
k DPAD_CENTER 2
GOT=$(bt page '(function(){var s=ShoreQA.state(),b=s.bluetooth,n=document.querySelectorAll("#appearance [data-action^=\"bluetooth.device.\"]").length,r=document.querySelector("#appearance .section-btpair .setting-reason");
  if(s.settingsGroup!=="bluetooth")return "not on the page: "+s.settingsGroup;
  if(b.status==="ok")return n===Math.min(4,b.devices.length)?"devices|"+b.devices.length:"drew "+n+" of "+b.devices.length;
  return b.status==="error"&&r&&r.textContent.indexOf(b.reason)===0?"reason|"+b.reason:"status "+b.status})()')
if [[ $GOT == devices\|* || $GOT == reason\|* ]]; then pass "Bluetooth page lists the devices or the reason" "$GOT"
else fail "Bluetooth page lists the devices or the reason" "$GOT" "'devices|<n>' or 'reason|<why>'"; fi
# Unpair (web/bluetooth.js). NOTHING IS EVER UNPAIRED HERE: unpairing the remote would strand the stick,
# and headphones would need pairing again by hand. So this section has no Left key (Yes is to the left of
# where the question starts) and never sends OK while the question is open: a real remote press between
# reading the focus and sending OK could make that OK a real Yes. No against Yes is tests/bluetooth.test.js's
# job; tests/tools.test.sh fails on either here. It reads canUnpair; prints how many devices could go,
# and FAILs if all of them could or one is named like a remote (every stick here has a paired remote);
# asks the bridge about a token that does not exist; opens a device that stays paired and reads why; and on
# a device that could go, opens the question, reads that the focus starts on No, leaves by Back, and
# reads the TV's own list to prove it is still there.
bt_any() { local f; f=$(bt_focus "$1" DPAD_DOWN "$2"); [ "$f" = "$2" ] || f=$(bt_focus "$1" DPAD_UP "$2"); echo "$f"; }
if feature btunpair "Bluetooth Unpair" bluetooth.js CleanFrameBluetooth unpair; then
BT_CAN=$(bt canunpair 'String(ShoreQA.state().bluetooth.canUnpair)')
if [ "$BT_CAN" = true ] || [ "$BT_CAN" = false ]; then pass "Bluetooth reports canUnpair" "$BT_CAN"
else fail "Bluetooth reports canUnpair" "$BT_CAN" "true or false"; fi
BT_COUNT=$(bt counts '(function(){var d=ShoreQA.state().bluetooth.devices,n=0,r=[],i;for(i=0;i<d.length;i++)if(d[i].unpair===true){n++;if(/RC|MG4/.test(d[i].name))r.push(d[i].name)}return n+"|"+d.length+"|"+r.join(",")})()')
BT_N=${BT_COUNT%%|*}; BT_REST=${BT_COUNT#*|}; BT_TOTAL=${BT_REST%%|*}; BT_NAMED=${BT_REST#*|}
if ! [[ $BT_COUNT =~ ^[0-9]+\|[0-9]+\| ]]; then fail "Bluetooth unpairable devices" "$BT_COUNT" "'<n>|<total>|<names>'"
elif [ "$BT_TOTAL" -gt 0 ] && [ "$BT_N" = "$BT_TOTAL" ]; then fail "the remote looks unpairable" "$BT_N of $BT_TOTAL devices" "at least one device that stays paired"
else pass "Bluetooth unpairable devices" "$BT_N of $BT_TOTAL"; fi
if [ -n "$BT_NAMED" ] && [ "$BT_NAMED" != "$BT_COUNT" ]; then fail "a remote by name is unpairable" "$BT_NAMED" "none named RC or MG4"
else pass "no device named like a remote is unpairable" "$BT_N of $BT_TOTAL checked"; fi
expect "an unknown device is refused" "$(bt noid 'String(window.AndroidHome.bluetoothUnpair("no-such-id"))')" "That device is not paired here any more."
BT_WANT="Remotes and other devices stay paired here so the TV can't lose its remote. Use TV settings to remove them."
[ "$BT_CAN" = true ] || BT_WANT="This TV can't unpair from here. Use TV settings to remove it."
BT_NO=$(bt norow '(function(){var d=ShoreQA.state().bluetooth.devices,i;for(i=0;i<d.length;i++)if(d[i].unpair!==true)return "bluetooth.device."+i;return ""})()')
if [ -z "$BT_NO" ]; then skip "a device that stays paired says why" "every device listed can be unpaired, or none is listed"
else
  expect "a device that stays paired focused" "$(bt_any "focus:$BT_NO" "$BT_NO")" "$BT_NO"
  k DPAD_CENTER 1
  expect "a device that stays paired says why" "$(bt refused 'ShoreQA.state().view+"|"+document.querySelector(".msg-line").textContent')" "message|$BT_WANT"
  k BACK 1
fi
BT_YES=$(bt yesrow '(function(){var b=ShoreQA.state().bluetooth,d=b.devices,i;if(b.canUnpair)for(i=0;i<d.length;i++)if(d[i].unpair===true)return "bluetooth.device."+i+"|"+d[i].id;return ""})()')
if [ -z "$BT_YES" ]; then skip "the question on a device that could go" "no device can be unpaired here (canUnpair $BT_CAN)"
else
  BT_ROW=${BT_YES%%|*}; BT_ID=${BT_YES#*|}
  expect "a device that could go focused" "$(bt_any "focus:$BT_ROW" "$BT_ROW")" "$BT_ROW"
  k DPAD_CENTER 1
  expect "the question starts on No" "$(bt ask '(function(){var t=document.querySelector(".msg-title").textContent;return ShoreQA.state().view+"|"+ShoreQA.where().focusAction+"|"+(t.indexOf("Unpair ")===0&&t.slice(-1)==="?"?"asks":"no question")})()')" "message|message.no|asks"
  k BACK 1
  expect "Back leaves it paired" "$(bt kept "(function(){var d=JSON.parse(window.AndroidHome.bluetooth()).devices,i;for(i=0;i<d.length;i++)if(d[i].id===\"$BT_ID\")return ShoreQA.state().view+\"|kept\";return \"gone\"})()")" "appearance|kept"
fi
fi   # feature btunpair
expect "Pair new device focused" "$(bt_focus focus:bluetooth.pair DPAD_DOWN bluetooth.pair)" bluetooth.pair
k DPAD_CENTER 4;          expect "Pair new device opens Android's pairing page" "$(top)" "$BT_PAIR"
k BACK 3;                 expect "Back returns to CleanFrame" "$(top)" "$ACT"
                          expect "on the Bluetooth page again" "$(bt back 'ShoreQA.state().settingsGroup')" bluetooth
home;                     expect "Home leaves the Bluetooth page" "$(bt home 'ShoreQA.state().view')" home
fi   # feature bluetooth

snap
LOG=$(sort -u <<<"${LOG//$'\r'/}")
for p in $(grep -oE "am_proc_start: \[[0-9]+,[0-9]+,[0-9]+,$RE(:[^,]*)?," <<<"$LOG" | cut -d, -f2); do PIDS+=" $p "; done
count() { local n; n=$(grep -cE "$1" <<<"$LOG") || [ $? -eq 1 ] || n=GREP_ERROR; echo "$n"; }
if [ -n "$LOGERR" ]; then fail "logcat read" "$LOGERR" "the crash/main/system/events buffers"
else pass "logcat read" "$(wc -l <<<"$LOG") lines"; fi
expect "Java crashes"   "$(count "Process: $RE(:[^,]*)?, PID:")" 0
expect "native crashes" "$(count ">>> $RE(:[^ ]*)? <<<|Cmdline: $RE(:[^ ]*)?\$")" 0
RENDER=$( { grep -E "Renderer process \([0-9]+\) crash detected|Render process \([0-9]+\)('s crash| kill)" <<<"$LOG" || true; } \
  | awk -v pids="$PIDS" 'BEGIN { n = split(pids, p, " "); for (i = 1; i <= n; i++) ours[p[i]] = 1 } ($3 in ours)' | wc -l)
expect "renderer deaths" "$RENDER" 0
# threadtime pads the tag to 8 columns: the events line is "I am_anr  : [0,<pid>,<pkg>,...".
expect "ANRs"           "$(count "ANR in $RE([^.A-Za-z0-9_]|\$)|am_anr *: \[[0-9]+,[0-9]+,$RE,")" 0
# The live Home must still be Home: for staging this proves the staging app left it alone.
expect "HOME resolves to CleanFrame" "$(homepkg)" home.cleanframe.tv
[ $STAGING = 0 ] || ! clean_sha || tested_apk
check_adb_timeouts
dt_log_note

echo "passed $PASSES, failed $FAILS"
DONE=1
markers_after_run
if [ "$FAILS" -ne 0 ]; then echo "STICK_TEST_FAIL $FAILS"; exit 1; fi
echo STICK_TEST_OK

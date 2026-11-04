#!/bin/bash
# Offline tests for the release tooling: install.sh (staging and recovery gates, per stick; signer pin,
# recorded sha256), tools/rollback.sh, build.sh's provenance guard and provenance file (versionName,
# apksigner failure, no stale sidecars), full-clone and dirty-tree refusals, tools/check.py's test runner (recursion guard, per-test timeout),
# tools/backup-signing-key.sh (fake key, temp destination, run lock), tools/stick-test.sh's gate
# logic (fake adb + fake DevTools + fake curl: per-stick promotion and recovery markers with the tested APK's
# sha256, the flat-Background switch and restore, no upload token in curl's argv, mode refusals,
# crash/ANR counting, adb timeouts, the page-ready wait after a cold start, feature sections an older
# app predates SKIPped only when its commit agrees, undefined as no answer, the pairing code never
# printed, Bluetooth's Unpair read and left by Back, never carried out), tools/devtools-eval.mjs (fake DevTools), and static gates: no early-exit consumers in
# pipelines (every *.sh in the repo), no dead BASH_REMATCH captures, shellcheck parse traps, one
# copy of the adb helpers (tools/adb-lib.sh), every test in tests/ classified for tools/rollback.sh.
# Usage (from the repo root): bash tests/tools.test.sh
# No device, no network, no real APK, no real key: install.sh runs from a temp copy against a fake
# adb and a fake aapt2 driven by state files; rollback.sh runs in a throwaway git clone with a dummy
# identity; build.sh runs in a scratch repo and is stopped before Gradle. The real repo (including
# dist/) is only read. Both run the WORKING TREE's scripts: install.sh is
# copied, and the rollback clone gets the whole working tree (tracked and untracked files, not
# ignored ones; deletions included) committed on top of main, so the gate tests what is about to be
# committed, with each test next to the code it tests (also mid-rollback, when tests/ is restored). Everything lives under one mktemp dir, removed on exit.
# Prints one PASS/FAIL line per case, then TOOLS_TEST_OK or TOOLS_TEST_FAIL <n> (exit 1).
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BASE_TMP=${TMPDIR:-$HOME/scratch}
MADE_BASE=0
[ -d "$BASE_TMP" ] || { mkdir -p "$BASE_TMP" && MADE_BASE=1; }
T=$(mktemp -d "$BASE_TMP/cf-tools-test.XXXXXX") || { echo "TOOLS_TEST_FAIL 1 (no temp dir)"; exit 1; }
NG=   # the recursion guard this run makes (tools/rollback.sh below), removed on exit
cleanup() { rm -rf "$T"; [ -z "$NG" ] || rm -f "$NG"; [ $MADE_BASE = 0 ] || rmdir "$BASE_TMP" 2>/dev/null || true; }
trap cleanup EXIT
FAILS=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; FAILS=$((FAILS + 1)); [ ! -f "$T/out" ] || sed 's/^/    | /' "$T/out"; }

# Hermetic git: no user/system config, a dummy identity for rollback.sh's commits.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME="tools test" GIT_AUTHOR_EMAIL="tools-test@invalid" \
       GIT_COMMITTER_NAME="tools test" GIT_COMMITTER_EMAIL="tools-test@invalid"

# ---------------------------------------------------------------- pipelines: no early exit
# Under pipefail, `producer | grep -q x` (or head, grep -m1, grep -l) is a coin toss: the consumer
# exits at its first match, a producer still writing dies of SIGPIPE (141), and the pipeline is
# false although the match was found. tools/rollback.sh's has_verify() did exactly that (`git show
# | grep -q`, ~1 run in 5 refused a good rollback and failed this test). Banned in EVERY *.sh, not
# just files that say pipefail: tools/stick-lib.sh has no `set` line but is sourced into
# stick-test.sh's pipefail shell. Use first() (sed -n 1p, reads everything), sed -n 1,Np, or a
# command that needs no pipe (git grep -q, grep -q on a file or here-string). Comment lines are
# ignored. Not caught: sed q / awk exit as a consumer (none in the repo; do not add one).
EARLY='(^|[^|])\|[[:space:]]*(head([^A-Za-z0-9_-]|$)|grep[[:space:]]([^|]*[[:space:]])?-(-quiet|-silent|-max-count|-files-with|[A-Za-z]*[qml]))'
early_ok=1
P='|'   # the bad examples are spelled with $P so this file does not trip its own check
for bad in "git show \"\$1:\$PATCH\" 2>/dev/null $P grep -q '^def verify('" "x $P head -1" "x $P grep -m1 y" \
           "x $P grep -E -q y" "x ${P}head" "  $P grep --quiet y" "\$(x $P head)"; do
  grep -qE "$EARLY" <<<"$bad" || { fail pipe-check-self-test "the pattern misses: $bad"; early_ok=0; }
done
for good in "x | first" "x || head -1 f" "x | grep -oE 'a|b' | first" "x | sed -n 1,3p" "head -3 f | tr a b"; do
  ! grep -qE "$EARLY" <<<"$good" || { fail pipe-check-self-test "the pattern flags: $good"; early_ok=0; }
done
[ $early_ok = 0 ] || pass pipe-check-self-test
nsh=0; offenders=
while IFS= read -r -d '' f; do
  [ -f "$ROOT/$f" ] || continue
  nsh=$((nsh + 1))
  hits=$(grep -nE "$EARLY" "$ROOT/$f" | grep -vE '^[0-9]+:[[:space:]]*#' || true)
  [ -z "$hits" ] || offenders+=$(sed "s|^|    $f:|" <<<"$hits")$'\n'
done < <(git -C "$ROOT" ls-files -z -co --exclude-standard -- '*.sh')
if [ $nsh -lt 5 ]; then fail no-early-exit-pipelines "scanned only $nsh *.sh files (git ls-files failed?)"
elif [ -n "$offenders" ]; then fail no-early-exit-pipelines "early-exit consumer in a pipeline (SIGPIPE under pipefail):"$'\n'"$offenders"
else pass "no-early-exit-pipelines ($nsh scripts)"; fi

# ---------------------------------------------------------------- Settings focus helpers read the page's focus
# "$FOC" takes the DOM's first .is-focus, which with Settings open was Home's still-marked Settings
# button (stick, 8af9984 staging run, 2026-11-03: the Bluetooth row was never reached). A helper that
# walks Settings rows must read ShoreQA.where().focusAction, which looks only in the top layer.
bad=$(grep -nE '^(hx|bt)_focus\(\).*"\$FOC"' "$ROOT/tools/stick-test.sh" || true)
good=$(grep -cE '^(hx|bt)_focus\(\).*ShoreQA\.where\(\)\.focusAction' "$ROOT/tools/stick-test.sh" || true)
if [ -n "$bad" ]; then fail settings-focus-helpers-read-the-page "a Settings focus helper reads the DOM's first .is-focus:"$'\n'"$bad"
elif [ "$good" != 2 ]; then fail settings-focus-helpers-read-the-page "want hx_focus and bt_focus on ShoreQA.where().focusAction, found $good"
else pass settings-focus-helpers-read-the-page; fi

# ---------------------------------------------------------------- adb helpers: one copy
# adbt, a, first, homepkg, top, stickid and ui live only in tools/adb-lib.sh. They used to be copied between
# install.sh and tools/stick-test.sh, so a fix to one copy could miss the other (ui()'s empty-dump
# retry reached stick-lib.sh but not stick-test.sh's own Back-hold dump). Every other *.sh
# (tracked or untracked) must not define one of these names nor run `uiautomator dump` itself, the
# lib must define all six, and both callers must source it. Comment lines are ignored. Not caught:
# a definition after another command on the same line (`x; first() {`); do not write one.
HELPERS='(adbt|a|first|homepkg|top|stickid|ui)'
# A uiautomator dump COMMAND (followed by a path, an option, a separator or the end of a quoted
# string), not prose like "a uiautomator dump without".
DUMP="uiautomator[[:space:]]+dump([[:space:]]+[-/]|[[:space:]]*(&&|;|\\|)|[[:space:]]*'?\$)"
# shellcheck disable=SC1087  # "$HELPERS[[:space:]]" is a string followed by a regex bracket expression, not an array index
DEF="^[[:space:]]*(function[[:space:]]+)?$HELPERS[[:space:]]*\(\)|^[[:space:]]*function[[:space:]]+$HELPERS([[:space:]]|\{|$)"
def_ok=1
for bad in "adbt() {" "stickid() {" "  first() { sed -n 1p; }" "a(){ adbt 1 \"\$@\"; }" "function top {" "function homepkg() {" \
           "top ()  { :; }" "	a() { :; }" "ui() {" "function ui {"; do
  grep -qE "$DEF" <<<"$bad" || { fail helper-def-check-self-test "the pattern misses: $bad"; def_ok=0; }
done
for good in "a_b() {" "topx() {" "first_line() { :; }" "stop() {" "  x=\$(first)" "  a shell true" "data() { :; }" \
            "homepkg_x() {" "function adbtx {" "uitext() {" "expect_ui() {"; do
  ! grep -qE "$DEF" <<<"$good" || { fail helper-def-check-self-test "the pattern flags: $good"; def_ok=0; }
done
U=uiautomator   # split, so these samples are not themselves flagged in this file
for bad in "x=\$(a shell '$U dump /sdcard/x.xml && cat /sdcard/x.xml')" "a shell $U dump" \
           "a shell '$U  dump --compressed /sdcard/x'" "a shell '$U dump; cat /sdcard/window_dump.xml'"; do
  grep -qE "$DUMP" <<<"$bad" || { fail helper-def-check-self-test "the dump pattern misses: $bad"; def_ok=0; }
done
for good in "fail x \"a uiautomator dump without '\$2'\"" "fail x y \"'Back to CleanFrame' in uiautomator dump\"; fi"; do
  ! grep -qE "$DUMP" <<<"$good" || { fail helper-def-check-self-test "the dump pattern flags: $good"; def_ok=0; }
done
[ $def_ok = 0 ] || pass helper-def-check-self-test
LIB=tools/adb-lib.sh; nsh=0; offenders=
while IFS= read -r -d '' f; do
  [ -f "$ROOT/$f" ] && [ "$f" != "$LIB" ] || continue
  nsh=$((nsh + 1))
  hits=$(grep -nE "$DEF|$DUMP" "$ROOT/$f" | grep -vE '^[0-9]+:[[:space:]]*#' || true)
  [ -z "$hits" ] || offenders+=$(sed "s|^|    $f:|" <<<"$hits")$'\n'
done < <(git -C "$ROOT" ls-files -z -co --exclude-standard -- '*.sh')
for n in adbt a first homepkg top stickid ui; do
  grep -qE "^$n\(\) \{" "$ROOT/$LIB" 2>/dev/null || offenders+="    $LIB: does not define $n()"$'\n'
done
for f in install.sh tools/stick-test.sh; do
  { grep -qxF 'ADB_LIB=$PWD/tools/adb-lib.sh' "$ROOT/$f" && grep -qxF '. "$ADB_LIB"' "$ROOT/$f"; } \
    || offenders+="    $f: does not source $LIB (ADB_LIB=\$PWD/tools/adb-lib.sh, then . \"\$ADB_LIB\")"$'\n'
done
if [ $nsh -lt 5 ]; then fail adb-helpers-one-copy "scanned only $nsh *.sh files (git ls-files failed?)"
elif [ -n "$offenders" ]; then fail adb-helpers-one-copy "adb helpers outside $LIB, or the lib not sourced:"$'\n'"$offenders"
else pass "adb-helpers-one-copy ($nsh scripts)"; fi

# ---------------------------------------------------------------- no dead BASH_REMATCH captures
# A variable set from ${BASH_REMATCH[n]} must be read again in the same script (shellcheck's SC2034,
# and shellcheck is not assumed installed): tools/stick-test.sh's AE_ID was captured and never read.
# Every *.sh in the repo (tracked or not), comment lines ignored. Not caught: a capture read only
# through eval or ${!name}; do not write one.
R=BASH_REMATCH   # split, so the samples below are not themselves captures in this file
capture_dead() {  # capture_dead <file>: each name set from BASH_REMATCH and never read, one per line
  local v body
  body=$(grep -vE '^[[:space:]]*#' "$1")
  for v in $(grep -oE '(^|[^A-Za-z0-9_])[A-Za-z_][A-Za-z0-9_]*=\$\{'"$R"'\[[0-9]+\]\}' <<<"$body" \
             | sed -E 's/^[^A-Za-z_]?([A-Za-z_][A-Za-z0-9_]*)=.*/\1/' | sort -u); do
    grep -qE '\$\{?'"$v"'([^A-Za-z0-9_]|$)' <<<"$body" || echo "$v"
  done
}
printf '%s\n' 'if [[ $x =~ (a)(b)(c) ]]; then' "  DEADV=\${$R[1]}; LIVEV=\${$R[2]};BRACED=\${$R[3]}" \
  '  echo "$LIVEV ${BRACED:-x}"' "  # COMMENTED=\${$R[1]}" '  echo "$DEADVX"' 'fi' > "$T/capture-sample.sh"
got=$(capture_dead "$T/capture-sample.sh")
if [ "$got" = DEADV ]; then pass dead-capture-check-self-test
else fail dead-capture-check-self-test "want exactly DEADV flagged, got '${got//$'\n'/ }'"; fi
nsh=0; offenders=
while IFS= read -r -d '' f; do
  [ -f "$ROOT/$f" ] || continue
  nsh=$((nsh + 1))
  for v in $(capture_dead "$ROOT/$f"); do offenders+="    $f: $v is set from BASH_REMATCH but never read"$'\n'; done
done < <(git -C "$ROOT" ls-files -z -co --exclude-standard -- '*.sh')
if [ $nsh -lt 5 ]; then fail no-dead-captures "scanned only $nsh *.sh files (git ls-files failed?)"
elif [ -n "$offenders" ]; then fail no-dead-captures "drop the capture or use it:"$'\n'"$offenders"
else pass "no-dead-captures ($nsh scripts)"; fi

# ---------------------------------------------------------------- shellcheck's parse traps
# Three of shellcheck's rules, checked here by pattern (it is not assumed installed):
#   - a comment that starts "# shellcheck" IS a directive to it: anything after it but key=value pairs
#     (disable=, enable=, source=, source-path=, shell=, external-sources=) is SC1073/SC1072, a parse
#     error that turns shellcheck off for the whole file (tests/tools.test.sh had one: "shellcheck is
#     not installed ..." at the start of a comment line);
#   - "NAME= value" (SC1007): an empty assignment followed by a word, which bash runs as a command with
#     NAME set to "" (tests/fallback.test.sh and upload.test.sh had "JAVAC= JAVA="); write NAME='';
#   - the keyword done as a word in a test (SC1010: [ "$GOT" = done ], tools/stick-test.sh had one):
#     it reads as the end of a loop to shellcheck; quote it, 'done'.
# Every *.sh except the APP_TESTS that tools/rollback.sh restores from an older commit (they are
# checked by tests/android.test.py, which is rolled back with them): an old one must not refuse a rollback.
BADSC='^[[:space:]]*#[[:space:]]*shellcheck([[:space:]]|$)'
GOODSC='#[[:space:]]*shellcheck([[:space:]]+(disable|enable|source|source-path|shell|external-sources)=[^[:space:]]+)+([[:space:]]+#.*)?$'
EMPTYASSIGN='(^|;)[[:space:]]*((local|export|declare)[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*=[[:space:]]+[^[:space:]#;&|)]'
DONEWORD='(\[\[?|!?==?)[[:space:]]+done([[:space:]]|$)'
sh_lint() {  # sh_lint <file>: one "line: text" per problem
  { grep -nE "$BADSC" "$1" | grep -vE "$GOODSC"; grep -nE "$EMPTYASSIGN|$DONEWORD" "$1" | grep -vE '^[0-9]+:[[:space:]]*#'; } || true
}
lint_ok=1
E='= '   # split, so the samples below are not themselves flagged in this file
D=done   # likewise: the SC1010 samples are built, not written out
for bad in "# shellcheck is not installed here" "  # shellcheck disable=SC1087 and more words" "JAVAC${E}JAVA=" "x=1; y${E}z" "  local a${E}b" \
           "[ \"\$GOT\" = $D ] && break" "[[ \$x != $D ]]" "[ $D = \"\$x\" ]"; do
  printf '%s\n' "$bad" > "$T/lint-sample.sh"
  [ -n "$(sh_lint "$T/lint-sample.sh")" ] || { fail shell-lint-self-test "the pattern misses: $bad"; lint_ok=0; }
done
for good in "JAVAC=''; JAVA=''" "# shellcheck disable=SC1087  # a reason" "# shellcheck source=tools/adb-lib.sh" "# shellcheck shell=bash" \
            "x=1 y=2 cmd" "# the shellcheck rule SC2034" "  # NAME= value in a comment" 'a="b= c"' \
            "[ \"\$GOT\" = '$D' ] && break" "for x in a; do :; $D" "while :; do break; $D < f"; do
  printf '%s\n' "$good" > "$T/lint-sample.sh"
  [ -z "$(sh_lint "$T/lint-sample.sh")" ] || { fail shell-lint-self-test "the pattern flags: $good"; lint_ok=0; }
done
[ $lint_ok = 0 ] || pass shell-lint-self-test
APPLIST=$(grep -E '^APP_TESTS=\(' "$ROOT/tools/rollback.sh" || true)
nsh=0; offenders=
while IFS= read -r -d '' f; do
  [ -f "$ROOT/$f" ] || continue
  [[ " ${APPLIST//[()]/ } " != *" $f "* ]] || continue
  nsh=$((nsh + 1))
  hits=$(sh_lint "$ROOT/$f")
  [ -z "$hits" ] || offenders+=$(sed "s|^|    $f:|" <<<"$hits")$'\n'
done < <(git -C "$ROOT" ls-files -z -co --exclude-standard -- '*.sh')
if [ -z "$APPLIST" ] || [ $nsh -lt 5 ]; then fail shell-lint "no APP_TESTS line in tools/rollback.sh, or scanned only $nsh *.sh files"
elif [ -n "$offenders" ]; then fail shell-lint "a malformed shellcheck directive, NAME= value or unquoted done in a test:"$'\n'"$offenders"
else pass "shell-lint ($nsh scripts)"; fi

# ---------------------------------------------------------------- tests/ classified for rollback
# tools/rollback.sh restores the APP_TESTS with android/ + web/ and keeps the HEAD_TESTS (they test
# the tools a rollback does not restore). Every top-level entry of tests/ in the working tree
# (tracked or not: what is about to be committed) must be in exactly one list, so a new test cannot
# be committed unclassified (rollback.sh would refuse), and every HEAD test must exist, so a rename
# cannot leave a kept test behind. (An APP test may be missing: a rolled-back tree lacks those newer
# than its SHA. A tree rolled back to an SHA with a test since removed fails here until it is
# classified, which refuses that rollback: fail-closed.) The lists are read from rollback.sh itself.
classify_errors() {  # classify_errors <rollback.sh> <tests dir>: one line per problem, none if OK
  local HEAD_TESTS=() APP_TESTS=() lists x y n e
  lists=$(grep -E '^(HEAD|APP)_TESTS=\(' "$1") || { echo "no HEAD_TESTS=(...) / APP_TESTS=(...) lines in $1"; return; }
  eval "$lists"
  [ ${#HEAD_TESTS[@]} -gt 0 ] && [ ${#APP_TESTS[@]} -gt 0 ] || { echo "empty HEAD_TESTS or APP_TESTS in $1"; return; }
  for e in "$2"/*; do
    [ -e "$e" ] || continue
    x=tests/${e##*/}; n=0
    for y in "${HEAD_TESTS[@]}" "${APP_TESTS[@]}"; do [ "$y" != "$x" ] || n=$((n + 1)); done
    [ $n = 1 ] || echo "$x is in $n of HEAD_TESTS/APP_TESTS (want exactly 1)"
  done
  for x in "${HEAD_TESTS[@]}"; do [ -e "$2/${x#tests/}" ] || echo "$x is in HEAD_TESTS but does not exist"; done
}
CL=$T/classify; mkdir -p "$CL/tests"; cp -r "$ROOT/tests/." "$CL/tests/"
self=1
: > "$CL/tests/zz-new.test.sh"
[ -n "$(classify_errors "$ROOT/tools/rollback.sh" "$CL/tests")" ] || { fail tests-classified-self-test "an unlisted tests/zz-new.test.sh was not flagged"; self=0; }
rm -f "$CL/tests/zz-new.test.sh" "$CL/tests/icon_metrics.test.py"
[ -n "$(classify_errors "$ROOT/tools/rollback.sh" "$CL/tests")" ] || { fail tests-classified-self-test "a missing HEAD test (icon_metrics.test.py) was not flagged"; self=0; }
rm -rf "$CL/tests/java" "$CL/tests/gradle.test.py"; cp "$ROOT/tests/icon_metrics.test.py" "$CL/tests/"
[ -z "$(classify_errors "$ROOT/tools/rollback.sh" "$CL/tests")" ] || { fail tests-classified-self-test "missing APP tests (a rolled-back tree) were flagged"; self=0; }
[ $self = 0 ] || pass tests-classified-self-test
errs=$(classify_errors "$ROOT/tools/rollback.sh" "$ROOT/tests")
if [ -z "$errs" ]; then pass tests-classified-for-rollback
else fail tests-classified-for-rollback "classify each in tools/rollback.sh (HEAD_TESTS: tests HEAD's tools; APP_TESTS: tests android/, web/ or the patch tool):"$'\n'"$errs"; fi
# Key material is never committed by accident: .gitignore covers every keystore/key format the
# signing setup could produce (the key itself lives in ~/.config/cleanframe-tv/, docs/RULES.md rule 5).
ign_bad=; for f in release.jks x.keystore x.p12 x.pem signing.properties; do
  git -C "$ROOT" check-ignore -q --no-index "android/$f" || ign_bad+=" $f"; done
if [ -z "$ign_bad" ]; then pass gitignore-key-material; else fail gitignore-key-material "not ignored:$ign_bad"; fi

# ---------------------------------------------------------------- fakes
# The serial is not an address anything can reach, and the fake adb refuses any other one.
SERIAL=fake-stick.invalid:5555
FAKE=$T/fake; BIN=$T/bin; TC=$T/toolchain
mkdir -p "$FAKE" "$BIN" "$TC/sdk/build-tools/34.0.0"
cat > "$BIN/adb" <<'EOF'
#!/bin/bash
# Fake adb. State: $FAKE_STATE/{reachable,home,resumed,home_after_install,set_home_works,
# vn_<pkg>,vc_<pkg>,model,serialno}; aapt_* describe the APK being installed. Every call is logged.
# set_home_late: the first set-home-activity has no effect; set_home_dies: that call fails (a dropped
# connection); keys_dead: a key press changes nothing; install_keeps_old: install says Success, nothing changes.
S=$FAKE_STATE; echo "$*" >> "$S/adb.log"
st() { cat "$S/$1" 2>/dev/null; }
if [ "$1" = connect ]; then
  [ "$2" = "$FAKE_SERIAL" ] || { echo "fake adb: unexpected serial $2" >&2; exit 97; }
  [ "$(st reachable)" = 1 ] && echo "connected to $2" || echo "failed to connect to $2"
  exit 0
fi
[ "$1" = -s ] && [ "$2" = "$FAKE_SERIAL" ] || { echo "fake adb: unexpected args $*" >&2; exit 97; }
shift 2
[ "$(st reachable)" = 1 ] || { echo "error: device '$FAKE_SERIAL' not found" >&2; exit 1; }
case "$1 ${2:-} ${3:-}" in
  "get-state "*) echo device ;;
  "shell dumpsys package")
    p=$4; [ -f "$S/vc_$p" ] || exit 0
    printf '  Package [%s]\n    versionCode=%s minSdk=21 targetSdk=34\n    versionName=%s\n' "$p" "$(st "vc_$p")" "$(st "vn_$p")" ;;
  "install -r "*)
    [ ! -f "$S/hang_install" ] || exec "$REAL_SLEEP" 30   # a half-dead connection: no answer
    echo "$3" >> "$S/installed"; p=$(st aapt_pkg)
    if [ ! -f "$S/install_keeps_old" ]; then st aapt_vc > "$S/vc_$p"; st aapt_vn > "$S/vn_$p"; fi
    [ ! -f "$S/home_after_install" ] || cp "$S/home_after_install" "$S/home"
    echo Success ;;
  "shell pm set-home-activity")
    [ ! -f "$S/set_home_dies" ] || { rm -f "$S/set_home_dies"; echo "error: closed" >&2; exit 1; }
    if [ -f "$S/set_home_late" ]; then rm -f "$S/set_home_late"; else [ "$(st set_home_works)" = 1 ] && echo "${4%%/*}" > "$S/home"; fi; echo "Success" ;;
  "shell input keyevent")   # Home key: the Home app comes to the front, the chooser does not
    h=$(st home); [ -f "$S/keys_dead" ] || [ "$4" != KEYCODE_HOME ] || [ "$h" = android ] || echo "$h/.MainActivity" > "$S/resumed" ;;
  "shell cmd package")
    printf 'priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\nActivityInfo:\n  name=x\n  packageName=%s\n' "$(st home)" ;;
  "shell dumpsys activity") echo "  mResumedActivity: ActivityRecord{1 u0 $(st resumed) t1}" ;;
  "shell getprop ro.product.model") st model ;;   # the stick's identity (stickid, tools/adb-lib.sh)
  "shell getprop ro.serialno") st serialno ;;
  *) echo "fake adb: unhandled: $*" >&2; exit 98 ;;
esac
EOF
cat > "$TC/sdk/build-tools/34.0.0/aapt2" <<'EOF'
#!/bin/bash
# Fake aapt2: "dump badging <apk>" from $FAKE_STATE/aapt_{pkg,vn,vc}.
[ "$1 $2" = "dump badging" ] && [ -f "$3" ] || { echo "fake aapt2: bad call $*" >&2; exit 1; }
S=$FAKE_STATE
echo "package: name='$(cat "$S/aapt_pkg")' versionCode='$(cat "$S/aapt_vc")' versionName='$(cat "$S/aapt_vn")' platformBuildVersionName='14'"
echo "sdkVersion:'21'"
EOF
cat > "$TC/sdk/build-tools/34.0.0/apksigner" <<'EOF'
#!/bin/bash
# Fake apksigner: "verify --print-certs <apk>" names the signer in $FAKE_STATE/signer; it fails
# (as for a bad signature) when $FAKE_STATE/apksigner_fails exists.
[ "$1 $2" = "verify --print-certs" ] && [ -f "$3" ] || { echo "fake apksigner: bad call $*" >&2; exit 1; }
[ ! -f "$FAKE_STATE/apksigner_fails" ] || { echo "DOES NOT VERIFY"; echo "ERROR: JAR signer CERT.RSA: JAR signature META-INF/CERT.RSA indicates the file is corrupted"; exit 1; }
echo "Signer #1 certificate DN: CN=cleanframe-tv, O=example"
echo "Signer #1 certificate SHA-256 digest: $(cat "$FAKE_STATE/signer")"
echo "Signer #1 certificate SHA-1 digest: 4d80f326f11b98dcee081d5adc4f6caf9db3d1ec"
EOF
printf '#!/bin/sh\nexit 0\n' > "$BIN/sleep"   # install.sh's settle delay after the Home key
chmod +x "$BIN/adb" "$BIN/sleep" "$TC/sdk/build-tools/34.0.0/aapt2" "$TC/sdk/build-tools/34.0.0/apksigner"
REAL_SLEEP=$(command -v sleep)   # the real one; $BIN/sleep (on the runs' PATH) is a no-op
export FAKE_STATE=$FAKE FAKE_SERIAL=$SERIAL REAL_SLEEP
TPATH=$BIN:$PATH
if [ "$(PATH=$TPATH command -v adb)" != "$BIN/adb" ]; then
  echo "FAIL setup: the fake adb does not shadow adb on PATH; not running anything"; echo "TOOLS_TEST_FAIL 1"; exit 1
fi

# ---------------------------------------------------------------- install.sh
NOGIT=22e48948c463; OTHER=adbce2ecc663
INST=$T/inst
# install.sh runs in a small git repo, so its recovery gate can diff <the stick's Home>..<the APK's SHA>:
#   C_A  android/, web/main.js, README     C_B  README only            C_C  android/ changed
#   C_D  web/device.js only                C_E  web/main.js only       SHA  README again (main's tip)
# origin/main (a fake remote-tracking ref) is main's tip: every one of them is pushed, as a release must
# be (install.sh refuses a release commit that is not on origin/main). $NOGIT and $OTHER are not commits
# there ($OTHER as Home: an unknown base, so the recovery pass is required).
TPL=$T/insttpl
tpl_commit() { mkdir -p "$TPL/$(dirname "$1")" && echo "$2" > "$TPL/$1" && git -C "$TPL" add -A && git -C "$TPL" commit -q -m "$1=$2" && git -C "$TPL" rev-parse --short=12 HEAD; }
git init -q -b main "$TPL"
mkdir -p "$TPL/android/app" "$TPL/web"; echo 1 > "$TPL/android/app/Main.java"; echo 1 > "$TPL/web/main.js"
C_A=$(tpl_commit README 1); C_B=$(tpl_commit README 2); C_C=$(tpl_commit android/app/Main.java 2)
C_D=$(tpl_commit web/device.js 1); C_E=$(tpl_commit web/main.js 2); SHA=$(tpl_commit README 3)
git -C "$TPL" update-ref refs/remotes/origin/main main
[[ "$C_A$C_B$C_C$C_D$C_E$SHA" =~ ^[0-9a-f]{72}$ ]] || fail install-setup "could not build the recovery-gate repo ($C_A $C_B $C_C $C_D $C_E $SHA)"
PIN=$(tr -d '[:space:]' < "$ROOT/tools/signer-cert.sha256" 2>/dev/null)
[[ $PIN =~ ^[0-9a-f]{64}$ ]] || fail install-setup "tools/signer-cert.sha256 is not one SHA-256 ('$PIN')"
TESTED=aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111   # the tested staging APK's sha256
# Fresh copy of install.sh (+ tools/adb-lib.sh, the signer pin) in that repo, an empty dist/, and a
# stick whose Home is the live CleanFrame at $OTHER; APKs signed by the pinned key.
fresh() {
  rm -rf "${INST:?}" "${FAKE:?}"/*; cp -a "$TPL" "$INST"; mkdir -p "$INST/dist" "$INST/tools"; cp "$ROOT/install.sh" "$INST/install.sh"
  cp "$ROOT/tools/adb-lib.sh" "$ROOT/tools/signer-cert.sha256" "$INST/tools/"; echo "$PIN" > "$FAKE/signer"
  echo 1 > "$FAKE/reachable"; echo home.cleanframe.tv > "$FAKE/home"
  echo home.cleanframe.tv/.MainActivity > "$FAKE/resumed"
  echo 1 > "$FAKE/set_home_works"; : > "$FAKE/adb.log"
  echo 10 > "$FAKE/vc_home.cleanframe.tv"; echo "0.1+$OTHER" > "$FAKE/vn_home.cleanframe.tv"
  stick_is X
}
# Two sticks at the same fake address (an address can move to another stick): X and Y differ only in
# what they report (model + serial, read by stickid). SIDX/SIDY are their identities as stickid prints them.
SIDX=Fake_TV_4K-XS0001; SIDY=FakeBox-YS0002
stick_is() { if [ "$1" = X ]; then echo "Fake TV 4K" > "$FAKE/model"; echo XS0001 > "$FAKE/serialno"
             else echo FakeBox > "$FAKE/model"; echo YS0002 > "$FAKE/serialno"; fi; }
apk() {  # apk <file name> <package> <versionName> <versionCode>; its sha256 recorded as build.sh does
  echo "fake apk $1 $RANDOM" > "$INST/dist/$1"; ( cd "$INST/dist" && sha256sum "$1" > "$1.sha256" )
  echo "$2" > "$FAKE/aapt_pkg"; echo "$3" > "$FAKE/aapt_vn"; echo "$4" > "$FAKE/aapt_vc"
}
# marker <sha> [staging-only|both] [<stick id>]: the staging pass's marker and (unless staging-only) the
# recovery pass's, both for the same tested APK on stick X (or the stick given), as tools/stick-test.sh
# writes them. mkf <sha> <kind> [<stick id>]: that marker's file.
mkf() { echo "$INST/dist/cleanframe-tv-$1.${3:-$SIDX}.$2-ok"; }
marker() {
  local sid=${3:-$SIDX}
  printf 'sha=%s\napk_sha256=%s\nstick_id=%s\nmode=normal\n' "$1" "$TESTED" "$sid" > "$(mkf "$1" staging "$sid")"
  [ "${2:-}" = staging-only ] || printf 'sha=%s\napk_sha256=%s\nstick_id=%s\nmode=recovery\n' "$1" "$TESTED" "$sid" > "$(mkf "$1" recovery "$sid")"
}
home_at() { echo "0.1.0+$1" > "$FAKE/vn_home.cleanframe.tv"; }   # the SHA installed as Home
RC=0
run_install() {   # ADB_INSTALL_TIMEOUT, if set on the call, reaches install.sh
  ( cd "$T" && PATH=$TPATH ANDROID_TOOLCHAIN=$TC STICK=$SERIAL bash "$INST/install.sh" "$@" ) > "$T/out" 2>&1
  RC=$?
}
has() { grep -qF -- "$1" "$T/out"; }
has_ok() { grep -qx INSTALL_OK "$T/out"; }   # the line itself, not "no INSTALL_OK" in INSTALL_FAIL
installed() { [ -s "$FAKE/installed" ]; }
# check <name> <want rc: 0|nz> <must-contain> [extra test command...]
check() {
  local name=$1 want=$2 text=$3; shift 3
  if [ "$want" = 0 ] && [ $RC != 0 ]; then fail "$name" "exit $RC, want 0"
  elif [ "$want" = nz ] && [ $RC = 0 ]; then fail "$name" "exit 0, want non-zero"
  elif ! has "$text"; then fail "$name" "output lacks '$text'"
  elif [ $# -gt 0 ] && ! "$@"; then fail "$name" "post-condition failed: $*"
  else pass "$name"; fi
}
not() { ! "$@"; }
ok_and_installed() { has_ok && installed; }
no_ok_no_install() { not has_ok && not installed; }

fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 11
run_install "dist/cleanframe-tv-$SHA-staging.apk"
check install-staging-ok 0 "HOME resolves to: home.cleanframe.tv" ok_and_installed
# An APK path relative to the caller's directory, outside the repo (run_install runs from $T, the
# repo is $T/inst): read before install.sh's cd, not as a path inside the repo.
fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 11
run_install "inst/dist/cleanframe-tv-$SHA-staging.apk"
check install-apk-path-relative-to-caller 0 "HOME resolves to: home.cleanframe.tv" ok_and_installed

fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 11
echo android > "$FAKE/home_after_install"
run_install "dist/cleanframe-tv-$SHA-staging.apk"
check install-staging-home-changed-fails nz "HOME CHANGED" \
  eval 'not has_ok && has "fix: adb -s $SERIAL shell pm set-home-activity home.cleanframe.tv/.MainActivity" && not grep -q set-home-activity "$FAKE/adb.log"'

fresh; apk "cleanframe-tv-$SHA-dirty.apk" home.cleanframe.tv "0.1+$SHA-dirty" 11; marker "$SHA-dirty"
run_install "dist/cleanframe-tv-$SHA-dirty.apk" --skip-staging
check install-dirty-release-refused-with-skip-staging nz "is not a clean commit" no_ok_no_install

fresh; apk "cleanframe-tv-$SHA-dirty.apk" home.cleanframe.tv "0.1+$SHA-dirty" 11
run_install "dist/cleanframe-tv-$SHA-dirty.apk" --make-home
check install-dirty-release-refused-make-home nz "is not a clean commit" no_ok_no_install

fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11
run_install "dist/cleanframe-tv-$SHA.apk"
check install-release-without-marker-refused nz "refusing: no staging pass" no_ok_no_install

fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11
printf 'sha=%s\nstick_id=%s\n' "$OTHER" "$SIDX" > "$(mkf "$SHA" staging)"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-release-marker-for-other-sha-refused nz "refusing: no staging pass" no_ok_no_install

fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-release-with-marker-ok 0 "staging pass: sha=$SHA" eval 'ok_and_installed && has "HOME resolves to: home.cleanframe.tv"'

# A release install without --make-home still replaces Home in place: HOME must still resolve to
# the live CleanFrame afterwards (docs/RULES.md rule 6), here it falls to the chooser. install.sh sets it
# back once (pm set-home-activity) and checks again: restored is an install like any other; still
# lost (set-home-activity has no effect) fails, printing the exact command that fixes it.
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
echo android > "$FAKE/home_after_install"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-release-home-lost-restored-ok 0 "HOME LOST after the release install" \
  eval 'ok_and_installed && has "HOME resolves to (after set-home-activity): home.cleanframe.tv" && [ "$(grep -c "pm set-home-activity home.cleanframe.tv/.MainActivity" "$FAKE/adb.log")" = 1 ]'
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
echo android > "$FAKE/home_after_install"; echo 0 > "$FAKE/set_home_works"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-release-home-lost-not-restored-fails nz "HOME CHANGED" \
  eval 'not has_ok && has INSTALL_FAIL && has "HOME resolves to (after set-home-activity): android" && has "fix: adb -s $SERIAL shell pm set-home-activity home.cleanframe.tv/.MainActivity" && [ "$(grep -c "pm set-home-activity" "$FAKE/adb.log")" = 1 ]'

# adb that never answers the APK push: bounded by ADB_INSTALL_TIMEOUT, named, INSTALL_FAIL.
fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 11; : > "$FAKE/hang_install"
t0=$SECONDS; ADB_INSTALL_TIMEOUT=1 run_install "dist/cleanframe-tv-$SHA-staging.apk"; took=$((SECONDS - t0))
check install-adb-timeout-fails nz "ADB TIMEOUT: 'adb -s $SERIAL install -r" eval 'not has_ok && has INSTALL_FAIL && [ $took -lt 20 ]'

# The timeout switch is OFF in install.sh even when ADB_TIMEOUT_LOG is inherited: a timeout fails the
# install with ADB TIMEOUT, writes no log and never prints stick-test's "FAIL  adb timeout".
fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 11; : > "$FAKE/hang_install"
rm -f "$T/inherited-timeouts"
ADB_TIMEOUT_LOG=$T/inherited-timeouts ADB_INSTALL_TIMEOUT=1 run_install "dist/cleanframe-tv-$SHA-staging.apk"
check install-adb-timeout-switch-off nz "ADB TIMEOUT: 'adb -s $SERIAL install -r" \
  eval 'not has_ok && has INSTALL_FAIL && not has "FAIL  adb timeout" && [ ! -e "$T/inherited-timeouts" ]'

# tools/adb-lib.sh missing: install.sh refuses before any adb call, and says which file.
fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 11; rm "$INST/tools/adb-lib.sh"
run_install "dist/cleanframe-tv-$SHA-staging.apk"
check install-missing-adb-lib-refused nz "refusing: $INST/tools/adb-lib.sh is missing" \
  eval 'has INSTALL_FAIL && no_ok_no_install && [ ! -s "$FAKE/adb.log" ]'

fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
echo android > "$FAKE/home"; echo com.android.tv.settings/.MainSettings > "$FAKE/resumed"
run_install "dist/cleanframe-tv-$SHA.apk" --make-home
check install-release-make-home-ok 0 "HOME resolves to: home.cleanframe.tv  resumed: home.cleanframe.tv/.MainActivity" \
  grep -q "pm set-home-activity home.cleanframe.tv/.MainActivity" "$FAKE/adb.log"

fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11
run_install "dist/cleanframe-tv-$SHA.apk" --skip-staging
check install-skip-staging-banner-proceeds 0 "!!!!!!!! --skip-staging" ok_and_installed

fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 40
echo 50 > "$FAKE/vc_home.cleanframe.tv.staging"; echo "0.1+$OTHER" > "$FAKE/vn_home.cleanframe.tv.staging"
run_install "dist/cleanframe-tv-$SHA-staging.apk"
check install-older-versioncode-refused nz "versionCode 40 is older than the stick's 50" no_ok_no_install

fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$OTHER" 11; marker "$SHA"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-name-versionname-mismatch-refused nz "does not match its file name" no_ok_no_install

fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
echo 0 > "$FAKE/set_home_works"; echo android > "$FAKE/home"
echo com.android.tv.settings/.MainSettings > "$FAKE/resumed"
run_install "dist/cleanframe-tv-$SHA.apk" --make-home
check install-make-home-elsewhere-fails nz "HOME NOT SWITCHED" eval 'not has_ok && has INSTALL_FAIL && has "HOME LOST after the release install" && has "fix: adb -s $SERIAL shell pm set-home-activity home.cleanframe.tv/.MainActivity"'
# A --make-home that does not take goes through the same path as a Home lost after any install: set back once
# and checked again. The second try taking is an install like any other (it used to exit at once, Home lost).
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
echo android > "$FAKE/home"; echo com.android.tv.settings/.MainSettings > "$FAKE/resumed"; : > "$FAKE/set_home_late"
run_install "dist/cleanframe-tv-$SHA.apk" --make-home
check install-make-home-set-back-recovers 0 "HOME NOT SWITCHED" eval 'ok_and_installed && has "HOME resolves to (after set-home-activity): home.cleanframe.tv" && has "resumed (Home pressed again): home.cleanframe.tv/.MainActivity"'
# ... a connection that drops on that call too (it used to end the run under set -e, with no fix printed).
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
echo android > "$FAKE/home"; echo com.android.tv.settings/.MainSettings > "$FAKE/resumed"; : > "$FAKE/set_home_dies"
run_install "dist/cleanframe-tv-$SHA.apk" --make-home
check install-make-home-adb-drop-set-back 0 "HOME LOST after the release install" ok_and_installed
# ... and HOME right but never in front: refused, with the fix, and Home not set twice for nothing.
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
echo com.android.tv.settings/.MainSettings > "$FAKE/resumed"; : > "$FAKE/keys_dead"
run_install "dist/cleanframe-tv-$SHA.apk" --make-home
check install-make-home-not-in-front-fails nz "HOME NOT SWITCHED: HOME resolves to home.cleanframe.tv, but" \
  eval 'not has_ok && has INSTALL_FAIL && has "fix: adb -s $SERIAL shell pm set-home-activity home.cleanframe.tv/.MainActivity" && [ "$(grep -c "pm set-home-activity" "$FAKE/adb.log")" = 1 ]'
# S4: the SHA read back off the stick after the install must be the APK's (the install said Success, the stick kept the old one).
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; : > "$FAKE/install_keeps_old"
run_install "dist/cleanframe-tv-$SHA.apk" --make-home
check install-provenance-mismatch-fails nz "PROVENANCE MISMATCH" eval 'not has_ok && has INSTALL_FAIL && has "on stick: home.cleanframe.tv $OTHER  wanted: $SHA" && not grep -q "pm set-home-activity" "$FAKE/adb.log"'

fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 11; echo 0 > "$FAKE/reachable"
run_install "dist/cleanframe-tv-$SHA-staging.apk"
check install-unreachable-stick-fails nz "unreachable over adb" no_ok_no_install

# The staging marker must record the tested APK's sha256 (a marker from before that is no pass).
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
printf 'sha=%s\nstick_id=%s\nresult=STICK_TEST_OK\n' "$SHA" "$SIDX" > "$(mkf "$SHA" staging)"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-marker-without-apk-sha256-refused nz "without the tested APK's sha256" no_ok_no_install

# Signer pin and recorded sha256 (rule 4): checked before adb is touched, for every APK / every release.
fresh; apk "cleanframe-tv-$SHA-staging.apk" home.cleanframe.tv.staging "0.1+$SHA" 11
echo 1111111111111111111111111111111111111111111111111111111111111111 > "$FAKE/signer"
run_install "dist/cleanframe-tv-$SHA-staging.apk"
check install-other-signer-refused nz "not the pinned key ('$PIN')" eval 'no_ok_no_install && [ ! -s "$FAKE/adb.log" ]'
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; : > "$FAKE/apksigner_fails"
run_install "dist/cleanframe-tv-$SHA.apk" --skip-staging
check install-unverified-signature-refused nz "apksigner verify failed" eval 'no_ok_no_install && [ ! -s "$FAKE/adb.log" ]'
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; rm "$INST/tools/signer-cert.sha256"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-no-pin-refused nz "no tools/signer-cert.sha256" no_ok_no_install
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; echo tampered >> "$INST/dist/cleanframe-tv-$SHA.apk"
run_install "dist/cleanframe-tv-$SHA.apk" --skip-staging
check install-release-sha256-mismatch-refused nz "but build.sh recorded" eval 'no_ok_no_install && [ ! -s "$FAKE/adb.log" ]'
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; rm "$INST/dist/cleanframe-tv-$SHA.apk.sha256"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-release-sha256-missing-refused nz "build.sh recorded 'nothing'" no_ok_no_install

# Recovery gate (rule 6): <Home's SHA>..<the APK's SHA> touching android/ or web/device.js needs the
# recovery-ok marker of the same tested APK; other changes do not; an unknown Home SHA does.
fresh; home_at "$C_A"; apk "cleanframe-tv-$C_C.apk" home.cleanframe.tv "0.1+$C_C" 11; marker "$C_C" staging-only
run_install "dist/cleanframe-tv-$C_C.apk" --make-home
check install-recovery-required-missing-refused nz "refusing: no --recovery pass for $C_C" \
  eval 'no_ok_no_install && has "$C_A..$C_C changes 1 file(s) under android/ or web/device.js, e.g. android/app/Main.java"'
fresh; home_at "$C_A"; apk "cleanframe-tv-$C_C.apk" home.cleanframe.tv "0.1+$C_C" 11; marker "$C_C"
run_install "dist/cleanframe-tv-$C_C.apk" --make-home
check install-recovery-present-ok 0 "recovery pass ($C_A..$C_C changes" ok_and_installed
fresh; home_at "$C_C"; apk "cleanframe-tv-$C_D.apk" home.cleanframe.tv "0.1+$C_D" 11; marker "$C_D" staging-only
run_install "dist/cleanframe-tv-$C_D.apk"
check install-recovery-device-js-required nz "e.g. web/device.js" no_ok_no_install
fresh; home_at "$C_A"; apk "cleanframe-tv-$C_B.apk" home.cleanframe.tv "0.1+$C_B" 11; marker "$C_B" staging-only
run_install "dist/cleanframe-tv-$C_B.apk" --make-home
check install-recovery-not-required 0 "recovery pass not required: $C_A..$C_B" ok_and_installed
fresh; home_at "$C_D"; apk "cleanframe-tv-$C_E.apk" home.cleanframe.tv "0.1+$C_E" 11; marker "$C_E" staging-only
run_install "dist/cleanframe-tv-$C_E.apk"
check install-recovery-not-required-other-web 0 "recovery pass not required: $C_D..$C_E" ok_and_installed
fresh; echo "0.1.0+nogit" > "$FAKE/vn_home.cleanframe.tv"; apk "cleanframe-tv-$C_B.apk" home.cleanframe.tv "0.1+$C_B" 11; marker "$C_B" staging-only
run_install "dist/cleanframe-tv-$C_B.apk"
check install-recovery-unknown-base-required nz "the SHA installed as Home is unknown ('nogit'" no_ok_no_install
fresh; rm "$FAKE/vn_home.cleanframe.tv" "$FAKE/vc_home.cleanframe.tv"; apk "cleanframe-tv-$C_B.apk" home.cleanframe.tv "0.1+$C_B" 11; marker "$C_B" staging-only
run_install "dist/cleanframe-tv-$C_B.apk"
check install-recovery-no-home-required nz "the SHA installed as Home is unknown ('none'" no_ok_no_install
fresh; home_at "$C_A"; apk "cleanframe-tv-$C_C.apk" home.cleanframe.tv "0.1+$C_C" 11; marker "$C_C"
sed -i 's/^apk_sha256=.*/apk_sha256=bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222/' "$(mkf "$C_C" recovery)"
run_install "dist/cleanframe-tv-$C_C.apk"
check install-recovery-other-apk-refused nz "refusing: no --recovery pass for $C_C" no_ok_no_install
fresh; home_at "$C_A"; apk "cleanframe-tv-$C_C.apk" home.cleanframe.tv "0.1+$C_C" 11; marker "$C_C" staging-only
printf 'sha=%s\napk_sha256=%s\nstick_id=%s\n' "$C_B" "$TESTED" "$SIDX" > "$(mkf "$C_C" recovery)"
run_install "dist/cleanframe-tv-$C_C.apk"
check install-recovery-marker-other-sha-refused nz "refusing: no --recovery pass for $C_C" no_ok_no_install
fresh; home_at "$C_A"; apk "cleanframe-tv-$C_C.apk" home.cleanframe.tv "0.1+$C_C" 11
run_install "dist/cleanframe-tv-$C_C.apk" --make-home --skip-staging
check install-recovery-skip-staging-bypasses 0 "!!!!!!!! --skip-staging" eval 'ok_and_installed && not has "recovery pass"'

# A release is a commit on origin/main (pushed): one committed after it (unpushed), one on another
# branch, a SHA that is not a commit here, or no origin/main at all is refused before adb is touched,
# also with --skip-staging. A staging APK of an unpushed commit (a feature branch) installs as usual.
inst_commit() { git -C "$INST" commit -q --allow-empty -m "$1" && git -C "$INST" rev-parse --short=12 HEAD; }
pushed_refused() { no_ok_no_install && [ ! -s "$FAKE/adb.log" ] && has "only main's pushed"; }
fresh; U=$(inst_commit unpushed); apk "cleanframe-tv-$U.apk" home.cleanframe.tv "0.1+$U" 11; marker "$U"
run_install "dist/cleanframe-tv-$U.apk" --make-home
check install-release-unpushed-refused nz "refusing: release APK $U is not on origin/main" pushed_refused
run_install "dist/cleanframe-tv-$U.apk" --make-home --skip-staging
check install-release-unpushed-refused-skip-staging nz "refusing: release APK $U is not on origin/main" pushed_refused
fresh; git -C "$INST" checkout -q -b feature; U=$(inst_commit feature); apk "cleanframe-tv-$U.apk" home.cleanframe.tv "0.1+$U" 11; marker "$U"
run_install "dist/cleanframe-tv-$U.apk"
check install-release-other-branch-refused nz "refusing: release APK $U is not on origin/main" pushed_refused
fresh; U=$(inst_commit unpushed); apk "cleanframe-tv-$U-staging.apk" home.cleanframe.tv.staging "0.1+$U" 11
run_install "dist/cleanframe-tv-$U-staging.apk"
check install-staging-unpushed-ok 0 "HOME resolves to: home.cleanframe.tv" ok_and_installed
fresh; apk "cleanframe-tv-$NOGIT.apk" home.cleanframe.tv "0.1+$NOGIT" 11; marker "$NOGIT"
run_install "dist/cleanframe-tv-$NOGIT.apk" --skip-staging
check install-release-not-a-commit-refused nz "refusing: release APK $NOGIT is not a commit in this repo" eval 'no_ok_no_install && [ ! -s "$FAKE/adb.log" ]'
fresh; git -C "$INST" update-ref -d refs/remotes/origin/main; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-release-no-origin-main-refused nz "refusing: release APK $SHA is not on origin/main" pushed_refused

# Markers are per stick (STICK=, two sticks): named and stamped (stick_id=) with the identity read off
# the stick, not its address. A pass on X never unlocks Y; passes on both stand side by side; a marker
# without stick_id=, with another stick's, or in the old per-SHA-only name is refused, and so is a stick
# whose identity cannot be read. The recovery marker is per stick too.
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; stick_is Y
run_install "dist/cleanframe-tv-$SHA.apk"
check install-pass-on-x-refused-on-y nz "refusing: no staging pass for $SHA on this stick ($SIDY" \
  eval 'no_ok_no_install && has "a pass on another stick does not count: dist/cleanframe-tv-$SHA.$SIDX.staging-ok"'
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; marker "$SHA" both "$SIDY"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-pass-on-both-x-allowed 0 "staging pass: sha=$SHA apk_sha256=$TESTED stick_id=$SIDX" ok_and_installed
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; marker "$SHA" both "$SIDY"; stick_is Y
run_install "dist/cleanframe-tv-$SHA.apk"
check install-pass-on-both-y-allowed 0 "staging pass: sha=$SHA apk_sha256=$TESTED stick_id=$SIDY" \
  eval 'ok_and_installed && [ -f "$(mkf "$SHA" staging)" ] && [ -f "$(mkf "$SHA" staging "$SIDY")" ]'
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11
printf 'sha=%s\napk_sha256=%s\nmode=normal\n' "$SHA" "$TESTED" > "$(mkf "$SHA" staging)"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-marker-without-stick-id-refused nz "without stick_id=$SIDX" no_ok_no_install
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; sed -i "s/^stick_id=.*/stick_id=$SIDY/" "$(mkf "$SHA" staging)"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-marker-other-stick-id-refused nz "refusing: no staging pass for $SHA on this stick" no_ok_no_install
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11
printf 'sha=%s\napk_sha256=%s\nstick=%s\nmode=normal\n' "$SHA" "$TESTED" "$SERIAL" > "$INST/dist/cleanframe-tv-$SHA.staging-ok"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-old-unnamed-marker-refused nz "refusing: no staging pass for $SHA on this stick" no_ok_no_install
fresh; apk "cleanframe-tv-$SHA.apk" home.cleanframe.tv "0.1+$SHA" 11; marker "$SHA"; : > "$FAKE/serialno"
run_install "dist/cleanframe-tv-$SHA.apk"
check install-stick-identity-unreadable-refused nz "could not read the stick's identity" no_ok_no_install
fresh; home_at "$C_A"; apk "cleanframe-tv-$C_C.apk" home.cleanframe.tv "0.1+$C_C" 11; marker "$C_C" staging-only; marker "$C_C" both "$SIDY"
run_install "dist/cleanframe-tv-$C_C.apk"
check install-recovery-on-other-stick-refused nz "refusing: no --recovery pass for $C_C on this stick" no_ok_no_install

# Every adb call above went to the fake serial (the fake exits 97 otherwise, failing its case).
if grep -q "192\.168\." "$T"/fake/adb.log 2>/dev/null; then fail no-real-stick "a call named a LAN address"; fi
# There is no default stick: without STICK= install.sh refuses before any adb call.
fresh; ( cd "$T" && env -u STICK PATH=$TPATH ANDROID_TOOLCHAIN=$TC bash "$INST/install.sh" dist/x.apk ) > "$T/out" 2>&1; RC=$?
check install-needs-stick nz "set STICK=" eval '[ $RC = 2 ] && no_ok_no_install'

# ---------------------------------------------------------------- build.sh (provenance guard)
# Stopped before tools/check.py and Gradle by a missing signing.properties: "no signing.properties"
# in the output proves the version check passed; "refusing: ... CLEANFRAME_VERSION" that it fired.
# The scratch repo has the verification metadata (a stub: build.sh refuses a tree without it) and a
# fake origin/main (a remote-tracking ref) at its commit: a release build must be pushed.
B=$T/buildrepo; GH=$T/gradle-home; META_STUB='<?xml version="1.0" encoding="UTF-8"?>\n<verification-metadata/>\n'
mkdir -p "$B/android/gradle" "$GH" && cp "$ROOT/build.sh" "$B/" && printf '%b' "$META_STUB" > "$B/android/gradle/verification-metadata.xml" \
  && git -C "$B" init -q -b main && git -C "$B" add -A && git -C "$B" commit -q -m "build.sh only" \
  && git -C "$B" update-ref refs/remotes/origin/main main
NOVER=(-u CLEANFRAME_VERSION_CODE -u CLEANFRAME_VERSION_NAME -u ORG_GRADLE_PROJECT_CLEANFRAME_VERSION_CODE
       -u ORG_GRADLE_PROJECT_CLEANFRAME_VERSION_NAME -u GRADLE_OPTS -u JAVA_OPTS -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS)
BUILD_ARGS=()   # build.sh's own arguments
run_build() {  # run_build <build.sh dir> [VAR=value...]
  local d=$1; shift
  ( cd "$T" && env "${NOVER[@]}" GRADLE_USER_HOME="$GH" CLEANFRAME_SIGNING="$T/no-signing.properties" \
      ANDROID_TOOLCHAIN="$TC" "$@" bash "$d/build.sh" "${BUILD_ARGS[@]}" ) > "$T/out" 2>&1
  RC=$?
}
run_build "$B"
check build-no-override-passes-guard nz "refusing: no signing.properties" not has CLEANFRAME_VERSION
for v in CLEANFRAME_VERSION_CODE CLEANFRAME_VERSION_NAME ORG_GRADLE_PROJECT_CLEANFRAME_VERSION_CODE; do
  run_build "$B" "$v=42"
  check "build-refuses-$v-in-git" nz "refusing: $v is set in a full git checkout" not has "no signing.properties"
done
run_build "$B" GRADLE_OPTS=-Dorg.gradle.project.CLEANFRAME_VERSION_CODE=42
check build-refuses-gradle-opts-override nz "refusing: GRADLE_OPTS/JAVA_OPTS/JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS set CLEANFRAME_VERSION_" not has "no signing.properties"
run_build "$B" JDK_JAVA_OPTIONS=-Dorg.gradle.project.CLEANFRAME_VERSION_NAME=x
check build-refuses-jdk-java-options-override nz "JDK_JAVA_OPTIONS set CLEANFRAME_VERSION_" not has "no signing.properties"
echo "CLEANFRAME_VERSION_CODE=42" > "$GH/gradle.properties"; run_build "$B"; rm -f "$GH/gradle.properties"
check build-refuses-gradle-properties-override nz "gradle.properties sets CLEANFRAME_VERSION_" not has "no signing.properties"
# ... also in a wrapper distribution's gradle.properties (Gradle's installation directory), or inside a value.
GWD=$GH/wrapper/dists/gradle-8.7-bin/abc123/gradle-8.7; mkdir -p "$GWD"
echo "CLEANFRAME_VERSION_NAME=x" > "$GWD/gradle.properties"; run_build "$B"; rm -rf "$GH/wrapper"
check build-refuses-wrapper-dist-properties-override nz "gradle-8.7/gradle.properties sets CLEANFRAME_VERSION_" not has "no signing.properties"
echo "org.gradle.jvmargs=-Dorg.gradle.project.CLEANFRAME_VERSION_CODE=42" > "$GH/gradle.properties"; run_build "$B"; rm -f "$GH/gradle.properties"
check build-refuses-version-in-jvmargs nz "gradle.properties sets CLEANFRAME_VERSION_" not has "no signing.properties"
printf '# CLEANFRAME_VERSION_CODE=42\n! CLEANFRAME_VERSION_NAME=x\n' > "$GH/gradle.properties"; run_build "$B"; rm -f "$GH/gradle.properties"
check build-version-in-a-comment-allowed nz "refusing: no signing.properties" not has "CLEANFRAME_VERSION_"
# android/local.properties (ignored by git, so the tree still reads clean) may not point Gradle at another SDK
# than the provenance reads; the same SDK by another spelling, or no sdk.dir, is fine.
printf '# x\nsdk.dir=%s/other-sdk\n' "$T" > "$B/android/local.properties"; printf 'android/local.properties\n' >> "$B/.git/info/exclude"; run_build "$B"
check build-refuses-other-sdk-dir nz "refusing: android/local.properties has sdk.dir=$T/other-sdk" not has "no signing.properties"
printf 'sdk.dir=%s/sdk/../sdk/\n' "$TC" > "$B/android/local.properties"; run_build "$B"
check build-same-sdk-dir-allowed nz "refusing: no signing.properties" not has "local.properties"
printf 'ndk.dir=/elsewhere\n' > "$B/android/local.properties"; run_build "$B"
check build-no-sdk-dir-allowed nz "refusing: no signing.properties" not has "local.properties"
# As java.util.Properties reads it. <file text, printf format with %s = this SDK>: each must be refused or allowed.
OTHER_SDK=$T/other-sdk; REL_SDK=$(realpath -m --relative-to="$B/android" "$TC/sdk")
sdk_case() {  # sdk_case <name> <refused|allowed> <file text> [what the refusal says]
  printf '%s' "$3" > "$B/android/local.properties"; run_build "$B"
  if [ "$2" = refused ]; then check "build-sdk-dir-$1-refused" nz "refusing: android/local.properties ${4:-has sdk.dir=$OTHER_SDK}" not has "no signing.properties"
  else check "build-sdk-dir-$1-allowed" nz "refusing: no signing.properties" not has "local.properties"; fi
}
sdk_case whitespace-separator refused "sdk.dir $OTHER_SDK"$'\n'
sdk_case colon-separator refused "sdk.dir:$OTHER_SDK"$'\n'
sdk_case spaced-equals refused "  sdk.dir  =  $OTHER_SDK"$'\n'
sdk_case no-final-newline refused "sdk.dir=$OTHER_SDK"
sdk_case last-wins-whitespace-override refused "sdk.dir=$TC/sdk"$'\n'"sdk.dir $OTHER_SDK"$'\n'
sdk_case last-wins-back-to-this-sdk allowed "sdk.dir $OTHER_SDK"$'\n'"sdk.dir=$TC/sdk"$'\n'
sdk_case escaped-key refused "sdk\\.dir=$OTHER_SDK"$'\n'
sdk_case unicode-key refused "sdk\\u002edir=$OTHER_SDK"$'\n'
sdk_case continuation-line refused "sdk.dir=\\"$'\n'"    $OTHER_SDK"$'\n'
sdk_case crlf refused "sdk.dir=$OTHER_SDK"$'\r\n'
sdk_case commented-out allowed "# sdk.dir=$OTHER_SDK"$'\n'"! sdk.dir $OTHER_SDK"$'\n'
sdk_case other-key allowed "sdk.dirx $OTHER_SDK"$'\n'
sdk_case comment-does-not-continue allowed "# x\\"$'\n'"ndk.dir=y"$'\n'
sdk_case relative-same allowed "sdk.dir=$REL_SDK"$'\n'
sdk_case relative-other refused "sdk.dir=../elsewhere"$'\n' "has sdk.dir=../elsewhere"
sdk_case escaped-same allowed "sdk.dir=${TC//\//\\\/}/sdk"$'\n'
sdk_case empty refused "sdk.dir="$'\n' "has an empty sdk.dir"
sdk_case bad-unicode-escape refused "sdk.dir=/a\\u00zz"$'\n' "has a bad"
sdk_case non-ascii refused "sdk.dir=/caf"$'\xc3\xa9'$'\n' "cannot be read with confidence"
rm -f "$B/android/local.properties"; ln -s "$T/no-such-file" "$B/android/local.properties"; run_build "$B"; rm -f "$B/android/local.properties"
check build-sdk-dir-unreadable-file-refused nz "refusing: android/local.properties cannot be read with confidence" not has "no signing.properties"
# Only a full clone builds (versionCode = commit count; check.py's tests read old commits): a shallow
# clone is refused before anything runs, with or without CLEANFRAME_VERSION_CODE, and so is a tree
# outside git (a tarball or git archive).
if git clone -q --depth 1 "file://$B" "$T/shallow" 2>/dev/null; then
  run_build "$T/shallow"
  check build-shallow-clone-refused nz "is not a full git clone" not has "no signing.properties"
  run_build "$T/shallow" CLEANFRAME_VERSION_CODE=42
  check build-shallow-clone-override-still-refused nz "is not a full git clone" not has "no signing.properties"
else fail build-shallow-clone-refused "could not make a shallow clone"; fi
mkdir -p "$T/nogit/android/gradle" && cp "$ROOT/build.sh" "$T/nogit/" && printf '%b' "$META_STUB" > "$T/nogit/android/gradle/verification-metadata.xml"
run_build "$T/nogit" GIT_CEILING_DIRECTORIES="$T"
check build-outside-git-refused nz "is not a full git clone" not has "no signing.properties"
# Dirty tree (rule 4): refused before tools/check.py and Gradle; --dirty lets it through (to the
# signing check, where this scratch repo stops). No toolchain needed for either.
echo stray > "$B/stray.txt"; run_build "$B"
check build-dirty-tree-refused nz "refusing: working tree is dirty" not has "no signing.properties"
BUILD_ARGS=(--dirty); run_build "$B"; BUILD_ARGS=(); rm -f "$B/stray.txt"
check build-dirty-flag-allows-dirty-tree nz "refusing: no signing.properties" not has "working tree is dirty"
# A release build (clean tree, not --staging) is of a commit on origin/main only: an unpushed commit, one on
# another branch, or a repo without origin/main is refused before the gate and Gradle, also with --dirty on
# a clean tree (its APK would have a clean name); --staging builds any commit (a feature branch).
git -C "$B" commit -q --allow-empty -m unpushed; run_build "$B"
check build-release-unpushed-refused nz "is not on origin/main" not has "no signing.properties"
BUILD_ARGS=(--dirty); run_build "$B"; BUILD_ARGS=()
check build-release-unpushed-dirty-flag-clean-tree-refused nz "is not on origin/main" not has "no signing.properties"
BUILD_ARGS=(--staging); run_build "$B"; BUILD_ARGS=()
check build-staging-unpushed-allowed nz "refusing: no signing.properties" not has "origin/main"
git -C "$B" checkout -q -b feature main~0; git -C "$B" commit -q --allow-empty -m feature; run_build "$B"
check build-release-other-branch-refused nz "is not on origin/main" not has "no signing.properties"
git -C "$B" checkout -q main; git -C "$B" reset -q --hard refs/remotes/origin/main; git -C "$B" branch -q -D feature
git -C "$B" update-ref -d refs/remotes/origin/main; run_build "$B"; git -C "$B" update-ref refs/remotes/origin/main main
check build-release-no-origin-main-refused nz "refusing: no origin/main here" not has "no signing.properties"
run_build "$B"
check build-release-pushed-passes nz "refusing: no signing.properties" not has "origin/main"
# Provenance (rule 4, README "Build"): a build that gets through writes dist/<apk>.provenance beside
# dist/<apk>.sha256: the commit, versionName/versionCode read off the APK (aapt2), its sha256, the
# JDK (java -version), build-tools and Gradle (the wrapper's pinned distribution). Here Gradle, java
# and the SDK tools are fakes (the toolchain fakes above, a java that prints a version, a gradlew that
# writes the APK) and tools/check.py is a stub (this run is the real gate). A JDK that cannot say its
# version refuses the build and leaves no APK behind.
BP=$T/buildprov; WRP=android/gradle/wrapper/gradle-wrapper.properties
mkdir -p "$BP/tools" "$BP/android/gradle/wrapper" "$BP/android/app" "$TC/jdk17/bin" "$TC/sdk/platforms/android-34"
# The SDK as Gradle sees it: one build-tools (its source.properties) and compileSdk 34's platform.
printf 'Pkg.UserSrc=false\nPkg.Revision=34.0.0\n#Pkg.Revision=34.0.0 rc3\n' > "$TC/sdk/build-tools/34.0.0/source.properties"
printf 'Pkg.Desc=Android SDK Platform 14\nPkg.Revision=3\nAndroidVersion.ApiLevel=34\n' > "$TC/sdk/platforms/android-34/source.properties"
printf 'android {\n    compileSdk 34\n}\n' > "$BP/android/app/build.gradle"
cp "$ROOT/build.sh" "$ROOT/.gitignore" "$BP/" && cp "$ROOT/tools/signer-cert.sha256" "$BP/tools/" && cp "$ROOT/$WRP" "$BP/$WRP"
printf 'print("check ok (tools.test.sh stub)")\n' > "$BP/tools/check.py"
printf '<?xml version="1.0" encoding="UTF-8"?>\n<verification-metadata/>\n' > "$BP/android/gradle/verification-metadata.xml"
cat > "$BP/android/gradlew" <<'EOF'
#!/bin/bash
# Fake gradlew: the last argument (assembleRelease/assembleStaging) writes that APK where Gradle would.
case "${!#}" in assembleRelease) o=release/app-release.apk ;; assembleStaging) o=staging/app-staging.apk ;; *) exit 3 ;; esac
mkdir -p "app/build/outputs/apk/${o%/*}" && echo "fake apk $RANDOM" > "app/build/outputs/apk/$o"
EOF
cat > "$TC/jdk17/bin/java" <<'EOF'
#!/bin/bash
# Fake java: -version as Temurin prints it (stderr); nothing and exit 1 when $FAKE_STATE/java_broken exists.
# $FAKE_STATE/java_kills_build (a build.sh path): SIGKILL that build.sh, part-way through (no trap runs).
if [ -s "$FAKE_STATE/java_kills_build" ]; then pkill -KILL -f "bash $(cat "$FAKE_STATE/java_kills_build")"; exit 1; fi
[ "$1" = -version ] && [ ! -f "$FAKE_STATE/java_broken" ] || exit 1
echo 'openjdk version "17.0.99" 2099-01-01' >&2
echo 'OpenJDK Runtime Environment Temurin-17.0.99+1 (build 17.0.99+1)' >&2
echo 'OpenJDK 64-Bit Server VM Temurin-17.0.99+1 (build 17.0.99+1, mixed mode)' >&2
EOF
chmod +x "$BP/android/gradlew" "$TC/jdk17/bin/java"; : > "$T/fake-signing.properties"
git -C "$BP" init -q -b main && git -C "$BP" add -A && git -C "$BP" commit -q -m "build.sh provenance test" \
  && git -C "$BP" update-ref refs/remotes/origin/main main
BPSHA=$(git -C "$BP" rev-parse --short=12 HEAD); PV=$BP/dist/cleanframe-tv-$BPSHA.apk.provenance
# (versionCode is the commit count, as android/app/build.gradle sets it)
echo "$PIN" > "$FAKE/signer"; echo home.cleanframe.tv > "$FAKE/aapt_pkg"; echo "0.1.0+$BPSHA" > "$FAKE/aapt_vn"; git -C "$BP" rev-list --count HEAD > "$FAKE/aapt_vc"
GSUM=$(sed -n 's/^distributionSha256Sum=//p' "$ROOT/$WRP")
prov_ok() {
  local sum; sum=$(sed -n 's/^\([0-9a-f]\{64\}\)[[:space:]].*/\1/p' "$BP/dist/cleanframe-tv-$BPSHA.apk.sha256" 2>/dev/null)
  [[ $sum =~ ^[0-9a-f]{64}$ ]] && [[ $GSUM =~ ^[0-9a-f]{64}$ ]] && [ -f "$PV" ] \
    && grep -qx "apk=cleanframe-tv-$BPSHA.apk" "$PV" && grep -qx "sha=$BPSHA" "$PV" && grep -qx "versionName=0.1.0+$BPSHA" "$PV" \
    && grep -qx "versionCode=$(git -C "$BP" rev-list --count HEAD)" "$PV" && grep -qx "apk_sha256=$sum" "$PV" \
    && grep -qx 'jdk=openjdk version "17.0.99" 2099-01-01 | OpenJDK Runtime Environment Temurin-17.0.99+1 (build 17.0.99+1) | OpenJDK 64-Bit Server VM Temurin-17.0.99+1 (build 17.0.99+1, mixed mode)' "$PV" \
    && grep -qx "build_tools=${PROV_BT:-34.0.0}" "$PV" && grep -qx 'platform=android-34 r3' "$PV" \
    && grep -qxE 'gradle=[0-9]+(\.[0-9]+)+' "$PV" && grep -qx "gradle_sha256=$GSUM" "$PV" \
    && grep -qxE 'built=[0-9]{4}-[0-9]{2}-[0-9]{2}T.*' "$PV" && [ "$(grep -c . "$PV")" = 11 ]
}
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"
check build-writes-provenance 0 "BUILD_OK dist/cleanframe-tv-$BPSHA.apk" prov_ok
rm -rf "$BP/dist"; : > "$FAKE/java_broken"; run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; rm -f "$FAKE/java_broken"
check build-unreadable-jdk-refused nz "refusing: could not read JDK for dist/cleanframe-tv-$BPSHA.apk.provenance" \
  eval 'not has BUILD_OK && [ -z "$(ls -A "$BP/dist")" ]'
# A rebuild overwrites the provenance (the new APK's sha256), and a refused rebuild leaves nothing of the
# earlier build behind: no APK, no .sha256, no .provenance (a stale record of another APK).
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"
# shellcheck disable=SC2034  # read in the eval'd post-condition below
first_sum=$(sed -n 's/^apk_sha256=//p' "$PV")
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"
check build-rebuild-overwrites-provenance 0 "BUILD_OK dist/cleanframe-tv-$BPSHA.apk" eval 'prov_ok && [ -n "$first_sum" ] && not grep -qx "apk_sha256=$first_sum" "$PV"'
: > "$FAKE/java_broken"; run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; rm -f "$FAKE/java_broken"
check build-refused-removes-old-provenance nz "refusing: could not read JDK" eval 'not has BUILD_OK && [ -z "$(ls -A "$BP/dist")" ]'
# A build killed part-way (here while reading the JDK, after the APK's sha256 is known) leaves no
# .sha256 without its .provenance (install.sh accepts a release APK by its .sha256); the next build
# clears what it left and completes.
rm -rf "$BP/dist"; echo "$BP/build.sh" > "$FAKE/java_kills_build"; run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; rm -f "$FAKE/java_kills_build"
APKP=$BP/dist/cleanframe-tv-$BPSHA.apk
check build-killed-leaves-no-lone-sha256 nz "check ok (tools.test.sh stub)" eval 'not has BUILD_OK && [ -e "$APKP" ] && [ ! -e "$APKP.sha256" ] && [ ! -e "$APKP.provenance" ]'
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"
check build-after-kill-completes 0 "BUILD_OK dist/cleanframe-tv-$BPSHA.apk" eval 'prov_ok && [ -z "$(find "$BP/dist" -name "*.part")" ]'
# apksigner that cannot verify the APK: refused, named, the APK removed (it used to exit under set -e
# with no message and leave the unverified APK in dist/).
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; : > "$FAKE/apksigner_fails"
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; rm -f "$FAKE/apksigner_fails"
check build-apksigner-fails-refused nz "refusing: apksigner verify failed for dist/cleanframe-tv-$BPSHA.apk: DOES NOT VERIFY" \
  eval 'not has BUILD_OK && [ -z "$(ls -A "$BP/dist")" ]'
# versionName must carry this build's SHA (0.1.0+<sha>): anything else is refused and removed.
echo "0.1.0+000000000000" > "$FAKE/aapt_vn"; run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; echo "0.1.0+$BPSHA" > "$FAKE/aapt_vn"
check build-versionname-mismatch-refused nz "refusing: dist/cleanframe-tv-$BPSHA.apk has versionName '0.1.0+000000000000', not 0.1.0+$BPSHA" \
  eval 'not has BUILD_OK && [ -z "$(ls -A "$BP/dist")" ]'
# versionCode must be this commit's count, as build.gradle sets it: anything else is refused and removed.
BPVC=$(cat "$FAKE/aapt_vc"); echo 99999 > "$FAKE/aapt_vc"; run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; echo "$BPVC" > "$FAKE/aapt_vc"
check build-versioncode-mismatch-refused nz "refusing: dist/cleanframe-tv-$BPSHA.apk has versionCode '99999', not $BPVC" \
  eval 'not has BUILD_OK && [ -z "$(ls -A "$BP/dist")" ]'
# build_tools is the SDK's own record of the build-tools Gradle used, not build.sh's constant: the only one
# installed (Gradle cannot use another), or android/app/build.gradle's buildToolsVersion when it pins one.
# Two installed and no pin, or a platform whose revision cannot be read: refused, nothing left behind.
mv "$TC/sdk/build-tools/34.0.0/source.properties" "$T/bt.props"; sed 's/^Pkg.Revision=.*/Pkg.Revision=34.0.1/' "$T/bt.props" > "$TC/sdk/build-tools/34.0.0/source.properties"
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; mv "$T/bt.props" "$TC/sdk/build-tools/34.0.0/source.properties"
check build-provenance-build-tools-read-off-the-sdk 0 "build-tools 34.0.1" eval 'PROV_BT=34.0.1 prov_ok'
mkdir -p "$TC/sdk/build-tools/35.0.0"; printf 'Pkg.Revision=35.0.0\n' > "$TC/sdk/build-tools/35.0.0/source.properties"
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"
check build-two-build-tools-unpinned-refused nz "refusing: could not read BT for" eval 'not has BUILD_OK && [ -z "$(ls -A "$BP/dist")" ]'
mv "$TC/sdk/build-tools/35.0.0" "$T/bt35"; rm -rf "$BP/dist"; mv "$TC/sdk/platforms/android-34/source.properties" "$T/pf.props"
run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"; mv "$T/pf.props" "$TC/sdk/platforms/android-34/source.properties"
check build-unreadable-platform-refused nz "refusing: could not read PLATFORM for" eval 'not has BUILD_OK && [ -z "$(ls -A "$BP/dist")" ]'
mv "$T/bt35" "$TC/sdk/build-tools/35.0.0"
printf 'android {\n    compileSdk 34\n    buildToolsVersion "35.0.0"\n}\n' > "$BP/android/app/build.gradle"; git -C "$BP" commit -q -am "pin build-tools"
git -C "$BP" update-ref refs/remotes/origin/main main; BPSHA=$(git -C "$BP" rev-parse --short=12 HEAD); PV=$BP/dist/cleanframe-tv-$BPSHA.apk.provenance
echo "0.1.0+$BPSHA" > "$FAKE/aapt_vn"; git -C "$BP" rev-list --count HEAD > "$FAKE/aapt_vc"; run_build "$BP" CLEANFRAME_SIGNING="$T/fake-signing.properties"
check build-provenance-pinned-build-tools 0 "build-tools 35.0.0" eval 'PROV_BT=35.0.0 prov_ok'
rm -rf "$TC/sdk/build-tools/35.0.0"

# ---------------------------------------------------------------- tools/backup-signing-key.sh
# A FAKE key under a fake HOME, backed up to a temp dir (CLEANFRAME_KEY_BACKUP): never the real
# key, never a real backup directory. Seeded with 3 older runs, a killed run's .partial and a stale verify dir.
KH=$T/keyhome; KB=$T/keybackup
mkdir -p "$KH/.config/cleanframe-tv" "$KH/scratch/cf-key-verify.stale" "$KB/20990101T000000Z.partial"
echo "fake keystore $RANDOM" > "$KH/.config/cleanframe-tv/release.jks"
echo "storePassword=fake-not-a-secret" > "$KH/.config/cleanframe-tv/signing.properties"
echo fake > "$KH/scratch/cf-key-verify.stale/release.jks"
for d in 20200101T000000Z 20200102T000000Z 20200103T000000Z; do mkdir -p "$KB/$d"; done
run_backup() { ( cd "$T" && HOME=$KH CLEANFRAME_KEY_BACKUP=$KB bash "$ROOT/tools/backup-signing-key.sh" ) > "$T/out" 2>&1; RC=$?; }
backup_ok() {
  local runs=("$KB"/2*Z) new
  new=$(sed -n 's/^KEY_BACKUP_OK \([^ ]*\) .*/\1/p' "$T/out")
  [ ${#runs[@]} = 3 ] && [ ! -e "$KB/20200101T000000Z" ] && [ -d "$KB/20200102T000000Z" ] && [ -d "$new" ] \
    && [ -z "$(find "$KB" "$KH/scratch" -maxdepth 1 \( -name '*.partial' -o -name 'cf-key-verify.*' \))" ] \
    && mkdir "$T/restore" && tar -xzf "$new/cleanframe-tv-signing.tar.gz" -C "$T/restore" \
    && cmp -s "$T/restore/.config/cleanframe-tv/release.jks" "$KH/.config/cleanframe-tv/release.jks" \
    && cmp -s "$T/restore/.config/cleanframe-tv/signing.properties" "$KH/.config/cleanframe-tv/signing.properties"
}
# Two runs never overlap: while another run holds the lock, a run refuses at once and touches
# nothing, not even the other run's in-progress .partial (the old prune deleted it mid-write).
mkdir -p "$KB/20990102T000000Z.partial"; echo in-progress > "$KB/20990102T000000Z.partial/x"
exec 8>>"$KH/scratch/cf-key-backup.lock"
if flock -n 8; then
  t0=$SECONDS; run_backup; took=$((SECONDS - t0))
  check backup-key-concurrent-run-refused nz "another tools/backup-signing-key.sh is running" \
    eval '[ -f "$KB/20990102T000000Z.partial/x" ] && [ -d "$KB/20990101T000000Z.partial" ] && [ -d "$KB/20200101T000000Z" ] && [ $took -lt 20 ] && not has KEY_BACKUP_OK'
else fail backup-key-concurrent-run-refused "the test could not take the lock itself"; fi
exec 8>&-   # released: the next run prunes both partials (their run is gone)
run_backup
check backup-key-newest-3-no-leftovers 0 "KEY_BACKUP_OK $KB/2" backup_ok
# The directory has no default: env, else dest= in backup.conf, whose mount= must be mounted; neither refuses.
KC=$KH/.config/cleanframe-tv/backup.conf; run_backup_conf() { ( cd "$T" && env -u CLEANFRAME_KEY_BACKUP HOME=$KH bash "$ROOT/tools/backup-signing-key.sh" ) > "$T/out" 2>&1; RC=$?; }
run_backup_conf; check backup-key-no-directory-refused nz "no backup directory"
printf 'dest=%s\nmount=%s\n' "$T/keyconf" "$T/not-a-mount" > "$KC"; run_backup_conf
check backup-key-not-mounted-refused nz "is not mounted" eval '[ ! -e "$T/keyconf" ]'
printf 'dest=%s\n' "$T/keyconf" > "$KC"; run_backup_conf; check backup-key-conf-directory 0 "KEY_BACKUP_OK $T/keyconf/2"
rm -f "$KC"
# The restore check is what makes it a backup: an archive that restores to other bytes counts for nothing. Here tar
# restores a damaged copy (a shim on PATH, for this run only): no new run, no last-success, no leftovers with the key.
KT=$T/keytarbin; mkdir -p "$KT"
cat > "$KT/tar" <<'EOF'
#!/bin/bash
"$REAL_TAR" "$@" || exit
if [ "$1" = -xzf ]; then printf X | dd of="$4/.config/cleanframe-tv/$KEY_DAMAGE" bs=1 count=1 conv=notrunc 2>/dev/null; fi
EOF
chmod +x "$KT/tar"; REAL_TAR=$(command -v tar); export REAL_TAR
for KEY_DAMAGE in release.jks signing.properties; do
  "$REAL_SLEEP" 1.1   # (run directories are named by the second)
  export KEY_DAMAGE; runs_before=$(find "$KB" -maxdepth 1 -name '2*Z' | sort | tr '\n' ' '); ls_before=$(cat "$KB/last-success")
  ( cd "$T" && PATH=$KT:$PATH HOME=$KH CLEANFRAME_KEY_BACKUP=$KB bash "$ROOT/tools/backup-signing-key.sh" ) > "$T/out" 2>&1; RC=$?
  check "backup-key-restore-mismatch-$KEY_DAMAGE-refused" nz "$KEY_DAMAGE differ" \
    eval 'not has KEY_BACKUP_OK && [ "$(find "$KB" -maxdepth 1 -name "2*Z" | sort | tr "\n" " ")" = "$runs_before" ] && [ "$(cat "$KB/last-success")" = "$ls_before" ] && [ -z "$(find "$KB" "$KH/scratch" -maxdepth 1 \( -name "*.partial" -o -name "cf-key-verify.*" \))" ]'
done
unset KEY_DAMAGE
rm -f "$KH/.config/cleanframe-tv/release.jks"; run_backup
check backup-key-missing-refused nz "refusing: no key in" true

# ---------------------------------------------------------------- tools/adb-lib.sh (timeout switch)
# The lib itself, sourced into a pipefail shell with an adb that never answers. ADB_TIMEOUT_LOG set
# (stick-test.sh): the timeout is appended to that file and printed as "FAIL  adb timeout".
# Empty (install.sh): "ADB TIMEOUT", nothing logged. Either way the call fails with 124.
HB=$T/hangbin; mkdir -p "$HB"
printf '#!/bin/bash\nexec "$REAL_SLEEP" 30\n' > "$HB/adb"; chmod +x "$HB/adb"
lib_call() {  # lib_call <ADB_TIMEOUT_LOG value>: a() against the hanging adb, 1 s deadline
  ( cd "$T" && PATH=$HB:$PATH ADB_TIMEOUT_LOG=$1 bash -c 'set -euo pipefail; S=$0; ADB_TIMEOUT=1
      . "$1"; rc=0; a shell true || rc=$?; echo "rc=$rc"' "$SERIAL" "$ROOT/tools/adb-lib.sh" ) > "$T/out" 2>&1
  RC=$?
}
LIBMSG="'adb -s $SERIAL shell true' gave no answer in 1 s (half-dead connection? try: adb disconnect $SERIAL)"
rm -f "$T/lib-timeouts"; lib_call "$T/lib-timeouts"
check adb-lib-timeout-switch-on 0 "FAIL  adb timeout: $LIBMSG" \
  eval 'has rc=124 && not has "ADB TIMEOUT" && [ "$(cat "$T/lib-timeouts")" = "adb -s $SERIAL shell true (no answer in 1 s)" ]'
lib_call ""
check adb-lib-timeout-switch-off 0 "ADB TIMEOUT: $LIBMSG" \
  eval 'has rc=124 && not has "FAIL  adb timeout"'

# ---------------------------------------------------------------- tools/devtools-eval.mjs
# The real script against a fake DevTools (fetch and WebSocket replaced before it loads, node
# --import): a value prints as JSON, null as the protocol's result (expect_set refuses it), and
# undefined is no answer: exit 1, nothing on stdout. Printed as {"result":{"type":"undefined"}} it
# used to read as a value to tools/stick-test.sh's dt, so expect_set/expect_not passed on nothing.
# DT_MOCK_REPLY replaces the whole reply (an error reply has no result); DT_MOCK_REFUSE: the socket
# fails (error event, never opens); DT_MOCK_NO_WS: no global WebSocket (Node before 22).
cat > "$T/dt-mock.mjs" <<'EOF'
globalThis.fetch = async () => ({ json: async () => [{ url: 'https://cleanframe.local/home/', webSocketDebuggerUrl: 'ws://fake.invalid/1' }] });
globalThis.WebSocket = class {
  constructor() { setTimeout(() => (process.env.DT_MOCK_REFUSE ? this.onerror({ type: 'error' }) : this.onopen()), 0); }
  send(m) {
    const id = JSON.parse(m).id;
    const reply = process.env.DT_MOCK_REPLY ? JSON.parse(process.env.DT_MOCK_REPLY) : { id, result: JSON.parse(process.env.DT_MOCK_RESULT) };
    setTimeout(() => this.onmessage({ data: JSON.stringify(reply) }), 0);
  }
  close() {}
};
if (process.env.DT_MOCK_NO_WS) delete globalThis.WebSocket;
EOF
dte() { ( cd "$T" && DT_MOCK_RESULT=$1 node --import "$T/dt-mock.mjs" "$ROOT/tools/devtools-eval.mjs" x ) > "$T/dt-stdout" 2> "$T/out"; RC=$?; }
dte '{"result":{"type":"string","value":"ok"}}'
if [ $RC = 0 ] && [ "$(cat "$T/dt-stdout")" = '"ok"' ]; then pass devtools-eval-prints-value
else fail devtools-eval-prints-value "exit $RC, stdout '$(cat "$T/dt-stdout")', want 0 and '\"ok\"'"; fi
dte '{"result":{"type":"object","subtype":"null","value":null}}'
if [ $RC = 0 ] && [[ $(cat "$T/dt-stdout") == *'"subtype":"null"'* ]]; then pass devtools-eval-null-is-the-protocol-result
else fail devtools-eval-null-is-the-protocol-result "exit $RC, stdout '$(cat "$T/dt-stdout")'"; fi
dte '{"result":{"type":"undefined"}}'
if [ $RC != 0 ] && [ ! -s "$T/dt-stdout" ] && has "gave undefined"; then pass devtools-eval-undefined-is-no-answer
else fail devtools-eval-undefined-is-no-answer "exit $RC, stdout '$(cat "$T/dt-stdout")', want non-zero and nothing"; fi
# An error reply ({id:1,error:...}, no result) exits 1 naming it, never a TypeError on r.result.
DT_MOCK_REPLY='{"id":1,"error":{"code":-32000,"message":"Cannot find context with specified id"}}' dte '{}'
if [ $RC = 1 ] && [ ! -s "$T/dt-stdout" ] && has "devtools: Runtime.evaluate failed" && has "Cannot find context" && not has TypeError; then pass devtools-eval-error-reply-named
else fail devtools-eval-error-reply-named "exit $RC, stdout '$(cat "$T/dt-stdout")', want 1, nothing, the error named"; fi
# A refused socket fails at once (onerror), not after the 10 s no-answer wait.
t0=$SECONDS; DT_MOCK_REFUSE=1 dte '{}'; took=$((SECONDS - t0))
if [ $RC = 1 ] && [ $took -lt 5 ] && has "devtools: WebSocket error" && not has "no answer in 10s"; then pass devtools-eval-refused-socket-fails-fast
else fail devtools-eval-refused-socket-fails-fast "exit $RC after ${took}s, want 1 at once with the socket error named"; fi
# Node without a global WebSocket (before 22): named, exit 1, not a ReferenceError.
DT_MOCK_NO_WS=1 dte '{}'
if [ $RC = 1 ] && has "needs Node 22+" && not has ReferenceError; then pass devtools-eval-old-node-named
else fail devtools-eval-old-node-named "exit $RC, want 1 and 'needs Node 22+'"; fi

# ---------------------------------------------------------------- tools/stick-test.sh (gate logic)
# The WORKING TREE's stick-test.sh + stick-lib.sh run from a temp copy (their own dist/) against a
# fake stick: a fake adb (keys, activities, HOME resolution, uiautomator, logcat from a file) and
# a fake `node` standing in for tools/devtools-eval.mjs (the page's DevTools answers, from the same
# state). The fake page behaves like a good build, so a run passes unless a case injects a fault.
# What is tested is stick-test.sh's own logic: the staging-ok marker (deleted at start, written
# only after a full clean pass), mode refusals, crash/ANR/renderer counting for OUR package only,
# a failed logcat read, and adb timeouts. The stick's real behaviour is not (that is the stick run).
SB=$T/stickbin; SR=$T/stickrepo; STK=$T/stick; SH=$T/stickhome
mkdir -p "$SB" "$SR/tools" "$SH"
cat > "$SB/adb" <<'EOF'
#!/bin/bash
# Fake stick adb. State in $STK: vn_<pkg>, home, resumed, act, sheet, panel, view, focus, centers,
# list, ui_empty, logcat, logcat_mid + logcat_mid_at (those lines in that one read of the logs only, as
# if the buffer then wrapped; reads counted in logcat_reads), hang (a substring: any call containing it never answers), apk_sha (the
# installed APK's sha256; none: sha256sum fails), keys_die_after (a pg step: once the fake page has
# answered it, every key press fails, as on a dropped connection). Every call is logged.
S=$STK; echo "$*" >> "$S/adb.log"
st() { cat "$S/$1" 2>/dev/null; }
if [ -f "$S/hang" ] && [[ "$*" == *"$(st hang)"* ]]; then exec "$REAL_SLEEP" 30; fi
if [ "$1" = connect ]; then echo "connected to $2"; exit 0; fi
[ "$1" = -s ] && [ "$2" = "$FAKE_SERIAL" ] || { echo "fake adb: unexpected args $*" >&2; exit 97; }
shift 2
closeall() { echo false > "$S/sheet"; echo false > "$S/panel"; echo home > "$S/view"; }
case "$1 ${2:-} ${3:-}" in
  "get-state "*) echo device ;;
  "forward "*) exit 0 ;;
  "logcat -b "*) exit 0 ;;   # clear
  "logcat -d "*) n=$(( $(st logcat_reads || echo 0) + 1 )); echo "$n" > "$S/logcat_reads"; cat "$S/logcat"
    [ "$n" != "$(st logcat_mid_at)" ] || cat "$S/logcat_mid" ;;
  "shell pidof "*) echo 4242 ;;
  "shell dumpsys package") [ -f "$S/vn_$4" ] && printf '  Package [%s]\n    versionName=%s\n' "$4" "$(st "vn_$4")" ;;
  "shell dumpsys activity")   # splash_left: that many more looks, then the launched app's next screen comes to the front
    n=$(st splash_left); if [ -n "$n" ]; then if [ "$n" -le 1 ]; then rm -f "$S/splash_left"; echo com.google.android.youtube.tv/.Dashboard > "$S/resumed"; else echo $((n - 1)) > "$S/splash_left"; fi; fi
    if [ -f "$S/a14" ]; then   # Android 14's wording (the Hugsun): no mResumedActivity line
      printf '      topResumedActivity=ActivityRecord{1 u0 %s t1}\n    Resumed: ActivityRecord{1 u0 %s t1}\n  ResumedActivity: ActivityRecord{1 u0 %s t1}\n' "$(st resumed)" "$(st resumed)" "$(st resumed)"
    else echo "  mResumedActivity: ActivityRecord{1 u0 $(st resumed) t1}"; fi ;;
  "shell cmd package") printf 'ActivityInfo:\n  name=x\n  packageName=%s\n' "$(st home)" ;;
  "shell am start")
    n=; for x in "$@"; do [ "${p:-}" = -n ] && n=$x; p=$x; done
    echo "$n" > "$S/resumed"; [[ "$*" != *category.HOME* ]] || { closeall; rm -f "$S/un_rec"; } ;;
  "shell input keyevent")
    if [ -f "$S/keys_die_after" ] && grep -qx "$(st keys_die_after)" "$S/pg.log" "$S/hx.log" 2>/dev/null; then echo "error: closed" >&2; exit 1; fi
    # The key pressed on Unpair's question (/*bt:ask*/): the one thing that could unpair a real device.
    if [ -f "$S/after_ask" ]; then echo "$4" > "$S/ask_key"; rm -f "$S/after_ask"; fi
    # Uninstall: every key from the menu's opening to the next Home; OK inside Android's dialog would be a real uninstall.
    [ ! -f "$S/un_rec" ] || echo "$4" >> "$S/un_keys"
    if [ "$4" = DPAD_CENTER ] && [ "$(st resumed)" = com.android.packageinstaller/.UninstallerActivity ]; then touch "$S/un_confirmed"; fi
    case "$4" in
      HOME|KEYCODE_HOME) closeall; rm -f "$S/un_rec"; st act > "$S/resumed" ;;
      DPAD_LEFT) echo home.app.b > "$S/focus" ;;
      DPAD_RIGHT) echo home.app.c > "$S/focus" ;;
      DPAD_UP) ;;
      DPAD_DOWN) st act > "$S/resumed" ;;
      DPAD_CENTER)
        if [ -f "$S/next_resumed" ]; then mv "$S/next_resumed" "$S/resumed"; exit 0; fi
        c=$(( $(st centers) + 1 )); echo $c > "$S/centers"
        case $c in 1) echo com.google.android.youtube.tv/.Main > "$S/resumed"; [ ! -f "$S/splash" ] || st splash > "$S/splash_left" ;;
                   *) echo true > "$S/sheet" ;; esac ;;
      BACK) if [ "$(st list)" = 1 ]; then echo 0 > "$S/list"
            # Back on Android's pairing page (Settings > Bluetooth > Pair new device) returns to the app
            elif [ "$(st resumed)" = com.android.tv.settings/.accessories.AddAccessoryActivity ]; then st act > "$S/resumed"
            elif [ "$(st resumed)" = com.android.packageinstaller/.UninstallerActivity ]; then st act > "$S/resumed"
            else echo false > "$S/sheet"; fi ;;
      --longpress) echo 1 > "$S/list" ;;
    esac ;;
  "shell settings get") if [ "${5:-}" = screen_off_timeout ]; then echo "$(st timeout)"
    elif [ -f "$S/ime_fail" ]; then echo "error: closed" >&2; exit 1   # the read fails: no answer at all
    else echo null; fi ;;   # no keyboard: Add group typing is SKIPped
  "shell pm path") echo "package:/data/app/~~fake==/$4-fake==/base.apk" ;;
  "shell sha256sum "*) [ "$(st apk_sha)" != none ] || { echo "sha256sum: $3: Permission denied" >&2; exit 1; }
    echo "$(st apk_sha)  $3" ;;
  # No R calls (cat, ls, grep, mkdir, mv, push): adb cannot read the app's R on the stick (DEVICE-CHECK 1),
  # so stick-test.sh must not try; any such call is unhandled and fails the run.
  "shell ip -4") echo '1: lo    inet 127.0.0.1/8 scope host lo'; echo '3: wlan0    inet 10.11.12.13/24 brd 10.11.12.255 scope global wlan0' ;;
  "shell getprop ro.product.model") st model ;;   # the stick's identity (stickid): model + serial
  "shell getprop ro.serialno") st serialno ;;
  "shell uiautomator"*)   # the dump-and-cat one-liner, as one argument; ui_empty: that many empty dumps first;
    # ui_late: that many dumps of a page not drawn yet (empty text) before ui_text shows
    n=$(st ui_empty); if [ "${n:-0}" -gt 0 ]; then echo $((n - 1)) > "$S/ui_empty"; exit 0; fi
    n=$(st ui_late); if [ -f "$S/ui_text" ] && [ "${n:-0}" -gt 0 ]; then echo $((n - 1)) > "$S/ui_late"; echo '<hierarchy><node text=""/></hierarchy>'; exit 0; fi
    if [ "$(st list)" = 1 ]; then echo '<hierarchy><node text="Back to CleanFrame"/></hierarchy>'
    elif [ -f "$S/ui_text" ]; then echo "<hierarchy><node text=\"$(st ui_text)\"/>$([ -f "$S/ui_pkg" ] && echo "<node text=\"$(st ui_pkg)\"/>")</hierarchy>"; rm -f "$S/ui_text" "$S/ui_pkg"
    else echo '<hierarchy/>'; fi ;;
  *) echo "fake adb: unhandled: $*" >&2; exit 98 ;;
esac
EOF
cat > "$SB/node" <<'EOF'
#!/bin/bash
# Fake `node tools/devtools-eval.mjs <expr>`: the page's answers, from the fake stick's state.
S=$STK; [ "$1" = tools/devtools-eval.mjs ] || { echo "fake node: unexpected $*" >&2; exit 1; }
e=$2; st() { cat "$S/$1" 2>/dev/null; }
# Cold start: dt_down = K, the first K DevTools calls (any expression) get no answer (DT_ERROR);
# ready_never: the ready probe (stick-test.sh's READY) never answers true. Ready probes are counted.
[[ $e != *'typeof window.ShoreQA'* ]] || echo x >> "$S/ready.log"
n=$(st dt_down); if [ "${n:-0}" -gt 0 ]; then echo $((n - 1)) > "$S/dt_down"; echo "fake devtools: down (cold start)" >&2; exit 1; fi
case $e in
  *'typeof window.ShoreQA'*) if [ -f "$S/ready_never" ]; then echo false; else echo true; fi ;;
  # Feature probes (/*feature:<id>*/, stick-test.sh's feature()): "has" (a build with everything), or
  # $STK/feature_<id> (absent:<why>, broken:<why>; DT_ERROR: no answer). Each probe is logged.
  '/*feature:'*) f=${e#/\*feature:}; f=${f%%\*/*}; echo "$f" >> "$S/feature.log"
    [[ $e == *'querySelectorAll("script[src]")'* ]] || { echo "fake node: feature probe $f reads no script tags" >&2; exit 1; }
    if [ ! -f "$S/feature_$f" ]; then echo '"has"'; elif [ "$(st "feature_$f")" = DT_ERROR ]; then exit 1; else echo "\"$(st "feature_$f")\""; fi ;;
  # The photo-group checks (/*pg:<step>*/): a good build's answers, or $STK/pg_<step> if that file
  # exists; focus:<action> answers the focus it waits for. Each step is logged to $STK/pg.log.
  '/*pg:focus:'*) f=${e#/\*pg:focus:}; echo "\"${f%%\*/*}\"" ;;
  '/*pg:'*) p=${e#/\*pg:}; p=${p%%\*/*}; echo "$p" >> "$S/pg.log"
    # Background type, ASYNCHRONOUS like the page (setLook loads the Photo bundle first): tophoto and
    # restorebg answer the OLD type and start a switch that the next LOOK_POLLS (default 2) polls of
    # <step>wait still see pending; then it lands (tophoto: bg_photo, so pg_open "flat@..." stops).
    # pg_tophoto / pg_restorebg: answer that and never switch.
    case $p in tophoto|restorebg) [ -f "$S/pg_$p" ] || echo "${LOOK_POLLS:-2}" > "$S/sw_$p" ;; esac
    case $p in tophotowait|restorebgwait)
      q=${p%wait}; if [ "$q" = tophoto ]; then old=dark; new=photo; else old=photo; new=dark; fi
      # the poll must ask the page whether the look has landed (nothing pending, shown = committed)
      if [[ $e != *'ShoreQA.ready()'* ]] || [[ $e != *'look.bg'* ]] || [[ $e != *'wallpaper'* ]]; then echo DT_ERROR > "$S/pg_$p.now"
      elif [ -f "$S/pg_$p" ]; then :
      elif [ ! -f "$S/sw_$q" ]; then echo "$old|misty|ready" > "$S/pg_$p.now"
      elif [ "$(st "sw_$q")" -gt 0 ]; then echo $(( $(st "sw_$q") - 1 )) > "$S/sw_$q"; echo "$old|misty|pending" > "$S/pg_$p.now"
      else echo "$new|misty|ready" > "$S/pg_$p.now"; [ "$q" != tophoto ] || touch "$S/bg_photo"; fi ;;
    esac
    if [ "$p" = open ] && [ -f "$S/bg_photo" ]; then rm -f "$S/pg_open"; fi
    if [ -f "$S/pg_$p" ]; then v=$(st "pg_$p"); elif [ -f "$S/pg_$p.now" ]; then v=$(st "pg_$p.now"); else case $p in
      open) v='Scenery|Space@misty' ;; tick) v='scenery|scenery' ;; page) v='wallpaper|space|photo' ;;
      cand|applied) v=space-cliffs ;; reload) v=reloading ;; after) v='space-cliffs|space|fixed' ;; scene) v=appearance ;;
      name) v='groupname|Group 1' ;; setname) v='CF stick test' ;; created) v='wallpaper|CF stick test|acts|gstick001' ;;
      upload) v='upload|482913|1' ;; added) v='1 photo added to CF stick test' ;; view) v=wallpaper ;;
      confirmphoto) v='confirm|photo|No' ;; kept) v='photo|1|true' ;; confirmgroup) v='confirm|group|No' ;;
      deleted) v='appearance|0' ;; cleanup|cleanupname) v=0 ;; home) v=home ;; restore|restored) v=misty ;;
      upurl) v='http://10.11.12.13:8097/u/0123456789abcdef0123456789abcdef' ;; upwait|keepwait) v=1 ;; upgone) v=0 ;;
      upfiles) v='wall:200:jpeg thumb:200:jpeg panel:200:jpeg field-blur:200:jpeg field-clear:200:jpeg field-frost:200:jpeg study-soft:200:jpeg' ;;
      upmeta) v='Stick upload' ;; upleak) v=none ;; gone) v='0|404' ;;
      upcrop|keepwall|keepwall2) v='1920x1080 match' ;; keepcrop) v='0.25|2|new' ;; keepcrop2|keepcrop3) v='0.75|2|edited' ;;
      recrop) v=1 ;; recropstate) v=done ;; recropanswer) v=ok ;; adjustopen) v='adjust|drawn' ;; adjustleft) v='wallpaper|photo' ;;
      bg) v=dark ;; tophoto) v=dark ;; restorebg) v=photo ;;   # the old type: the switch has not landed yet
      *) echo "fake node: unhandled step $p" >&2; exit 1 ;;
    esac; fi; echo "\"$v\"" ;;
  # The Home-additions checks (/*hx:<step>*/): a good build's answers, or $STK/hx_<step> if that file
  # exists; focus:<action> answers the focus it waits for. Each step is logged to $STK/hx.log.
  '/*hx:focus:'*) f=${e#/\*hx:focus:}; echo "\"${f%%\*/*}\"" ;;
  '/*hx:'*) p=${e#/\*hx:}; p=${p%%\*/*}; echo "$p" >> "$S/hx.log"
    if [ -f "$S/hx_$p" ]; then v=$(st "hx_$p"); else case $p in
      marked) v='0|util' ;; zone) v=apps ;; rowswant) v=object ;; rows) v='9|fits' ;; orig) v='12|off|on' ;; reset) v=ok ;; view) v=settings ;;
      page) v='clock|pref.clockFormat.12' ;; saved) v='24|true' ;; home) v=home ;; clock24) v=24h ;; seconds) v=ticking ;;
      hidden) v=hidden ;; restore) v=restored ;;
      order) v='a,b,c' ;; sel) v=a ;; hold|hold2|hold3) v='message|message.move' ;; menuback|moveback) v='home|a,b,c' ;;
      move) v='editor|a' ;; title) v=Emby ;; pkg) v=com.mb.android ;; infoback) v=home ;;
      screenpage) v='screen|screen.open.screenDim' ;; dimpage) v='screenDim|true|Screen' ;; screenback) v=screen ;; screenhome) v=home ;;
      policy) v=2100000 ;; dimfocus) v=home.app.a ;; dim) v='dim|home.app.a' ;; undim) v='bright|home.app.a' ;;
      # Sleep: Sleep after 30 → 60 from the Sleep button and back; Sleep now writes 15 s until a key.
      # The fake stick's screen_off_timeout ($S/timeout) follows what the app would write.
      sleepnow) v=30 ;; sleepnext) v=60 ;; sleepmenu) v='message|Sleep|message.30' ;; sleepfirst) v=message.now ;;
      sleepsaved) v='60|none'; echo 3900000 > "$S/timeout" ;; policy1) v=3900000 ;;
      sleepback) v=30; echo 2100000 > "$S/timeout" ;;
      nap) v='black|30'; echo 15000 > "$S/timeout" ;; napwake|napoff) v='bright|30'; echo 2100000 > "$S/timeout" ;;
      # App info: the next OK opens Android's page for the app, which names it
      infofocus) v=message.info; echo com.android.tv.settings/.device.apps.AppManagementActivity > "$S/next_resumed"; echo Emby > "$S/ui_text"; echo com.mb.android > "$S/ui_pkg" ;;
      # Rename and Change icon: Emby (no override) renamed "CF Test TV" and back; with no choice it draws its
      # package's mark (emby); App's own icon is chosen for the run, then IPTV and back to App's own icon.
      aeorig) v='emby|com.mb.android||' ;; aereset) v=ok ;; aeownname) v=Emby ;; aeorder) v='a,b,c' ;;
      aeautoid) v=emby ;; aetext) v=icons ;; aeauto) v='home|none|emby|a,b,c' ;; aeownset) v=ok ;; aedrawn) v='home|own|emby|a,b,c' ;;
      aemenu) v='message|message.move|message.move,message.rename,message.icon,message.remove,message.info,message.back' ;;
      aefit) v='6|fits' ;; aeun) v=no ;; aerenamefocus) v=message.rename ;; aename) v='appname|appname.field|Emby' ;; aesetname) v='CF Test TV' ;;
      aesaved) v='home|CF Test TV|CF Test TV|CF Test TV|a,b,c' ;; aehold2|aehold3|aehold4) v='message|message.move' ;;
      aeorigfocus) v=appname.original ;; aeorigname) v='home|Emby|Emby|none|a,b,c' ;; aeiconfocus) v=message.icon ;;
      aeicon) v='appicon|appicon.own' ;; aeiptvfocus) v=appicon.iptv ;; aeiptv) v='home|iptv|iptv|a,b,c' ;;
      aeicon2) v='appicon|appicon.iptv' ;; aeownfocus) v=appicon.own ;; aeown) v='home|own|emby|a,b,c' ;; aerestore) v=restored ;;
      *) echo "fake node: unhandled step $p" >&2; exit 1 ;;
    esac; fi; echo "\"$v\"" ;;
  # Random rotation (/*rot:<step>*/): Fixed -> Every hour moves misty to wave (Scenery, 3 photos), the
  # tick is on Rotation, the group page ticks no photo; then put back. Each step is logged to rot.log.
  '/*rot:focus:'*) f=${e#/\*rot:focus:}; echo "\"${f%%\*/*}\"" ;;
  '/*rot:'*) p=${e#/\*rot:}; p=${p%%\*/*}; echo "$p" >> "$S/rot.log"
    if [ -f "$S/rot_$p" ]; then v=$(st "rot_$p"); else case $p in
      rotorig|restored) v='fixed|misty|photo' ;; group) v='scenery|3 photos' ;; count) v=3 ;; page) v=rotation ;;
      onwall) v='60|wave|ready' ;; ongroup) v=scenery ;; tick) v='background|ticked' ;; grouprot) v=rotating ;; page2) v='wallpaper|0|0|1|Now showing' ;;
      restore) v=fixed ;; restoredwall|fixedwall) v='fixed|misty|ready' ;; fixed) v=fixed ;; tophoto|restorebg) v=photo ;; tophotowait|restoredbg) v='fixed|misty|ready' ;;
      *) echo "fake node: unhandled step $p" >&2; exit 1 ;;
    esac; fi; echo "\"$v\"" ;;
  # Settings > Bluetooth (/*bt:<step>*/): two paired devices; focus on Pair new device makes the next OK
  # open Android's pairing page. Each step is logged to bt.log.
  '/*bt:focus:'*) f=${e#/\*bt:focus:}; f=${f%%\*/*}; echo "\"$f\""
    [ "$f" != bluetooth.pair ] || echo com.android.tv.settings/.accessories.AddAccessoryActivity > "$S/next_resumed" ;;
  '/*bt:'*) p=${e#/\*bt:}; p=${p%%\*/*}; echo "$p" >> "$S/bt.log"
    [ "$p" != ask ] || : > "$S/after_ask"
    if [ -f "$S/bt_$p" ]; then v=$(st "bt_$p"); else case $p in
      row) v='2 paired' ;; page) v='devices|2' ;; back) v=bluetooth ;; home) v=home ;;
      # Unpair: a good build that can unpair, one device of each sort. Nothing here can unpair.
      canunpair) v=true ;; counts) v='1|2|' ;; noid) v='That device is not paired here any more.' ;;
      norow) v=bluetooth.device.0 ;; refused) v="message|Remotes and other devices stay paired here so the TV can't lose its remote. Use TV settings to remove them." ;;
      yesrow) v='bluetooth.device.1|bt7' ;; ask) v='message|message.no|asks' ;; kept) v='appearance|kept' ;;
      *) echo "fake node: unhandled step $p" >&2; exit 1 ;;
    esac; fi; echo "\"$v\"" ;;
  # Uninstall (/*un:<step>*/): a good build, an app that can go; or $STK/un_<step>. Focus on Uninstall: the
  # next OK opens Android's dialog (un_nodialog: nothing opens). Nothing here can uninstall.
  '/*un:'*) p=${e#/\*un:}; p=${p%%\*/*}; echo "$p" >> "$S/un.log"
    if [ "$p" = menu ]; then : > "$S/un_keys"; : > "$S/un_rec"; fi
    if [ -f "$S/un_$p" ]; then v=$(st "un_$p"); else case $p in
      self|system) v=false ;; app) v='emby|com.mb.android|true' ;; order) v='a,b,c' ;;
      menu) v='message|message.move|message.move,message.rename,message.icon,message.remove,message.info,message.uninstall,message.back' ;;
      focus) v=message.uninstall ;; kept) v='home|a,b,c|listed' ;;
      *) echo "fake node: unhandled step $p" >&2; exit 1 ;;
    esac; fi
    if [ "$p" = focus ] && [ "$v" = message.uninstall ] && [ ! -f "$S/un_nodialog" ]; then echo com.android.packageinstaller/.UninstallerActivity > "$S/next_resumed"; fi
    echo "\"$v\"" ;;
  *appRecords*) echo '"com.google.android.youtube.tv"' ;;
  *'ShoreQA.scene("apps")'*) echo true > "$S/sheet"; echo apps > "$S/view"; echo '"apps"' ;;
  *'ShoreQA.scene("appearance")'*) echo true > "$S/panel"; echo appearance > "$S/view"; echo '"appearance"' ;;
  *'getElementById("sheet")'*) st sheet ;;
  *'getElementById("appearance")'*) st panel ;;
  'ShoreQA.state().view') echo "\"$(st view)\"" ;;
  *'getElementById("stage")'*) if [ -f "$S/scale_off" ]; then echo '"scale(0.5) vs scale(0.751563)"'; else echo '"fit"'; fi ;;
  # focus_undefined: the page answers undefined, as DevTools prints it ({"result":{"type":"undefined"}})
  # focus_lost: after D-pad Left focus is on nothing, as tools/devtools-eval.mjs prints a null
  *is-focus*) if [ -f "$S/focus_undefined" ]; then echo '{"result":{"type":"undefined"}}'
    elif [ -f "$S/focus_lost" ] && [ "$(st focus)" = home.app.b ]; then echo '{"result":{"type":"object","subtype":"null","value":null}}'
    else echo "\"$(st focus)\""; fi ;;
  *data-native-icon*) echo 3 ;;
  *'querySelector("[data-action]")!==null'*) echo true ;;
  *) echo "fake node: unhandled $e" >&2; exit 1 ;;
esac
EOF
cat > "$SB/curl" <<'EOF'
#!/bin/bash
# Fake curl for stick-test.sh's direct-upload step: the stick's upload page as a good build answers.
# $STK/curl_<what> (code, upload, keep, delete, redirect, thumb, closed) overrides an answer; curl_closed_rc is
# the exit code with curl_closed (default 0; without curl_closed the port refuses: closed:000, exit 7). Every call's
# argv is logged to $STK/curl.log; a config read with --config - (URL and form data) to $STK/curl-config.log,
# and is matched as if it were in argv.
S=$STK; echo "$*" >> "$S/curl.log"
st() { cat "$S/$1" 2>/dev/null; }
cfg=; if [[ " $* " == *" --config - "* ]]; then cfg=$(cat); echo "$cfg" >> "$S/curl-config.log"; fi
url=$(sed -n 's/^url = "\(.*\)"$/\1/p' <<<"$cfg"); data=$(sed -n 's/^data = "\(.*\)"$/\1/p' <<<"$cfg")
case " $* ${data:+--data $data }$url " in
  *"filename=Stick keep"*) if [ -f "$S/curl_keep" ]; then st curl_keep; else echo '{"ok":true,"photo":{"group":"gstick001","id":"pstickkeep1","name":"Stick keep","added":2,"pending":true}}'; fi ;;
  *" -F "*) if [ -f "$S/curl_upload" ]; then st curl_upload; else echo '{"ok":true,"photo":{"group":"gstick001","id":"pstickup001","name":"Stick upload","added":1,"pending":true}}'; fi ;;
  *" --data code="*) if [ -f "$S/curl_redirect" ]; then st curl_redirect; else echo -n '303 http://10.11.12.13:8097/u/0123456789abcdef0123456789abcdef'; fi ;;
  *"/thumbs/"*) o=; p=; for x in "$@"; do [ "$p" = -o ] && o=$x; p=$x; done
    if [ -f "$S/curl_thumb" ]; then st curl_thumb > "$o"; else printf '\377\330clean\377\331' > "$o"; fi; echo -n 200 ;;
  *"closed:%{http_code}"*) if [ -f "$S/curl_closed" ]; then st curl_closed; exit "$(st curl_closed_rc || echo 0)"; else echo -n closed:000; exit 7; fi ;;
  *" -X DELETE "*) if [ -f "$S/curl_delete" ]; then st curl_delete; else echo '{"ok":true}'; fi ;;
  *"%{http_code}"*) if [ -f "$S/curl_code" ]; then st curl_code; else echo 200; fi ;;
  *) echo "fake curl: unhandled $*" >&2; exit 2 ;;
esac
EOF
chmod +x "$SB/adb" "$SB/node" "$SB/curl"
export STK
LOGLINE='10-31 12:00:00.000  1000  1000 I ActivityManager: nothing to see'
# stick_fresh <package> <versionName>: a fake stick with that build installed, Home = CleanFrame.
stick_fresh() {
  rm -rf "${STK:?}" "${SR:?}"/dist "${SR:?}"/.git "${SH:?}"/scratch; mkdir -p "$STK" "$SR/dist"
  cp "$ROOT/tools/stick-test.sh" "$ROOT/tools/stick-lib.sh" "$ROOT/tools/adb-lib.sh" "$SR/tools/"
  mkdir -p "$SR/web/assets/wallpapers"; cp "$ROOT/web/assets/wallpapers/space-moon-thumb.jpg" "$SR/web/assets/wallpapers/"
  echo "$2" > "$STK/vn_$1"; echo home.cleanframe.tv > "$STK/home"; echo home.app.a > "$STK/focus"
  # the installed APK is the one build.sh recorded for that commit
  echo "$APKSUM" > "$STK/apk_sha"; echo "$APKSUM  cleanframe-tv-${2#*+}-staging.apk" > "$SR/dist/cleanframe-tv-${2#*+}-staging.apk.sha256"
  if [ "$1" = home.cleanframe.tv ]; then echo home.cleanframe.tv/.MainActivity > "$STK/act"
  else echo home.cleanframe.tv.staging/home.cleanframe.tv.MainActivity > "$STK/act"; fi
  cp "$STK/act" "$STK/resumed"; echo 0 > "$STK/centers"; echo "$LOGLINE" > "$STK/logcat"; : > "$STK/adb.log"
  echo 2100000 > "$STK/timeout"   # Settings > Screen's defaults applied: (5 + 30) min
  echo "Fake TV 4K" > "$STK/model"; echo XS0001 > "$STK/serialno"   # stick X: $SIDX
}
run_stick() {  # run_stick [args...]; ADB_TIMEOUT / PKG, if set on the call, reach stick-test.sh
  ( cd "$T" && PATH=$SB:$BIN:$PATH HOME=$SH STICK=$SERIAL GIT_CEILING_DIRECTORIES=$T bash "$SR/tools/stick-test.sh" "$@" ) > "$T/out" 2>&1
  RC=$?
}
MK=$SR/dist/cleanframe-tv-$SHA.$SIDX.staging-ok; RMK=$SR/dist/cleanframe-tv-$SHA.$SIDX.recovery-ok   # stick X's
YMK=$SR/dist/cleanframe-tv-$SHA.$SIDY.staging-ok; YRMK=$SR/dist/cleanframe-tv-$SHA.$SIDY.recovery-ok   # stick Y's
APKSUM=cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333
no_adb_calls() { [ ! -s "$STK/adb.log" ]; }
no_markers() { [ -z "$(find "$SR/dist" -name '*-ok')" ]; }

stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-staging-pass-writes-marker 0 STICK_TEST_OK eval 'grep -qx "sha=$SHA" "$MK" && grep -qx "package=home.cleanframe.tv.staging" "$MK" && grep -qx "apk_sha256=$APKSUM" "$MK" && grep -qx "stick_id=$SIDX" "$MK" && grep -qx "stick=$SERIAL" "$MK" && has "PASS  installed APK is the one build.sh recorded: sha256 $APKSUM" && not has "FAIL "'
# Every feature section asked the page first, in order (none bypassed by a typo in its id), and a
# good build SKIPs none of them.
check stick-feature-probes-all-ran 0 STICK_TEST_OK eval '[ "$(tr "\n" " " < "$STK/feature.log")" = "extras sleep hold appedit uninstall photogroups upload rotation bluetooth btunpair " ] && not has "app predates"'
# Per stick: a run on stick Y (same address) writes Y's marker and leaves X's alone, both ways; a stick
# whose identity cannot be read is no run at all (nothing written or deleted).
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo "sha=$SHA" > "$MK"; echo FakeBox > "$STK/model"; echo YS0002 > "$STK/serialno"; run_stick --staging
check stick-pass-on-y-leaves-x-marker 0 "promotion marker: dist/cleanframe-tv-$SHA.$SIDY.staging-ok" \
  eval 'grep -qx "stick_id=$SIDY" "$YMK" && [ "$(cat "$MK")" = "sha=$SHA" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo "sha=$SHA" > "$MK"; echo "sha=$SHA" > "$YMK"; : > "$STK/serialno"; run_stick --staging
check stick-identity-unreadable-refused nz "FAIL  stick identity: could not read ro.product.model / ro.serialno" \
  eval '[ -e "$MK" ] && [ -e "$YMK" ] && [ "$(find "$SR/dist" -name "*-ok" | wc -l)" = 2 ] && not has "PASS "'

# Photo groups: a good build passes every step (no keyboard: typing SKIPped, the name set through
# DevTools) and deletes its test group; a flat background is switched to Photo, the section runs and
# the type is restored (also when a step aborts mid-section); a switch that does not take FAILs and
# writes no marker; a page without Groups FAILs;
# a delete that did not happen FAILs and the leftover group is still cleaned up and the wallpaper
# restored.
pg_ran() { grep -qx "$1" "$STK/pg.log"; }
has_all() { local x; for x; do has "$x" || return 1; done; }
# Random rotation and Settings > Bluetooth (2026-11-03): a good build passes both sections, Pair new
# device opens Android's pairing page and Back returns; a group page that still ticks the photo while
# rotating, or a pairing page that never opens, FAILs and writes no marker; the rotation the run found
# is put back either way.
rot_ran() { grep -qx "$1" "$STK/rot.log" 2>/dev/null; }
bt_ran() { grep -qx "$1" "$STK/bt.log" 2>/dev/null; }
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-rotation-and-bluetooth-good-build 0 STICK_TEST_OK eval 'has_all "PASS  rotation: turned on, another photo of the group at once: misty -> wave (scenery, 3 photos)" "PASS  rotation: the group page ticks no photo, marks the one on screen: wallpaper|0|0|1|Now showing" "PASS  rotation: put back as found: fixed|misty|photo" "PASS  Pair new device opens Android'"'"'s pairing page: com.android.tv.settings/.accessories.AddAccessoryActivity" "PASS  Back returns to CleanFrame: home.cleanframe.tv.staging/home.cleanframe.tv.MainActivity" "PASS  Settings rows fit above the hint: 9|fits" && bt_ran home && not has "FAIL "'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'wallpaper|1|1|0|Applied' > "$STK/rot_page2"; run_stick --staging
check stick-rotation-photo-ticked-fails nz "FAIL  rotation: the group page ticks no photo, marks the one on screen: got 'wallpaper|1|1|0|Applied'" eval 'rot_ran restored && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo '60|misty|ready' > "$STK/rot_onwall"; run_stick --staging
check stick-rotation-same-photo-fails nz "FAIL  rotation: turned on, another photo of the group at once" eval 'rot_ran restored && no_markers'
# The rotating group's row, read once focused, that does not say Rotating: FAIL.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'no: Scenery · 15 photos' > "$STK/rot_grouprot"; run_stick --staging
check stick-rotation-group-not-rotating-fails nz "FAIL  rotation: the group's row says Rotating: got 'no: Scenery · 15 photos'" eval 'rot_ran restored && no_markers'
# A page that never settles (rot_ready's 20 polls all "pending") is a FAIL, never read as ready: on
# turning rotation on (the wallpaper it shows is not the one it will settle on), and while putting the
# rotation back (which is still attempted to the end).
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo '60|wave|pending' > "$STK/rot_onwall"; run_stick --staging
check stick-rotation-never-settles-fails nz "FAIL  rotation: the page settled (onwall): got '60|wave|pending'" \
  eval '[ "$(grep -cx onwall "$STK/rot.log")" = 20 ] && rot_ran restored && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'fixed|misty|pending' > "$STK/rot_restoredwall"; run_stick --staging
check stick-rotation-restore-never-settles-fails nz "FAIL  rotation: put back as found: got 'unsettled(restoredwall): fixed|misty|pending; fixed|misty|photo'" \
  eval 'rot_ran restored && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'Checking…' > "$STK/bt_row"; run_stick --staging
check stick-bluetooth-row-unanswered-fails nz "FAIL  Bluetooth row: the stick's answer: got 'Checking…'" eval 'no_markers'
# Unpair (2026-11-03): never carried out. A good build reports canUnpair, refuses a token that does not
# exist, says why a remote stays, prints how many devices could go, and leaves the question by Back, never
# by OK, whatever the focus reads. A missing answer FAILs and writes no marker; every device unpairable, or
# one named like a remote, FAILs (every stick here has a paired remote); a device with no unpair true SKIPs
# that step, with its reason.
bt_key() { [ "$(cat "$STK/ask_key" 2>/dev/null)" = "$1" ]; }
BT_REFUSED="Remotes and other devices stay paired here so the TV can't lose its remote. Use TV settings to remove them."
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-unpair-good-build 0 STICK_TEST_OK eval 'has_all "PASS  Bluetooth reports canUnpair: true" "PASS  an unknown device is refused: That device is not paired here any more." "PASS  a device that stays paired says why: message|$BT_REFUSED" "PASS  the question starts on No: message|message.no|asks" "PASS  Back leaves it paired: appearance|kept" "PASS  Bluetooth unpairable devices: 1 of 2" "PASS  no device named like a remote is unpairable" && bt_key BACK && not has "SKIP  the question" && not has "FAIL "'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo undefined > "$STK/bt_canunpair"; run_stick --staging
check stick-unpair-no-canunpair-fails nz "FAIL  Bluetooth reports canUnpair: got 'undefined'" eval 'no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; : > "$STK/bt_noid"; run_stick --staging
check stick-unpair-unknown-id-accepted-fails nz "FAIL  an unknown device is refused: got ''" eval 'no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'message|Unpair Xiaomi RC?' > "$STK/bt_refused"; run_stick --staging
check stick-unpair-remote-not-refused-fails nz "FAIL  a device that stays paired says why: got 'message|Unpair Xiaomi RC?'" eval 'no_markers'
# The focus reads Yes: FAIL, and the key is Back, never OK.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'message|message.yes|asks' > "$STK/bt_ask"; run_stick --staging
check stick-unpair-focus-on-yes-never-ok nz "FAIL  the question starts on No: got 'message|message.yes|asks'" eval 'bt_key BACK && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo gone > "$STK/bt_kept"; run_stick --staging
check stick-unpair-device-gone-fails nz "FAIL  Back leaves it paired: got 'gone'" eval 'no_markers'
# All devices unpairable, or one named like a remote: FAIL, never a SKIP. None paired at all says nothing
# about a remote; the count is printed either way; no count at all FAILs.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo '3|3|' > "$STK/bt_counts"; : > "$STK/bt_norow"; run_stick --staging
check stick-unpair-all-unpairable-fails nz "FAIL  the remote looks unpairable: got '3 of 3 devices'" eval 'no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo '1|3|Xiaomi RC' > "$STK/bt_counts"; run_stick --staging
check stick-unpair-named-rc-fails nz "FAIL  a remote by name is unpairable: got 'Xiaomi RC'" eval 'has "PASS  Bluetooth unpairable devices: 1 of 3" && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo '1|3|MG4,Other' > "$STK/bt_counts"; run_stick --staging
check stick-unpair-named-mg4-fails nz "FAIL  a remote by name is unpairable: got 'MG4,Other'" eval 'no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo '0|0|' > "$STK/bt_counts"; : > "$STK/bt_norow"; : > "$STK/bt_yesrow"; run_stick --staging
check stick-unpair-none-paired-passes 0 STICK_TEST_OK eval 'has "PASS  Bluetooth unpairable devices: 0 of 0"'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo undefined > "$STK/bt_counts"; run_stick --staging
check stick-unpair-no-count-fails nz "FAIL  Bluetooth unpairable devices: got 'undefined'" eval 'no_markers'
# Nothing can go here: no question is opened, and the run still passes.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; : > "$STK/bt_yesrow"; run_stick --staging
check stick-unpair-none-unpairable-skips 0 STICK_TEST_OK eval 'has "SKIP  the question on a device that could go: no device can be unpaired here (canUnpair true)" && not bt_ran ask'
# A TV that hides removeBond: the page says to use TV settings, on every device.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo false > "$STK/bt_canunpair"; : > "$STK/bt_yesrow"; echo "message|This TV can't unpair from here. Use TV settings to remove it." > "$STK/bt_refused"; run_stick --staging
check stick-unpair-canunpair-false-passes 0 STICK_TEST_OK eval 'has_all "PASS  Bluetooth reports canUnpair: false" "SKIP  the question on a device that could go: no device can be unpaired here (canUnpair false)" && not bt_ran ask'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo false > "$STK/bt_canunpair"; : > "$STK/bt_yesrow"; run_stick --staging
check stick-unpair-canunpair-false-wrong-words-fails nz "FAIL  a device that stays paired says why" eval 'no_markers'
# A page without Unpair, on a commit not shown to lack it: FAIL, and none of its steps run.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'absent:window.CleanFrameBluetooth has no unpair' > "$STK/feature_btunpair"; run_stick --staging
check stick-unpair-absent-from-this-commit-fails nz "FAIL  Bluetooth Unpair: in the page: got 'window.CleanFrameBluetooth has no unpair'" eval 'not bt_ran canunpair && no_markers'
# A real unpair must not exist in stick-test.sh: no call but the made-up token, no Left in the section
# (Yes is to the left of where the question starts), no OK from the moment the question is read to the
# end of the section (a real remote press could have moved the focus to Yes), nothing naming Yes.
BT_SEC=$(sed -n '/^if feature btunpair/,/^fi   # feature btunpair/p' "$ROOT/tools/stick-test.sh")
BT_CALLS=$(grep -oE '[A-Za-z_.]*(bluetoothUnpair|[Uu]npair)\([^)]*\)' "$ROOT/tools/stick-test.sh" | sort -u | tr '\n' ' ')
if [ -z "$BT_SEC" ]; then fail stick-test-never-unpairs "no feature btunpair section in tools/stick-test.sh"
elif [ "$BT_CALLS" != 'window.AndroidHome.bluetoothUnpair("no-such-id") ' ]; then fail stick-test-never-unpairs "unpair calls other than the made-up token: $BT_CALLS"
elif [[ $BT_SEC == *DPAD_LEFT* || $BT_SEC == *KEYCODE_DPAD* ]]; then fail stick-test-never-unpairs "a Left key in the Unpair section: Yes is to the left of No"
elif [[ ${BT_SEC#*bt ask } == *DPAD_CENTER* ]]; then fail stick-test-never-unpairs "OK while the question is open: it could be a real Yes"
elif grep -qE 'message\.yes|removeBond|"yes"' "$ROOT/tools/stick-test.sh"; then fail stick-test-never-unpairs "tools/stick-test.sh names Yes or removeBond"
else pass stick-test-never-unpairs; fi

stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-photogroups-good-build 0 "PASS  Background groups: Scenery|Space" eval 'has_all "SKIP  Add group typing: no keyboard" "PASS  Add photo shows a QR and a code: upload|<code>|1" "PASS  the code on screen opens the QR page: 303 to http://10.11.12.13:8097/u/<token>" "PASS  No keeps the photo: photo|1|true" "PASS  Yes deletes the group: appearance|0" "PASS  original wallpaper restored: misty" && pg_ran setname && not has "FAIL "'
# Direct upload (contract 4 v2): the QR's URL is the stick's, curl sends a JPEG with a planted GPS
# tag (made from the Space thumb) and deletes it again; the token is never printed; a refused upload
# FAILs and skips the rest; a URL that is not the stick's own FAILs without calling curl.
up_ok() { has_all "PASS  Add photo URL is the stick's own: http://10.11.12.13:8097/u/<token>" "PASS  the stick serves the code page: 200" \
  "PASS  curl uploads a photo to the stick: pstickup001" "PASS  the upload appears in the group (materials drawn by the page): 1" \
  "PASS  the stick serves its seven files" "PASS  its meta.json is read (name from the upload): Stick upload" \
  "PASS  no planted metadata reached the stick: none|server thumb 200:0" "PASS  a second upload appears too: 1" \
  "PASS  the API deletes the first" "PASS  and it leaves the group: 0" "PASS  closing Add photo stops the upload server: connection refused (curl exit 7)" \
  "PASS  the group is gone from the stick: 0|404" \
  "PASS  its wall is the centred 16:9 of the copy it kept (no crop sent): 1920x1080 match" "PASS  the framed upload lists its crop: 0.25|2|new" \
  "PASS  its wall is the frame it was sent with: 1920x1080 match" "PASS  Adjust re-frames the photo (recrop, then its materials): done|ok" \
  "PASS  the same photo lists its new crop and when it was adjusted: 0.75|2|edited" "PASS  its wall is the new frame: 1920x1080 match" \
  "PASS  Adjust opens the photo full screen by remote: adjust|drawn" "PASS  Back, Back leaves Adjust without saving: wallpaper|photo" \
  && grep -q -- "-F crop=0,0,2" "$STK/curl.log" && pg_ran recrop \
  && pg_ran upwait && pg_ran upgone && pg_ran keepwait && ! grep -q 0123456789abcdef "$T/out" \
  && ! grep -q 482913 "$T/out" \
  && ! grep -q Android/data "$STK/adb.log" \
  && grep -q -- "-F group=gstick001" "$STK/curl.log" && grep -q -- "-X DELETE" "$STK/curl.log" \
  && grep -qx 'url = "http://10.11.12.13:8097/api/0123456789abcdef0123456789abcdef/photos/gstick001/pstickup001"' "$STK/curl-config.log" \
  && grep -qx 'data = "code=482913"' "$STK/curl-config.log" \
  && ! grep -qE '0123456789abcdef|482913' "$STK/curl.log"; }   # neither the token nor the code in any curl argv
check stick-direct-upload-good-build 0 "PASS  curl uploads a photo to the stick" up_ok
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo '{"ok":false,"error":"type","message":"no"}' > "$STK/curl_upload"; run_stick --staging
check stick-direct-upload-refused-fails nz "FAIL  curl uploads a photo to the stick" eval 'not pg_ran upwait && ! grep -q "X DELETE" "$STK/curl.log" && [ ! -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'http://192.168.0.6:8097/u/0123456789abcdef0123456789abcdef' > "$STK/pg_upurl"; run_stick --staging
check stick-direct-upload-wrong-url-fails nz "FAIL  Add photo URL is the stick's own" eval '[ ! -s "$STK/curl.log" ] || ! grep -q -- "-F" "$STK/curl.log"'
# The stick's answers that must FAIL: a planted string served back (page or server thumbnail), a code that
# does not lead to the QR's page, a port still open after Add photo closed, the group's files still served.
for f in 'pg_upleak;wall;no planted metadata reached the stick' 'curl_thumb;PLANTED-GPS;no planted metadata reached the stick' \
         "curl_redirect;403 ;the code on screen opens the QR page" 'curl_closed;closed:200;closing Add photo stops the upload server' \
         'pg_gone;0|200;the group is gone from the stick' 'pg_upfiles;wall:404:x;the stick serves its seven files' \
         'pg_keepwall;1920x1080 differ 31.5;its wall is the frame it was sent with' 'pg_upleak;src;no planted metadata reached the stick' \
         'pg_recropstate;failed:That photo could not be read.;Adjust re-frames the photo' 'pg_keepcrop2;0.25|2|new;the same photo lists its new crop'; do
  IFS=';' read -r FF FV FN <<<"$f"
  stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; printf %s "$FV" > "$STK/$FF"; run_stick --staging
  check "stick-direct-upload-$FF-fails" nz "FAIL  $FN" eval '[ ! -e "$MK" ]'
done
# A closed port is a refused connection (curl exit 7) only: a timeout (28) or a reset (56) prints code 000
# too, and is no proof the server stopped: FAIL, named with curl's exit code.
for rc in 28 56; do
  stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; printf closed:000 > "$STK/curl_closed"; echo "$rc" > "$STK/curl_closed_rc"; run_stick --staging
  check "stick-upload-port-closed-curl-exit-$rc-fails" nz "FAIL  closing Add photo stops the upload server: got 'curl exit $rc, closed:000', want a refused connection (curl exit 7)" \
    eval 'not has "PASS  closing Add photo" && no_markers'
done
# The created group's id must be g + 8 [0-9a-z]: anything else (or none) FAILs instead of silently skipping
# the upload, Adjust, Remove and Delete steps, and the group is still cleaned up, by its name.
for gid in 'G-1;x' ''; do
  stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; printf 'wallpaper|CF stick test|acts|%s' "$gid" > "$STK/pg_created"; run_stick --staging
  check "stick-photogroups-bad-group-id${gid:+-set}-fails" nz "FAIL  the new group's id: got '$gid', want g + 8 of [0-9a-z]" \
    eval 'pg_ran created && not pg_ran upload && not pg_ran deleted && pg_ran cleanupname && not pg_ran cleanup && pg_ran restored && no_markers'
done
# The keyboard check: the literal null is no keyboard (SKIP); an adb read that fails (empty) is a FAIL at
# both places that ask (Rename typing, Add group typing), never a SKIP; the rest still runs.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; : > "$STK/ime_fail"; run_stick --staging
check stick-ime-read-failure-fails nz "FAIL  Rename typing: default input method read: got '', want an input method or null" \
  eval 'has "FAIL  Add group typing: default input method read: got '"''"'" && not has "SKIP  Rename typing" && not has "SKIP  Add group typing" && pg_ran setname && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'flat@misty' > "$STK/pg_open"; run_stick --staging
check stick-photogroups-flat-switched-and-run 0 "PASS  Background switched to Photo for the photo groups: photo" \
  eval 'has_all "PASS  Background groups: Scenery|Space" "PASS  Yes deletes the group: appearance|0" "PASS  original wallpaper restored: misty" "PASS  original Background type restored: dark" && pg_ran created && pg_ran restorebg && [ -e "$MK" ] && not has "FAIL " && not has "SKIP  photo"'
# the type is restored after the wallpaper (the wallpaper is set while Photo is still on)
check stick-photogroups-flat-restore-order 0 STICK_TEST_OK eval '[ "$(grep -nxE "restored|restorebg" "$STK/pg.log" | tail -1)" = "$(grep -nx restorebg "$STK/pg.log")" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'flat@misty' > "$STK/pg_open"; echo dark > "$STK/pg_tophoto"; run_stick --staging
check stick-photogroups-flat-switch-fails nz "FAIL  Background switched to Photo for the photo groups: got 'dark|misty|ready', want 'photo|misty|ready' within 20 s" \
  eval 'has "FAIL  photo groups: got '"'Background is still not Photo"'" && not pg_ran created && pg_ran restorebg && no_markers && [ "$(grep -cx tophotowait "$STK/pg.log")" = 20 ]'
# The switch takes a few polls on a fresh page (seen on the stick): 5 pending polls still pass; one that
# never lands within LOOK_WAIT polls FAILs (a bounded wait, not a hang); so does a restore that never lands.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'flat@misty' > "$STK/pg_open"; LOOK_POLLS=5 run_stick --staging
check stick-photogroups-flat-slow-switch-passes 0 "PASS  Background switched to Photo for the photo groups: photo" eval 'has "PASS  original Background type restored: dark" && [ -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'flat@misty' > "$STK/pg_open"; LOOK_POLLS=50 run_stick --staging
check stick-photogroups-flat-switch-timeout-fails nz "FAIL  Background switched to Photo for the photo groups: got 'dark|misty|pending', want 'photo|misty|ready' within 20 s" no_markers
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'flat@misty' > "$STK/pg_open"; echo 'photo|misty|ready' > "$STK/pg_restorebgwait"; run_stick --staging
check stick-photogroups-bg-restore-timeout-fails nz "FAIL  original Background type restored: got 'photo|misty|ready'" no_markers
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'flat@misty' > "$STK/pg_open"; echo DT_ERROR > "$STK/pg_bg"; run_stick --staging
check stick-photogroups-bg-unreadable-fails nz "FAIL  Background type read: got 'DT_ERROR'" eval 'not pg_ran tophoto && not pg_ran restorebg && no_markers'
# A step that aborts mid-section (the keys die after the group is created): the EXIT trap still
# deletes the test group and restores the wallpaper and the Background type; no marker.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'flat@misty' > "$STK/pg_open"; echo created > "$STK/keys_die_after"; run_stick --staging
check stick-photogroups-abort-restores nz "STICK_TEST_FAIL aborted" \
  eval 'pg_ran cleanup && pg_ran restored && has "PASS  original Background type restored: dark" && not pg_ran deleted && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'nogroups@misty' > "$STK/pg_open"; run_stick --staging
check stick-photogroups-no-groups-fails nz "FAIL  Background groups: got 'nogroups'" eval 'has "STICK_TEST_FAIL 1" && [ ! -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'appearance|1' > "$STK/pg_deleted"; run_stick --staging
check stick-photogroups-failed-delete-cleans-up nz "FAIL  Yes deletes the group: got 'appearance|1'" eval 'pg_ran cleanup && pg_ran restored && [ ! -e "$MK" ]'
# Home additions (/*hx:<step>*/, web/home-extras.js): a good build passes and the clock options the
# run found are put back; a dock app still marked after Up FAILs with no marker; a step that aborts
# after the options were reset (the keys die): the EXIT trap still puts them back.
hx_ran() { grep -qx "$1" "$STK/hx.log"; }
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-home-extras-good-build 0 "PASS  UP: no dock app stays marked: 0|util" \
  eval 'has_all "PASS  Settings rows fit above the hint: 9|fits" "PASS  Clock page, focus on the applied format: clock|pref.clockFormat.12" "PASS  Home clock is 24-hour: 24h" "PASS  clock options put back: restored" && [ -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo '1|util' > "$STK/hx_marked"; run_stick --staging
check stick-home-extras-marked-fails nz "FAIL  UP: no dock app stays marked: got '1|util'" eval 'has "STICK_TEST_FAIL 1" && [ ! -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo reset > "$STK/keys_die_after"; run_stick --staging
check stick-home-extras-abort-restores nz "STICK_TEST_FAIL aborted" eval 'hx_ran reset && hx_ran restore && not hx_ran page && no_markers'
# Screen and Sleep: the good build passes (the Sleep button saves Sleep after and the stick's timeout
# follows; Sleep now writes 15 s, Sleep after kept, and a key puts it back); a timeout the app did not
# write FAILs; a Sleep now a key does not cancel FAILs; a step that aborts after Sleep after was
# changed, or during Sleep now: the EXIT trap puts Sleep after back / cancels Sleep now.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-screen-sleep-good-build 0 "PASS  screen-off timeout is Dim after + Sleep after: 2100000" \
  eval 'has_all "PASS  Dim after list: applied value, Back says Screen: screenDim|true|Screen" "PASS  the first key only brightens: bright|home.app.a" "PASS  Sleep opens on the applied Sleep after: message|Sleep|message.30" "PASS  Sleep now is the first button: message.now" "PASS  the Sleep button saves Sleep after: 60|none" "PASS  and the stick'"'"'s timeout follows: 3900000" "PASS  Sleep after put back: 30" "PASS  Sleep now: Home black, Sleep after kept: black|30" "PASS  Sleep now: the stick'"'"'s timeout is 15 s: 15000" "PASS  the next key only wakes Home: bright|30" "PASS  Sleep now: the timeout is Sleep after'"'"'s again: 2100000" && not hx_ran napoff && [ -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 1800000 > "$STK/timeout"; run_stick --staging
check stick-screen-timeout-not-written-fails nz "FAIL  screen-off timeout is Dim after + Sleep after: got '1800000'" eval 'has "STICK_TEST_FAIL 1" && [ ! -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'black|30' > "$STK/hx_napwake"; run_stick --staging
check stick-sleep-now-not-cancelled-fails nz "FAIL  the next key only wakes Home: got 'black|30'" eval 'has "FAIL  Sleep now: the timeout is Sleep after'"'"'s again: got '"'15000'"'" && [ ! -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo sleepfirst > "$STK/keys_die_after"; run_stick --staging
check stick-sleep-abort-puts-back nz "STICK_TEST_FAIL aborted" eval 'hx_ran sleepfirst && hx_ran sleepback && not hx_ran sleepsaved && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo nap > "$STK/keys_die_after"; run_stick --staging
check stick-sleep-now-abort-cancels nz "STICK_TEST_FAIL aborted" eval 'hx_ran nap && hx_ran napoff && not hx_ran napwake && no_markers'
# Long press: the good build passes the menu, Move and App info checks; a hold that opens nothing
# FAILs; an App info that does not open Android's page FAILs.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-long-press-good-build 0 "PASS  OK held opens the app menu: message|message.move" \
  eval 'has_all "PASS  Move picks the app up: editor|a" "PASS  Back twice: dock unchanged: home|a,b,c" "PASS  App info names the app: Emby (com.mb.android)" && has "PASS  App info opens Android'"'"'s page: com.android.tv.settings/" && [ -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'home|home.app.a' > "$STK/hx_hold"; run_stick --staging
check stick-long-press-no-menu-fails nz "FAIL  OK held opens the app menu: got 'home|home.app.a'" eval 'has "STICK_TEST_FAIL" && [ ! -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo message.info > "$STK/hx_infofocus"; run_stick --staging
check stick-long-press-no-app-info-fails nz "FAIL  App info opens Android's page" eval 'has "FAIL  App info names the app" && [ ! -e "$MK" ]'
# App info drawn late (2026-11-01: an early dump had only empty text= nodes): it is looked at again
# and passes; never drawn within the 5 looks: FAIL.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 2 > "$STK/ui_late"; run_stick --staging
check stick-app-info-drawn-late-passes 0 "PASS  App info names the app: Emby (com.mb.android)" eval 'has STICK_TEST_OK && [ "$(grep -c "shell uiautomator" "$STK/adb.log")" = 4 ] && [ -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 5 > "$STK/ui_late"; run_stick --staging
check stick-app-info-never-drawn-fails nz "FAIL  App info names the app" eval 'has "STICK_TEST_FAIL 1" && [ ! -e "$MK" ]'
# An app renamed in CleanFrame (Sky Glass+ as IPTV, Xiaomi 2026-11-03): Android's page keeps the app's own
# name, so the package on the page names it; a page for another package FAILs.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo IPTV > "$STK/hx_title"; run_stick --staging
check stick-app-info-renamed-app-passes 0 "PASS  App info names the app: IPTV (com.mb.android)" eval 'has STICK_TEST_OK && [ -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo IPTV > "$STK/hx_title"; echo com.other.app > "$STK/hx_pkg"; run_stick --staging
check stick-app-info-other-app-fails nz "FAIL  App info names the app" eval 'has "STICK_TEST_FAIL 1" && [ ! -e "$MK" ]'
# Uninstall on the long-press menu. THE RULE: stick-test.sh opens Android's dialog and leaves it with BACK.
# The fake stick records every key from the menu on, and un_confirmed if OK lands inside the dialog.
un_ran() { grep -qx "$1" "$STK/un.log" 2>/dev/null; }
un_keys() { [ "$(tr '\n' ' ' < "$STK/un_keys" 2>/dev/null)" = "$1" ]; }
un_safe() { [ ! -e "$STK/un_confirmed" ]; }
UN_MENU_YES='message|message.move|message.move,message.rename,message.icon,message.remove,message.info,message.uninstall,message.back'
UN_MENU_NO='message|message.move|message.move,message.rename,message.icon,message.remove,message.info,message.back'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-uninstall-good-build 0 STICK_TEST_OK eval 'has_all "PASS  CleanFrame cannot be uninstalled from its menu: false" "PASS  a system package cannot be uninstalled from the menu: false" "PASS  the menu has Uninstall only if the TV says so (com.mb.android: true)" "PASS  Uninstall focused: message.uninstall" "PASS  Uninstall opens Android'"'"'s dialog: com.android.packageinstaller/.UninstallerActivity" "PASS  Back changes nothing: home|a,b,c|listed" "PASS  the app is still installed: com.mb.android" && un_safe && un_keys "DPAD_CENTER BACK " && [ -e "$MK" ]'
# An app the TV will not let go: no Uninstall on the menu, the dialog step SKIPs saying why, no OK at all.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'emby|com.mb.android|false' > "$STK/un_app"; echo "$UN_MENU_NO" > "$STK/un_menu"; run_stick --staging
check stick-uninstall-system-app-skips 0 STICK_TEST_OK eval 'has "SKIP  Uninstall opens Android'"'"'s dialog: the TV will not let com.mb.android go" && un_safe && un_keys "BACK " && not un_ran focus && [ -e "$MK" ]'
# The menu disagrees with canUninstall, either way: FAIL, and no OK is sent on that menu.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'emby|com.mb.android|false' > "$STK/un_app"; run_stick --staging
check stick-uninstall-shown-when-refused-fails nz "FAIL  the menu has Uninstall only if the TV says so (com.mb.android: false)" eval 'un_keys "BACK " && un_safe && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo "$UN_MENU_NO" > "$STK/un_menu"; run_stick --staging
check stick-uninstall-missing-when-allowed-fails nz "FAIL  the menu has Uninstall only if the TV says so (com.mb.android: true)" eval 'un_keys "BACK " && not un_ran focus && un_safe && no_markers'
# CleanFrame or a system package offered for uninstall FAILs; so does an unreadable answer.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo true > "$STK/un_self"; run_stick --staging
check stick-uninstall-cleanframe-allowed-fails nz "FAIL  CleanFrame cannot be uninstalled from its menu: got 'true'" eval 'un_safe && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo true > "$STK/un_system"; run_stick --staging
check stick-uninstall-system-allowed-fails nz "FAIL  a system package cannot be uninstalled from the menu: got 'true'" eval 'un_safe && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; : > "$STK/un_app"; run_stick --staging
check stick-uninstall-app-unreadable-fails nz "FAIL  Uninstall: the selected app" eval 'not un_ran menu && un_safe && no_markers'
# Focus that never reaches Uninstall: FAIL, and OK is never sent.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo message.back > "$STK/un_focus"; run_stick --staging
check stick-uninstall-focus-never-there-never-ok nz "FAIL  Uninstall focused: got 'message.back'" eval 'un_safe && ! grep -qx DPAD_CENTER "$STK/un_keys" && no_markers'
# The dialog does not open; the app gone or the order changed after Back; pm no longer has it: FAIL.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; touch "$STK/un_nodialog"; run_stick --staging
check stick-uninstall-no-dialog-fails nz "FAIL  Uninstall opens Android's dialog" eval 'un_safe && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'home|a,b,c|gone' > "$STK/un_kept"; run_stick --staging
check stick-uninstall-gone-after-back-fails nz "FAIL  Back changes nothing: got 'home|a,b,c|gone'" eval 'no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'home|b,c|listed' > "$STK/un_kept"; run_stick --staging
check stick-uninstall-order-changed-fails nz "FAIL  Back changes nothing: got 'home|b,c|listed'" eval 'no_markers'
# The appedit section counts Uninstall when the app has it: seven buttons, Uninstall before Cancel.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo can > "$STK/hx_aeun"; echo "$UN_MENU_YES" > "$STK/hx_aemenu"; echo '7|fits' > "$STK/hx_aefit"; run_stick --staging
check stick-app-edit-menu-with-uninstall-passes 0 "PASS  the menu's buttons fit on the stage: 7|fits" eval 'has STICK_TEST_OK && [ -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo can > "$STK/hx_aeun"; run_stick --staging
check stick-app-edit-menu-without-uninstall-fails nz "FAIL  OK held: the menu has Rename and Change icon" eval 'no_markers'
# A page without Uninstall, on a commit not shown to lack it: FAIL, and none of its steps run.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'absent:window.FrameDevice has no canUninstall' > "$STK/feature_uninstall"; run_stick --staging
check stick-uninstall-absent-from-this-commit-fails nz "FAIL  Uninstall: in the page: got 'window.FrameDevice has no canUninstall'" eval 'not un_ran self && no_markers'
# A real uninstall must not exist in stick-test.sh: no uninstall call of any kind in the file; exactly one OK
# in the section and none after the step that opens the dialog; no other key that could press a button.
UN_SEC=$(sed -n '/^if feature uninstall/,/^fi   # feature uninstall/p' "$ROOT/tools/stick-test.sh")
UN_AFTER=${UN_SEC#*# un-dialog:}
UN_CALLS=$(grep -nE '\.uninstall\(|(pm|adb|package|a) +uninstall|-delete-package|ACTION_DELETE|ACTION_UNINSTALL' "$ROOT/tools/stick-test.sh" || true)
UN_OKS=$(grep -c DPAD_CENTER <<<"$UN_SEC" || true)
if [ -z "$UN_SEC" ] || [ "$UN_AFTER" = "$UN_SEC" ]; then fail stick-test-never-uninstalls "no feature uninstall section with its un-dialog step in tools/stick-test.sh"
elif [ -n "$UN_CALLS" ]; then fail stick-test-never-uninstalls "an uninstall call in tools/stick-test.sh: $UN_CALLS"
elif [[ $UN_AFTER == *DPAD_CENTER* ]]; then fail stick-test-never-uninstalls "OK after the dialog opened: it could be a real uninstall"
elif [[ $UN_AFTER == *ENTER* || $UN_AFTER == *BUTTON_* || $UN_AFTER == *DPAD_LEFT* || $UN_AFTER == *DPAD_RIGHT* || $UN_AFTER == *DPAD_UP* || $UN_AFTER == *DPAD_DOWN* || $UN_AFTER == *"input tap"* || $UN_AFTER == *"input text"* ]]; then fail stick-test-never-uninstalls "a key other than BACK after the dialog opened"
elif [ "$UN_OKS" != 1 ]; then fail stick-test-never-uninstalls "$UN_OKS OKs in the section, want the one that opens the dialog"
else pass stick-test-never-uninstalls; fi

# Rename and Change icon: the good build passes (renamed and back by remote, IPTV and back, the menu fits)
# and the overrides are put back; a name that did not reach the tile, an IPTV mark the tile does not
# draw, a menu without the new buttons or one that overflows FAIL; an unreadable start FAILs and changes
# nothing; a step that aborts after the overrides were cleared: the EXIT trap still puts them back.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-app-edit-good-build 0 "PASS  Save renames the dock app, order unchanged: home|CF Test TV|CF Test TV|CF Test TV|a,b,c" \
  eval 'has_all "PASS  OK held: the menu has Rename and Change icon: message|message.move|message.move,message.rename,message.icon,message.remove,message.info,message.back" "PASS  the menu'"'"'s buttons fit on the stage: 6|fits" "PASS  Original name restores the name: home|Emby|Emby|none|a,b,c" "PASS  the dock tile draws the IPTV mark: home|iptv|iptv|a,b,c" "PASS  with no choice the dock tile draws its package'"'"'s mark: home|none|emby|a,b,c" "PASS  App'"'"'s own icon chosen for the run: ok" "PASS  App'"'"'s own icon draws the app'"'"'s own icon again: home|own|emby|a,b,c" "PASS  app overrides put back: restored" "SKIP  Rename typing" && [ -e "$MK" ]'
for f in 'aesaved;home|Emby|Emby|CF Test TV|a,b,c;Save renames the dock app, order unchanged' 'aeiptv;home|iptv|emby|a,b,c;the dock tile draws the IPTV mark' \
         'aemenu;message|message.move|message.move,message.remove,message.info,message.back;OK held: the menu has Rename and Change icon' \
         'aefit;6|overflows;the menu'"'"'s buttons fit on the stage' 'aeorigname;home|CF Test TV|CF Test TV|CF Test TV|a,b,c;Original name restores the name' \
         'aeown;home|iptv|iptv|a,b,c;App'"'"'s own icon draws the app'"'"'s own icon again' 'aeown;home|none|emby|a,b,c;App'"'"'s own icon draws the app'"'"'s own icon again' \
         'aeauto;home|none|monogram|a,b,c;with no choice the dock tile draws its package'"'"'s mark' 'aeownset;failed;App'"'"'s own icon chosen for the run' 'aesaved;home|CF Test TV|CF Test TV|CF Test TV|b,a,c;Save renames the dock app, order unchanged'; do
  IFS=';' read -r FF FV FN <<<"$f"; AE_N=$((${AE_N:-0} + 1))
  stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; printf %s "$FV" > "$STK/hx_$FF"; run_stick --staging
  check "stick-app-edit-$AE_N-$FF-fails" nz "FAIL  $FN: got '$FV'" eval 'hx_ran aerestore && [ ! -e "$MK" ]'
done
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo DT_ERROR > "$STK/hx_aeorig"; run_stick --staging
check stick-app-edit-unreadable-fails nz "FAIL  app overrides read: got 'DT_ERROR'" eval 'not hx_ran aereset && not hx_ran aerestore && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo aemenu > "$STK/keys_die_after"; run_stick --staging
check stick-app-edit-abort-puts-back nz "STICK_TEST_FAIL aborted" eval 'hx_ran aereset && hx_ran aerestore && not hx_ran aename && no_markers'
# The pairing code on the Add photo screen is never printed (nor is the token): a FAIL of that
# step redacts it too.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'upload|482913|0' > "$STK/pg_upload"; run_stick --staging
check stick-pairing-code-never-printed nz "FAIL  Add photo shows a QR and a code: got 'upload|<code>|0'" eval '! grep -q 482913 "$T/out" && no_markers'

# Feature sections the app predates: tools/rollback.sh keeps this HEAD script while the app goes back
# (rubric v1 review at 687edfd: a rollback to before acee9ec could never pass --staging). A rollback
# to acee9ec^ restores a web/ without app-edit.js: the page says so, the tested commit's own web/
# agrees (git, through stick_git), so that section is SKIPped with the reason, nothing in it runs,
# every other section does, and the pass writes the marker. The same for a photo-groups build from
# before photos went straight to the stick (e782aec^: no FrameDevice.materialJobs). A feature the
# page lacks but its commit has, a script loaded without its API, a probe that does not answer, or a
# SHA that is not a commit here: FAIL, the section not run, no marker. (A feature the page HAS runs
# its section and still FAILs on any misbehaviour: the stick-app-edit-*-fails cases above.)
# stick_git: the fake stick's repo sees this repo's commits (objects read-only through alternates, no
# refs), so stick-test.sh's commit_lacks can read a real SHA's web/.
stick_git() { git init -q -b main "$SR" && echo "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)/objects" > "$SR/.git/objects/info/alternates"; }
fe_ran() { grep -qx "$1" "$STK/feature.log" 2>/dev/null; }
mk_of() { echo "$SR/dist/cleanframe-tv-$1.$SIDX.staging-ok"; }
PRE_AE=$(git -C "$ROOT" rev-parse -q --verify 'acee9ec^' | cut -c1-12)
PRE_UP=$(git -C "$ROOT" rev-parse -q --verify 'e782aec^' | cut -c1-12)
AE12=$(git -C "$ROOT" rev-parse -q --verify 'acee9ec^{commit}' | cut -c1-12)
if [[ $PRE_AE$PRE_UP$AE12 =~ ^[0-9a-f]{36}$ ]]; then
  stick_fresh home.cleanframe.tv.staging "0.1+$PRE_AE"; stick_git; echo 'absent:the page loads no app-edit.js' > "$STK/feature_appedit"; run_stick --staging
  check stick-feature-predated-skips-and-marks 0 "SKIP  Rename and Change icon: app predates Rename and Change icon (the page loads no app-edit.js; $PRE_AE's web/ has none either)" \
    eval 'has STICK_TEST_OK && not has "FAIL " && fe_ran appedit && not hx_ran aeorig && hx_ran hold && hx_ran sleepnow && pg_ran created && grep -qx "sha=$PRE_AE" "$(mk_of "$PRE_AE")"'
  stick_fresh home.cleanframe.tv.staging "0.1+$PRE_UP"; stick_git; echo 'absent:window.FrameDevice has no materialJobs' > "$STK/feature_upload"; run_stick --staging
  check stick-feature-predated-photo-upload-skipped 0 "SKIP  Photo groups straight to the stick: app predates Photo groups straight to the stick (window.FrameDevice has no materialJobs; $PRE_UP's web/ has none either)" \
    eval 'has STICK_TEST_OK && not has "FAIL " && fe_ran photogroups && [ ! -e "$STK/pg.log" ] && [ -e "$(mk_of "$PRE_UP")" ]'
  stick_fresh home.cleanframe.tv.staging "0.1+$AE12"; stick_git; echo 'absent:the page loads no app-edit.js' > "$STK/feature_appedit"; run_stick --staging
  check stick-feature-missing-but-commit-has-it-fails nz "FAIL  Rename and Change icon: in the page: got 'the page loads no app-edit.js', want the feature ($AE12's web/ has it)" \
    eval 'not hx_ran aeorig && not has "SKIP  Rename" && no_markers'
else fail stick-feature-setup "could not resolve acee9ec^, e782aec^ or acee9ec in $ROOT ('$PRE_AE' '$PRE_UP' '$AE12')"; fi
# git grep failing (an unreadable blob, exit 128) is no answer, not "the commit lacks it": FAIL, never a SKIP. Both
# reads: the shim fails only the grep naming $GIT_BAD.
GB=$T/gitbadbin; mkdir -p "$GB"; REAL_GIT=$(command -v git); export REAL_GIT
cat > "$GB/git" <<'EOF'
#!/bin/bash
if [ "$1" = grep ] && [[ " $* " == *"$GIT_BAD"* ]]; then exit 128; fi
exec "$REAL_GIT" "$@"
EOF
chmod +x "$GB/git"
if [[ $PRE_AE$PRE_UP =~ ^[0-9a-f]{24}$ ]]; then
  stick_fresh home.cleanframe.tv.staging "0.1+$PRE_AE"; stick_git; echo 'absent:the page loads no app-edit.js' > "$STK/feature_appedit"
  GIT_BAD=web/index.html PATH=$GB:$PATH run_stick --staging
  check stick-feature-git-error-on-index-fails nz "(git could not read $PRE_AE's web/)" eval 'not has "SKIP  Rename" && no_markers'
  stick_fresh home.cleanframe.tv.staging "0.1+$PRE_UP"; stick_git; echo 'absent:window.FrameDevice has no materialJobs' > "$STK/feature_upload"
  GIT_BAD=web/device.js PATH=$GB:$PATH run_stick --staging
  check stick-feature-git-error-on-member-fails nz "(git could not read $PRE_UP's web/)" eval 'not has "SKIP  Photo groups straight" && no_markers'
fi
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'absent:the page loads no app-edit.js' > "$STK/feature_appedit"; run_stick --staging
check stick-feature-missing-unknown-commit-fails nz "want the feature, or a clean commit here that predates it ('$SHA' is not one)" \
  eval 'not hx_ran aeorig && not has "SKIP  Rename" && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 'broken:app-edit.js is loaded but window.CleanFrameAppEdit is undefined' > "$STK/feature_appedit"; run_stick --staging
check stick-feature-broken-fails nz "FAIL  Rename and Change icon: in the page: got 'broken:app-edit.js is loaded but window.CleanFrameAppEdit is undefined'" \
  eval 'not hx_ran aeorig && not has "SKIP  Rename" && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo DT_ERROR > "$STK/feature_photogroups"; run_stick --staging
check stick-feature-probe-unanswered-fails nz "FAIL  Photo groups: in the page: got 'DT_ERROR'" \
  eval '[ ! -e "$STK/pg.log" ] && not fe_ran upload && no_markers'

# dt: an expression that gives undefined is no answer (DT_ERROR), never a value expect_set/expect_not
# accept (DevTools prints it {"result":{"type":"undefined"}}).
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; : > "$STK/focus_undefined"; run_stick --staging
check stick-undefined-is-no-answer nz "FAIL  initial focus: got 'DT_ERROR', want a value" eval 'has "FAIL  UP focus: got '"'DT_ERROR'"'" && no_markers'
# expect_not: null is no answer either. D-pad moves focus used to pass with focus lost altogether.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; : > "$STK/focus_lost"; run_stick --staging
check stick-focus-lost-is-not-moved nz "FAIL  D-pad moves focus: got '{\"result\":{\"type\":\"object\",\"subtype\":\"null\"" no_markers

stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; printf 'sha=%s\nresult=old\n' "$SHA" > "$MK"
echo "10-31 12:00:01.000  4242  4242 E AndroidRuntime: Process: home.cleanframe.tv.staging, PID: 4242" >> "$STK/logcat"
run_stick --staging
has_all() { local x; for x; do has "$x" || return 1; done; }
no_marker() { [ ! -e "$MK" ]; }
check stick-staging-fail-deletes-old-marker nz "STICK_TEST_FAIL 1" eval 'no_marker && has_all "FAIL  Java crashes: got '"'1'"'"'

# One call hangs, inside a capture that swallows its failure (a deadline of 1 s on every call failed this case
# on a loaded machine: a healthy key press overran it and ended the run before the totals).
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; printf 'sha=%s\n' "$SHA" > "$MK"; echo 'pm path' > "$STK/hang"
t0=$SECONDS; ADB_TIMEOUT=10 run_stick --staging; took=$((SECONDS - t0))
check stick-adb-timeout-fails nz "FAIL  adb calls answered in time" \
  eval 'has_all "FAIL  adb timeout: '"'adb -s $SERIAL shell pm path home.cleanframe.tv.staging'"' gave no answer in 10 s" STICK_TEST_FAIL && no_marker && [ $took -lt 300 ]'

# tools/adb-lib.sh missing: stick-test.sh refuses (exit 2) before any adb call, and says which file.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; rm "$SR/tools/adb-lib.sh"; run_stick --staging
check stick-missing-adb-lib-refused nz "$SR/tools/adb-lib.sh is missing" eval '[ $RC = 2 ] && no_adb_calls && no_marker'

stick_fresh home.cleanframe.tv.staging "0.1+$SHA-dirty"; run_stick --staging
check stick-dirty-staging-no-marker 0 "no promotion marker" eval 'has STICK_TEST_OK && no_markers && not has "installed APK is the one"'

stick_fresh home.cleanframe.tv "0.1+$SHA"; run_stick
check stick-release-pass-no-marker 0 STICK_TEST_OK eval 'no_markers && not has "installed APK is the one"'
# A second stick (the Hugsun HS89, Android 14, 2026-11-02): top() reads Android 14's "ResumedActivity:"
# line, and a stage scaled for another viewport still has to match fit()'s; a wrong scale FAILs.
stick_fresh home.cleanframe.tv "0.1+$SHA"; touch "$STK/a14"; run_stick
check stick-android14-activity-wording 0 "PASS  OK launches the focused app: com.google.android.youtube.tv/.Main" eval 'has STICK_TEST_OK && has "PASS  BACK stays: home.cleanframe.tv/.MainActivity"'
stick_fresh home.cleanframe.tv "0.1+$SHA"; touch "$STK/scale_off"; run_stick
check stick-stage-scale-mismatch-fails nz "FAIL  stage scale fits the viewport: got '\"scale(0.5) vs scale(0.751563)\"'" eval 'has "STICK_TEST_FAIL 1"'
# The launched app's splash opens its next screen a few looks later (Skyglass, 2026-11-01): Home is
# pressed only once the front activity has settled, so BACK still stays on Home.
stick_fresh home.cleanframe.tv "0.1+$SHA"; echo 3 > "$STK/splash"; run_stick
check stick-splash-settles-before-home 0 "PASS  BACK stays: home.cleanframe.tv/.MainActivity" eval 'has STICK_TEST_OK && [ ! -e "$STK/splash_left" ]'

# The tested APK must be the one build.sh recorded: another sha256 on the stick, one that cannot be
# read, or no recorded one is a FAIL and no marker.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444 > "$STK/apk_sha"; run_stick --staging
check stick-apk-sha256-mismatch-fails nz "FAIL  installed APK is the one build.sh recorded: got 'dddd4444" eval 'has "STICK_TEST_FAIL 1" && no_markers'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo none > "$STK/apk_sha"; run_stick --staging
check stick-apk-sha256-unreadable-fails nz "FAIL  installed APK is the one build.sh recorded: got 'no sha256 for /data/app/" no_markers
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; rm "$SR/dist/cleanframe-tv-$SHA-staging.apk.sha256"; run_stick --staging
check stick-apk-sha256-not-recorded-fails nz "want '<no dist/cleanframe-tv-$SHA-staging.apk.sha256>'" no_markers

# --recovery's marker. Its steps (tools/stick-lib.sh) need the stick, so recovery_test is replaced by
# a stub (appended to the copied lib) that passes, FAILs ($STK/recovery_fail) or aborts the run
# ($STK/recovery_abort); finish and the markers are the real ones.
stub_recovery() { cat >> "$SR/tools/stick-lib.sh" <<'EOF'
recovery_test() {   # tools.test.sh stub
  [ ! -f "$STK/recovery_abort" ] || exit 3
  if [ -f "$STK/recovery_fail" ]; then fail "stub recovery" bad good; else pass "stub recovery" ok; fi
}
EOF
}
seed_markers() { printf 'sha=%s\napk_sha256=%s\n' "$SHA" "$APKSUM" > "$MK"; printf 'sha=%s\napk_sha256=old\n' "$SHA" > "$RMK"; }
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; stub_recovery; seed_markers; run_stick --recovery
check stick-recovery-pass-writes-marker 0 "recovery marker: dist/cleanframe-tv-$SHA.$SIDX.recovery-ok" \
  eval 'has STICK_TEST_OK && grep -qx "sha=$SHA" "$RMK" && grep -qx "apk_sha256=$APKSUM" "$RMK" && grep -qx "stick_id=$SIDX" "$RMK" && grep -qx mode=recovery "$RMK" && [ -e "$MK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; stub_recovery; seed_markers; : > "$STK/recovery_fail"; run_stick --recovery
check stick-recovery-fail-deletes-both-markers nz "STICK_TEST_FAIL 1" eval 'has "recovery FAIL: deleted" && no_markers'
# ... only this stick's: a recovery FAIL on X leaves stick Y's markers for the same SHA.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; stub_recovery; seed_markers; echo "sha=$SHA" > "$YMK"; echo "sha=$SHA" > "$YRMK"
: > "$STK/recovery_fail"; run_stick --recovery
check stick-recovery-fail-keeps-other-stick nz "recovery FAIL: deleted" eval '[ ! -e "$MK" ] && [ ! -e "$RMK" ] && [ -e "$YMK" ] && [ -e "$YRMK" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; stub_recovery; seed_markers; : > "$STK/recovery_abort"; run_stick --recovery
check stick-recovery-abort-deletes-both-markers nz "STICK_TEST_FAIL aborted" no_markers
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; stub_recovery; seed_markers; echo none > "$STK/apk_sha"; run_stick --recovery
check stick-recovery-apk-unreadable-fails nz "FAIL  installed APK is the one build.sh recorded" no_markers
stick_fresh home.cleanframe.tv.staging "0.1+$SHA-dirty"; stub_recovery; run_stick --recovery
check stick-recovery-dirty-no-marker 0 "no recovery marker" eval 'has STICK_TEST_OK && no_markers'

# The real recovery_test, every device step stubbed (the driver below), asks the TESTED COMMIT's tree, like
# feature(): at a commit from before 2888814 or ed314d2 that section is SKIPped, named, and no check of it runs
# (a FAIL there deleted that older commit's staging-ok); from them on it runs; a SHA that is no commit here runs too.
cat > "$T/recovery-driver.sh" <<'EOF'
set -uo pipefail
cd "$1"; SHA=$2; PKG=p; ACT=p/.M; S=x; PORT=1; SLOW=0; PAGE=x
clean_sha() { [[ $SHA =~ ^[0-9a-f]{12}$ ]]; }
. tools/stick-lib.sh
k() { :; }; a() { :; }; first() { sed -n 1p; }; dt() { echo true; }; cdp() { echo frozen; }; crash_page() { echo sent; }   # stubs, not copies
home() { SECONDS=$((SECONDS + 10)); }; top() { echo x; }; homepkg() { echo x; }; pidof_pkg() { echo 1; }; yt_pkg() { echo y; }; youtube_front() { echo y; }
early_death() { echo 1; }; ui() { echo '<hierarchy>crashed 3 times in 10 minutes</hierarchy>'; }
pass() { echo "RAN $1"; }; fail() { echo "RAN $1"; }; skip() { echo "SKIP $1: $2"; }; expect() { echo "RAN $1"; }
expect_ui() { echo "RAN $1"; }; expect_no_ui() { echo "RAN $1"; }; expect_page() { echo "RAN $1"; }
recovery_test
EOF
run_rec_driver() { ( cd "$T" && PATH=$BIN:$PATH GIT_CEILING_DIRECTORIES=$T bash "$T/recovery-driver.sh" "$SR" "$1" ) > "$T/out" 2>&1; RC=$?; }
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; stick_git
R_BACK="RAN after the Back key one crash is rebuilt"; R_HOME="RAN home-only hang: one hang is not the fallback"; R_END="RAN live Home in front at the end"
S_BACK="SKIP the Back key clears the counts: app predates it (2888814"; S_HOME="SKIP Home alone finds a hung page: app predates it (ed314d2"
PRE_BK=$(git -C "$ROOT" rev-parse -q --verify '2888814^' | cut -c1-12); PRE_HM=$(git -C "$ROOT" rev-parse -q --verify 'ed314d2^' | cut -c1-12); AT_HM=$(git -C "$ROOT" rev-parse -q --verify 'ed314d2^{commit}' | cut -c1-12)
if [[ $PRE_BK$PRE_HM$AT_HM =~ ^[0-9a-f]{36}$ ]]; then
  run_rec_driver "$PRE_BK"; check stick-recovery-predates-back-key-skips 0 "$S_BACK" eval 'has "$S_HOME" && has "$R_END" && not has "$R_BACK" && not has "$R_HOME"'
  run_rec_driver "$PRE_HM"; check stick-recovery-predates-home-ping-skips 0 "$S_HOME" eval 'has "$R_BACK" && has "$R_END" && not has "$S_BACK" && not has "$R_HOME"'
  run_rec_driver "$AT_HM"; check stick-recovery-has-both-runs-both 0 "$R_HOME" eval 'has "$R_BACK" && has "$R_END" && not has "SKIP the Back" && not has "SKIP Home alone"'
else fail stick-recovery-predates-setup "could not resolve 2888814^, ed314d2^ or ed314d2 in $ROOT"; fi
# git grep failing is no answer: a FAIL, and the sections run, never a SKIP (it used to read as "the commit lacks it").
if [[ $PRE_BK =~ ^[0-9a-f]{12}$ ]]; then
  ( cd "$T" && PATH=$GB:$BIN:$PATH GIT_BAD=FallbackPolicy.java GIT_CEILING_DIRECTORIES=$T bash "$T/recovery-driver.sh" "$SR" "$PRE_BK" ) > "$T/out" 2>&1; RC=$?
  check stick-recovery-git-error-fails-not-skips 0 "RAN read $PRE_BK's FallbackPolicy.java" eval 'has "$R_BACK" && has "$R_HOME" && not has "SKIP the Back" && not has "SKIP Home alone"'
fi
run_rec_driver "$SHA"; check stick-recovery-unknown-commit-runs-both 0 "$R_HOME" eval 'has "$R_BACK" && not has "SKIP the Back" && not has "SKIP Home alone"'
run_rec_driver "$SHA-dirty"; check stick-recovery-dirty-runs-both 0 "$R_HOME" eval 'has "$R_BACK" && not has "SKIP the Back" && not has "SKIP Home alone"'

# Start-up race (2026-11-01 stick run of 6db785b: a fixed sleep 4 after `am start` read DT_ERROR on a
# cold start after install.sh). stick-test.sh polls READY until the page answers: DevTools down for
# the first 3 calls still passes and reports the wait; a page that never gets ready is FAIL  page
# ready, fails the run (that FAIL alone: everything else answers) and writes no marker.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 3 > "$STK/dt_down"; run_stick --staging
check stick-cold-start-waits-for-page 0 "PASS  page ready: after " \
  eval 'has "(4 polls)" && [ "$(grep -c . "$STK/ready.log")" = 4 ] && [ -e "$MK" ] && not has "FAIL "'
# dt does not hide devtools-eval's stderr: it goes to a log kept after the run (path printed), with the
# expression each DT_ERROR was for; a run without DevTools errors leaves no log behind.
# shellcheck disable=SC2034  # read in the eval'd post-condition below
DTL=$(compgen -G "$SH/scratch/cf-stick-devtools.*" || true)
check stick-devtools-stderr-logged 0 "NOTE  devtools errors: " \
  eval '[ -n "$DTL" ] && [ "$(grep -c "fake devtools: down (cold start)" "$DTL")" = 3 ] && [ "$(grep -c "^DT_ERROR for: " "$DTL")" = 3 ] && has "kept in $DTL"'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-no-devtools-errors-no-log 0 STICK_TEST_OK \
  eval 'not has "NOTE  devtools errors" && [ -z "$(compgen -G "$SH/scratch/cf-stick-devtools.*" || true)" ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; : > "$STK/ready_never"; run_stick --staging
check stick-page-never-ready-fails nz "FAIL  page ready: got 'false after " \
  eval 'has "(60 polls)" && has "STICK_TEST_FAIL 1" && no_markers'

# The Back-hold check reads the list through ui() (tools/adb-lib.sh): two empty dumps ("could not
# get idle state") are retried; three (UI_TRIES) are a FAIL, never a pass. (4 dumps in all: the
# Back-hold list's 3 tries, then the long-press App info page's one.)
stick_fresh home.cleanframe.tv "0.1+$SHA"; echo 2 > "$STK/ui_empty"; run_stick
check stick-backhold-retries-empty-dump 0 "PASS  Back-hold shows native list" eval 'has STICK_TEST_OK && [ "$(grep -c "shell uiautomator" "$STK/adb.log")" = 4 ]'
stick_fresh home.cleanframe.tv "0.1+$SHA"; echo 3 > "$STK/ui_empty"; run_stick
check stick-backhold-always-empty-fails nz "FAIL  Back-hold shows native list" has STICK_TEST_FAIL

# Counting: the staging app's crash/ANR, another app's crash and another PID's renderer death are
# not ours when testing the live Home; each of ours is counted once (logs are read twice).
stick_fresh home.cleanframe.tv "0.1+$SHA"
cat >> "$STK/logcat" <<'EOF'
10-31 12:00:01.000  5000  5000 E AndroidRuntime: Process: home.cleanframe.tv.staging, PID: 5000
10-31 12:00:01.000  5001  5001 E AndroidRuntime: Process: com.example.other, PID: 5001
10-31 12:00:02.000  1000  1100 E ActivityManager: ANR in home.cleanframe.tv.staging (home.cleanframe.tv.staging/home.cleanframe.tv.MainActivity)
10-31 12:00:02.000  1000  1100 I am_anr  : [0,5000,home.cleanframe.tv.staging,123,Input dispatching timed out]
10-31 12:00:03.000  777   800 E cr_ChildProcessConn: Renderer process (9999) crash detected
10-31 12:00:04.000  6000  6000 F DEBUG   : >>> com.example.other <<<
EOF
run_stick
check stick-counts-ignore-other-packages 0 STICK_TEST_OK has_all "PASS  Java crashes: 0" "PASS  ANRs: 0" \
  "PASS  renderer deaths: 0" "PASS  native crashes: 0"
stick_fresh home.cleanframe.tv "0.1+$SHA"
cat >> "$STK/logcat" <<'EOF'
10-31 12:00:01.000  4242  4242 E AndroidRuntime: Process: home.cleanframe.tv, PID: 4242
10-31 12:00:02.000  1000  1100 E ActivityManager: ANR in home.cleanframe.tv (home.cleanframe.tv/.MainActivity)
10-31 12:00:02.000  1000  1100 I am_anr  : [0,4242,home.cleanframe.tv,123,Input dispatching timed out]
10-31 12:00:03.000  4242  4300 E cr_ChildProcessConn: Renderer process (9999) crash detected
10-31 12:00:04.000  6000  6000 F DEBUG   : >>> home.cleanframe.tv <<<
EOF
run_stick
check stick-counts-our-crashes nz "STICK_TEST_FAIL 4" has_all "FAIL  Java crashes: got '1'" "FAIL  ANRs: got '2'" \
  "FAIL  renderer deaths: got '1'" "FAIL  native crashes: got '1'"

# The logs are read at every feature section: a renderer death that only one mid-run read can see (the
# main buffer wrapped before the next) fails the run and writes no marker; read at launch and at the end
# only, it was never counted. A clean run reads them once per section, plus those two.
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --staging
check stick-logs-read-at-every-section 0 STICK_TEST_OK eval '[ "$(cat "$STK/logcat_reads")" -ge $(( $(grep -c . "$STK/feature.log") + 2 )) ]'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; echo 5 > "$STK/logcat_mid_at"
echo '10-31 12:00:03.000  4242  4300 E cr_ChildProcessConn: Renderer process (9999) crash detected' > "$STK/logcat_mid"; run_stick --staging
check stick-mid-run-renderer-death-fails nz "FAIL  renderer deaths: got '1'" eval 'has "STICK_TEST_FAIL 1" && no_markers'

stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; : > "$STK/logcat"; run_stick --staging
check stick-empty-logcat-fails nz "FAIL  logcat read" eval 'has STICK_TEST_FAIL && no_marker'

stick_fresh home.cleanframe.tv "0.1+$SHA"; PKG=home.cleanframe.tv run_stick --recovery
check stick-recovery-refused-for-live-home nz "staging only, refused for home.cleanframe.tv" eval '[ $RC = 2 ] && no_adb_calls'
stick_fresh home.cleanframe.tv.staging "0.1+$SHA"; run_stick --reboot --staging
check stick-reboot-refused-for-staging nz "refused for home.cleanframe.tv.staging" eval '[ $RC = 2 ] && no_adb_calls'
stick_fresh home.cleanframe.tv "0.1+$SHA"; run_stick --bogus
check stick-unknown-argument-refused nz "usage:" eval '[ $RC = 2 ] && no_adb_calls'
if grep -q "192\.168\." "$STK/adb.log" 2>/dev/null; then fail no-real-stick-stick-test "a call named a LAN address"; fi
# There is no default stick: without STICK= the stick test refuses before any adb call.
stick_fresh home.cleanframe.tv "0.1+$SHA"; ( cd "$T" && env -u STICK PATH=$SB:$BIN:$PATH HOME=$SH GIT_CEILING_DIRECTORIES=$T bash "$SR/tools/stick-test.sh" ) > "$T/out" 2>&1; RC=$?
check stick-needs-stick nz "set STICK=" eval '[ $RC = 2 ] && no_adb_calls'

# ---------------------------------------------------------------- tools/rollback.sh
# The ONLY variable these rollbacks get beyond a human's: the recursion guard, a random token in a
# file only this run knows, made as tools/check.py makes them (cf-check-guard.* directly in $TMPDIR,
# else ~/scratch: check.py honours no other file). tools/check.py then skips just tests/tools.test.sh
# (this file) inside the clones and still runs every other test of the restored tree; the
# rollback-runs-restored-tests cases below prove it. Standalone and under check.py this file
# therefore behaves the same. (Under check.py it replaces the parent's guard with its own.)
TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
NG=$(mktemp "$BASE_TMP/cf-check-guard.XXXXXX") || { echo "TOOLS_TEST_FAIL 1 (no guard file)"; exit 1; }
printf '%s' "$TOKEN" > "$NG"
export CLEANFRAME_NESTED_GUARD="$NG:$TOKEN"
unset CLEANFRAME_IN_TESTS
C=$T/clone
BASE=
# clone_main <repo> <dir>: a full clone of <repo> with a local main at <repo>'s main, or at its
# refs/remotes/origin/main when it has no local main (docs/REVIEW.md's reviewer clone is
# `git clone` + `checkout --detach`: no main of its own, and `clone --branch main` failed setup,
# so the ~20 rollback cases never ran). A local clone copies every object, so the commit is there.
clone_main() {
  local m
  m=$(git -C "$1" rev-parse -q --verify 'refs/heads/main^{commit}' || git -C "$1" rev-parse -q --verify 'refs/remotes/origin/main^{commit}') \
    || { echo "clone_main: $1 has neither main nor origin/main" >&2; return 1; }
  git clone -q --no-hardlinks --no-checkout "$1" "$2" && git -C "$2" checkout -q -B main "$m"
}
# ... and it works on such a clone: a detached clone with no local main still gets main = origin/main.
# (Its origin/main is set to HEAD^ so that main cannot be taken from HEAD by mistake.)
RV=$T/review-clone; RM=$(git -C "$ROOT" rev-parse HEAD)
if git clone -q --no-checkout "$ROOT" "$RV" 2>"$T/out" && git -C "$RV" checkout -q --detach "$RM" \
   && { ! git -C "$RV" rev-parse -q --verify refs/heads/main >/dev/null || git -C "$RV" branch -q -D main; } \
   && git -C "$RV" update-ref refs/remotes/origin/main "$RM^" \
   && clone_main "$RV" "$T/review-clone2" 2>>"$T/out" \
   && [ "$(git -C "$T/review-clone2" rev-parse main)" = "$(git -C "$RV" rev-parse "$RM^")" ] \
   && [ "$(git -C "$T/review-clone2" rev-parse --abbrev-ref HEAD)" = main ]; then pass rollback-setup-without-local-main
else fail rollback-setup-without-local-main "clone_main of a detached clone with no local main: $(tail -3 "$T/out" | tr '\n' ' ')"; fi
rm -rf "$RV" "$T/review-clone2"
if clone_main "$ROOT" "$C" 2>"$T/out"; then
  # Overlay the working tree and commit it. Plus a marker in android/, so the base differs from
  # every target even when main itself is a rollback commit (its app tree then equals an older
  # SHA's, and every case would hit "nothing to roll back").
  existing() { while IFS= read -r -d '' f; do [ ! -e "$f" ] && [ ! -L "$f" ] || printf '%s\0' "$f"; done; }
  if ( cd "$C" && git ls-files -z | xargs -0 rm -f ) \
     && ( cd "$ROOT" && git ls-files -z -co --exclude-standard | existing | tar --null -T - -cf - ) | tar -xf - -C "$C" \
     && echo "tools.test.sh rollback base" > "$C/android/ROLLBACK-TEST-BASE" \
     && git -C "$C" add -A . \
     && git -C "$C" commit -q --allow-empty -m "test: working tree on main; base marker" >"$T/out" 2>&1; then
    BASE=$(git -C "$C" rev-parse HEAD)
  else fail rollback-setup "could not overlay the working tree on the clone"; fi
else fail rollback-setup "git clone failed"; fi
FIRST=$(git -C "$C" rev-list --max-parents=0 HEAD 2>/dev/null | tail -1)
reset_clone() { git -C "$C" checkout -q -f main && git -C "$C" reset -q --hard "$1" && git -C "$C" clean -qfd; }
run_rb() { ( cd "$T" && bash "$C/tools/rollback.sh" "$@" ) > "$T/out" 2>&1; RC=$?; }
head_is() { [ "$(git -C "$C" rev-parse HEAD)" = "$1" ]; }
clean() { [ -z "$(git -C "$C" status --porcelain)" ]; }
untouched() { head_is "$1" && clean; }
# rollback.sh's test lists (HEAD_TESTS kept from HEAD, the rest of tests/ from the SHA).
HEAD_TESTS=(); eval "$(grep -E '^HEAD_TESTS=\(' "$ROOT/tools/rollback.sh")"
[ ${#HEAD_TESTS[@]} -gt 0 ] || fail rollback-setup "no HEAD_TESTS=(...) line in tools/rollback.sh"
NOT_HEAD=(); for k in "${HEAD_TESTS[@]}"; do NOT_HEAD+=(":!$k"); done
META=android/gradle/verification-metadata.xml
# A rollback commit: one new commit on $1, clean tree, android/ + web/ + tests/ match $2 (except
# the HEAD_TESTS, which match $1, and the Gradle verification metadata, which is $2's when $2 has one,
# else $1's: build.sh refuses a tree without it), current check passes. A failing step is named in $T/out (which
# fail() prints), with that check's own output: "post-condition failed" alone hid which nested test
# had failed.
rolled() {
  local why
  if [ "$(git -C "$C" rev-parse HEAD^)" != "$1" ]; then why="HEAD^ is not $1"
  elif ! clean; then why="the clone is not clean"
  elif ! git -C "$C" diff --quiet "$2" HEAD -- android web tests "${NOT_HEAD[@]}" ":!$META"; then why="android/, web/ or app tests differ from $2"
  elif git -C "$C" cat-file -e "$2:$META" 2>/dev/null && ! git -C "$C" diff --quiet "$2" HEAD -- "$META"; then why="$META is not $2's"
  elif ! git -C "$C" cat-file -e "$2:$META" 2>/dev/null && ! git -C "$C" diff --quiet "$1" HEAD -- "$META"; then why="$2 has no $META, and HEAD's was not kept"
  elif ! git -C "$C" diff --quiet "$1" HEAD -- "${HEAD_TESTS[@]}"; then why="HEAD tests differ from $1"
  elif [ "$(git -C "$C" show HEAD:tools/rolled-back-to 2>/dev/null)" != "$(git -C "$C" rev-parse "$2^{commit}")" ]; then why="tools/rolled-back-to does not name $2"
  elif ! ( cd "$C" && python3 tools/check.py ) > "$T/rolled-check" 2>&1; then
    { echo "rolled: tools/check.py fails on the rollback commit:"; sed 's/^/  /' "$T/rolled-check"; } >> "$T/out"; return 1
  else return 0; fi
  echo "rolled: $why" >> "$T/out"; return 1
}
# Every HEAD test is in the rollback commit, identical to $1's, and the rollback's check ran it
# (tests/tools.test.sh is the nested one: check.py names it as skipped).
head_tests_kept_and_ran() {
  local k
  for k in "${HEAD_TESTS[@]}"; do
    git -C "$C" cat-file -e "HEAD:$k" 2>/dev/null || { echo "missing after rollback: $k"; return 1; }
    if [ "$k" = tests/tools.test.sh ]; then has "nested: skipped tools.test.sh" || return 1
    else grep -E "offline tests \(.*\b${k#tests/}\b" "$T/out" >/dev/null || { echo "did not run: $k"; return 1; }; fi
  done
  git -C "$C" diff --quiet "$1" HEAD -- "${HEAD_TESTS[@]}"
}

if [ -n "$BASE" ]; then
  # has_verify() itself, many times: the pipeline version failed ~1 call in 5 on a SHA WITH verify().
  HV=$(grep -E '^has_verify\(\) \{' "$C/tools/rollback.sh")
  if [ -z "$HV" ]; then fail has-verify-stable "no one-line has_verify() in tools/rollback.sh (update this test)"
  else
    got=$(cd "$C" && bash -c 'set -euo pipefail; PATCH=tools/android-patch.py; eval "$1"; y=0 n=0
      for _ in $(seq 200); do if has_verify "$2"; then y=$((y + 1)); fi; if has_verify "$3"; then n=$((n + 1)); fi; done
      echo "$y $n"' _ "$HV" "$BASE" 2a115ea 2>&1)
    if [ "$got" = "200 0" ]; then pass "has-verify-stable (200x yes on HEAD, 200x no on 2a115ea)"
    else fail has-verify-stable "want '200 0' (HEAD has verify(), 2a115ea has not), got '$got'"; fi
  fi

  # tools/check.py's recursion guard: only a live token skips; a stray export runs everything.
  # tests/tools.test.sh is replaced (on disk only) by a probe that records it ran.
  GT=$T/guard-tmp; mkdir -p "$GT"
  CHK_ARGS=()   # run_chk [env -u options] [VAR=value...]; check.py's own arguments in CHK_ARGS
  CHK_TMP=      # TMPDIR for check.py ($GT unless set: the live guard is in $BASE_TMP)
  run_chk() { ( cd "$C" && env "$@" TMPDIR="${CHK_TMP:-$GT}" python3 tools/check.py "${CHK_ARGS[@]}" ) > "$T/out" 2>&1; RC=$?; }
  probe_ran() { [ -f "$T/nested-probe-ran" ]; }
  no_guard_left() { [ -z "$(ls -A "$GT")" ]; }
  reset_clone "$BASE"
  printf '#!/bin/bash\ntouch "%s/nested-probe-ran"\necho TOOLS_TEST_OK\n' "$T" > "$C/tests/tools.test.sh"
  rm -f "$T/nested-probe-ran"; run_chk -u CLEANFRAME_NESTED_GUARD CLEANFRAME_IN_TESTS=1
  check check-old-boolean-does-not-skip 0 "check ok" eval 'probe_ran && not has "nested: skipped" && no_guard_left'
  rm -f "$T/nested-probe-ran"; run_chk CLEANFRAME_NESTED_GUARD="$T/no-such-guard:$TOKEN"
  check check-stale-guard-does-not-skip 0 "ignoring CLEANFRAME_NESTED_GUARD" eval 'probe_ran && not has "nested: skipped" && no_guard_left'
  # a guard file made like check.py's (name, place) but holding another token
  OG=$GT/cf-check-guard.wrongtoken; printf '%s' "$(python3 -c 'import secrets; print(secrets.token_hex(16))')" > "$OG"
  rm -f "$T/nested-probe-ran"; run_chk CLEANFRAME_NESTED_GUARD="$OG:$TOKEN"
  check check-wrong-token-does-not-skip 0 "ignoring CLEANFRAME_NESTED_GUARD" eval 'probe_ran && not has "nested: skipped"'
  rm -f "$OG"
  # (a file holding the live token but not made as check.py makes them: tests/check.test.py)
  rm -f "$T/nested-probe-ran"; CHK_TMP=$BASE_TMP; run_chk; CHK_TMP=
  check check-live-guard-skips-only-tools-test 0 "nested: skipped tools.test.sh" eval 'not probe_ran && has "offline tests"'
  rm -f "$T/nested-probe-ran"; CHK_ARGS=(--skip-nested); run_chk -u CLEANFRAME_NESTED_GUARD; CHK_ARGS=()
  check check-skip-nested-flag 0 "nested: skipped tools.test.sh" not probe_ran

  # Per-test timeout: a hung test is killed (with its children) and named. It is named to run FIRST
  # (check.py runs tests in name order and stops at the first failure), so the 3 s budget never
  # applies to the real tests: as zz-hang it came last, and any real test that took over 3 s under
  # load failed this case first. The test records its process group (check.py starts each test in a
  # new session) and the check looks for its sleep in that group only: a machine-wide
  # `pgrep -f 'sleep 987.25'` also matched any other process with that text, an ssh command line too.
  reset_clone "$BASE"; rm -f "$T/hang-pgid"
  printf '#!/bin/bash\nps -o pgid= -p $$ > "%s/hang-pgid"\nsleep 987.25\n' "$T" > "$C/tests/00-hang.test.sh"
  t0=$SECONDS; run_chk CLEANFRAME_TEST_TIMEOUT=3; took=$((SECONDS - t0))
  hang_killed() {
    local pg; pg=$(tr -d ' ' < "$T/hang-pgid" 2>/dev/null)
    [ $took -lt 120 ] || { echo "took $took s" >> "$T/out"; return 1; }
    [[ $pg =~ ^[0-9]+$ ]] || { echo "the hang test recorded no process group" >> "$T/out"; return 1; }
    ! command -v pgrep >/dev/null || ! pgrep -g "$pg" -f 'sleep 987.25' >/dev/null \
      || { echo "its sleep survived in process group $pg" >> "$T/out"; return 1; }
  }
  check check-test-timeout-named nz "tests/00-hang.test.sh TIMED OUT after 3 s" hang_killed

  reset_clone "$BASE"; run_rb 2a115ea
  check rollback-2a115ea-commits 0 "ROLLBACK_COMMIT" rolled "$BASE" 2a115ea
  # 2a115ea predates the verification metadata (cdd234f): HEAD's is kept, said, and named in the commit.
  check rollback-old-sha-keeps-head-metadata 0 "has no $META (it predates dependency verification): keeping HEAD's" \
    eval 'has "2a115ea53fe5 has no $META" && git -C "$C" cat-file -e "HEAD:$META" && git -C "$C" diff --quiet "$BASE" HEAD -- "$META" && [[ $(git -C "$C" log -1 --format=%b) == *"$META kept from HEAD"* ]]'
  check rollback-2a115ea-pre-staging-hint 0 "predates the staging build type" has "--make-home --skip-staging"

  reset_clone "$BASE"; run_rb 22e4894
  check rollback-22e4894-commits 0 "ROLLBACK_COMMIT" eval 'rolled "$BASE" 22e4894 && [ ! -e "$C/tests/device.test.js" ]'

  reset_clone "$BASE"; run_rb b4833a1
  check rollback-b4833a1-commits 0 "ROLLBACK_COMMIT" eval 'rolled "$BASE" b4833a1 && git -C "$C" diff --quiet b4833a1 HEAD -- tools/android-patch.py && has "./build.sh --staging" \
    && has "the stick test is HEAD'"'"'s: it SKIPs, naming it, each feature section b4833a1"'
  # android/ differs from the commit it rolled back from (the base marker alone does that): install.sh will
  # refuse the release without a --recovery pass, so the printed steps have it, before the release.
  check rollback-next-steps-have-recovery 0 "tools/stick-test.sh --staging && tools/stick-test.sh --recovery" has "install.sh refuses the release without that pass"
  # b4833a1 has no tests/ at all: the HEAD tests (check.py, stick-lib.sh, icon_metrics.py, build.sh,
  # the release tools) must survive the rollback and run in its gate; no app test comes back.
  check rollback-before-tests-keeps-head-tests 0 "ROLLBACK_COMMIT" eval 'head_tests_kept_and_ran "$BASE" && [ ! -e "$C/tests/android.test.py" ]'

  # ... and a rollback that leaves android/ and web/device.js alone (only an app test differs) does not print it.
  reset_clone "$BASE"; echo '// tools.test.sh: a later commit' >> "$C/tests/device.test.js"
  git -C "$C" commit -q -am "test: only an app test changes"; H2=$(git -C "$C" rev-parse HEAD); run_rb "$BASE"
  check rollback-next-steps-no-recovery-when-native-same 0 "ROLLBACK_COMMIT" \
    eval 'rolled "$H2" "$BASE" && not has "stick-test.sh --recovery" && has "no --recovery here"'

  # bf71b36 has app tests (android, brands, device, fallback + java, patch) but none of the HEAD tests
  # except tools.test.sh: its app tests come back and HEAD's tool tests stay and run beside them.
  reset_clone "$BASE"; run_rb bf71b36
  check rollback-mixed-tests 0 "ROLLBACK_COMMIT" eval 'rolled "$BASE" bf71b36 && head_tests_kept_and_ran "$BASE" \
    && git -C "$C" diff --quiet bf71b36 HEAD -- tests/android.test.py tests/java && [ ! -e "$C/tests/gradle.test.py" ]'

  # 849c54e (the commit before Cyberpunk, 9d8f477) has tests/fixtures, older than HEAD's: its app
  # tests read ITS fixture, which comes back with them (kept from HEAD, it refused this rollback).
  reset_clone "$BASE"; run_rb 849c54e
  check rollback-849c54e-takes-its-fixture 0 "ROLLBACK_COMMIT" eval 'rolled "$BASE" 849c54e && git -C "$C" diff --quiet 849c54e HEAD -- tests/fixtures'

  # An unclassified test at HEAD: refused before anything changes (it could be a tool test that a
  # rollback would silently delete).
  reset_clone "$BASE"; printf '#!/bin/bash\nexit 0\n' > "$C/tests/zz-unlisted.test.sh"
  git -C "$C" add tests/zz-unlisted.test.sh && git -C "$C" commit -q -m "test: unlisted test"
  UNL=$(git -C "$C" rev-parse HEAD); run_rb b4833a1
  check rollback-unclassified-test-refused nz "refusing: unclassified tests at HEAD: tests/zz-unlisted.test.sh" untouched "$UNL"

  reset_clone "$BASE"; run_rb "$FIRST"
  check rollback-first-commit-refused nz "has no android; nothing changed" untouched "$BASE"

  reset_clone "$BASE"; run_rb deadbeefdeadbeef
  check rollback-garbage-sha-refused nz "is not a commit" untouched "$BASE"

  reset_clone "$BASE"; echo dirty >> "$C/README.md"; run_rb b4833a1
  check rollback-dirty-tree-refused nz "working tree is dirty" eval 'head_is "$BASE" && [ "$(git -C "$C" status --porcelain)" = " M README.md" ]'

  reset_clone "$BASE"; git -C "$C" checkout -q -b not-main; run_rb b4833a1
  check rollback-not-main-refused nz "not on main" eval 'head_is "$BASE" && clean'

  reset_clone "$BASE"; git -C "$C" checkout -q -b side; echo x > "$C/side.txt"; git -C "$C" add side.txt
  git -C "$C" commit -q -m side; SIDE=$(git -C "$C" rev-parse HEAD); reset_clone "$BASE"; run_rb "$SIDE"
  check rollback-non-ancestor-refused nz "not in main's history" untouched "$BASE"

  reset_clone "$BASE"; run_rb "$BASE"
  check rollback-head-nothing-to-do nz "nothing to roll back" eval 'has "rollback aborted" && untouched "$BASE"'

  # The restored tree's own tests run inside the rollback even with CLEANFRAME_IN_TESTS set (the
  # guard is narrow): GOOD adds a test, the next commit deletes it, roll back to GOOD.
  # (a) a passing probe test must have run (it leaves a file behind);
  probe_good() {  # probe_good <exit code of the probe test>; sets GOOD and HEAD2
    reset_clone "$BASE"
    printf '#!/bin/bash\ntouch "%s/probe-ran"\nexit %s\n' "$T" "$1" > "$C/tests/zz-probe.test.sh"
    git -C "$C" add tests/zz-probe.test.sh && git -C "$C" commit -q -m "test: probe test"
    GOOD=$(git -C "$C" rev-parse HEAD)
    git -C "$C" rm -q tests/zz-probe.test.sh && git -C "$C" commit -q -m "test: drop probe"
    # shellcheck disable=SC2034  # read in the eval'd post-conditions below
    HEAD2=$(git -C "$C" rev-parse HEAD); rm -f "$T/probe-ran"
  }
  probe_good 0; run_rb "$GOOD"
  check rollback-runs-restored-tests 0 "ROLLBACK_COMMIT" eval '[ -f "$T/probe-ran" ] && rolled "$HEAD2" "$GOOD"'
  # (b) a failing one must abort the rollback.
  probe_good 1; run_rb "$GOOD"
  check rollback-restored-test-failure-aborts nz "check failed" eval '[ -f "$T/probe-ran" ] && has "zz-probe.test.sh" && untouched "$HEAD2"'

  # A SHA whose build.gradle hard-codes versionCode (116d096, 17a2d96: `versionCode 1`) could never
  # be installed over the stick's build: refused before anything changes.
  for v1 in 17a2d96 116d096; do
    reset_clone "$BASE"; run_rb "$v1"
    check "rollback-$v1-hardcoded-versioncode-refused" nz "does not set versionCode from the commit count" untouched "$BASE"
  done

  # Across a change of upstream pin: the manifest must come back WITH the patch file. Synthetic
  # history on BASE: GOOD pins upstream Q (UPSTREAM_SHA and the manifest's upstream_sha, same
  # files), the next commit pins back to P. Restoring GOOD's patch file with HEAD's manifest made
  # android-patch.py refuse ("is for upstream P, UPSTREAM_SHA is Q") and the rollback abort.
  PF=tools/android-patch.py; MF=tools/upstream-manifest.json; Q=1111111111111111111111111111111111111111
  pin_to() {  # pin_to <from-sha> <to-sha>: rewrite both pins in the clone's working tree
    sed -i "s/^UPSTREAM_SHA = '$1'/UPSTREAM_SHA = '$2'/" "$C/$PF" \
      && sed -i "s/\"upstream_sha\": \"$1\"/\"upstream_sha\": \"$2\"/" "$C/$MF" \
      && git -C "$C" grep -q -F "UPSTREAM_SHA = '$2'" -- "$PF" 2>/dev/null || return 1
    grep -qF "\"upstream_sha\": \"$2\"" "$C/$MF"
  }
  # The same history also carries a Gradle verification metadata that GOOD has and HEAD changed (an XML
  # comment): a SHA that has the file brings its own (it is part of android/), and nothing says HEAD's was kept.
  reset_clone "$BASE"; P=$(sed -n "s/^UPSTREAM_SHA = '\([0-9a-f]\{40\}\)'.*/\1/p" "$C/$PF")
  if [ -n "$P" ] && pin_to "$P" "$Q" && sed -i '1a <!-- tools.test.sh: GOOD -->' "$C/$META" && git -C "$C" commit -q -am "test: pin upstream Q, GOOD's metadata" \
     && GOOD=$(git -C "$C" rev-parse HEAD) && pin_to "$Q" "$P" && git -C "$C" checkout -q "$BASE" -- "$META" \
     && git -C "$C" commit -q -am "test: pin back to P, HEAD's metadata"; then
    HEAD2=$(git -C "$C" rev-parse HEAD); run_rb "$GOOD"
    pin_pair_ok() {
      rolled "$HEAD2" "$GOOD" && git -C "$C" diff --quiet "$GOOD" HEAD -- "$PF" "$MF" \
        && [[ $(git -C "$C" log -1 --format=%s) == *"$MF"* ]]
    }
    check rollback-across-pin-change-restores-manifest 0 "ROLLBACK_COMMIT" pin_pair_ok
    check rollback-sha-metadata-taken-from-sha 0 "ROLLBACK_COMMIT" \
      eval 'git -C "$C" diff --quiet "$GOOD" HEAD -- "$META" && ! git -C "$C" diff --quiet "$HEAD2" HEAD -- "$META" && not has "keeping HEAD'"'"'s"'
  else fail rollback-across-pin-change-restores-manifest "could not build the synthetic pin history"; fi

  # The later-patch-file search takes each candidate's OWN manifest. GOOD has no verify() (renamed);
  # C1 restores verify() but pins Q (with a Q manifest); C2 pins back to P. C1 is the earliest that
  # verifies GOOD's web/, but only together with its own manifest: with GOOD's (P) it fails and the
  # search would wrongly settle on C2. Before C1, C0 has verify() back with the patch file pinning Q
  # but the manifest still P: the search must reject it and SAY why (android-patch.py refuses the
  # pair), as it says why for any rejected candidate. (C1 adds a comment line to the patch file, so
  # it touches it and is a candidate: the search only visits commits that change the patch file.) (This case once failed with C2 chosen and no
  # reason shown: a flaky upload test inside C1's check rejected C1, 2026-10-31.)
  reset_clone "$BASE"
  if [ -n "$P" ] && sed -i 's/^def verify(/def verify_disabled_by_test(/' "$C/$PF" && git -C "$C" commit -q -am "test: no verify()" \
     && GOOD=$(git -C "$C" rev-parse HEAD) && sed -i 's/^def verify_disabled_by_test(/def verify(/' "$C/$PF" \
     && sed -i "s/^UPSTREAM_SHA = '$P'/UPSTREAM_SHA = '$Q'/" "$C/$PF" \
     && git -C "$C" commit -q -am "test: verify() back, patch file pins Q, manifest P" && C0=$(git -C "$C" rev-parse HEAD) \
     && echo "# tools.test.sh: C1" >> "$C/$PF" && pin_to "$P" "$Q" \
     && git -C "$C" commit -q -am "test: manifest pins Q too" && C1=$(git -C "$C" rev-parse HEAD) \
     && pin_to "$Q" "$P" && git -C "$C" commit -q -am "test: pin back to P"; then
    HEAD2=$(git -C "$C" rev-parse HEAD); run_rb "$GOOD"
    search_pair_ok() {
      has "  not ${C0:0:12}: web/ is not upstream + android patch: ${MF##*/} is for upstream $P, UPSTREAM_SHA is $Q" \
        && not has "  not ${C1:0:12}" && has "using $PF from ${C1:0:12}" \
        && rolled "$HEAD2" "$GOOD" && git -C "$C" diff --quiet "$C1" HEAD -- "$PF" "$MF"
    }
    check rollback-patch-search-takes-its-manifest 0 "ROLLBACK_COMMIT" search_pair_ok
  else fail rollback-patch-search-takes-its-manifest "could not build the synthetic search history"; fi

  # Forced gate failure: a committed check.py that always fails.
  reset_clone "$BASE"
  sed -i '1a import sys; sys.exit("forced check failure")' "$C/tools/check.py"
  git -C "$C" commit -q -am "test: check.py always fails"; FORCED=$(git -C "$C" rev-parse HEAD)
  forced_aborted() { has "rollback aborted" && untouched "$1" && has "forced check failure"; }
  run_rb b4833a1
  check rollback-check-failure-aborts nz "check failed" forced_aborted "$FORCED"
fi

rm -f "$T/out"
if [ $FAILS = 0 ]; then echo TOOLS_TEST_OK; exit 0; fi
echo "TOOLS_TEST_FAIL $FAILS"; exit 1

# shellcheck shell=bash
# The adb helpers shared by install.sh and tools/stick-test.sh (and, through stick-test.sh,
# tools/stick-lib.sh): adbt, a, first, homepkg, top, stickid, ui. ONE copy, so a fix reaches every caller;
# tests/tools.test.sh fails if any other *.sh defines one of these names. Not run on its own.
#
# The caller sets, BEFORE sourcing:
#   S               the stick's adb serial (host:port)
#   ADB_TIMEOUT     the per-call deadline in seconds, used by a()
#   ADB_TIMEOUT_LOG the timeout switch. Empty: a timeout prints "ADB TIMEOUT: ..." on stderr and
#                   the call fails (install.sh: set -e / pipefail end the run). A file path: a
#                   timeout is also appended to that file and printed as "FAIL  adb timeout: ...",
#                   so a timeout swallowed by "|| true" or a capture still fails the run
#                   (tools/stick-test.sh counts the file in "adb calls answered in time").
#                   Every caller sets it explicitly; an inherited value would change the behaviour.
# and checks that timeout(1) exists (each with its own message and exit code).

# adbt <seconds> <adb args...>: adb (first on PATH) with a deadline; TERM at it, KILL 5 s later.
# Returns adb's status; 124/137 mean it timed out.
adbt() {
  local t=$1 rc=0; shift
  timeout -k 5 "$t" adb "$@" </dev/null || rc=$?
  if [ $rc = 124 ] || [ $rc = 137 ]; then
    if [ -n "${ADB_TIMEOUT_LOG:-}" ]; then
      echo "adb $* (no answer in $t s)" >> "$ADB_TIMEOUT_LOG"
      echo "FAIL  adb timeout: 'adb $*' gave no answer in $t s (half-dead connection? try: adb disconnect $S)" >&2
    else
      echo "ADB TIMEOUT: 'adb $*' gave no answer in $t s (half-dead connection? try: adb disconnect $S)" >&2
    fi
  fi
  return $rc
}
a() { adbt "$ADB_TIMEOUT" -s "$S" "$@"; }
# first: the first line of stdin, but it reads ALL of it. Never end a pipeline with an early exit
# (head -1, grep -m1, grep -q): the producer (adb, git, sed) dies of SIGPIPE and pipefail makes the
# whole pipeline fail, at random, depending on output size and timing. tests/tools.test.sh bans it.
first() { sed -n 1p; }
# The package the HOME intent resolves to (the chooser resolves to "android"); empty if adb fails.
homepkg() {
  a shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME \
    | sed -n 's/^ *packageName=\([^ ]*\).*/\1/p' | first | tr -d '\r' || true
}
# The resumed activity as pkg/class (package names may contain _); empty if adb fails.
# Android 11 prints "mResumedActivity: ActivityRecord{...}", Android 14 (the Hugsun) "ResumedActivity: ...".
top() { a shell dumpsys activity activities | grep -E '(^|[[:space:]])m?ResumedActivity: ' | first | grep -oE '[A-Za-z0-9._]+/[A-Za-z0-9._$]+' | first || true; }
# stickid: which stick this is, for the per-stick promotion markers (dist/cleanframe-tv-<sha>.<id>.*-ok,
# install.sh and tools/stick-test.sh): "<ro.product.model>-<ro.serialno>" read off the device, never the
# adb address (STICK= can change, and an IP can move to another stick). The model is part of it so two
# boxes that both report a generic serial still differ. Anything outside [A-Za-z0-9._-] becomes "_".
# Empty (the caller refuses) when adb fails or either property is empty or "unknown".
stickid() {
  local m s
  m=$(a shell getprop ro.product.model | tr -d '\r' | first || true)
  s=$(a shell getprop ro.serialno | tr -d '\r' | first || true)
  [ -n "$m" ] && [ -n "$s" ] && [ "$m" != unknown ] && [ "$s" != unknown ] || return 0
  printf '%s-%s\n' "$m" "$s" | tr -c 'A-Za-z0-9._\n-' '_'
}
# ui: the screen as a uiautomator dump (XML), for native views the page's DevTools cannot see (the
# fallback list, the Back-hold list). Every dump in tools/*.sh goes through it (tests/tools.test.sh).
# uiautomator dump fails now and then while the screen is still settling ("could not get idle
# state"), which gives an empty result: seen 2026-10-31 at 47a0916 just after the page rebuilt
# ("no fallback after the trims: got ''"). So retry up to UI_TRIES times, 1 s apart, until there
# is a real dump; still empty after that is returned empty, and the expect_* checks fail on it.
UI_TRIES=${UI_TRIES:-3}
ui() {
  local u i
  for ((i = 1; i <= UI_TRIES; i++)); do
    u=$(a shell 'uiautomator dump /sdcard/cf-stick-test.xml >/dev/null 2>&1 && cat /sdcard/cf-stick-test.xml; rm -f /sdcard/cf-stick-test.xml' || true)
    if [[ $u == *"<hierarchy"* ]]; then printf '%s' "$u"; return 0; fi
    [ "$i" -lt "$UI_TRIES" ] && sleep 1
  done
  printf '%s' "$u"
}

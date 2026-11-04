# shellcheck shell=bash
# The --recovery and --reboot modes of tools/stick-test.sh, which sources this file after its own and
# tools/adb-lib.sh's helpers (a, first, ui, k, top, dt, home, homepkg, pass/fail/skip/expect, PAGE, check_adb_timeouts) and the
# adb/version checks. Every adb call is bounded: through a() (ADB_TIMEOUT, stick-test.sh) or, in
# the reboot poll, by its own short timeout.
# Not run on its own.
#
# --recovery (staging only; it crashes the page on purpose) drives MainActivity's native fallback
# with techniques proven on this stick (am crash does not work here; am kill takes the whole app):
#   trim-memory  `am send-trim-memory <pkg> BACKGROUND` while YouTube is in front drops the page;
#                Home rebuilds it in the same process, uncounted (x2, no fallback).
#   crash        CDP Page.crash on the page's DevTools websocket is a renderer crash (didCrash);
#                the 3rd in 10 minutes shows "crashed 3 times". Home before 60 s keeps the list;
#                with --slow, Home after 60 s retries it and one more crash brings it straight back.
#                "Try CleanFrame again" (the focused button) clears the counts.
#   Back-hold    `input keyevent --longpress KEYCODE_BACK` opens the list by hand; the Back key
#                returns and, like the list's "Back to CleanFrame" button, clears the counts (with
#                2 crashes in the window, one more after Back is rebuilt, not the fallback).
#   start guard  three starts force-stopped right after the first frame (well before the 2 s ready
#                poll) make the 4th start show "stopped starting 3 times"; Try again restores, and
#                a start whose page lasted resets the count (2 more early deaths then do not trip it):
#                since FallbackPolicy.STABLE_MS that takes 30 s on screen with the page drawn, or leaving
#                for another app with it drawn. An older app reset it at the first draw; the wait is harmless there.
# The fallback is native, so its text is read with uiautomator; the page through DevTools.
# It ends with the staging page drawn in a fresh process (all counts zero) and the live Home in
# front. Every check is PASS/FAIL; the caller prints the totals. This file stays at HEAD through
# tools/rollback.sh while the app goes back, so a section for native behaviour newer than the tested
# commit is SKIPped, named (native_lacks): section 3 (2888814) and 3c (ed314d2); a FAIL there would
# delete that older commit's staging marker for doing what it was built to do.
#
# --reboot (live Home only) reboots the stick, waits for a NEW boot_id with sys.boot_completed=1
# (BOOT_TIMEOUT, default 300 s), then checks HOME resolves to home.cleanframe.tv, it is the
# resumed activity and the page draws (PAGE_TIMEOUT, default 120 s after boot_completed).

# The YouTube the trim checks put in front: Google's (the Xiaomi), else Rockchip's (the Hugsun, Android
# 14, 2026-11-02: with no Google YouTube the page stayed in front and Android refused the trim, "Unable
# to set a higher trim level than current level"). Picked by yt_pkg at the start of recovery_test.
YT_CHOICES="com.google.android.youtube.tv com.rockchip.android.youtube.tv"
yt_pkg() {
  local p
  for p in $YT_CHOICES; do [ -z "$(a shell pm path "$p" | tr -d '\r' || true)" ] || { echo "$p"; return 0; }; done
  echo "${YT_CHOICES%% *}"   # none installed: Google's, so the "YouTube in front" checks fail by name
}
YT=${YT_CHOICES%% *}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-300}
PAGE_TIMEOUT=${PAGE_TIMEOUT:-120}

finish() {
  check_adb_timeouts
  echo "passed $PASSES, failed $FAILS"
  # shellcheck disable=SC2034  # DONE is read by stick-test.sh's EXIT trap
  DONE=1
  markers_after_run   # stick-test.sh: --recovery's marker (written on a pass, deleted with staging-ok on a FAIL)
  if [ "$FAILS" -ne 0 ]; then echo "STICK_TEST_FAIL $FAILS"; exit 1; fi
  echo STICK_TEST_OK
  exit 0
}

# ui (the uiautomator dump, retried on an empty one) and UI_TRIES: tools/adb-lib.sh.
uitext() { grep -oE 'text="[^"]+"' <<<"$1" | sed -n 1,3p | tr '\n' ' ' || true; }   # sed reads all: no SIGPIPE (see first())
expect_ui() {      # expect_ui <name> <text>: <text> is on screen
  local u; u=$(ui)
  if [[ $u == *"$2"* ]]; then pass "$1" "$2"; else fail "$1" "$(uitext "$u")" "'$2' on screen"; fi
}
expect_no_ui() {   # expect_no_ui <name> <text>: a real dump without <text>
  local u; u=$(ui)
  if [[ $u == *"<hierarchy"* ]] && [[ $u != *"$2"* ]]; then pass "$1" "no '$2'"
  else fail "$1" "$(uitext "$u")" "a uiautomator dump without '$2'"; fi
}
# expect_page <name> <seconds>: poll until the page has drawn a control.
expect_page() {
  local t0=$SECONDS r
  while :; do
    r=$(dt "$PAGE")
    if [ "$r" = true ]; then pass "$1" "drawn in $((SECONDS - t0)) s"; return 0; fi
    if [ $((SECONDS - t0)) -ge "$2" ]; then fail "$1" "$r after $((SECONDS - t0)) s" "page drawn within $2 s"; return 0; fi
    sleep 1
  done
}
pidof_pkg() { a shell pidof "$PKG" | tr -d '\r' || true; }

# Run a DevTools script (node, ES module) against our app's DevTools socket; DT_ERROR on failure.
cdp() {
  local pid out
  pid=$(pidof_pkg)
  [ -n "$pid" ] || { echo DT_ERROR; return 0; }
  a forward "tcp:$PORT" "localabstract:webview_devtools_remote_$pid" >/dev/null || { echo DT_ERROR; return 0; }
  out=$(node --input-type=module -e "$1" 2>/dev/null) || out=
  echo "${out:-DT_ERROR}"
}
# How many CleanFrame pages DevTools lists (0 once the WebView is destroyed).
PAGES_JS=$(cat <<'EOF'
setTimeout(() => process.exit(1), 10000).unref();
const pages = await (await fetch(`http://127.0.0.1:${process.env.DEVTOOLS_PORT}/json`)).json();
console.log(pages.filter(p => p.url.startsWith('https://cleanframe.local/')).length);
EOF
)
# Page.crash on the page's websocket: the renderer dies with didCrash=true. Prints "sent".
CRASH_JS=$(cat <<'EOF'
setTimeout(() => process.exit(1), 10000).unref();
const pages = await (await fetch(`http://127.0.0.1:${process.env.DEVTOOLS_PORT}/json`)).json();
const page = pages.find(p => p.url.startsWith('https://cleanframe.local/'));
if (!page) { console.log('no page'); process.exit(0); }
let sent = false;
const done = () => { console.log(sent ? 'sent' : 'not sent'); process.exit(0); };
const ws = new WebSocket(page.webSocketDebuggerUrl);
ws.onopen = () => { ws.send(JSON.stringify({ id: 1, method: 'Page.crash' })); sent = true; setTimeout(done, 3000); };
ws.onclose = done;
ws.onerror = done;
EOF
)
crash_page() { cdp "$CRASH_JS"; }

youtube_front() {
  local act t0=$SECONDS
  act=$(a shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.LEANBACK_LAUNCHER "$YT" | tr -d '\r' | tail -1)
  a shell am start -n "$act" >/dev/null || true
  # ShellActivity hands over to MainActivity (mResumedActivity is briefly empty): wait until
  # YouTube has been in front for two polls in a row, then let the app settle.
  local n=0 t
  while [ $n -lt 2 ] && [ $((SECONDS - t0)) -lt 30 ]; do
    sleep 1; t=$(top | cut -d/ -f1)
    if [ "$t" = "$YT" ]; then n=$((n + 1)); else n=0; fi
  done
  [ $n -lt 2 ] || sleep 2
  echo "${t:-<nothing resumed>}"
}

# Start the app and force-stop it once the first frame is up (am start -W), in the same shell
# call so it lands well before the 2 s ready poll: a start that died before the page drew.
# Prints the PID it killed (empty if the process never ran).
early_death() {
  a shell am force-stop "$PKG"
  a shell am start -W -n "$ACT" >/dev/null
  a shell "pidof $PKG; am force-stop $PKG" | tr -d '\r' || true
}

# Force-stop and start the staging app afresh; recover an earlier run's start-guard fallback.
fresh_start() {
  a shell am force-stop "$PKG"
  a shell am start -n "$ACT" >/dev/null
  sleep 4
  if [ "$(dt "$PAGE")" != true ] && [[ $(ui) == *"Try CleanFrame again"* ]]; then
    echo "note: a fallback from an earlier run was showing; pressing Try again"
    k DPAD_CENTER 3
  fi
}

# native_lacks <text>: 0 when the tested commit's own FallbackPolicy.java does not have <text> (git grep,
# never a pipeline; tools/rollback.sh keeps this file at HEAD, and a rollback commit's android/ is older
# than its history, so only its tree can say). 1 when it has it, or when $SHA is no clean commit here:
# then the section runs and any misbehaviour FAILs, as for any other build. git grep exits 0 for found, 1 for
# not found; anything else (an unreadable blob) is no answer: a FAIL, and the section runs, never a SKIP.
native_lacks() {
  clean_sha && git cat-file -e "$SHA^{commit}" 2>/dev/null || return 1
  local rc=0
  git grep -q -F -e "$1" "$SHA" -- android/app/src/main/java/home/cleanframe/tv/FallbackPolicy.java 2>/dev/null || rc=$?
  case $rc in
    0) return 1 ;;
    1) return 0 ;;
    *) fail "read $SHA's FallbackPolicy.java" "git grep exit $rc" "0 or 1"; return 1 ;;
  esac
}

recovery_test() {
  local i p0 r failed_at wait
  echo "recovery test: crashes, drops and kills the staging page on purpose"
  YT=$(yt_pkg); echo "YouTube for the trim checks: $YT"
  fresh_start
  expect_page "staging page drawn" 60
  sleep 3   # let the app's own 2 s ready poll see it: YouTube, next, then leaves a drawn page, which clears the start count

  # 1. Trim-memory behind YouTube, twice: the page is dropped and rebuilt in the same process.
  for i in 1 2; do
    p0=$(pidof_pkg)
    expect "trim $i: YouTube in front" "$(youtube_front)" "$YT"
    expect "trim $i: page alive behind YouTube" "$(cdp "$PAGES_JS")" 1
    # Round 2 asks a higher level: Android 14 refuses one not above the process's current level ("Unable
    # to set a higher trim level than current level", Hugsun 2026-11-02). Both are >= BACKGROUND, which
    # is what MainActivity.onTrimMemory drops the page at.
    r=$(a shell am send-trim-memory "$PKG" "$([ $i = 1 ] && echo BACKGROUND || echo MODERATE)" 2>&1 | tr -d '\r' | grep -E 'Exception|Error' | first || true)
    expect "trim $i: send-trim-memory accepted" "${r:-ok}" ok
    sleep 2
    expect "trim $i: page dropped by trim-memory" "$(cdp "$PAGES_JS")" 0
    home
    expect "trim $i: staging back in front" "$(top)" "$ACT"
    expect_page "trim $i: page rebuilt on return" 30
    expect "trim $i: same process (dropped, not killed)" "$(pidof_pkg)" "$p0"
  done
  expect_no_ui "no fallback after the trims" "Try CleanFrame again"

  # 2. Three renderer crashes in front: rebuilt twice, then the failure fallback.
  for i in 1 2 3; do
    expect "crash $i: Page.crash sent" "$(crash_page)" sent
    if [ $i -lt 3 ]; then expect_page "crash $i: page rebuilt at once" 30; fi
  done
  sleep 2
  failed_at=$SECONDS
  expect_ui "crash 3: fallback says crashed 3 times" "crashed 3 times in 10 minutes"
  expect_ui "fallback offers Try again" "Try CleanFrame again"
  home
  expect_ui "Home before 60 s keeps the fallback" "crashed 3 times in 10 minutes"
  if [ "$SLOW" = 1 ]; then
    wait=$((62 - (SECONDS - failed_at)))
    if [ $wait -gt 0 ]; then echo "waiting ${wait} s for the 60 s Home retry"; sleep "$wait"; fi
    home
    expect_page "Home after 60 s retries the page" 30
    expect "one more crash: Page.crash sent" "$(crash_page)" sent
    sleep 2
    expect_ui "one more crash brings the fallback straight back" "crashed 4 times in 10 minutes"
  else
    skip "Home after 60 s retries the page" "needs --slow"
  fi
  k DPAD_CENTER 3
  expect_page "Try again restores the page" 30
  expect "after Try again: Page.crash sent" "$(crash_page)" sent
  expect_page "after Try again one crash is rebuilt (counts cleared)" 30

  # 3. Back-hold list by hand; the Back key returns and clears the counts, as the list's "Back to
  # CleanFrame" button does (2026-10-31; before, the key kept them). Section 2's last crash is
  # still in the 10 min window; one more makes 2, so if Back kept the counts the crash after it would
  # be the 3rd and show "crashed 3 times".
  if native_lacks 'boolean back()'; then
    skip "the Back key clears the counts" "app predates it (2888814: $SHA's FallbackPolicy has no back())"
  else
  expect "Back key setup: Page.crash sent (2nd crash in the window)" "$(crash_page)" sent
  expect_page "Back key setup: page rebuilt at once" 30
  a shell input keyevent --longpress KEYCODE_BACK >/dev/null; sleep 2
  expect_ui "Back-hold opens the list" "Opened by holding Back"
  k BACK 3
  expect_page "Back returns to the page" 30
  expect "after the Back key: Page.crash sent" "$(crash_page)" sent
  sleep 2
  expect_no_ui "after the Back key one crash is not the fallback (counts cleared)" "crashed 3 times"
  expect_page "after the Back key one crash is rebuilt (counts cleared)" 30
  fi

  # 3b. Hang. First clear the counts: section 3's last crash is still in the 10 min window, and with
  # it the 2nd hang would already be death 3 (2026-10-31 run: fallback at hang 2). This uses the
  # list's button, not the Back key (section 3 covers that), so both ways out stay tested; if section
  # 3 left a failure list, the Back-hold does nothing there and DPAD_CENTER is its "Try again".
  a shell input keyevent --longpress KEYCODE_BACK >/dev/null; sleep 2
  expect_ui "hang setup: Back-hold list" "Back to CleanFrame"
  k DPAD_CENTER 3
  expect_page "hang setup: 'Back to CleanFrame' (clears the counts) restores the page" 30
  # DevTools freezes the page
  # with an endless loop (queued by setTimeout so Runtime.evaluate itself answers). A D-pad key
  # is input, so WebView reports the hung renderer; MainActivity terminates it ~15 s later and
  # rebuilds the page, counting a crash. Back alone is not input to the page (it is sent with
  # evaluateJavascript), so the second hang uses only Back: MainActivity's ping must find it.
  # The third hang in 10 minutes shows the fallback.
  # Chromium sends WebView NO unresponsive reports while a DevTools session is attached (measured
  # 2026-10-31: single D-pad, DevTools polled every second: no report in 40 s; not polled: reports
  # at 6, 12, 18 s, terminated at 18 s). expect_page polls through DevTools, so after a D-pad the
  # test first waits HANG_QUIET seconds with nothing attached; the Back case is MainActivity's own
  # ping, which DevTools does not suppress, so it is polled at once. The freeze itself detaches
  # (cdp's node process closes its websocket) before the key is sent.
  local freeze_js t_hang HANG_QUIET=30
  freeze_js=$(cat <<'EOF'
setTimeout(() => process.exit(1), 10000).unref();
const pages = await (await fetch(`http://127.0.0.1:${process.env.DEVTOOLS_PORT}/json`)).json();
const page = pages.find(p => p.url.startsWith('https://cleanframe.local/'));
if (!page) { console.log('no page'); process.exit(0); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate',
  params: { expression: 'setTimeout(function(){while(true){}},0);1', returnByValue: true } }));
ws.onmessage = m => { if (JSON.parse(m.data).id === 1) { console.log('frozen'); process.exit(0); } };
ws.onerror = () => { console.log('ws error'); process.exit(0); };
EOF
)
  for i in 1 2 3; do
    expect "hang $i: page frozen through DevTools" "$(cdp "$freeze_js")" frozen
    t_hang=$SECONDS
    if [ $i = 2 ]; then k BACK 1; else k DPAD_RIGHT 1; fi
    if [ $i -lt 3 ]; then
      if [ $i != 2 ]; then sleep "$HANG_QUIET"; fi   # nothing attached while WebView's hang monitor runs
      expect_page "hang $i ($([ $i = 2 ] && echo 'Back only, ping' || echo 'D-pad')): hung page terminated and rebuilt" 60
      if [ $((SECONDS - t_hang)) -ge 10 ]; then pass "hang $i: not rebuilt before the hang timeout" "$((SECONDS - t_hang)) s"
      else fail "hang $i: not rebuilt before the hang timeout" "$((SECONDS - t_hang)) s" ">= 10 s"; fi
    else
      # The third hang shows the fallback. WebView 113 (the Hugsun, 2026-11-02) kills a hung renderer
      # itself and reports it as a crash, so its fallback says "crashed 3 times in 10 minutes"; the
      # Xiaomi's says "stopped responding (3 crashes or hangs in 10 minutes)". Either is the fallback
      # after 3; anything else (no fallback, another reason) FAILs.
      local u
      while [ $((SECONDS - t_hang)) -lt 60 ]; do u=$(ui); [[ $u == *"stopped responding"* || $u == *"crashed 3 times in 10 minutes"* ]] && break; sleep 3; done
      if [[ $u == *"stopped responding (3 crashes or hangs in 10 minutes)"* || $u == *"crashed 3 times in 10 minutes"* ]]; then
        pass "hang 3: fallback after 3 hangs" "$(uitext "$u")"
      else fail "hang 3: fallback after 3 hangs" "$(uitext "$u")" "'stopped responding (3 crashes or hangs in 10 minutes)' or 'crashed 3 times in 10 minutes' on screen"; fi
    fi
  done
  k DPAD_CENTER 3
  expect_page "Try again after the hangs restores the page" 30

  # 3c. Home-only hang (2026-10-31 review, area A). On Android 10+ Home is pause, onNewIntent, resume
  # (measured on this stick: ~5 ms from pause to resume, for staging's HOME intent and the live Home's
  # real key alike), so the press arrives while paused: MainActivity must send its ping once resumed,
  # and that short pause must not restart the hang clock. The page is frozen as in 3b, then ONLY Home
  # is pressed (home(): for staging the HOME intent, the same onNewIntent path), every ~4 s for
  # HOME_FOR s, longer than the ~15 s hang timeout. No D-pad (WebView's own report would find the hang,
  # not the ping) and no DevTools until the presses stop (the trap in 3b; and a frozen page cannot
  # answer DevTools anyway). Before the fix no ping was ever sent and the page stayed frozen; were each
  # press to restart the clock, it would be terminated only ~15 s after the last press. So the page
  # must already be rebuilt when the presses stop: expect_page with HOME_LATE, well under 15 s. Section
  # 3b's Try again cleared the counts, so this one hang is rebuilt, not the fallback.
  local t_home HOME_FOR=28 HOME_LATE=8
  if native_lacks 'boolean pingPending()'; then
    skip "Home alone finds a hung page" "app predates it (ed314d2: $SHA's FallbackPolicy has no pingPending())"
  else
  expect "home-only hang: page frozen through DevTools" "$(cdp "$freeze_js")" frozen
  t_home=$SECONDS
  while [ $((SECONDS - t_home)) -lt "$HOME_FOR" ]; do home; sleep 1; done
  expect_page "home-only hang: Home alone (pressed every ~4 s for ${HOME_FOR} s) found it; page rebuilt" "$HOME_LATE"
  expect_no_ui "home-only hang: one hang is not the fallback" "stopped responding"
  fi

  # 4. Start guard: three early deaths, then the 4th start shows the fallback.
  for i in 1 2 3; do
    r=$(early_death)
    if [[ $r =~ ^[0-9]+$ ]]; then pass "early death $i: started and killed before ready" "pid $r"
    else fail "early death $i: started and killed before ready" "$r" "a PID"; fi
  done
  a shell am start -n "$ACT" >/dev/null; sleep 4
  expect "4th start in front" "$(top)" "$ACT"
  expect_ui "4th start: fallback says stopped starting" "stopped starting 3 times"
  k DPAD_CENTER 3
  expect_page "Try again restores the page" 30
  sleep 3
  a shell am force-stop "$PKG"; a shell am start -n "$ACT" >/dev/null
  expect_page "normal restart draws the page" 60
  expect_no_ui "normal restart does not trip the guard" "stopped starting"
  sleep 33   # the page must last: STABLE_MS (30 s on screen, drawn) clears the start count; 3 s for the 2 s ready poll
  for i in 1 2; do
    r=$(early_death)
    if [[ $r =~ ^[0-9]+$ ]]; then pass "reset check: early death $i" "pid $r"; else fail "reset check: early death $i" "$r" "a PID"; fi
  done
  a shell am start -n "$ACT" >/dev/null
  expect_page "drawn start reset the count (2 more early deaths do not trip it)" 60
  expect_no_ui "no start-guard fallback" "stopped starting"
  sleep 3   # end state: drawn, fresh process (no crash or kill counts); the live Home below covers it, which clears the start count

  # Leave the stick on the live Home dock (the real Home key, which staging never answers).
  k HOME 3
  expect "live Home in front at the end" "$(top)" home.cleanframe.tv/.MainActivity
  expect "HOME resolves to CleanFrame" "$(homepkg)" home.cleanframe.tv
}

reboot_test() {
  local boot0 bid bc t0 t1 t2
  expect "HOME resolves to CleanFrame before reboot" "$(homepkg)" home.cleanframe.tv
  [ "$FAILS" = 0 ] || { echo "not rebooting: Home is already wrong"; return 0; }
  boot0=$(a shell cat /proc/sys/kernel/random/boot_id | tr -d '\r' || true)
  [ -n "$boot0" ] || { fail "read boot_id" "" "a boot id"; return 0; }
  echo "rebooting $S (timeouts: boot ${BOOT_TIMEOUT} s, page ${PAGE_TIMEOUT} s)"
  t0=$SECONDS
  timeout -k 5 30 adb -s "$S" reboot </dev/null >/dev/null 2>&1 || true
  bid=$boot0; bc=
  while [ $((SECONDS - t0)) -lt "$BOOT_TIMEOUT" ]; do
    sleep 5
    timeout 10 adb connect "$S" </dev/null >/dev/null 2>&1 || true
    [ "$(timeout 10 adb -s "$S" get-state </dev/null 2>/dev/null || true)" = device ] || continue
    bid=$(timeout 10 adb -s "$S" shell cat /proc/sys/kernel/random/boot_id </dev/null 2>/dev/null | tr -d '\r' || true)
    [ -n "$bid" ] && [ "$bid" != "$boot0" ] || continue
    bc=$(timeout 10 adb -s "$S" shell getprop sys.boot_completed </dev/null 2>/dev/null | tr -d '\r' || true)
    [ "$bc" = 1 ] && break
  done
  t1=$SECONDS
  if [ "$bc" = 1 ]; then pass "stick rebooted (new boot_id) and sys.boot_completed=1" "after $((t1 - t0)) s"
  else fail "stick rebooted (new boot_id) and sys.boot_completed=1" "boot_id ${bid:0:8} (was ${boot0:0:8}), boot_completed '$bc' after $((t1 - t0)) s" "within ${BOOT_TIMEOUT} s"; return 0; fi
  expect "HOME resolves to CleanFrame after reboot" "$(homepkg)" home.cleanframe.tv
  while [ "$(top)" != "$ACT" ] && [ $((SECONDS - t1)) -lt "$PAGE_TIMEOUT" ]; do sleep 2; done
  expect "CleanFrame is the resumed activity" "$(top)" "$ACT"
  expect_page "page drawn after boot" $((PAGE_TIMEOUT - (SECONDS - t1) > 5 ? PAGE_TIMEOUT - (SECONDS - t1) : 5))
  t2=$SECONDS
  echo "boot time: $((t1 - t0)) s reboot -> sys.boot_completed; $((t2 - t0)) s reboot -> page drawn"
}

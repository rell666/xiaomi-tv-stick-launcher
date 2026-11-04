#!/usr/bin/env python3
"""Offline checks of tools/stick-lib.sh's --recovery hang cases (they need the stick to run, so
here only their shape is checked, against a trap measured on the stick on 2026-10-31):
Chromium sends WebView NO onRenderProcessUnresponsive reports while a DevTools session is attached
to the page. expect_page, dt and cdp all attach DevTools, so polling with them straight after the
D-pad key that should trigger WebView's hang report suppresses the report, and the frozen page is
never terminated (83d6ee1's recovery run: "hang 1 (D-pad): DT_ERROR after 66 s"). So:
  - after each D-pad key in the hang loop, the first thing is a sleep of HANG_QUIET >= 20 s
    (report ~6 s, terminate ~18 s after the key) before any DevTools use;
  - the hang-3 branch (waits for the fallback) uses only uiautomator (ui/expect_ui), no DevTools;
  - the counts are cleared first, through the Back-hold list's button (DPAD_CENTER on "Back to
    CleanFrame"): a crash left in the window made the 2nd hang the 3rd death (2026-10-31 run). The
    Back key now clears them too (2026-10-31), but section 3 covers the key, so the hang setup
    keeps the button;
  - section 3 (Back-hold) shows the Back key clears the counts: a crash before the Back-hold (2 in
    the window), the list left with the Back key, then one more crash that must not be the fallback;
  - section 3c (Home-only hang, 2026-10-31 review area A): after the freeze only Home is pressed
    (home(), no key, no DevTools) in a loop for HOME_FOR >= 20 s (longer than the ~15 s hang timeout),
    then the page must be rebuilt within HOME_LATE < 15 s: a hang found only after the presses stop
    (each press restarting the clock) fails it.
  - section 4 (start guard): a drawn page clears the start count only after FallbackPolicy.STABLE_MS (30 s on
    screen), so between "normal restart draws the page" and the 2 more early deaths it waits at least that, plus
    the 2 s ready poll, with no restart in between: a shorter wait leaves the restart counted and they trip the guard.
Python 3 standard library only. Run from the repo root:  python3 -I tests/stick-lib.test.py"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
REL = 'tools/stick-lib.sh'
DEVTOOLS = re.compile(r'\b(?:expect_page|dt|cdp|crash_page)\b')


class Fail(Exception):
    pass


def check(cond, msg):
    if not cond:
        raise Fail('%s: %s' % (REL, msg))


def code(text):
    """Shell text without comment lines or trailing # comments (keeps the heredoc JS intact enough)."""
    return '\n'.join(re.sub(r'(^|\s)#.*$', '', line) for line in text.splitlines())


def hang_section():
    src = (ROOT / REL).read_text(encoding='utf-8')
    m = re.search(r'^recovery_test\(\)\s*\{\n(.*?)^\}', src, re.S | re.M)
    check(m, 'no recovery_test() { ... }')
    body = m.group(1)
    start, end = body.find('# 3b. Hang'), body.find('# 3c. Home-only hang')
    check(0 <= start < end, "recovery_test has no '# 3b. Hang' section before '# 3c. Home-only hang'")
    sec = body[start:end]
    loop = sec.find("EOF\n)")
    check(loop >= 0, 'the hang section has no freeze_js heredoc ending in EOF / )')
    return sec, code(sec[loop:])


def test_quiet_after_dpad():
    sec, loop = hang_section()
    q = re.search(r'\bHANG_QUIET=(\d+)', sec)
    check(q and int(q.group(1)) >= 20, 'HANG_QUIET is missing or under 20 s (WebView terminates ~18 s after the key)')
    keys = [m.end() for m in re.finditer(r'\bk\s+DPAD_\w+', loop)]
    check(keys, 'no D-pad key in the hang loop')
    rest = loop[keys[0]:]
    first_dt = DEVTOOLS.search(rest)
    quiet = re.search(r'\bsleep\s+"?\$HANG_QUIET"?', rest)
    check(quiet, 'no sleep "$HANG_QUIET" after the D-pad key')
    check(first_dt is None or quiet.start() < first_dt.start(), 'DevTools (%s) is used after the D-pad key before '
          'sleep "$HANG_QUIET": an attached DevTools session stops WebView reporting the hang' % (first_dt and first_dt.group(0)))
    return 'HANG_QUIET=%s s' % q.group(1)


def test_fallback_wait_uses_no_devtools():
    _, loop = hang_section()
    m = re.search(r'\n\s*else\n(.*?)\n\s*fi\n', loop, re.S)
    check(m, 'no else branch (hang 3, waits for the fallback) in the hang loop')
    # expect_ui, or (since 2026-11-02, two accepted wordings) ui() with its own PASS/FAIL of the fallback.
    g = m.group(1)
    check('expect_ui' in g or ('$(ui)' in g and 'pass "hang 3: fallback' in g and 'fail "hang 3: fallback' in g),
          'the hang-3 branch does not check the fallback text on screen (expect_ui, or ui() with PASS and FAIL)')
    bad = DEVTOOLS.search(m.group(1))
    check(not bad, 'the hang-3 branch uses DevTools (%s) while WebView should be reporting the hang' % (bad and bad.group(0)))


def test_counts_cleared_before_hangs():
    sec, _ = hang_section()
    pre = code(sec[:sec.find('freeze_js=')])
    hold = re.search(r'input keyevent --longpress KEYCODE_BACK', pre)
    check(hold, 'the hang section does not open the Back-hold list before the hangs (to clear the counts)')
    press = re.search(r'\bk\s+DPAD_CENTER\b', pre[hold.end():])
    check(press, "no DPAD_CENTER on the list's 'Back to CleanFrame' button after the Back-hold: the button's "
          'clearing of the counts is no longer tested (section 3 tests the Back key)')
    check(not re.search(r'\bk\s+BACK\b', pre[hold.end():hold.end() + press.start()]), 'returns from the list with '
          'the Back key instead of the button: the button way out is no longer tested')


def test_back_key_clears_counts():
    src = (ROOT / REL).read_text(encoding='utf-8')
    m = re.search(r'^recovery_test\(\)\s*\{\n(.*?)^\}', src, re.S | re.M)
    check(m, 'no recovery_test() { ... }')
    body = m.group(1)
    start, end = body.find('# 3. Back-hold'), body.find('# 3b. Hang')
    check(0 <= start < end, "recovery_test has no '# 3. Back-hold' section before '# 3b. Hang'")
    sec = code(body[start:end])
    hold = re.search(r'input keyevent --longpress KEYCODE_BACK', sec)
    check(hold, 'section 3 does not open the list with a Back-hold')
    check(re.search(r'\bcrash_page\b', sec[:hold.start()]), 'section 3 crashes nothing before the Back-hold: with only '
          "section 2's one crash in the window, the crash after Back would not reach the fallback either way")
    after = sec[hold.end():]
    back = re.search(r'\bk\s+BACK\b', after)
    check(back, 'section 3 does not leave the list with the Back key')
    check(not re.search(r'\bk\s+DPAD_CENTER\b', after[:back.start()]), 'section 3 presses the button before the Back key')
    rest = after[back.end():]
    crash = re.search(r'\bcrash_page\b', rest)
    check(crash, 'no crash after the Back key: nothing shows that it cleared the counts')
    check(re.search(r'expect_no_ui\s+"[^"]*"\s+"crashed 3 times"', rest[crash.end():]),
          'after the Back key and one crash, section 3 does not expect_no_ui "crashed 3 times"')
    check(re.search(r'\bexpect_page\b', rest[crash.end():]), 'after the Back key and one crash, section 3 does not '
          'expect the page rebuilt')



def test_home_only_hang():
    src = (ROOT / REL).read_text(encoding='utf-8')
    m = re.search(r'^recovery_test\(\)\s*\{\n(.*?)^\}', src, re.S | re.M)
    check(m, 'no recovery_test() { ... }')
    body = m.group(1)
    start, end = body.find('# 3c. Home-only hang'), body.find('# 4. Start guard')
    check(0 <= start < end, "recovery_test has no '# 3c. Home-only hang' section before '# 4. Start guard'")
    sec = code(body[start:end])
    for name, low, high in (('HOME_FOR', 20, None), ('HOME_LATE', 1, 14)):
        v = re.search(r'\b%s=(\d+)' % name, sec)
        check(v and int(v.group(1)) >= low and (high is None or int(v.group(1)) <= high),
              '%s is missing or outside %s..%s s (hang timeout ~15 s)' % (name, low, high or ''))
    freeze = re.search(r'cdp\s+"\$freeze_js"', sec)
    check(freeze, 'section 3c does not freeze the page (cdp "$freeze_js")')
    after = sec[freeze.end():]
    loop = re.search(r'\bwhile\b[^\n]*\$HOME_FOR[^\n]*;\s*do\b(.*?)\bdone\b', after, re.S)
    check(loop, 'section 3c has no loop over "$HOME_FOR" seconds after the freeze')
    check(re.search(r'(^|[\s;])home($|[\s;])', loop.group(1)), 'the 3c loop does not press Home (home)')
    pressed = after[:loop.end()]
    check(not re.search(r'\bk\s+\w+|input\s+keyevent', pressed), 'section 3c sends a key after the freeze: '
          'it must be Home alone (a D-pad lets WebView find the hang, not the Home ping)')
    bad = DEVTOOLS.search(pressed)
    check(not bad, 'section 3c uses DevTools (%s) before the Home presses stop' % (bad and bad.group(0)))
    check(re.search(r'\bexpect_page\s+"[^"]*"\s+"\$HOME_LATE"', after[loop.end():]), 'after the Home presses, '
          'section 3c does not expect_page within "$HOME_LATE" (a hang found only after the presses stop must fail)')
    return 'HOME_FOR=%s s, HOME_LATE=%s s' % (re.search(r'HOME_FOR=(\d+)', sec).group(1),
                                            re.search(r'HOME_LATE=(\d+)', sec).group(1))


FAKE_A = r"""
a() {
  local n
  n=$(( $(cat "$CALLS" 2>/dev/null || echo 0) + 1 ))
  echo "$n" > "$CALLS"
  if [ "$n" -le "$EMPTIES" ]; then return 0; fi
  echo '<hierarchy><node text="x"/></hierarchy>'
}
sleep() { :; }
"""


def test_ui_retries_an_empty_dump():
    """ui() run for real in bash against a fake a(): empty dumps are retried, a real one returned,
    and an always-empty screen still comes back empty after UI_TRIES calls (so checks fail)."""
    import os, subprocess, tempfile
    src = (ROOT / 'tools/adb-lib.sh').read_text(encoding='utf-8')   # ui() moved there (one copy)
    m = re.search(r'^UI_TRIES=.*?^ui\(\) \{.*?^\}\n', src, re.S | re.M)
    check(m, 'no UI_TRIES setting followed by a ui() function in tools/adb-lib.sh')
    script = 'set -euo pipefail\n' + FAKE_A + m.group(0) + 'out=$(ui); printf "%s" "$out"\n'
    with tempfile.TemporaryDirectory() as d:
        def run(empties):
            calls = os.path.join(d, 'calls-%d' % empties)
            env = dict(os.environ, CALLS=calls, EMPTIES=str(empties))
            env.pop('UI_TRIES', None)
            r = subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=20, env=env)
            return r.returncode, r.stdout, int(pathlib.Path(calls).read_text())
        rc, out, calls = run(2)
        check(rc == 0 and '<hierarchy' in out and calls == 3,
              'two empty dumps then a real one: want the dump after 3 calls, got rc=%s calls=%s out=%r'
              % (rc, calls, out[:40]))
        rc, out, calls = run(99)
        check(rc == 0 and out == '' and calls == 3,
              'always empty: want empty after 3 calls, got rc=%s calls=%s out=%r' % (rc, calls, out[:40]))
    return '2 empties retried; always-empty stops after 3'


def test_yt_pkg_picks_the_installed_youtube():
    """yt_pkg() run for real in bash against a fake a(): Google's YouTube if installed, else Rockchip's
    (the Hugsun), else Google's name so the checks fail by name."""
    import os, subprocess
    src = (ROOT / 'tools/stick-lib.sh').read_text(encoding='utf-8')
    m = re.search(r'^YT_CHOICES=.*?^yt_pkg\(\) \{.*?^\}\n', src, re.S | re.M)
    check(m, 'no YT_CHOICES followed by a yt_pkg() function in tools/stick-lib.sh')
    fake = 'a() { case " $INSTALLED " in *" $4 "*) echo "package:/data/app/$4/base.apk" ;; esac; }\n'
    script = 'set -euo pipefail\n' + fake + m.group(0) + 'yt_pkg\n'
    got = {}
    for inst in ('com.google.android.youtube.tv com.rockchip.android.youtube.tv', 'com.rockchip.android.youtube.tv', ''):
        r = subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=20, env=dict(os.environ, INSTALLED=inst))
        got[inst] = (r.returncode, r.stdout.strip())
    want = {'com.google.android.youtube.tv com.rockchip.android.youtube.tv': (0, 'com.google.android.youtube.tv'),
            'com.rockchip.android.youtube.tv': (0, 'com.rockchip.android.youtube.tv'), '': (0, 'com.google.android.youtube.tv')}
    check(got == want, 'yt_pkg: want %r, got %r' % (want, got))
    check(re.search(r'^\s*YT=\$\(yt_pkg\)', src[src.index('recovery_test() {'):], re.M), 'recovery_test does not pick YT with yt_pkg')
    return 'Google, else Rockchip, else Google by name'


def test_start_count_wait():
    src = (ROOT / REL).read_text(encoding='utf-8')
    m = re.search(r'^recovery_test\(\)\s*\{\n(.*?)^\}', src, re.S | re.M)
    check(m, 'no recovery_test() { ... }')
    body = code(m.group(1))
    start = body.find('normal restart draws the page')
    end = body.find('for i in 1 2;', start)
    check(0 <= start < end and 'reset check: early death' in body[end:], 'section 4 has no "normal restart draws the page" before its 2 more early deaths')
    between = body[start:end]
    check(not re.search(r'force-stop|am\s+start|early_death', between), 'the app is restarted during the wait that should clear the start count')
    waited = sum(int(n) for n in re.findall(r'\bsleep\s+(\d+)', between))
    check(waited >= 33, 'only %d s between the drawn restart and the 2 more early deaths: the start count clears after '
          'STABLE_MS (30 s on screen) and the ready poll takes up to 2 s, so at least 33 s' % waited)
    return '%d s' % waited


CASES = [(n[5:].replace('_', ' '), f) for n, f in list(globals().items()) if n.startswith('test_') and callable(f)]


def main():
    failed = 0
    for name, fn in CASES:
        try:
            detail = fn()
            print('ok   %s%s' % (name, ' (%s)' % detail if detail else ''))
        except Fail as e:
            failed += 1
            print('FAIL %s: %s' % (name, e))
        except Exception as e:  # a broken test is a failure, never a pass
            failed += 1
            print('FAIL %s: test error %s: %s' % (name, type(e).__name__, e))
    print('%d/%d passed' % (len(CASES) - failed, len(CASES)))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

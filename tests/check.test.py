#!/usr/bin/env python3
"""Offline tests for tools/check.py itself, mostly its failure paths: a JS syntax error (in every page
script we patch or own, each named), ES2015+ syntax in our own scripts or in the code the patch
injects into main.js (each construct named with its line; strings, comments, regex literals and
upstream's own code not judged), no node on PATH, PRIMARY_APPS
missing from main.js or without ids, metrics.js in another shape, an unknown test type, a misnamed
test that would never run (.mjs too), a test or script under a tests/ subdirectory that is not a helper
dir, the recursion guard (only a cf-check-guard.* file in the guard
dir counts), the summary line for an old patch file whose verify() returns no counts
(tools/rollback.sh can restore one), a tree with no tests (allowed only before de933e0, as git says), tests going
missing (every HEAD_TESTS/APP_TESTS entry of tools/rollback.sh must exist; in a rolled-back tree, tools/rolled-back-to's
SHA's entries instead), a test file with no code, and a test that exits 0 without its end marker.
Usage (from the repo root): python3 -I tests/check.test.py
Python 3 stdlib + node + git (the no-tests cases read this repo's objects); no network, no device.
The repo is only read: each case copies the
working tree's tools/ and web/ into a sandbox with its own tests/ (so the real tests, this one
included, never run recursively) and runs the sandbox's tools/check.py. Temp dirs under $TMPDIR,
else ~/scratch, removed on exit. Each case asserts the exit code AND the message.
Prints one PASS/FAIL line per case, then CHECK_TEST_OK, or CHECK_TEST_FAIL <n> with exit 1."""
import importlib.util, os, pathlib, re, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
FAILS = []
# An old android-patch.py (before counts): verify() raises on failure and returns None.
OLD_VERIFY = '\n_real_verify = verify\ndef verify(web):\n    _real_verify(web)\n'
# One test that passes (a test must end with its end marker): a sandbox with no tests at all is refused
# unless git says it predates de933e0.
OK_SH = 'echo OK_TEST_OK\n'
OK_TEST = {'ok.test.sh': OK_SH}
TESTS_SINCE = 'de933e0'   # the commit that added tests/ (tools/check.py)
# Lets a case edit main.js (which the real verify() would reject as a hand edit) to reach later code.
PASS_VERIFY = '\ndef verify(web):\n    return (1, 0)\n'


def case(name, fn):
    try:
        problem = fn()
    except Exception as e:  # a crash in the case itself is a failure, never a pass
        problem = 'test raised %s: %s' % (type(e).__name__, e)
    if problem:
        FAILS.append(name)
        print('FAIL %s: %s' % (name, problem))
    else:
        print('PASS %s' % name)


def main():
    base = pathlib.Path(os.environ.get('TMPDIR') or pathlib.Path.home() / 'scratch')
    base.mkdir(parents=True, exist_ok=True)
    tmp = pathlib.Path(tempfile.mkdtemp(prefix='cf-check-test.', dir=str(base)))
    try:
        cases(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    if FAILS:
        print('CHECK_TEST_FAIL %d' % len(FAILS))
        return 1
    print('CHECK_TEST_OK')
    return 0


def cases(tmp):
    n = [0]

    def sandbox(tests=None, patch_tail='', edit=None, lists=None):
        """A copy of tools/ + web/ with tests/ holding exactly TESTS {name: text}; returns its root.
        tools/rollback.sh's lists name what tests/ holds, unless LISTS is 'real' (HEAD's own) or
        (head entries, app entries), so check.py's "listed tests must exist" only fires when a case wants it."""
        n[0] += 1
        d = tmp / ('s%d' % n[0])
        shutil.copytree(ROOT / 'tools', d / 'tools')
        shutil.copytree(ROOT / 'web', d / 'web')
        (d / 'tests').mkdir()
        for name, text in (tests or {}).items():
            (d / 'tests' / name).parent.mkdir(parents=True, exist_ok=True)
            (d / 'tests' / name).write_text(text)
        if patch_tail:
            with open(d / 'tools' / 'android-patch.py', 'a') as f:
                f.write(patch_tail)
        if edit:
            edit(d)
        # A rollback commit carries its record; the sandbox is no such tree unless a case's edit made it one.
        if not (edit and getattr(edit, 'keeps_record', False)) and (ROOT / 'tools' / 'rolled-back-to').exists():
            (d / 'tools' / 'rolled-back-to').unlink(missing_ok=True)
        if lists != 'real':
            have = ['tests/' + p.name for p in sorted((d / 'tests').iterdir())] if (d / 'tests').is_dir() else []
            head, app = lists or (have, have)
            rb = d / 'tools' / 'rollback.sh'
            text = re.sub(r'^HEAD_TESTS=\(.*\)$', lambda m: 'HEAD_TESTS=(%s)' % ' '.join(head), rb.read_text(), flags=re.M)
            rb.write_text(re.sub(r'^APP_TESTS=\(.*\)$', lambda m: 'APP_TESTS=(%s)' % ' '.join(app), text, flags=re.M))
        return d

    def check(d, extra_env=None):
        env = dict(os.environ, TMPDIR=str(tmp))
        env.pop('CLEANFRAME_NESTED_GUARD', None)
        env.update(extra_env or {})
        r = subprocess.run([sys.executable, str(d / 'tools' / 'check.py')], capture_output=True, text=True,
                           cwd=str(d), env=env, timeout=120)
        return r.returncode, r.stdout + r.stderr

    def expect(d, want_rc, *want, absent=('Traceback',), env=None):
        rc, out = check(d, env)
        if (rc == 0) != (want_rc == 0):
            return 'exit %d, want %s; output: %s' % (rc, 'zero' if want_rc == 0 else 'non-zero', out.strip()[-600:])
        for w in want:
            if not (w.search(out) if hasattr(w, 'search') else w in out):
                return 'output lacks %r: %s' % (getattr(w, 'pattern', w), out.strip()[-600:])
        for a in absent:
            if a in out:
                return 'output has %r: %s' % (a, out.strip()[-600:])
        return None

    def baseline():
        # tools/rollback.sh keeps this test at HEAD (it tests check.py) but rolls tools/android-patch.py
        # back: an older patch file's verify() returns no counts, and check.py must then say "count
        # unknown" instead of the real-counts form this case wants at HEAD.
        spec = importlib.util.spec_from_file_location('android_patch_baseline', ROOT / 'tools' / 'android-patch.py')
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        try:
            counts = mod.verify(ROOT / 'web')
        except Exception:
            counts = True   # check.py fails on it too; the real-counts expectation reports it
        if counts:
            return expect(sandbox(OK_TEST), 0, re.compile(
                r'upstream [0-9a-f]{7} \(\d+ files\) \+ android patch \+ \d+ of ours \+ 1 offline tests \(ok\.test\.sh\)'))
        return expect(sandbox(OK_TEST), 0, '(count unknown (old patch file)) + android patch + 1 offline tests (ok.test.sh)',
                      absent=('Traceback', 'of ours'))
    case('baseline-passes-with-real-counts', baseline)

    case('old-patch-file-count-unknown-not-made-up', lambda: expect(
        sandbox(OK_TEST, patch_tail=OLD_VERIFY), 0, '(count unknown (old patch file)) + android patch + 1 offline tests (ok.test.sh)',
        absent=('Traceback', '2 files', 'of ours')))

    def syntax_error(d):
        with open(d / 'web' / 'device.js', 'a') as f:
            f.write('\nfunction (\n')
    case('js-syntax-error-named', lambda: expect(sandbox(edit=syntax_error), 1, 'syntax: device.js'))

    # Every page script we patch (FILES) or own (OURS) is syntax-checked, not only main.js, device.js and
    # metrics.js: a syntax error in each one, alone, is named. PASS_VERIFY keeps verify() out of the way
    # (a patched file's edit would otherwise fail there first). The list is read from android-patch.py
    # as check.py reads it: this test stays at HEAD in a rollback, which may restore an older patch file
    # (fewer OURS, or none), and then checks that file's list.
    spec = importlib.util.spec_from_file_location('android_patch_js', ROOT / 'tools' / 'android-patch.py')
    ap = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ap)
    page_js = sorted({'main.js', 'device.js', 'assets/icons/metrics.js'}
                     | {n for n, _ in getattr(ap, 'FILES', ()) if n.endswith('.js')}
                     | {n for n in getattr(ap, 'OURS', {}) if n.endswith('.js')})
    def break_js(rel):
        def edit(d):
            with open(d / 'web' / rel, 'a') as f:
                f.write('\nfunction (\n')
        return edit
    def every_page_js():
        bad = []
        for rel in page_js:
            problem = expect(sandbox(patch_tail=PASS_VERIFY, edit=break_js(rel)), 1, 'syntax: %s\n' % rel)
            if problem: bad.append('%s: %s' % (rel, problem))
        return '; '.join(bad) or None
    case('js-syntax-error-in-every-page-script-named', every_page_js)

    # ES5 only (docs/RULES.md rule 2): node's parse accepts any syntax, so check.py lexes our own scripts
    # (every .js in OURS/OPTIONAL but the vendored QR library, read from android-patch.py) and the
    # code android-patch.py injects into main.js. Each ES2015+ construct is named with file and line;
    # the same words in strings, comments and regex literals, ES5 property names (o.class, {let: 1})
    # and division are not constructs. An older patch file (rollback) without OURS checks no own file.
    own_js = sorted(n for n in set(getattr(ap, 'OURS', {})) | set(getattr(ap, 'OPTIONAL', {}))
                    if n.endswith('.js') and n != getattr(ap, 'QR_FILE', None) and (ROOT / 'web' / n).is_file())
    ES6 = [('let es5a = 1;', 'let'), ('const es5b = 2;', 'const'), ('class Es5C {}', 'class'),
           ('var es5d = (a) => a;', 'arrow function'), ('var es5e = `t${1}`;', 'template literal'),
           ('var {es5f} = {};', 'destructuring'), ('var [es5g] = [1];', 'destructuring'),
           ('Math.max(...[1, 2]);', 'spread/rest (...)'), ('function es5h(a, b = 1) { return a + b; }', 'default parameter'),
           ('function es5k({a}) { return a; }', 'destructuring parameter'), ('for (var es5v of [1]) {}', 'for-of'),
           ('function* es5gen() {}', 'generator function*'), ('async function es5af() {}', 'async function'),
           ('var es5p = 2 ** 3;', 'ES2016+ operator **'), ('var es5q = es5p?.a;', 'ES2016+ operator ?.'),
           ('var es5m = {f() { return 1; }};', 'shorthand method'), ('var es5gm = {*g() {}};', 'generator method'),
           ('var es5sp = {es5m, es5p};', 'shorthand property'), ('var es5ck = {[es5p + 1]: 1};', 'computed property name'),
           ('var es5ru = /x/u;', 'regex flag u'), ('var es5ry = /x/y;', 'regex flag y'),
           ('try {} catch ({message}) {}', 'destructuring catch parameter'), ('try {} catch {}', 'optional catch binding'),
           ('var es5x, es5z; [es5x, es5z] = [1, 2];', 'destructuring assignment'), ('({a: es5x} = {a: 1});', 'destructuring assignment'),
           ('function Es5T() { return new.target; }', 'new.target'), ('var es5cp = "\\u{1F600}";', 'code point escape \\u{...}'),
           # ES2017-2020 that node's parse (new Function) also accepts
           ('function es5tc(a, b,) { return a + b; }', 'trailing comma in parameters or arguments'),
           ('es5h(1, 2,);', 'trailing comma in parameters or arguments'),
           ('var es5ng = /(?<y>a)/;', 'regex named group'), ('var es5lb = /(?<=a)b/;', 'regex lookbehind'),
           ('var es5nb = /(?<!a)b/;', 'regex lookbehind'), ('var es5im = import("x");', 'import()'),
           # an operand's ++/-- ends the expression: read as a regex, the "/" hid the rest of the line
           ('var es5u1 = 1; es5u1++ / 2; let es5u2 = 1;', 'let'), ('es5u1-- / 2; const es5u3 = 1;', 'const'),
           ('var es5a2 = [1]; es5a2[0]++ / 2; let es5u4 = 1;', 'let')]
    ES6_AFTER_BRACE = [
           # a "/" after a brace that ended a statement starts a regex: read as division, the regex went unjudged
           ("if (true) {} /(?<es5g1>a)/.test('a');", 'regex named group'), ('function es5d1() {} /(?<=a)b/.test("x");', 'regex lookbehind'),
           ('for (;;) { break; } /(?<!a)b/.test("x");', 'regex lookbehind'), ('try {} finally {} /(?<es5g2>a)/.test("x");', 'regex named group'),
           ('{ } /x/u.test("x");', 'regex flag u'), ('var es5s1 = function () {}; if (es5s1) { es5s1(); } else { } /x/y.test("x");', 'regex flag y'),
           # ... and after one that closed an expression it is division: read as a regex, it hid the rest of the line
           ('var es5e1 = function () {} / 2; let es5e2 = 1;', 'let'), ('var es5e3 = function es5e4() {} / 2; const es5e5 = 1;', 'const'),
           ('var es5e6 = {a: 1} / 2; let es5e7 = 1;', 'let')]
    ES5_OK = ("\nvar es5ok = 'let const => `class` ... ' + \"for (x of y)\";   // let x = `y` => z\n"
              "/* const a = () => 1; class B {} */ var es5r = /=>`|\\/[`]/g, es5o = {let: 1, class: 2, const: 3};\n"
              "es5o.class = es5o.let / 2 / es5o.const; if (es5o) /`x/.test(es5ok); for (var es5i = 0; es5i < 1; es5i++) {}\n"
              "for (var es5k in es5o) {} function es5f(a, b) { return a ? b : function () { return a / b; }; }\n"
              # ES5 object literals, blocks and strings that look like the ES2015+ ones checked above
              "var es5ac = {get x() { return 1; }, set x(v) {}, 'a': 1, 2: 3, get: 4, set: 5, f: function () {}, t: 1,};\n"
              "es5o['k'] = es5o[0] = es5f(1, 2)[0]; switch (es5ok) { case 1: { break; } default: {} } es5lbl: { break es5lbl; }\n"
              "var es5tn = es5ok ? {x: 1} : {y: 2}; if (es5ok) { es5f } else { es5f } do {} while (0); es5o.new = /a[/]b/gim;\n"
              "var es5es = '\\u0041 \\\\u{1F600}'; try {} catch (es5e) {} (function () { return {a: es5ac}; })();\n"
              # the ES2018 groups' look-alikes are ES5 regexes, a trailing comma in a literal is ES5, import/export
              # are property names, and a prefix ++ is an operator: a "/" after a postfix one is division
              "var es5rg = /\\(?<x|[(?<]a|(?:b)|(?=c)|(?!d)/, es5tl = [1, 2,], es5io = {import: 1, export: 2}; es5io.import = es5io.export;\n"
              # a "/" after a brace that closed an expression is division, and "/x/" after a statement a plain regex
              "var es5dv = {a: 1} / 2 / 1, es5dw = function () { return 1; } / 2 / 1, es5dx = function es5nm() {} / 2 / 3; if (es5dv) {} /`x/.test(es5ok);\n"
              "(function () {} / 2 / 1); es5f(function () {} / 2 / 1, 3 / 1);\n"
              "var es5n2 = 4; es5n2++ / 2 / es5n2; var es5n3 = (es5n2)-- / 2 / 1; ++es5n2; es5n2 = es5n2++ / 2;\n")
    def es5_constructs():
        if not own_js: print('  (this patch file lists no own .js: nothing to check)'); return None
        rel = 'device.js' if 'device.js' in own_js else own_js[0]
        src = (ROOT / 'web' / rel).read_text(encoding='utf-8')
        first = src.count('\n') + 2   # the line of the first appended construct
        def edit(d):
            with open(d / 'web' / rel, 'a', encoding='utf-8') as f:
                f.write('\n' + '\n'.join(js for js, _ in ES6) + '\n')
        want = ['ES5: not ES5'] + ['web/%s:%d: %s' % (rel, first + i, w) for i, (_, w) in enumerate(ES6)]
        return expect(sandbox(patch_tail=PASS_VERIFY, edit=edit), 1, *want)
    case('es5-every-construct-named-with-line', es5_constructs)
    def es5_after_brace():   # its own case: check.py names the first 40 problems
        if not own_js: return None
        rel = 'device.js' if 'device.js' in own_js else own_js[0]
        first = (ROOT / 'web' / rel).read_text(encoding='utf-8').count('\n') + 2
        def edit(d):
            with open(d / 'web' / rel, 'a', encoding='utf-8') as f:
                f.write('\n' + '\n'.join(js for js, _ in ES6_AFTER_BRACE) + '\n')
        return expect(sandbox(patch_tail=PASS_VERIFY, edit=edit), 1, *['web/%s:%d: %s' % (rel, first + i, w) for i, (_, w) in enumerate(ES6_AFTER_BRACE)])
    case('es5-regex-after-a-statement-brace-judged', es5_after_brace)
    def es5_clean():
        if not own_js: return None
        def edit(d):
            with open(d / 'web' / own_js[0], 'a', encoding='utf-8') as f: f.write(ES5_OK)
        return expect(sandbox(OK_TEST, patch_tail=PASS_VERIFY, edit=edit), 0, 'check ok', absent=('Traceback', 'ES5:'))
    case('es5-strings-comments-regex-and-property-names-pass', es5_clean)
    # Every own script, each alone (the list is android-patch.py's, not one in check.py), and a new file
    # of ours: listed in OURS it is checked at once.
    def es5_every_own():
        if not own_js: return None   # an older patch file (rollback) with no OURS: nothing of ours to check
        bad = []
        for rel in own_js:
            def edit(d, rel=rel):
                with open(d / 'web' / rel, 'a', encoding='utf-8') as f: f.write('\nconst es5 = 1;\n')
            problem = expect(sandbox(patch_tail=PASS_VERIFY, edit=edit), 1, re.compile(r'web/%s:\d+: const' % re.escape(rel)))
            if problem: bad.append('%s: %s' % (rel, problem))
        def new_file(d):
            (d / 'web' / 'zz-new-own.js').write_text('var a = 1;\nvar b = () => a;\n', encoding='utf-8')
        problem = expect(sandbox(patch_tail=PASS_VERIFY + "\nOURS['zz-new-own.js'] = 'tests/check.test.py'\n", edit=new_file),
                         1, 'web/zz-new-own.js:2: arrow function')
        if problem: bad.append('new own file: %s' % problem)
        return '; '.join(bad) or None
    case('es5-every-own-script-and-a-new-one-checked', es5_every_own)
    # Code the patch injects into main.js is ours: an arrow function in a MAIN new text, on its line, is
    # named; the same arrow in main.js outside every new text is upstream's and not judged.
    def es5_injected():
        if not dict(getattr(ap, 'FILES', ())).get('main.js'): return None
        def edit(d):
            with open(d / 'web' / 'main.js', 'a', encoding='utf-8') as f:
                f.write('\nvar zzUp = 1;\nvar zzInjected = () => 1;\n')
        rc, out = check(sandbox(OK_TEST, patch_tail=PASS_VERIFY + "\nMAIN.append(('zzOld', 'var zzInjected = () => 1;', 1))\n", edit=edit))
        n = (ROOT / 'web' / 'main.js').read_text(encoding='utf-8').count('\n') + 3
        if rc == 0 or 'web/main.js:%d (injected by tools/android-patch.py): arrow function' % n not in out:
            return 'injected arrow not named at web/main.js:%d (exit %d): %s' % (n, rc, out.strip()[-600:])
        return expect(sandbox(OK_TEST, patch_tail=PASS_VERIFY, edit=edit), 0, 'check ok', absent=('Traceback', 'ES5:'))
    case('es5-injected-code-checked-upstream-not', es5_injected)
    # The new constructs keep that attribution: on a new text's line, named as injected; elsewhere in main.js, upstream's.
    def es5_injected_new():
        if not dict(getattr(ap, 'FILES', ())).get('main.js'): return None
        def edit(d):
            with open(d / 'web' / 'main.js', 'a', encoding='utf-8') as f:
                f.write('\nvar zzUp = 1;\nvar zzInj = /(?<zz>a)/, zzI2 = zzUp++ / 2; let zzAfter = String(1,);\n')
        rc, out = check(sandbox(OK_TEST, patch_tail=PASS_VERIFY + "\nMAIN.append(('zzOld', 'var zzInj = /(?<zz>a)/, zzI2 = zzUp++ / 2; let zzAfter = String(1,);', 1))\n", edit=edit))
        n = (ROOT / 'web' / 'main.js').read_text(encoding='utf-8').count('\n') + 3
        for what in ('regex named group', 'let', 'trailing comma in parameters or arguments'):
            if rc == 0 or 'web/main.js:%d (injected by tools/android-patch.py): %s' % (n, what) not in out:
                return '%s not named at web/main.js:%d (exit %d): %s' % (what, n, rc, out.strip()[-600:])
        return expect(sandbox(OK_TEST, patch_tail=PASS_VERIFY, edit=edit), 0, 'check ok', absent=('Traceback', 'ES5:'))
    case('es5-newer-constructs-attributed-injected-upstream-not', es5_injected_new)
    # import.meta, import and export declarations stop node's parse first ("syntax:"), so the lexer is
    # asked directly: its functions, cut out of tools/check.py, on strings, with the module-level code left out.
    def es5_modules():
        src = (ROOT / 'tools' / 'check.py').read_text(encoding='utf-8')
        ns = {'re': re}
        exec(src[src.index('_ES5_PUNCT = '):src.index('OWN_JS = ')], ns)
        for js, what in (('var a = import.meta.url;', 'import.meta'), ('import x from "y";', 'import declaration'),
                         ('import "y";', 'import declaration'), ('export var a = 1;', 'export'), ('export default 1;', 'export')):
            got = [w for _, w in ns['es5_problems'](js)]
            if what not in got: return '%r: want %r, got %r' % (js, what, got)
        for js in ('o.import = 1; o.export = 2;', 'var o = {import: 1, export: 2};'):
            got = ns['es5_problems'](js)
            if got: return '%r is ES5, got %r' % (js, got)
        return None
    case('es5-module-syntax-named', es5_modules)

    # No node: a named refusal (exit 1), never a FileNotFoundError traceback. PATH holds only an empty dir.
    def no_node():
        empty = tmp / 'empty-path'
        empty.mkdir(exist_ok=True)
        return expect(sandbox(OK_TEST), 1, 'refusing: no node on PATH', absent=('Traceback', 'FileNotFoundError'),
                      env={'PATH': str(empty)})
    case('no-node-refused-named', no_node)

    def no_primary(d):
        p = d / 'web' / 'main.js'
        s = p.read_text()
        if 'var PRIMARY_APPS = [' not in s:
            raise AssertionError('main.js has no PRIMARY_APPS to remove (update this test)')
        p.write_text(s.replace('var PRIMARY_APPS = [', 'var PRIMARY_APPZ = ['))
    case('primary-apps-missing-named', lambda: expect(
        sandbox(patch_tail=PASS_VERIFY, edit=no_primary), 1, 'web/main.js: no "var PRIMARY_APPS = [ ... ];"',
        absent=('Traceback', 'ValueError')))

    # PRIMARY_APPS whose entries no longer match id: '<id>' (upstream changed its shape): no ids would
    # check no marks at all and pass, so it fails, named.
    def no_ids(d):
        p = d / 'web' / 'main.js'
        s = p.read_text()
        a = s.find('var PRIMARY_APPS = [')
        b = s.find('];', a)
        if a < 0 or b < 0 or "id: '" not in s[a:b]:
            raise AssertionError('main.js has no PRIMARY_APPS ids to reshape (update this test)')
        p.write_text(s[:a] + s[a:b].replace("id: '", "id:  '") + s[b:])   # still valid JS
    case('primary-apps-without-ids-fails', lambda: expect(
        sandbox(patch_tail=PASS_VERIFY, edit=no_ids), 1, "web/main.js: PRIMARY_APPS has no id: '<id>' entries"))

    # metrics.js in another shape: a named error, never an AttributeError/ValueError traceback.
    def metrics_edit(old, new):
        def edit(d):
            p = d / 'web' / 'assets' / 'icons' / 'metrics.js'
            s = p.read_text()
            if s.count(old) != 1:
                raise AssertionError('metrics.js has no %r (update this test)' % old)
            p.write_text(s.replace(old, new))
        return edit
    for name, old, new in (('renamed', 'FrameIconMetrics = {', 'FrameIconMetricz = {'),
                           ('not-json', 'FrameIconMetrics = {', 'FrameIconMetrics = {zz: 1, ')):
        case('metrics-js-%s-named' % name, lambda old=old, new=new: expect(
            sandbox(patch_tail=PASS_VERIFY, edit=metrics_edit(old, new)), 1,
            'web/assets/icons/metrics.js: no "FrameIconMetrics = {...};" JSON object',
            absent=('Traceback', 'AttributeError', 'JSONDecodeError')))

    # The recursion guard counts only a file made the way check.py makes them: cf-check-guard.* directly
    # in the guard dir ($TMPDIR here). Holding the token is not enough. A guarded tools.test.sh that
    # fails proves which way it went: skipped (exit 0) or run (exit 1).
    def guard_case(rel, want_rc, *want):
        def run():
            token = 'ab' * 16
            g = tmp / rel
            g.parent.mkdir(parents=True, exist_ok=True)
            g.write_text(token)
            try:
                return expect(sandbox({'tools.test.sh': 'exit 1\n'}), want_rc, *want,
                              env={'CLEANFRAME_NESTED_GUARD': '%s:%s' % (g, token)})
            finally:
                g.unlink()
        return run
    case('guard-made-like-check-skips-nested', guard_case('cf-check-guard.case', 0, 'nested: skipped tools.test.sh'))
    case('guard-other-name-ignored', guard_case('other-guard', 1, 'ignoring CLEANFRAME_NESTED_GUARD', 'tests/tools.test.sh'))
    case('guard-other-dir-ignored', guard_case('sub/cf-check-guard.case', 1, 'ignoring CLEANFRAME_NESTED_GUARD', 'tests/tools.test.sh'))

    case('unknown-test-type-named', lambda: expect(
        sandbox({'x.test.txt': 'not a test\n'}), 1, 'tests/x.test.txt: unknown test type'))

    case('misnamed-tests-named-not-skipped', lambda: expect(
        sandbox({'x_test.py': 'raise SystemExit(1)\n', 'helper.sh': 'exit 1\n', 'ok.test.sh': OK_SH,
                 'x.mjs': 'process.exit(1)\n'}), 1,   # node never runs tests/x.mjs (only *.test.js)
        'tests/helper.sh, tests/x.mjs, tests/x_test.py: not named *.test.{js,sh,py}'))

    # A failing test's FAIL lines are named however early they came: the tail alone (3000 characters)
    # hid which case of tests/tools.test.sh, or which nested gate inside it, had failed.
    long_fail = ('echo "PASS first"\necho "FAIL early-case: the reason"\necho "    | FAIL nested-case: deeper"\n'
                 'for i in $(seq 2000); do echo "PASS filler $i"; done\nexit 1\n')
    case('failing-test-names-early-fail-lines', lambda: expect(
        sandbox({'long.test.sh': long_fail}), 1, 'tests failed: bash tests/long.test.sh (exit 1)',
        'failed cases:\nFAIL early-case: the reason\n    | FAIL nested-case: deeper\n--- end of output ---', 'PASS filler 2000'))

    # A stale tools/__pycache__ is never used: a .pyc of android-patch.py whose recorded mtime and size
    # still match after the file changed (same size, same second: tools/rollback.sh swaps patch files
    # that differ only in a pinned SHA) made check.py run the OLD code (2026-10-31). The edit swaps
    # the first line (every version's shebang) for a same-size line that stops the import, so it holds
    # for any patch file a rollback restores.
    probe = "exit('pyc-probe')"   # 17 bytes; site's exit() (check.py runs without -S)
    def stale_pyc(d):
        import py_compile
        pf = d / 'tools' / 'android-patch.py'
        py_compile.compile(str(pf), doraise=True, invalidation_mode=py_compile.PycInvalidationMode.TIMESTAMP)
        st = pf.stat()
        first, rest = pf.read_bytes().split(b'\n', 1)
        if len(first) < len(probe):
            raise AssertionError('first line of android-patch.py is under %d bytes (update this test)' % len(probe))
        pf.write_bytes((probe + '#' * (len(first) - len(probe))).encode() + b'\n' + rest)
        os.utime(pf, ns=(st.st_atime_ns, st.st_mtime_ns))
        if pf.stat().st_size != st.st_size:
            raise AssertionError('the edit changed the size (update this test)')
    case('stale-patch-pyc-not-used', lambda: expect(sandbox(edit=stale_pyc), 1, 'pyc-probe'))

    # Discovery is top level only: a test or script in a subdirectory would never run, so it fails, named,
    # unless the subdirectory is a helper dir (its own runner tests/<dir>.test.*, or java/ and fixtures/).
    case('subdir-tests-and-scripts-named-not-skipped', lambda: expect(
        sandbox({'ok.test.sh': OK_SH, 'sub/x.test.js': 'process.exit(1)\n', 'sub/helper.py': 'raise SystemExit(1)\n',
                 'a/b/deep.test.sh': 'exit 1\n', 'sub/notes.md': 'notes\n'}), 1,
        'tests/a/b/deep.test.sh, tests/sub/helper.py, tests/sub/x.test.js: under a subdirectory of tests/ that is not a helper dir'))
    case('helper-dirs-with-runner-allowed', lambda: expect(
        sandbox({'ok.test.sh': OK_SH, 'ok/data/synthetic.py': 'raise SystemExit(1)\n', 'ok/Case.java': 'class Case {}\n',
                 'java/Helper.sh': 'exit 1\n', 'fixtures/lib/expected.json': '{}\n'}), 0, '+ 1 offline tests (ok.test.sh)'))
    case('runner-of-another-name-is-not-a-helper-dir', lambda: expect(
        sandbox({'ok.test.sh': OK_SH, 'okay/run.sh': 'exit 1\n'}), 1, 'tests/okay/run.sh: under a subdirectory'))

    case('well-named-test-runs-non-scripts-allowed', lambda: expect(
        sandbox({'ok.test.sh': OK_SH, 'README.md': 'notes\n'}), 0, '+ 1 offline tests (ok.test.sh)'))

    # No tests (tests/ empty or missing) passes only in a tree from before de933e0, the commit that
    # added them, and only when git says so: the sandbox becomes a git repo that reads this repo's
    # objects (alternates, nothing written there) with HEAD a new commit on top of de933e0 (descends:
    # refused) or de933e0^ (predates: allowed). A sandbox that is not a git checkout cannot tell: refused.
    git_env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1', GIT_AUTHOR_NAME='check test',
                   GIT_AUTHOR_EMAIL='check-test@invalid', GIT_COMMITTER_NAME='check test', GIT_COMMITTER_EMAIL='check-test@invalid')
    def git(*args, cwd=ROOT):
        return subprocess.run(['git', '-C', str(cwd)] + list(args), check=True, capture_output=True, text=True,
                              env=git_env).stdout.strip()

    def at(rev, no_dir=False):
        def edit(d):
            objects = pathlib.Path(git('rev-parse', '--path-format=absolute', '--git-common-dir')) / 'objects'
            git('init', '-q', cwd=d)
            (d / '.git' / 'objects' / 'info' / 'alternates').write_text(str(objects) + '\n')
            if rev == 'child':
                head = git('commit-tree', TESTS_SINCE + '^{tree}', '-p', TESTS_SINCE, '-m', 'on top of ' + TESTS_SINCE, cwd=d)
            else:
                head = git('rev-parse', '--verify', rev + '^{commit}')
            git('update-ref', 'HEAD', head, cwd=d)
            if no_dir:
                shutil.rmtree(d / 'tests')
        return edit
    case('no-tests-descends-from-de933e0-refused', lambda: expect(
        sandbox(edit=at('child')), 1, 'no offline tests under tests/, but HEAD descends from de933e0'))
    case('no-tests-dir-descends-from-de933e0-refused', lambda: expect(
        sandbox(edit=at('child', no_dir=True)), 1, 'no offline tests under tests/, but HEAD descends from de933e0'))
    case('no-tests-before-de933e0-allowed', lambda: expect(
        sandbox(edit=at(TESTS_SINCE + '^', no_dir=True)), 0, '+ no offline tests (tree predates de933e0)'))
    # The published tree: tools/public-tree skips the tests it names, says so, and is refused where the history exists.
    def public_tree(names, then=None):
        def edit(d):
            if then: then(d)
            (d / 'tools' / 'public-tree').write_text(names + '\n')
        return edit
    no_git = {'GIT_CEILING_DIRECTORIES': str(tmp)}
    case('public-tree-skips-named-test-and-says-so', lambda: expect(
        sandbox({'ok.test.sh': OK_SH, 'gone.test.sh': 'exit 1\n'}, edit=public_tree('gone.test.sh')), 0,
        'NOT RUN here (they need the private history): gone.test.sh', env=no_git))
    case('public-tree-with-history-refused', lambda: expect(
        sandbox(OK_TEST, edit=public_tree('ok.test.sh', at('child'))), 1, 'this repository has the history its tests need'))
    case('public-tree-unknown-test-refused', lambda: expect(
        sandbox(OK_TEST, edit=public_tree('nope.test.sh')), 1, 'names tests that do not exist: nope.test.sh', env=no_git))
    case('no-tests-not-git-refused', lambda: expect(
        sandbox(), 1, 'git cannot tell whether HEAD predates de933e0', env={'GIT_CEILING_DIRECTORIES': str(tmp)}))

    # Tests going missing fails (a review replaced tests/ with one empty tests/empty.test.js and exit 0 came back):
    # with HEAD's own lists, one empty test; a listed test deleted, on either list, a helper dir emptied.
    case('tests-replaced-by-one-empty-file-refused', lambda: expect(
        sandbox({'empty.test.js': ''}, lists='real'), 1, 'tests missing: ', 'tests/tools.test.sh', 'tests/check.test.py'))
    A, B = 'tests/ok.test.sh', 'tests/other.test.sh'
    both = {'ok.test.sh': OK_SH, 'other.test.sh': OK_SH}
    case('listed-tests-present-pass', lambda: expect(sandbox(both, lists=([A], [B])), 0, '+ 2 offline tests'))
    case('deleted-app-test-refused', lambda: expect(sandbox(OK_TEST, lists=([A], [B])), 1, 'tests missing: tests/other.test.sh'))
    case('deleted-head-test-refused', lambda: expect(sandbox(OK_TEST, lists=([B], [A])), 1, 'tests missing: tests/other.test.sh'))
    case('empty-helper-dir-refused', lambda: expect(
        sandbox(OK_TEST, edit=lambda d: (d / 'tests' / 'java').mkdir(), lists=([A], ['tests/java'])), 1, 'tests missing: tests/java'))
    # No lists to read: refused, not "nothing is required". tools.test.sh's probe of a missing tests/ runs through here too.
    def no_lists(line):
        def edit(d):
            rb = d / 'tools' / 'rollback.sh'
            rb.write_text(re.sub(r'^%s=.*$' % line, '', rb.read_text(), flags=re.M))
        return edit
    case('no-head-tests-line-refused', lambda: expect(
        sandbox(OK_TEST, edit=no_lists('HEAD_TESTS'), lists='real'), 1, 'refusing: no HEAD_TESTS=(...) line'))
    case('no-app-tests-line-refused', lambda: expect(
        sandbox(OK_TEST, edit=no_lists('APP_TESTS'), lists='real'), 1, 'refusing: no APP_TESTS=(...) line'))
    case('empty-list-refused', lambda: expect(sandbox(OK_TEST, lists=([], [A])), 1, 'refusing: no HEAD_TESTS=(...) line'))
    case('no-rollback-sh-refused', lambda: expect(
        sandbox(OK_TEST, edit=lambda d: (d / 'tools' / 'rollback.sh').unlink(), lists='real'), 1, 'refusing: cannot read tools/rollback.sh'))

    # The per-test timeout is a hang guard: tests/tools.test.sh took ~16 min alone at 5acb6ea and overran the old 900 s under
    # load. The default stays at 1800 s or more, and the README says what it is.
    def default_timeout():
        code = re.search(r"CLEANFRAME_TEST_TIMEOUT'\) or (\d+)\)", (ROOT / 'tools' / 'check.py').read_text(encoding='utf-8'))
        doc = re.search(r'`CLEANFRAME_TEST_TIMEOUT`, default (\d+) s', (ROOT / 'README.md').read_text(encoding='utf-8'))
        if not code or not doc: return 'no default found in tools/check.py or README.md (update this test)'
        if int(code.group(1)) < 1800 or code.group(1) != doc.group(1): return 'check.py %s, README %s; want the same, >= 1800' % (code.group(1), doc.group(1))
        return None
    case('test-timeout-default-1800-and-documented', default_timeout)

    # A test file with no code could only pass, whatever its language; so a test that exits 0 must also end
    # with its end marker, the last line it prints, a complete and non-zero count if it is one.
    for name, text in (('empty.test.js', ''), ('comment.test.js', "// nothing\n/* at all */\n'use strict';\n"),
                       ('empty.test.sh', '#!/bin/bash\n# nothing\n\n'), ('doc.test.py', '"""Only a docstring."""\npass\n')):
        case('no-code-test-refused-%s' % name, lambda name=name, text=text: expect(
            sandbox({name: text, 'ok.test.sh': OK_SH}), 1, 'tests/%s: no code in it' % name))
    for name, out in (('no-output', ':'), ('other-last-line', 'echo FOO_TEST_OK; echo more'), ('lower-case', 'echo foo_ok'),
                      ('zero-of-zero', 'echo 0/0 passed'), ('part-of-all', 'echo 3/4 passed'), ('ok-part', 'echo OK 3/4 passed'),
                      ('a-fail-marker', 'echo FOO_TEST_FAIL 0')):
        case('no-end-marker-refused-%s' % name, lambda out=out: expect(
            sandbox({'x.test.sh': out + '\n'}), 1, 'tests/x.test.sh exited 0 without its end marker'))
    for name, out in (('name-ok', 'echo FOO_TEST_OK 3 cases'), ('ok-count', 'echo OK 3/3 passed in 1 ms'), ('count-passed', 'echo 7/7 passed')):
        case('end-marker-accepted-%s' % name, lambda out=out: expect(sandbox({'x.test.sh': out + '\n'}), 0, '+ 1 offline tests (x.test.sh)'))

    # A tree made by tools/rollback.sh holds that SHA's app tests under HEAD's lists. Each case builds that history in
    # the sandbox: commit S, the rollback commit R (its record names S), then AFTER, one commit per {file: text or
    # None to delete}, and WT left uncommitted. S's tests are excused only as they were: exit code alone while the
    # blob is S's. A test added or changed since is held to the full rules.
    H, OLD, NEWER, LATER = 'tests/h.test.sh', 'tests/old.test.sh', 'tests/newer.test.sh', 'tests/later.test.sh'
    S_TESTS = {'h.test.sh': OK_SH, 'old.test.sh': 'exit 0\n'}
    LISTS = ([H], [OLD, NEWER, LATER])
    def rolled_history(after=(), wt=None):
        def write(d, files):
            for name, text in files.items():
                p = d / 'tests' / name
                if text is None: p.unlink()
                else: p.write_text(text)
        def edit(d):
            git('init', '-q', cwd=d)
            write(d, S_TESTS)
            git('add', 'tests', cwd=d); git('commit', '-q', '-m', 'S', cwd=d)
            (d / 'tools' / 'rolled-back-to').write_text(git('rev-parse', 'HEAD', cwd=d) + '\n')
            git('add', 'tools/rolled-back-to', cwd=d); git('commit', '-q', '-m', 'R', cwd=d)
            for files in after:
                write(d, files)
                git('add', '-A', 'tests', cwd=d); git('commit', '-q', '-m', 'after', cwd=d)
            write(d, wt or {})
        edit.keeps_record = True
        return edit
    def rolled_case(after=(), wt=None):
        return sandbox(edit=rolled_history(after, wt), lists=LISTS)
    case('rolled-back-tree-needs-its-own-tests-not-newer-ones', lambda: expect(rolled_case(), 0, '+ 2 offline tests'))
    case('rolled-back-tree-with-its-test-deleted-refused', lambda: expect(
        rolled_case(wt={'old.test.sh': None}), 1, 'tests missing: tests/old.test.sh'))
    case('rolled-back-tree-without-a-head-test-refused', lambda: expect(
        rolled_case(wt={'h.test.sh': None}), 1, 'tests missing: tests/h.test.sh'))
    case('rolled-back-tree-head-test-still-needs-marker', lambda: expect(
        rolled_case(wt={'h.test.sh': 'exit 0\n'}), 1, 'tests/h.test.sh exited 0 without its end marker'))
    case('rolled-back-test-changed-since-needs-marker-committed', lambda: expect(
        rolled_case([{'old.test.sh': 'true\n'}]), 1, 'tests/old.test.sh exited 0 without its end marker'))
    case('rolled-back-test-changed-since-needs-marker-uncommitted', lambda: expect(
        rolled_case(wt={'old.test.sh': 'true\n'}), 1, 'tests/old.test.sh exited 0 without its end marker'))
    case('rolled-back-test-changed-with-marker-passes', lambda: expect(rolled_case([{'old.test.sh': OK_SH}]), 0, 'check ok'))
    case('test-added-since-rollback-needs-marker', lambda: expect(
        rolled_case([{'later.test.sh': 'exit 0\n'}]), 1, 'tests/later.test.sh exited 0 without its end marker'))
    case('test-added-since-rollback-with-marker-passes', lambda: expect(rolled_case([{'later.test.sh': OK_SH}]), 0, '+ 3 offline tests'))
    case('test-added-since-rollback-deleted-refused', lambda: expect(
        rolled_case([{'later.test.sh': OK_SH}, {'later.test.sh': None}]), 1, 'tests missing: tests/later.test.sh'))
    case('test-added-since-rollback-deleted-uncommitted-refused', lambda: expect(
        rolled_case([{'later.test.sh': OK_SH}], {'later.test.sh': None}), 1, 'tests missing: tests/later.test.sh'))
    # While tools/rollback.sh is making the commit, HEAD still holds the newer tests it is removing: not required.
    def mid_rollback():
        real = rolled_history()
        def edit(d):
            real(d)
            git('rm', '-q', '--cached', 'tools/rolled-back-to', cwd=d); git('commit', '-q', '-m', 'HEAD without the record', cwd=d)
        edit.keeps_record = True
        return expect(sandbox(edit=edit, lists=LISTS), 0, 'check ok')
    case('rollback-in-progress-does-not-require-head-tests', mid_rollback)
    # The record itself: the SHA must be readable.
    def rolled_to(rev, record=None):
        real = at('child')
        def edit(d):
            real(d)
            (d / 'tools' / 'rolled-back-to').write_text((record if record is not None else git('rev-parse', rev + '^{commit}')) + '\n')
        edit.keeps_record = True
        return edit
    case('rolled-back-record-not-a-sha-refused', lambda: expect(
        sandbox(OK_TEST, edit=rolled_to('bf71b36', 'not-a-sha')), 1, 'refusing: tools/rolled-back-to is not one full commit SHA'))
    case('rolled-back-record-unknown-commit-refused', lambda: expect(
        sandbox(OK_TEST, edit=rolled_to('bf71b36', 'f' * 40)), 1, 'refusing: git cannot read the tests of ffffffffffff'))


if __name__ == '__main__':
    sys.exit(main())

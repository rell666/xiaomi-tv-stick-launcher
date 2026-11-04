#!/usr/bin/env python3
"""Pre-build gate: web/ on disk is exactly pinned upstream (every file hash-checked against
tools/upstream-manifest.json) + tools/android-patch.py (main.js, metrics.js, index.html) + the files listed in
OURS in android-patch.py, with nothing else in it (a hand edit or a stray file fails here instead
of being lost at the next upstream copy or packaged into the APK); the page and its device layer
parse, and our own scripts (and the code the patch injects) are ES5 (docs/RULES.md rule 2); and every default Home app has a mark with measured metrics (an unmeasured mark is
stretched by CleanFrame's mask-size:100% 100%); then it runs every offline test under tests/
(see below). Usage: python3 tools/check.py [--skip-nested]"""
import ast, hashlib, hmac, importlib.util, json, os, pathlib, re, secrets, shutil, signal, stat, subprocess, sys, tempfile
if any(a != '--skip-nested' for a in sys.argv[1:]): sys.exit('usage: tools/check.py [--skip-nested]')
root = pathlib.Path(__file__).resolve().parent.parent
web = root / 'web'
# Never trust tools/__pycache__. Python reuses a .pyc whose recorded source mtime (whole seconds) and
# size match the .py, and tools/rollback.sh (and tests/tools.test.sh around it) swap same-size
# versions of android-patch.py (they differ only in a pinned SHA) within one second: a stale .pyc
# then made this gate judge one commit's patch file with another's code, and rollback.sh's search
# picked the wrong patch file (2026-10-31). Removed before this file or any test imports from tools/.
shutil.rmtree(root / 'tools' / '__pycache__', ignore_errors=True)
spec = importlib.util.spec_from_file_location('android_patch', root / 'tools' / 'android-patch.py')
android_patch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(android_patch)
try:
    # An older android-patch.py restored by tools/rollback.sh returns None: no file count.
    counts = android_patch.verify(web)
except android_patch.PatchError as e:
    sys.exit('web/ is not upstream + android patch: %s' % e)
up_desc = '%d files' % counts[0] if counts else 'count unknown (old patch file)'
ours_desc = ' + %d of ours' % counts[1] if counts else ''
# Every page script we change or own parses: the patched ones (the .js in FILES: main.js, metrics.js;
# index.html is patched too, but is not a script) and every
# .js in OURS (device.js, our screens, the catalogues, the vendored QR library) and in OPTIONAL when
# present, not only the three it used to be. An older patch file restored by tools/rollback.sh may have no FILES or OURS: the three
# are always checked. A missing node is a failure, named (never a FileNotFoundError traceback).
JS_CHECK = sorted({'main.js', 'device.js', 'assets/icons/metrics.js'}
                  | {n for n, _ in getattr(android_patch, 'FILES', ()) if n.endswith('.js')}
                  | {n for n in getattr(android_patch, 'OURS', {}) if n.endswith('.js')}
                  | {n for n in getattr(android_patch, 'OPTIONAL', {}) if n.endswith('.js')})
if not shutil.which('node'):
    sys.exit('refusing: no node on PATH; the gate needs it to syntax-check web/ and to run the .test.js tests '
             '(and tools/devtools-eval.mjs needs Node 22+)')
for js in JS_CHECK:
    if js in getattr(android_patch, 'OPTIONAL', {}) and not (web / js).exists(): continue
    r = subprocess.run(['node', '-e', 'new Function(require("fs").readFileSync(process.argv[1],"utf8"))', str(web / js)], capture_output=True, text=True)
    if r.returncode: sys.exit('syntax: %s\n%s' % (js, r.stderr))
if 'AndroidHome' not in (web / 'device.js').read_text(): sys.exit('web/device.js is not the Android device layer')
# ES5 only (docs/RULES.md rule 2, CleanFrame's own rule for web/): node's parse above accepts any modern
# syntax, so our own page scripts are also lexed here (strings, comments and regex literals skipped)
# and any ES2015+ construct fails, named with file and line. Checked: every .js in OURS (and OPTIONAL,
# when present), read from tools/android-patch.py like JS_CHECK, so a new file of ours is checked as
# soon as it is listed (it must be, or verify() fails); and the code tools/android-patch.py injects
# into a patched file (FILES): the whole patched file is lexed (a fragment alone can start inside a
# string) and a construct counts when it is on a line one of the patch's new texts produced.
# Upstream's own code is not ours and is not judged (nor is the vendored QR library, QR_FILE). After
# "}" a "/" starts a regex when the brace closed a statement (a block, a function declaration) and is
# division when it closed an expression (an object literal, a function expression). Object
# literals are told from blocks by the token before "{" (_es5_frames), so shorthand methods and
# properties and computed keys are found in literals only; a "{" the heuristic takes for a block is
# not judged for them (the other checks still apply inside it).
# ES5-only check (docs/RULES.md rule 2) for our own page scripts: a small JavaScript lexer that skips
# strings, comments and regex literals, then looks for ES2015+ syntax in the token stream.
_ES5_PUNCT = sorted(('>>>=', '...', '===', '!==', '**=', '>>>', '<<=', '>>=', '&&=', '||=', '??=', '=>', '==', '!=',
                     '<=', '>=', '&&', '||', '??', '?.', '++', '--', '+=', '-=', '*=', '/=', '%=', '&=', '|=',
                     '^=', '<<', '>>', '**'), key=len, reverse=True)
_ES5_ID = re.compile(r'[A-Za-z_$\u0080-\uffff][\w$\u0080-\uffff]*')
_ES5_NUM = re.compile(r'0[xXbBoO][0-9a-fA-F_]+n?|(?:\d[\d_]*\.?[\d_]*|\.\d[\d_]*)(?:[eE][+-]?\d+)?n?')
# After these a "/" starts a regex literal; after anything else (a name, number, string, ")" "]" "}")
# it is division.
_ES5_REGEX_AFTER_KW = {'return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void', 'throw',
                       'case', 'do', 'else', 'yield', 'await'}


# After these a "{" opens an object literal (an expression); after the rest (")", ";", "}", else, do,
# try, finally, nothing) it opens a block. A ":" is resolved by the frame it is in: a ternary's (a "?"
# is pending) or a property value's (in an object literal) opens an object, a label's or case's a block.
_ES5_OBJ_AFTER_KW = {'return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void', 'throw',
                     'case', 'yield', 'await'}


class _Es5Frames:
    """The brackets around each token, fed token by token (the lexer needs the answers as it goes). ctx[i]: the
    innermost bracket around toks[i], 'obj' (object literal), 'block', 'paren', 'bracket' or 'top'. obj_close:
    indexes of "}" that close an object literal. stmt_close: of "}" that end a statement (a block, a function
    declaration; a "/" after one starts a regex). ctl_close: of ")" that close if (...), while (...), for (...)."""
    def __init__(self, toks):
        self.toks, self.stack, self.ctx = toks, [['top', 0, -1]], []
        self.obj_close, self.stmt_close, self.ctl_close, self.tern = set(), set(), set(), set()   # tern: a ternary's ":"
        self.parens, self.open_of = [], {}   # the "(" of each ")"

    def _function_expression(self, brace):
        """Does the "{" at toks[brace] open the body of a function expression (not at a statement's start)?"""
        toks = self.toks
        if brace == 0 or toks[brace - 1][:2] != ('p', ')'): return False
        k = self.open_of.get(brace - 1, 0) - 1
        if k >= 0 and toks[k][0] == 'id' and toks[k][1] != 'function': k -= 1   # its name
        if k < 0 or toks[k][:2] != ('id', 'function'): return False
        if k == 0: return False
        pk, pt = toks[k - 1][:2]
        return not (pk == 'p' and (pt in (';', '{', '}') or pt == ')' and k - 1 in self.ctl_close))

    def feed(self, i):
        kind, t, _ = self.toks[i]
        stack = self.stack
        self.ctx.append(stack[-1][0])
        if kind != 'p': return
        if t == '{':
            pk, pt = self.toks[i - 1][:2] if i else (None, None)
            if pk is None: frame = 'block'
            elif pk == 'p' and pt == ':': frame = 'obj' if i - 1 in self.tern or stack[-1][0] == 'obj' else 'block'
            elif pk == 'p': frame = 'block' if pt in (')', ']', '}', ';', '=>') else 'obj'
            elif pk == 'id': frame = 'obj' if pt in _ES5_OBJ_AFTER_KW else 'block'
            else: frame = 'block'
            stack.append([frame, 0, i])
        elif t in '([':
            if t == '(':
                ctl = i > 0 and self.toks[i - 1][0] == 'id' and self.toks[i - 1][1] in ('if', 'while', 'for', 'with')
                self.parens.append((i, ctl))
            stack.append(['paren' if t == '(' else 'bracket', 0, i])
        elif t in ')]}':
            if t == ')' and self.parens:
                self.open_of[i], ctl = self.parens.pop()
                if ctl: self.ctl_close.add(i)
            if len(stack) > 1:
                if t == '}' and stack[-1][0] == 'obj': self.obj_close.add(i)
                elif t == '}' and stack[-1][0] == 'block' and not self._function_expression(stack[-1][2]): self.stmt_close.add(i)
                stack.pop()
        elif t == '?': stack[-1][1] += 1
        elif t == ':' and stack[-1][1] > 0: stack[-1][1] -= 1; self.tern.add(i)


def _es5_frames(toks):
    """(ctx, obj_close) of the token list, as _Es5Frames gives them."""
    f = _Es5Frames(toks)
    for i in range(len(toks)): f.feed(i)
    return f.ctx, f.obj_close


def _es5_tokens(src):
    """(kind, text, line) for each token; kind is id, num, str, regex, tmpl or p (punctuator)."""
    toks, i, line, n = [], 0, 1, len(src)
    frames = _Es5Frames(toks)   # fed each token as it is made: the "/" after ")" or "}" depends on what closed
    def add(tok):
        toks.append(tok); frames.feed(len(toks) - 1)
    def regex_ok():
        if not toks: return True
        k, t, _ = toks[-1]
        if k == 'id': return t in _ES5_REGEX_AFTER_KW
        if k == 'p' and t == ')': return len(toks) - 1 in frames.ctl_close
        if k == 'p' and t == '}': return len(toks) - 1 in frames.stmt_close
        # i++ / 2: a postfix ++/-- ends an expression (it follows an operand), so this "/" is division. A
        # prefix one (++/x/.lastIndex, never written) starts one. Read as a regex, it hid the rest of the line.
        if k == 'p' and t in ('++', '--') and len(toks) > 1:
            pk, pt, _ = toks[-2]
            if pk in ('num', 'str', 'regex', 'tmpl') or (pk == 'id' and pt not in _ES5_REGEX_AFTER_KW): return False
            if pk == 'p' and pt in (')', ']', '}'): return False
        return k == 'p' and t not in (']', '}')
    while i < n:
        c = src[i]
        if c == '\n': line += 1; i += 1; continue
        if c in ' \t\r\f\v﻿   ': i += 1; continue
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); j = n if j < 0 else j + 2
            line += src.count('\n', i, j); i = j; continue
        if c in '\'"':
            j = i + 1
            while j < n and src[j] != c:
                if src[j] == '\\': j += 1
                elif src[j] == '\n': break   # unterminated: node's syntax check reports it
                j += 1
            add(('str', src[i:j + 1], line)); line += src.count('\n', i, j + 1); i = j + 1; continue
        if c == '`':
            add(('tmpl', '`', line)); j = i + 1
            while j < n and src[j] != '`':
                j += 2 if src[j] == '\\' else 1
            line += src.count('\n', i, j + 1); i = j + 1; continue
        if c == '/' and regex_ok():
            j, cls = i + 1, False
            while j < n and src[j] != '\n' and (cls or src[j] != '/'):
                if src[j] == '\\': j += 1
                elif src[j] == '[': cls = True
                elif src[j] == ']': cls = False
                j += 1
            j += 1
            while j < n and (src[j].isalnum() or src[j] in '_$'): j += 1
            add(('regex', src[i:j], line)); i = j; continue
        m = _ES5_NUM.match(src, i) if (c.isdigit() or (c == '.' and i + 1 < n and src[i + 1].isdigit())) else None
        if m:
            add(('num', m.group(), line)); i = m.end(); continue
        m = _ES5_ID.match(src, i)
        if m:
            add(('id', m.group(), line)); i = m.end(); continue
        p = next((q for q in _ES5_PUNCT if src.startswith(q, i)), c)
        add(('p', p, line)); i += len(p)
    return toks


def _es5_group(toks, i):
    """Index just past the bracket group that opens at toks[i] ("(", "[" or "{")."""
    depth = 0
    for j in range(i, len(toks)):
        if toks[j][0] == 'p' and toks[j][1] in '([{': depth += 1
        elif toks[j][0] == 'p' and toks[j][1] in ')]}':
            depth -= 1
            if depth == 0: return j + 1
    return len(toks)


_ES5_CODE_POINT_ESCAPE = re.compile(r'(?<!\\)(?:\\\\)*\\u\{')


def _es5_regex_groups(body):
    """[what] for each ES2018 group in a regex literal's body: named (?<n>...), lookbehind (?<=...), (?<!...).
    An escaped "(" and anything in a [class] are literal."""
    out, i, cls = [], 0, False
    while i < len(body):
        c = body[i]
        if c == '\\': i += 2; continue
        if c == '[': cls = True
        elif c == ']': cls = False
        elif not cls and body.startswith('(?<', i):
            out.append('regex lookbehind' if body[i + 3:i + 4] in ('=', '!') else 'regex named group')
        i += 1
    return out


def es5_problems(src):
    """[(line, what)] for each ES2015+ construct in src: let, const, class, arrow functions,
    template literals, spread/rest, destructuring, default parameters, for-of, generators,
    async/await, the later operators (**, ?., ??, logical assignment) and 0b/0o/BigInt numbers; in
    object literals shorthand methods and properties and computed keys; destructuring assignment and
    catch parameters, optional catch binding, new.target, regex flags u/y/s/d/v and \\u{...} escapes, regex
    named groups and lookbehind, a trailing comma in parameters or arguments, import(), import.meta,
    import and export declarations."""
    toks, out = _es5_tokens(src), []
    ctx, obj_close = _es5_frames(toks)
    def at(k, kind, text):
        return 0 <= k < len(toks) and toks[k][0] == kind and toks[k][1] == text
    def indexes(k):   # toks[k] is "[": does it index the expression before it (a[0], f()[0], "s"[0])?
        if k == 0: return False
        pk, pt = toks[k - 1][:2]
        if pk == 'id': return pt not in _ES5_OBJ_AFTER_KW and pt not in ('else', 'do')
        return pk in ('num', 'str', 'regex') or pk == 'p' and (pt in (')', ']') or pt == '}' and k - 1 in obj_close)
    for i, (kind, t, line) in enumerate(toks):
        prop = at(i - 1, 'p', '.') or at(i + 1, 'p', ':')   # a property name (obj.class, {let: 1}) is ES5
        # The start of an object literal's property: just inside its "{", or after one of its commas.
        if ctx[i] == 'obj' and (at(i - 1, 'p', '{') or at(i - 1, 'p', ',')):
            if kind == 'p' and t == '[': out.append((line, 'computed property name'))
            elif kind == 'p' and t == '*': out.append((line, 'generator method'))
            elif kind in ('id', 'str', 'num') and at(i + 1, 'p', '('): out.append((line, 'shorthand method'))
            elif kind == 'id' and t in ('get', 'set') and at(i + 1, 'p', '['): out.append((line, 'computed property name'))
            elif kind == 'id' and (at(i + 1, 'p', ',') or at(i + 1, 'p', '}')): out.append((line, 'shorthand property'))
        if kind == 'tmpl': out.append((line, 'template literal'))
        elif kind == 'str' and _ES5_CODE_POINT_ESCAPE.search(t): out.append((line, 'code point escape \\u{...}'))
        elif kind == 'regex':
            flags = t[t.rfind('/') + 1:]
            for f in sorted(set(flags) & set('uysdv')): out.append((line, 'regex flag %s' % f))
            if _ES5_CODE_POINT_ESCAPE.search(t): out.append((line, 'code point escape \\u{...}'))
            for what in _es5_regex_groups(t[1:t.rfind('/')]): out.append((line, what))
        # An array or object literal assigned to ([a, b] = c, ({a: x} = c)): only a pattern can be.
        elif kind == 'p' and (t == '[' and not indexes(i) or t == '{' and _es5_group(toks, i) - 1 in obj_close) \
                and at(_es5_group(toks, i), 'p', '='):
            out.append((line, 'destructuring assignment'))
        elif kind == 'id' and t == 'new' and not at(i - 1, 'p', '.') and at(i + 1, 'p', '.'): out.append((line, 'new.target'))
        elif kind == 'id' and t == 'catch' and not prop:
            if at(i + 1, 'p', '{'): out.append((line, 'optional catch binding'))
            elif at(i + 1, 'p', '(') and (at(i + 2, 'p', '{') or at(i + 2, 'p', '[')): out.append((line, 'destructuring catch parameter'))
        elif kind == 'num' and (t[:2].lower() in ('0b', '0o') or t.endswith('n') or '_' in t):
            out.append((line, 'ES2015+ number literal %s' % t))
        elif kind == 'p' and t == ',' and at(i + 1, 'p', ')'): out.append((line, 'trailing comma in parameters or arguments'))
        elif kind == 'id' and not prop and t == 'import':
            out.append((line, 'import()' if at(i + 1, 'p', '(') else 'import.meta' if at(i + 1, 'p', '.') else 'import declaration'))
        elif kind == 'id' and not prop and t == 'export': out.append((line, 'export'))
        elif kind == 'p' and t == '=>': out.append((line, 'arrow function'))
        elif kind == 'p' and t == '...': out.append((line, 'spread/rest (...)'))
        elif kind == 'p' and t in ('**', '**=', '?.', '??', '??=', '&&=', '||='): out.append((line, 'ES2016+ operator %s' % t))
        elif kind == 'id' and not prop and t in ('let', 'const', 'class'): out.append((line, t))
        elif kind == 'id' and not prop and t == 'async' and (at(i + 1, 'id', 'function') or toks[i + 1:i + 2] and toks[i + 1][0] == 'id'):
            out.append((line, 'async function'))
        elif kind == 'id' and not prop and t == 'await': out.append((line, 'await'))
        elif kind == 'id' and t == 'var' and not prop and (at(i + 1, 'p', '{') or at(i + 1, 'p', '[')):
            out.append((line, 'destructuring'))
        elif kind == 'id' and t == 'function' and not prop:
            if at(i + 1, 'p', '*'): out.append((line, 'generator function*'))
            k = i + 1
            if k < len(toks) and toks[k][0] == 'id': k += 1
            if at(k, 'p', '('):
                end, depth = _es5_group(toks, k), 0
                for kk in range(k + 1, end - 1):
                    pk, pt = toks[kk][0], toks[kk][1]
                    if pk == 'p' and pt in '([{':
                        if depth == 0 and pt in '[{': out.append((toks[kk][2], 'destructuring parameter'))
                        depth += 1
                    elif pk == 'p' and pt in ')]}': depth -= 1
                    elif depth == 0 and pk == 'p' and pt == '=': out.append((toks[kk][2], 'default parameter'))
        elif kind == 'id' and t == 'for' and not prop and at(i + 1, 'p', '('):
            k = i + 2
            if k < len(toks) and toks[k][0] == 'id' and toks[k][1] == 'var': k += 1
            if k + 1 < len(toks) and toks[k][0] == 'id' and at(k + 1, 'id', 'of'): out.append((line, 'for-of'))
    return out

OWN_JS = sorted(n for n in set(getattr(android_patch, 'OURS', {})) | set(getattr(android_patch, 'OPTIONAL', {}))
                if n.endswith('.js') and n != getattr(android_patch, 'QR_FILE', None) and (web / n).is_file())
es5_bad = ['web/%s:%d: %s' % (n, l, w) for n in OWN_JS for l, w in es5_problems((web / n).read_text(encoding='utf-8'))]
for name, pairs in getattr(android_patch, 'FILES', ()):
    if not name.endswith('.js') or not (web / name).is_file(): continue
    text = (web / name).read_text(encoding='utf-8')
    spans = []   # (first, last) line of each place a new text of the patch sits in the patched file
    for pair in pairs:
        new = pair[1]; at = text.find(new)
        while new and at >= 0:
            first = text.count('\n', 0, at) + 1
            spans.append((first, first + new.count('\n')))
            at = text.find(new, at + len(new))
    es5_bad += ['web/%s:%d (injected by tools/android-patch.py): %s' % (name, l, w)
                for l, w in es5_problems(text) if any(a <= l <= b for a, b in spans)]
if es5_bad:
    sys.exit('ES5: not ES5 (docs/RULES.md rule 2: ES5 JavaScript only in web/):\n  ' + '\n  '.join(es5_bad[:40]))
main = (web / 'main.js').read_bytes().decode('utf-8')
start = main.find('var PRIMARY_APPS = [')
end = main.find('];', start) if start >= 0 else -1
if end < 0: sys.exit('web/main.js: no "var PRIMARY_APPS = [ ... ];" (did upstream rename it? update tools/check.py)')
block = main[start:end]
ids = re.findall(r"id: '([a-z0-9]+)'", block)
# No ids would make the loop below check nothing and pass (fail closed: upstream reshaped the list).
if not ids: sys.exit("web/main.js: PRIMARY_APPS has no id: '<id>' entries (did upstream change its shape? update tools/check.py)")
m = re.search(r'FrameIconMetrics = (\{.*\});', (web / 'assets/icons/metrics.js').read_bytes().decode('utf-8'))
try:
    metrics = json.loads(m.group(1)) if m else None
except ValueError:
    metrics = None
if not isinstance(metrics, dict):
    sys.exit('web/assets/icons/metrics.js: no "FrameIconMetrics = {...};" JSON object (did it change shape? update tools/check.py)')
for i in ids:
    if not (web / 'assets/icons' / (i + '.svg')).exists(): sys.exit('no mark for default app %s' % i)
    if i not in metrics: sys.exit('no metrics for mark %s' % i)
# Offline tests, on every check (so on every build, before every commit and on every rollback).
# Discovered, not listed: every tests/*.test.js (node), tests/*.test.sh (bash) and tests/*.test.py
# (python3 -I), in name order, each must exit 0 within CLEANFRAME_TEST_TIMEOUT seconds (default
# 1800; a test that overruns is killed with its whole process group and fails, named). Any other
# tests/*.test.* fails (it would never run), as does a script-like tests/* (.js, .mjs, .sh, .py, ...)
# not named *.test.*.
# Discovery alone passes when tests go missing, so three more checks (they apply when any test is
# found; no test at all is the case below):
#  - every entry of this tree's HEAD_TESTS and APP_TESTS (tools/rollback.sh) exists: a deleted test
#    fails, named. A tree made by tools/rollback.sh holds that SHA's app tests but HEAD's rollback.sh,
#    so it records the SHA in tools/rolled-back-to, and then what must exist is HEAD_TESTS, the top-level
#    tests/ entries of that SHA (git says which; an unreadable SHA refuses) and every listed test added by
#    a commit since the one that wrote the record: the record excuses the tests a rollback removed, not
#    one added later. Really dropping a test means dropping its list entry too, which a diff of rollback.sh shows;
#  - a test file with no code (empty, comments only, a docstring only) fails: it could only pass;
#  - a test that exits 0 must also end (last stdout line) with its end marker: <NAME>_OK..., "OK n/n" or
#    "n/n passed" (n > 0), so a suite that stops early with exit 0 fails. Every suite has one today.
#    In a rolled-back tree an app test still byte for byte that SHA's (same blob) is excused: an older
#    suite may have none, and for it the exit code is what is trusted. Anything added or changed since,
#    and every HEAD_TESTS entry, is not.
# No tests at all (no tests/, or none in it) is allowed ONLY for a tree from before de933e0, the commit
# that added them (tools/rollback.sh can restore one): git must say HEAD does not descend from it. A
# commit that does, or a tree where git cannot tell (not a git checkout, a shallow clone without
# de933e0), fails: a deleted tests/ must not pass the gate as "no offline tests".
# Recursion guard, deliberately narrow: tests/tools.test.sh runs tools/rollback.sh in throwaway
# clones, which runs this file again there. ONLY the tests in NESTED_SKIP are ever skipped, and
# only when (a) --skip-nested is given (tools/rollback.sh's search for a patch file), or (b)
# CLEANFRAME_NESTED_GUARD is "<file>:<token>" and <file> is a regular file of ours, named
# cf-check-guard.* directly in $TMPDIR (else ~/scratch) as this file makes them, holding exactly
# that random token. This file creates such a guard for the tests it runs and deletes it when they
# finish (tests/tools.test.sh makes its own when run alone), so a stale or hand-made export (the
# old boolean CLEANFRAME_IN_TESTS is ignored) cannot skip a test: it is reported and everything runs.
RUNNERS = {'.test.js': ['node'], '.test.sh': ['bash'], '.test.py': ['python3', '-I']}
NESTED_SKIP = {'tools.test.sh'}   # the only tests that spawn rollback clones
GUARD_VAR = 'CLEANFRAME_NESTED_GUARD'

GUARD_PREFIX = 'cf-check-guard.'

def guard_dir():
    """Where guards are made: $TMPDIR, else ~/scratch (never /tmp)."""
    return pathlib.Path(os.environ.get('TMPDIR') or pathlib.Path.home() / 'scratch')

def guard_valid(value):
    path, sep, token = value.rpartition(':')
    if not sep or not path or not re.fullmatch(r'[0-9a-f]{32,}', token): return False
    # Only a guard as this file makes them (tests/tools.test.sh makes its own the same way): named
    # cf-check-guard.* directly in guard_dir(). Any other file, even one holding the token, is not one.
    p = pathlib.Path(path)
    if not p.name.startswith(GUARD_PREFIX) or os.path.realpath(str(p.parent)) != os.path.realpath(str(guard_dir())):
        return False
    try:
        st = os.lstat(path)
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid(): return False
        with open(path) as f: return hmac.compare_digest(f.read().strip(), token)
    except OSError:
        return False

try:
    # 1800 s, not 900 (not 600, not 300): tests/tools.test.sh alone took 246 s on an idle machine at 687edfd and
    # ~285 s after the rubric-v1 fixes, well over 300 s while other worktrees ran their gates (2026-11-02),
    # over 600 s at 30e7356 while three worktrees ran theirs (2026-11-03), and ~16 min alone at 5acb6ea,
    # over 900 s under load (2026-11-04): a hang guard must not fail a slow but healthy run.
    timeout_s = float(os.environ.get('CLEANFRAME_TEST_TIMEOUT') or 1800)
    if timeout_s <= 0: raise ValueError
except ValueError:
    sys.exit('CLEANFRAME_TEST_TIMEOUT must be a positive number of seconds')
guard = os.environ.get(GUARD_VAR, '')
nested = '--skip-nested' in sys.argv[1:] or (bool(guard) and guard_valid(guard))
if guard and not nested:
    print('check: ignoring %s (no live guard file with that token); running every test' % GUARD_VAR, file=sys.stderr)
    guard = ''
tdir = root / 'tests'
found = sorted(p.name for p in tdir.glob('*.test.*') if p.is_file()) if tdir.is_dir() else []
# A script in tests/ not named *.test.* (tests/x_test.py, tests/x.mjs) would silently never run. Any
# script-like file (SCRIPT_SUFFIXES) counts, also one that no runner takes (.mjs, .ts, ...): named
# *.test.* it fails below as an unknown test type, otherwise here. Helper files that are not tests go
# in TEST_HELPERS (none today).
TEST_HELPERS = set()
SCRIPT_SUFFIXES = ('.js', '.mjs', '.cjs', '.ts', '.sh', '.bash', '.py')
misnamed = sorted(p.name for p in tdir.iterdir() if p.is_file() and p.suffix in SCRIPT_SUFFIXES
                  and '.test.' not in p.name and p.name not in TEST_HELPERS) if tdir.is_dir() else []
if misnamed: sys.exit('tests/%s: not named *.test.{js,sh,py}, so it would never run (rename it, or list a helper in TEST_HELPERS in tools/check.py)' % ', tests/'.join(misnamed))
# Discovery is top level only (each top-level entry is also what tools/rollback.sh classifies), so a
# test or script under a subdirectory (tests/sub/x.test.js) would never run either. A subdirectory may
# hold scripts only as a helper dir: its own top-level runner tests/<dir>.test.{js,sh,py} (tests/upload
# <- upload.test.sh; tests/photo-upload <- photo-upload.test.py at older commits a rollback restores),
# or one in HELPER_DIRS. Anywhere else a *.test.* or script-like file fails, named.
HELPER_DIRS = {'java': 'run by tests/fallback.test.sh', 'fixtures': 'data read by the tests, no scripts run from it'}
def _helper_dir(d):
    return d in HELPER_DIRS or any((tdir / (d + k)).is_file() for k in RUNNERS)
stray = sorted(str(p.relative_to(tdir)) for p in tdir.rglob('*') if p.is_file() and len(p.relative_to(tdir).parts) > 1
               and ('.test.' in p.name or p.suffix in SCRIPT_SUFFIXES)
               and not _helper_dir(p.relative_to(tdir).parts[0])) if tdir.is_dir() else []
if stray: sys.exit('tests/%s: under a subdirectory of tests/ that is not a helper dir, so it would never run (move a test to '
                   'tests/ itself; a helper dir needs its runner tests/<dir>.test.{js,sh,py} or an entry in HELPER_DIRS in tools/check.py)'
                   % ', tests/'.join(stray))
ROLLBACK_SH, ROLLED_BACK_TO = root / 'tools' / 'rollback.sh', root / 'tools' / 'rolled-back-to'


def test_lists():
    """(HEAD_TESTS, APP_TESTS) of this tree's tools/rollback.sh; neither missing nor empty (fail closed)."""
    try:
        text = ROLLBACK_SH.read_text(encoding='utf-8')
    except OSError as e:
        sys.exit('refusing: cannot read tools/rollback.sh for its test lists: %s' % e)
    lists = []
    for name in ('HEAD_TESTS', 'APP_TESTS'):
        m = re.search(r'^%s=\(([^)]*)\)[ \t]*$' % name, text, re.M)
        if not m or not m.group(1).split():
            sys.exit('refusing: no %s=(...) line with entries in tools/rollback.sh, so this gate cannot tell which tests must exist' % name)
        lists.append(m.group(1).split())
    return lists


def rolled_back_entries():
    """(None, None), or ({top-level tests/ entry: its object id} of the commit tools/rolled-back-to names, that SHA)."""
    if not ROLLED_BACK_TO.exists(): return None, None
    sha = ROLLED_BACK_TO.read_text(encoding='utf-8').strip()
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        sys.exit('refusing: tools/rolled-back-to is not one full commit SHA (%r)' % sha[:60])
    try:
        r = subprocess.run(['git', '-C', str(root), 'ls-tree', sha + '^{commit}', 'tests/'], capture_output=True, text=True)
    except OSError as e:
        sys.exit('refusing: git cannot read the tests of %s (tools/rolled-back-to): %s' % (sha[:12], e))
    if r.returncode:
        sys.exit('refusing: git cannot read the tests of %s (tools/rolled-back-to): %s' % (sha[:12], r.stderr.strip()))
    return {l.split('\t', 1)[1]: l.split('\t', 1)[0].split()[2] for l in r.stdout.split('\n')[:-1]}, sha


def added_since_rollback(sha):
    """Top-level tests/ entries added by commits since the one that wrote tools/rolled-back-to. None while
    tools/rollback.sh is still making that commit (HEAD does not hold this record yet: HEAD's newer tests are
    the ones it is removing). A git failure after that refuses."""
    def git(*args):
        try:
            r = subprocess.run(['git', '-C', str(root)] + list(args), capture_output=True, text=True)
        except OSError as e:
            sys.exit('refusing: git cannot read the history of tools/rolled-back-to: %s' % e)
        return r
    if git('show', 'HEAD:tools/rolled-back-to').stdout.strip() != sha: return set()
    wrote = git('log', '-1', '--format=%H', '--', 'tools/rolled-back-to')
    if wrote.returncode or not wrote.stdout.strip():
        sys.exit('refusing: git cannot say which commit wrote tools/rolled-back-to: %s' % wrote.stderr.strip())
    r = git('log', '--format=', '--name-only', '--diff-filter=A', wrote.stdout.strip() + '..HEAD', '--', 'tests/')
    if r.returncode: sys.exit('refusing: git cannot read the tests added since the rollback: %s' % r.stderr.strip())
    return {'/'.join(p.split('/')[:2]) for p in r.stdout.split('\n') if p}


def blob_id(path):
    data = path.read_bytes()
    return hashlib.sha1(b'blob %d\0' % len(data) + data).hexdigest()


def has_no_code(path):
    """A test file that could only pass: nothing in it but comments (or, in Python, a docstring and pass)."""
    text = path.read_text(encoding='utf-8', errors='replace')
    if path.name.endswith('.py'):
        try:
            body = ast.parse(text).body
        except SyntaxError:
            return False   # python3 fails on it, named
        return all(isinstance(n, ast.Pass) or isinstance(n, ast.Expr) and isinstance(n.value, ast.Constant) for n in body)
    if path.name.endswith('.js'):
        text = re.sub(r"/\*.*?\*/|//[^\n]*|^\s*['\"]use strict['\"];?\s*$", '', text, flags=re.S | re.M)
    else:
        text = re.sub(r'^\s*#[^\n]*$', '', text, flags=re.M)
    return not text.strip()


_END_MARK = re.compile(r'(?:[A-Z][A-Z0-9_]*_OK\b|OK (\d+)/(\d+)\b|(\d+)/(\d+) passed\b)')


def has_end_marker(out):
    """Is the last line a test printed its end marker? A count must be complete and not zero."""
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    m = _END_MARK.match(lines[-1]) if lines else None
    if not m: return False
    n, of = m.group(1) or m.group(3), m.group(2) or m.group(4)
    return n is None or (n == of and int(of) > 0)


marker_excused = set()   # tests that need no end marker: a rollback's app tests, unchanged
if found:
    head_tests, app_tests = test_lists()
    rolled_entries, rolled_sha = rolled_back_entries()
    if rolled_entries is None:
        required = head_tests + app_tests
    else:
        added = added_since_rollback(rolled_sha)
        required = head_tests + [e for e in rolled_entries if e not in head_tests] + [e for e in app_tests if e in added]
        marker_excused = {n for n in found if 'tests/' + n not in head_tests and (tdir / n).is_file()
                          and rolled_entries.get('tests/' + n) == blob_id(tdir / n)}
    missing = sorted(e for e in set(required) if not (root / e).exists()
                     or (root / e).is_dir() and not any(p.is_file() for p in (root / e).rglob('*')))
    if missing:
        where = 'tools/rolled-back-to\'s commit, HEAD_TESTS and the tests added since' if rolled_entries is not None else 'HEAD_TESTS/APP_TESTS in tools/rollback.sh'
        sys.exit('tests missing: %s (%s list them): restore each; a test really dropped leaves its list too, in the same commit' % (', '.join(missing), where))
    no_code = [n for n in found if n.endswith(tuple(RUNNERS)) and has_no_code(tdir / n)]
    if no_code: sys.exit('tests/%s: no code in it, so it could only pass (a test must assert something)' % ', tests/'.join(no_code))
ran, skipped, made = [], [], None
try:
    if found and not guard:   # our own guard for the tests we run; removed in finally
        gdir = guard_dir()
        gdir.mkdir(parents=True, exist_ok=True)
        fd, made = tempfile.mkstemp(prefix=GUARD_PREFIX, dir=str(gdir))
        token = secrets.token_hex(16)
        with os.fdopen(fd, 'w') as f: f.write(token)
        guard = '%s:%s' % (made, token)
    # The published tree is one commit, so tests that read the private history cannot run there.
    # tools/public-tree names them. Only the export writes it, and it is refused where that history
    # exists, so it can never quieten a test in the private repository.
    public_tree = root / 'tools' / 'public-tree'
    public_skip = set()
    if public_tree.exists():
        has_history = subprocess.run(['git', '-C', str(root), 'cat-file', '-e', '065aa2b^{commit}'], capture_output=True).returncode == 0
        if has_history: sys.exit('tools/public-tree exists, but this repository has the history its tests need: refusing')
        public_skip = {l.strip() for l in public_tree.read_text().splitlines() if l.strip()}
        unknown = sorted(public_skip - set(found))
        if unknown: sys.exit('tools/public-tree names tests that do not exist: ' + ', '.join(unknown))
        print('check: NOT RUN here (they need the private history): ' + ', '.join(sorted(public_skip)))
    for name in found:
        if name in public_skip: continue
        kind = next((k for k in RUNNERS if name.endswith(k)), None)
        if kind is None: sys.exit('tests/%s: unknown test type (want .test.js, .test.sh or .test.py)' % name)
        if nested and name in NESTED_SKIP:
            skipped.append(name); continue
        cmd = RUNNERS[kind] + ['tests/' + name]
        env = dict(os.environ, **{GUARD_VAR: guard}); env.pop('CLEANFRAME_IN_TESTS', None)
        proc = subprocess.Popen(cmd, cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, start_new_session=True)
        try:
            out, err = proc.communicate(timeout=timeout_s)
        except subprocess.TimeoutExpired:
            try: os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            try: out, err = proc.communicate(timeout=10)
            except subprocess.TimeoutExpired: out, err = '', '(output lost: a process outside the group kept the pipes open)'
            sys.exit('tests failed: %s TIMED OUT after %g s (CLEANFRAME_TEST_TIMEOUT); killed\n%s%s'
                     % (' '.join(cmd), timeout_s, out[-3000:], err[-2000:]))
        if proc.returncode:
            # Every FAIL line first (also a nested one, "    | FAIL ..."), however early in a long output:
            # the tail alone hid which case of tests/tools.test.sh had failed.
            fails = [l for l in (out + err).splitlines() if re.match(r'[\s|]*FAIL\b', l)]
            named = 'failed cases:\n%s\n--- end of output ---\n' % '\n'.join(fails[:40]) if fails else ''
            sys.exit('tests failed: %s (exit %d)\n%s%s%s' % (' '.join(cmd), proc.returncode, named, out[-3000:], err[-2000:]))
        if name not in marker_excused and not has_end_marker(out):
            sys.exit('tests failed: %s exited 0 without its end marker (last stdout line <NAME>_OK..., "OK n/n" or "n/n passed"): '
                     'a suite that stopped early looks like this\n%s' % (' '.join(cmd), out[-1000:]))
        ran.append(name)
finally:
    if made:
        try: os.unlink(made)
        except OSError: pass
TESTS_SINCE = 'de933e0'   # the commit that added tests/
if not found:
    try:
        anc = subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor', TESTS_SINCE, 'HEAD'],
                             capture_output=True, text=True).returncode
    except OSError:
        anc = None
    if anc == 0:
        sys.exit('no offline tests under tests/, but HEAD descends from %s, which added them: restore tests/ '
                 '(a tree without its tests does not pass this gate)' % TESTS_SINCE)
    if anc != 1:
        sys.exit('no offline tests under tests/, and git cannot tell whether HEAD predates %s (the commit that '
                 'added them; not a full git clone?): refusing' % TESTS_SINCE)
tests = ' + %d offline tests (%s)' % (len(ran), ', '.join(ran)) if ran else ' + no offline tests (tree predates %s)' % TESTS_SINCE
if skipped: tests += ', nested: skipped %s' % ', '.join(skipped)
print('check ok: web/ = upstream %s (%s) + android patch%s%s; defaults' % (android_patch.UPSTREAM_SHA[:7], up_desc, ours_desc, tests), ', '.join(ids))

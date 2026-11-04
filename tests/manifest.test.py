#!/usr/bin/env python3
"""tools/upstream-manifest.json must be exactly what tools/android-patch.py --write-manifest makes
from the manifest's own from_commit. --verify trusts the manifest's hashes, so without this a hand
edit to an upstream file under web/ plus the matching hash edit in the manifest would pass the gate.
Usage (from the repo root): python3 -I tests/manifest.test.py
Python 3 stdlib, git; no network, no device. The repo is only read: --write-manifest runs from a
copy of the working-tree tools/android-patch.py in a shared, no-checkout clone (under $TMPDIR, else
~/scratch), removed on exit. Fails closed: outside a full git clone (no history, or from_commit
missing, e.g. a shallow clone) it fails, as tests/patch.test.py does.
Inside tools/rollback.sh this runs with the restored android-patch.py against HEAD's manifest
(rollback does not restore the manifest); it passes when that patch file regenerates HEAD's
manifest byte for byte, and otherwise refuses the rollback, which is the safe answer: the restored
patch file would then describe a different upstream than the manifest it is checked against.
Prints PASS/FAIL lines, then MANIFEST_TEST_OK, or MANIFEST_TEST_FAIL <n> with exit 1."""
import json, os, pathlib, re, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / 'tools' / 'upstream-manifest.json'
GIT_ENV = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1', LC_ALL='C')
for v in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_OBJECT_DIRECTORY'):
    GIT_ENV.pop(v, None)


def git(*args, cwd=ROOT, check=True):
    return subprocess.run(['git', '-C', str(cwd)] + list(args), check=check, capture_output=True,
                          env=GIT_ENV, timeout=120)


def first_difference(want, got):
    try:
        w, g = json.loads(want), json.loads(got)
    except ValueError:
        return 'committed manifest is not JSON'
    for k in sorted(set(w) | set(g)):
        if k == 'files':
            continue
        if w.get(k) != g.get(k):
            return '%s: committed %r, regenerated %r' % (k, w.get(k), g.get(k))
    wf, gf = w.get('files') or {}, g.get('files') or {}
    for k in sorted(set(wf) | set(gf)):
        if wf.get(k) != gf.get(k):
            return 'files[%s]: committed %s, regenerated %s' % (k, wf.get(k), gf.get(k))
    return 'same content, different bytes (formatting or key order; regenerate it, never hand-edit it)'


def run():
    try:
        m = json.loads(MANIFEST.read_bytes())
        commit = m['from_commit']
    except (OSError, ValueError, KeyError, TypeError) as e:
        return 'cannot read from_commit from %s: %s' % (MANIFEST.name, e)
    if not isinstance(commit, str) or not re.fullmatch(r'[0-9a-f]{40}', commit):
        return 'from_commit %r is not a full commit SHA' % (commit,)
    if git('rev-parse', '--is-inside-work-tree', check=False).returncode:
        return 'not a git checkout: the manifest cannot be regenerated (needs a full clone)'
    if git('cat-file', '-e', commit + '^{commit}', check=False).returncode:
        return 'from_commit %s is not in this repo (shallow clone? this test needs a full clone)' % commit[:12]
    if git('merge-base', '--is-ancestor', commit, 'HEAD', check=False).returncode:
        return 'from_commit %s is not an ancestor of HEAD' % commit[:12]
    base = pathlib.Path(os.environ.get('TMPDIR') or pathlib.Path.home() / 'scratch')
    base.mkdir(parents=True, exist_ok=True)
    tmp = pathlib.Path(tempfile.mkdtemp(prefix='cf-manifest-test.', dir=str(base)))
    try:
        g = tmp / 'clone'
        git('clone', '-q', '--shared', '--no-checkout', str(ROOT), str(g), cwd=tmp)
        (g / 'tools').mkdir()
        shutil.copy2(ROOT / 'tools' / 'android-patch.py', g / 'tools' / 'android-patch.py')  # working tree's
        # and its manifest: --write-manifest accepts from_commit for the same UPSTREAM_SHA even when the
        # commit's message does not name it (tools/tools.test.sh's synthetic re-pins rely on that); the
        # regenerated file must still equal the committed one byte for byte.
        shutil.copy2(MANIFEST, g / 'tools' / 'upstream-manifest.json')
        r = subprocess.run([sys.executable, '-I', str(g / 'tools' / 'android-patch.py'), '--write-manifest', commit],
                           capture_output=True, text=True, cwd=str(g), env=GIT_ENV, timeout=120)
        if r.returncode:
            return '--write-manifest %s failed (exit %d): %s' % (commit[:12], r.returncode, (r.stdout + r.stderr).strip()[-500:])
        got, want = (g / 'tools' / 'upstream-manifest.json').read_bytes(), MANIFEST.read_bytes()
        if got != want:
            return ('tools/upstream-manifest.json is not what --write-manifest %s makes: %s'
                    % (commit[:12], first_difference(want, got)))
        return None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    try:
        problem = run()
    except Exception as e:  # a crash is a failure, never a pass
        problem = 'test raised %s: %s' % (type(e).__name__, e)
    if problem:
        print('FAIL manifest-regenerates-byte-identical: %s' % problem)
        print('MANIFEST_TEST_FAIL 1')
        return 1
    print('PASS manifest-regenerates-byte-identical')
    print('MANIFEST_TEST_OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())

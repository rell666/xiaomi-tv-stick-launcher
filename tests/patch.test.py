#!/usr/bin/env python3
"""Offline tests for tools/android-patch.py: apply, --verify and --write-manifest, mostly their
failure paths (and its argument parsing: only the three documented forms) (docs/RULES.md rule 1: web/ is upstream + one patch + OURS, nothing else).
Usage (from the repo root): python3 -I tests/patch.test.py
Python 3 stdlib, git; no network, no device. The real repo is only read: each case runs
android-patch.py from a copy of tools/ against a copy of web/, both in one temp dir removed on exit
(under $TMPDIR, else ~/scratch). --write-manifest runs in a shared, no-checkout clone of the repo.
Each case asserts the exit code AND that the message names the right file or anchor. Every anchor
is also moved one at a time in pristine upstream to prove it fails loudly, naming itself, and the
vendored QR generator (web/qrcode-generator.js) is checked against the sha-256 recorded in OURS.
Apply writes all patched files or none (a write or a move that fails part-way changes nothing), and
--write-manifest refuses a patched commit, one holding files of ours, or one not naming UPSTREAM_SHA.
The new commits a --write-manifest case makes live only in that clone (its own temp index file).
Prints one PASS/FAIL line per case, then PATCH_TEST_OK, or PATCH_TEST_FAIL <n> with exit 1."""
import hashlib, json, os, pathlib, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
UPSTREAM_COPY = '065aa2b'   # the commit that vendored upstream app/ into web/ unmodified
GIT_ENV = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1', LC_ALL='C')
FAILS = []


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def git(*args, cwd=ROOT):
    return subprocess.run(['git', '-C', str(cwd)] + list(args), check=True, capture_output=True,
                          env=GIT_ENV).stdout


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
    tmp = pathlib.Path(tempfile.mkdtemp(prefix='cf-patch-test.', dir=base))
    try:
        run_cases(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    if FAILS:
        print('PATCH_TEST_FAIL %d' % len(FAILS))
        return 1
    print('PATCH_TEST_OK')
    return 0


def run_cases(tmp):
    pristine = tmp / 'pristine'
    shutil.copytree(ROOT / 'web', pristine / 'web', symlinks=True)
    shutil.copytree(ROOT / 'tools', pristine / 'tools', symlinks=True,
                    ignore=shutil.ignore_patterns('__pycache__'))
    n = [0]

    def fresh():
        """A new copy of web/ + tools/; returns (dir, web)."""
        n[0] += 1
        d = tmp / ('c%02d' % n[0])
        shutil.copytree(pristine, d, symlinks=True)
        return d, d / 'web'

    def patch(d, *args):
        r = subprocess.run([sys.executable, '-I', str(d / 'tools' / 'android-patch.py')] + list(args),
                           capture_output=True, text=True, cwd=str(d), timeout=60)
        return r.returncode, r.stdout + r.stderr

    def expect(rc, out, want_rc, *texts, absent=('Traceback',)):
        if (rc == 0) != (want_rc == 0):
            return 'exit %d, want %s; output: %s' % (rc, '0' if want_rc == 0 else 'non-zero', out.strip()[:400])
        for t in texts:
            if t not in out:
                return 'output lacks %r; output: %s' % (t, out.strip()[:400])
        for t in absent:
            if t in out:
                return 'output has %r; output: %s' % (t, out.strip()[:400])
        return None

    def edit(path, old, new):
        b = path.read_bytes()
        assert b.count(old) >= 1, 'test setup: %r not in %s' % (old, path)
        path.write_bytes(b.replace(old, new, 1))

    # The module itself (from the copy), for its anchors and helpers.
    import importlib.util
    spec = importlib.util.spec_from_file_location('android_patch', pristine / 'tools' / 'android-patch.py')
    ap = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ap)
    MAIN_JS, METRICS_JS, INDEX_HTML = 'main.js', 'assets/icons/metrics.js', 'index.html'
    PATCHED = [name for name, _ in ap.FILES]
    ART_OLD, ART_NEW = "art: ['Art Mode', 'art']", "art: ['Screensaver', 'art']"

    def verify_pristine():
        d, web = fresh()
        rc, out = patch(d, '--verify', str(web))
        p = expect(rc, out, 0, 'verified: ', 'upstream %s' % ap.UPSTREAM_SHA[:7])
        if p:
            return p
        files = json.loads((d / 'tools' / 'upstream-manifest.json').read_bytes())['files']
        for name, pairs in ap.FILES:   # reverse-applying the patch gives exactly the pinned upstream
            got = ap._sha(ap.unapply((web / name).read_bytes().decode('utf-8'), pairs).encode('utf-8'))
            if got != files[name]:
                return '%s reverse-applied sha256 %s, manifest %s' % (name, got, files[name])
        patched = {name for name, _ in ap.FILES}
        for rel, h in files.items():
            if rel not in patched and rel not in ap.OURS and sha(web / rel) != h:
                return '%s does not match the manifest' % rel
        return None
    case('verify-passes-on-unmodified-copy+reverse-apply-matches-manifest', verify_pristine)

    def upstream_byte_edit():
        d, web = fresh()
        b = bytearray((web / 'style.css').read_bytes()); b[0] ^= 0x20
        (web / 'style.css').write_bytes(bytes(b))
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'style.css: differs from upstream')
    case('verify-fails-one-byte-edit-to-upstream-style.css', upstream_byte_edit)

    # Only the three documented forms: an extra argument, --verify anywhere but first, or an unknown
    # option is refused (exit 2, usage) and nothing is written. Each of these used to be accepted:
    # "web --verify" verified, "web extra" applied to web and ignored the rest.
    def bad_args():
        d, web = fresh()
        before ={p: p.read_bytes() for p in sorted(web.rglob('*')) if p.is_file()}
        for args in ([str(web), '--verify'], ['--verify', str(web), 'extra'], [str(web), 'extra'], ['--frobnicate'],
                     ['--verify', '--verify', str(web)], ['--write-manifest'], ['--write-manifest', 'HEAD', 'extra'],
                     ['--write-manifest', '--verify']):
            rc, out = patch(d, *args)
            if rc != 2 or 'usage: tools/android-patch.py' not in out or 'Traceback' in out:
                return '%r: exit %d, want 2 with usage; output: %s' % (args, rc, out.strip()[:300])
        after = {p: p.read_bytes() for p in sorted(web.rglob('*')) if p.is_file()}
        if after != before:
            return 'a refused call changed web/'
        rc, out = patch(d, '--verify', str(web))   # the documented forms still work
        return expect(rc, out, 0, 'verified: ')
    case('unknown-or-extra-arguments-refused', bad_args)

    def edit_in_patched_region():
        d, web = fresh()
        edit(web / MAIN_JS, ART_NEW.encode(), b"art: ['Screensaver!', 'art']")
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'main.js: anchor', ART_OLD, 'matches neither')
    case('verify-fails-edit-inside-patched-region-names-anchor', edit_in_patched_region)

    def edit_outside_regions():
        d, web = fresh()
        b = (web / MAIN_JS).read_bytes()
        (web / MAIN_JS).write_bytes(b'/* hand edit */' + b)
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'main.js: is not upstream', 'reverse-applied sha256')
    case('verify-fails-edit-outside-patched-regions-of-main.js', edit_outside_regions)

    def stray_file():
        d, web = fresh()
        (web / 'x.jks').write_bytes(b'not a keystore')
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'x.jks: neither upstream nor in OURS')
    case('verify-fails-stray-x.jks', stray_file)

    def symlink():
        d, web = fresh()
        os.symlink('style.css', str(web / 'link.css'))
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'link.css: is a symlink')
    case('verify-fails-symlink', symlink)

    def upstream_deleted():
        d, web = fresh()
        (web / 'style.css').unlink()
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'style.css: upstream file is missing')
    case('verify-fails-upstream-file-deleted', upstream_deleted)

    def ours_deleted():
        d, web = fresh()
        (web / 'device.js').unlink()
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'device.js: listed in OURS but missing')
    case('verify-fails-OURS-file-deleted', ours_deleted)

    def crlf():
        d, web = fresh()
        b = (web / MAIN_JS).read_bytes()
        assert b'\r\n' not in b, 'test setup: main.js already has CRLF'
        (web / MAIN_JS).write_bytes(b.replace(b'\n', b'\r\n'))
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'main.js: anchor', 'matches neither')
    case('verify-fails-CRLF-main.js', crlf)

    def partly_patched():
        d, web = fresh()
        edit(web / MAIN_JS, ART_NEW.encode(), ART_OLD.encode())   # one anchor reverted
        before = {f: sha(web / f) for f in (MAIN_JS, METRICS_JS)}
        rc, out = patch(d, str(web))
        p = expect(rc, out, 1, 'android-patch: main.js: partly patched', ART_OLD, absent=('Traceback', 'NEW UPSTREAM'))
        if p:
            return 'apply: ' + p
        after = {f: sha(web / f) for f in (MAIN_JS, METRICS_JS)}
        if after != before:
            return 'apply wrote to %s' % [f for f in before if before[f] != after[f]]
        rc, out = patch(d, '--verify', str(web))
        p = expect(rc, out, 1, 'main.js: partly patched', ART_OLD)
        return 'verify: ' + p if p else None
    case('partly-patched-apply-refuses-writes-nothing+verify-fails', partly_patched)

    def files_disagree():
        d, web = fresh()
        (web / METRICS_JS).write_bytes(git('show', '%s:web/%s' % (UPSTREAM_COPY, METRICS_JS)))
        before = {f: sha(web / f) for f in (MAIN_JS, METRICS_JS)}
        rc, out = patch(d, str(web))
        p = expect(rc, out, 1, 'partly patched', 'main.js is patched', 'metrics.js is unpatched',
                   absent=('Traceback', 'NEW UPSTREAM'))
        if p:
            return p
        after = {f: sha(web / f) for f in (MAIN_JS, METRICS_JS)}
        return None if after == before else 'apply wrote a file'
    case('apply-refuses-main.js-patched-metrics.js-not', files_disagree)

    def apply_pristine():
        d, web = fresh()
        if PATCHED != [MAIN_JS, METRICS_JS, INDEX_HTML]:
            return 'ap.FILES is %s: update this test' % PATCHED
        for f in PATCHED:
            (web / f).write_bytes(git('show', '%s:web/%s' % (UPSTREAM_COPY, f)))
        rc, out = patch(d, str(web))
        p = expect(rc, out, 0, 'patched main.js, assets/icons/metrics.js, index.html', absent=('Traceback', 'NEW UPSTREAM'))
        if p:
            return 'first apply: ' + p
        for f in PATCHED:
            if (web / f).read_bytes() != (ROOT / 'web' / f).read_bytes():
                return '%s after apply is not byte-identical to the committed web/%s' % (f, f)
        rc, out = patch(d, str(web))
        p = expect(rc, out, 0, 'already applied')
        if p:
            return 'second apply: ' + p
        rc, out = patch(d, '--verify', str(web))
        p = expect(rc, out, 0, 'verified: ')
        return 'verify after apply: ' + p if p else None
    case('apply-on-pristine-upstream-is-byte-identical+second-apply-no-op', apply_pristine)

    def index_outside_regions():
        d, web = fresh()
        edit(web / INDEX_HTML, b'<title>CleanFrame</title>', b'<title>CleanFrame!</title>')
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'index.html: is not upstream', 'reverse-applied sha256')
    case('verify-fails-hand-edit-of-index.html', index_outside_regions)

    def index_partly_patched():
        d, web = fresh()
        edit(web / INDEX_HTML, b'<script src="assets/wallpapers/space-catalog.js"></script>\n', b'')
        before = {f: sha(web / f) for f in PATCHED}
        rc, out = patch(d, str(web))
        p = expect(rc, out, 1, 'index.html: partly patched', 'catalog.js', absent=('Traceback', 'NEW UPSTREAM'))
        if p:
            return p
        return None if {f: sha(web / f) for f in PATCHED} == before else 'apply wrote a file'
    case('index.html-without-the-space-script-is-partly-patched', index_partly_patched)

    def required_space_catalog():
        # Since the Space gallery shipped (2026-10-31) space-catalog.js is required: listed in OURS,
        # not OPTIONAL, so deleting it fails --verify; a Space image nobody listed is still a stray.
        d, web = fresh()
        rel = 'assets/wallpapers/space-catalog.js'
        if rel not in ap.OURS or rel in ap.OPTIONAL:
            return '%s is not (only) in OURS' % rel
        (web / rel).unlink()
        rc, out = patch(d, '--verify', str(web))
        p = expect(rc, out, 1, rel + ': listed in OURS but missing')
        if p:
            return 'absent: ' + p
        d, web = fresh()
        (web / 'assets/wallpapers/space-x.jpg').write_bytes(b'not listed')
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'assets/wallpapers/space-x.jpg: neither upstream nor in OURS')
    case('verify-space-catalog-required-and-unlisted-images-refused', required_space_catalog)

    def required_cyberpunk_catalog():
        # The Cyberpunk gallery (2026-11-01) is required like Space: cyberpunk-catalog.js is in OURS,
        # deleting it fails --verify, an unlisted cyberpunk-* image is a stray, and index.html
        # without its script tag is partly patched.
        d, web = fresh()
        rel = 'assets/wallpapers/cyberpunk-catalog.js'
        if rel not in ap.OURS or rel in ap.OPTIONAL:
            return '%s is not (only) in OURS' % rel
        (web / rel).unlink()
        rc, out = patch(d, '--verify', str(web))
        p = expect(rc, out, 1, rel + ': listed in OURS but missing')
        if p:
            return 'absent: ' + p
        d, web = fresh()
        (web / 'assets/materials/cyberpunk-x-field-blur.jpg').write_bytes(b'not listed')
        rc, out = patch(d, '--verify', str(web))
        p = expect(rc, out, 1, 'assets/materials/cyberpunk-x-field-blur.jpg: neither upstream nor in OURS')
        if p:
            return 'stray: ' + p
        d, web = fresh()
        edit(web / INDEX_HTML, b'<script src="assets/wallpapers/cyberpunk-catalog.js"></script>\n', b'')
        rc, out = patch(d, str(web))
        return expect(rc, out, 1, 'index.html: partly patched', absent=('Traceback', 'NEW UPSTREAM'))
    case('verify-cyberpunk-catalog-required-unlisted-images-refused-script-tag-required', required_cyberpunk_catalog)

    def ours_groups_deleted():
        d, web = fresh()
        (web / 'wallpaper-groups.js').unlink()
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, 'wallpaper-groups.js: listed in OURS but missing')
    case('verify-fails-wallpaper-groups.js-deleted', ours_groups_deleted)

    def manifest_missing_entry():
        d, web = fresh()
        mp = d / 'tools' / 'upstream-manifest.json'
        m = json.loads(mp.read_bytes()); del m['files']['main.js']
        mp.write_text(json.dumps(m, indent=1) + '\n')
        for args in (['--verify', str(web)], [str(web)]):
            rc, out = patch(d, *args)
            p = expect(rc, out, 1, 'upstream-manifest.json: no entry for main.js', absent=('Traceback', 'KeyError'))
            if p:
                return '%s: %s' % (' '.join(args[:-1]) or 'apply', p)
        return None
    case('manifest-missing-main.js-is-a-clean-PatchError', manifest_missing_entry)

    def every_anchor_fails_when_upstream_moves():
        # Each (old, new, count): upstream with that one anchor's text moved (a space inserted
        # inside its first occurrence) no longer classifies, and the error names that anchor.
        bad = []
        for name, pairs in ap.FILES:
            text = git('show', '%s:web/%s' % (UPSTREAM_COPY, name)).decode('utf-8')
            if ap.state(text, pairs, name) != 'unpatched':
                return '%s at %s does not classify as unpatched' % (name, UPSTREAM_COPY)
            for old, new, want in pairs:
                k = max(1, len(old) // 2)
                moved = text.replace(old, old[:k] + ' ' + old[k:], 1)
                try:
                    ap.state(moved, pairs, name)
                    bad.append('%s %s: still classifies' % (name, ap._short(old)))
                except ap.PatchError as e:
                    if ap._short(old) not in str(e):
                        bad.append('%s %s: error names another anchor: %s' % (name, ap._short(old), e))
        return '; '.join(bad[:5]) or None
    case('every-anchor-fails-loudly-when-upstream-moves-it', every_anchor_fails_when_upstream_moves)

    def photo_group_anchor_moved_apply_refuses():
        # End to end through the tool: upstream main.js whose Background page sections moved.
        d, web = fresh()
        for f in PATCHED:
            (web / f).write_bytes(git('show', '%s:web/%s' % (UPSTREAM_COPY, f)))
        old = "{ id: 'wall', title: 'Wallpaper', items:"
        edit(web / MAIN_JS, old.encode(), b"{ id: 'wall', title: 'Photos', items:")
        before = sha(web / MAIN_JS)
        rc, out = patch(d, str(web))
        p = expect(rc, out, 1, 'NEW UPSTREAM', 'anchor failure', "id: 'bg', title: 'Background type'", absent=('Traceback',))
        if p:
            return p
        return None if sha(web / MAIN_JS) == before else 'apply wrote main.js'
    case('photo-groups-anchor-moved-apply-refuses-names-it', photo_group_anchor_moved_apply_refuses)

    def qr_vendored_pinned():
        rel = ap.QR_FILE
        if rel not in ap.OURS or ap.OURS[rel] != ap.QR_REASON:
            return '%s is not in OURS with QR_REASON' % rel
        got = sha(pristine / 'web' / rel)
        if got != ap.QR_SHA256:
            return '%s sha256 %s, recorded %s' % (rel, got, ap.QR_SHA256)
        for want in ('qrcode-generator 1.4.4', 'Kazuhiko Arase', 'MIT', 'registry.npmjs.org/qrcode-generator/-/qrcode-generator-1.4.4.tgz', ap.QR_SHA256):
            if want not in ap.QR_REASON:
                return 'QR_REASON lacks %r' % want
        lic = (pristine / 'web' / 'LICENSES' / 'qrcode-generator-MIT.txt').read_text()
        if 'MIT License' not in lic or 'Copyright (c) 2009 Kazuhiko Arase' not in lic:
            return 'web/LICENSES/qrcode-generator-MIT.txt is not its MIT licence'
        if 'LICENSES/qrcode-generator-MIT.txt' not in (pristine / 'web' / 'LICENSES' / 'README.txt').read_text():
            return 'web/LICENSES/README.txt does not list it'
        notices = (ROOT / 'THIRD_PARTY_NOTICES.md').read_text()
        if 'qrcode-generator' not in notices or ap.QR_SHA256 not in notices:
            return 'THIRD_PARTY_NOTICES.md does not record it with its sha-256'
        d, web = fresh()   # one changed byte fails --verify, naming the file and the recorded sha
        b = bytearray((web / rel).read_bytes()); b[-2] ^= 0x01
        (web / rel).write_bytes(bytes(b))
        rc, out = patch(d, '--verify', str(web))
        return expect(rc, out, 1, rel + ': is not the vendored qrcode-generator 1.4.4', ap.QR_SHA256)
    case('qrcode-generator-vendored-sha256-licence-and-notices', qr_vendored_pinned)

    # Writes are all or none (B1, review of 30e7356): every patched file is written to a temp file
    # first and only then moved into place. Upstream's three files, then a write that cannot happen
    # for the second (metrics.js and its directory read-only): before, main.js was already written.
    def upstream_files(web):
        for f in PATCHED:
            (web / f).write_bytes(git('show', '%s:web/%s' % (UPSTREAM_COPY, f)))
        return {f: sha(web / f) for f in PATCHED}

    def no_temps(web):
        left = sorted(p.relative_to(web).as_posix() for p in web.rglob('*' + ap.TMP_SUFFIX))
        return 'temp files left in web/: %s' % left if left else None

    def write_failure_writes_nothing():
        if os.geteuid() == 0:   # root ignores the modes; the move-failure case below still runs
            return 'run as root: file modes cannot make a write fail (run the gate as a user)'
        d, web = fresh()
        before = upstream_files(web)
        icons = web / 'assets' / 'icons'
        os.chmod(str(icons / 'metrics.js'), 0o444); os.chmod(str(icons), 0o555)
        try:
            rc, out = patch(d, str(web))
        finally:
            os.chmod(str(icons), 0o755); os.chmod(str(icons / 'metrics.js'), 0o644)
        p = expect(rc, out, 1, 'nothing was changed', absent=('Traceback', 'patched main.js'))
        if p:
            return p
        after = {f: sha(web / f) for f in PATCHED}
        if after != before:
            return 'a failed apply changed %s' % [f for f in PATCHED if after[f] != before[f]]
        return no_temps(web)
    case('apply-write-failure-on-metrics.js-writes-no-file', write_failure_writes_nothing)

    def move_failure_puts_back():
        # In process: os.replace fails for the second file. The first, already moved, gets its old
        # bytes back; the call fails naming the file; no temp file is left.
        import contextlib, io
        d, web = fresh()
        before = upstream_files(web)
        spec2 = importlib.util.spec_from_file_location('android_patch_c', d / 'tools' / 'android-patch.py')
        m = importlib.util.module_from_spec(spec2)
        spec2.loader.exec_module(m)
        real, calls = os.replace, []

        def flaky(src, dst):
            calls.append(dst)
            if len(calls) == 2:
                raise OSError(28, 'No space left on device')
            return real(src, dst)
        m.os.replace = flaky
        err = io.StringIO()
        try:
            with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
                rc = m.main(['android-patch.py', str(web)])
        finally:
            m.os.replace = real
        out = err.getvalue()
        if len(calls) != 2:
            return 'os.replace called %d times, want 2 (the second fails): the files are not moved into place' % len(calls)
        p = expect(rc, out, 1, 'could not write assets/icons/metrics.js', 'put back main.js')
        if p:
            return p
        after = {f: sha(web / f) for f in PATCHED}
        if after != before:
            return 'a failed apply left %s changed' % [f for f in PATCHED if after[f] != before[f]]
        return no_temps(web)
    case('apply-move-failure-puts-back-the-moved-file', move_failure_puts_back)

    def existing_temp_survives_refusal():
        # A temp path that is already there (another run's, say) is refused ('x') and left alone:
        # before, it was registered for clean-up before open() and the refusal unlinked it. It is
        # the LAST file's temp, so the earlier files' own temps were made (and must be removed).
        d, web = fresh()
        before = upstream_files(web)
        theirs = web / (ap.FILES[-1][0] + ap.TMP_SUFFIX)
        theirs.write_bytes(b'not ours\n')
        rc, out = patch(d, str(web))
        p = expect(rc, out, 1, 'nothing was changed', absent=('Traceback',))
        if p:
            return p
        if not theirs.exists() or theirs.read_bytes() != b'not ours\n':
            return 'the refusal removed or changed %s, which this run did not create' % theirs.name
        after = {f: sha(web / f) for f in PATCHED}
        if after != before:
            return 'a refused apply changed %s' % [f for f in PATCHED if after[f] != before[f]]
        theirs.unlink()
        return no_temps(web)
    case('apply-refuses-an-existing-temp-file-and-leaves-it', existing_temp_survives_refusal)

    g = tmp / 'clone'
    subprocess.run(['git', 'clone', '-q', '--shared', '--no-checkout', str(ROOT), str(g)],
                   check=True, capture_output=True, env=GIT_ENV)
    shutil.copytree(pristine / 'tools', g / 'tools')   # the working-tree tool, not HEAD's
    mp = g / 'tools' / 'upstream-manifest.json'
    who = dict(GIT_ENV, GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@example.invalid', GIT_COMMITTER_NAME='t',
               GIT_COMMITTER_EMAIL='t@example.invalid', GIT_INDEX_FILE=str(tmp / 'index'))

    def write_manifest(commit):
        r = subprocess.run([sys.executable, '-I', str(g / 'tools' / 'android-patch.py'), '--write-manifest', commit],
                           capture_output=True, text=True, cwd=str(g), env=GIT_ENV, timeout=60)
        return r.returncode, r.stdout + r.stderr

    def commit_like_upstream_copy(message, extra=None):
        """A commit in the clone: the upstream-copy commit's tree, plus `extra` (path -> bytes)."""
        def g_(*a, data=None):
            return subprocess.run(['git', '-C', str(g)] + list(a), input=data, check=True,
                                  capture_output=True, env=who).stdout.decode().strip()
        g_('read-tree', UPSTREAM_COPY)
        for path, data in (extra or {}).items():
            blob = g_('hash-object', '-w', '--stdin', data=data)
            g_('update-index', '--add', '--cacheinfo', '100644,%s,%s' % (blob, path))
        return g_('commit-tree', g_('write-tree'), '-p', UPSTREAM_COPY, '-m', message)

    def write_manifest_on_patched_head():
        before = sha(mp)
        rc, out = write_manifest('HEAD')
        p = expect(rc, out, 1, 'main.js at HEAD is already patched')
        if p:
            return p
        return None if sha(mp) == before else 'the manifest was rewritten'
    case('write-manifest-on-patched-HEAD-refused', write_manifest_on_patched_head)

    def write_manifest_upstream_copy_reproduces():
        before = mp.read_bytes()
        rc, out = write_manifest(UPSTREAM_COPY)
        p = expect(rc, out, 0, 'wrote upstream-manifest.json')
        if p:
            return p
        return None if mp.read_bytes() == before else 'the manifest from %s differs from the committed one' % UPSTREAM_COPY
    case('write-manifest-from-the-upstream-copy-reproduces-the-committed-manifest', write_manifest_upstream_copy_reproduces)

    # B3 (review of 30e7356): an unpatched commit is not enough. One holding a file of ours (here a
    # mark we added) would pin it as upstream; one whose message does not name UPSTREAM_SHA may be
    # another upstream's copy. Both are refused and the manifest is left as it was.
    def write_manifest_ours_refused():
        before = sha(mp)
        c = commit_like_upstream_copy('Vendor CleanFrame app/ at upstream %s' % ap.UPSTREAM_SHA,
                                      {'web/assets/icons/emby.svg': b'<svg/>', 'web/LICENSES/README.txt': b'x'})
        rc, out = write_manifest(c)
        p = expect(rc, out, 1, 'LICENSES/README.txt, assets/icons/emby.svg at %s are ours' % c, absent=('Traceback', 'wrote '))
        if p:
            return p
        return None if sha(mp) == before else 'the manifest was rewritten'
    case('write-manifest-refuses-a-commit-holding-our-files', write_manifest_ours_refused)

    def write_manifest_other_upstream_refused():
        before = sha(mp)
        c = commit_like_upstream_copy('Vendor CleanFrame app/ at upstream 0123456789abcdef0123456789abcdef01234567')
        rc, out = write_manifest(c)
        p = expect(rc, out, 1, 'does not name upstream %s' % ap.UPSTREAM_SHA, absent=('Traceback', 'wrote '))
        if p:
            return p
        return None if sha(mp) == before else 'the manifest was rewritten'
    case('write-manifest-refuses-a-commit-not-naming-UPSTREAM_SHA', write_manifest_other_upstream_refused)


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""build.sh keeps Gradle dependency verification strict (README, "Build"): it refuses
org.gradle.dependency.verification set in ~/.gradle/gradle.properties (also as systemProp., or anywhere in
a value or continuation line such as org.gradle.jvmargs=-D...; a commented-out line is allowed) or in
GRADLE_OPTS/JAVA_OPTS/JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS, refuses any Gradle init script (init.gradle,
init.gradle.kts, init.d/ in the Gradle user home or in a wrapper distribution), allows unrelated
properties, passes --dependency-verification strict on every ./gradlew line, refuses a tree without
android/gradle/verification-metadata.xml (tools/rollback.sh keeps HEAD's for an older SHA), and also scans
the gradle.properties of a wrapper distribution (Gradle's installation directory).
Split out of tests/gradle.test.py (2026-10-31): that file checks android/'s Gradle files against the
metadata and is rolled back with android/; this one tests build.sh, which tools/rollback.sh keeps at
HEAD, so it is in rollback.sh's HEAD_TESTS and runs whatever android/ a rollback restored. It does
not read android/ at all: build.sh only checks that the metadata file exists, so each case gets a
stub file (or none).
Each case runs a copy of build.sh in a temp dir (under $TMPDIR, else ~/scratch; removed on exit)
with no git repo, no toolchain and no signing file, so it stops at a refusal (a verification one, or
else "not a full git clone", which comes right after them) and never reaches Gradle. Python 3 stdlib only; no network, no Gradle, no device.
Usage (from the repo root): python3 -I tests/build-verification.test.py
Prints PASS/FAIL lines, then BUILD_VERIFICATION_TEST_OK, or BUILD_VERIFICATION_TEST_FAIL <n> with exit 1."""
import os, pathlib, re, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERIFY_PROP = 'org.gradle.dependency.verification'
STUB_META = ('<?xml version="1.0" encoding="UTF-8"?>\n'
             '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification"/>\n')
fails = []


def check(name, ok, detail=''):
    print('%s %s%s' % ('PASS' if ok else 'FAIL', name, '' if ok else ': ' + detail))
    if not ok:
        fails.append(name)


def finish():
    if fails:
        print('BUILD_VERIFICATION_TEST_FAIL %d (%s)' % (len(fails), ', '.join(fails)))
        sys.exit(1)
    print('BUILD_VERIFICATION_TEST_OK')
    sys.exit(0)


build = ROOT / 'build.sh'
if not build.is_file():
    check('build-sh-present', False, 'no build.sh')
    finish()
base = pathlib.Path(os.environ.get('TMPDIR') or pathlib.Path.home() / 'scratch')
base.mkdir(parents=True, exist_ok=True)
tmp = pathlib.Path(tempfile.mkdtemp(prefix='cf-build-verification-test.', dir=str(base)))
try:
    def run_build(gh_props=None, extra_env=None, with_meta=True, gh_files=(), gh_text=None):
        d = tmp / ('b%d' % len(list(tmp.iterdir())))
        (d / 'android' / 'gradle').mkdir(parents=True)
        shutil.copy(str(build), str(d / 'build.sh'))
        if with_meta:
            (d / 'android' / 'gradle' / 'verification-metadata.xml').write_text(STUB_META)
        gh = d / 'gh'; gh.mkdir()
        if gh_props is not None:
            (gh / 'gradle.properties').write_text(gh_props)
        for rel in gh_files:   # e.g. an init script, relative to the Gradle user home; 'x/' = an empty dir
            (gh / rel).mkdir(parents=True, exist_ok=True) if rel.endswith('/') else (gh / rel).parent.mkdir(parents=True, exist_ok=True)
            if not rel.endswith('/'): (gh / rel).write_text(gh_text if gh_text is not None else '// tools test: an init script\n')
        env = {k: v for k, v in os.environ.items() if not k.startswith(('GRADLE_', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLEANFRAME_', 'ORG_GRADLE_', 'GIT_'))}
        # stopped before Gradle: no git repo, no toolchain, no signing file -> a refusal or a git error
        env.update(GRADLE_USER_HOME=str(gh), ANDROID_TOOLCHAIN=str(d / 'no-toolchain'),
                   CLEANFRAME_SIGNING=str(d / 'no-signing.properties'), GIT_CEILING_DIRECTORIES=str(d.parent),
                   **(extra_env or {}))
        r = subprocess.run(['bash', str(d / 'build.sh')], cwd=str(d), env=env, capture_output=True, text=True, timeout=60)
        return r.returncode, r.stdout + r.stderr
    REFUSE = 'dependency verification must stay strict'
    for name, kw in [('lenient', dict(gh_props='%s=lenient\n' % VERIFY_PROP)),
                     ('off', dict(gh_props='  systemProp.%s = off\n' % VERIFY_PROP)),
                     # in a value, not as a key: the JVM Gradle runs in gets it as a system property
                     ('in-jvmargs', dict(gh_props='org.gradle.jvmargs=-Xmx2g -D%s=lenient\n' % VERIFY_PROP)),
                     ('on-continuation-line', dict(gh_props='org.gradle.jvmargs=-Xmx2g \\\n    -D%s=off\n' % VERIFY_PROP)),
                     ('gradle-opts', dict(extra_env={'GRADLE_OPTS': '-D%s=off' % VERIFY_PROP})),
                     ('java-opts', dict(extra_env={'JAVA_OPTS': '-D%s=lenient' % VERIFY_PROP})),
                     ('java-tool-options', dict(extra_env={'JAVA_TOOL_OPTIONS': '-Xmx1g -D%s=off' % VERIFY_PROP})),
                     ('underscore-java-options', dict(extra_env={'_JAVA_OPTIONS': '-D%s=lenient' % VERIFY_PROP})),
                     ('jdk-java-options', dict(extra_env={'JDK_JAVA_OPTIONS': '-D%s=off' % VERIFY_PROP}))]:
        rc, out = run_build(**kw)
        check('build-refuses-verification-%s' % name, rc != 0 and REFUSE in out, 'rc=%d: %s' % (rc, out[-400:]))
    # Gradle's installation directory has a gradle.properties too (the wrapper's unpacked distribution):
    # a verification setting there is refused like one in the Gradle user home; an unrelated one is not.
    DIST_PROPS = 'wrapper/dists/gradle-8.7-bin/abc123/gradle-8.7/gradle.properties'
    rc, out = run_build(gh_files=(DIST_PROPS,), gh_text='%s=lenient\n' % VERIFY_PROP)
    check('build-refuses-verification-in-gradle-installation', rc != 0 and REFUSE in out and DIST_PROPS in out, 'rc=%d: %s' % (rc, out[-400:]))
    rc, out = run_build(gh_files=(DIST_PROPS,), gh_text='org.gradle.jvmargs=-Xmx2g\n')
    check('build-allows-unrelated-gradle-installation-properties', REFUSE not in out and 'is not a full git clone' in out, 'rc=%d: %s' % (rc, out[-400:]))
    INIT = 'Gradle init script'
    for rel in ('init.gradle', 'init.gradle.kts', 'init.d/lenient.gradle', 'init.d/x.gradle.kts',
                'wrapper/dists/gradle-8.7-bin/abc123/gradle-8.7/init.d/dist.gradle'):
        rc, out = run_build(gh_files=(rel,))
        check('build-refuses-init-script-%s' % rel.replace('/', '-'), rc != 0 and INIT in out and rel in out,
              'rc=%d: %s' % (rc, out[-400:]))
    # an init.d/ with only the readme.txt every Gradle distribution ships (Gradle runs only *.gradle and
    # *.gradle.kts from there), and an unrelated file beside it, are not init scripts
    rc, out = run_build(gh_files=('init.d/readme.txt', 'wrapper/dists/gradle-8.7-bin/abc123/gradle-8.7/init.d/readme.txt',
                                  'wrapper/dists/gradle-8.7-bin/abc123/gradle-8.7/lib/x.jar'))
    check('build-allows-no-init-script', INIT not in out and 'is not a full git clone' in out, out[-400:])
    # every Gradle run in build.sh asks for strict verification itself (accepted by Gradle 8.7: a real
    # ./build.sh run proves it; this keeps the flag from being dropped)
    gl = [l for l in build.read_text().splitlines() if re.search(r'(^|[\s(;&])\./gradlew\s', l) and not l.lstrip().startswith(('#', 'echo'))]
    check('build-gradle-runs-strict', gl and all('--dependency-verification strict' in l for l in gl), repr(gl))
    rc, out = run_build(gh_props='org.gradle.jvmargs=-Xmx2g\n# %s=lenient (a comment)\n!   -D%s=off\n' % (VERIFY_PROP, VERIFY_PROP))
    check('build-allows-unrelated-properties-and-comments', REFUSE not in out, out[-400:])
    # Gradle never runs here: the copy is not in a git repo, so build.sh stops at that refusal, which
    # comes after every verification refusal (so reaching it means they all let this one through).
    check('build-stops-before-gradle', 'is not a full git clone' in out and rc != 0, 'rc=%d: %s' % (rc, out[-400:]))
    # No verification metadata, no build (S5): refused before anything else runs, pointing at
    # tools/rollback.sh and the README's regeneration; a tree with it is not refused for it.
    NOMETA = 'refusing: no android/gradle/verification-metadata.xml'
    check('build-not-refused-with-metadata', NOMETA not in out, out[-400:])
    rc, out = run_build(with_meta=False)
    check('build-refuses-tree-without-metadata', rc != 0 and NOMETA in out and 'tools/rollback.sh' in out
          and 'is not a full git clone' not in out, 'rc=%d: %s' % (rc, out[-400:]))
finally:
    shutil.rmtree(str(tmp), ignore_errors=True)
finish()

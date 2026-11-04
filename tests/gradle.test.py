#!/usr/bin/env python3
"""Gradle dependency verification fails closed. android/gradle/verification-metadata.xml pins every
plugin and library artifact the release and staging builds resolve by SHA-256 (README, "Build");
this checks, offline, that the pin is real and complete for what the *.gradle files declare:
  - the file exists and parses as XML in Gradle's dependency-verification namespace;
  - <verify-metadata> is true and <verify-signatures> is explicitly false (policy: SHA-256 only,
    no PGP; no <trusted-keys>/<ignored-keys>/<pgp> either, so nothing leans on signatures that are
    never checked) — turning PGP on is a deliberate change to this test;
  - no <trusted-artifacts> (no "trust everything"/whole-group escape hatch) and no regex matching;
  - every component has group/name/version and at least one artifact, and every artifact has a
    64-hex <sha256> (sha1/md5 alone are not accepted);
  - every plugin `id '...' version '...'` and every 'group:name:version' coordinate in the
    android/**/*.gradle files is in the metadata (a plugin through its marker,
    <id>:<id>.gradle.plugin:<version>; AGP also as com.android.tools.build:gradle), with no dynamic
    versions; a version catalog or *.gradle.kts (which this does not parse) fails;
  - android/gradle.properties does not set org.gradle.dependency.verification.
build.sh's own refusals (a weakening from ~/.gradle/gradle.properties or GRADLE_OPTS/JAVA_OPTS, the
NOTE for a tree without the metadata) are in tests/build-verification.test.py: build.sh is not
rolled back, this file and what it checks (android/) are.
It checks the tree it sits in (ROOT = this file's ../). tools/rollback.sh restores this test with
android/ (it is in APP_TESTS), so a rollback to a SHA from before verification restores neither this
test nor the file: that tree is unverified by its own definition and build.sh says so. A tree that
HAS this test always needs the file. Python 3 stdlib only; no network, no Gradle, no device.
Usage (from the repo root): python3 -I tests/gradle.test.py
Prints PASS/FAIL lines, then GRADLE_TEST_OK, or GRADLE_TEST_FAIL <n> with exit 1."""
import pathlib, re, sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID = ROOT / 'android'
META = ANDROID / 'gradle' / 'verification-metadata.xml'
NS = '{https://schema.gradle.org/dependency-verification}'
VERIFY_PROP = 'org.gradle.dependency.verification'
fails = []


def check(name, ok, detail=''):
    print('%s %s%s' % ('PASS' if ok else 'FAIL', name, '' if ok else ': ' + detail))
    if not ok:
        fails.append(name)


def tag(e):
    return e.tag[len(NS):] if e.tag.startswith(NS) else e.tag


def finish():
    if fails:
        print('GRADLE_TEST_FAIL %d (%s)' % (len(fails), ', '.join(fails)))
        sys.exit(1)
    print('GRADLE_TEST_OK')
    sys.exit(0)


# ------------------------------------------------------------------ the metadata file
check('metadata-exists', META.is_file(), '%s missing (regenerate it: README, "Build")' % META.relative_to(ROOT))
if not META.is_file():
    finish()
try:
    tree = ET.parse(str(META))
    doc = tree.getroot()
    check('metadata-xml', tag(doc) == 'verification-metadata' and doc.tag.startswith(NS),
          'root element is %r, not verification-metadata in %s' % (doc.tag, NS))
except ET.ParseError as e:
    check('metadata-xml', False, 'not valid XML: %s' % e)
    finish()

cfg = doc.find(NS + 'configuration')
def cfg_text(name):
    e = cfg.find(NS + name) if cfg is not None else None
    return None if e is None else (e.text or '').strip()
check('verify-metadata-true', cfg_text('verify-metadata') == 'true',
      '<verify-metadata> is %r (must be true)' % cfg_text('verify-metadata'))
check('verify-signatures-explicitly-false', cfg_text('verify-signatures') == 'false',
      '<verify-signatures> is %r; the policy is SHA-256 only (README): PGP on is a deliberate change '
      'to this test, never a silent one' % cfg_text('verify-signatures'))
banned = sorted({tag(e) for e in doc.iter() if tag(e) in ('trusted-artifacts', 'trust', 'trusted-keys',
                 'trusted-key', 'ignored-keys', 'ignored-key', 'pgp', 'key-servers')})
check('no-trust-or-pgp-escape-hatches', not banned, 'found <%s>' % '>, <'.join(banned))
regex = sorted({tag(e) for e in doc.iter() if e.get('regex') not in (None, 'false')})
check('no-regex-matching', not regex, 'regex="true" on <%s>' % '>, <'.join(regex))

comps = doc.find(NS + 'components')
components = [] if comps is None else [c for c in comps if tag(c) == 'component']
check('has-components', len(components) > 0, 'no <component> entries')
pinned = set()
bad = []
n_art = 0
for c in components:
    g, n, v = c.get('group'), c.get('name'), c.get('version')
    who = '%s:%s:%s' % (g, n, v)
    if not (g and n and v):
        bad.append('%s: group/name/version missing' % who); continue
    pinned.add((g, n, v))
    arts = [a for a in c if tag(a) == 'artifact']
    if not arts:
        bad.append('%s: no <artifact>' % who)
    for a in arts:
        n_art += 1
        shas = [s.get('value', '') for s in a if tag(s) == 'sha256']
        if not shas or not all(re.fullmatch(r'[0-9a-f]{64}', s) for s in shas):
            bad.append('%s %s: no valid <sha256>' % (who, a.get('name')))
check('every-artifact-sha256', not bad, '; '.join(bad[:5]) + (' ...' if len(bad) > 5 else ''))

# ------------------------------------------------------------------ declared versions are pinned
def sources(pattern):   # build outputs and Gradle's own android/.gradle cache are not sources
    return sorted(p for p in ANDROID.rglob(pattern) if p.is_file() and not {'build', '.gradle'} & set(p.relative_to(ANDROID).parts[:-1]))
gradle_files = sources('*.gradle')
unparsed = [str(p.relative_to(ROOT)) for p in sources('*.gradle.kts') + sources('*.versions.toml')]
check('no-unparsed-build-files', not unparsed, '%s: this test only reads Groovy *.gradle; extend it' % ', '.join(unparsed))
check('found-gradle-files', len(gradle_files) >= 2, 'found %s' % [str(p) for p in gradle_files])
declared = []   # (what, (group, name, version))
dynamic = []
for p in gradle_files:
    text = re.sub(r'//[^\n]*', '', p.read_text())
    rel = str(p.relative_to(ROOT))
    for pid, ver in re.findall(r"""\bid\s*\(?\s*['"]([\w.\-]+)['"]\s*\)?\s+version\s*\(?\s*['"]([^'"]+)['"]""", text):
        declared.append(('%s plugin %s %s' % (rel, pid, ver), (pid, pid + '.gradle.plugin', ver)))
        if pid in ('com.android.application', 'com.android.library'):
            declared.append(('%s AGP %s' % (rel, ver), ('com.android.tools.build', 'gradle', ver)))
    for g, n, v in re.findall(r"""['"]([\w.\-]+):([\w.\-]+):([^'"@:\s]+)(?::[^'"@\s]*)?(?:@\w+)?['"]""", text):
        declared.append(('%s dependency %s:%s:%s' % (rel, g, n, v), (g, n, v)))
    # a plugin with a version not written as a literal (a variable, a property) cannot be checked
    for m in re.findall(r"""\bid\s*\(?\s*['"][\w.\-]+['"]\s*\)?\s+version\s+(?!\(?\s*['"])(\S+)""", text):
        dynamic.append('%s: plugin version %s is not a literal' % (rel, m))
for what, (g, n, v) in declared:
    if '+' in v or v.startswith('latest.') or re.search(r'[\[\]()$,]', v):
        dynamic.append('%s: dynamic or computed version' % what)
check('no-dynamic-versions', not dynamic, '; '.join(dynamic))
check('agp-declared', any(k[0] == 'com.android.tools.build' for _, k in declared),
      'no com.android.application/library plugin version found in %s (did the plugins block move?)' % [str(p) for p in gradle_files])
missing = [what for what, key in declared if key not in pinned]
check('declared-versions-pinned', not missing, 'not in verification-metadata.xml: %s' % '; '.join(missing))

props = ANDROID / 'gradle.properties'
check('gradle-properties-no-verification-override',
      not (props.is_file() and re.search(r'(?m)^\s*(systemProp\.)?' + re.escape(VERIFY_PROP) + r'\s*[=:]', props.read_text())),
      'android/gradle.properties sets %s' % VERIFY_PROP)

print('metadata: %d components, %d artifacts, %d declared versions checked' % (len(components), n_art, len(declared)))
finish()

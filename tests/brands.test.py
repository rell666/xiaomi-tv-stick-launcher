#!/usr/bin/env python3
"""Offline consistency test for the brand marks and default Home apps.
Usage (from anywhere): python3 -I tests/brands.test.py      (Python 3 stdlib only, no node)
Reads, never writes: web/device.js (IDS), web/assets/icons/*.svg + metrics.js (FrameIconMetrics),
tools/android-patch.py (the PRIMARY_APPS replacement it writes), web/main.js and README.md.
  - every device.js brand has a mark and measured metrics (an unmeasured mark is drawn stretched
    by CleanFrame's mask-size:100% 100%);
  - the default apps as the patch writes them = PRIMARY_APPS in web/main.js on disk, and each one
    has the same package as device.js IDS (else a default app never gets its brand);
  - README.md's "Default Home apps:" list names the same apps, in the same order;
  - THIRD_PARTY_NOTICES.md names every file we own (OURS in tools/android-patch.py) that is not an
    asset as added by this repository, so it does not read as upstream (c) Nathan Clark, and lists every
    mark in OURS under Brand icons in the row of its licence (ADDITIONAL-SOURCES.json: CC0 Simple Icons,
    or drawn for this repository), by its name in web/app-marks.js.
Prints one PASS/FAIL line per case, then BRANDS_TEST_OK, or BRANDS_TEST_FAIL <n> with exit 1."""
import importlib.util, json, pathlib, re, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
WEB = ROOT / 'web'
ICONS = WEB / 'assets' / 'icons'
FAILS = []
ENTRY = re.compile(r"\{ id: '([a-z0-9]+)', name: '([^']*)', mark: true, appId: '([^']+)' \}")


class Skip(str):
    pass


def text(p):
    return p.read_bytes().decode('utf-8')


def case(name, fn):
    try:
        problem = fn()
    except Exception as e:  # a crash in the case itself is a failure, never a pass
        problem = 'crashed: %s: %s' % (type(e).__name__, e)
    if isinstance(problem, Skip):
        print('SKIP %s -- %s' % (name, problem))
    elif problem:
        FAILS.append(name)
        print('FAIL %s -- %s' % (name, problem))
    else:
        print('PASS %s' % name)


def device_ids():
    """device.js `var IDS = { brand: 'package', ... };` as a dict, duplicates kept visible."""
    src = text(WEB / 'device.js')
    m = re.search(r'var IDS = \{(.*?)\};', src, re.S)
    if not m:
        raise ValueError('web/device.js: no `var IDS = {...};`')
    pairs = re.findall(r"([a-z0-9]+): '([^']+)'", m.group(1))
    leftover = re.sub(r"([a-z0-9]+): '([^']+)'", '', m.group(1)).replace(',', '').split()
    if leftover:
        raise ValueError('web/device.js IDS: unparsed text %r' % ' '.join(leftover)[:80])
    return pairs


def metrics():
    m = re.search(r'FrameIconMetrics = (\{.*\});', text(ICONS / 'metrics.js'))
    if not m:
        raise ValueError('web/assets/icons/metrics.js: no FrameIconMetrics object')
    return json.loads(m.group(1))


def patch_module():
    spec = importlib.util.spec_from_file_location('android_patch', ROOT / 'tools' / 'android-patch.py')
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def patch_primary():
    """PRIMARY_APPS exactly as tools/android-patch.py writes it (the new side of its MAIN pair)."""
    mod = patch_module()
    found = [new for old, new, _n in mod.MAIN if "{ id: 'stremio'" in old]
    if len(found) != 1:
        raise ValueError('tools/android-patch.py: expected 1 PRIMARY_APPS pair in MAIN, found %d' % len(found))
    return ENTRY.findall(found[0])


def disk_primary():
    src = text(WEB / 'main.js')
    start = src.index('var PRIMARY_APPS = [')
    return ENTRY.findall(src[start:src.index('];', start)])


def c_ids_parse():
    pairs = device_ids()
    if not pairs:
        return 'web/device.js IDS is empty'
    brands, pkgs = [b for b, _ in pairs], [p for _, p in pairs]
    dup_b = sorted({b for b in brands if brands.count(b) > 1})
    dup_p = sorted({p for p in pkgs if pkgs.count(p) > 1})
    if dup_b or dup_p:
        return 'web/device.js IDS: duplicate brand(s) %s / package(s) %s' % (dup_b, dup_p)


def c_marks():
    missing = [b for b, _ in device_ids() if not (ICONS / (b + '.svg')).is_file()]
    if missing:
        return 'no mark for brand(s): %s' % ', '.join('%s (web/assets/icons/%s.svg)' % (b, b) for b in missing)


def c_metrics():
    m = metrics()
    missing = [b for b, _ in device_ids() if b not in m]
    if missing:
        return 'brand(s) not measured in web/assets/icons/metrics.js FrameIconMetrics: %s' % ', '.join(missing)


def c_patch_vs_disk():
    patch, disk = patch_primary(), disk_primary()
    if not patch:
        return 'tools/android-patch.py: PRIMARY_APPS replacement has no entries'
    if patch != disk:
        return 'web/main.js PRIMARY_APPS %s != tools/android-patch.py %s' % (disk, patch)


def c_primary_vs_ids():
    ids = dict(device_ids())
    bad = []
    for brand, _name, pkg in patch_primary():
        if brand not in ids:
            bad.append('%s: default app (tools/android-patch.py) missing from web/device.js IDS' % brand)
        elif ids[brand] != pkg:
            bad.append('%s: tools/android-patch.py appId %s != web/device.js IDS %s' % (brand, pkg, ids[brand]))
    return '; '.join(bad)


def c_readme():
    readme = ROOT / 'README.md'
    m = re.search(r'Default Home apps: ([^.\n]+)\.', text(readme)) if readme.is_file() else None
    if not m:
        return 'README.md has no "Default Home apps: ..." line to compare with PRIMARY_APPS'
    listed = [s.strip() for s in m.group(1).split(',')]
    want = [name for _b, name, _p in patch_primary()]
    if listed != want:
        return 'README.md "Default Home apps" %s != PRIMARY_APPS %s' % (listed, want)


def notices():
    p = ROOT / 'THIRD_PARTY_NOTICES.md'
    return text(p) if p.is_file() else None


def c_notices_files():
    src = notices()
    if src is None:
        return Skip('no THIRD_PARTY_NOTICES.md in this tree')
    # The list is in the CleanFrame (MIT) section: everything else in web/ is upstream, (c) Nathan Clark.
    sec = src.split('\n## CleanFrame (MIT)\n', 1)[1].split('\n## ', 1)[0] if '\n## CleanFrame (MIT)\n' in src else ''
    added = [para for para in sec.split('\n\n') if 'were added by this repository' in para]
    if len(added) != 1:
        return 'THIRD_PARTY_NOTICES.md "## CleanFrame (MIT)": want one paragraph saying "were added by this repository", found %d' % len(added)
    missing = []
    for rel in sorted(patch_module().OURS):
        if rel.startswith('assets/'):
            continue   # marks: c_notices_marks; wallpapers: their own sections
        want = 'web/LICENSES/' if rel.startswith('LICENSES/') else 'web/' + rel
        if '`%s`' % want not in added[0]:
            missing.append(want)
    if missing:
        return 'not named as added by this repository (they read as upstream CleanFrame): %s' % ', '.join(missing)


def c_notices_marks():
    src = notices()
    if src is None:
        return Skip('no THIRD_PARTY_NOTICES.md in this tree')
    names = dict(re.findall(r"\{ id: '([a-z0-9]+)', name: '([^']+)'", text(WEB / 'app-marks.js')))
    lic = {e['icon']: e.get('licence', '') for e in json.loads(text(ICONS / 'ADDITIONAL-SOURCES.json'))}
    rows = [l for l in src.splitlines() if l.startswith('| ')]
    cc0 = [l for l in rows if 'Simple Icons, CC0' in l]
    ours = [l for l in rows if 'drawn for this repository' in l and "This repository's MIT licence" in l]
    if not cc0 or len(ours) != 1:
        return 'THIRD_PARTY_NOTICES.md Brand icons: want Simple Icons CC0 row(s) and one "drawn for this repository" row'
    marks = sorted(r[len('assets/icons/'):-len('.svg')] for r in patch_module().OURS
                   if r.startswith('assets/icons/') and r.endswith('.svg'))
    if len(marks) < 3:
        return 'only %d marks in OURS (parse?)' % len(marks)
    bad = []
    for m in marks:
        name, l = names.get(m), lic.get(m, '')
        if not name:
            bad.append('%s: not in web/app-marks.js' % m); continue
        if l == 'CC0-1.0': where = cc0
        elif l.startswith('MIT (cleanframe-tv'): where = ours
        else:
            bad.append('%s: licence %r in ADDITIONAL-SOURCES.json' % (m, l)); continue
        if not any(re.search(r'(^|[|:,] )%s( \||,)' % re.escape(name), row) for row in where):
            bad.append('%s (%s, %s)' % (name, m, l))
    if bad:
        return 'marks in OURS not listed under Brand icons in their licence row: %s' % '; '.join(bad)


start = time.time()
case('device.js IDS parses, brands and packages unique', c_ids_parse)
case('every device.js brand has web/assets/icons/<brand>.svg', c_marks)
case('every device.js brand is measured in FrameIconMetrics', c_metrics)
case('PRIMARY_APPS in web/main.js = as tools/android-patch.py writes it', c_patch_vs_disk)
case('every default app has the same package in device.js IDS', c_primary_vs_ids)
case('README.md default-dock list = PRIMARY_APPS names, in order', c_readme)
case('THIRD_PARTY_NOTICES.md names every non-asset file in OURS as added by this repository', c_notices_files)
case('THIRD_PARTY_NOTICES.md lists every mark in OURS under its licence', c_notices_marks)
print('%s (%.2f s)' % ('BRANDS_TEST_FAIL %d' % len(FAILS) if FAILS else 'BRANDS_TEST_OK', time.time() - start))
sys.exit(1 if FAILS else 0)

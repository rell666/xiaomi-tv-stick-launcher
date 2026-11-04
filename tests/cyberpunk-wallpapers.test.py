#!/usr/bin/env python3
"""Offline test for the "Cyberpunk" wallpaper group (docs/RULES.md rule 1: these files are ours, in OURS).
Usage (from anywhere): python3 -I tests/cyberpunk-wallpapers.test.py   (stdlib + node, no Pillow)
Reads, never writes: web/assets/wallpapers/{catalog.js,space-catalog.js,cyberpunk-catalog.js,
SOURCES.json,CYBERPUNK-SOURCES.json}, web/assets/wallpapers/cyberpunk-*, web/assets/materials/cyberpunk-*,
web/assets/CYBERPUNK-PHOTO-CREDIT.md, web/LICENSES/README.txt, THIRD_PARTY_NOTICES.md, web/index.html
and tools/android-patch.py (OURS, CYBERPUNK_WALLPAPERS).
  - cyberpunk-catalog.js, evaluated after catalog.js and space-catalog.js as index.html loads them,
    appends exactly 15 entries with "group": "cyberpunk"; upstream's and Space's entries stay first;
  - index.html loads cyberpunk-catalog.js after space-catalog.js and before wallpaper-groups.js;
  - ids are unique, `cyberpunk-<word>`, clash with no other entry, and equal CYBERPUNK_WALLPAPERS;
  - each entry has every field all upstream entries have, with values main.js/style.css accept,
    and src/thumb at the paths main.js builds from the id;
  - every derived file exists with upstream's dimensions (JPEG SOF parsed with the stdlib), within
    the size budget, and there are no stray cyberpunk-* files;
  - every id has one CYBERPUNK-SOURCES.json record (SOURCES.json's fields + licence) from a
    Wikimedia Commons file page, with an allowed licence (CC0, public domain, CC BY 2.0/3.0/4.0,
    CC BY-SA 2.0/3.0/4.0; never NC or ND) whose URL matches it, a named credit, an upload.wikimedia.org
    original with its size and sha256, and the download date;
  - a row in CYBERPUNK-PHOTO-CREDIT.md per id (name, credit, licence, source page), and the notices
    point at it;
  - every file is listed in OURS.
Prints one PASS/FAIL line per case, then CYBERPUNK_WALLPAPERS_TEST_OK, or
CYBERPUNK_WALLPAPERS_TEST_FAIL <n> with exit 1."""
import importlib.util, json, pathlib, re, struct, subprocess, sys, time
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parent.parent
WEB = ROOT / 'web'
WP = WEB / 'assets' / 'wallpapers'
MAT = WEB / 'assets' / 'materials'
GROUP, COUNT = 'cyberpunk', 15
# Allowed licences (docs/RULES.md rule 1) and the deed each must link to.
LICENCES = {
    'CC0-1.0': 'https://creativecommons.org/publicdomain/zero/1.0/',
    'public-domain': None,
    'CC-BY-2.0': 'https://creativecommons.org/licenses/by/2.0/',
    'CC-BY-3.0': 'https://creativecommons.org/licenses/by/3.0/',
    'CC-BY-4.0': 'https://creativecommons.org/licenses/by/4.0/',
    'CC-BY-SA-2.0': 'https://creativecommons.org/licenses/by-sa/2.0/',
    'CC-BY-SA-3.0': 'https://creativecommons.org/licenses/by-sa/3.0/',
    'CC-BY-SA-4.0': 'https://creativecommons.org/licenses/by-sa/4.0/',
}
LABELS = {'CC0-1.0': 'CC0 1.0', 'public-domain': 'Public domain', 'CC-BY-2.0': 'CC BY 2.0', 'CC-BY-3.0': 'CC BY 3.0',
          'CC-BY-4.0': 'CC BY 4.0', 'CC-BY-SA-2.0': 'CC BY-SA 2.0', 'CC-BY-SA-3.0': 'CC BY-SA 3.0',
          'CC-BY-SA-4.0': 'CC BY-SA 4.0'}
# Upstream's geometry: background 1920x1280 (the original nine's 3:2), thumbnail 512x341, panel
# 320x180, materials the centred 1920x1080 crop (docs/assets.md at the pinned SHA).
FILES = {'wallpapers/%s.jpg': (1920, 1280), 'wallpapers/%s-thumb.jpg': (512, 341),
         'wallpapers/%s-panel.jpg': (320, 180), 'materials/%s-field-blur.jpg': (1920, 1080),
         'materials/%s-field-clear.jpg': (1920, 1080), 'materials/%s-field-frost.jpg': (1920, 1080),
         'materials/%s-study-soft.jpg': (1920, 1080)}
PER_WALLPAPER_MAX, TOTAL_MAX = 1600000, 25000000
CATALOGUES = ['catalog.js', 'space-catalog.js', 'cyberpunk-catalog.js']
FAILS = []

LOAD = r"""
const fs = require('fs'), vm = require('vm');
const ctx = { window: {} }; vm.createContext(ctx);
for (const f of process.argv.slice(1)) vm.runInContext(fs.readFileSync(f, 'utf8'), ctx, { filename: f });
process.stdout.write(JSON.stringify(ctx.window.CleanFrameWallpapers));
"""


def walls():
    """(upstream, cyberpunk, every) of window.CleanFrameWallpapers, loaded in index.html's order."""
    r = subprocess.run(['node', '-e', LOAD] + [str(WP / c) for c in CATALOGUES], capture_output=True, text=True, timeout=60)
    if r.returncode:
        raise ValueError('node could not load the catalogues: %s' % r.stderr.strip()[-300:])
    every = json.loads(r.stdout)
    return [w for w in every if 'group' not in w], [w for w in every if w.get('group') == GROUP], every


def patch_module():
    spec = importlib.util.spec_from_file_location('android_patch', ROOT / 'tools' / 'android-patch.py')
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def jpeg_size(path):
    """(width, height) from the first SOF marker, stdlib only."""
    b = path.read_bytes()
    if b[:2] != b'\xff\xd8':
        raise ValueError('%s: not a JPEG' % path.name)
    i = 2
    while i + 4 <= len(b):
        if b[i] != 0xFF:
            raise ValueError('%s: bad marker at %d' % (path.name, i))
        m = b[i + 1]
        if m in (0xD8, 0x01) or 0xD0 <= m <= 0xD7:
            i += 2
            continue
        n = struct.unpack('>H', b[i + 2:i + 4])[0]
        if m in (0xC0, 0xC1, 0xC2, 0xC3):
            h, w = struct.unpack('>HH', b[i + 5:i + 9])
            return w, h
        i += 2 + n
    raise ValueError('%s: no SOF marker' % path.name)


def sources():
    return json.loads((WP / 'CYBERPUNK-SOURCES.json').read_text(encoding='utf-8'))


def case(name, fn):
    try:
        problem = fn()
    except Exception as e:  # a crash in the case itself is a failure, never a pass
        problem = 'crashed: %s: %s' % (type(e).__name__, e)
    if problem:
        FAILS.append(name)
        print('FAIL %s -- %s' % (name, problem))
    else:
        print('PASS %s' % name)


def c_count():
    up, cp, every = walls()
    if len(cp) != COUNT:
        return '%d entries with group "%s", want %d' % (len(cp), GROUP, COUNT)
    if len(up) != 15:
        return '%d upstream entries, want 15 (a catalogue replaced or dropped some?)' % len(up)
    if [w['id'] for w in every[:len(up)]] != [w['id'] for w in up]:
        return 'upstream entries are not first'
    if [w['id'] for w in every[-COUNT:]] != [w['id'] for w in cp]:
        return 'cyberpunk entries are not appended last (after Space)'
    other = sorted({w.get('group') for w in every if 'group' in w} - {'space', GROUP})
    if other:
        return 'entries with an unknown group: %s' % other


def c_index():
    html = (WEB / 'index.html').read_text(encoding='utf-8')
    order = ['assets/wallpapers/catalog.js', 'assets/wallpapers/space-catalog.js',
             'assets/wallpapers/cyberpunk-catalog.js', 'wallpaper-groups.js']
    at = [html.find('<script src="%s"></script>' % s) for s in order]
    if min(at) < 0 or at != sorted(at):
        return 'index.html script order %s for %s' % (at, order)


def c_ids():
    _, cp, every = walls()
    ids = [w.get('id') for w in cp]
    dup = sorted({i for i in ids if ids.count(i) > 1})
    if dup:
        return 'duplicate ids: %s' % dup
    bad = [i for i in ids if not isinstance(i, str) or not re.fullmatch(r'cyberpunk-[a-z]+', i)]
    if bad:
        return 'ids not cyberpunk-<lowercase word>: %s' % bad
    clash = sorted(set(ids) & {w['id'] for w in every if w.get('group') != GROUP})
    if clash:
        return 'ids clash with other entries: %s' % clash
    want = ['cyberpunk-' + n for n in patch_module().CYBERPUNK_WALLPAPERS]
    if ids != want:
        return 'catalogue ids %s != CYBERPUNK_WALLPAPERS %s' % (ids, want)


def c_fields():
    up, cp, _ = walls()
    common = set.intersection(*(set(w) for w in up))
    problems = []
    for w in cp:
        i = w.get('id')
        missing = sorted(common - set(w))
        if missing:
            problems.append('%s lacks %s' % (i, missing))
            continue
        checks = [
            (isinstance(w['name'], str) and w['name'].strip() != '', 'name'),
            (w['src'] == 'assets/wallpapers/%s.jpg' % i, 'src'),
            (w['thumb'] == 'assets/wallpapers/%s-thumb.jpg' % i, 'thumb'),
            (w['tone'] in ('light', 'dark'), 'tone'),
            (isinstance(w['ut'], dict) and set(w['ut']) == {'tr', 'br'}
             and all(v in ('light', 'mist', 'dark') for v in w['ut'].values()), 'ut'),
            (w['frostVeil'] in (.12, .20, .30, .36, .44), 'frostVeil (main.js frostClass levels)'),
            (w['listColumn'] in (0, 2, 3), 'listColumn (style.css has lc-2, lc-3)'),
            (isinstance(w['dockQuiet'], bool), 'dockQuiet'),
            (isinstance(w['clockMist'], bool), 'clockMist'),
        ]
        problems += ['%s: bad %s' % (i, what) for ok, what in checks if not ok]
    names = [w.get('name') for w in cp]
    if len(set(names)) != len(names):
        problems.append('duplicate names')
    return '; '.join(problems)


def c_files():
    _, cp, _ = walls()
    problems, total = [], 0
    for w in cp:
        size = 0
        for pattern, want in FILES.items():
            p = WEB / 'assets' / (pattern % w['id'])
            if not p.is_file():
                problems.append('missing %s' % p.relative_to(ROOT))
                continue
            got = jpeg_size(p)
            if got != want:
                problems.append('%s is %dx%d, want %dx%d' % ((p.relative_to(ROOT),) + got + want))
            size += p.stat().st_size
        if size > PER_WALLPAPER_MAX:
            problems.append('%s: %d bytes > %d' % (w['id'], size, PER_WALLPAPER_MAX))
        total += size
    if total > TOTAL_MAX:
        problems.append('total %d bytes > %d' % (total, TOTAL_MAX))
    return '; '.join(problems)


def c_no_strays():
    _, cp, _ = walls()
    expected = {(WEB / 'assets' / (p % w['id'])) for w in cp for p in FILES}
    found = set(WP.glob('cyberpunk-*.jpg')) | set(MAT.glob('cyberpunk-*'))
    stray = sorted(str(p.relative_to(ROOT)) for p in found - expected)
    if stray:
        return 'cyberpunk-* files no catalogue entry uses: %s' % stray


def c_sources():
    _, cp, _ = walls()
    recs = sources()
    upstream_fields = set.intersection(*(set(r) for r in json.loads((WP / 'SOURCES.json').read_text(encoding='utf-8'))))
    need = upstream_fields | {'licence', 'licenceUrl', 'licenceEvidence', 'original', 'originalPx', 'originalSha256', 'downloaded', 'subject'}
    by_id, problems = {}, []
    for r in recs:
        if r.get('id') in by_id:
            problems.append('duplicate source record %s' % r.get('id'))
        by_id[r.get('id')] = r
    if set(by_id) != {w['id'] for w in cp}:
        problems.append('source ids %s != catalogue ids' % sorted(set(by_id) ^ {w['id'] for w in cp}))
    for w in cp:
        r = by_id.get(w['id'])
        if r is None:
            continue
        missing = sorted(need - set(r))
        if missing:
            problems.append('%s record lacks %s' % (w['id'], missing))
            continue
        lic = r['licence']
        if lic not in LICENCES or re.search(r'(?i)\bNC\b|\bND\b|-NC|-ND', lic):
            problems.append('%s: licence %r not in %s' % (w['id'], lic, sorted(LICENCES)))
            continue
        if LICENCES[lic] and r['licenceUrl'] != LICENCES[lic]:
            problems.append('%s: licenceUrl %s is not the %s deed' % (w['id'], r['licenceUrl'], lic))
        src, orig = urlparse(r['source']), urlparse(r['original'])
        if src.scheme != 'https' or src.hostname != 'commons.wikimedia.org' or not src.path.startswith('/wiki/File:'):
            problems.append('%s: source %s is not a Wikimedia Commons file page' % (w['id'], r['source']))
        if orig.scheme != 'https' or orig.hostname != 'upload.wikimedia.org' or orig.query:
            problems.append('%s: original %s is not an upload.wikimedia.org file URL' % (w['id'], r['original']))
        if not str(r['credit']).strip():
            problems.append('%s: empty credit' % w['id'])
        if not re.fullmatch(r'[0-9a-f]{64}', str(r['originalSha256'])):
            problems.append('%s: originalSha256 is not a sha256' % w['id'])
        px = r['originalPx']
        if not (isinstance(px, list) and len(px) == 2 and all(isinstance(v, int) for v in px)
                and min(px[0], round(px[1] * 1.5)) >= 1920 and min(px[1], round(px[0] / 1.5)) >= 1280):
            problems.append('%s: original %s is too small for a 1920x1280 crop' % (w['id'], px))
        if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', str(r['downloaded'])):
            problems.append('%s: downloaded is not a date' % w['id'])
        for k in ('name', 'src', 'thumb', 'tone'):
            if r[k] != w[k]:
                problems.append('%s: CYBERPUNK-SOURCES %s %r != catalogue %r' % (w['id'], k, r[k], w[k]))
    return '; '.join(problems)


def c_credit_table():
    _, cp, _ = walls()
    table = (WEB / 'assets' / 'CYBERPUNK-PHOTO-CREDIT.md').read_text(encoding='utf-8')
    rows = {m.group(1): m.group(0) for m in re.finditer(r'^\|[^|\n]*\| `([a-z-]+)` \|.*$', table, re.M)}
    problems = []
    for r in sources():
        row = rows.get(r['id'])
        if row is None:
            problems.append('no row for %s' % r['id'])
        elif any(x not in row for x in (r['credit'], r['source'], r['name'], LABELS.get(r['licence'], '?'))):
            problems.append('row for %s lacks its name, credit, licence or source page' % r['id'])
    if set(rows) - {w['id'] for w in cp}:
        problems.append('rows for unknown ids %s' % sorted(set(rows) - {w['id'] for w in cp}))
    for f in (WEB / 'LICENSES' / 'README.txt', ROOT / 'THIRD_PARTY_NOTICES.md'):
        if 'CYBERPUNK-PHOTO-CREDIT.md' not in f.read_text(encoding='utf-8'):
            problems.append('%s does not point at CYBERPUNK-PHOTO-CREDIT.md' % f.relative_to(ROOT))
    return '; '.join(problems)


def c_ours():
    _, cp, _ = walls()
    ours = patch_module().OURS
    want = ['assets/wallpapers/cyberpunk-catalog.js', 'assets/wallpapers/CYBERPUNK-SOURCES.json', 'assets/CYBERPUNK-PHOTO-CREDIT.md']
    want += ['assets/' + (p % w['id']) for w in cp for p in FILES]
    missing = [p for p in want if p not in ours or not str(ours[p]).strip()]
    if missing:
        return 'not in OURS (with a reason): %s' % missing[:8]


start = time.time()
case('cyberpunk-catalog.js appends exactly %d "cyberpunk" entries after catalog.js and space-catalog.js' % COUNT, c_count)
case('index.html loads cyberpunk-catalog.js after space-catalog.js, before wallpaper-groups.js', c_index)
case('cyberpunk ids unique, cyberpunk-<word>, no clash, = CYBERPUNK_WALLPAPERS', c_ids)
case('every cyberpunk entry has every upstream field with a valid value', c_fields)
case('every derived file exists with upstream dimensions, within the size budget', c_files)
case('no stray cyberpunk-* files', c_no_strays)
case('every id has a Commons source record with an allowed licence, credit and original', c_sources)
case('credit table row per id; LICENSES/README.txt and THIRD_PARTY_NOTICES.md point at it', c_credit_table)
case('every cyberpunk file is listed in OURS', c_ours)
print('%s (%.2f s)' % ('CYBERPUNK_WALLPAPERS_TEST_FAIL %d' % len(FAILS) if FAILS else 'CYBERPUNK_WALLPAPERS_TEST_OK', time.time() - start))
sys.exit(1 if FAILS else 0)

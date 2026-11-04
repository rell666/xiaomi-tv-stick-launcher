#!/usr/bin/env python3
"""Offline test for the "Space" wallpaper group (docs/RULES.md rule 1: these files are ours, in OURS).
Usage (from anywhere): python3 -I tests/space-wallpapers.test.py   (stdlib + node, no Pillow)
Reads, never writes: web/assets/wallpapers/{catalog.js,space-catalog.js,SOURCES.json,
SPACE-SOURCES.json}, web/assets/wallpapers/space-*, web/assets/materials/space-*,
web/assets/SPACE-PHOTO-CREDIT.md, web/LICENSES/README.txt, THIRD_PARTY_NOTICES.md and
tools/android-patch.py (OURS, SPACE_WALLPAPERS).
  - space-catalog.js, evaluated after catalog.js as index.html loads them, appends exactly 15
    entries with "group": "space"; upstream's entries keep no group and stay first;
  - ids are unique, `space-<word>`, do not clash with upstream's, and equal SPACE_WALLPAPERS;
  - each entry has every field all upstream entries have, with values main.js/style.css accept,
    and src/thumb at the paths main.js builds from the id;
  - every derived file exists with upstream's dimensions (JPEG SOF parsed with the stdlib), within
    the size budget, and there are no stray space-* files;
  - every id has one SPACE-SOURCES.json record (SOURCES.json's fields + licence) with an allowed
    licence and a matching host, a row in SPACE-PHOTO-CREDIT.md, and the notices point at it;
  - every file is listed in OURS.
Prints one PASS/FAIL line per case, then SPACE_WALLPAPERS_TEST_OK, or SPACE_WALLPAPERS_TEST_FAIL <n>
with exit 1."""
import importlib.util, json, pathlib, re, struct, subprocess, sys, time
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parent.parent
WEB = ROOT / 'web'
WP = WEB / 'assets' / 'wallpapers'
MAT = WEB / 'assets' / 'materials'
COUNT = 15
LICENCES = {'CC-BY-4.0': ('esawebb.org', 'esahubble.org'), 'public-domain-NASA': ('images.nasa.gov',)}
# Upstream's geometry: background 1920x1280 (the original nine's 3:2), thumbnail 512x341, panel
# 320x180, materials the centred 1920x1080 crop (docs/assets.md at the pinned SHA).
FILES = {'wallpapers/%s.jpg': (1920, 1280), 'wallpapers/%s-thumb.jpg': (512, 341),
         'wallpapers/%s-panel.jpg': (320, 180), 'materials/%s-field-blur.jpg': (1920, 1080),
         'materials/%s-field-clear.jpg': (1920, 1080), 'materials/%s-field-frost.jpg': (1920, 1080),
         'materials/%s-study-soft.jpg': (1920, 1080)}
PER_WALLPAPER_MAX, TOTAL_MAX = 1600000, 25000000
FAILS = []

LOAD = r"""
const fs = require('fs'), vm = require('vm');
const ctx = { window: {} }; vm.createContext(ctx);
for (const f of process.argv.slice(1)) vm.runInContext(fs.readFileSync(f, 'utf8'), ctx, { filename: f });
process.stdout.write(JSON.stringify(ctx.window.CleanFrameWallpapers));
"""


def walls():
    """window.CleanFrameWallpapers after catalog.js then space-catalog.js, as index.html loads them."""
    r = subprocess.run(['node', '-e', LOAD, str(WP / 'catalog.js'), str(WP / 'space-catalog.js')],
                       capture_output=True, text=True, timeout=60)
    if r.returncode:
        raise ValueError('node could not load the catalogues: %s' % r.stderr.strip()[-300:])
    every = json.loads(r.stdout)
    return [w for w in every if 'group' not in w], [w for w in every if w.get('group') == 'space'], every


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
    return json.loads((WP / 'SPACE-SOURCES.json').read_text(encoding='utf-8'))


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
    up, space, every = walls()
    if len(space) != COUNT:
        return '%d entries with group "space", want %d' % (len(space), COUNT)
    other = [w.get('id') for w in every if 'group' in w and w.get('group') != 'space']
    if other:
        return 'entries with a group other than "space": %s' % other
    if [w['id'] for w in every[:len(up)]] != [w['id'] for w in up]:
        return 'upstream entries are not first (space-catalog.js must append)'
    if len(up) != 15:
        return '%d upstream entries, want 15 (space-catalog.js replaced or dropped some?)' % len(up)


def c_ids():
    up, space, _ = walls()
    ids = [w.get('id') for w in space]
    dup = sorted({i for i in ids if ids.count(i) > 1})
    if dup:
        return 'duplicate ids: %s' % dup
    bad = [i for i in ids if not isinstance(i, str) or not re.fullmatch(r'space-[a-z]+', i)]
    if bad:
        return 'ids not space-<lowercase word>: %s' % bad
    clash = sorted(set(ids) & {w['id'] for w in up})
    if clash:
        return 'ids clash with upstream: %s' % clash
    want = ['space-' + n for n in patch_module().SPACE_WALLPAPERS]
    if ids != want:
        return 'catalogue ids %s != SPACE_WALLPAPERS %s' % (ids, want)


def c_fields():
    up, space, _ = walls()
    common = set.intersection(*(set(w) for w in up))
    problems = []
    for w in space:
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
    names = [w.get('name') for w in space]
    if len(set(names)) != len(names):
        problems.append('duplicate names')
    return '; '.join(problems)


def c_files():
    _, space, _ = walls()
    problems, total = [], 0
    for w in space:
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
    _, space, _ = walls()
    expected = {(WEB / 'assets' / (p % w['id'])) for w in space for p in FILES}
    found = set(WP.glob('space-*.jpg')) | set(MAT.glob('space-*'))
    stray = sorted(str(p.relative_to(ROOT)) for p in found - expected)
    if stray:
        return 'space-* files no catalogue entry uses: %s' % stray


def c_sources():
    _, space, _ = walls()
    recs = sources()
    upstream_fields = set.intersection(*(set(r) for r in json.loads((WP / 'SOURCES.json').read_text(encoding='utf-8'))))
    need = upstream_fields | {'licence', 'licenceUrl', 'original', 'downloaded'}
    by_id, problems = {}, []
    for r in recs:
        if r.get('id') in by_id:
            problems.append('duplicate source record %s' % r.get('id'))
        by_id[r.get('id')] = r
    if set(by_id) != {w['id'] for w in space}:
        problems.append('source ids %s != catalogue ids' % sorted(set(by_id) ^ {w['id'] for w in space}))
    for w in space:
        r = by_id.get(w['id'])
        if r is None:
            continue
        missing = sorted(need - set(r))
        if missing:
            problems.append('%s record lacks %s' % (w['id'], missing))
            continue
        lic = r['licence']
        if lic not in LICENCES:
            problems.append('%s: licence %r not in %s' % (w['id'], lic, sorted(LICENCES)))
            continue
        host = urlparse(r['source']).hostname or ''
        if urlparse(r['source']).scheme != 'https' or host not in LICENCES[lic]:
            problems.append('%s: source %s is not on %s' % (w['id'], r['source'], LICENCES[lic]))
        if urlparse(r['original']).scheme != 'https':
            problems.append('%s: original is not an https URL' % w['id'])
        if lic == 'public-domain-NASA' and r['credit'] != 'NASA':
            problems.append('%s: a NASA public-domain image must be credited to NASA alone' % w['id'])
        if not str(r['credit']).strip():
            problems.append('%s: empty credit' % w['id'])
        if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', str(r['downloaded'])):
            problems.append('%s: downloaded is not a date' % w['id'])
        for k in ('name', 'src', 'thumb', 'tone'):
            if r[k] != w[k]:
                problems.append('%s: SPACE-SOURCES %s %r != catalogue %r' % (w['id'], k, r[k], w[k]))
    return '; '.join(problems)


def c_credit_table():
    _, space, _ = walls()
    table = (WEB / 'assets' / 'SPACE-PHOTO-CREDIT.md').read_text(encoding='utf-8')
    rows = {m.group(1): m.group(0) for m in re.finditer(r'^\|[^|\n]*\| `([a-z-]+)` \|.*$', table, re.M)}
    problems = []
    for r in sources():
        row = rows.get(r['id'])
        if row is None:
            problems.append('no row for %s' % r['id'])
        elif r['credit'] not in row or r['source'] not in row or r['name'] not in row:
            problems.append('row for %s lacks its name, credit or source page' % r['id'])
    if set(rows) - {w['id'] for w in space}:
        problems.append('rows for unknown ids %s' % sorted(set(rows) - {w['id'] for w in space}))
    for f in (WEB / 'LICENSES' / 'README.txt', ROOT / 'THIRD_PARTY_NOTICES.md'):
        if 'SPACE-PHOTO-CREDIT.md' not in f.read_text(encoding='utf-8'):
            problems.append('%s does not point at SPACE-PHOTO-CREDIT.md' % f.relative_to(ROOT))
    return '; '.join(problems)


def c_ours():
    _, space, _ = walls()
    ours = patch_module().OURS
    want = ['assets/wallpapers/space-catalog.js', 'assets/wallpapers/SPACE-SOURCES.json', 'assets/SPACE-PHOTO-CREDIT.md']
    want += ['assets/' + (p % w['id']) for w in space for p in FILES]
    missing = [p for p in want if p not in ours or not str(ours[p]).strip()]
    if missing:
        return 'not in OURS (with a reason): %s' % missing[:8]


start = time.time()
case('space-catalog.js appends exactly %d "space" entries after upstream catalog.js' % COUNT, c_count)
case('space ids unique, space-<word>, no clash with upstream, = SPACE_WALLPAPERS', c_ids)
case('every space entry has every upstream field with a valid value', c_fields)
case('every derived file exists with upstream dimensions, within the size budget', c_files)
case('no stray space-* files', c_no_strays)
case('every id has a source record with an allowed licence', c_sources)
case('credit table row per id; LICENSES/README.txt and THIRD_PARTY_NOTICES.md point at it', c_credit_table)
case('every space file is listed in OURS', c_ours)
print('%s (%.2f s)' % ('SPACE_WALLPAPERS_TEST_FAIL %d' % len(FAILS) if FAILS else 'SPACE_WALLPAPERS_TEST_OK', time.time() - start))
sys.exit(1 if FAILS else 0)

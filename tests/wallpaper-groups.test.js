/* Unit tests for web/wallpaper-groups.js (window.CleanFrameWallpaperGroups): grouping the gallery,
   rotation within a group, the fallback for a saved id that is no longer shipped, dropping bad
   or duplicate catalogue entries, merging the stick's photo library (docs/PHOTO-GROUPS.md contract
   3), the Background page's group list and the one image path helper. Also checks the real
   catalogue (web/assets/wallpapers/catalog.js, plus space-catalog.js and cyberpunk-catalog.js when
   they are in web/), builds
   the merged catalogue from the shared fixture tests/fixtures/user-library/R (read with node fs by
   the contract 1 rules) against its expected.json, and that the patched web/main.js builds every
   image path through the helper. Rotation is random within the group (WG.random, and the patched
   main.js's start-up, timer and Rotation choice, replayed from main.js), and while it rotates the
   tick is on Background's Rotation row and the rotating group, not on a photo.
   No dependencies: node:assert + node:vm and a tiny runner. Run from the repo root:
     node tests/wallpaper-groups.test.js
   Prints one line per case and exits non-zero on any failure. */
'use strict';
var assert = require('node:assert');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var WEB = path.join(__dirname, '..', 'web');
var SOURCE_PATH = process.env.WALLPAPER_GROUPS_JS || path.join(WEB, 'wallpaper-groups.js');
var SOURCE = fs.readFileSync(SOURCE_PATH, 'utf8');

function load(extra) {
  var ctx = { window: {} };
  vm.createContext(ctx);
  (extra || []).forEach(function (src) { vm.runInContext(src, ctx); });
  vm.runInContext(SOURCE, ctx, { filename: 'wallpaper-groups.js' });
  return ctx.window;
}
function lib() { return load().CleanFrameWallpaperGroups; }
function W(id, group) { var w = { id: id, name: id.toUpperCase() }; if (group !== undefined) w.group = group; return w; }
function ids(list) { return plain(list.map(function (w) { return w.id; })); }
// JSON round trip: arrays made inside the vm context have another Array.prototype.
function plain(x) { return JSON.parse(JSON.stringify(x)); }

var MIXED = [W('a'), W('b'), W('s1', 'space'), W('c'), W('s2', 'space'), W('s3', 'space')];

var cases = [];
function test(name, fn) { cases.push([name, fn]); }

test('entries without group are scenery; group "space" is space', function () {
  var G = lib();
  assert.strictEqual(G.groupOf(W('a')), 'scenery');
  assert.strictEqual(G.groupOf(W('s1', 'space')), 'space');
  assert.strictEqual(G.groupOf(W('x', '')), 'scenery');
  assert.strictEqual(G.groupOf(W('x', 7)), 'scenery');
  assert.strictEqual(G.groupOf(W('x', 'Bad Group!')), 'scenery');
  assert.strictEqual(G.groupOf(null), 'scenery');
});

test('groups(): Scenery then Space, labelled, only groups that have entries', function () {
  var G = lib();
  assert.deepStrictEqual(plain(G.groups(MIXED)), [{ id: 'scenery', label: 'Scenery' }, { id: 'space', label: 'Space' }]);
  assert.deepStrictEqual(plain(G.groups([W('a'), W('b')])), [{ id: 'scenery', label: 'Scenery' }]);
  // Order is fixed, not catalogue order: a space entry first still lists Scenery first.
  assert.deepStrictEqual(plain(G.groups([W('s1', 'space'), W('a')])).map(function (g) { return g.id; }), ['scenery', 'space']);
  assert.deepStrictEqual(plain(G.groups([W('s1', 'space')])), [{ id: 'space', label: 'Space' }]);
});

test('groups(): Cyberpunk is the third built-in, after Space and before any other group, whatever the catalogue order', function () {
  var G = lib();
  assert.strictEqual(G.groupOf(W('c1', 'cyberpunk')), 'cyberpunk');
  assert.strictEqual(G.label('cyberpunk'), 'Cyberpunk');
  assert.deepStrictEqual(plain(G.groups([W('z1', 'zoo'), W('c1', 'cyberpunk'), W('s1', 'space'), W('a')])),
    [{ id: 'scenery', label: 'Scenery' }, { id: 'space', label: 'Space' }, { id: 'cyberpunk', label: 'Cyberpunk' }, { id: 'zoo', label: 'Zoo' }]);
  var walls = [W('a'), W('c1', 'cyberpunk'), W('s1', 'space'), W('c2', 'cyberpunk')];
  assert.strictEqual(G.next(walls, 'c2'), 'c1', 'rotation wraps within Cyberpunk');
  assert.strictEqual(G.next(walls, 'c1'), 'c2', 'and never into Space');
});

test('groups(): an unknown group follows the known ones, in catalogue order, with a readable label', function () {
  var G = lib();
  var got = plain(G.groups([W('z1', 'zoo'), W('a'), W('o1', 'ocean-life'), W('s1', 'space'), W('z2', 'zoo')]));
  assert.deepStrictEqual(got, [{ id: 'scenery', label: 'Scenery' }, { id: 'space', label: 'Space' },
    { id: 'zoo', label: 'Zoo' }, { id: 'ocean-life', label: 'Ocean life' }]);
});

test('members(): one group, catalogue order', function () {
  var G = lib();
  assert.deepStrictEqual(ids(G.members(MIXED, 'scenery')), ['a', 'b', 'c']);
  assert.deepStrictEqual(ids(G.members(MIXED, 'space')), ['s1', 's2', 's3']);
  assert.deepStrictEqual(ids(G.members(MIXED, 'nope')), []);
});

test('next(): rotation stays inside the wallpaper\'s group and wraps', function () {
  var G = lib();
  assert.strictEqual(G.next(MIXED, 'a'), 'b');
  assert.strictEqual(G.next(MIXED, 'b'), 'c');      // skips s1, which sits between them
  assert.strictEqual(G.next(MIXED, 'c'), 'a');      // wraps within Scenery, never into Space
  assert.strictEqual(G.next(MIXED, 's1'), 's2');
  assert.strictEqual(G.next(MIXED, 's3'), 's1');    // wraps within Space
});

test('next(): one group behaves exactly like upstream (i + 1) % length', function () {
  var G = lib(), walls = [W('a'), W('b'), W('c')], i;
  for (i = 0; i < walls.length; i++) assert.strictEqual(G.next(walls, walls[i].id), walls[(i + 1) % walls.length].id);
  assert.strictEqual(G.next([W('only')], 'only'), 'only');
  assert.strictEqual(G.next([W('s', 'space'), W('a')], 's'), 's');   // a group of one stays put
});

test('next(): an id not in the catalogue goes to the first wallpaper', function () {
  var G = lib();
  assert.strictEqual(G.next(MIXED, 'gone'), 'a');
  assert.strictEqual(G.next(MIXED, undefined), 'a');
});

test('resolve(): a saved id still shipped is kept; a missing one falls back to the first Scenery entry', function () {
  var G = lib();
  assert.strictEqual(G.resolve(MIXED, 's2'), 's2');
  assert.strictEqual(G.resolve(MIXED, 'c'), 'c');
  // A space wallpaper saved, then rolled back to a build without Space: falls back to Scenery.
  assert.strictEqual(G.resolve([W('a'), W('b')], 's2'), 'a');
  assert.strictEqual(G.resolve(MIXED, undefined), 'a');
  assert.strictEqual(G.resolve(MIXED, ''), 'a');
  assert.strictEqual(G.resolve(MIXED, { id: 's2' }), 'a');
  assert.strictEqual(G.resolve(MIXED, 'constructor'), 'a');
  // The first SCENERY wallpaper, not the first entry: a deleted photo or group never lands in Space.
  assert.strictEqual(G.resolve([W('s1', 'space'), W('a'), W('b')], 'u-gone-p0000000000'), 'a');
  // A stale entry (kept only while it is still on screen) counts as missing.
  var stale = [W('a'), W('x', 'gabcdefgh')]; stale[1].stale = true;
  assert.strictEqual(G.resolve(stale, 'x'), 'a');
  // No Scenery at all: the first entry.
  assert.strictEqual(G.resolve([W('s1', 'space'), W('s2', 'space')], 'gone'), 's1');
});

test('clean(): drops entries without a usable id or name, and later duplicates', function () {
  var G = lib();
  var dup = W('a', 'space');
  var got = G.clean([W('a'), null, 'x', { id: 'noname' }, { id: 'bad id', name: 'B' }, { name: 'no id' },
    W('s1', 'space'), dup, { id: 'constructor', name: 'ok' }, W('b')]);
  assert.deepStrictEqual(ids(got), ['a', 's1', 'constructor', 'b']);
  assert.strictEqual(got[0].group, undefined);   // the upstream entry wins over the duplicate
  assert.deepStrictEqual(plain(G.clean(undefined)), []);
  assert.deepStrictEqual(plain(G.clean({})), []);
  var keep = [W('a'), W('b')];
  var kept = G.clean(keep);   // valid catalogue unchanged, same objects
  assert.strictEqual(kept.length, 2);
  assert.ok(kept[0] === keep[0] && kept[1] === keep[1]);
});

/* ---------- The photo library (contract 3) ---------- */
function photo(group, id, extra) {
  var p = { group: group, id: id, name: id.toUpperCase(), added: 1, tone: 'light', ut: { tr: 'mist', br: 'dark' },
    frostVeil: 0.2, listColumn: 2, dockQuiet: true, clockMist: true };
  Object.keys(extra || {}).forEach(function (k) { p[k] = extra[k]; });
  return p;
}
var LIB = { v: 1, groups: [{ id: 'scenery', name: 'Scenery', builtin: true }, { id: 'space', name: 'Space', builtin: true },
  { id: 'gzzzzzzz2', name: 'Later', created: 20, builtin: false }, { id: 'gaaaaaaa1', name: 'Holiday', created: 10, builtin: false }],
  photos: [photo('gaaaaaaa1', 'p0000000001'), photo('space', 'p0000000002'), photo('gaaaaaaa1', 'p0000000003')] };
var FILE_NAMES = { wall: 'wall.jpg', thumb: 'thumb.jpg', panel: 'panel.jpg', blur: 'field-blur.jpg', clear: 'field-clear.jpg', frost: 'field-frost.jpg', soft: 'study-soft.jpg' };

test('merge(): library photos become u-<gid>-<pid> entries with group, meta and a files map; base untouched', function () {
  var G = lib(), base = [W('a'), W('s1', 'space')], before = JSON.stringify(base), got = G.merge(base, LIB), w;
  assert.strictEqual(JSON.stringify(base), before, 'base changed');
  // Library order: added, then group order (scenery, space, cyberpunk, user groups by created), then id.
  assert.deepStrictEqual(ids(got), ['a', 's1', 'u-space-p0000000002', 'u-gaaaaaaa1-p0000000001', 'u-gaaaaaaa1-p0000000003']);
  w = plain(got[3]);
  assert.deepStrictEqual(w, { id: 'u-gaaaaaaa1-p0000000001', pid: 'p0000000001', group: 'gaaaaaaa1', name: 'P0000000001', added: 1,
    tone: 'light', ut: { tr: 'mist', br: 'dark' }, frostVeil: 0.2, listColumn: 2, dockQuiet: true, clockMist: true,
    files: { wall: 'user/gaaaaaaa1/p0000000001/wall.jpg', thumb: 'user/gaaaaaaa1/p0000000001/thumb.jpg', panel: 'user/gaaaaaaa1/p0000000001/panel.jpg',
      blur: 'user/gaaaaaaa1/p0000000001/field-blur.jpg', clear: 'user/gaaaaaaa1/p0000000001/field-clear.jpg',
      frost: 'user/gaaaaaaa1/p0000000001/field-frost.jpg', soft: 'user/gaaaaaaa1/p0000000001/study-soft.jpg' } });
  assert.strictEqual(G.groupOf(got[2]), 'space', 'an upload to a built-in group joins it');
  assert.ok(G.isUser(got[2]) && !G.isUser(got[0]) && !G.isUser(null));
  assert.deepStrictEqual(ids(G.members(got, 'gaaaaaaa1')), ['u-gaaaaaaa1-p0000000001', 'u-gaaaaaaa1-p0000000003']);
  assert.deepStrictEqual(ids(G.merge(base, null)), ['a', 's1']);
  assert.deepStrictEqual(ids(G.merge(base, { groups: 'x', photos: 7 })), ['a', 's1']);
});

test('merge(): Adjust fields: src only when true, the crop when well-formed, edited versions every file path', function () {
  var G = lib(), got = G.merge([], { v: 1, groups: LIB.groups, photos: [
    photo('gaaaaaaa1', 'p0000000001', { src: true, crop: { x: 0.25, y: 0.5, z: 2 }, edited: 1761901000000 }),
    photo('gaaaaaaa1', 'p0000000003', { src: 'yes', crop: { x: 'a', y: 0.5, z: 2 }, edited: -1 }),
    photo('gaaaaaaa1', 'p0000000005', { crop: { x: 0.5, y: 0.5, z: 0 } })] });
  var a = plain(got[0]), b = plain(got[1]), c = plain(got[2]);
  assert.strictEqual(a.src, 'user/gaaaaaaa1/p0000000001/src.jpg?v=1761901000000');
  assert.deepStrictEqual(a.crop, { x: 0.25, y: 0.5, z: 2 });
  assert.strictEqual(a.edited, 1761901000000);
  Object.keys(FILE_NAMES).forEach(function (k) { assert.strictEqual(a.files[k], 'user/gaaaaaaa1/p0000000001/' + FILE_NAMES[k] + '?v=1761901000000', k); });
  assert.ok(!('src' in b) && !('crop' in b) && !('edited' in b), 'junk ignored: ' + JSON.stringify(b));
  assert.strictEqual(b.files.wall, 'user/gaaaaaaa1/p0000000003/wall.jpg', 'never edited: plain paths');
  assert.ok(!('crop' in c), 'zero zoom ignored');
});

test('merge(): bad ids, unknown or invalid groups, duplicates and junk are skipped; bad meta gets upstream defaults', function () {
  var G = lib(), lib2 = { groups: LIB.groups.concat([{ id: 'BADGROUP1', name: 'x', created: 1 }, { id: 'gbbbbbbb2', name: '   ', created: 1 }, null]),
    photos: [photo('gaaaaaaa1', 'BAD..ID'), photo('gaaaaaaa1', 'p00000000001x'), photo('gnotlisted', 'p0000000004'), photo('BADGROUP1', 'p0000000005'),
      photo('gbbbbbbb2', 'p0000000006'), photo('gaaaaaaa1', 'p0000000001'), photo('gaaaaaaa1', 'p0000000001'), null, 'x',
      photo('gaaaaaaa1', 'p0000000007', { name: ' ', tone: 'neon', ut: null, frostVeil: 9, listColumn: 7, dockQuiet: 'yes', clockMist: 1, added: 'x' })] };
  var got = G.merge([], lib2), w = plain(got[0]);
  assert.deepStrictEqual(ids(got), ['u-gaaaaaaa1-p0000000007', 'u-gaaaaaaa1-p0000000001'], 'a bad added counts as 0');
  assert.deepStrictEqual([w.name, w.tone, w.ut, w.frostVeil, w.listColumn, w.dockQuiet, w.clockMist, w.added],
    ['Photo', 'dark', { tr: 'light', br: 'light' }, 0.12, 0, false, false, 0]);
  // A user entry can never shadow a catalogue id: the catalogue entry wins.
  got = G.merge([W('u-gaaaaaaa1-p0000000001')], LIB);
  assert.deepStrictEqual(ids(got), ['u-gaaaaaaa1-p0000000001', 'u-space-p0000000002', 'u-gaaaaaaa1-p0000000003']);
  assert.ok(!G.isUser(got[0]));
});

test('merge(): photos sorted by added, then group order, then id, whatever order the bridge sent', function () {
  var G = lib(), lib2 = { groups: LIB.groups, photos: [
    photo('gzzzzzzz2', 'p0000000009', { added: 5 }), photo('gaaaaaaa1', 'p000000000b', { added: 5 }),
    photo('gaaaaaaa1', 'p000000000a', { added: 5 }), photo('space', 'p0000000008', { added: 5 }), photo('gzzzzzzz2', 'p0000000001', { added: 1 }),
    photo('cyberpunk', 'p0000000007', { added: 5 })] };
  assert.deepStrictEqual(ids(G.merge([], lib2)), ['u-gzzzzzzz2-p0000000001', 'u-space-p0000000008', 'u-cyberpunk-p0000000007',
    'u-gaaaaaaa1-p000000000a', 'u-gaaaaaaa1-p000000000b', 'u-gzzzzzzz2-p0000000009']);
  assert.deepStrictEqual(ids(G.members(G.merge([], lib2), 'gzzzzzzz2')), ['u-gzzzzzzz2-p0000000001', 'u-gzzzzzzz2-p0000000009'], 'a group page lists by added');
});

test('merge(): frostVeil 0-0.44 kept (upstream\u2019s range), anything else 0.12', function () {
  var G = lib();
  [[0, 0], [0.44, 0.44], [0.3, 0.3], [0.45, 0.12], [-0.1, 0.12], ['0.2', 0.12]].forEach(function (c) {
    assert.strictEqual(G.merge([], { groups: LIB.groups, photos: [photo('space', 'p0000000002', { frostVeil: c[0] })] })[0].frostVeil, c[1], String(c[0]));
  });
});

test('update(): changes the SAME array in place; a kept id that vanished stays, marked stale, then goes', function () {
  var G = lib(), base = [W('a'), W('b')], walls = G.merge(base, LIB), same = walls, gone = 'u-gaaaaaaa1-p0000000001';
  var lib2 = { groups: LIB.groups, photos: LIB.photos.slice(1) };
  assert.strictEqual(G.update(walls, base, lib2, [gone, undefined]), true);
  assert.ok(walls === same, 'not in place');
  assert.deepStrictEqual(ids(walls), ['a', 'b', 'u-space-p0000000002', 'u-gaaaaaaa1-p0000000003', gone]);
  assert.strictEqual(G.find(walls, gone).stale, true);
  assert.deepStrictEqual(ids(G.members(walls, 'gaaaaaaa1')), ['u-gaaaaaaa1-p0000000003'], 'a stale entry is listed nowhere');
  assert.strictEqual(G.next(walls, gone), 'u-gaaaaaaa1-p0000000003', 'rotation off a stale entry stays in its group');
  assert.strictEqual(G.update(walls, base, lib2, ['a']), true, 'dropped once no longer kept');
  assert.deepStrictEqual(ids(walls), ['a', 'b', 'u-space-p0000000002', 'u-gaaaaaaa1-p0000000003']);
  assert.strictEqual(G.update(walls, base, lib2, []), false, 'unchanged');
});

test('list(): Scenery, Space, Cyberpunk, then user groups by created (empty ones too), with counts and first thumbnails', function () {
  var G = lib(), walls = G.merge([W('a'), W('b'), W('s1', 'space')], LIB);
  assert.deepStrictEqual(plain(G.list(walls, LIB)), [
    { id: 'scenery', label: 'Scenery', count: 2, builtin: true, created: 0, thumb: 'assets/wallpapers/a-thumb.jpg' },
    { id: 'space', label: 'Space', count: 2, builtin: true, created: 0, thumb: 'assets/wallpapers/s1-thumb.jpg' },
    { id: 'cyberpunk', label: 'Cyberpunk', count: 0, builtin: true, created: 0, thumb: '' },
    { id: 'gaaaaaaa1', label: 'Holiday', count: 2, builtin: false, created: 10, thumb: 'user/gaaaaaaa1/p0000000001/thumb.jpg' },
    { id: 'gzzzzzzz2', label: 'Later', count: 0, builtin: false, created: 20, thumb: '' }]);
  // No library (web preview, old APK): the catalogue's groups, as before.
  assert.deepStrictEqual(plain(G.list([W('a')], null)).map(function (g) { return g.id; }), ['scenery']);
  assert.deepStrictEqual(plain(G.list(MIXED, null)).map(function (g) { return g.id + ':' + g.count; }), ['scenery:3', 'space:3']);
  assert.strictEqual(G.labelOf(LIB, 'gaaaaaaa1'), 'Holiday');
  assert.strictEqual(G.labelOf(LIB, 'space'), 'Space');
  assert.strictEqual(G.labelOf(LIB, 'cyberpunk'), 'Cyberpunk');
  assert.strictEqual(G.labelOf(null, 'cyberpunk'), 'Cyberpunk');
  // With its catalogue, Cyberpunk lists its count and first thumbnail like the other built-ins.
  walls = G.merge([W('a'), W('c1', 'cyberpunk'), W('c2', 'cyberpunk')], LIB);
  assert.deepStrictEqual(plain(G.list(walls, LIB)).slice(0, 3).map(function (g) { return g.id + ':' + g.count + ':' + g.thumb; }),
    ['scenery:1:assets/wallpapers/a-thumb.jpg', 'space:1:user/space/p0000000002/thumb.jpg', 'cyberpunk:2:assets/wallpapers/c1-thumb.jpg']);
  assert.strictEqual(G.labelOf(null, 'ocean-life'), 'Ocean life');
});

test('next(): rotation stays inside a user group and wraps', function () {
  var G = lib(), walls = G.merge([W('a'), W('b')], LIB);
  assert.strictEqual(G.next(walls, 'u-gaaaaaaa1-p0000000001'), 'u-gaaaaaaa1-p0000000003');
  assert.strictEqual(G.next(walls, 'u-gaaaaaaa1-p0000000003'), 'u-gaaaaaaa1-p0000000001');
  assert.strictEqual(G.next(walls, 'b'), 'a');
});

test("src(): w.files when present, else upstream's id paths, for all seven kinds; unknown kind throws", function () {
  var G = lib(), u = G.find(G.merge([], LIB), 'u-gaaaaaaa1-p0000000001');
  assert.deepStrictEqual(['wall', 'thumb', 'panel', 'blur', 'clear', 'frost', 'soft'].map(function (k) { return G.src(W('misty'), k); }),
    ['assets/wallpapers/misty.jpg', 'assets/wallpapers/misty-thumb.jpg', 'assets/wallpapers/misty-panel.jpg',
     'assets/materials/misty-field-blur.jpg', 'assets/materials/misty-field-clear.jpg', 'assets/materials/misty-field-frost.jpg',
     'assets/materials/misty-study-soft.jpg']);
  Object.keys(FILE_NAMES).forEach(function (k) { assert.strictEqual(G.src(u, k), 'user/gaaaaaaa1/p0000000001/' + FILE_NAMES[k]); });
  assert.throws(function () { G.src(u, 'constructor'); }, /Unknown image kind/);
  assert.strictEqual(G.src({ id: 'x', files: {} }, 'wall'), 'assets/wallpapers/x.jpg', 'a files map without that kind falls back');
});

/* Contract 1 read with node fs, exactly as the app and the server must: what is listed and what is ignored. */
var FIXTURE = path.join(__dirname, 'fixtures', 'user-library');
function readTree(R) {
  var groups = [{ id: 'scenery', name: 'Scenery', created: 0, builtin: true }, { id: 'space', name: 'Space', created: 0, builtin: true },
    { id: 'cyberpunk', name: 'Cyberpunk', created: 0, builtin: true }];
  var user = [], photos = [], ignored = [], dirs = fs.readdirSync(path.join(R, 'groups')).sort();
  function json(f) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch (e) { return null; } }
  dirs.forEach(function (gid) {
    var g;
    if (gid.charAt(0) === '.' || gid === 'scenery' || gid === 'space' || gid === 'cyberpunk') return;
    g = /^g[0-9a-z]{8}$/.test(gid) ? json(path.join(R, 'groups', gid, 'group.json')) : null;
    if (!g || g.v !== 1 || g.id !== gid || typeof g.name !== 'string' || typeof g.created !== 'number') { ignored.push('groups/' + gid); return; }
    user.push({ id: gid, name: g.name, created: g.created, builtin: false });
  });
  user.sort(function (a, b) { return a.created - b.created; });
  groups = groups.concat(user);
  dirs.forEach(function (gid) {
    var pdir = path.join(R, 'groups', gid, 'photos'), got = [];
    if (!groups.some(function (g) { return g.id === gid; }) || !fs.existsSync(pdir)) return;
    fs.readdirSync(pdir).sort().forEach(function (pid) {
      var m = /^p[0-9a-z]{10}$/.test(pid) ? json(path.join(pdir, pid, 'meta.json')) : null;
      var all = Object.keys(FILE_NAMES).every(function (k) { return fs.existsSync(path.join(pdir, pid, FILE_NAMES[k])); });
      if (!m || m.id !== pid || !all) { ignored.push('groups/' + gid + '/photos/' + pid); return; }
      m.group = gid; got.push(m);
    });
    photos = photos.concat(got);
  });
  // UserLibrary.library()'s order: added, then group order, then id.
  var rank = {}; groups.forEach(function (g, i) { rank[g.id] = i; });
  photos.sort(function (a, b) { return a.added - b.added || rank[a.group] - rank[b.group] || (a.id < b.id ? -1 : 1); });
  return { lib: { v: 1, groups: groups, photos: photos }, ignored: ignored };
}

test('fixture: the merged catalogue from tests/fixtures/user-library/R matches its expected.json', function () {
  var G = lib(), expected = JSON.parse(fs.readFileSync(path.join(FIXTURE, 'expected.json'), 'utf8'));
  var read = readTree(path.join(FIXTURE, 'R')), base = [W('misty'), W('space-moon', 'space')], walls = G.merge(base, read.lib);
  // expected.json also names groups/gnojson03, an EMPTY directory, which git cannot hold: it is not
  // in a checkout, so only the listed entries that exist on disk can be checked.
  var present = expected.ignored.filter(function (rel) { return fs.existsSync(path.join(FIXTURE, 'R', rel)); });
  assert.ok(present.length >= 6, 'fixture lost its malformed entries');
  assert.deepStrictEqual(read.ignored.slice().sort(), present.sort(), 'the reader ignores exactly the malformed entries');
  var users = walls.filter(G.isUser);
  assert.deepStrictEqual(plain(users.map(function (w) { return { group: w.group, id: w.pid }; })), expected.photos);
  assert.deepStrictEqual(ids(users), expected.photos.map(function (p) { return 'u-' + p.group + '-' + p.id; }));
  users.forEach(function (w) {
    Object.keys(w.files).forEach(function (k) {
      var rel = w.files[k].split('/'), f = path.join(FIXTURE, 'R', 'groups', rel[1], 'photos', rel[2], rel[3]);
      assert.strictEqual(rel[0], 'user'); assert.ok(fs.existsSync(f), w.files[k] + ' is not a fixture file');
    });
  });
  var listed = plain(G.list(walls, read.lib));
  assert.deepStrictEqual(listed.map(function (g) { return { id: g.id, builtin: g.builtin }; }),
    expected.groups.map(function (g) { return { id: g.id, builtin: g.builtin }; }));
  expected.groups.forEach(function (g, i) {
    if (!g.builtin) { assert.strictEqual(listed[i].label, g.name); assert.strictEqual(listed[i].created, g.created); }
  });
  assert.deepStrictEqual(listed.map(function (g) { return g.count; }), [1, 2, 0, 2, 0]);
  assert.strictEqual(G.next(walls, 'u-gholiday1-psunset0002'), 'u-gholiday1-pbeach00001');
});

test('ES5 only: no let/const/arrow/class/template in web/wallpaper-groups.js', function () {
  var code = SOURCE.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
  assert.ok(!/\b(let|const|class)\s/.test(code), 'let/const/class');
  assert.ok(!/=>/.test(code), 'arrow function');
  assert.ok(code.indexOf('`') < 0, 'template literal');
});

test('the shipped catalogue: 15 upstream wallpapers are Scenery; Space and Cyberpunk only if their catalogues are in web/', function () {
  var cat = fs.readFileSync(path.join(WEB, 'assets', 'wallpapers', 'catalog.js'), 'utf8');
  var spacePath = path.join(WEB, 'assets', 'wallpapers', 'space-catalog.js'), extra = [cat];
  var cyberPath = path.join(WEB, 'assets', 'wallpapers', 'cyberpunk-catalog.js');
  var hasSpace = fs.existsSync(spacePath), hasCyber = fs.existsSync(cyberPath), want = ['Scenery'];
  if (hasSpace) extra.push(fs.readFileSync(spacePath, 'utf8'));
  if (hasCyber) extra.push(fs.readFileSync(cyberPath, 'utf8'));
  var win = load(extra), G = win.CleanFrameWallpaperGroups, walls = G.clean(win.CleanFrameWallpapers);
  assert.strictEqual(walls.length, win.CleanFrameWallpapers.length, 'a catalogue entry was dropped as invalid or duplicate');
  assert.strictEqual(G.members(walls, 'scenery').length, 15);
  assert.ok(walls.slice(0, 15).every(function (w) { return G.groupOf(w) === 'scenery'; }), 'upstream entries are Scenery');
  var labels = G.groups(walls).map(function (g) { return g.label; });
  if (hasSpace) { want.push('Space'); assert.ok(G.members(walls, 'space').length > 0); }
  if (hasCyber) { want.push('Cyberpunk'); assert.ok(G.members(walls, 'cyberpunk').length > 0); }
  assert.deepStrictEqual(plain(labels), want);
});

/* ---------- Random rotation (2026-11-03: "it starts on a random photo") ---------- */

/* rnd() answering each value in turn (then the last one again). */
function seq() { var v = Array.prototype.slice.call(arguments), i = 0; return function () { return v[Math.min(i++, v.length - 1)]; }; }

test('random(): stays in the group, never the current photo when the group has another, covers every other photo', function () {
  var G = lib(), seen = {}, i, r;
  for (i = 0; i < 1000; i++) {
    r = G.random(MIXED, 's2');
    assert.ok(r === 's1' || r === 's3', 'stays in Space and never repeats s2: ' + r);
    seen[r] = 1;
  }
  assert.deepStrictEqual(Object.keys(seen).sort(), ['s1', 's3'], 'Math.random by default reaches every other photo');
  for (i = 0; i < 200; i++) assert.ok(['b', 'c'].indexOf(G.random(MIXED, 'a')) >= 0, 'Scenery stays Scenery');
});

test('random(): rnd is injected; [0, 1) maps over the other photos in catalogue order; a bad rnd counts as 0', function () {
  var G = lib();
  // Scenery in MIXED is a, b, c: from a the others are b, c
  assert.strictEqual(G.random(MIXED, 'a', seq(0)), 'b');
  assert.strictEqual(G.random(MIXED, 'a', seq(0.49)), 'b');
  assert.strictEqual(G.random(MIXED, 'a', seq(0.5)), 'c');
  assert.strictEqual(G.random(MIXED, 'a', seq(0.999999)), 'c');
  assert.strictEqual(G.random(MIXED, 'c', seq(0.7)), 'b', 'from c the others are a, b');
  [1, -0.1, NaN, undefined, 'x', Infinity].forEach(function (v) { assert.strictEqual(G.random(MIXED, 'a', function () { return v; }), 'b', String(v)); });
  assert.notStrictEqual(G.random(MIXED, 'a', 'not a function'), 'a', 'a non-function rnd falls back to Math.random');
});

test('random(): a group of one stays put; an unknown id falls back like next(); a stale photo moves to a live one of its group', function () {
  var G = lib(), walls;
  assert.strictEqual(G.random([W('only')], 'only', seq(0.5)), 'only');
  assert.strictEqual(G.random([W('s', 'space'), W('a'), W('b')], 's', seq(0.9)), 's', 'Space has one photo: no jump into Scenery');
  assert.strictEqual(G.random(MIXED, 'gone', seq(0.9)), 'a');
  assert.strictEqual(G.random(MIXED, undefined), 'a');
  assert.strictEqual(G.random([], 'x'), 'x');
  walls = [W('a'), W('s1', 'space'), W('s2', 'space')]; walls[1].stale = true;
  assert.strictEqual(G.random(walls, 's1', seq(0.9)), 's2', 'a stale current photo is left for the live one');
  walls[2].stale = true;
  assert.strictEqual(G.random(walls, 's1', seq(0.9)), 's1', 'no live photo in the group: stays');
});

/* Functions of the patched web/main.js, run alone in a vm with fakes for what they call. */
function mainFn(main, name) {
  var a = main.indexOf('  function ' + name + '('), line, b;
  assert.ok(a >= 0, 'web/main.js has no function ' + name);
  line = main.slice(a, main.indexOf('\n', a));
  if (line.split('{').length === line.split('}').length) return line;   // a one-line function
  b = main.indexOf('\n  }\n', a);
  assert.ok(b > a, 'the end of ' + name);
  return main.slice(a, b + 4);
}
function rotationPage(mode, opts) {
  var main = fs.readFileSync(path.join(WEB, 'main.js'), 'utf8'), log = [], G = lib(), ctx;
  opts = opts || {};
  ctx = { WG: G, WALLS: MIXED, log: log, S: { c: { bg: opts.bg || 'photo', mode: mode, wall: 's2', home: [], recent: [] }, err: null, lookError: null },
    next: function () { return { mode: ctx.S.c.mode, wall: ctx.S.c.wall }; },
    write: function (c) { if (opts.failWrite) return false; ctx.S.c = { bg: ctx.S.c.bg, mode: c.mode, wall: c.wall, home: [], recent: [] }; return true; },
    setLook: function (patch, o) { log.push(['setLook', patch.wall, o.row]); } };
  vm.createContext(ctx);
  vm.runInContext(mainFn(main, 'applyRotation') + '\n' + mainFn(main, 'rotating'), ctx, { filename: 'main.js (rotation)' });
  return ctx;
}
test('patched main.js: turning rotation on (Fixed -> a rotating mode) moves to a random other photo of the group at once', function () {
  ['open', '5', '15', '60'].forEach(function (m) {
    var c = rotationPage('fixed');
    c.applyRotation(m);
    assert.strictEqual(c.S.c.mode, m);
    assert.strictEqual(c.log.length, 1, m + ': one change of photo');
    assert.ok(c.log[0][1] === 's1' || c.log[0][1] === 's3', m + ': a random other Space photo, not ' + c.log[0][1]);
    assert.strictEqual(c.log[0][2], 'bg');
    assert.strictEqual(c.rotating(), true);
  });
});
test('patched main.js: changing between rotating modes, turning rotation off, a failed save or a flat background change no photo', function () {
  var c = rotationPage('15'); c.applyRotation('60'); assert.deepStrictEqual(c.log, [], '15 -> 60');
  c = rotationPage('open'); c.applyRotation('fixed'); assert.deepStrictEqual(c.log, [], 'open -> fixed'); assert.strictEqual(c.rotating(), false);
  c = rotationPage('fixed'); c.applyRotation('fixed'); assert.deepStrictEqual(c.log, [], 'fixed -> fixed');
  c = rotationPage('fixed', { failWrite: true }); c.applyRotation('15'); assert.deepStrictEqual(c.log, [], 'not saved'); assert.ok(c.S.lookError);
  c = rotationPage('fixed', { bg: 'dark' }); c.applyRotation('15'); assert.deepStrictEqual(c.log, [], 'flat background');
});
test('patched main.js: every rotating mode starts on a random photo at start-up; timed changes are random; a hand pick keeps the mode', function () {
  var main = fs.readFileSync(path.join(WEB, 'main.js'), 'utf8'), sync = mainFn(main, 'syncRotation'), apply = mainFn(main, 'applyWallpaper');
  assert.ok(main.indexOf("    if (saved && initial.mode !== 'fixed') initial.wall = WG.random(WALLS, initial.wall);") > 0, 'start-up');
  assert.ok(main.indexOf("initial.mode === 'open'") < 0, 'start-up no longer only for "Each time Home opens"');
  assert.ok(sync.indexOf('setLook({ wall: WG.random(WALLS, S.c.wall) }') > 0, 'the timer picks at random');
  // A photo picked by hand is applied with the mode untouched, so rotation goes on (randomly) from it.
  assert.ok(apply.indexOf('c.wall = w.id;') > 0 && apply.indexOf('c.mode') < 0, 'applyWallpaper leaves the mode as it is');
});

/* The tick follows rotation (2026-11-03: "if random is selected instead of fixed there should be
   a tick on rotate not a selected photo"). */
function backgroundPage(mode, bg) {
  var main = fs.readFileSync(path.join(WEB, 'main.js'), 'utf8'), ctx, G = lib();
  ctx = { WG: G, WALLS: MIXED, LIB: null, GROUP_VISIBLE: 4, S: { c: { mode: mode, wall: 's2', bg: bg || 'photo' } },
    MODES: [{ id: 'fixed', name: 'Fixed' }, { id: 'open', name: 'Each time Home opens' }, { id: '15', name: 'Every 15 minutes' }],
    PG: { countText: function (n) { return n + (n === 1 ? ' photo' : ' photos'); }, canAddGroup: function () { return true; }, MAX_GROUPS: 8,
      windowTop: function () { return 0; } },
    icon: function (n) { return '[' + n + ']'; }, wall: function (id) { return G.find(MIXED, id); },
    materialApplies: function () { return true; }, choice: function (axis, value) { return { axis: axis, value: value, action: 'choice.' + axis + '.' + value }; },
    X: { owns: function () { return false; } }, BT: { sections: function () { return []; } } };
  vm.createContext(ctx);
  ['rotating', 'activeGroup', 'groupRows', 'groupSection', 'mode', 'detailSections'].forEach(function (n) { vm.runInContext(mainFn(main, n), ctx); });
  return plain(ctx.detailSections({ group: 'background', f: {} }));
}
test('patched main.js: Background page while rotating: the Rotation row and the rotating group carry the tick and "Rotating"', function () {
  var s = backgroundPage('15'), groups = s[1].items, rot = s[2].items[0];
  assert.strictEqual(rot.label, 'Every 15 minutes');
  assert.strictEqual(rot.tick, true, 'the Rotation row is ticked');
  var space = groups.filter(function (g) { return g.group === 'space'; })[0], scenery = groups.filter(function (g) { return g.group === 'scenery'; })[0];
  assert.strictEqual(space.tick, true); assert.strictEqual(space.note, '3 photos · Rotating');
  assert.strictEqual(scenery.tick, false); assert.strictEqual(scenery.note, '3 photos');
  s = backgroundPage('open');
  assert.strictEqual(s[2].items[0].tick, true, 'Each time Home opens is rotating too');
});
test('patched main.js: Background page when Fixed is as before: no tick on Rotation, the applied group ticked, no "Rotating"', function () {
  var s = backgroundPage('fixed'), rot = s[2].items[0], space = s[1].items.filter(function (g) { return g.group === 'space'; })[0];
  assert.ok(!('tick' in rot), 'no tick field at all (no empty tick box): ' + JSON.stringify(rot));
  assert.strictEqual(space.tick, true); assert.strictEqual(space.note, '3 photos');
  rot = backgroundPage('15', 'dark')[2].items[0];
  assert.ok(!('tick' in rot) && rot.disabled, 'a flat background ticks no Rotation row');
});
test('patched main.js: the group page ticks no photo while rotating (is-showing, "Now showing"); Fixed ticks the applied one', function () {
  var main = fs.readFileSync(path.join(WEB, 'main.js'), 'utf8'), page = mainFn(main, 'renderWallpaper');
  var cls = /row\.className = 'setting-choice wallpaper-choice'[^;]*;/.exec(page)[0];
  var tick = /setHTML\(row\.querySelector\('\.setting-tick'\), [^;]*;/.exec(page)[0];
  var checked = /row\.setAttribute\('aria-checked', [^;]*;/.exec(page)[0];
  var note = /note = t\.previewDue [^;]*;/.exec(page)[0];
  function run(src, mode, id) {
    var ctx = { S: { c: { mode: mode, wall: 's2' } }, t: { f: { zone: 'photo' }, i: 0, removing: false, adjusting: false, previewDue: null }, i: 1, w: { id: id }, row: {
      setAttribute: function (k, v) { ctx.attr = v; } }, shown: { id: id }, candidate: { name: 'x' }, setHTML: function (el, h) { ctx.html = h; },
      icon: function () { return 'TICK'; }, WG: { isUser: function () { return false; } }, adjustable: function () { return false; } };
    ctx.row.querySelector = function () { return {}; };
    vm.createContext(ctx);
    vm.runInContext(mainFn(main, 'rotating') + '\nvar note;\n' + src, ctx);
    return ctx;
  }
  ['15', 'open'].forEach(function (m) {
    var on = run(cls, m, 's2');
    assert.ok(/ is-showing/.test(on.row.className) && !/ is-applied/.test(on.row.className), m + ': ' + on.row.className);
    assert.strictEqual(run(tick, m, 's2').html, '', m + ': no tick on the photo on screen');
    assert.strictEqual(run(checked, m, 's2').attr, 'false', m + ': not a checked radio');
    assert.strictEqual(run(note, m, 's2').note, 'Now showing');
  });
  var fixed = run(cls, 'fixed', 's2');
  assert.ok(/ is-applied/.test(fixed.row.className) && !/ is-showing/.test(fixed.row.className), 'fixed: ' + fixed.row.className);
  assert.strictEqual(run(tick, 'fixed', 's2').html, 'TICK');
  assert.strictEqual(run(checked, 'fixed', 's2').attr, 'true');
  assert.strictEqual(run(note, 'fixed', 's2').note, 'Applied');
  assert.ok(!/ is-showing| is-applied/.test(run(cls, '15', 's1').row.className), 'another photo carries neither');
  var css = fs.readFileSync(path.join(WEB, 'wallpaper-groups.css'), 'utf8');
  assert.ok(/\.wallpaper-choice\.is-showing \.setting-name::after\{content:' · Now showing'/.test(css), 'the subtle "Now showing" mark');
});

test('patched web/main.js routes rotation, the saved-id fallback, the group pages and every image path through the groups', function () {
  var main = fs.readFileSync(path.join(WEB, 'main.js'), 'utf8');
  assert.ok(main.indexOf('WALL_BASE = WG.clean(window.CleanFrameWallpapers)') >= 0, 'catalogue not cleaned');
  assert.ok(main.indexOf('WALLS = WG.merge(WALL_BASE, LIB)') >= 0, 'library not merged at start-up');
  assert.strictEqual(main.split('WG.next(WALLS, ').length - 1, 0, 'no rotation walks the group in order any more');
  assert.strictEqual(main.split('WG.random(WALLS, ').length - 1, 3, 'start-up, timed and turning rotation on use WG.random');
  assert.ok(main.indexOf('% WALLS.length') < 0, 'a rotation still walks the whole gallery');
  assert.ok(main.indexOf('wall: WG.resolve(WALLS, modern.wall || s.wallpaper)') >= 0, 'saved id not resolved');
  assert.ok(main.indexOf('WALLS[t.i]') < 0, 'the group page still indexes the whole gallery');
  // ONE path helper: no wallpaper or material path is built anywhere else.
  assert.strictEqual(main.split('function imgSrc(id, kind) { return WG.src(').length - 1, 1, 'the helper');
  assert.ok(!/\bWP \+/.test(main), 'a path is still built from WP');
  assert.ok(main.indexOf("'assets/materials/'") < 0, 'a material path is still built by hand');
  assert.ok(main.indexOf("-thumb.jpg'") < 0 && main.indexOf("-panel.jpg'") < 0, 'a thumb/panel path is still built by hand');
  ['wall', 'panel', 'thumb'].forEach(function (k) { assert.ok(main.indexOf("imgSrc(") >= 0 && main.indexOf("'" + k + "')") >= 0, k); });
  assert.ok(main.indexOf("imgSrc(id, material === 'soft' ? 'soft' : material === 'tint' ? 'clear' : 'frost')") >= 0, 'matSrc');
  assert.ok(main.indexOf("imgSrc(id, material === 'soft' ? 'blur' : material === 'tint' ? 'clear' : 'frost')") >= 0, 'fieldSrc');
  // The Background page has Groups instead of the Wallpaper row; 3b95ace's switcher row is gone.
  assert.ok(main.indexOf("action: 'background.wallpaper'") < 0, 'the old Wallpaper row is still there');
  assert.ok(main.indexOf('wallpaper-group') < 0 && main.indexOf('switchWallGroup') < 0, '3b95ace group switcher still there');
  assert.ok(main.indexOf('groupSection(t, flat)') >= 0, 'no Groups section');
  var html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
  var order = ['assets/wallpapers/catalog.js', 'assets/wallpapers/space-catalog.js', 'assets/wallpapers/cyberpunk-catalog.js', 'wallpaper-groups.js', 'qrcode-generator.js', 'photo-groups.js', 'main.js']
    .map(function (s) { return html.indexOf('<script src="' + s + '"></script>'); });
  assert.ok(order.every(function (n) { return n >= 0; }), 'index.html lacks a script: ' + order);
  for (var i = 1; i < order.length; i++) assert.ok(order[i - 1] < order[i], 'script order ' + order);
});

var failed = 0;
cases.forEach(function (c) {
  try { c[1](); console.log('PASS ' + c[0]); }
  catch (e) { failed++; console.log('FAIL ' + c[0] + ': ' + (e && e.message)); }
});
if (failed) { console.log('WALLPAPER_GROUPS_TEST_FAIL ' + failed); process.exit(1); }
console.log('WALLPAPER_GROUPS_TEST_OK ' + cases.length);

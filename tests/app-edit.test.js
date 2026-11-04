/* Unit tests for web/app-edit.js (window.CleanFrameAppEdit) and web/app-marks.js (the mark library),
   per-app name and icon (2026-11-01): the store (own localStorage key keyed by Android package,
   bad or unknown values ignored, a failed save changes nothing and says so, entries it does not touch
   written back unchanged, upstream's cleanframe.home.v1 never written), name rules, dressing app
   records (identity, launch target and Home order never change; two apps with one mark stay two),
   the mark by package (an app whose package a mark lists shows it, an unknown package keeps its own
   icon, an explicit choice including "App's own icon" wins, older saved overrides still load, every
   mark's packages are valid, evidenced in docs/mark-packages.json and under one mark at most),
   the Rename and Change icon screens' keys and drawing (fake page), every picker name fitting its tile
   (LABEL_LINE x LABEL_LINES against home-extras.css), the library (every mark has its
   SVG, metrics, and is ours in OURS or pinned upstream), the original IPTV mark (valid, 24-unit,
   white, recorded as ours), the Simple Icons 16.36.0 marks (pinned source, CC0, white, measured), and the patched web/main.js hooks replayed with a fake page: records are
   dressed at start-up and from the installed-apps list, saved with their OWN names, drawn with the
   override mark, redrawn when an override changes, re-sorted in All apps, and the menu opens both
   screens. Starter Home (AE.starter and the patched start-up): a new stick's Home is the installed
   apps with a white mark, one per mark, in library order, at most 5 (fewer if fewer; the first app by
   name if none); once only, and never over a saved or already changed layout. Two packages of one brand both
   show. Uninstall on the long-press menu: Cancel changes nothing, a package really gone leaves Home (never
   the last) and its override goes. No dependencies: node:assert + node:vm. Run from the repo root: node tests/app-edit.test.js
   Prints one line per case and exits non-zero on any failure. */
'use strict';
var assert = require('node:assert');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var ROOT = path.join(__dirname, '..');
var WEB = path.join(ROOT, 'web');
function read(p) { return fs.readFileSync(path.join(ROOT, p), 'utf8'); }
var SOURCE = read('web/app-edit.js');
var MARKS = read('web/app-marks.js');
var METRICS_JS = read('web/assets/icons/metrics.js');
var MAIN = read('web/main.js');
var INDEX = read('web/index.html');
var PATCH = read('tools/android-patch.py');
var MANIFEST = JSON.parse(read('tools/upstream-manifest.json')).files;
var KEY = 'cleanframe.tv.apps.v1';

function fakeStorage(initial, failWrites) {
  var data = {}, k, writes = [];
  for (k in initial || {}) data[k] = initial[k];
  return { data: data, writes: writes, getItem: function (key) { return Object.prototype.hasOwnProperty.call(data, key) ? data[key] : null; },
    setItem: function (key, value) { if (failWrites) throw new Error('QuotaExceededError'); writes.push(key); data[key] = String(value); } };
}
/* A tiny fake page: elements by id; innerHTML is kept as text, and querySelector(All) answer stubs
   for the class names the screens look up (as many as the HTML holds). */
function fakeEl(id) {
  var el = { id: id, className: '', textContent: '', innerHTML: '', style: {}, _q: {}, value: '',
    listeners: {}, addEventListener: function (t, fn) { this.listeners[t] = fn; },
    focus: function () { doc.activeElement = this; }, blur: function () { if (doc.activeElement === this) doc.activeElement = null; },
    classList: { add: function () {}, remove: function () {} } };
  el.querySelectorAll = function (sel) {
    var cls = sel.replace(/^\./, ''), n = 0, re, list;
    if (sel.indexOf('img[') === 0) return [];
    re = new RegExp('class="[^"]*\\b' + cls + '\\b', 'g');
    while (re.exec(el.innerHTML)) n++;
    list = el._q[sel] = (el._q[sel] && el._q[sel].length === n) ? el._q[sel] : Array.apply(null, Array(n)).map(function () { return fakeEl(''); });
    return list;
  };
  el.querySelector = function (sel) { return el.querySelectorAll(sel)[0] || null; };
  return el;
}
var doc;
function fakeDocument() {
  var els = {};
  doc = { activeElement: null, getElementById: function (id) {
    if (id === 'appNameInput') return /appNameInput/.test(els.appearanceRows ? els.appearanceRows.innerHTML : '') ? (els.input = els.input || fakeEl('appNameInput')) : null;
    return els[id] = els[id] || fakeEl(id);
  }, els: els };
  return doc;
}
function load(storage, opts) {
  opts = opts || {};
  var ctx = { window: { localStorage: storage || fakeStorage() }, document: fakeDocument() };
  vm.createContext(ctx);
  ctx.window.window = ctx.window;
  vm.runInContext(METRICS_JS, ctx, { filename: 'metrics.js' });
  if (!opts.noLibrary) vm.runInContext(MARKS, ctx, { filename: 'app-marks.js' });
  if (opts.marks) ctx.window.CleanFrameAppMarks = opts.marks;
  vm.runInContext(SOURCE, ctx, { filename: 'app-edit.js' });
  return ctx.window.CleanFrameAppEdit;
}
function plain(x) { return JSON.parse(JSON.stringify(x)); }
var cases = [];
function test(name, fn) { cases.push([name, fn]); }

/* ---------- The store ---------- */

test('set and clear a name and a mark: own key, keyed by package, survives a reload; upstream key untouched', function () {
  var st = fakeStorage({ 'cleanframe.home.v1': '{"dock":["emby"]}' }), A = load(st), heard = [];
  A.onChange(function (pkg, field) { heard.push(pkg + ':' + field); });
  assert.deepStrictEqual(plain(A.get('com.mb.android')), {});
  assert.deepStrictEqual(plain(A.setName('com.mb.android', '  Living room TV  ')), { ok: true, reason: '' });
  assert.deepStrictEqual(plain(A.setMark('com.mb.android', 'iptv')), { ok: true, reason: '' });
  assert.deepStrictEqual(JSON.parse(st.data[KEY]), { 'com.mb.android': { name: 'Living room TV', mark: 'iptv' } });
  assert.deepStrictEqual(heard, ['com.mb.android:name', 'com.mb.android:mark']);
  assert.strictEqual(st.data['cleanframe.home.v1'], '{"dock":["emby"]}');
  assert.deepStrictEqual(st.writes.filter(function (k) { return k !== KEY; }), [], 'only its own key is written');
  var B = load(st);
  assert.deepStrictEqual(plain(B.get('com.mb.android')), { name: 'Living room TV', mark: 'iptv' });
  assert.deepStrictEqual(plain(B.all()), { 'com.mb.android': { name: 'Living room TV', mark: 'iptv' } });
  assert.ok(B.setName('com.mb.android', null).ok); assert.ok(B.setMark('com.mb.android', '').ok);
  assert.deepStrictEqual(JSON.parse(st.data[KEY]), { 'com.mb.android': { mark: '' } }, 'App’s own icon is kept as an explicit choice');
  assert.deepStrictEqual(plain(load(st).all()), { 'com.mb.android': { mark: '' } }, 'and reported');
  assert.ok(B.setMark('com.mb.android', null).ok);
  assert.deepStrictEqual(JSON.parse(st.data[KEY]), {}, 'Original name and no icon choice remove the entry');
});

test('names: trimmed, never empty, capped like group names, no control characters', function () {
  var A = load(), long = new Array(A.NAME_MAX + 2).join('x');
  assert.strictEqual(A.NAME_MAX, 24);
  assert.deepStrictEqual(plain(A.validName('  IPTV  ')), { ok: true, name: 'IPTV', reason: '' });
  assert.strictEqual(A.validName('   ').ok, false);
  assert.strictEqual(A.validName('').reason, 'Type a name for the app.');
  assert.strictEqual(A.validName(long.slice(1)).ok, true, '24 is allowed');
  assert.strictEqual(A.validName(long).ok, false, '25 is not');
  assert.strictEqual(A.validName('a\u0007b').ok, false);
  assert.strictEqual(A.validName(new Array(25).join('📺')).ok, true, 'an emoji counts as one character');
  assert.strictEqual(A.validName(null).ok, false);
  var r = A.setName('com.mb.android', '   ');
  assert.deepStrictEqual(plain(r), { ok: false, reason: 'Type a name for the app.' });
  assert.deepStrictEqual(plain(A.get('com.mb.android')), {}, 'a refused name changes nothing');
});

test('a failed save (storage full or missing) changes nothing, says so and does not notify', function () {
  var st = fakeStorage({}), A = load(st);
  assert.ok(A.setName('org.example.kodi', 'Kodi box').ok);
  var full = fakeStorage(st.data, true), B = load(full), heard = 0;
  B.onChange(function () { heard++; });
  var rev = B.rev();
  assert.deepStrictEqual(plain(B.setName('org.example.kodi', 'Other')), { ok: false, reason: B.SAVE_FAILED });
  assert.deepStrictEqual(plain(B.setMark('org.example.kodi', 'iptv')), { ok: false, reason: B.SAVE_FAILED });
  assert.strictEqual(B.SAVE_FAILED, 'Couldn’t save. Nothing changed.');
  assert.deepStrictEqual(plain(B.get('org.example.kodi')), { name: 'Kodi box' });
  assert.strictEqual(heard, 0); assert.strictEqual(B.rev(), rev, 'nothing to redraw');
  var none = load({ getItem: function () { throw new Error('denied'); }, setItem: function () { throw new Error('denied'); } });
  assert.deepStrictEqual(plain(none.get('org.example.kodi')), {});
  assert.strictEqual(none.setName('org.example.kodi', 'X').ok, false);
});

test('saved junk is ignored value by value; unknown marks fall back; untouched entries are written back as they were', function () {
  var saved = { 'com.a': { name: 'Good', mark: 'nosuchmark' }, 'com.b': { name: '', mark: 'iptv' }, 'com.c': 'junk', 'com.d': ['x'],
    'com.e': { name: 42, mark: 7 }, 'com.f': { name: new Array(40).join('y') }, '__proto__x': { name: 'x' } };
  var st = fakeStorage({}), A;
  st.data[KEY] = JSON.stringify(saved);
  A = load(st);
  assert.deepStrictEqual(plain(A.get('com.a')), { name: 'Good' }, 'unknown mark id: own icon');
  assert.deepStrictEqual(plain(A.get('com.b')), { mark: 'iptv' });
  ['com.c', 'com.d', 'com.e', 'com.f', 'com.none', 'constructor', '__proto__', 'hasOwnProperty'].forEach(function (p) {
    assert.deepStrictEqual(plain(A.get(p)), {}, p);
  });
  assert.ok(A.setName('com.z', 'Zed').ok);
  var after = JSON.parse(st.data[KEY]);
  assert.deepStrictEqual(after['com.a'], saved['com.a'], 'an unknown mark (a newer build’s?) survives a save');
  assert.deepStrictEqual(after['com.c'], 'junk');
  assert.deepStrictEqual(after['com.z'], { name: 'Zed' });
  assert.ok(A.setName('com.a', 'Better').ok);
  assert.deepStrictEqual(JSON.parse(st.data[KEY])['com.a'], { name: 'Better', mark: 'nosuchmark' }, 'only the field set changes');
  ['[1,2]', 'not json', 'null', '"x"'].forEach(function (raw) {
    var s2 = fakeStorage({}); s2.data[KEY] = raw;
    var B = load(s2);
    assert.deepStrictEqual(plain(B.all()), {}, raw);
    assert.ok(B.setMark('com.q', 'iptv').ok, raw + ': still saves');
  });
  assert.strictEqual(A.setMark('com.q', 'nosuchmark').ok, false, 'an unknown mark is refused');
  assert.strictEqual(A.setName('../etc', 'x').ok, false, 'not a package');
});

/* ---------- Records ---------- */

test('dress: shows the override, keeps the own name and mark, never touches id, appId or brand', function () {
  var A = load();
  var emby = { id: 'emby', appId: 'com.mb.android', name: 'Emby', brand: 'emby', mark: true };
  var kodi = { id: 'org.example.kodi', appId: 'org.example.kodi', name: 'Kodi', brand: '', mark: false, icon: '/icons/org.example.kodi.png' };
  A.dress(emby); A.dress(kodi);
  assert.deepStrictEqual([emby.name, emby.mark, emby.markId, kodi.name, kodi.mark, kodi.markId], ['Emby', true, 'emby', 'Kodi', false, '']);
  A.setMark('com.mb.android', 'iptv'); A.setMark('org.example.kodi', 'iptv'); A.setName('org.example.kodi', 'Sports');
  A.dress(emby); A.dress(kodi);
  assert.deepStrictEqual([emby.markId, kodi.markId, kodi.mark, kodi.name], ['iptv', 'iptv', true, 'Sports']);
  assert.deepStrictEqual([emby.id, emby.appId, emby.brand, kodi.id, kodi.appId, kodi.brand, kodi.icon],
    ['emby', 'com.mb.android', 'emby', 'org.example.kodi', 'org.example.kodi', '', '/icons/org.example.kodi.png'], 'two apps with one mark stay two apps');
  assert.strictEqual(A.ownName(kodi), 'Kodi');
  A.setName('org.example.kodi', null); A.setMark('org.example.kodi', null); A.dress(kodi);
  assert.deepStrictEqual([kodi.name, kodi.mark, kodi.markId], ['Kodi', false, ''], 'back to its own name and icon');
  kodi.name = 'Kodi 21'; kodi.mark = false; A.dress(kodi, true);
  assert.strictEqual(A.ownName(kodi), 'Kodi 21', 'fresh: the own values are taken again');
  assert.strictEqual(A.ownName({ name: 'Plain' }), 'Plain');
  assert.strictEqual(A.dress(null), null);
});

/* ---------- A mark by package (2026-11-02) ---------- */

function rec(pkg, name) { return { id: pkg, appId: pkg, name: name, brand: '', mark: false, icon: '/icons/' + pkg + '.png' }; }

test('by package: an installed app whose package a mark lists shows that mark; an unknown package keeps its own icon', function () {
  var A = load(), jf = A.dress(rec('org.jellyfin.androidtv', 'Jellyfin')), nf = A.dress(rec('com.netflix.mediaclient', 'Netflix')),
    gp = A.dress(rec('com.android.vending', 'Google Play Store')), other = A.dress(rec('org.example.unknown', 'Unknown'));
  assert.deepStrictEqual([jf.markId, jf.mark, nf.markId, gp.markId], ['jellyfin', true, 'netflix', 'googleplay']);
  assert.deepStrictEqual([other.markId, other.mark, other.icon], ['', false, '/icons/org.example.unknown.png'], 'no mark: its own icon');
  assert.deepStrictEqual([jf.id, jf.appId, jf.name, jf.own.mark], ['org.jellyfin.androidtv', 'org.jellyfin.androidtv', 'Jellyfin', false], 'id, launch target and name unchanged');
  assert.deepStrictEqual(plain(A.all()), {}, 'nothing is saved for a mark by package');
  var iptv = A.dress(rec('com.skyglass.v2', 'Skyglass')); assert.strictEqual(iptv.markId, '', 'com.skyglass.v2 (IPTV, not Sky) is not mapped');
  assert.strictEqual(A.autoMark('../x'), '');
});

test('by package: the user’s choice wins: explicit App’s own icon stays the own icon; an explicit other mark wins', function () {
  var st = fakeStorage(), A = load(st), jf = rec('org.jellyfin.androidtv', 'Jellyfin'), emby = { id: 'emby', appId: 'com.mb.android', name: 'Emby', brand: 'emby', mark: true };
  assert.ok(A.setMark('org.jellyfin.androidtv', '').ok); A.dress(jf);
  assert.deepStrictEqual([jf.markId, jf.mark], ['', false], 'explicit App’s own icon: the Android icon, not the Jellyfin mark');
  assert.deepStrictEqual(JSON.parse(st.data[KEY]), { 'org.jellyfin.androidtv': { mark: '' } });
  A = load(st); A.dress(jf, true); assert.strictEqual(jf.markId, '', 'and after a restart');
  assert.ok(A.setMark('org.jellyfin.androidtv', 'iptv').ok); A.dress(jf);
  assert.deepStrictEqual([jf.markId, jf.mark], ['iptv', true], 'an explicit other mark wins over the package’s');
  assert.ok(A.setMark('org.jellyfin.androidtv', null).ok); A.dress(jf);
  assert.strictEqual(jf.markId, 'jellyfin', 'no choice: the package’s mark again');
  A.setMark('com.mb.android', ''); A.dress(emby);
  assert.deepStrictEqual([emby.markId, emby.mark], ['', true], 'a default brand app’s own icon is its brand mark (main.js draws a.brand)');
});

test('by package: saved overrides from before 2026-11-02 still load; a saved mark still wins', function () {
  var saved = { 'org.jellyfin.androidtv': { name: 'Films', mark: 'iptv' }, 'com.plexapp.android': { name: 'Plex box' }, 'org.example.kodi': { mark: 'emby' } };
  var st = fakeStorage(); st.data[KEY] = JSON.stringify(saved);
  var A = load(st), jf = A.dress(rec('org.jellyfin.androidtv', 'Jellyfin')), px = A.dress(rec('com.plexapp.android', 'Plex')), k = A.dress(rec('org.example.kodi', 'Kodi'));
  assert.deepStrictEqual([jf.name, jf.markId, px.name, px.markId, k.markId], ['Films', 'iptv', 'Plex box', 'plex', 'emby'],
    'a saved mark still wins; a saved name alone leaves the icon to the package (it had no icon choice)');
  assert.deepStrictEqual(plain(A.all()), saved);
  assert.ok(A.setName('com.plexapp.android', null).ok);
  delete saved['com.plexapp.android'];
  assert.deepStrictEqual(JSON.parse(st.data[KEY]), saved, 'untouched entries written back as they were');
});

test('by package: every mark has a packages array of valid, verified packages, none under two marks', function () {
  var raw = (function () { var c = { window: {} }; vm.createContext(c); vm.runInContext(MARKS, c); return c.window.CleanFrameAppMarks; })();
  var ev = JSON.parse(read('docs/mark-packages.json')).packages, owner = {};
  raw.forEach(function (e) {
    assert.ok(Array.isArray(e.packages), e.id + ': packages is an array');
    e.packages.forEach(function (p) {
      assert.ok(typeof p === 'string' && /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(p), e.id + ': ' + p + ' is an Android package name');
      assert.ok(!Object.prototype.hasOwnProperty.call(owner, p), p + ' is listed under ' + owner[p] + ' and ' + e.id);
      owner[p] = e.id;
      assert.ok(ev[p] && ev[p].mark === e.id, p + ': evidence in docs/mark-packages.json under ' + e.id);
      assert.ok(ev[p].evidence === 'installed' || ev[p].evidence === 'play' && /^https:\/\/play\.google\.com\/store\/apps\/details\?id=/.test(ev[p].url) && ev[p].developer, p + ': evidence');
    });
  });
  Object.keys(ev).forEach(function (p) { assert.strictEqual(owner[p], ev[p].mark, p + ' (docs/mark-packages.json) is in app-marks.js under ' + ev[p].mark); });
  assert.ok(!owner['com.skyglass.v2'], 'com.skyglass.v2 is an IPTV app, not Sky');
  assert.deepStrictEqual([owner['com.android.vending'], owner['com.mb.android'], owner['tv.emby.embyatv']], ['googleplay', 'emby', 'emby']);
  /* Every package web/device.js gives a brand shows that brand's mark. */
  var dev = read('web/device.js'), ids = /var IDS = (\{[\s\S]*?\});/.exec(dev)[1], also = /var ALSO = (\{[\s\S]*?\});/.exec(dev)[1];
  ids = vm.runInNewContext('(' + ids + ')'); also = vm.runInNewContext('(' + also + ')');
  Object.keys(ids).forEach(function (b) { assert.strictEqual(owner[ids[b]], b, 'device.js IDS.' + b); });
  Object.keys(also).forEach(function (b) { also[b].forEach(function (p) { assert.strictEqual(owner[p], b, 'device.js ALSO.' + b + ' ' + p); }); });
});

test('by package: a package listed under two marks gets neither (and the library check above fails)', function () {
  var A = load(null, { marks: [{ id: 'iptv', name: 'IPTV', packages: ['com.twice'] }, { id: 'emby', name: 'Emby', packages: ['com.twice', 'com.once'] },
    { id: 'kodi', name: 'Kodi', packages: 'org.example.kodi' }] });
  assert.deepStrictEqual([A.autoMark('com.twice'), A.autoMark('com.once'), A.autoMark('org.example.kodi')], ['', 'emby', ''], 'a bad packages value is no packages');
});

/* ---------- The mark library ---------- */

function ours() {
  var m = /\nOURS = \{([\s\S]*?)\n\}/.exec(PATCH);
  assert.ok(m, 'OURS in tools/android-patch.py');
  return m[1];
}
function metrics() { return JSON.parse(/FrameIconMetrics = (\{.*\});/.exec(METRICS_JS)[1]); }

test('library: every mark has its SVG, measured metrics and is ours (OURS) or pinned upstream; iptv first', function () {
  var A = load(), list = load().library(), m = metrics(), o = ours(), seen = {};
  var raw = (function () { var c = { window: {} }; vm.createContext(c); vm.runInContext(MARKS, c); return c.window.CleanFrameAppMarks; })();
  assert.strictEqual(list.length, raw.length, 'no entry is dropped (each has metrics)');
  assert.strictEqual(raw[0].id, 'iptv');
  raw.forEach(function (e) {
    assert.ok(/^[a-z0-9]+$/.test(e.id) && !seen[e.id], e.id); seen[e.id] = 1;
    assert.ok(typeof e.name === 'string' && e.name.length > 0 && e.name.length <= 16, e.id + ' name');
    assert.strictEqual(e.svg, 'assets/icons/' + e.id + '.svg', 'main.js draws assets/icons/<id>.svg');
    assert.ok(fs.existsSync(path.join(WEB, e.svg)), e.svg);
    assert.ok(Object.prototype.hasOwnProperty.call(m, e.id), e.id + ' measured in metrics.js');
    assert.ok(o.indexOf("'" + e.svg + "'") >= 0 || Object.prototype.hasOwnProperty.call(MANIFEST, e.svg), e.svg + ' in OURS or upstream');
  });
  /* Every brand mark the app ships is offered (the utility outlines are not brands). */
  fs.readdirSync(path.join(WEB, 'assets/icons')).filter(function (f) { return /\.svg$/.test(f); }).forEach(function (f) {
    var id = f.slice(0, -4);
    if (['apps', 'art', 'home', 'settings'].indexOf(id) < 0) assert.ok(seen[id], id + ' is in the library');
  });
  assert.ok(o.indexOf("'app-marks.js'") >= 0 && o.indexOf("'app-edit.js'") >= 0, 'both scripts are ours');
  assert.deepStrictEqual(plain(A.choices().slice(0, 2)), [{ id: '', name: 'App’s own icon' }, { id: 'iptv', name: 'IPTV' }]);
});

test('library: an unmeasured, duplicate or malformed entry is not offered', function () {
  var A = load(null, { marks: [{ id: 'iptv', name: 'IPTV' }, { id: 'iptv', name: 'Again' }, { id: 'nometrics', name: 'X' }, { id: 'Bad id', name: 'Y' }, null, { id: 'emby' }] });
  assert.deepStrictEqual(plain(A.library()).map(function (e) { return e.id; }), ['iptv']);
  assert.strictEqual(A.setMark('com.x', 'nometrics').ok, false);
  var none = load(null, { noLibrary: true });
  assert.deepStrictEqual(plain(none.choices()), [{ id: '', name: 'App’s own icon' }], 'no library: only the own icon');
});

test('IPTV mark: ours, well-formed, white on the 24-unit grid, nothing external; recorded and measured', function () {
  var svg = read('web/assets/icons/iptv.svg'), m = metrics().iptv, tags = [], re = /<(\/?)([a-zA-Z]+)[^>]*?(\/?)>/g, t, stack = [];
  assert.ok(/^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg" viewBox="0 0 24 24"[ >]/.test(svg), 'an <svg> on viewBox 0 0 24 24');
  while ((t = re.exec(svg))) {
    tags.push(t[2]);
    if (t[3]) continue;
    if (t[1]) assert.strictEqual(stack.pop(), t[2], 'balanced </' + t[2] + '>');
    else stack.push(t[2]);
  }
  assert.deepStrictEqual(stack, [], 'every element closed');
  tags.forEach(function (n) { assert.ok(['svg', 'title', 'path'].indexOf(n) >= 0, 'no <' + n + '> (text, images, scripts)'); });
  assert.ok(!/href|url\(|<script|<text|on[a-z]+=/i.test(svg), 'nothing external, no wordmark');
  (svg.match(/(?:fill|stroke)="([^"]*)"/g) || []).forEach(function (a) { assert.ok(/="(#fff|none)"/.test(a), a + ' is white or none'); });
  (svg.match(/d="([^"]*)"/g) || []).forEach(function (d) {
    (d.match(/-?\d*\.?\d+/g) || []).forEach(function (n) { assert.ok(Math.abs(+n) <= 24, 'coordinates on the 24 grid: ' + n); });
  });
  var add = JSON.parse(read('web/assets/icons/ADDITIONAL-SOURCES.json')).filter(function (e) { return e.icon === 'iptv'; });
  assert.strictEqual(add.length, 1, 'recorded in ADDITIONAL-SOURCES.json');
  assert.ok(/original/i.test(add[0].note) && /cleanframe-tv/.test(add[0].source), 'recorded as our own work');
  assert.ok(/'assets\/icons\/iptv\.svg': 'original IPTV mark/.test(PATCH), 'OURS says it is original');
  assert.ok(m && m.n === 240 && m.w > 150 && m.h > 150 && m.fill > 0.3 && m.fill < 0.9, 'measured like the other marks');
  assert.ok(PATCH.indexOf('"iptv": {"n": 240, "x": ' + m.x + ', "y": ' + m.y + ', "w": ' + m.w + ', "h": ' + m.h + ', "fill": ' + m.fill + '}') > 0,
    'metrics.js has METRICS["iptv"] from the patch');
});

/* The library's Simple Icons marks (2026-11-02): each recorded with a raw URL pinned to a commit,
   CC0, and drawn white on the 24-unit grid as one path, like emby and rumble. */
var SI_16_36_0 = '2f67e5d1a7e5cba43c79a0ebc00abcd95fb0ea30';
var SI_MARKS = ['jellyfin', 'plex', 'kodi', 'vlc', 'itvx', 'channel4', 'now', 'paramountplus', 'dazn', 'mubi', 'crunchyroll', 'twitch',
  'youtubemusic', 'tidal', 'deezer', 'youtubekids', 'googleplay', 'vimeo', 'dailymotion', 'soundcloud', 'audible', 'pocketcasts', 'steam',
  'playstation', 'nordvpn', 'expressvpn', 'surfshark', 'protonvpn', 'mullvad', 'fubo', 'starz', 'showtime', 'sky'];
test('Simple Icons marks: pinned to the 16.36.0 commit, CC0, white single path on the 24-unit grid, measured and ours', function () {
  var add = JSON.parse(read('web/assets/icons/ADDITIONAL-SOURCES.json')), m = metrics(), o = ours(), lib = load().library().map(function (e) { return e.id; });
  assert.strictEqual(SI_MARKS.length, 33);
  SI_MARKS.forEach(function (id) {
    var e = add.filter(function (x) { return x.icon === id; }), svg = read('web/assets/icons/' + id + '.svg');
    assert.strictEqual(e.length, 1, id + ' recorded once in ADDITIONAL-SOURCES.json');
    assert.ok(new RegExp('^https://raw\\.githubusercontent\\.com/simple-icons/simple-icons/' + SI_16_36_0 + '/icons/[a-z0-9]+\\.svg$').test(e[0].source), id + ' source pinned: ' + e[0].source);
    assert.strictEqual(e[0].licence, 'CC0-1.0', id);
    assert.ok(/16\.36\.0/.test(e[0].note), id + ' note names the release');
    assert.ok(/^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg" viewBox="0 0 24 24" fill="#fff"><title>[^<]+<\/title><path d="[^"]+"\/><\/svg>\n$/.test(svg), id + '.svg: white, 24-unit, one path');
    assert.ok(m[id] && m[id].n === 240 && m[id].fill > 0.2 && m[id].fill < 0.9, id + ' measured');
    assert.ok(PATCH.indexOf('"' + id + '": {"n": 240, "x": ' + m[id].x + ', "y": ' + m[id].y + ', "w": ' + m[id].w + ', "h": ' + m[id].h + ', "fill": ' + m[id].fill + '}') > 0, id + ' METRICS');
    assert.ok(o.indexOf("'assets/icons/" + id + ".svg': 'mark for ") >= 0, id + ' in OURS');
    assert.ok(lib.indexOf(id) > 0, id + ' offered');
  });
});

/* The 16 marks drawn for cleanframe-tv (branch marks-drawn, 2026-11-02): original work like iptv, each
   one well-formed white paths on the 24-unit grid, recorded as ours, measured, in OURS and offered. */
var DRAWN = ['bbciplayer', 'my5', 'britbox', 'plutotv', 'discoveryplus', 'amazonmusic', 'smarttube', 'tivimate', 'plexamp', 'xbox',
  'geforcenow', 'moonlight', 'purevpn', 'downloader', 'xplore', 'airscreen'];
test('drawn marks: ours, well-formed, white on the 24-unit grid, nothing external; recorded, measured, in OURS and offered', function () {
  var add = JSON.parse(read('web/assets/icons/ADDITIONAL-SOURCES.json')), m = metrics(), o = ours(), lib = load().library().map(function (e) { return e.id; });
  assert.ok(!fs.existsSync(path.join(ROOT, 'docs/DRAWN-MARKS.json')), 'folded into ADDITIONAL-SOURCES.json and docs/mark-packages.json (one source each)');
  DRAWN.forEach(function (id) {
    var svg = read('web/assets/icons/' + id + '.svg'), re = /<(\/?)([a-zA-Z]+)[^>]*?(\/?)>/g, t, stack = [], e;
    assert.ok(/^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg" viewBox="0 0 24 24"[ >]/.test(svg), id + ': an <svg> on viewBox 0 0 24 24');
    while ((t = re.exec(svg))) {
      assert.ok(['svg', 'title', 'path'].indexOf(t[2]) >= 0, id + ': no <' + t[2] + '>');
      if (t[3]) continue;
      if (t[1]) assert.strictEqual(stack.pop(), t[2], id + ': balanced'); else stack.push(t[2]);
    }
    assert.deepStrictEqual(stack, [], id + ': every element closed');
    assert.ok(!/href|url\(|<script|<text|on[a-z]+=/i.test(svg), id + ': nothing external, no text');
    (svg.match(/(?:fill|stroke)="([^"]*)"/g) || []).forEach(function (a) { assert.ok(/="(#fff|none)"/.test(a), id + ': ' + a); });
    (svg.match(/ d="([^"]*)"/g) || []).forEach(function (d) {
      (d.match(/-?\d*\.?\d+/g) || []).forEach(function (n) { assert.ok(Math.abs(+n) <= 24, id + ': on the 24 grid: ' + n); });
    });
    e = add.filter(function (x) { return x.icon === id; });
    assert.strictEqual(e.length, 1, id + ' recorded once');
    assert.ok(/^Original drawing for cleanframe-tv/.test(e[0].note) && /cleanframe-tv original work/.test(e[0].source) && /^MIT/.test(e[0].licence), id + ' recorded as ours');
    assert.ok(m[id] && m[id].n === 240 && m[id].fill > 0.2 && m[id].fill < 0.9, id + ' measured');
    assert.ok(PATCH.indexOf('"' + id + '": {"n": 240, "x": ' + m[id].x + ', "y": ' + m[id].y + ', "w": ' + m[id].w + ', "h": ' + m[id].h + ', "fill": ' + m[id].fill + '}') > 0, id + ' METRICS');
    assert.ok(o.indexOf("'assets/icons/" + id + ".svg': 'original ") >= 0, id + ' in OURS as original');
    assert.ok(lib.indexOf(id) > 0, id + ' offered');
  });
  assert.strictEqual(lib.length, 64);
  assert.deepStrictEqual(plain(lib.slice(0, 15)), ['iptv', 'emby', 'youtube', 'spotify', 'rumble', 'netflix', 'primevideo', 'disneyplus', 'stremio', 'appletv', 'hbomax',
    'tubi', 'applemusic', 'abciview', 'sbs'], 'the first marks stay first');
});

/* ---------- Keys ---------- */

test('Rename keys: field, then Save, Cancel, Original name in a row; Back cancels anywhere', function () {
  var A = load();
  function k(f, key) { return plain(A.nameKey(f, key)); }
  assert.deepStrictEqual(k({ zone: 'field', b: 0 }, 'ok'), { zone: 'field', b: 0, act: 'type' });
  assert.deepStrictEqual(k({ zone: 'field', b: 2 }, 'down'), { zone: 'buttons', b: 0, act: null });
  assert.deepStrictEqual(k({ zone: 'field', b: 0 }, 'up'), { zone: 'back', b: 0, act: null });
  assert.deepStrictEqual(k({ zone: 'buttons', b: 0 }, 'right'), { zone: 'buttons', b: 1, act: null });
  assert.deepStrictEqual(k({ zone: 'buttons', b: 1 }, 'right'), { zone: 'buttons', b: 2, act: null });
  assert.deepStrictEqual(k({ zone: 'buttons', b: 2 }, 'right'), { zone: 'buttons', b: 2, act: null });
  assert.deepStrictEqual(k({ zone: 'buttons', b: 0 }, 'left'), { zone: 'buttons', b: 0, act: null });
  assert.deepStrictEqual(['save', 'cancel', 'original'].map(function (a, b) { return k({ zone: 'buttons', b: b }, 'ok').act; }), ['save', 'cancel', 'original']);
  assert.deepStrictEqual(k({ zone: 'buttons', b: 1 }, 'up'), { zone: 'field', b: 1, act: null });
  ['back', 'field', 'buttons'].forEach(function (z) { assert.strictEqual(k({ zone: z, b: 0 }, 'back').act, 'cancel', z); });
  assert.deepStrictEqual(k({ zone: 'back', b: 0 }, 'ok'), { zone: 'back', b: 0, act: 'cancel' });
  assert.deepStrictEqual(k({ zone: 'back', b: 0 }, 'down'), { zone: 'field', b: 0, act: null });
});

test('Change icon keys: four columns; Up/Left off the grid reach Back; Down never leaves a gap', function () {
  var A = load(), n = 16;
  function k(f, key, m) { return plain(A.iconKey(f, key, m || n)); }
  assert.strictEqual(A.COLS, 4);
  assert.deepStrictEqual(k({ zone: 'grid', i: 0 }, 'right'), { zone: 'grid', i: 1, act: null });
  assert.deepStrictEqual(k({ zone: 'grid', i: 3 }, 'right'), { zone: 'grid', i: 3, act: null }, 'end of a row');
  assert.deepStrictEqual(k({ zone: 'grid', i: 4 }, 'left'), { zone: 'back', i: 4, act: null });
  assert.deepStrictEqual(k({ zone: 'grid', i: 5 }, 'left'), { zone: 'grid', i: 4, act: null });
  assert.deepStrictEqual(k({ zone: 'grid', i: 2 }, 'up'), { zone: 'back', i: 2, act: null });
  assert.deepStrictEqual(k({ zone: 'grid', i: 6 }, 'up'), { zone: 'grid', i: 2, act: null });
  assert.deepStrictEqual(k({ zone: 'grid', i: 6 }, 'down'), { zone: 'grid', i: 10, act: null });
  assert.deepStrictEqual(k({ zone: 'grid', i: 14 }, 'down'), { zone: 'grid', i: 14, act: null }, 'last row');
  assert.deepStrictEqual(k({ zone: 'grid', i: 11 }, 'down', 14), { zone: 'grid', i: 13, act: null }, 'to the last choice on a short last row');
  assert.deepStrictEqual(k({ zone: 'grid', i: 13 }, 'right', 14), { zone: 'grid', i: 13, act: null });
  assert.deepStrictEqual(k({ zone: 'back', i: 5 }, 'down'), { zone: 'grid', i: 5, act: null });
  assert.deepStrictEqual(k({ zone: 'back', i: 5 }, 'ok'), { zone: 'back', i: 5, act: 'cancel' });
  assert.deepStrictEqual(k({ zone: 'grid', i: 7 }, 'ok'), { zone: 'grid', i: 7, act: 'apply' });
  assert.strictEqual(k({ zone: 'grid', i: 7 }, 'back').act, 'cancel');
  assert.deepStrictEqual([A.windowTop(0, 4, 6, 4), A.windowTop(2, 1, 6, 4), A.windowTop(0, 3, 4, 4), A.windowTop(5, 5, 6, 4)], [1, 1, 0, 2]);
});

/* ---------- The screens, with a fake page ---------- */

function screens(st) {
  var A = load(st || fakeStorage()), stack = [{ id: 'home', f: {} }], log = [];
  var recs = {
    emby: A.dress({ id: 'emby', appId: 'com.mb.android', name: 'Emby', brand: 'emby', mark: true }),
    kodi: A.dress({ id: 'org.example.kodi', appId: 'org.example.kodi', name: 'Kodi', brand: '', mark: false, icon: '/icons/org.example.kodi.png' })
  };
  A.onChange(function () { Object.keys(recs).forEach(function (k) { A.dress(recs[k]); }); });
  var host = { top: function () { return stack[stack.length - 1]; }, push: function (e) { stack.push(e); return e; }, pop: function () { stack.pop(); },
    after: function () { log.push('after'); }, esc: function (s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'); },
    icon: function (n) { return '<svg class="ico">' + n + '</svg>'; }, setHTML: function (el, h) { el.innerHTML = h; }, focusStage: function () { log.push('stage'); },
    app: function (id) { return id === 'emby' ? recs.emby : id === 'org.example.kodi' ? recs.kodi : null; },
    where: function (t) { return t.where === 'apps' ? 'All apps' : 'Home'; },
    markHtml: function (id) { return '<span class="mark" data-mark="' + id + '"></span>'; },
    appMark: function (a) { return a.mark ? '<span class="mark" data-mark="' + (a.markId || a.brand || a.id) + '"></span>' : '<span class="mark monogram">' + a.name + '</span>'; } };
  var V = A.views(host);
  function key(k) { var t = stack[stack.length - 1]; V.V[t.id].norm(t); V.V[t.id].key(t, k); if (V.owns(stack[stack.length - 1])) V.render(stack[stack.length - 1]); }
  return { A: A, V: V, stack: stack, recs: recs, key: key, log: log, doc: doc, top: host.top };
}

test('Rename: opens on the field with the shown name; Save stores it; Original name clears it; Cancel and Back keep it', function () {
  var s = screens(), t = s.V.openName({ where: 'dock', id: 'emby' });
  assert.strictEqual(t.id, 'appname'); assert.ok(s.V.owns(t)); assert.strictEqual(t.f.zone, 'field'); assert.strictEqual(t.value, 'Emby');
  s.V.render(t);
  var rows = s.doc.els.appearanceRows;
  assert.ok(/appname\.save[^]*appname\.cancel[^]*appname\.original/.test(rows.innerHTML), 'Save, Cancel, Original name in that order');
  assert.strictEqual(s.doc.els.appearanceTitle.textContent, 'Rename app');
  assert.ok(/<span>Home<\/span>/.test(s.doc.els.appearanceBack.innerHTML), 'Back names where the menu was');
  assert.strictEqual(rows.querySelector('.app-name-own').textContent, 'Original name: Emby');
  var input = s.doc.getElementById('appNameInput'); assert.strictEqual(input.value, 'Emby');
  s.key('ok'); assert.strictEqual(s.doc.activeElement, input, 'OK on the field brings up the keyboard');
  input.value = '  Lounge  ';
  var ev = { type: 'keydown', keyCode: 13, preventDefault: function () {} };
  assert.strictEqual(s.V.inputKey(ev), true, 'Enter leaves the box'); assert.strictEqual(s.doc.activeElement, null);
  assert.deepStrictEqual([t.f.zone, t.f.b], ['buttons', 0]);
  assert.strictEqual(s.V.inputKey({ type: 'keydown', keyCode: 65, preventDefault: function () {} }), false, 'not typing: not taken');
  s.key('ok');
  assert.strictEqual(s.stack.length, 1, 'Save closes the screen');
  assert.deepStrictEqual(plain(s.A.get('com.mb.android')), { name: 'Lounge' });
  assert.strictEqual(s.recs.emby.name, 'Lounge'); assert.strictEqual(s.recs.emby.id, 'emby');
  t = s.V.openName({ where: 'apps', id: 'emby' }); assert.strictEqual(t.value, 'Lounge');
  s.key('down'); s.key('right'); s.key('right'); s.key('ok');
  assert.deepStrictEqual(plain(s.A.get('com.mb.android')), {}, 'Original name clears the override');
  assert.strictEqual(s.recs.emby.name, 'Emby');
  t = s.V.openName({ where: 'dock', id: 'emby' }); t.value = 'Nope'; s.key('back');
  assert.strictEqual(s.stack.length, 1); assert.deepStrictEqual(plain(s.A.get('com.mb.android')), {}, 'Back saves nothing');
  t = s.V.openName({ where: 'dock', id: 'emby' }); t.value = 'Nope'; s.key('down'); s.key('right'); s.key('ok');
  assert.deepStrictEqual(plain(s.A.get('com.mb.android')), {}, 'Cancel saves nothing');
  assert.strictEqual(s.V.openName({ where: 'dock', id: 'gone' }), null, 'an unknown app opens nothing');
});

test('Rename: an empty or too long name is refused on the screen; a failed save stays open and says so', function () {
  var s = screens(), t = s.V.openName({ where: 'dock', id: 'emby' });
  t.value = '   '; s.key('down'); s.key('ok');
  assert.strictEqual(s.top(), t); assert.strictEqual(t.error, 'Type a name for the app.'); assert.strictEqual(t.f.zone, 'field');
  s.V.render(t); assert.strictEqual(s.doc.els.appearanceRows.querySelector('.name-error').textContent, 'Type a name for the app.');
  s.doc.getElementById('appNameInput').value = new Array(30).join('n'); s.key('down'); s.key('ok'); assert.strictEqual(t.error, 'Use 24 characters or fewer.');
  var st = fakeStorage(); var f = screens(st); st.setItem = function () { throw new Error('full'); };
  t = f.V.openName({ where: 'dock', id: 'emby' }); t.value = 'Lounge'; f.key('down'); f.key('ok');
  assert.strictEqual(f.top(), t, 'stays open'); assert.strictEqual(t.error, 'Couldn’t save. Nothing changed.');
  assert.strictEqual(t.f.zone, 'buttons', 'focus stays on Save to try again'); assert.strictEqual(f.recs.emby.name, 'Emby');
});

test('Change icon: own icon first, then every mark drawn as on a tile; focus on the current choice; OK applies; Back cancels', function () {
  var s = screens(), t = s.V.openIcon({ where: 'apps', id: 'org.example.kodi' }), n = s.A.choices().length;
  assert.strictEqual(t.id, 'appicon'); assert.strictEqual(t.f.i, 0, 'no override: on App’s own icon');
  s.V.render(t);
  var rows = s.doc.els.appearanceRows, html = rows.innerHTML, choices = rows.querySelectorAll('.app-icon-choice');
  assert.strictEqual(choices.length, n);
  assert.ok(html.indexOf('data-action="appicon.own"') < html.indexOf('data-action="appicon.iptv"'), 'own icon first');
  assert.ok(/appicon\.own[^]*?class="mark monogram">Kodi/.test(html), 'own icon drawn as the tile draws it');
  assert.ok(/appicon\.iptv[^]*?data-mark="iptv"/.test(html), 'each mark drawn');
  assert.ok(/ is-focus/.test(choices[0].className) && / is-applied/.test(choices[0].className));
  assert.ok(/<span>All apps<\/span>/.test(s.doc.els.appearanceBack.innerHTML));
  assert.ok(/Keep this icon/.test(s.doc.els.appearanceDesc.innerHTML));
  s.key('right'); assert.ok(/Use this icon/.test(s.doc.els.appearanceDesc.innerHTML));
  s.key('ok');
  assert.strictEqual(s.stack.length, 1); assert.deepStrictEqual(plain(s.A.get('org.example.kodi')), { mark: 'iptv' });
  assert.deepStrictEqual([s.recs.kodi.markId, s.recs.kodi.mark, s.recs.kodi.id, s.recs.kodi.appId], ['iptv', true, 'org.example.kodi', 'org.example.kodi']);
  t = s.V.openIcon({ where: 'dock', id: 'org.example.kodi' }); assert.strictEqual(t.f.i, 1, 'opens on IPTV, the current choice');
  s.key('back'); assert.deepStrictEqual(plain(s.A.get('org.example.kodi')), { mark: 'iptv' }, 'Back changes nothing');
  t = s.V.openIcon({ where: 'dock', id: 'org.example.kodi' }); s.key('down'); s.key('ok');
  assert.deepStrictEqual(plain(s.A.get('org.example.kodi')), { mark: t.list[5].id }, 'Down moves a row');
  t = s.V.openIcon({ where: 'dock', id: 'org.example.kodi' }); t.f.i = 1; s.key('left'); s.key('left'); assert.strictEqual(t.f.zone, 'back'); s.key('ok');
  assert.strictEqual(s.stack.length, 1, 'OK on Back closes');
  t = s.V.openIcon({ where: 'dock', id: 'org.example.kodi' }); t.f.i = 0; s.key('ok');
  assert.deepStrictEqual(plain(s.A.get('org.example.kodi')), { mark: '' }, 'App’s own icon is saved as an explicit choice');
  assert.deepStrictEqual([s.recs.kodi.markId, s.recs.kodi.mark], ['', false]);
  t = s.V.openIcon({ where: 'dock', id: 'emby' }); assert.strictEqual(t.list[t.f.i].id, 'emby', 'no choice: opens on the package’s mark, the one shown');
  t.f.i = 0; s.key('ok');
  assert.deepStrictEqual([plain(s.A.get('com.mb.android')), s.recs.emby.markId], [{ mark: '' }, ''], 'App’s own icon on an app with a mark by package');
});

test('Change icon: rows past the fourth scroll into view; a failed save stays open and says so', function () {
  var s = screens(), t = s.V.openIcon({ where: 'dock', id: 'emby' }), rows, choices;
  t.list = t.list.concat(t.list.slice(1)); t.key = 'long';   /* "There will be others": twice as many marks */
  t.f.i = t.list.length - 1; s.V.render(t);
  assert.ok(t.top > 0, 'the window scrolled');
  rows = s.doc.els.appearanceRows; choices = rows.querySelectorAll('.app-icon-choice');
  assert.strictEqual(t.top, Math.ceil(t.list.length / 4) - 4, 'the last row shows');
  assert.ok(/ is-off/.test(choices[0].className) === (t.top > 0), 'rows above the window are hidden');
  assert.ok(!/ is-off/.test(choices[t.list.length - 1].className));
  assert.ok(/more-before/.test(rows.querySelector('.app-icon-view').className) === (t.top > 0));
  var st = fakeStorage(), f = screens(st); st.setItem = function () { throw new Error('full'); };
  t = f.V.openIcon({ where: 'dock', id: 'emby' }); f.key('right'); f.key('ok');
  assert.strictEqual(f.top(), t); assert.strictEqual(t.error, 'Couldn’t save. Nothing changed.'); assert.strictEqual(f.recs.emby.markId, 'emby');
});

test('Change icon: every choice’s name fits its tile whole ("App’s own icon" was cut to "App’s ow…")', function () {
  var A = load(), css = read('web/home-extras.css'), rule = /\n\.app-icon-name\{([^}]*)\}/.exec(css), tile = /\.setting-choice\.app-icon-choice\{[^}]*width:(\d+)px/.exec(css);
  function px(prop) { var m = new RegExp('(?:^|;)' + prop + ':(\\d+)px').exec(rule[1]); assert.ok(m, '.app-icon-name ' + prop); return +m[1]; }
  assert.ok(rule && tile, 'the name and tile rules in home-extras.css');
  assert.ok(/\.setting-choice\.app-icon-choice>\*\+\*\{margin-left:0\}/.test(css), 'upstream’s 16px gap between a row’s children is not taken off the name');
  var width = +tile[1] - px('left') - px('right');
  assert.ok(width >= A.LABEL_LINE * A.LABEL_PX, 'the name box (' + width + 'px) holds LABEL_LINE characters');
  assert.ok(px('height') >= A.LABEL_LINES * px('line-height'), 'and LABEL_LINES lines');
  assert.ok(px('top') + px('height') <= 150, 'inside the 150px tile');
  function lines(name) {
    var out = [], cur = '';
    name.split(' ').forEach(function (w) {
      assert.ok(w.length <= A.LABEL_LINE, '"' + w + '" fits a line');
      if (cur && (cur + ' ' + w).length > A.LABEL_LINE) { out.push(cur); cur = w; } else cur = cur ? cur + ' ' + w : w;
    });
    return out.concat(cur);
  }
  A.choices().forEach(function (c) { assert.ok(lines(c.name).length <= A.LABEL_LINES, c.name + ': ' + lines(c.name).join(' / ')); });
  assert.deepStrictEqual(lines('App’s own icon'), ['App’s own', 'icon']);
});

test('ES5 only: no let/const/class/arrow/template in web/app-edit.js or web/app-marks.js', function () {
  [SOURCE, MARKS].forEach(function (src) {
    var code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
    assert.ok(!/\b(let|const|class)\s/.test(code), 'let/const/class');
    assert.ok(!/=>/.test(code), 'arrow function');
    assert.ok(code.indexOf('`') < 0, 'template literal');
  });
});

/* ---------- The patched web/main.js ---------- */

function slice(from, to) {
  var a = MAIN.indexOf(from), b = MAIN.indexOf(to, a + 1);
  assert.ok(a > 0 && b > a, 'web/main.js has ' + from.trim().slice(0, 50));
  return MAIN.slice(a, b);
}
/* The saved layout, start-up and the installed-apps list, run from main.js with a fake page. */
function mainApps(st, entries) {
  var A = load(st), ctx;
  var code = slice('  var PRIMARY_APPS = [', '  var WG = window.CleanFrameWallpaperGroups')
    + slice('  function app(id) {', '  function wall(id)')
    + slice('  function safeId(id) {', '  function clearSession()')
    + slice('  function write(c, label) {', '  function errText(')
    + /  function without\(list, id\)[^\n]*\n/.exec(MAIN)[0]
    + slice('  function refreshCatalog() {', '  /* ---------- Navigation targets');
  ctx = { AE: A, KEY: 'cleanframe.home.v1', window: { FrameIconMetrics: metrics() }, localStorage: st, WG: { resolve: function () { return 'misty'; }, random: function (w, id) { return id; } }, WALLS: [],
    LOOK_VALUES: { bg: ['photo'], layout: ['dock'], apps: ['both'], controls: ['both'], focus: ['glass'], material: ['soft'], font: ['manrope'] },
    MODES: [{ id: 'fixed' }], CAP: 9, storageOk: true, S: { writes: 0, failed: 0, undo: [], removedAt: {} }, catalog: { loading: false, request: 0 },
    resetRotation: function () {}, log: function () {},
    pick: function (v, l, f) { return f; }, lookOf: function () { return {}; }, next: function (h, r) { return { home: h, recent: r }; },
    top: function () { return { id: 'home', f: {} }; }, norm: function () {}, render: function () {},
    perform: function (title, run) { ctx.performed = []; run(function (err) { ctx.performed.push(err); }); },
    FrameDevice: { apps: function (done) { if (ctx.appsError) done(ctx.appsError); else done(null, ctx.entries); },
      uninstall: function (pkg, done) { ctx.asked = pkg; done(ctx.refused || null); } },
    entries: entries };
  vm.createContext(ctx);
  vm.runInContext(code + '\nthis.app = app; this.serialise = serialise; this.initialise = initialise; this.refreshCatalog = refreshCatalog; this.uninstallApp = uninstallApp; this.uninstallReturned = uninstallReturned;'
    + ' this.APPS = APPS; this.alpha = function () { return ALPHA; }; this.AE = AE;', ctx, { filename: 'main.js (apps)' });
  return ctx;
}
var ENTRIES = [{ appId: 'com.mb.android', name: 'Emby for Android', brand: 'emby', icon: '' },
  { appId: 'org.example.kodi', name: 'Kodi', brand: '', icon: '/icons/org.example.kodi.png' },
  { appId: 'org.example.pluto', name: 'Pluto TV', brand: '', icon: '/icons/org.example.pluto.png' },
  { appId: 'com.google.android.youtube.tv', name: 'YouTube', brand: 'youtube', icon: '' }];

test('patched main.js: start-up and the apps list show the overrides; the saved layout keeps the OWN names', function () {
  var st = fakeStorage(), A0 = load(st);
  A0.setName('com.mb.android', 'Films'); A0.setName('org.example.kodi', 'Zz Sports'); A0.setMark('org.example.kodi', 'iptv'); A0.setMark('org.example.pluto', 'iptv');
  A0.setName('com.uninstalled.app', 'Ghost');
  st.data['cleanframe.home.v1'] = JSON.stringify({ home: ['emby', 'org.example.kodi', 'youtube'], appRecords: [{ id: 'org.example.kodi', appId: 'org.example.kodi', name: 'Kodi', brand: '', icon: '' }] });
  var before = st.data['cleanframe.home.v1'];
  var m = mainApps(st, ENTRIES);
  m.initialise();
  var saved = JSON.parse(st.data['cleanframe.home.v1']);
  assert.deepStrictEqual(saved.home, ['emby', 'org.example.kodi', 'youtube'], 'Home order unchanged');
  assert.deepStrictEqual(saved.appRecords.map(function (r) { return r.name; }), ['Kodi'], 'the layout saves the own name, never an override');
  assert.strictEqual(JSON.stringify(saved.appRecords[0]), JSON.stringify(JSON.parse(before).appRecords[0]), 'the record is written back as it was');
  assert.deepStrictEqual([m.app('emby').name, m.app('org.example.kodi').name, m.app('org.example.kodi').markId, m.app('org.example.kodi').mark], ['Films', 'Zz Sports', 'iptv', true]);
  m.refreshCatalog();
  var alpha = plain(m.alpha());
  assert.deepStrictEqual(alpha, ['emby', 'org.example.pluto', 'youtube', 'org.example.kodi'], 'All apps sorted by the shown name: ' + alpha.join());
  assert.deepStrictEqual(alpha.map(function (id) { return m.app(id).name; }), ['Films', 'Pluto TV', 'YouTube', 'Zz Sports']);
  assert.strictEqual(m.app('emby').own.name, 'Emby', 'a default app keeps its own name (not the package label)');
  assert.deepStrictEqual([m.app('org.example.kodi').markId, m.app('org.example.pluto').markId], ['iptv', 'iptv']);
  assert.deepStrictEqual([m.app('org.example.kodi').appId, m.app('org.example.pluto').appId], ['org.example.kodi', 'org.example.pluto'], 'two apps with the IPTV mark stay two apps');
  m.refreshCatalog();
  assert.strictEqual(m.app('emby').own.name, 'Emby', 'a second refresh does not take the override as its own name');
  var out = m.serialise({ home: ['emby', 'org.example.kodi', 'org.example.pluto'], recent: [] });
  assert.deepStrictEqual(plain(out.appRecords.map(function (r) { return r.id + '=' + r.name + '|' + r.brand; })), ['org.example.kodi=Kodi|', 'org.example.pluto=Pluto TV|']);
  assert.ok(!m.app('com.uninstalled.app'), 'an override for an uninstalled app adds nothing');
});

/* ---------- Starter Home ---------- */

function marked(id, markId) { return { id: id, markId: markId }; }

test('starter: marked apps only, one per mark, in library order, at most 5; fewer if fewer; none if none', function () {
  var A = load();
  assert.strictEqual(A.STARTER, 5);
  var all = [marked('org.xbmc.kodi', 'kodi'), marked('org.example.notes', ''), marked('com.netflix.ninja', 'netflix'), marked('youtube', 'youtube'),
    marked('org.jellyfin.androidtv', 'jellyfin'), marked('spotify', 'spotify'), marked('emby', 'emby'), marked('com.plexapp.android', 'plex')];
  assert.deepStrictEqual(plain(A.starter(all, A.STARTER)), ['emby', 'youtube', 'spotify', 'com.netflix.ninja', 'org.jellyfin.androidtv']);
  assert.deepStrictEqual(plain(A.starter([marked('org.xbmc.kodi', 'kodi'), marked('org.example.notes', ''), marked('youtube', 'youtube')], 5)), ['youtube', 'org.xbmc.kodi']);
  assert.deepStrictEqual(plain(A.starter([marked('org.example.notes', ''), marked('x', 'nosuchmark'), null], 5)), []);
  assert.deepStrictEqual(plain(A.starter([marked('youtube', 'youtube'), marked('com.google.android.youtube', 'youtube')], 5)), ['youtube'], 'one app per mark');
  assert.deepStrictEqual(plain(A.starter(all, 0)).length, 5, 'no max: AE.STARTER');
  assert.deepStrictEqual(plain(A.starter('junk', 5)), []);
});

var STICK = [{ appId: 'tv.emby.embyatv', name: 'Emby for Android TV', brand: 'emby', icon: '' },
  { appId: 'org.example.notes', name: 'Aa Notes', brand: '', icon: '' },
  { appId: 'org.xbmc.kodi', name: 'Kodi', brand: '', icon: '' },
  { appId: 'com.netflix.ninja', name: 'Netflix', brand: 'netflix', icon: '' },
  { appId: 'org.jellyfin.androidtv', name: 'Jellyfin', brand: '', icon: '' },
  { appId: 'com.google.android.youtube.tv', name: 'YouTube', brand: 'youtube', icon: '' },
  { appId: 'com.spotify.tv.android', name: 'Spotify', brand: 'spotify', icon: '' }];
function homeOf(st) { return JSON.parse(st.data['cleanframe.home.v1']).home; }

/* ALSO (web/device.js): one brand, two installed packages. */
test('patched main.js: two packages of one brand both show and each opens its own; the same package twice is one', function () {
  var both = [{ appId: 'com.mb.android', name: 'Emby for Android', brand: 'emby', icon: '' }, { appId: 'tv.emby.embyatv', name: 'Emby for Android TV', brand: 'emby', icon: '' },
    { appId: 'com.google.android.youtube.tv', name: 'YouTube', brand: 'youtube', icon: '' }, { appId: 'com.rockchip.android.youtube.tv', name: 'YouTube (Rockchip)', brand: 'youtube', icon: '' },
    { appId: 'com.mb.android', name: 'Emby again', brand: 'emby', icon: '' }];
  var st = fakeStorage({ 'cleanframe.home.v1': JSON.stringify({ home: ['emby'] }) }), m = mainApps(st, both);
  m.initialise(); m.refreshCatalog();
  assert.deepStrictEqual(plain(m.alpha()).slice().sort(), ['com.rockchip.android.youtube.tv', 'emby', 'tv.emby.embyatv', 'youtube']);
  assert.strictEqual(m.app('emby').appId, 'com.mb.android');
  assert.strictEqual(m.app('tv.emby.embyatv').appId, 'tv.emby.embyatv');
  assert.strictEqual(m.app('youtube').appId, 'com.google.android.youtube.tv');
  assert.strictEqual(m.app('com.rockchip.android.youtube.tv').appId, 'com.rockchip.android.youtube.tv');
  assert.strictEqual(m.app('tv.emby.embyatv').name, 'Emby for Android TV', 'its own name');
  assert.strictEqual(m.app('tv.emby.embyatv').brand, 'emby', 'still its brand\'s mark');
  m.refreshCatalog();
  assert.strictEqual(m.alpha().length, 4, 'a second read adds nothing');
  assert.strictEqual(m.app('emby').appId, 'com.mb.android', 'and moves nothing');
});

/* Uninstall on the long-press menu: only the installed list says what happened. */
function uninstallStick(home, recent, can) {
  var st = fakeStorage({ 'cleanframe.home.v1': JSON.stringify({ home: ['emby'] }) }), m = mainApps(st, ENTRIES.slice());
  m.initialise(); m.refreshCatalog();
  m.S.c.home = home.slice(); m.S.c.recent = (recent || []).slice();
  return { st: st, m: m };
}
function drop(m, pkg) { m.entries = m.entries.filter(function (e) { return e.appId !== pkg; }); }

test('patched main.js: Uninstall opens the dialog and changes nothing; Cancel there changes nothing either', function () {
  var u = uninstallStick(['emby', 'org.example.kodi'], ['org.example.pluto']), m = u.m, a = m.app('org.example.kodi');
  m.AE.setName('org.example.kodi', 'Sports');
  m.uninstallApp(a, 'org.example.kodi');
  assert.strictEqual(m.asked, 'org.example.kodi');
  assert.deepStrictEqual(plain(m.performed), [null]);
  assert.deepStrictEqual(plain(m.S.c.home), ['emby', 'org.example.kodi'], 'nothing yet');
  var writes = m.S.writes;
  m.uninstallReturned();   // Home is back; the app is still listed: Cancel, or still going
  assert.deepStrictEqual(plain(m.S.c.home), ['emby', 'org.example.kodi']);
  assert.strictEqual(m.AE.get('org.example.kodi').name, 'Sports');
  assert.strictEqual(m.S.writes, writes, 'nothing saved');
});

test('patched main.js: Home coming back reads the list only while an uninstall is waiting', function () {
  var u = uninstallStick(['emby']), m = u.m, reads = 0, apps = m.FrameDevice.apps;
  m.FrameDevice.apps = function (d) { reads++; apps(d); };
  m.uninstallReturned();
  assert.strictEqual(reads, 0, 'nothing waiting: no read');
  m.uninstallApp(m.app('org.example.kodi'), 'org.example.kodi');
  m.uninstallReturned(); assert.strictEqual(reads, 1);
  drop(m, 'org.example.kodi'); m.uninstallReturned(); assert.strictEqual(reads, 2);
  m.uninstallReturned(); assert.strictEqual(reads, 2, 'settled: no read');
});

test('patched main.js: really gone: off Home and Recently removed, out of the undo history, override dropped, saved', function () {
  var u = uninstallStick(['emby', 'org.example.kodi', 'youtube'], ['org.example.kodi', 'org.example.pluto']), m = u.m;
  m.AE.setName('org.example.kodi', 'Sports'); m.AE.setMark('org.example.kodi', 'iptv'); m.AE.setName('org.example.pluto', 'Other');
  m.S.undo = [{ label: 'Add Kodi', snap: { home: ['emby', 'youtube'], recent: ['org.example.kodi'] } }, { label: 'Move', snap: { home: ['org.example.kodi', 'emby', 'youtube'], recent: [] } }, { label: 'Other', snap: { home: ['emby'], recent: ['org.example.pluto'] } }];
  m.S.removedAt['org.example.kodi'] = 1;
  m.uninstallApp(m.app('org.example.kodi'), 'org.example.kodi');
  drop(m, 'org.example.kodi');
  m.uninstallReturned();
  assert.deepStrictEqual(plain(m.S.c.home), ['emby', 'youtube']);
  assert.deepStrictEqual(plain(m.S.c.recent), ['org.example.pluto'], 'not offered back in Recently removed');
  assert.deepStrictEqual(JSON.parse(u.st.data['cleanframe.home.v1']).home, ['emby', 'youtube'], 'saved');
  assert.deepStrictEqual(plain(m.AE.get('org.example.kodi')), {}, 'override dropped');
  assert.strictEqual(JSON.parse(u.st.data[KEY]).hasOwnProperty('org.example.kodi'), false, 'from the store too');
  assert.strictEqual(m.AE.get('org.example.pluto').name, 'Other', 'another app\'s stays');
  assert.deepStrictEqual(plain(m.S.undo).map(function (x) { return x.label; }), ['Other'], 'no undo brings it back');
  assert.strictEqual(m.S.removedAt.hasOwnProperty('org.example.kodi'), false);
  assert.strictEqual(m.S.note, 'Kodi uninstalled.');
  assert.ok(plain(m.alpha()).indexOf('org.example.kodi') < 0, 'and off All apps');
});

test('patched main.js: uninstalled from All apps (not on Home) drops the override and writes no layout', function () {
  var u = uninstallStick(['emby']), m = u.m, w;
  m.AE.setName('org.example.kodi', 'Sports');
  m.uninstallApp(m.app('org.example.kodi'), 'org.example.kodi');
  w = m.S.writes; drop(m, 'org.example.kodi'); m.uninstallReturned();
  assert.strictEqual(m.S.writes, w, 'nothing on Home changed');
  assert.deepStrictEqual(plain(m.AE.get('org.example.kodi')), {});
});

test('patched main.js: the last app on Home stays when uninstalled (upstream: Home needs one app); its override goes', function () {
  var u = uninstallStick(['org.example.kodi']), m = u.m;
  m.AE.setName('org.example.kodi', 'Sports');
  m.uninstallApp(m.app('org.example.kodi'), 'org.example.kodi');
  drop(m, 'org.example.kodi'); m.uninstallReturned();
  assert.deepStrictEqual(plain(m.S.c.home), ['org.example.kodi'], 'never an empty Home');
  assert.deepStrictEqual(plain(m.AE.get('org.example.kodi')), {});
  m.uninstallReturned();
  assert.deepStrictEqual(plain(m.S.c.home), ['org.example.kodi'], 'settled once');
});

test('patched main.js: a refusal keeps nothing waiting; a failed read or a failed save tries again next time', function () {
  var u = uninstallStick(['emby', 'org.example.kodi']), m = u.m, reads = 0, apps = m.FrameDevice.apps;
  m.refused = { message: 'The TV could not open this screen.', detail: 'No.' };
  m.uninstallApp(m.app('org.example.kodi'), 'org.example.kodi');
  assert.strictEqual(m.performed[0].detail, 'No.', 'the reason goes to perform, which shows it');
  m.FrameDevice.apps = function (d) { reads++; apps(d); };
  m.uninstallReturned(); assert.strictEqual(reads, 0, 'refused: nothing waiting');
  m.refused = null;
  m.uninstallApp(m.app('org.example.kodi'), 'org.example.kodi');
  drop(m, 'org.example.kodi');
  m.appsError = { message: 'x' }; m.uninstallReturned();
  assert.deepStrictEqual(plain(m.S.c.home), ['emby', 'org.example.kodi'], 'no list, no conclusion');
  m.appsError = null;
  var set = u.st.setItem, fail = true;
  u.st.setItem = function (k, v) { if (fail && k === 'cleanframe.home.v1') throw new Error('full'); return set.call(u.st, k, v); };
  m.uninstallReturned();
  assert.deepStrictEqual(plain(m.S.c.home), ['emby', 'org.example.kodi'], 'the save failed');
  fail = false; m.uninstallReturned();
  assert.deepStrictEqual(plain(m.S.c.home), ['emby']);
});

test('patched main.js: an app that vanished without Uninstall stays on Home as before', function () {
  var u = uninstallStick(['emby', 'org.example.kodi']), m = u.m;
  m.uninstallApp(m.app('org.example.pluto'), 'org.example.pluto');
  drop(m, 'org.example.kodi'); m.refreshCatalog();
  assert.deepStrictEqual(plain(m.S.c.home), ['emby', 'org.example.kodi']);
});

test('forget: drops one package\'s whole entry, tells listeners, leaves the rest; nothing to drop writes nothing; a failed save changes nothing', function () {
  var st = fakeStorage(), A = load(st), told = [];
  A.setName('a.b', 'One'); A.setMark('a.b', 'iptv'); A.setName('c.d', 'Two');
  A.onChange(function (p) { told.push(p); });
  var r = A.rev(), n = st.writes.length;
  assert.strictEqual(A.forget('e.f').ok, true); assert.strictEqual(st.writes.length, n); assert.strictEqual(A.rev(), r); assert.deepStrictEqual(told, []);
  assert.strictEqual(A.forget('__proto__').ok, false);
  assert.strictEqual(A.forget('a.b').ok, true);
  assert.deepStrictEqual(Object.keys(JSON.parse(st.data[KEY])), ['c.d']);
  assert.deepStrictEqual(plain(A.get('a.b')), {}); assert.deepStrictEqual(told, ['a.b']); assert.ok(A.rev() > r);
  var bad = fakeStorage({ 'cleanframe.tv.apps.v1': '{"a.b":{"name":"One"}}' }, true), B = load(bad);
  assert.deepStrictEqual(plain(B.forget('a.b')), { ok: false, reason: B.SAVE_FAILED });
  assert.strictEqual(B.get('a.b').name, 'One');
});

test('patched main.js: a new stick gets its 5 first marked apps on Home, never an uninstalled Rumble', function () {
  var st = fakeStorage(), m = mainApps(st, STICK);
  m.initialise();
  assert.deepStrictEqual(homeOf(st), ['emby', 'youtube', 'spotify', 'rumble'], 'before the list arrives: upstream defaults');
  m.refreshCatalog();
  assert.deepStrictEqual(homeOf(st), ['emby', 'youtube', 'spotify', 'com.netflix.ninja', 'org.jellyfin.androidtv']);
  assert.strictEqual(JSON.parse(st.data['cleanframe.home.v1']).lastApp, 'emby');
  assert.deepStrictEqual([m.S.sel, m.S.selIdx, m.S.starter], ['emby', 0, false]);
  assert.strictEqual(m.app('emby').appId, 'tv.emby.embyatv', 'the dock tile opens the installed package');
  m.S.c.home = ['emby']; m.refreshCatalog();
  assert.deepStrictEqual(m.S.c.home, ['emby'], 'once only');
});

test('patched main.js: fewer marked apps, fewer on Home; none, the first app by name', function () {
  var st = fakeStorage(), m = mainApps(st, STICK.slice(1, 3));
  m.initialise(); m.refreshCatalog();
  assert.deepStrictEqual(homeOf(st), ['org.xbmc.kodi'], 'one marked app: one on Home');
  st = fakeStorage(); m = mainApps(st, [STICK[1], { appId: 'org.example.zed', name: 'Zed', brand: '', icon: '' }]);
  m.initialise(); m.refreshCatalog();
  assert.deepStrictEqual(homeOf(st), ['org.example.notes'], 'no marked app: the first by name');
});

test('patched main.js: a saved or already changed layout is never replaced', function () {
  var st = fakeStorage(), m;
  st.data['cleanframe.home.v1'] = JSON.stringify({ home: ['emby', 'youtube', 'spotify', 'rumble'] });
  m = mainApps(st, STICK); m.initialise(); m.refreshCatalog();
  assert.deepStrictEqual(homeOf(st), ['emby', 'youtube', 'spotify', 'rumble'], 'a stick already set up keeps its Home');
  st = fakeStorage(); m = mainApps(st, STICK); m.initialise();
  m.S.c.home = ['youtube'];
  m.refreshCatalog();
  assert.deepStrictEqual(m.S.c.home, ['youtube'], 'changed before the list arrived: kept');
  st = fakeStorage(); m = mainApps(st, []); m.initialise(); m.refreshCatalog();
  assert.deepStrictEqual(homeOf(st), ['emby', 'youtube', 'spotify', 'rumble'], 'an empty list changes nothing');
});

test('patched main.js: a tile draws its override mark; App’s own icon draws the own one again', function () {
  var code = slice('  function appMark(a, w, h, size) {', '  function smallMark(a)'), ctx = { maskSupport: true, S: { c: { bg: 'photo' } },
    markHtml: function (id) { return 'mark:' + id; }, esc: function (s) { return s; } };
  vm.createContext(ctx); vm.runInContext(code + '\nthis.appMark = appMark;', ctx);
  assert.strictEqual(ctx.appMark({ id: 'emby', brand: 'emby', mark: true, markId: 'iptv', name: 'Emby' }, 120, 72, 58), 'mark:iptv');
  assert.strictEqual(ctx.appMark({ id: 'emby', brand: 'emby', mark: true, markId: '', name: 'Emby' }, 120, 72, 58), 'mark:emby');
  assert.ok(/monogram/.test(ctx.appMark({ id: 'k', brand: '', mark: false, markId: '', name: 'Kodi' }, 120, 72, 58)));
});

test('patched main.js: an installed app with a mark by package is drawn with it; its id, launch target and Home place stay', function () {
  var st = fakeStorage();
  st.data['cleanframe.home.v1'] = JSON.stringify({ home: ['emby', 'org.jellyfin.androidtv', 'youtube'], appRecords: [{ id: 'org.jellyfin.androidtv', appId: 'org.jellyfin.androidtv', name: 'Jellyfin', brand: '', icon: '' }] });
  var m = mainApps(st, ENTRIES.concat([{ appId: 'org.jellyfin.androidtv', name: 'Jellyfin', brand: '', icon: '/icons/org.jellyfin.androidtv.png' }]));
  m.initialise(); m.refreshCatalog();
  var jf = m.app('org.jellyfin.androidtv');
  assert.deepStrictEqual([jf.markId, jf.mark, jf.id, jf.appId], ['jellyfin', true, 'org.jellyfin.androidtv', 'org.jellyfin.androidtv']);
  assert.deepStrictEqual([m.app('org.example.kodi').markId, m.app('org.example.kodi').mark], ['', false], 'no mark for its package: own icon');
  assert.deepStrictEqual(JSON.parse(st.data['cleanframe.home.v1']).home, ['emby', 'org.jellyfin.androidtv', 'youtube']);
  var code = slice('  function appMark(a, w, h, size) {', '  function smallMark(a)'), ctx = { maskSupport: true, S: { c: { bg: 'photo' } },
    markHtml: function (id) { return 'mark:' + id; }, esc: function (s) { return s; } };
  vm.createContext(ctx); vm.runInContext(code + '\nthis.appMark = appMark;', ctx);
  assert.strictEqual(ctx.appMark(jf, 120, 72, 58), 'mark:jellyfin');
});

test('patched main.js: every cached tile redraws when a name or mark changes', function () {
  var A = load(), code = slice('  function setContent(el, sig, renderContent) {', '  function setHTML('), drawn = 0;
  var ctx = { AE: A, catalog: { revision: 0 }, fontRevision: 0 };
  vm.createContext(ctx); vm.runInContext(code + '\nthis.setContent = setContent;', ctx);
  var el = { querySelectorAll: function () { return []; } };
  function draw() { ctx.setContent(el, 'emby|36', function () { drawn++; return 'x'; }); }
  draw(); draw(); assert.strictEqual(drawn, 1);
  A.setMark('com.mb.android', 'iptv'); draw(); assert.strictEqual(drawn, 2, 'redrawn after a change');
  A.setMark('com.mb.android', 'nosuchmark'); draw(); assert.strictEqual(drawn, 2, 'a refused change redraws nothing');
});

test('patched main.js: a change re-dresses every app and All apps keeps its focus on the same app', function () {
  var A = load(), code = slice('  function appsChanged() {', "  function openSleepMenu()");
  var recs = [A.dress({ id: 'a', appId: 'com.a', name: 'Alpha' }), A.dress({ id: 'b', appId: 'com.b', name: 'Beta' }), A.dress({ id: 'c', appId: 'com.c', name: 'Gamma' })];
  var ctx = { AE: A, APPS: recs, ALPHA: ['a', 'b', 'c'], S: { stack: [{ id: 'home', f: {} }, { id: 'apps', f: { i: 0 } }, { id: 'appname', f: {} }] }, rendered: 0,
    app: function (id) { return recs.filter(function (r) { return r.id === id; })[0]; } };
  ctx.render = function () { ctx.rendered++; };
  vm.createContext(ctx); vm.runInContext(code + '\nthis.appsChanged = appsChanged;', ctx);
  A.setName('com.a', 'Zulu');
  ctx.appsChanged();
  assert.deepStrictEqual(plain(ctx.ALPHA), ['b', 'c', 'a']); assert.strictEqual(ctx.S.stack[1].f.i, 2, 'focus follows Alpha, now Zulu');
  assert.strictEqual(recs[0].name, 'Zulu'); assert.strictEqual(ctx.rendered, 1);
  assert.ok(MAIN.indexOf('  AE.onChange(appsChanged);') > 0, 'run on every change');
});

test('patched main.js: the menu opens Rename and Change icon; the screens are settings panels with their keys', function () {
  var code = slice('  function appMenuAct(target, act) {', '  /* Rename / Change icon saved'), log = [];
  var ctx = { app: function (id) { return id === 'emby' ? { name: 'Emby', appId: 'com.mb.android' } : null; }, S: { c: { home: ['emby'] } }, begin: function () {},
    AEV: { openName: function (t) { log.push('name:' + t.where + ':' + t.id); }, openIcon: function (t) { log.push('icon:' + t.where + ':' + t.id); } } };
  vm.createContext(ctx); vm.runInContext(code + '\nthis.appMenuAct = appMenuAct;', ctx);
  ctx.appMenuAct({ where: 'dock', id: 'emby' }, 'rename'); ctx.appMenuAct({ where: 'apps', id: 'emby' }, 'icon'); ctx.appMenuAct({ where: 'dock', id: 'gone' }, 'icon');
  assert.deepStrictEqual(log, ['name:dock:emby', 'icon:apps:emby']);
  assert.ok(MAIN.indexOf('function isSettingsDetail(t) { return t.id === \'appearance\' || t.id === \'wallpaper\' || PGV.owns(t) || AEV.owns(t); }') > 0);
  assert.ok(MAIN.indexOf('V.appname = AEV.V.appname; V.appicon = AEV.V.appicon;') > 0);
  assert.ok(MAIN.indexOf('    if (PGV.render(t) || AEV.render(t)) return;') > 0);
  assert.ok(MAIN.indexOf('if (PGV.inputKey(ev) || AEV.inputKey(ev)) { heldKeys = {}; return; }') > 0, 'typing a name keeps its keys');
  assert.ok(MAIN.indexOf('  var AE = window.CleanFrameAppEdit;') > 0 && MAIN.indexOf('  var AEV = AE.views({') > 0);
  assert.ok(MAIN.indexOf('out.appEdits = AE.all();') > 0, 'ShoreQA.state() reports the overrides');
});

test('index.html loads app-marks.js then app-edit.js after home-extras.js and before main.js', function () {
  var h = INDEX.indexOf('<script src="home-extras.js"></script>'), m = INDEX.indexOf('<script src="app-marks.js"></script>'),
    e = INDEX.indexOf('<script src="app-edit.js"></script>'), main = INDEX.indexOf('<script src="main.js"></script>'),
    metricsAt = INDEX.indexOf('<script src="assets/icons/metrics.js"></script>');
  assert.ok(metricsAt > 0 && h > metricsAt && m > h && e > m && main > e);
});

var failed = 0;
cases.forEach(function (c) {
  try { c[1](); console.log('PASS ' + c[0]); }
  catch (e) { failed++; console.log('FAIL ' + c[0] + ': ' + String((e && e.message) || e).split('\n')[0]); }
});
if (failed) { console.log('APP_EDIT_TEST_FAIL ' + failed); process.exit(1); }
console.log('APP_EDIT_TEST_OK ' + cases.length);

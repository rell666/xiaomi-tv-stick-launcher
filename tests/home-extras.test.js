/* Unit tests for web/home-extras.js (window.CleanFrameExtras): the clock options' store (own
   localStorage key, bad values fall back, a failed save changes nothing), the clock text and redraw
   step, the Settings summary and page sections. Also checks the patched web/main.js
   (tools/android-patch.py): with focus on the top-right controls no dock app or list row keeps the
   "remembered" marker, the clock text and timer come from CleanFrameExtras, Settings lists Clock,
   and index.html loads home-extras.js before main.js. Long press: X.Hold with fake timers, the menu's
   buttons (Uninstall only when the TV says so), and the patched key handlers and menu actions replayed with a fake page. Screen: the
   Settings > Screen pages (link rows, Back to Screen), the dim layer and its first-key rule with fake
   timers and a fake page, playback holding off the dim, the policy handed to FrameDevice, and the
   Sleep button's menu (Sleep after shared with Settings > Screen, Sleep now and its cancel, the
   device's answer) and its patched hooks.
   No dependencies: node:assert + node:vm and a tiny runner. Run from the repo root:
     node tests/home-extras.test.js
   Prints one line per case and exits non-zero on any failure. */
'use strict';
var assert = require('node:assert');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var WEB = path.join(__dirname, '..', 'web');
var SOURCE = fs.readFileSync(path.join(WEB, 'home-extras.js'), 'utf8');
var MAIN = fs.readFileSync(path.join(WEB, 'main.js'), 'utf8');
var INDEX = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');

function fakeStorage(initial, failWrites) {
  var data = {}, k;
  for (k in initial || {}) data[k] = initial[k];
  return { data: data, getItem: function (key) { return Object.prototype.hasOwnProperty.call(data, key) ? data[key] : null; },
    setItem: function (key, value) { if (failWrites) throw new Error('QuotaExceededError'); data[key] = String(value); } };
}
function load(storage) {
  var ctx = { window: { localStorage: storage === undefined ? fakeStorage() : storage } };
  vm.createContext(ctx);
  vm.runInContext(SOURCE, ctx, { filename: 'home-extras.js' });
  return ctx.window.CleanFrameExtras;
}
// JSON round trip: objects made inside the vm context have another Object.prototype.
function plain(x) { return JSON.parse(JSON.stringify(x)); }
function at(h, m, s) { return new Date(2026, 10, 1, h, m, s || 0); }

var cases = [];
function test(name, fn) { cases.push([name, fn]); }

test('defaults match the clock before this change: 12-hour, no AM/PM, no seconds, shown', function () {
  var X = load();
  assert.deepStrictEqual(plain(X.all()), { clockFormat: '12', clockSeconds: 'off', clockShow: 'on', screenDim: '5', screenSleep: '30' });
  assert.strictEqual(X.clockText(at(19, 5)), '7:05');
  assert.strictEqual(X.clockText(at(0, 0)), '12:00');
  assert.strictEqual(X.clockText(at(12, 30)), '12:30');
  assert.strictEqual(X.clockStep(), 60000);
});

test('formats: 12-hour with AM/PM, 24-hour with a leading zero, seconds', function () {
  var X = load();
  function txt(p, d) { return X.clockText(d, X.clean(p)); }
  assert.strictEqual(txt({ clockFormat: '12a' }, at(0, 7)), '12:07 AM');
  assert.strictEqual(txt({ clockFormat: '12a' }, at(11, 59)), '11:59 AM');
  assert.strictEqual(txt({ clockFormat: '12a' }, at(12, 0)), '12:00 PM');
  assert.strictEqual(txt({ clockFormat: '12a' }, at(23, 1)), '11:01 PM');
  assert.strictEqual(txt({ clockFormat: '24' }, at(7, 5)), '07:05');
  assert.strictEqual(txt({ clockFormat: '24' }, at(0, 0)), '00:00');
  assert.strictEqual(txt({ clockFormat: '24' }, at(23, 59)), '23:59');
  assert.strictEqual(txt({ clockFormat: '24', clockSeconds: 'on' }, at(9, 3, 7)), '09:03:07');
  assert.strictEqual(txt({ clockFormat: '12a', clockSeconds: 'on' }, at(21, 3, 59)), '9:03:59 PM');
  assert.strictEqual(txt({ clockShow: 'off', clockSeconds: 'on' }, at(9, 3)), '', 'hidden clock has no text');
});

test('redraw step: every second only while seconds are shown', function () {
  var X = load();
  assert.strictEqual(X.clockStep(X.clean({ clockSeconds: 'on' })), 1000);
  assert.strictEqual(X.clockStep(X.clean({ clockSeconds: 'on', clockShow: 'off' })), 60000);
  assert.strictEqual(X.clockStep(X.clean({ clockSeconds: 'off' })), 60000);
});

test('set saves to its own key, notifies, and survives a reload; upstream key untouched', function () {
  var st = fakeStorage({ 'cleanframe.home.v1': '{"version":2}' }), X = load(st), seen = [];
  X.onChange(function (k, v) { seen.push(k + '=' + v); });
  assert.strictEqual(X.set('clockFormat', '24'), true);
  assert.strictEqual(X.set('clockSeconds', 'on'), true);
  assert.deepStrictEqual(seen, ['clockFormat=24', 'clockSeconds=on']);
  assert.strictEqual(st.data['cleanframe.home.v1'], '{"version":2}');
  assert.deepStrictEqual(JSON.parse(st.data[X.KEY]), { clockFormat: '24', clockSeconds: 'on', clockShow: 'on', screenDim: '5', screenSleep: '30' });
  assert.strictEqual(load(st).get('clockFormat'), '24', 'read back by a new page');
});

test('set refuses an unknown key or value and changes nothing', function () {
  var st = fakeStorage(), X = load(st), seen = 0;
  X.onChange(function () { seen++; });
  assert.strictEqual(X.set('clockFormat', '13'), false);
  assert.strictEqual(X.set('nope', 'on'), false);
  assert.strictEqual(X.set('__proto__', 'on'), false);
  assert.strictEqual(seen, 0);
  assert.strictEqual(st.data[X.KEY], undefined);
  assert.strictEqual(X.get('clockFormat'), '12');
});

test('a failed save (storage full or missing) keeps the old value and does not notify', function () {
  var X = load(fakeStorage({}, true)), seen = 0;
  X.onChange(function () { seen++; });
  assert.strictEqual(X.set('clockFormat', '24'), false);
  assert.strictEqual(X.get('clockFormat'), '12');
  assert.strictEqual(seen, 0);
  var Y = load(null);
  assert.strictEqual(Y.set('clockFormat', '24'), false);
  assert.strictEqual(Y.get('clockFormat'), '12');
});

test('saved junk falls back per key; unparseable JSON falls back to defaults', function () {
  var X = load(fakeStorage({ 'cleanframe.tv.extras.v1': '{"clockFormat":"24","clockSeconds":"maybe","clockShow":7,"extra":1}' }));
  assert.deepStrictEqual(plain(X.all()), { clockFormat: '24', clockSeconds: 'off', clockShow: 'on', screenDim: '5', screenSleep: '30' });
  X = load(fakeStorage({ 'cleanframe.tv.extras.v1': '{not json' }));
  assert.deepStrictEqual(plain(X.all()), { clockFormat: '12', clockSeconds: 'off', clockShow: 'on', screenDim: '5', screenSleep: '30' });
  X = load(fakeStorage({ 'cleanframe.tv.extras.v1': '{"screenDim":"7","screenSleep":"off"}' }));
  assert.deepStrictEqual([X.get('screenDim'), X.get('screenSleep')], ['5', 'off'], 'a dim time not offered falls back');
  X = load(fakeStorage({ 'cleanframe.tv.extras.v1': '["24"]' }));
  assert.strictEqual(X.get('clockFormat'), '12');
});

test('a listener that throws does not stop the others or the save', function () {
  var X = load(), got = 0;
  X.onChange(function () { throw new Error('boom'); });
  X.onChange(function () { got++; });
  assert.strictEqual(X.set('clockShow', 'off'), true);
  assert.strictEqual(got, 1);
});

test('Settings summary and the Clock page sections', function () {
  var X = load();
  assert.strictEqual(X.summary('clock'), '12-hour');
  X.set('clockFormat', '12a'); X.set('clockSeconds', 'on');
  assert.strictEqual(X.summary('clock'), '12-hour with AM/PM, seconds');
  X.set('clockShow', 'off');
  assert.strictEqual(X.summary('clock'), 'Hidden');
  assert.ok(X.owns('clock')); assert.ok(!X.owns('background')); assert.ok(!X.owns('__proto__'));
  assert.strictEqual(X.title('clock'), 'Clock');
  var s = plain(X.sections('clock'));
  assert.deepStrictEqual(s.map(function (x) { return [x.id, x.title, x.horizontal]; }),
    [['clockFormat', 'Format', false], ['clockSeconds', 'Seconds', true], ['clockShow', 'Clock on Home', true]]);
  assert.deepStrictEqual(s[0].items.map(function (i) { return i.action; }), ['pref.clockFormat.12', 'pref.clockFormat.12a', 'pref.clockFormat.24']);
  assert.ok(s.every(function (x) { return x.items.every(function (i) { return i.pref === x.id && !i.axis; }); }), 'items carry pref, never axis');
  assert.strictEqual(X.applied(s[0].items[1]), true);
  assert.strictEqual(X.applied(s[0].items[0]), false);
  assert.strictEqual(X.applied({ axis: 'bg', value: 'photo' }), false);
});

/* ---------- The patched main.js ---------- */

/* The dock's and the list's "remembered" flags, evaluated for each Home zone. */
function flags(line, name) {
  var m = new RegExp(name + ' = ([^,;]+(?: \\|\\| [^,;]+)*)[,;]').exec(line);
  assert.ok(m, name + ' is computed in web/main.js');
  return function (zone) { return vm.runInNewContext(m[1], { hs: { zone: zone } }); };
}

test('patched main.js: focus on the controls leaves no dock app or list row marked', function () {
  var dock = MAIN.split('\n').filter(function (l) { return l.indexOf('var tileFocus = ') >= 0; });
  var list = MAIN.split('\n').filter(function (l) { return l.indexOf('var rowFocus = ') >= 0; });
  assert.strictEqual(dock.length, 1); assert.strictEqual(list.length, 1);
  var tileSel = flags(dock[0], 'tileSel'), remembered = flags(list[0], 'remembered');
  ['util', 'ctl'].forEach(function (z) {
    assert.strictEqual(!!tileSel(z), false, 'dock: zone ' + z);
    assert.strictEqual(!!remembered(z), false, 'list: zone ' + z);
  });
  ['act', 'preview'].forEach(function (z) {
    assert.strictEqual(!!tileSel(z), true, 'dock keeps the marker for ' + z);
    assert.strictEqual(!!remembered(z), true, 'list keeps the marker for ' + z);
  });
  // The dock's plate follows tileSel: no lit plate under the app while the controls have focus.
  assert.ok(MAIN.indexOf("tileFocus ? ' on-focus' : tileSel ? ' on-sel' : ''") > 0);
});

test('patched main.js: the clock text and its timer come from CleanFrameExtras', function () {
  var a = MAIN.indexOf('  function clockText() {'), b = MAIN.indexOf('  function clockHtml()', a);
  assert.ok(a > 0 && b > a);
  var ctx = { X: load() };
  ctx.X.set('clockFormat', '24');
  vm.createContext(ctx);
  vm.runInContext(MAIN.slice(a, b) + '\nthis.out = clockText();', ctx);
  assert.ok(/^\d\d:\d\d$/.test(ctx.out), 'clockText() follows the saved format: ' + ctx.out);
  assert.ok(MAIN.indexOf('clockTimer = setTimeout(updateClock, X.clockStep() - (Date.now() % X.clockStep()) + 30);') > 0);
  assert.ok(MAIN.indexOf('  var X = window.CleanFrameExtras;') > 0);
  assert.ok(MAIN.indexOf('X.onChange(function () { updateClock(); render(); });') > 0);
});

test('patched main.js: Settings lists Clock before Apps on Home; its page saves with setPref', function () {
  var rows = MAIN.indexOf("{ id: 'clock', label: 'Clock', value: X.summary('clock') },"), editor = MAIN.indexOf("{ id: 'editor', label: 'Apps on Home'");
  var screen = MAIN.indexOf("{ id: 'screen', label: 'Screen', value: X.summary('screen') },");
  assert.ok(rows > 0 && screen > rows && editor > screen, 'Clock, then Screen, then Apps on Home');
  assert.ok(MAIN.indexOf('    if (X.owns(t.group)) return X.sections(t.group);') > 0);
  assert.ok(MAIN.indexOf('    } else if (item.sub) openAppearance(item.sub);\n    else if (item.pref) setPref(item.pref, item.value);') > 0);
  assert.ok(MAIN.indexOf("S.lookError = X.set(key, value) ? null : { type: 'save', patch: {}, row: 'pref', pref: key, value: value };") > 0);
  assert.ok(MAIN.indexOf("if (item.action === 'settings.retry' && error && error.pref) setPref(error.pref, error.value);") > 0);
  // (nor on an item marked bare: a Bluetooth device, tests/bluetooth.test.js)
  assert.ok(MAIN.indexOf("if (!item.axis && !item.pref && !item.bare) body += icon('right', 'setting-forward');") > 0, 'no forward arrow on a choice');
});

test('patched main.js: a Clock page opens on the first applied choice (Format), not on Back or the last section', function () {
  var a = MAIN.indexOf('  V.appearance = {'), b = MAIN.indexOf('    key: function (t, k) {', a);
  assert.ok(a > 0 && b > a);
  var X = load(); X.set('clockFormat', '24');
  var focused = [], ctx = { X: X, S: { c: { bg: 'photo' } },
    detailRows: function (t) {   // as main.js builds them: Format one per row, Seconds and Clock on Home one row each
      var sec = X.sections('clock');
      return [[{ action: 'settings.back', label: 'Settings' }]].concat(sec[0].items.map(function (i) { return [i]; }), [sec[1].items, sec[2].items]);
    },
    detailFocus: function (t, item) { t.f.action = item.action; focused.push(item.action); },
    detailLocation: function () { return { column: 0 }; } };
  vm.createContext(ctx);
  vm.runInContext('var V = {};\n' + MAIN.slice(a, b) + '};', ctx);
  var t = { group: 'clock', f: { action: 'samsung.settings' } };
  ctx.V.appearance.norm(t);
  assert.strictEqual(t.f.action, 'pref.clockFormat.24');
  var bg = { group: 'background', f: { action: 'none' } };
  ctx.S.c.bg = 'dark';
  ctx.detailRows = function () { return [[{ action: 'settings.back' }], [{ action: 'choice.bg.photo', axis: 'bg', value: 'photo' }, { action: 'choice.bg.dark', axis: 'bg', value: 'dark' }]]; };
  ctx.V.appearance.norm(bg);
  assert.strictEqual(bg.f.action, 'choice.bg.dark', 'upstream axis pages still open on the applied value');
});

test('setPref: a failed save reports through lookError with a retry; a good one clears it', function () {
  var a = MAIN.indexOf('  function setPref(key, value) {'), b = MAIN.indexOf('  function detailBack()', a);
  assert.ok(a > 0 && b > a);
  var st = fakeStorage({}, true), ctx = { X: load(st), S: { err: 'x', lookError: null } };
  vm.createContext(ctx);
  vm.runInContext(MAIN.slice(a, b), ctx);
  ctx.setPref('clockFormat', '24');
  assert.deepStrictEqual(plain(ctx.S.lookError), { type: 'save', patch: {}, row: 'pref', pref: 'clockFormat', value: '24' });
  assert.strictEqual(ctx.S.err, null);
  ctx.X = load(fakeStorage());
  vm.runInContext(MAIN.slice(a, b), ctx);
  ctx.setPref('clockFormat', '24');
  assert.strictEqual(ctx.S.lookError, null);
  assert.strictEqual(ctx.X.get('clockFormat'), '24');
});

test('index.html loads home-extras.js after device.js and before main.js, and its stylesheet', function () {
  var dev = INDEX.indexOf('<script src="device.js"></script>'), ex = INDEX.indexOf('<script src="home-extras.js"></script>'), main = INDEX.indexOf('<script src="main.js"></script>');
  assert.ok(dev >= 0 && ex > dev && main > ex);
  assert.ok(INDEX.indexOf('<link rel="stylesheet" href="home-extras.css">') > 0);
});

/* ---------- Long press ---------- */

function fakeTimers() {
  var now = 0, q = [], id = 0;
  return {
    setTimeout: function (fn, ms) { q.push({ id: ++id, at: now + ms, fn: fn }); return id; },
    clearTimeout: function (i) { q = q.filter(function (t) { return t.id !== i; }); },
    advance: function (ms) {
      now += ms;
      q.sort(function (a, b) { return a.at - b.at; });
      while (q.length && q[0].at <= now) q.shift().fn();
    },
    pending: function () { return q.length; }
  };
}
function loadWith(timers) {
  var ctx = { window: { localStorage: fakeStorage() }, setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout };
  vm.createContext(ctx);
  vm.runInContext(SOURCE, ctx, { filename: 'home-extras.js' });
  return ctx.window.CleanFrameExtras;
}

test('Hold: released before 600 ms taps (on release); held fires once; cancel does neither', function () {
  var T = fakeTimers(), X = loadWith(T), h = new X.Hold(), log = [];
  function tap() { log.push('tap'); } function fire() { log.push('fire'); }
  assert.strictEqual(X.HOLD_MS, 600);
  h.start(tap, fire); T.advance(599); assert.deepStrictEqual(log, []);
  assert.strictEqual(h.end(), true); assert.deepStrictEqual(log, ['tap']); assert.strictEqual(T.pending(), 0);
  log = []; h.start(tap, fire); T.advance(600); assert.deepStrictEqual(log, ['fire']);
  assert.strictEqual(h.end(), false, 'the release after a fire does nothing'); assert.deepStrictEqual(log, ['fire']);
  log = []; h.start(tap, fire); h.cancel(); T.advance(1000); assert.strictEqual(h.end(), false); assert.deepStrictEqual(log, []);
  log = []; h.start(tap, fire); h.start(tap, fire); T.advance(600); assert.deepStrictEqual(log, ['fire'], 'a restart replaces the old hold');
  assert.strictEqual(h.active(), false);
});

test('app menu: dock app = Move, Rename, Change icon, Remove, App info, Cancel; All apps = Add or Remove, Rename, Change icon, App info, Cancel', function () {
  var X = load();
  function acts(m) { return plain(m).map(function (a) { return a[0]; }); }
  assert.deepStrictEqual(acts(X.appMenu('dock', true)), ['move', 'rename', 'icon', 'remove', 'info', 'back']);
  assert.deepStrictEqual(acts(X.appMenu('apps', false)), ['add', 'rename', 'icon', 'info', 'back']);
  assert.deepStrictEqual(acts(X.appMenu('apps', true)), ['remove', 'rename', 'icon', 'info', 'back']);
  assert.deepStrictEqual(plain(X.appMenu('dock', true)).map(function (a) { return a[1]; }),
    ['Move', 'Rename', 'Change icon', 'Remove from Home', 'App info', 'Cancel']);
  assert.ok(X.appMenu('dock', true).every(function (a) { return typeof a[1] === 'string' && a[1]; }));
  /* Uninstall: only on a plain true, after App info (whose place never moves), before Cancel. */
  assert.deepStrictEqual(acts(X.appMenu('dock', true, true)), ['move', 'rename', 'icon', 'remove', 'info', 'uninstall', 'back']);
  assert.deepStrictEqual(acts(X.appMenu('apps', false, true)), ['add', 'rename', 'icon', 'info', 'uninstall', 'back']);
  assert.deepStrictEqual(acts(X.appMenu('apps', true, true)), ['remove', 'rename', 'icon', 'info', 'uninstall', 'back']);
  assert.strictEqual(plain(X.appMenu('dock', true, true))[5][1], 'Uninstall');
  [false, undefined, 'true', 1].forEach(function (no) { assert.ok(acts(X.appMenu('dock', true, no)).indexOf('uninstall') < 0, String(no)); });
  assert.ok(X.appMenuLine('dock').length > 0 && X.appMenuLine('apps').length > 0);
});

/* The patched key handlers and the menu actions, run from web/main.js with a fake page around them. */
function mainHold(state) {
  var a = MAIN.indexOf('  var HOLD = new X.Hold();'), b = MAIN.indexOf("  window.addEventListener('resize', fit);", a);
  assert.ok(a > 0 && b > a, 'the long-press block and the key handlers are in web/main.js');
  var T = fakeTimers(), X = loadWith(T), log = [], docL = {}, winL = {}, stack = [state.view];
  var S = { c: { home: state.home.slice() }, sel: state.home[0], note: null };
  var APPS = { emby: { id: 'emby', name: 'Emby', appId: 'com.mb.android' }, youtube: { id: 'youtube', name: 'YouTube', appId: 'com.google.android.youtube.tv' },
    kodi: { id: 'kodi', name: 'Kodi', appId: 'org.xbmc.kodi' } };
  var ctx = {
    X: X, S: S, busy: false, KEYS: { 13: 'ok', 37: 'left', 38: 'up', 39: 'right', 40: 'down', 27: 'back' },
    PGV: { inputKey: function () { return false; } },
    AEV: { inputKey: function () { return false; }, openName: function (t) { log.push('rename:' + t.where + ':' + t.id); }, openIcon: function (t) { log.push('icon:' + t.where + ':' + t.id); } },
    top: function () { return stack[stack.length - 1]; }, push: function (e) { stack.push(e); log.push('push:' + e.id); return e; },
    pop: function () { stack.pop(); }, app: function (id) { return APPS[id] || null; }, ensureSel: function () { return S.c.home.indexOf(S.sel); },
    appsItems: function () { return state.alpha || []; }, press: function (k) { log.push('press:' + k); },
    norm: function () {}, render: function () { log.push('render'); }, syncRotation: function () {}, begin: function () {},
    snap: function () { return { home: S.c.home.slice() }; },
    openEditor: function (o) { stack.push({ id: 'editor', origin: o, lifted: null, f: { zone: 'other', i: 0, part: 'remove' } }); log.push('editor:' + o); },
    homeRemove: function (id) { S.c.home = S.c.home.filter(function (x) { return x !== id; }); log.push('remove:' + id); return true; },
    homeAdd: function (id) { S.c.home.push(id); log.push('add:' + id); return true; },
    perform: function (title, run) { log.push('perform:' + title); run(function () {}); },
    FrameDevice: { appInfo: function (pkg, done) { log.push('appInfo:' + pkg); done(null); }, canUninstall: function (pkg) { log.push('can:' + pkg); return state.can === pkg; } },
    uninstallApp: function (a, id) { log.push('uninstall:' + a.appId + ':' + id); }, uninstallReturned: function () { log.push('returned'); },
    refreshBluetooth: function () {}, release: function () {}, focusStage: function () {}, after: function () {}, updateClock: function () {},
    document: { addEventListener: function (t, fn) { docL[t] = fn; }, hidden: false },
    window: { addEventListener: function (t, fn) { winL[t] = fn; } },
    pauseRotation: function () {}, clearTimeout: T.clearTimeout, clockTimer: 0
  };
  vm.createContext(ctx);
  vm.runInContext(MAIN.slice(a, b), ctx, { filename: 'main.js (long press)' });
  function ev(type, code, repeat) { docL[type]({ type: type, keyCode: code, repeat: !!repeat, preventDefault: function () {} }); }
  return { ev: ev, T: T, log: log, stack: stack, S: S, ctx: ctx, win: winL, doc: docL };
}

test('patched main.js: a short OK on a dock app is sent on release; nothing on press', function () {
  var m = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby', 'youtube'] });
  m.ev('keydown', 13); assert.deepStrictEqual(m.log, []);
  m.T.advance(200); m.ev('keyup', 13); assert.deepStrictEqual(m.log, ['press:ok']);
});

test('patched main.js: OK held 600 ms on a dock app opens its menu once; the release opens nothing', function () {
  var m = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby', 'youtube'] });
  m.ev('keydown', 13); m.ev('keydown', 13, true); m.T.advance(300); m.ev('keydown', 13, true); m.T.advance(300);
  assert.deepStrictEqual(m.log.slice(0, 2), ['can:com.mb.android', 'push:message']);
  var menu = m.stack[m.stack.length - 1];
  assert.strictEqual(menu.content.title, 'Emby');
  assert.deepStrictEqual(plain(menu.content.actions).map(function (x) { return x[0]; }), ['move', 'rename', 'icon', 'remove', 'info', 'back']);
  assert.strictEqual(menu.msg, 'appmenu.dock.emby', 'msg is per app (the sheet template is keyed by it)');
  m.ev('keyup', 13);
  assert.ok(m.log.indexOf('press:ok') < 0, 'no OK after the menu opened');
});

test('patched main.js: OK elsewhere (top controls, settings, an empty Home) is pressed at once', function () {
  [{ id: 'home', f: { zone: 'util' } }, { id: 'settings', f: {} }, { id: 'apps', f: { zone: 'head' } }].forEach(function (v) {
    var m = mainHold({ view: v, home: ['emby'] });
    m.ev('keydown', 13); assert.deepStrictEqual(m.log, ['press:ok'], v.id + '/' + v.f.zone);
  });
  var e = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: [] });
  e.ev('keydown', 13); assert.deepStrictEqual(e.log, ['press:ok'], 'empty Home: OK is Add apps at once');
  var b = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby'] });
  b.ctx.busy = true; b.ev('keydown', 13); assert.deepStrictEqual(b.log, ['press:ok'], 'busy: no hold');
});

test('patched main.js: focus lost or page hidden mid-hold opens nothing and sends no OK', function () {
  var m = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby'] });
  m.ev('keydown', 13); m.win.blur(); m.T.advance(1000); m.ev('keyup', 13);
  assert.deepStrictEqual(m.log, []);
  var n = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby'] });
  n.ev('keydown', 13); n.ctx.document.hidden = true; n.doc.visibilitychange(); n.T.advance(1000);
  assert.deepStrictEqual(n.log, []);
});

test('patched main.js: All apps tile: Add to Home or Remove; Back keys are not held', function () {
  var m = mainHold({ view: { id: 'apps', f: { zone: 'grid', i: 1 } }, home: ['emby'], alpha: ['emby', 'kodi'] });
  m.ev('keydown', 13); m.T.advance(600);
  var menu = m.stack[m.stack.length - 1];
  assert.strictEqual(menu.content.title, 'Kodi');
  assert.deepStrictEqual(plain(menu.content.actions).map(function (x) { return x[0]; }), ['add', 'rename', 'icon', 'info', 'back']);
  m.ctx.appMenuAct(menu.appMenu, 'add');
  assert.ok(m.log.indexOf('add:kodi') >= 0); assert.strictEqual(m.S.note, 'Kodi added to Home.');
  var k = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby'] });
  k.ev('keydown', 27); assert.deepStrictEqual(k.log, ['press:back']);
});

test('patched main.js: Move opens the Apps on Home editor with that app picked up; App info asks the TV', function () {
  var m = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby', 'youtube'] });
  m.ctx.appMenuAct({ where: 'dock', id: 'youtube' }, 'move');
  var e = m.stack[m.stack.length - 1];
  assert.strictEqual(e.id, 'editor'); assert.strictEqual(e.origin, 'home');
  assert.deepStrictEqual(plain(e.f), { zone: 'home', i: 1, part: 'name' });
  assert.deepStrictEqual(plain(e.lifted), { id: 'youtube', from: 1, order: ['emby', 'youtube'], snap: { home: ['emby', 'youtube'] } });
  m.ctx.appMenuAct({ where: 'dock', id: 'emby' }, 'info');
  assert.ok(m.log.indexOf('appInfo:com.mb.android') >= 0);
  m.ctx.appMenuAct({ where: 'dock', id: 'emby' }, 'remove');
  assert.deepStrictEqual(plain(m.S.c.home), ['youtube']);
  m.ctx.appMenuAct({ where: 'dock', id: 'gone' }, 'remove');
  assert.deepStrictEqual(plain(m.S.c.home), ['youtube'], 'an unknown app does nothing');
});

test('patched main.js: the menu has Uninstall only when the TV says so, for that package; choosing it goes to uninstallApp', function () {
  function menu(view, can, where) {
    var m = mainHold({ view: view, home: ['emby', 'youtube'], alpha: ['emby', 'kodi'], can: can });
    m.ev('keydown', 13); m.T.advance(600);
    return { m: m, acts: plain(m.stack[m.stack.length - 1].content.actions).map(function (x) { return x[0]; }) };
  }
  var dock = { id: 'home', f: { zone: 'apps' } }, grid = { id: 'apps', f: { zone: 'grid', i: 1 } };
  assert.deepStrictEqual(menu(dock, 'com.mb.android').acts, ['move', 'rename', 'icon', 'remove', 'info', 'uninstall', 'back']);
  assert.deepStrictEqual(menu(dock, 'org.other').acts, ['move', 'rename', 'icon', 'remove', 'info', 'back'], 'another package\'s answer is not this one\'s');
  assert.deepStrictEqual(menu(grid, 'org.xbmc.kodi').acts, ['add', 'rename', 'icon', 'info', 'uninstall', 'back']);
  assert.deepStrictEqual(menu(grid, '').acts, ['add', 'rename', 'icon', 'info', 'back']);
  var m = menu(dock, 'com.mb.android').m;
  m.ctx.appMenuAct({ where: 'dock', id: 'emby' }, 'uninstall');
  assert.ok(m.log.indexOf('uninstall:com.mb.android:emby') >= 0);
  assert.deepStrictEqual(plain(m.S.c.home), ['emby', 'youtube'], 'choosing it changes nothing yet');
});

test('patched main.js: Home coming back asks again (shown or focused); hiding does not', function () {
  var m = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby'] });
  m.ctx.document.hidden = true; m.doc.visibilitychange();
  assert.ok(m.log.indexOf('returned') < 0, 'hidden: nothing');
  m.ctx.document.hidden = false;
  var v = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby'] });
  assert.ok(MAIN.indexOf("if (!document.hidden) { refreshBluetooth(true); uninstallReturned(); }") > 0, 'shown');
  v.win.focus();
  assert.ok(v.log.indexOf('returned') >= 0, 'focused');
});

test('patched main.js: the message view runs the chosen menu action after closing the menu', function () {
  assert.ok(MAIN.indexOf("if (a === 'samsung') perform('Samsung Home', FrameDevice.home); else if (t.appMenu) appMenuAct(t.appMenu, a);") > 0);
});

/* ---------- Screen: Settings > Screen, the dim layer, the Sleep button ---------- */

function fakePage() {
  var body = { kids: [], appendChild: function (el) { this.kids.push(el); } };
  return { hidden: false, body: body,
    createElement: function (tag) { return { tag: tag, className: '', attrs: {}, setAttribute: function (k, v) { this.attrs[k] = v; } }; } };
}
/* home-extras.js with fake timers, a fake page and a fake FrameDevice that records every call. */
function loadScreen(opts) {
  opts = opts || {};
  var T = fakeTimers(), doc = fakePage(), calls = [], st = { playing: !!opts.playing };
  var FD = opts.noDevice ? undefined : {
    screenPolicy: function (d, s, done) { calls.push(['policy', d, s]); done(null, { canWrite: true }); },
    sleepNow: function (done) { calls.push(['now']); if (opts.nowErr) done(opts.nowErr); else done(null, {}); },
    sleepCancel: function (done) { calls.push(['cancel']); done(null, {}); },
    sleepStatusNow: function () { calls.push(['status']); return opts.status === undefined ? st : opts.status; }
  };
  var ctx = { window: { localStorage: opts.storage || fakeStorage(), document: doc, FrameDevice: FD }, setTimeout: T.setTimeout, clearTimeout: T.clearTimeout };
  vm.createContext(ctx);
  vm.runInContext(SOURCE, ctx, { filename: 'home-extras.js' });
  var X = ctx.window.CleanFrameExtras;
  function layer() { return doc.body.kids[0] || null; }
  return { X: X, T: T, doc: doc, calls: calls, st: st, layer: layer, on: function () { return !!layer() && / is-on\b/.test(layer().className); },
    black: function () { return !!layer() && / is-on is-asleep$/.test(layer().className); } };
}
var MIN = 60000;

test('Screen: summary, the two link rows, their pages, and Back names Screen', function () {
  var X = load();
  assert.strictEqual(X.summary('screen'), 'Dim 5 min, sleep 30 min');
  X.set('screenDim', 'off'); X.set('screenSleep', '120');
  assert.strictEqual(X.summary('screen'), 'Dim off, sleep 2 h');
  X.set('screenSleep', '60');
  assert.strictEqual(X.summary('screen'), 'Dim off, sleep 1 h');
  var s = plain(X.sections('screen'));
  assert.deepStrictEqual(s.map(function (x) { return [x.id, x.title]; }), [['screenDim', 'Dim after'], ['screenSleep', 'Sleep after']]);
  assert.deepStrictEqual(s.map(function (x) { return x.items.map(function (i) { return [i.action, i.sub, i.label, !!i.pref]; }); }),
    [[['screen.open.screenDim', 'screenDim', 'Off', false]], [['screen.open.screenSleep', 'screenSleep', '1 hour', false]]]);
  assert.ok(s.every(function (x) { return x.items[0].hint; }), 'each link row has its own hint');
  var dim = plain(X.sections('screenDim'));
  assert.deepStrictEqual(dim[0].items.map(function (i) { return i.value; }), ['off', '2', '5', '10', '15']);
  assert.deepStrictEqual(plain(X.sections('screenSleep'))[0].items.map(function (i) { return i.value; }), ['off', '10', '30', '60', '120']);
  assert.ok(dim[0].reason.indexOf('first button press only brightens') >= 0);
  assert.strictEqual(X.parent('screenDim'), 'Screen'); assert.strictEqual(X.parent('screenSleep'), 'Screen');
  assert.strictEqual(X.parent('screen'), ''); assert.strictEqual(X.parent('clock'), ''); assert.strictEqual(X.parent('__proto__'), '');
  ['screen', 'screenDim', 'screenSleep'].forEach(function (g) { assert.ok(X.owns(g), g); });
  assert.deepStrictEqual(['screen', 'screenDim', 'screenSleep'].map(X.title), ['Screen', 'Dim after', 'Sleep after']);
});

test('Screen: start-up hands the policy to the TV; a change sends it again', function () {
  var m = loadScreen();
  m.X.screenStart();
  assert.deepStrictEqual(plain(m.calls), [['policy', 5, 30]]);
  m.X.set('screenDim', 'off'); m.X.set('screenSleep', '120'); m.X.set('clockSeconds', 'on');
  assert.deepStrictEqual(plain(m.calls.slice(1)), [['policy', 0, 30], ['policy', 0, 120]], 'Off is 0; clock changes send nothing');
});

test('Screen: dims after Dim after with no key; the first key only brightens, and its repeats', function () {
  var m = loadScreen();
  m.X.screenStart();
  m.T.advance(5 * MIN - 1); assert.strictEqual(m.on(), false);
  m.T.advance(1); assert.strictEqual(m.on(), true); assert.strictEqual(m.X.dimmed(), true);
  assert.strictEqual(m.layer().attrs['aria-hidden'], 'true');
  assert.strictEqual(m.X.activity(40), true, 'the waking key is swallowed');
  assert.strictEqual(m.on(), false);
  assert.strictEqual(m.X.activity(40), true, 'its auto-repeat too');
  m.X.keyUp(40);
  assert.strictEqual(m.X.activity(40), false, 'the next press acts');
  m.T.advance(4 * MIN); assert.strictEqual(m.X.activity(13), false, 'a key before the dim restarts the wait');
  m.T.advance(4 * MIN); assert.strictEqual(m.on(), false);
  m.T.advance(MIN); assert.strictEqual(m.on(), true);
  assert.strictEqual(m.doc.body.kids.length, 1, 'one layer, reused');
});

test('Screen: no dim while something plays; it looks again a full period later', function () {
  var m = loadScreen({ playing: true });
  m.X.screenStart();
  m.T.advance(5 * MIN); assert.strictEqual(m.on(), false);
  m.st.playing = false;
  m.T.advance(5 * MIN); assert.strictEqual(m.on(), true);
  var n = loadScreen({ status: null });
  n.X.screenStart(); n.T.advance(5 * MIN);
  assert.strictEqual(n.on(), true, 'no answer from the TV: dim as usual');
});

test('Screen: Dim Off never dims; hidden pauses; shown again clears the dim and starts over', function () {
  var m = loadScreen({ storage: fakeStorage({ 'cleanframe.tv.extras.v1': '{"screenDim":"off"}' }) });
  m.X.screenStart(); m.T.advance(60 * MIN);
  assert.strictEqual(m.on(), false); assert.strictEqual(m.T.pending(), 0);
  var h = loadScreen();
  h.X.screenStart(); h.T.advance(5 * MIN); assert.strictEqual(h.on(), true);
  h.doc.hidden = true; h.X.visible(false); assert.strictEqual(h.T.pending(), 0, 'no timer while hidden');
  h.doc.hidden = false; h.X.visible(true); assert.strictEqual(h.on(), false, 'cleared when shown');
  assert.strictEqual(h.X.activity(13), false, 'the first key after showing acts');
  h.T.advance(5 * MIN); assert.strictEqual(h.on(), true);
  assert.strictEqual(h.X.wake(), true, 'Home (toHome) brightens'); assert.strictEqual(h.on(), false);
  assert.strictEqual(h.X.wake(), false);
  var c = loadScreen(); c.X.screenStart(); c.X.set('screenDim', '2');
  c.T.advance(2 * MIN); assert.strictEqual(c.on(), true, 'a new Dim after applies at once');
  assert.strictEqual(c.X.dimNow(), true, 'dimNow (stick test) dims at once');
});

test('Sleep: one setting with Settings > Screen > Sleep after (2026-11-01: two doors), Sleep now first', function () {
  var m = loadScreen();
  m.X.screenStart();
  var menu = plain(m.X.sleepMenu());
  assert.strictEqual(menu.title, 'Sleep');
  assert.deepStrictEqual(menu.actions, [['now', 'Sleep now'], ['off', 'Off'], ['10', '10 minutes'], ['30', '30 minutes'], ['60', '1 hour'], ['120', '2 hours']]);
  assert.deepStrictEqual(menu.actions.slice(1).map(function (a) { return a[0]; }),
    plain(m.X.sections('screenSleep'))[0].items.map(function (i) { return i.value; }), 'exactly Sleep after\'s choices');
  assert.ok(menu.line.indexOf('Sleep after: 30 minutes') === 0, menu.line);
  assert.strictEqual(m.X.sleepIndex(), 3, 'opens on the applied Sleep after (30 minutes)');
  var errs = [];
  m.X.sleepSet('60', function (e) { errs.push(e); });
  assert.deepStrictEqual(errs, [null]);
  assert.strictEqual(m.X.get('screenSleep'), '60', 'the Sleep button saves Sleep after');
  assert.deepStrictEqual(plain(m.calls.slice(1)), [['policy', 5, 60]], 'and the TV gets it, as from Settings > Screen');
  assert.strictEqual(plain(m.X.sections('screen'))[1].items[0].label, '1 hour', 'Settings > Screen shows it');
  assert.strictEqual(m.X.sleepIndex(), 4);
  m.X.set('screenSleep', 'off');
  assert.strictEqual(m.X.sleepIndex(), 1, 'a change in Settings > Screen shows in the Sleep menu');
  assert.ok(m.X.sleepMenu().line.indexOf('Sleep after: Off') === 0);
});

test('Sleep now: the TV sleeps, Home goes black, Sleep after is kept; a key first puts it back', function () {
  var m = loadScreen(), errs = [], seen = 0;
  m.X.screenStart(); m.X.set('screenSleep', '120');
  m.X.onChange(function () { seen++; });
  m.calls.length = 0;
  m.X.sleepSet('now', function (e) { errs.push(e); });
  assert.deepStrictEqual(errs, [null]);
  assert.deepStrictEqual(plain(m.calls), [['now']], 'no policy change');
  assert.strictEqual(seen, 0, 'no preference changed');
  assert.strictEqual(m.X.get('screenSleep'), '120', '"keeps the original setting after"');
  assert.strictEqual(m.X.napping(), true); assert.strictEqual(m.black(), true, 'Home is black at once');
  m.T.advance(5 * MIN); assert.strictEqual(m.black(), true, 'the dim timer does not lighten it');
  assert.strictEqual(m.X.activity(40), true, 'the first key only wakes Home');
  function sent() { return plain(m.calls).filter(function (c) { return c[0] !== 'status'; }); }
  assert.deepStrictEqual(sent(), [['now'], ['cancel']], 'and hands Sleep after back to the TV');
  assert.strictEqual(m.X.napping(), false); assert.strictEqual(m.on(), false);
  m.X.keyUp(40); m.X.activity(40);
  assert.strictEqual(sent().length, 2, 'cancelled once');
  ['wake', 'visible'].forEach(function (how) {
    var n = loadScreen(); n.X.screenStart(); n.X.sleepSet('now');
    if (how === 'wake') n.X.wake(); else { n.doc.hidden = true; n.X.visible(false); n.doc.hidden = false; n.X.visible(true); }
    assert.deepStrictEqual(plain(n.calls.slice(1)), [['now'], ['cancel']], how);
    assert.strictEqual(n.X.napping(), false, how); assert.strictEqual(n.on(), false, how);
  });
});

test('Sleep: a refused Sleep now or an impossible time changes nothing and reports', function () {
  var m = loadScreen({ nowErr: { message: 'Could not put the TV to sleep.', detail: 'x' } }), errs = [], seen = 0;
  m.X.onChange(function () { seen++; });
  m.X.sleepSet('now', function (e) { errs.push(e && e.message); });
  m.X.sleepSet('7', function (e) { errs.push(e && e.message); });
  m.X.sleepSet('90', function (e) { errs.push(e && e.message); });
  assert.deepStrictEqual(errs, ['Could not put the TV to sleep.', 'Not a sleep time.', 'Not a sleep time.']);
  assert.strictEqual(seen, 0); assert.strictEqual(m.X.napping(), false); assert.strictEqual(m.on(), false);
  m.X.activity(40); assert.deepStrictEqual(plain(m.calls), [['now']], 'nothing to cancel');
  var n = loadScreen({ noDevice: true }), e2 = [];
  n.X.sleepSet('now', function (e) { e2.push(e.message); });
  n.X.sleepSet('10', function (e) { e2.push(e); });
  n.X.screenStart(); n.X.visible(true);
  assert.deepStrictEqual(e2, ['Open CleanFrame on the TV to use Sleep now.', null], 'web preview: no device, no throw; Sleep after still saves');
});

test('patched main.js: Sleep replaces Screensaver; its menu runs X.sleepSet (Sleep now, Sleep after); nothing under the clock', function () {
  assert.ok(MAIN.indexOf("    sleep: '<path d=") > 0, 'a moon icon');
  // The Art Mode anchors stay as older commits patched them (tools/android-patch.py): the button is
  // relabelled after UTIL is made and answered before the screensaver branch, which is never reached.
  var a0 = MAIN.indexOf("  UTIL.art = ['Sleep', 'sleep'];"), u0 = MAIN.indexOf('  var UTIL = {');
  assert.ok(u0 > 0 && a0 > u0, 'relabelled after UTIL is made');
  var f = MAIN.indexOf('  function utilAction(u) {'), g = MAIN.indexOf('  function addAppsRoute()', f), opened = [], ran = [];
  var ctx = { openSleepMenu: function () { opened.push(1); }, perform: function (t) { ran.push(t); }, openApps: function () { ran.push('apps'); },
    openSettings: function () { ran.push('settings'); }, FrameDevice: {} };
  vm.createContext(ctx); vm.runInContext(MAIN.slice(f, g) + '\nutilAction("art"); utilAction("apps");', ctx);
  assert.deepStrictEqual([opened.length, ran], [1, ['apps']], 'Sleep opens its menu, never the screensaver');
  assert.ok(MAIN.indexOf("else if (t.sleepMenu) X.sleepSet(a, function (err) { if (err) message('Sleep', err.message, err.detail || ''); render(); });") > 0);
  var a = MAIN.indexOf('  function openSleepMenu() {'), b = MAIN.indexOf('  var heldKeys = {};', a);
  assert.ok(a > 0 && b > a);
  var m = loadScreen(), pushed = [], ctx = { X: m.X, push: function (e) { pushed.push(e); } };
  vm.createContext(ctx); vm.runInContext(MAIN.slice(a, b) + '\nopenSleepMenu();', ctx);
  assert.strictEqual(pushed[0].id, 'message'); assert.strictEqual(pushed[0].sleepMenu, true);
  assert.strictEqual(pushed[0].f.b, 3); assert.strictEqual(pushed[0].content.title, 'Sleep');
  assert.strictEqual(pushed[0].content.actions[pushed[0].f.b][0], '30', 'focus on the applied Sleep after');
  assert.ok(MAIN.indexOf('sleepText') < 0 && MAIN.indexOf('sleep-line') < 0, 'no sleep time under the clock ("i don\'t want a sleep time display")');
});

test('patched main.js: Screen pages go Back to Screen; the keys, visibility and start-up reach X', function () {
  assert.strictEqual(MAIN.split("t.group === 'rotation' ? 'Background' : X.parent(t.group) || 'Settings'").length - 1, 4);
  assert.strictEqual(MAIN.indexOf("t.group === 'rotation' ? 'Background' : 'Settings'"), -1);
  assert.ok(MAIN.indexOf("screen: 'Screen', screenDim: 'Dim after', screenSleep: 'Sleep after'") > 0);
  assert.ok(MAIN.indexOf("selected.hint ? selected.hint : selected.axis || selected.pref ? 'Apply selected setting'") > 0);
  assert.ok(MAIN.indexOf('    var code = ev.keyCode, k = KEYS[code];\n    if (X.activity(code)) { ev.preventDefault(); return; }\n    if (PGV.inputKey(ev) || AEV.inputKey(ev))') > 0,
    'the dim sees every key first');
  assert.ok(MAIN.indexOf('X.keyUp(ev.keyCode);') > 0 && MAIN.indexOf('HOLD.cancel(); X.visible(!document.hidden);') > 0);
  assert.ok(MAIN.indexOf('  X.onChange(function () { updateClock(); render(); });\n  X.screenStart();') > 0);
  // The installed apps are read at start-up, so a default dock app opens this stick's package of its
  // brand (device.js ALSO), not upstream's, before All apps is ever opened (Hugsun Emby, 2026-11-02).
  var st = MAIN.indexOf('  setLook({}, { transient: true, startup: true });\n  refreshCatalog();');
  assert.ok(st > MAIN.indexOf('  initialise();\n'), 'refreshCatalog() runs at start-up, after initialise()');
});

test('patched main.js: while dimmed, the first key goes to the dim and not to the page', function () {
  var m = mainHold({ view: { id: 'home', f: { zone: 'apps' } }, home: ['emby'] });
  m.ctx.X.dimNow();
  m.ev('keydown', 39); m.ev('keydown', 39, true); m.ev('keyup', 39);
  assert.deepStrictEqual(m.log, [], 'Right only brightened');
  m.ev('keydown', 39); assert.deepStrictEqual(m.log, ['press:right']);
});

var failed = 0;
cases.forEach(function (c) {
  try { c[1](); console.log('PASS ' + c[0]); }
  catch (e) { failed++; console.log('FAIL ' + c[0] + ': ' + String((e && e.message) || e).split('\n')[0]); }
});
if (failed) { console.log('HOME_EXTRAS_TEST_FAIL ' + failed); process.exit(1); }
console.log('HOME_EXTRAS_TEST_OK ' + cases.length);

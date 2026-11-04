/* Unit tests for web/device.js (window.FrameDevice over the window.AndroidHome bridge); toHome()'s
   cases also read web/main.js (its KEYS table) and web/photo-adjust.js (Adjust's key()).
   No dependencies: node:assert + node:vm and a tiny runner. Run from the repo root:
     node tests/device.test.js
   Prints one line per case and exits non-zero on any failure.
   device.js is loaded into a fresh vm context per case with a fake window, document and
   setTimeout; timers are queued and flushed explicitly, so every case is deterministic. */
'use strict';
var assert = require('node:assert');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var SOURCE_PATH = process.env.DEVICE_JS || path.join(__dirname, '..', 'web', 'device.js');
var SOURCE = fs.readFileSync(SOURCE_PATH, 'utf8');

var IDS = {
  emby: 'com.mb.android', youtube: 'com.google.android.youtube.tv',
  spotify: 'com.spotify.tv.android', rumble: 'com.rumble.battles',
  netflix: 'com.netflix.ninja', primevideo: 'com.amazon.amazonvideo.livingroom',
  disneyplus: 'com.disney.disneyplus', stremio: 'com.stremio.one',
  appletv: 'com.apple.atve.androidtv.appletv'
};

/* toHome() closes the Adjust screen with Escape: the real key table of main.js (KEYS) and the real
   web/photo-adjust.js key() decide what that Escape does there, so a change to either is seen here. */
var WEB = path.join(__dirname, '..', 'web');
var MAIN_KEYS = (function () {
  var m = /var KEYS = (\{[^}]*\});/.exec(fs.readFileSync(path.join(WEB, 'main.js'), 'utf8'));
  assert.ok(m, 'web/main.js: no "var KEYS = {...};" (did upstream rename it? update tests/device.test.js)');
  return vm.runInNewContext('(' + m[1] + ')');
})();
var adjustKey = (function () {
  var w = {};
  vm.runInNewContext(fs.readFileSync(path.join(WEB, 'photo-adjust.js'), 'utf8'), { window: w, Math: Math });
  assert.strictEqual(typeof w.CleanFramePhotoAdjust.key, 'function');
  return w.CleanFramePhotoAdjust.key;
})();

/* ---------- Fakes ---------- */

/* A fake DOM modelled on main.js: keydown of keyCode 27 is 'back' and is ignored while the
   same key is held (until its keyup); one 'back' closes the sheet first, then appearance. */
function fakeDocument(panels) {
  var held = {}, events = [];
  var doc = {
    events: events,
    panels: panels,
    getElementById: function (id) { return Object.prototype.hasOwnProperty.call(panels, id) ? panels[id] : null; },
    createEvent: function (kind) {
      assert.strictEqual(kind, 'Event');
      return { initEvent: function (type, bubbles, cancelable) { this.type = type; this.bubbles = bubbles; this.cancelable = cancelable; } };
    },
    dispatchEvent: function (e) {
      events.push({ type: e.type, keyCode: e.keyCode, which: e.which, bubbles: e.bubbles, cancelable: e.cancelable });
      if (e.type === 'keyup') { delete held[e.keyCode]; return true; }
      if (e.type !== 'keydown' || e.keyCode !== 27 || held[27]) return true;
      held[27] = true;
      /* The Adjust screen is on top (z-index 60) and takes the key itself: main.js maps keyCode 27
         through its own KEYS table and V.adjust.key ignores keys while saving, else runs
         CleanFramePhotoAdjust.key; on 'cancel' it pops back to the group page (#appearance). */
      var pa = panels.photoAdjust;
      if (pa && !pa.hidden) {
        if (pa.busy) return true;
        var r = adjustKey(pa.s, MAIN_KEYS[27]);
        pa.s = r.s;
        if (r.act === 'cancel') { pa.hidden = true; if (panels.appearance) panels.appearance.hidden = false; }
        return true;
      }
      if (panels.sheet && !panels.sheet.hidden && !panels.sheet.stuck) panels.sheet.hidden = true;
      else if (panels.appearance && !panels.appearance.hidden && !panels.appearance.stuck) panels.appearance.hidden = true;
      /* Nothing open: Back in the util zone moves focus to the dock (zone 'apps'), and render()
         drops is-awake from #utils; with focus already on the dock, Back does nothing. */
      else if (panels.utils && !panels.utils.stuck) panels.utils.className = panels.utils.className.replace(/(^|\s)is-awake(?=\s|$)/g, '');
      return true;
    }
  };
  return doc;
}

function load(opts) {
  opts = opts || {};
  var timers = [];
  var ctx = {
    setTimeout: function (fn, ms) { timers.push({ fn: fn, ms: ms }); return timers.length; },
    document: opts.document || fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true } })
  };
  ctx.window = ctx;
  if (opts.bridge) ctx.AndroidHome = opts.bridge;
  vm.createContext(ctx);
  vm.runInContext(SOURCE, ctx, { filename: 'web/device.js' });
  return {
    FD: ctx.FrameDevice,
    ctx: ctx,
    timers: timers,
    flush: function () {
      var n = 0, t;
      while (timers.length) { t = timers.shift(); assert.strictEqual(t.ms, 0, 'device.js timers are 0 ms'); t.fn(); if (++n > 100) throw new Error('timer storm'); }
      return n;
    }
  };
}

/* Records every done() call; plain() strips the vm realm so deepStrictEqual compares data. */
function recorder() {
  var calls = [];
  function done() { calls.push(Array.prototype.slice.call(arguments)); }
  done.calls = calls;
  return done;
}
function plain(v) { return v === undefined ? undefined : JSON.parse(JSON.stringify(v)); }

function bridge(over) {
  var log = [];
  var b = {
    log: log,
    apps: function () { log.push(['apps']); return '[]'; },
    launch: function (pkg) { log.push(['launch', pkg]); return ''; },
    settings: function () { log.push(['settings']); return ''; },
    appInfo: function (pkg) { log.push(['appInfo', pkg]); return ''; }
  };
  Object.keys(over || {}).forEach(function (k) { b[k] = over[k]; });
  return b;
}

/* Run apps() against a bridge answer; returns the single done() call. */
function appsWith(answer) {
  var env = load({ bridge: bridge({ apps: typeof answer === 'function' ? answer : function () { return answer; } }) });
  var done = recorder();
  env.FD.apps(done);
  assert.strictEqual(done.calls.length, 0, 'apps answers asynchronously');
  env.flush();
  assert.strictEqual(done.calls.length, 1, 'done called exactly once');
  return done.calls[0];
}
function assertAppsError(call) {
  assert.deepStrictEqual(plain(call[0]), { message: 'Could not read installed apps.' });
  assert.strictEqual(call.length, 1, 'no entries passed with an error');
}

/* ---------- Runner ---------- */

var cases = [];
function test(name, fn) { cases.push({ name: name, fn: fn }); }

/* ---------- apps() ---------- */

/* Entries keep the bridge's order (main.js sorts All apps by name itself); no rank field. */
test('apps: valid list -> done(null, list) once with exactly appId/name/brand/icon, in bridge order', function () {
  var call = appsWith(JSON.stringify([
    { appId: 'com.example.zeta', name: 'Zeta' },
    { appId: IDS.youtube, name: 'YouTube' },
    { appId: 'com.example.alpha', name: 'alpha' }
  ]));
  assert.strictEqual(call[0], null);
  assert.deepStrictEqual(plain(call[1]), [
    { appId: 'com.example.zeta', name: 'Zeta', brand: '', icon: '/icons/com.example.zeta.png' },
    { appId: IDS.youtube, name: 'YouTube', brand: 'youtube', icon: '/icons/' + IDS.youtube + '.png' },
    { appId: 'com.example.alpha', name: 'alpha', brand: '', icon: '/icons/com.example.alpha.png' }
  ]);
});

/* Two packages of one brand: main.js gives the brand's id to the first it reads, so the IDS package
   comes first whatever Android's order; everything else keeps its place. */
test('apps: a brand\'s IDS package comes before its ALSO package, in either bridge order', function () {
  function ids(list) { return plain(appsWith(JSON.stringify(list.map(function (p) { return { appId: p, name: p }; })))[1]).map(function (e) { return e.appId + ':' + e.brand; }); }
  assert.deepStrictEqual(ids(['a.b', 'com.rockchip.android.youtube.tv', 'c.d', IDS.youtube, 'e.f']),
    ['a.b', IDS.youtube + ':youtube', 'c.d', 'com.rockchip.android.youtube.tv:youtube', 'e.f'].map(function (x) { return x.indexOf(':') > 0 ? x : x + ':'; }));
  assert.deepStrictEqual(ids([IDS.youtube, 'com.rockchip.android.youtube.tv']), [IDS.youtube + ':youtube', 'com.rockchip.android.youtube.tv:youtube']);
  assert.deepStrictEqual(ids(['tv.emby.embyatv', IDS.emby]), [IDS.emby + ':emby', 'tv.emby.embyatv:emby']);
  assert.deepStrictEqual(ids(['tv.emby.embyatv', 'com.rockchip.android.youtube.tv']), ['tv.emby.embyatv:emby', 'com.rockchip.android.youtube.tv:youtube'], 'alone, an ALSO package has its brand');
});

test('apps: "[]" -> done(null, [])', function () {
  var call = appsWith('[]');
  assert.strictEqual(call[0], null);
  assert.deepStrictEqual(plain(call[1]), []);
});

test('apps: "null" -> error', function () { assertAppsError(appsWith('null')); });
test('apps: "{}" -> error', function () { assertAppsError(appsWith('{}')); });
test('apps: bad JSON -> error', function () { assertAppsError(appsWith('[{"appId":')); });
test('apps: bridge returns undefined -> error', function () { assertAppsError(appsWith(undefined)); });
test('apps: bridge throws -> error, done once', function () {
  assertAppsError(appsWith(function () { throw new Error('binder died'); }));
});

test('apps: entries missing/empty/non-string appId and null entries are dropped', function () {
  var call = appsWith(JSON.stringify([
    null, 7, { name: 'No id' }, { appId: '', name: 'Empty' }, { appId: 42, name: 'Number' },
    { appId: 'com.ok.app', name: 'Ok' }
  ]));
  assert.strictEqual(call[0], null);
  assert.deepStrictEqual(plain(call[1]).map(function (a) { return a.appId; }), ['com.ok.app']);
});

test('apps: non-string or empty name falls back to appId', function () {
  var call = appsWith(JSON.stringify([
    { appId: 'com.b.num', name: 5 }, { appId: 'com.a.missing' }, { appId: 'com.c.empty', name: '' }
  ]));
  assert.strictEqual(call[0], null);
  assert.deepStrictEqual(plain(call[1]).map(function (a) { return [a.appId, a.name]; }),
    [['com.b.num', 'com.b.num'], ['com.a.missing', 'com.a.missing'], ['com.c.empty', 'com.c.empty']]);
});

/* A whitespace-only name would show a blank label: it falls back to the appId; names are trimmed. */
test('apps: whitespace-only name falls back to appId; names are trimmed', function () {
  var call = appsWith(JSON.stringify([{ appId: 'com.ws.app', name: '   ' }, { appId: 'com.tr.app', name: '  Trim  ' }]));
  assert.strictEqual(call[0], null);
  var names = plain(call[1]).map(function (a) { return a.name; }).sort();
  assert.deepStrictEqual(names, ['Trim', 'com.ws.app']);
});

test('apps: no bridge (web preview) -> unavailable error, done once, asynchronously', function () {
  var env = load(), done = recorder();
  env.FD.apps(done);
  assert.strictEqual(done.calls.length, 0, 'answers asynchronously');
  env.flush();
  assert.strictEqual(done.calls.length, 1);
  assert.deepStrictEqual(plain(done.calls[0][0]), {
    message: 'Open CleanFrame on the TV to use this control.', detail: 'The web preview cannot control the TV.' });
});

test('apps: a done() that throws is not called a second time', function () {
  var env = load({ bridge: bridge({ apps: function () { return '[]'; } }) }), n = 0;
  env.FD.apps(function () { n++; throw new Error('render blew up'); });
  assert.throws(function () { env.flush(); }, /render blew up/);
  assert.strictEqual(n, 1);
});

/* ---------- Brand mapping ---------- */

/* device.js ALSO, read from the source so a new entry is tested too. */
function alsoMap() {
  var m = /var ALSO = (\{[\s\S]*?\});/.exec(SOURCE);
  assert.ok(m, 'web/device.js has no `var ALSO = {...};`');
  return vm.runInNewContext('(' + m[1] + ')');
}

test('brands: the other sticks\' packages (ALSO) get the same brand; none is in IDS or under two brands', function () {
  var also = alsoMap(), seen = {}, list = [];
  assert.deepStrictEqual(plain(also), { emby: ['tv.emby.embyatv'], youtube: ['com.rockchip.android.youtube.tv'] });
  Object.keys(also).forEach(function (b) {
    assert.ok(Object.prototype.hasOwnProperty.call(IDS, b), b + ' is not a brand in IDS');
    also[b].forEach(function (p) {
      assert.ok(!seen[p], p + ' is under two brands');
      seen[p] = b;
      Object.keys(IDS).forEach(function (i) { assert.notStrictEqual(IDS[i], p, p + ' is already IDS.' + i); });
      list.push({ appId: p, name: 'Other ' + b });
    });
  });
  /* Both packages of one app installed: each gets the brand (main.js keeps the first for the dock). */
  list.push({ appId: IDS.emby, name: 'Emby' });
  list.push({ appId: 'tv.emby.embyatv.extra', name: 'Not Emby' });
  var call = appsWith(JSON.stringify(list));
  assert.strictEqual(call[0], null);
  var byId = {};
  plain(call[1]).forEach(function (a) { byId[a.appId] = a; });
  Object.keys(seen).forEach(function (p) {
    assert.strictEqual(byId[p].brand, seen[p], p);
    assert.strictEqual(byId[p].icon, '/icons/' + p + '.png', p);
  });
  assert.strictEqual(byId[IDS.emby].brand, 'emby');
  assert.strictEqual(byId['tv.emby.embyatv.extra'].brand, '');
});

test('brands: the 9 IDS packages get their brand; unknown, near-miss and prototype-key ids get ""', function () {
  var list = Object.keys(IDS).map(function (b) { return { appId: IDS[b], name: b.toUpperCase() }; });
  list.push({ appId: 'com.unknown.thing', name: 'AAA Unknown' });
  list.push({ appId: 'com.mb.android.extra', name: 'Not Emby' });
  list.push({ appId: 'constructor', name: 'Proto' });
  list.push({ appId: '__proto__', name: 'Proto 2' });
  var call = appsWith(JSON.stringify(list));
  assert.strictEqual(call[0], null);
  var got = plain(call[1]);
  var byId = {};
  got.forEach(function (a) { byId[a.appId] = a; });
  Object.keys(IDS).forEach(function (b) { assert.strictEqual(byId[IDS[b]].brand, b, b); });
  assert.strictEqual(byId['com.unknown.thing'].brand, '');
  assert.strictEqual(byId['com.mb.android.extra'].brand, '');
  assert.strictEqual(got.length, list.length);
  got.forEach(function (a) {
    assert.strictEqual(typeof a.brand, 'string', a.appId);
    if (a.appId === 'constructor' || a.appId === '__proto__') assert.strictEqual(a.brand, '', a.appId);
  });
});

/* ---------- launchApp / settings / appInfo ---------- */

var ACTIONS = [
  { name: 'launchApp', bridgeFn: 'launch', run: function (FD, done) { FD.launchApp('com.example.app', done); }, expectLog: [['launch', 'com.example.app']] },
  { name: 'settings', bridgeFn: 'settings', run: function (FD, done) { FD.settings(done); }, expectLog: [['settings']] },
  { name: 'appInfo', bridgeFn: 'appInfo', run: function (FD, done) { FD.appInfo('com.example.app', done); }, expectLog: [['appInfo', 'com.example.app']] }
];

ACTIONS.forEach(function (A) {
  function runWith(impl) {
    var over = {};
    if (impl) over[A.bridgeFn] = impl;
    var b = bridge(over), env = load({ bridge: b }), done = recorder();
    A.run(env.FD, done);
    assert.strictEqual(done.calls.length, 0, A.name + ' answers asynchronously');
    env.flush();
    assert.strictEqual(done.calls.length, 1, A.name + ' calls done exactly once');
    return { call: done.calls[0], bridge: b };
  }
  test(A.name + ': success "" -> done(null) once', function () {
    var r = runWith();
    assert.strictEqual(r.call[0], null);
    assert.deepStrictEqual(r.bridge.log, A.expectLog);
  });
  test(A.name + ': returned reason -> error with reason as detail', function () {
    var r = runWith(function () { return 'Not installed'; });
    assert.deepStrictEqual(plain(r.call[0]), { message: 'The TV could not open this screen.', detail: 'Not installed' });
  });
  test(A.name + ': only "" is success: null, undefined, false, 0 or an object -> "no answer" error', function () {
    [null, undefined, false, 0, {}].forEach(function (v) {
      var r = runWith(function () { return v; });
      assert.deepStrictEqual(plain(r.call[0]), { message: 'The TV could not open this screen.', detail: 'The TV gave no answer.' }, String(v));
    });
  });
  test(A.name + ': bridge throws Error -> error with its message as detail', function () {
    var r = runWith(function () { throw new Error('SecurityException'); });
    assert.deepStrictEqual(plain(r.call[0]), { message: 'The TV could not open this screen.', detail: 'SecurityException' });
  });
  test(A.name + ': bridge throws a non-Error / null -> error detail string', function () {
    assert.strictEqual(runWith(function () { throw 'raw string'; }).call[0].detail, 'raw string');
    assert.strictEqual(runWith(function () { throw null; }).call[0].detail, 'Unknown error');
  });
  test(A.name + ': no bridge -> unavailable error, done exactly once', function () {
    var env = load(), done = recorder();
    A.run(env.FD, done);
    assert.strictEqual(done.calls.length, 0, 'answers asynchronously');
    env.flush();
    assert.strictEqual(done.calls.length, 1);
    assert.strictEqual(done.calls[0][0].message, 'Open CleanFrame on the TV to use this control.');
  });
});

test('launch(brand): known brand launches its package; unknown brand errors once, asynchronously', function () {
  var b = bridge(), env = load({ bridge: b }), done = recorder(), bad = recorder();
  env.FD.launch('netflix', done);
  env.FD.launch('tizen-only', bad);
  assert.strictEqual(done.calls.length + bad.calls.length, 0, 'answers asynchronously');
  env.flush();
  assert.deepStrictEqual(b.log, [['launch', IDS.netflix]]);
  assert.strictEqual(done.calls.length, 1); assert.strictEqual(done.calls[0][0], null);
  assert.strictEqual(bad.calls.length, 1);
  assert.deepStrictEqual(plain(bad.calls[0][0]), { message: 'This app has no launch target.', detail: 'CleanFrame on this TV has no app for tizen-only.' });
});

test('launch(brand): prototype keys are not brands; no bridge answers asynchronously', function () {
  var b = bridge(), env = load({ bridge: b }), proto = recorder(), off = load(), none = recorder();
  env.FD.launch('constructor', proto);
  off.FD.launch('netflix', none);
  assert.strictEqual(proto.calls.length + none.calls.length, 0, 'answers asynchronously');
  env.flush(); off.flush();
  assert.deepStrictEqual(b.log, []);
  assert.deepStrictEqual(plain(proto.calls), [[{ message: 'This app has no launch target.', detail: 'CleanFrame on this TV has no app for constructor.' }]]);
  assert.strictEqual(none.calls.length, 1);
  assert.strictEqual(none.calls[0][0].message, 'Open CleanFrame on the TV to use this control.');
});

test('home: always answers once, asynchronously, with an explanatory error', function () {
  var env = load({ bridge: bridge() }), done = recorder();
  env.FD.home(done);
  assert.strictEqual(done.calls.length, 0);
  env.flush();
  assert.strictEqual(done.calls.length, 1);
  assert.strictEqual(done.calls[0][0].message, 'CleanFrame is this TV’s Home screen.');
});

/* done is optional on every FrameDevice call, as in screenCall and photoCall: no callback (or not a
   function), with or without the bridge, with a bridge that throws or answers junk, never throws
   from a timer; the calls still answer asynchronously. */
test('done is optional: launchApp/launch/home/settings/appInfo/apps without a callback never throw', function () {
  [null, bridge({ launch: function () { throw new Error('x'); }, settings: function () { return 'no'; }, apps: function () { return 'junk'; } }),
    bridge()].forEach(function (b) {
    var env = load(b ? { bridge: b } : {});
    env.FD.launchApp('com.example'); env.FD.launch('emby'); env.FD.launch('nope'); env.FD.home();
    env.FD.settings(); env.FD.appInfo('com.example'); env.FD.apps();
    env.FD.launchApp('com.example', 'not a function'); env.FD.apps(42); env.FD.home({});
    assert.ok(env.timers.length >= 10, 'every call still answers asynchronously');
    env.flush();
  });
});

test('ES5 only: no let/const/class/arrow/template in web/device.js (docs/RULES.md rule 2)', function () {
  var code = SOURCE.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
  assert.ok(!/\b(let|const|class)\s/.test(code), 'let/const/class');
  assert.ok(!/=>/.test(code), 'arrow function');
  assert.ok(code.indexOf('`') < 0, 'template literal');
});

test('FrameDevice exposes exactly the interface main.js and MainActivity use', function () {
  var env = load();
  assert.deepStrictEqual(Object.keys(env.FD).sort(),
    ['addGroup', 'appInfo', 'apps', 'bluetooth', 'bluetoothPair', 'bluetoothUnpair', 'canUninstall', 'deleteGroup', 'deletePhoto', 'home', 'launch', 'launchApp', 'library', 'libraryNow',
     'materialJobs', 'materialsWake', 'onMaterialsWake', 'recrop', 'recropState', 'renameGroup', 'saveMaterial', 'screenPolicy', 'settings', 'sleepCancel',
     'sleepNow', 'sleepStatusNow', 'toHome', 'uninstall', 'uploadStart', 'uploadStop']);
});

/* ---------- Photo groups (docs/PHOTO-GROUPS.md contract 2) ---------- */

var LIB_JSON = '{"v":1,"groups":[{"id":"scenery","name":"Scenery","builtin":true}],"photos":[]}';
var JOBS_JSON = '{"v":1,"jobs":[{"gid":"space","pid":"pabcdefgh01","wall":"user-tmp/space/pabcdefgh01/wall.jpg","tone":"dark","need":["panel","study-soft"]}]}';
var UPLOAD = { ok: true, url: 'http://192.168.0.6:8097/u/0123456789abcdef0123456789abcdef', base: 'http://192.168.0.6:8097', code: '482913', expires: 1761901500000 };
var PHOTO_CALLS = [
  { name: 'library', bridgeFn: 'library', args: [], answer: LIB_JSON, value: JSON.parse(LIB_JSON), message: 'Could not read your photos.' },
  { name: 'addGroup', bridgeFn: 'addGroup', args: ['Holiday'], answer: '{"ok":true,"id":"gholiday1"}', value: { id: 'gholiday1' }, message: 'Could not add the group.' },
  { name: 'renameGroup', bridgeFn: 'renameGroup', args: ['gholiday1', 'Trip'], answer: '{"ok":true}', value: undefined, message: 'Could not rename the group.' },
  { name: 'deleteGroup', bridgeFn: 'deleteGroup', args: ['gholiday1'], answer: '{"ok":true}', value: undefined, message: 'Could not delete the group.' },
  { name: 'deletePhoto', bridgeFn: 'deletePhoto', args: ['gholiday1', 'pbeach00001'], answer: '{"ok":true}', value: undefined, message: 'Could not remove the photo.' },
  { name: 'uploadStart', bridgeFn: 'uploadStart', args: ['gholiday1'], answer: JSON.stringify(UPLOAD),
    value: { url: UPLOAD.url, base: UPLOAD.base, code: UPLOAD.code, expires: UPLOAD.expires }, message: 'Could not start adding photos.' },
  { name: 'uploadStop', bridgeFn: 'uploadStop', args: [], answer: '{"ok":true}', value: undefined, message: 'Could not stop adding photos.' },
  { name: 'materialJobs', bridgeFn: 'materialJobs', args: [], answer: JOBS_JSON, value: JSON.parse(JOBS_JSON), message: 'Could not read the photos being prepared.' },
  { name: 'saveMaterial', bridgeFn: 'saveMaterial', args: ['space', 'pabcdefgh01', 'panel', 'data:image/jpeg;base64,/9j/'], answer: '{"ok":true,"done":false}',
    value: { done: false }, message: 'Could not save a prepared photo.' },
  { name: 'recrop', bridgeFn: 'recrop', args: ['gholiday1', 'pbeach00001', '0.25,0.5,2'], answer: '{"ok":true}', value: undefined,
    message: 'Could not save the new framing.' },
  { name: 'recropState', bridgeFn: 'recropState', args: ['gholiday1', 'pbeach00001'], answer: '{"ok":true,"state":"working"}',
    value: { state: 'working', reason: '' }, message: 'Could not check the new framing.' }
];
function photoRun(P, impl, opts) {
  var over = {}, b, env, done = recorder();
  over[P.bridgeFn] = impl;
  b = bridge(over);
  if (opts && opts.remove) delete b[P.bridgeFn];
  env = load({ bridge: b });
  env.FD[P.name].apply(null, P.args.concat([done]));
  assert.strictEqual(done.calls.length, 0, P.name + ' answers asynchronously');
  env.flush();
  assert.strictEqual(done.calls.length, 1, P.name + ' calls done exactly once');
  return done.calls[0];
}
PHOTO_CALLS.forEach(function (P) {
  test(P.name + ': bridge answer -> done(null, value) once, asynchronously, with the arguments as strings', function () {
    var seen = null, call = photoRun(P, function () { seen = Array.prototype.slice.call(arguments); return P.answer; });
    assert.strictEqual(call[0], null);
    assert.deepStrictEqual(plain(call[1]), P.value);
    assert.deepStrictEqual(plain(seen), P.args);
  });
  test(P.name + ': {"ok":false,"reason"} -> error with the reason as detail', function () {
    var call = photoRun(P, function () { return '{"ok":false,"reason":"Too many groups"}'; });
    assert.deepStrictEqual(plain(call[0]), { message: P.message, detail: 'Too many groups' });
    assert.strictEqual(call.length, 1, 'no value with an error');
  });
  test(P.name + ': throws, bad JSON, "null", or a missing method -> one error, never a throw', function () {
    assert.deepStrictEqual(plain(photoRun(P, function () { throw new Error('SecurityException'); })[0]), { message: P.message, detail: 'SecurityException' });
    assert.strictEqual(photoRun(P, function () { return '{"ok":'; })[0].message, P.message);
    assert.deepStrictEqual(plain(photoRun(P, function () { return 'null'; })[0]), { message: P.message, detail: 'The TV gave no answer.' });
    assert.deepStrictEqual(plain(photoRun(P, null, { remove: true })[0]), { message: P.message, detail: 'Update CleanFrame on the TV to use photo groups.' });
  });
  test(P.name + ': no bridge -> unavailable error once, asynchronously', function () {
    var env = load(), done = recorder();
    env.FD[P.name].apply(null, P.args.concat([done]));
    assert.strictEqual(done.calls.length, 0);
    env.flush();
    assert.strictEqual(done.calls.length, 1);
    assert.strictEqual(done.calls[0][0].message, 'Open CleanFrame on the TV to use this control.');
  });
});

test('library: groups/photos not arrays -> error; ok:true without the library shape -> error', function () {
  var P = PHOTO_CALLS[0];
  ['{"v":1}', '{"groups":{},"photos":[]}', '{"groups":[],"photos":"x"}', '[]'].forEach(function (answer) {
    var call = photoRun(P, function () { return answer; });
    assert.strictEqual(call[0].message, 'Could not read your photos.', answer);
  });
});

test('addGroup: ok without a valid group id -> error', function () {
  var P = PHOTO_CALLS[1];
  ['{"ok":true}', '{"ok":true,"id":"../x"}', '{"ok":true,"id":"gTOOLONG123"}', '{"ok":true,"id":"gtoolong1234"}', '{"ok":true,"id":"gshort12"}', '{"id":"gholiday1"}'].forEach(function (answer) {
    assert.deepStrictEqual(plain(photoRun(P, function () { return answer; })[0]), { message: 'Could not add the group.', detail: 'The TV gave no group id.' }, answer);
  });
});

test('uploadStart: a bad code, url outside base, non-http base or missing expiry -> error', function () {
  var P = PHOTO_CALLS[5];
  [{ code: '48291' }, { code: '48291x' }, { code: 482913 }, { url: 'http://evil.example/u/x' }, { base: 'javascript:', url: 'javascript:x' },
   { expires: '1' }, { expires: null }, { ok: 'yes' }].forEach(function (bad) {
    var v = JSON.parse(JSON.stringify(UPLOAD));
    Object.keys(bad).forEach(function (k) { v[k] = bad[k]; });
    assert.deepStrictEqual(plain(photoRun(P, function () { return JSON.stringify(v); })[0]),
      { message: 'Could not start adding photos.', detail: 'The TV gave no upload address.' }, JSON.stringify(bad));
  });
});

test('uploadStart (v2): the stick URL alone -> base is its origin, code empty; a given base or code is still checked', function () {
  var P = PHOTO_CALLS[5], url = 'http://192.168.0.55:8097/u/0123456789abcdef0123456789abcdef';
  var call = photoRun(P, function () { return JSON.stringify({ ok: true, url: url, expires: 5 }); });
  assert.strictEqual(call[0], null);
  assert.deepStrictEqual(plain(call[1]), { url: url, base: 'http://192.168.0.55:8097', code: '', expires: 5 });
  [{ url: 'ftp://192.168.0.55/u/x' }, { url: '/u/x' }, { url: 7 }, { base: 'http://192.168.0.6:8097' }, { code: '12345' }, { code: null }].forEach(function (bad) {
    var v = { ok: true, url: url, expires: 5 };
    Object.keys(bad).forEach(function (k) { v[k] = bad[k]; });
    assert.strictEqual(photoRun(P, function () { return JSON.stringify(v); })[0].detail, 'The TV gave no upload address.', JSON.stringify(bad));
  });
});

test('uploadStart: the url must be under base as a path, not just share its first characters', function () {
  var P = PHOTO_CALLS[5];
  [{ url: 'http://192.168.0.6:80970/u/0123456789abcdef0123456789abcdef' }, { url: 'http://192.168.0.6:8097.evil.example/u/x' },
   { url: 'http://192.168.0.6:8097' }].forEach(function (bad) {
    var v = JSON.parse(JSON.stringify(UPLOAD));
    Object.keys(bad).forEach(function (k) { v[k] = bad[k]; });
    assert.deepStrictEqual(plain(photoRun(P, function () { return JSON.stringify(v); })[0]),
      { message: 'Could not start adding photos.', detail: 'The TV gave no upload address.' }, JSON.stringify(bad));
  });
});

/* Review of 30e7356: the origin is checked for whitespace but the rest of the url was not. */
test('uploadStart: whitespace or a newline anywhere in the url -> error', function () {
  var P = PHOTO_CALLS[5];
  [UPLOAD.url + '\n', UPLOAD.url.replace('/u/', '/u /'), UPLOAD.url + '\r\nX-Evil: 1', UPLOAD.url.replace('/u/', '/u/\t'), UPLOAD.url + ' '].forEach(function (url) {
    var v = JSON.parse(JSON.stringify(UPLOAD));
    v.url = url;
    assert.deepStrictEqual(plain(photoRun(P, function () { return JSON.stringify(v); })[0]),
      { message: 'Could not start adding photos.', detail: 'The TV gave no upload address.' }, JSON.stringify(url));
  });
  assert.strictEqual(photoRun(P, function () { return JSON.stringify(UPLOAD); })[0], null, 'the clean url still passes');
});

test('uploadStart: "No network connection" reaches the screen as the detail', function () {
  var call = photoRun(PHOTO_CALLS[5], function () { return '{"ok":false,"reason":"No network connection"}'; });
  assert.deepStrictEqual(plain(call[0]), { message: 'Could not start adding photos.', detail: 'No network connection' });
});

/* A missing id must not reach the bridge as "undefined" (review of 7c2b1e5): refused here, done once, async. */
var ID_ARGS = { renameGroup: [0], deleteGroup: [0], deletePhoto: [0, 1], uploadStart: [0], saveMaterial: [0, 1], recrop: [0, 1], recropState: [0, 1] };
test('photo calls: a missing group or photo id (undefined, null, "") -> one async error, nothing sent to the TV', function () {
  var n = 0;
  PHOTO_CALLS.forEach(function (P) {
    (ID_ARGS[P.name] || []).forEach(function (at) {
      [undefined, null, ''].forEach(function (bad) {
        var sent = false, Q = {}, call;
        Object.keys(P).forEach(function (k) { Q[k] = P[k]; });
        Q.args = P.args.slice(); Q.args[at] = bad;
        call = photoRun(Q, function () { sent = true; return P.answer; });
        assert.strictEqual(sent, false, P.name + ' arg ' + at + ' = ' + bad + ' was sent');
        assert.deepStrictEqual(plain(call[0]), { message: P.message, detail: 'No group or photo was given.' }, P.name + ' arg ' + at);
        assert.strictEqual(call.length, 1, 'no value with an error');
        n++;
      });
    });
  });
  assert.strictEqual(n, 33, 'every id argument of every photo call');
  var seen = null;
  photoRun({ name: 'deleteGroup', bridgeFn: 'deleteGroup', args: [5] }, function (g) { seen = g; return '{"ok":true}'; });
  assert.strictEqual(seen, '5', 'a non-string id is still sent as a string');
});

test('materialJobs: jobs must be a list (the jobs themselves are checked by materials.js)', function () {
  var P = PHOTO_CALLS[7];
  ['{"v":1}', '{"v":1,"jobs":{}}', '{"jobs":"x"}', '[]'].forEach(function (answer) {
    assert.deepStrictEqual(plain(photoRun(P, function () { return answer; })[0]), { message: P.message, detail: 'The TV gave no job list.' }, answer);
  });
  assert.deepStrictEqual(plain(photoRun(P, function () { return '{"v":1,"jobs":[]}'; })[1]), { v: 1, jobs: [] });
});

test('saveMaterial: ok:true with a boolean done -> { done }; anything else -> error; the data URL is passed whole', function () {
  var P = PHOTO_CALLS[8], seen = null, big = 'data:image/jpeg;base64,' + 'A'.repeat(4000);
  assert.deepStrictEqual(plain(photoRun(P, function () { return '{"ok":true,"done":true}'; })[1]), { done: true });
  ['{"ok":true}', '{"ok":true,"done":"yes"}', '{"ok":1,"done":true}', '{"done":true}'].forEach(function (answer) {
    assert.strictEqual(photoRun(P, function () { return answer; })[0].detail, 'The TV gave no answer.', answer);
  });
  assert.strictEqual(photoRun(P, function () { return '{"ok":false,"reason":"The panel has the wrong size."}'; })[0].detail, 'The panel has the wrong size.');
  var env = load({ bridge: bridge({ saveMaterial: function () { seen = Array.prototype.slice.call(arguments); return '{"ok":true,"done":false}'; } }) });
  env.FD.saveMaterial('gholiday1', 'pabcdefgh01', 'study-soft', big, function () {});
  env.flush();
  assert.deepStrictEqual(seen, ['gholiday1', 'pabcdefgh01', 'study-soft', big]);
});

test('recropState: one of working/saving/done/failed (with its reason); anything else -> error; recrop passes the crop as it is', function () {
  var P = PHOTO_CALLS[10], seen = null;
  assert.deepStrictEqual(plain(photoRun(P, function () { return '{"ok":true,"state":"failed","reason":"That photo could not be read."}'; })[1]),
    { state: 'failed', reason: 'That photo could not be read.' });
  ['saving', 'done'].forEach(function (s) { assert.strictEqual(photoRun(P, function () { return '{"ok":true,"state":"' + s + '"}'; })[1].state, s); });
  ['{"ok":true,"state":"maybe"}', '{"ok":true}', '{"state":"done"}'].forEach(function (answer) {
    assert.strictEqual(photoRun(P, function () { return answer; })[0].detail, 'The TV gave no answer.', answer);
  });
  var env = load({ bridge: bridge({ recrop: function () { seen = Array.prototype.slice.call(arguments); return '{"ok":true}'; } }) });
  env.FD.recrop('gholiday1', 'pbeach00001', '0.333333,0.5,1.25', function () {});
  env.flush();
  assert.deepStrictEqual(seen, ['gholiday1', 'pbeach00001', '0.333333,0.5,1.25']);
});

test('materialsWake: returns at once; the handler runs in its own task; an early wake waits for the handler', function () {
  var env = load({ bridge: bridge() }), n = 0;
  assert.doesNotThrow(function () { env.FD.materialsWake(); });
  assert.strictEqual(env.timers.length, 0, 'no handler yet: nothing scheduled');
  env.FD.onMaterialsWake(function () { n++; });
  assert.strictEqual(n, 0, 'delivered asynchronously');
  env.flush();
  assert.strictEqual(n, 1, 'the early wake is delivered once');
  env.FD.materialsWake(); env.FD.materialsWake();
  assert.strictEqual(n, 1);
  env.flush();
  assert.strictEqual(n, 3, 'each wake calls the handler');
  env.FD.onMaterialsWake(function () { throw new Error('queue blew up'); });
  env.FD.materialsWake();
  assert.doesNotThrow(function () { env.flush(); }, 'a throwing handler is contained');
  env.FD.onMaterialsWake(null);
  env.FD.materialsWake();
  assert.strictEqual(env.timers.length, 0, 'a cleared handler: the wake is kept, not run');
});

test('renameGroup/deleteGroup/deletePhoto/uploadStop: ok must be exactly true', function () {
  PHOTO_CALLS.filter(function (P) { return P.value === undefined; }).forEach(function (P) {
    assert.strictEqual(photoRun(P, function () { return '{"ok":1}'; })[0].detail, 'The TV gave no answer.', P.name);
    assert.strictEqual(photoRun(P, function () { return '{}'; })[0].detail, 'The TV gave no answer.', P.name);
  });
});

test('uploadStop without a callback (closing a screen) answers nobody and does not throw', function () {
  var n = 0, env = load({ bridge: bridge({ uploadStop: function () { n++; return '{"ok":true}'; } }) });
  assert.doesNotThrow(function () { env.FD.uploadStop(); env.flush(); });
  assert.strictEqual(n, 1);
  assert.doesNotThrow(function () { var off = load(); off.FD.uploadStop(); off.flush(); });
});

test('libraryNow: synchronous library, or null (no bridge, no method, throw, bad JSON, bad shape); no timers', function () {
  var env = load({ bridge: bridge({ library: function () { return LIB_JSON; } }) });
  assert.deepStrictEqual(plain(env.FD.libraryNow()), JSON.parse(LIB_JSON));
  assert.strictEqual(env.timers.length, 0, 'libraryNow is synchronous');
  assert.strictEqual(load().FD.libraryNow(), null);
  assert.strictEqual(load({ bridge: bridge() }).FD.libraryNow(), null, 'an older APK without library()');
  assert.strictEqual(load({ bridge: bridge({ library: function () { throw new Error('x'); } }) }).FD.libraryNow(), null);
  assert.strictEqual(load({ bridge: bridge({ library: function () { return '{'; } }) }).FD.libraryNow(), null);
  assert.strictEqual(load({ bridge: bridge({ library: function () { return '{"ok":true}'; } }) }).FD.libraryNow(), null);
});

/* Review of 30e7356: library() refuses {"ok":false}; libraryNow() took it when it carried the lists. */
test('libraryNow: {"ok":false} is refused like library(), even with groups and photos', function () {
  var refused = '{"ok":false,"reason":"Storage unavailable","groups":[],"photos":[]}';
  assert.strictEqual(load({ bridge: bridge({ library: function () { return refused; } }) }).FD.libraryNow(), null);
  assert.strictEqual(load({ bridge: bridge({ library: function () { return '{"ok":false}'; } }) }).FD.libraryNow(), null);
  assert.strictEqual(photoRun(PHOTO_CALLS[0], function () { return refused; })[0].detail, 'Storage unavailable', 'library() agrees');
  assert.deepStrictEqual(plain(load({ bridge: bridge({ library: function () { return '{"ok":true,"groups":[],"photos":[]}'; } }) }).FD.libraryNow()),
    { ok: true, groups: [], photos: [] }, 'ok:true with the lists is a library');
});

/* ---------- Bluetooth (web/bluetooth.js; the Android fixer's bridge contract) ---------- */

/* Run bluetooth() against a bridge answer (a function, or null to leave the method out); returns
   the single done() call, after checking it came asynchronously and exactly once. */
function btRun(impl) {
  var over = {}, b, env, done = recorder();
  if (impl) over.bluetooth = impl;
  b = bridge(over);
  env = load({ bridge: b });
  env.FD.bluetooth(done);
  assert.strictEqual(done.calls.length, 0, 'bluetooth answers asynchronously');
  env.flush();
  assert.strictEqual(done.calls.length, 1, 'bluetooth calls done exactly once');
  return done.calls[0];
}
function btErr(impl) { var c = btRun(impl); assert.strictEqual(c.length, 1, 'no value with an error'); return plain(c[0]); }

test('bluetooth: ok answer -> done(null, { on, devices }) once, connected first then by name, no extra fields', function () {
  var call = btRun(function () {
    assert.strictEqual(arguments.length, 0, 'no arguments to the TV');
    return JSON.stringify({ ok: true, on: true, canUnpair: true, extra: 1, devices: [
      { name: 'zebra keyboard', kind: 'input', connected: false, id: 'bt1', unpair: false },
      { name: 'Sony WH-1000XM4', kind: 'audio', connected: true, id: 'bt2', unpair: true, address: '00:11:22:33:44:55' },
      { name: 'Apple TV Remote', kind: 'input', connected: false, id: 'bt3', unpair: false }] });
  });
  assert.strictEqual(call[0], null);
  assert.deepStrictEqual(plain(call[1]), { on: true, canUnpair: true, devices: [
    { name: 'Sony WH-1000XM4', kind: 'audio', connected: true, id: 'bt2', unpair: true },
    { name: 'Apple TV Remote', kind: 'input', connected: false, id: 'bt3', unpair: false },
    { name: 'zebra keyboard', kind: 'input', connected: false, id: 'bt1', unpair: false }] });
});

test('bluetooth: off with no devices; an empty list is an answer', function () {
  assert.deepStrictEqual(plain(btRun(function () { return '{"ok":true,"on":false,"devices":[]}'; })[1]), { on: false, canUnpair: false, devices: [] });
});

test('bluetooth: bad entries are dropped, unknown kinds are "other", names are trimmed, folded and cut at 80', function () {
  var long = 'H' + new Array(100).join('x');
  var call = btRun(function () {
    return JSON.stringify({ ok: true, on: true, devices: [null, 7, 'x', { kind: 'audio', connected: true }, { name: '', kind: 'audio', connected: true },
      { name: '   ', kind: 'audio', connected: true }, { name: 'No state', kind: 'audio' }, { name: 'Str state', kind: 'audio', connected: 'true' },
      { name: 5, kind: 'audio', connected: true }, { name: '  Buds\n Pro  ', kind: 'headset', connected: false }, { name: long, kind: 'input', connected: false }] });
  });
  assert.strictEqual(call[0], null);
  assert.deepStrictEqual(plain(call[1]).devices, [
    { name: 'Buds Pro', kind: 'other', connected: false, id: '', unpair: false }, { name: long.slice(0, 80), kind: 'input', connected: false, id: '', unpair: false }]);
});

test('bluetooth: the 80-character cut never leaves half a surrogate pair or a trailing space', function () {
  var a79 = new Array(80).join('a'), call = btRun(function () {
    return JSON.stringify({ ok: true, on: true, devices: [
      { name: a79 + '\uD83C\uDFA7 Headphones', kind: 'audio', connected: true },
      { name: 'b' + a79.slice(1) + ' Speaker', kind: 'audio', connected: false },
      { name: '  c' + a79.slice(1) + '\uD83C\uDFA7', kind: 'audio', connected: false },
      { name: 'd' + a79.slice(2) + '\uD83C\uDFA7', kind: 'audio', connected: false }] });
  });
  assert.strictEqual(a79.length, 79);
  assert.deepStrictEqual(plain(call[1]).devices.map(function (d) { return d.name; }), [
    a79, 'b' + a79.slice(1), 'c' + a79.slice(1), 'd' + a79.slice(2) + '\uD83C\uDFA7']);
});

test('bluetooth: {"ok":false,"reason"} -> error with the reason (permission, no adapter)', function () {
  assert.deepStrictEqual(btErr(function () { return '{"ok":false,"reason":"Bluetooth permission not granted"}'; }),
    { message: 'Could not read Bluetooth.', detail: 'Bluetooth permission not granted' });
  assert.deepStrictEqual(btErr(function () { return '{"ok":false}'; }), { message: 'Could not read Bluetooth.', detail: 'The TV gave no answer.' });
  assert.deepStrictEqual(btErr(function () { return '{"on":true,"devices":[]}'; }), { message: 'Could not read Bluetooth.', detail: 'The TV gave no answer.' }, 'ok must be exactly true');
});

test('bluetooth: on/devices of the wrong shape -> error', function () {
  ['{"ok":true}', '{"ok":true,"on":"yes","devices":[]}', '{"ok":true,"on":true}', '{"ok":true,"on":true,"devices":{}}', '[]'].forEach(function (a) {
    assert.strictEqual(btErr(function () { return a; }).message, 'Could not read Bluetooth.', a);
  });
  assert.strictEqual(btErr(function () { return '{"ok":true,"on":1,"devices":[]}'; }).detail, 'The TV gave no Bluetooth state.');
});

test('bluetooth: an older APK without the method, a throw, bad JSON, "null", undefined -> one error, never a throw', function () {
  assert.deepStrictEqual(btErr(null), { message: 'Could not read Bluetooth.', detail: 'Update CleanFrame on the TV to see Bluetooth here.' });
  assert.deepStrictEqual(btErr(function () { throw new Error('SecurityException: BLUETOOTH_CONNECT'); }),
    { message: 'Could not read Bluetooth.', detail: 'SecurityException: BLUETOOTH_CONNECT' });
  assert.strictEqual(btErr(function () { return '{"ok":'; }).message, 'Could not read Bluetooth.');
  assert.deepStrictEqual(btErr(function () { return 'null'; }), { message: 'Could not read Bluetooth.', detail: 'The TV gave no answer.' });
  assert.strictEqual(btErr(function () { return undefined; }).message, 'Could not read Bluetooth.');
});

test('bluetooth: no bridge -> unavailable once, asynchronously; no callback never throws; a throwing done is not called twice', function () {
  var env = load(), done = recorder(), n = 0;
  env.FD.bluetooth(done);
  assert.strictEqual(done.calls.length, 0);
  env.flush();
  assert.deepStrictEqual(plain(done.calls), [[{ message: 'Open CleanFrame on the TV to use this control.', detail: 'The web preview cannot control the TV.' }]]);
  [null, bridge({ bluetooth: function () { throw new Error('x'); } }), bridge({ bluetooth: function () { return '{"ok":true,"on":true,"devices":[]}'; } })].forEach(function (b) {
    var e = load(b ? { bridge: b } : {});
    assert.doesNotThrow(function () { e.FD.bluetooth(); e.FD.bluetooth(42); e.flush(); });
  });
  env = load({ bridge: bridge({ bluetooth: function () { return '{"ok":true,"on":true,"devices":[]}'; } }) });
  env.FD.bluetooth(function () { n++; throw new Error('render blew up'); });
  assert.throws(function () { env.flush(); }, /render blew up/);
  assert.strictEqual(n, 1);
});

test('bluetooth: canUnpair and unpair are true only when exactly true; unpair needs an id; an older APK has neither', function () {
  function devices(v) { return plain(btRun(function () { return JSON.stringify(v); })[1]); }
  var d = devices({ ok: true, on: true, canUnpair: 1, devices: [
    { name: 'A', kind: 'audio', connected: false, id: 'bt1', unpair: 1 },
    { name: 'B', kind: 'audio', connected: false, id: 'bt2', unpair: 'true' },
    { name: 'C', kind: 'audio', connected: false, unpair: true },
    { name: 'D', kind: 'audio', connected: false, id: '', unpair: true },
    { name: 'E', kind: 'audio', connected: false, id: 7, unpair: true },
    { name: 'F', kind: 'audio', connected: false, id: 'bt6', unpair: true }] });
  assert.strictEqual(d.canUnpair, false, 'canUnpair 1 is not true');
  assert.deepStrictEqual(d.devices.map(function (x) { return [x.name, x.id, x.unpair]; }),
    [['A', 'bt1', false], ['B', 'bt2', false], ['C', '', false], ['D', '', false], ['E', '', false], ['F', 'bt6', true]]);
  assert.deepStrictEqual(devices({ ok: true, on: true, devices: [{ name: 'Old', kind: 'audio', connected: true }] }),
    { on: true, canUnpair: false, devices: [{ name: 'Old', kind: 'audio', connected: true, id: '', unpair: false }] }, 'an older APK');
});

function unpairRun(b, id) {
  var env = load(b === undefined ? {} : { bridge: b }), done = recorder();
  env.FD.bluetoothUnpair(id, done);
  assert.strictEqual(done.calls.length, 0, 'bluetoothUnpair answers asynchronously');
  env.flush();
  assert.strictEqual(done.calls.length, 1, 'done exactly once');
  return done.calls[0];
}
/* ---------- Uninstall on the long-press menu ---------- */

test('canUninstall: only true or "true" is yes; the package is passed on, nothing else', function () {
  [[true, true], ['true', true], [false, false], ['false', false], ['', false], [1, false], [null, false], [undefined, false], ['yes', false]].forEach(function (x) {
    var seen = [], env = load({ bridge: bridge({ canUninstall: function (p) { seen.push([p, arguments.length]); return x[0]; } }) });
    assert.strictEqual(env.FD.canUninstall('org.xbmc.kodi'), x[1], String(x[0]));
    assert.deepStrictEqual(seen, [['org.xbmc.kodi', 1]]);
  });
});
test('canUninstall: an older APK, no bridge, a throw or no package is no, never a crash', function () {
  assert.strictEqual(load({ bridge: bridge() }).FD.canUninstall('org.xbmc.kodi'), false, 'a bridge without the method');
  assert.strictEqual(load().FD.canUninstall('org.xbmc.kodi'), false, 'no bridge');
  assert.strictEqual(load({ bridge: bridge({ canUninstall: function () { throw new Error('x'); } }) }).FD.canUninstall('org.xbmc.kodi'), false, 'a throw');
  var asked = 0, env = load({ bridge: bridge({ canUninstall: function () { asked++; return true; } }) });
  [undefined, null, '', 7, {}].forEach(function (bad) { assert.strictEqual(env.FD.canUninstall(bad), false, String(bad)); });
  assert.strictEqual(asked, 0, 'a missing id never reaches the TV');
});
function uninstallRun(b, pkg) {
  var env = load({ bridge: b }), done = recorder();
  env.FD.uninstall(pkg, done);
  assert.strictEqual(done.calls.length, 0, 'uninstall answers asynchronously');
  env.flush();
  assert.strictEqual(done.calls.length, 1, 'exactly once');
  return done.calls[0];
}
test('uninstall: "" -> done(null), once, the package passed on; a reason or a throw -> an error carrying it', function () {
  var seen = [], reason = 'Android has no uninstall screen here.';
  assert.strictEqual(uninstallRun(bridge({ uninstall: function (p) { seen.push([p, arguments.length]); return ''; } }), 'org.xbmc.kodi')[0], null);
  assert.deepStrictEqual(seen, [['org.xbmc.kodi', 1]]);
  assert.strictEqual(uninstallRun(bridge({ uninstall: function () { return reason; } }), 'a.b')[0].detail, reason);
  assert.strictEqual(uninstallRun(bridge({ uninstall: function () { throw new Error('SecurityException'); } }), 'a.b')[0].detail, 'SecurityException');
  assert.strictEqual(uninstallRun(bridge({ uninstall: function () { return null; } }), 'a.b')[0].detail, 'The TV gave no answer.');
});
test('uninstall: a missing id never reaches the TV; an older APK or no bridge is an answer; done is optional', function () {
  var asked = 0, b = bridge({ uninstall: function () { asked++; return ''; } });
  [undefined, null, '', 7].forEach(function (bad) { assert.strictEqual(uninstallRun(b, bad)[0].detail, 'No app was chosen.', String(bad)); });
  assert.strictEqual(asked, 0);
  assert.strictEqual(uninstallRun(bridge(), 'a.b')[0].detail, 'Update CleanFrame on the TV to uninstall from here.');
  var env = load(), done = recorder();
  env.FD.uninstall('a.b', done); env.flush();
  assert.strictEqual(done.calls[0][0].message, 'Open CleanFrame on the TV to use this control.');
  var e2 = load({ bridge: bridge({ uninstall: function () { return ''; } }) });
  assert.doesNotThrow(function () { e2.FD.uninstall('a.b'); e2.FD.uninstall('a.b', 42); e2.flush(); });
});

test('bluetoothUnpair: "" -> done(null), once, the id passed on; a reason or a throw -> an error carrying it', function () {
  var seen = [], reason = 'Remotes and other devices stay paired here so the TV can\'t lose its remote. Use TV settings to remove them.';
  assert.strictEqual(unpairRun(bridge({ bluetoothUnpair: function (id) { seen.push([id, arguments.length]); return ''; } }), 'bt3')[0], null);
  assert.deepStrictEqual(seen, [['bt3', 1]], 'called once, with the id');
  assert.strictEqual(unpairRun(bridge({ bluetoothUnpair: function () { return reason; } }), 'bt1')[0].detail, reason);
  assert.strictEqual(unpairRun(bridge({ bluetoothUnpair: function () { throw new Error('SecurityException'); } }), 'bt1')[0].detail, 'SecurityException');
  assert.strictEqual(unpairRun(bridge({ bluetoothUnpair: function () { return null; } }), 'bt1')[0].detail, 'The TV gave no answer.');
});
test('bluetoothUnpair: a bad id never reaches the TV; a bridge without the method is an answer, not a crash', function () {
  var asked = 0, b = bridge({ bluetoothUnpair: function () { asked++; return ''; } });
  [undefined, null, '', 7, {}, ['bt1'], true].forEach(function (bad) {
    assert.strictEqual(unpairRun(b, bad)[0].detail, 'No device was chosen.', JSON.stringify(bad));
  });
  assert.strictEqual(asked, 0, 'the TV was never asked');
  assert.strictEqual(unpairRun(bridge(), 'bt1')[0].detail, 'Update CleanFrame on the TV to unpair from here.');
  assert.strictEqual(unpairRun(undefined, 'bt1')[0].message, 'Open CleanFrame on the TV to use this control.');
  var env = load({ bridge: bridge({ bluetoothUnpair: function () { return ''; } }) });
  assert.doesNotThrow(function () { env.FD.bluetoothUnpair('bt1'); env.FD.bluetoothUnpair('bt1', 42); env.flush(); }, 'done is optional');
});

function pairRun(b) {
  var env = load(b === undefined ? {} : { bridge: b }), done = recorder();
  env.FD.bluetoothPair(done);
  assert.strictEqual(done.calls.length, 0, 'bluetoothPair answers asynchronously');
  env.flush();
  assert.strictEqual(done.calls.length, 1, 'done exactly once');
  return done.calls[0];
}
test('bluetoothPair: "" -> done(null); a reason, a throw or an older APK -> the TV could not open it, with the reason', function () {
  var seen = [];
  assert.strictEqual(pairRun(bridge({ bluetoothPair: function () { seen.push(arguments.length); return ''; } }))[0], null);
  assert.deepStrictEqual(seen, [0], 'called once, with no arguments');
  assert.deepStrictEqual(plain(pairRun(bridge({ bluetoothPair: function () { return 'Pairing is not available on this TV'; } }))[0]),
    { message: 'The TV could not open this screen.', detail: 'Pairing is not available on this TV' });
  assert.strictEqual(pairRun(bridge({ bluetoothPair: function () { throw new Error('ActivityNotFoundException'); } }))[0].detail, 'ActivityNotFoundException');
  assert.strictEqual(pairRun(bridge())[0].detail, 'Update CleanFrame on the TV to pair from here.');
  assert.strictEqual(pairRun(bridge({ bluetoothPair: function () { return null; } }))[0].detail, 'The TV gave no answer.');
  assert.strictEqual(pairRun(undefined)[0].message, 'Open CleanFrame on the TV to use this control.');
});

/* ---------- toHome() ---------- */

function escPairs(n) {
  var out = [], i;
  for (i = 0; i < n; i++) {
    out.push({ type: 'keydown', keyCode: 27, which: 27, bubbles: true, cancelable: true });
    out.push({ type: 'keyup', keyCode: 27, which: 27, bubbles: true, cancelable: true });
  }
  return out;
}

test('toHome: sheet and appearance open -> Escape down/up pairs until both close', function () {
  var doc = fakeDocument({ sheet: { hidden: false }, appearance: { hidden: false } });
  var env = load({ document: doc });
  env.FD.toHome();
  assert.strictEqual(doc.panels.sheet.hidden, true);
  assert.strictEqual(doc.panels.appearance.hidden, true);
  assert.deepStrictEqual(doc.events, escPairs(2));
});

test('toHome: only appearance open -> one Escape pair', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: false } });
  load({ document: doc }).FD.toHome();
  assert.strictEqual(doc.panels.appearance.hidden, true);
  assert.deepStrictEqual(doc.events, escPairs(1));
});

test('toHome: nothing open -> no events', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true } });
  load({ document: doc }).FD.toHome();
  assert.deepStrictEqual(doc.events, []);
});

test('toHome: a panel that never closes stops after 12 Escape pairs', function () {
  var doc = fakeDocument({ sheet: { hidden: false, stuck: true }, appearance: { hidden: true } });
  load({ document: doc }).FD.toHome();
  assert.deepStrictEqual(doc.events, escPairs(12));
});

/* The Adjust screen (#photoAdjust, web/photo-adjust.js) is neither a sheet nor a settings detail:
   main.js hides #sheet and #appearance under it, so it must count as open by itself. */
test('toHome: Adjust open on a photo -> Escape to the buttons, Escape cancels, then the group page closes', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true },
    photoAdjust: { hidden: false, s: { zone: 'image', mode: 'move', b: 0, c: null, w: 4000, h: 3000 } } });
  load({ document: doc }).FD.toHome();
  assert.strictEqual(doc.panels.photoAdjust.hidden, true);
  assert.strictEqual(doc.panels.photoAdjust.s.zone, 'bar');
  assert.strictEqual(doc.panels.appearance.hidden, true);
  assert.deepStrictEqual(doc.events, escPairs(3));
});

test('toHome: Adjust still loading its photo -> one Escape cancels it', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true },
    photoAdjust: { hidden: false, s: { zone: 'image', mode: 'move', b: 0, c: null, w: 0, h: 0 } } });
  load({ document: doc }).FD.toHome();
  assert.strictEqual(doc.panels.photoAdjust.hidden, true);
  assert.deepStrictEqual(doc.events, escPairs(2));
});

test('toHome: Adjust hidden (never opened, or closed) -> not counted as open', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true }, photoAdjust: { hidden: true, s: {} } });
  load({ document: doc }).FD.toHome();
  assert.deepStrictEqual(doc.events, []);
});

test('toHome: Adjust saving (ignores keys) -> stops after 12 Escape pairs, no throw', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true },
    photoAdjust: { hidden: false, busy: true, s: { zone: 'bar', mode: 'move', b: 4, c: null, w: 4000, h: 3000 } } });
  load({ document: doc }).FD.toHome();
  assert.strictEqual(doc.panels.photoAdjust.hidden, false);
  assert.deepStrictEqual(doc.events, escPairs(12));
});

var UTILS_AWAKE = 'utils quiet-utils is-text is-awake', UTILS_QUIET = 'utils quiet-utils is-text';

test('toHome: nothing open, focus on the utility row -> one Escape pair, focus back on the dock', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true }, utils: { className: UTILS_AWAKE } });
  load({ document: doc }).FD.toHome();
  assert.strictEqual(doc.panels.utils.className, UTILS_QUIET);
  assert.deepStrictEqual(doc.events, escPairs(1));
});

test('toHome: sheet open over the utility row -> sheet closes, then one more pair to the dock', function () {
  var doc = fakeDocument({ sheet: { hidden: false }, appearance: { hidden: true }, utils: { className: UTILS_AWAKE } });
  load({ document: doc }).FD.toHome();
  assert.strictEqual(doc.panels.sheet.hidden, true);
  assert.strictEqual(doc.panels.utils.className, UTILS_QUIET);
  assert.deepStrictEqual(doc.events, escPairs(2));
});

test('toHome: focus already on the dock (#utils not awake) -> no events', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true }, utils: { className: UTILS_QUIET } });
  load({ document: doc }).FD.toHome();
  assert.deepStrictEqual(doc.events, []);
});

/* is-awake is a class token, not a substring: a class like "is-awakened" is not focus. */
test('toHome: #utils class merely containing "is-awake" as a substring -> no events', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true }, utils: { className: 'utils is-awakened' } });
  load({ document: doc }).FD.toHome();
  assert.deepStrictEqual(doc.events, []);
});

test('toHome: a utility row that never sleeps stops after 12 Escape pairs', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true }, utils: { className: UTILS_AWAKE, stuck: true } });
  load({ document: doc }).FD.toHome();
  assert.deepStrictEqual(doc.events, escPairs(12));
});

/* If upstream renames #sheet/#appearance, toHome must not throw (MainActivity calls it through
   evaluateJavascript, which would swallow the error and Home would silently stop working). */
test('toHome: #sheet/#appearance missing -> no throw, no events', function () {
  var doc = fakeDocument({});
  var env = load({ document: doc });
  env.FD.toHome();
  assert.deepStrictEqual(doc.events, []);
});

/* ---------- ready signal (AndroidHome.ready) ---------- */

/* A fake document with querySelector('[data-action]') and a controllable MutationObserver. */
function readyEnv(opts) {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true } });
  var observers = [], drawn = !!opts.drawn, selectors = [];
  doc.documentElement = { nodeName: 'HTML' };
  doc.querySelector = function (sel) { selectors.push(sel); return drawn && sel === '[data-action]' ? { nodeName: 'BUTTON' } : null; };
  function FakeObserver(cb) { this.cb = cb; this.connected = false; observers.push(this); }
  FakeObserver.prototype.observe = function (target, o) { this.target = target; this.opts = o; this.connected = true; };
  FakeObserver.prototype.disconnect = function () { this.connected = false; };
  var readyCalls = 0;
  var b = opts.noBridge ? null : bridge({ ready: opts.ready || function () { readyCalls++; return ''; } });
  var timers = [];
  var ctx = { setTimeout: function (fn, ms) { timers.push({ fn: fn, ms: ms }); return timers.length; }, document: doc };
  ctx.window = ctx;
  if (b) ctx.AndroidHome = b;
  if (!opts.noObserver) ctx.MutationObserver = FakeObserver;
  vm.createContext(ctx);
  vm.runInContext(SOURCE, ctx, { filename: 'web/device.js' });
  return {
    observers: observers, selectors: selectors, ctx: ctx,
    readyCalls: function () { return readyCalls; },
    draw: function () { drawn = true; },
    mutate: function () { observers.forEach(function (o) { if (o.connected) o.cb([]); }); }
  };
}

test('ready: page draws later -> AndroidHome.ready() once, observer disconnected', function () {
  var env = readyEnv({});
  assert.strictEqual(env.readyCalls(), 0, 'not drawn yet: no signal');
  assert.strictEqual(env.observers.length, 1, 'one observer');
  var o = env.observers[0];
  assert.ok(o.connected && o.target === env.ctx.document.documentElement, 'observes the document');
  assert.strictEqual(o.opts.subtree, true);
  assert.strictEqual(o.opts.childList, true);
  env.mutate();
  assert.strictEqual(env.readyCalls(), 0, 'a mutation without [data-action]: no signal');
  env.draw(); env.mutate(); env.mutate();
  assert.strictEqual(env.readyCalls(), 1, 'exactly one ready() once drawn');
  assert.strictEqual(o.connected, false, 'observer disconnected after the signal');
  assert.ok(env.selectors.every(function (s) { return s === '[data-action]'; }), 'tests only READY_JS\'s selector');
});

test('ready: already drawn when device.js runs -> ready() at once, no observer', function () {
  var env = readyEnv({ drawn: true });
  assert.strictEqual(env.readyCalls(), 1);
  assert.strictEqual(env.observers.length, 0);
});

test('ready: no bridge (web preview) or no MutationObserver -> no throw, no signal', function () {
  var env = readyEnv({ noBridge: true });
  env.draw(); env.mutate();
  assert.strictEqual(env.readyCalls(), 0);
  env = readyEnv({ noObserver: true });
  env.draw();
  assert.strictEqual(env.readyCalls(), 0, 'nothing to observe with: the native probe decides');
});

test('ready: a bridge ready() that throws is swallowed and not retried', function () {
  var n = 0;
  var env = readyEnv({ ready: function () { n++; throw new Error('bridge gone'); } });
  env.draw();
  assert.doesNotThrow(function () { env.mutate(); env.mutate(); });
  assert.strictEqual(n, 1);
});

/* ---------- Settings > Screen and the Sleep menu ---------- */

function screenRun(fnName, impl, run) {
  var over = {}, b, env, done = recorder();
  over[fnName] = impl;
  b = bridge(over); env = load({ bridge: b });
  run(env.FD, done);
  assert.strictEqual(done.calls.length, 0, fnName + ' answers asynchronously');
  env.flush();
  assert.strictEqual(done.calls.length, 1, fnName + ' calls done exactly once');
  return done.calls[0];
}

test('screenPolicy: passes both minutes as strings; canWrite comes back', function () {
  var got = [];
  var c = screenRun('screenPolicy', function (d, s) { got.push([d, s]); return '{"ok":true,"canWrite":false}'; },
    function (FD, done) { FD.screenPolicy(5, 30, done); });
  assert.deepStrictEqual(got, [['5', '30']]);
  assert.strictEqual(c[0], null);
  assert.deepStrictEqual(plain(c[1]), { canWrite: false });
});

test('screenPolicy / sleepNow / sleepCancel: refused, junk, a throw or no bridge become { message, detail }', function () {
  [['{"ok":false,"reason":"The TV has not let CleanFrame change its sleep time."}', 'The TV has not let CleanFrame change its sleep time.'],
   ['nope', null], ['null', 'The TV gave no answer.'], ['{"ok":"yes"}', 'The TV gave no answer.']].forEach(function (x) {
    var c = screenRun('sleepNow', function () { return x[0]; }, function (FD, done) { FD.sleepNow(done); });
    assert.strictEqual(c[0].message, 'Could not put the TV to sleep.', x[0]);
    if (x[1]) assert.strictEqual(c[0].detail, x[1]);
  });
  var t = screenRun('screenPolicy', function () { throw new Error('SecurityException'); }, function (FD, done) { FD.screenPolicy(0, 0, done); });
  assert.deepStrictEqual(plain(t[0]), { message: 'Could not change the screen settings.', detail: 'SecurityException' });
  var k = screenRun('sleepCancel', function () { return 'junk'; }, function (FD, done) { FD.sleepCancel(done); });
  assert.strictEqual(k[0].message, 'Could not change the screen settings.');
  ['sleepNow', 'sleepCancel'].forEach(function (n) {
    var env = load(), done = recorder();
    env.FD[n](done); env.flush();
    assert.strictEqual(done.calls.length, 1, n);
    assert.strictEqual(done.calls[0][0].message, 'Open CleanFrame on the TV to use this control.', n);
  });
});

test('screenPolicy / sleepNow / sleepCancel: an older APK says Update CleanFrame, never "x is not a function"', function () {
  [['screenPolicy', 'Could not change the screen settings.', function (FD, d) { FD.screenPolicy(1, 2, d); }],
   ['sleepNow', 'Could not put the TV to sleep.', function (FD, d) { FD.sleepNow(d); }],
   ['sleepCancel', 'Could not change the screen settings.', function (FD, d) { FD.sleepCancel(d); }]].forEach(function (x) {
    var env = load({ bridge: bridge() }), done = recorder();
    x[2](env.FD, done); env.flush();
    assert.strictEqual(done.calls.length, 1, x[0]);
    assert.deepStrictEqual(plain(done.calls[0][0]), { message: x[1], detail: 'Update CleanFrame on the TV to use this.' }, x[0]);
  });
});

test('sleepNow / sleepCancel: no arguments to the TV; {"ok":true} is success', function () {
  var got = [];
  var c = screenRun('sleepNow', function () { got.push(arguments.length); return '{"ok":true}'; }, function (FD, done) { FD.sleepNow(done); });
  var k = screenRun('sleepCancel', function () { got.push(arguments.length); return '{"ok":true}'; }, function (FD, done) { FD.sleepCancel(done); });
  assert.deepStrictEqual(got, [0, 0]);
  assert.strictEqual(c[0], null); assert.strictEqual(k[0], null);
});

test('sleepStatusNow: synchronous { playing }; null without a bridge or a usable answer', function () {
  var env = load({ bridge: bridge({ sleepStatus: function () { return '{"playing":true}'; } }) });
  assert.deepStrictEqual(plain(env.FD.sleepStatusNow()), { playing: true });
  assert.deepStrictEqual(plain(load({ bridge: bridge({ sleepStatus: function () { return '{"endsAt":5,"playing":false}'; } }) }).FD.sleepStatusNow()),
    { playing: false }, 'an old field is dropped');
  assert.strictEqual(load().FD.sleepStatusNow(), null);
  ['{}', 'junk', '{"playing":1}', '{"playing":"true"}'].forEach(function (a) {
    assert.strictEqual(load({ bridge: bridge({ sleepStatus: function () { return a; } }) }).FD.sleepStatusNow(), null, a);
  });
  assert.strictEqual(load({ bridge: bridge({ sleepStatus: function () { throw new Error('x'); } }) }).FD.sleepStatusNow(), null);
});

test('toHome brightens a dimmed Home first (CleanFrameExtras.wake), and copes without it', function () {
  var doc = fakeDocument({ sheet: { hidden: true }, appearance: { hidden: true } }), env = load({ document: doc }), woke = 0;
  env.ctx.CleanFrameExtras = { wake: function () { woke++; } };
  env.FD.toHome();
  assert.strictEqual(woke, 1);
  env.ctx.CleanFrameExtras = { wake: function () { throw new Error('x'); } };
  env.FD.toHome();
  delete env.ctx.CleanFrameExtras;
  env.FD.toHome();
});

/* ---------- Run ---------- */

var failed = 0, start = Date.now();
cases.forEach(function (c) {
  try { c.fn(); console.log('PASS ' + c.name); }
  catch (e) { failed++; console.log('FAIL ' + c.name + ' -- ' + String((e && e.message) || e).split('\n')[0]); }
});
console.log((failed ? 'FAILED ' : 'OK ') + (cases.length - failed) + '/' + cases.length + ' passed in ' + (Date.now() - start) + ' ms');
process.exitCode = failed ? 1 : 0;

/* Unit tests for web/bluetooth.js (window.CleanFrameBluetooth, Settings > Bluetooth): the Settings
   row's value, refresh() (one answer kept, late answers dropped), the page's sections (devices with
   their state and kind, Pair new device, the note, the scrolling list), what OK on a device shows
   (Unpair's question, the refusal, canUnpair false) and unpair() itself (the id, the re-reads, the result).
   Also replays the patched web/main.js (tools/android-patch.py) with a fake page: the Settings row
   sits after Apps on Home and before TV and all nine rows fit above the hint (web/home-extras.css),
   the page escapes device names (esc()) and never puts one in an action, its rows are reachable by
   the remote in order (Back, devices, Pair new device), OK on Pair new device asks the TV
   (FrameDevice.bluetoothPair), OK on a device opens Yes/No on No or the refusal, only Yes unpairs, and the stick is asked again when
   Settings or the page opens and when the page is shown again with Settings open. index.html loads
   bluetooth.js before main.js.
   No dependencies: node:assert + node:vm and a tiny runner. Run from the repo root:
     node tests/bluetooth.test.js
   Prints one line per case and exits non-zero on any failure. */
'use strict';
var assert = require('node:assert');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var WEB = path.join(__dirname, '..', 'web');
var SOURCE_PATH = process.env.BLUETOOTH_JS || path.join(WEB, 'bluetooth.js');
var SOURCE = fs.readFileSync(SOURCE_PATH, 'utf8');
var MAIN = fs.readFileSync(path.join(WEB, 'main.js'), 'utf8');
var INDEX = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
var NOTE = 'Headphones reconnect on their own when switched on. Pair new ones here.';
var REFUSED = 'Remotes and other devices stay paired here so the TV can\'t lose its remote. Use TV settings to remove them.';
var NO_UNPAIR = 'This TV can\'t unpair from here. Use TV settings to remove it.';

/* Timers are queued, run by tick() (each queued one, once): unpair()'s re-reads are deterministic. */
function load(timers) {
  var ctx = { window: {}, setTimeout: function (fn, ms) { if (timers) timers.push({ fn: fn, ms: ms }); } };
  vm.createContext(ctx);
  vm.runInContext(SOURCE, ctx, { filename: 'bluetooth.js' });
  return ctx.window.CleanFrameBluetooth;
}
function tick(timers) { var q = timers.splice(0, timers.length); q.forEach(function (t) { t.fn(); }); return q.length; }
// JSON round trip: objects made inside the vm context have another Object.prototype.
function plain(x) { return JSON.parse(JSON.stringify(x)); }
/* A FrameDevice whose bluetooth() keeps each callback, to be answered by hand (and so out of order). */
function fakeDevice() {
  var d = { pending: [], calls: 0 };
  d.bluetooth = function (done) { d.calls++; d.pending.push(done); };
  return d;
}
function dev(name, kind, connected, id, unpair) { return { name: name, kind: kind, connected: connected, id: id || '', unpair: unpair === true }; }
/* A device that answers: bluetooth() from `list` (read at each call), bluetoothUnpair() as told. */
function pairedDevice(list, unpairAnswer) {
  var d = { unpairs: [], reads: 0 };
  d.bluetooth = function (done) { d.reads++; done(null, { on: true, canUnpair: true, devices: list.slice() }); };
  d.bluetoothUnpair = function (id, done) { d.unpairs.push(id); done(unpairAnswer || null); };
  return d;
}
function answered(B, value) {
  var d = fakeDevice(), n = 0;
  B.refresh(d, function () { n++; });
  d.pending[0](null, value);
  assert.strictEqual(n, 1, 'done once');
  return B;
}

var cases = [];
function test(name, fn) { cases.push([name, fn]); }

/* ---------- web/bluetooth.js ---------- */

test('summary: Checking… before the first answer; Off; Connected: <audio device>; "<n> paired"; None paired', function () {
  var B = load();
  assert.strictEqual(B.summary(), 'Checking…');
  assert.strictEqual(answered(load(), { on: false, devices: [dev('Buds', 'audio', true)] }).summary(), 'Off', 'off wins');
  assert.strictEqual(answered(load(), { on: true, devices: [dev('Sony WH-1000XM4', 'audio', true), dev('Remote', 'input', true)] }).summary(), 'Connected: Sony WH-1000XM4');
  assert.strictEqual(answered(load(), { on: true, devices: [dev('Remote', 'input', true), dev('Buds', 'audio', true)] }).summary(), 'Connected: Buds', 'the audio one, not the first');
  assert.strictEqual(answered(load(), { on: true, devices: [dev('Remote', 'input', true), dev('Buds', 'audio', false)] }).summary(), '2 paired', 'a connected remote is not "Connected"');
  assert.strictEqual(answered(load(), { on: true, devices: [dev('Buds', 'audio', false)] }).summary(), '1 paired');
  assert.strictEqual(answered(load(), { on: true, devices: [] }).summary(), 'None paired');
});

test('summary: a failure shows its reason (permission, no adapter, older APK, no bridge)', function () {
  var B = load(), d = fakeDevice();
  B.refresh(d);
  d.pending[0]({ message: 'Could not read Bluetooth.', detail: 'Bluetooth permission not granted' });
  assert.strictEqual(B.summary(), 'Bluetooth permission not granted');
  assert.strictEqual(B.state().status, 'error');
  d = fakeDevice(); B.refresh(d); d.pending[0]({ message: 'Could not read Bluetooth.' });
  assert.strictEqual(B.summary(), 'Could not read Bluetooth.', 'no detail: the message');
  B = load(); var n = 0;
  B.refresh(null, function () { n++; });
  assert.strictEqual(n, 1, 'no FrameDevice: done at once');
  assert.strictEqual(B.summary(), 'Open CleanFrame on the TV to use Bluetooth.');
  B.refresh({}, function () { n++; });
  assert.strictEqual(n, 2, 'a FrameDevice without bluetooth(): done at once');
  assert.doesNotThrow(function () { B.refresh(null); B.refresh(fakeDevice(), 'not a function'); });
});

test('refresh: the newest request wins; a late answer to an older one is dropped; the last answer stays while asking', function () {
  var B = load(), d = fakeDevice(), got = [];
  B.refresh(d, function () { got.push('first'); });
  B.refresh(d, function () { got.push('second'); });
  d.pending[1](null, { on: true, devices: [dev('New', 'audio', true)] });
  d.pending[0](null, { on: true, devices: [dev('Old', 'audio', true)] });
  assert.deepStrictEqual(got, ['second']);
  assert.strictEqual(B.summary(), 'Connected: New');
  B.refresh(d);
  assert.strictEqual(B.summary(), 'Connected: New', 'kept while the next answer is on its way');
  assert.strictEqual(B.state().status, 'ok');
  var s = B.state(); s.devices.push(dev('x', 'audio', true)); s.devices[0].name = 'changed';
  assert.strictEqual(B.state().devices.length, 1, 'state() is a copy');
  assert.strictEqual(B.state().devices[0].name, 'New');
});

test('sections: each device with its state and kind (small text), no forward arrow, index actions; then Pair new device and the note', function () {
  var B = answered(load(), { on: true, devices: [dev('Sony WH-1000XM4', 'audio', true), dev('Keyboard', 'input', false), dev('Thing', 'other', false)] });
  var s = plain(B.sections({ f: {} }));
  assert.deepStrictEqual(s.map(function (x) { return x.id; }), ['btdevices', 'btpair']);
  assert.strictEqual(s[0].title, 'Paired devices');
  assert.deepStrictEqual(s[0].items.map(function (i) { return [i.action, i.label, i.description, i.bare, i.bt]; }), [
    ['bluetooth.device.0', 'Sony WH-1000XM4', 'Connected · Audio', true, 'device'],
    ['bluetooth.device.1', 'Keyboard', 'Not connected · Input', true, 'device'],
    ['bluetooth.device.2', 'Thing', 'Not connected · Other', true, 'device']]);
  assert.ok(s[0].items.every(function (i) { return !i.axis && !i.pref && typeof i.hint === 'string' && i.hint; }));
  assert.deepStrictEqual(s[1].items.map(function (i) { return [i.action, i.label, i.bt, !!i.bare]; }), [['bluetooth.pair', 'Pair new device', 'pair', false]]);
  assert.strictEqual(s[1].reason, NOTE);
  assert.strictEqual(B.NOTE, NOTE);
});

test('sections: nothing to list says why above the note: checking, a failure, off, none paired', function () {
  function pair(B) { var s = plain(B.sections({ f: {} })); assert.strictEqual(s.length, 1, 'only the Pair section'); assert.strictEqual(s[0].title, 'Paired devices'); return s[0].reason; }
  assert.strictEqual(pair(load()), 'Checking Bluetooth… ' + NOTE);
  var B = load(), d = fakeDevice(); B.refresh(d); d.pending[0]({ message: 'Could not read Bluetooth.', detail: 'Bluetooth permission not granted' });
  assert.strictEqual(pair(B), 'Bluetooth permission not granted. ' + NOTE);
  assert.strictEqual(pair(answered(load(), { on: false, devices: [] })), 'Bluetooth is off. ' + NOTE);
  assert.strictEqual(pair(answered(load(), { on: true, devices: [] })), 'No devices paired yet. ' + NOTE);
});

test('sections: more than four devices scroll with the focus, with arrows on the title only then', function () {
  var list = [], i, B, t, s;
  for (i = 0; i < 7; i++) list.push(dev('D' + i, 'audio', false));
  B = answered(load(), { on: true, devices: list });
  assert.strictEqual(B.VISIBLE, 4);
  function arrows(up, down) { return '<' + (up ? 'U' : '') + (down ? 'D' : '') + '>'; }
  t = { f: { action: 'bluetooth.device.0' } };
  s = plain(B.sections(t, arrows));
  assert.deepStrictEqual(s[0].window, [0, 4]); assert.strictEqual(s[0].title, 'Paired devices<D>');
  t.f.action = 'bluetooth.device.5'; s = plain(B.sections(t, arrows));
  assert.deepStrictEqual(s[0].window, [2, 6]); assert.strictEqual(s[0].title, 'Paired devices<UD>');
  t.f.action = 'bluetooth.pair'; s = plain(B.sections(t, arrows));
  assert.deepStrictEqual(s[0].window, [2, 6], 'focus off the list: the window stays');
  t.f.action = 'bluetooth.device.6'; s = plain(B.sections(t, arrows));
  assert.deepStrictEqual(s[0].window, [3, 7]); assert.strictEqual(s[0].title, 'Paired devices<U>');
  B = answered(load(), { on: true, devices: list.slice(0, 4) });
  assert.strictEqual(plain(B.sections({ f: {} }, arrows))[0].title, 'Paired devices', 'four fit: no arrows');
});

test('deviceMessage: an unpairable device asks "Unpair <name>?" with the note and carries its id; null for an index it does not have', function () {
  var B = answered(load(), { on: true, canUnpair: true, devices: [dev('Buds', 'audio', false, 'bt4', true)] });
  assert.deepStrictEqual(plain(B.deviceMessage(0)), { title: 'Unpair Buds?', line: '', note: '', ask: { id: 'bt4', name: 'Buds' } });
  assert.strictEqual(B.deviceMessage(1), null);
  assert.strictEqual(load().deviceMessage(0), null);
});

test('deviceMessage: any other device gets the refusal and no question; the words are the native refusal\'s', function () {
  var B = answered(load(), { on: true, canUnpair: true, devices: [dev('Xiaomi RC', 'input', true, 'bt1', false), dev('No token', 'audio', false, '', true), dev('Not true', 'audio', false, 'bt3', 1)] });
  assert.deepStrictEqual(plain(B.deviceMessage(0)), { title: 'Xiaomi RC', line: REFUSED, note: 'Connected · Input' });
  assert.strictEqual(B.deviceMessage(1).ask, undefined, 'unpair without an id');
  assert.strictEqual(B.deviceMessage(1).line, REFUSED);
  assert.strictEqual(plain(B.state()).devices[2].unpair, false, 'unpair must be exactly true');
  assert.strictEqual(B.deviceMessage(2).ask, undefined);
  var java = fs.readFileSync(path.join(__dirname, '..', 'android/app/src/main/java/home/cleanframe/tv/BluetoothReport.java'), 'utf8');
  assert.ok(java.indexOf('UNPAIR_REFUSED = "' + REFUSED + '"') > 0, 'BluetoothReport.UNPAIR_REFUSED says the same');
  assert.ok(java.indexOf('UNPAIR_UNAVAILABLE = "' + NO_UNPAIR + '"') > 0, 'BluetoothReport.UNPAIR_UNAVAILABLE says the same');
});

test('canUnpair false (or an older APK): no question on any device, it says to use TV settings, the row says so too', function () {
  [{ on: true, canUnpair: false, devices: [dev('Buds', 'audio', false, 'bt1', true), dev('Remote', 'input', true, 'bt2', false)] },
    { on: true, devices: [dev('Buds', 'audio', false, 'bt1', true)] }].forEach(function (answer) {
    var B = answered(load(), answer);
    assert.strictEqual(B.state().canUnpair, false);
    answer.devices.forEach(function (_, i) {
      assert.strictEqual(B.deviceMessage(i).ask, undefined);
      assert.strictEqual(B.deviceMessage(i).line, NO_UNPAIR);
    });
    assert.ok(plain(B.sections({ f: {} }))[0].items.every(function (i) { return i.hint === 'To unpair it, use TV settings'; }));
  });
});

test('state: canUnpair, each device\'s id and unpair; a failure has no unpairing', function () {
  var B = answered(load(), { on: true, canUnpair: true, devices: [dev('Buds', 'audio', true, 'bt1', true)] });
  assert.deepStrictEqual(plain(B.state()), { status: 'ok', on: true, canUnpair: true, reason: '', devices: [{ name: 'Buds', kind: 'audio', connected: true, id: 'bt1', unpair: true }] });
  assert.strictEqual(load().state().canUnpair, false, 'before the first answer');
  var d = fakeDevice(); B.refresh(d); d.pending[0]({ message: 'x' });
  assert.strictEqual(B.state().canUnpair, false, 'a failed read');
  var hints = plain(answered(load(), { on: true, canUnpair: true, devices: [dev('Buds', 'audio', false, 'bt1', true), dev('Remote', 'input', false, 'bt2', false)] }).sections({ f: {} }))[0].items;
  assert.deepStrictEqual(hints.map(function (i) { return i.hint; }), ['Unpair this device', 'Stays paired here']);
});

test('unpair: Yes calls the TV with the id; the list is read again until the device has gone; then the result, once', function () {
  var timers = [], B = load(timers), list = [dev('Remote', 'input', true, 'bt1', false), dev('Buds', 'audio', false, 'bt2', true)];
  var d = pairedDevice(list), results = [], changes = 0;
  answered(B, { on: true, canUnpair: true, devices: list });
  B.unpair(d, B.deviceMessage(1).ask, function () { changes++; }, function (m) { results.push(plain(m)); });
  assert.deepStrictEqual(d.unpairs, ['bt2'], 'the one device, by its id');
  assert.deepStrictEqual(results, [], 'not yet: it is still listed');
  assert.ok(changes >= 1, 'redrawn with what it read');
  tick(timers); tick(timers);
  assert.deepStrictEqual(results, []);
  list.pop();   // removeBond has finished
  tick(timers);
  assert.deepStrictEqual(results, [{ title: 'Buds', line: 'Unpaired.', note: '' }]);
  assert.strictEqual(B.summary(), '1 paired', 'the list has refreshed');
  while (tick(timers)) { /* nothing left may answer twice */ }
  assert.strictEqual(results.length, 1, 'done exactly once');
});

test('unpair: still bonded after ~3 s of reads says it did not work; a reason says why and reads nothing', function () {
  var timers = [], B = load(timers), list = [dev('Buds', 'audio', false, 'bt2', true)], d = pairedDevice(list), results = [], total = 0, q;
  answered(B, { on: true, canUnpair: true, devices: list });
  B.unpair(d, B.deviceMessage(0).ask, null, function (m) { results.push(plain(m)); });
  while (timers.length) { total += timers[0].ms; tick(timers); }
  assert.ok(total >= 2500 && total <= 3500, 'about 3 s, not ' + total);
  assert.strictEqual(d.reads, 6);
  assert.deepStrictEqual(results, [{ title: 'Buds', line: 'Unpairing did not work.', note: 'It is still paired. Use TV settings to remove it.' }]);
  /* the TV refuses */
  timers = []; B = load(timers); q = pairedDevice(list, { message: 'x', detail: REFUSED }); results = [];
  answered(B, { on: true, canUnpair: true, devices: list });
  B.unpair(q, B.deviceMessage(0).ask, null, function (m) { results.push(plain(m)); });
  assert.deepStrictEqual(results, [{ title: 'Buds', line: 'Unpairing did not work.', note: REFUSED }]);
  assert.strictEqual(q.reads, 0, 'nothing to wait for');
  assert.strictEqual(timers.length, 0);
  /* an older APK, no method */
  timers = []; B = load(timers); results = [];
  answered(B, { on: true, canUnpair: true, devices: list });
  B.unpair({ bluetooth: function () {} }, B.deviceMessage(0).ask, null, function (m) { results.push(plain(m)); });
  assert.strictEqual(results[0].note, 'Update CleanFrame on the TV to unpair from here.');
  assert.ok(B.deviceMessage(0).ask, 'and it can be asked again');
  /* the re-read fails all the way */
  timers = []; B = load(timers); results = [];
  answered(B, { on: true, canUnpair: true, devices: list });
  q = { bluetoothUnpair: function (id, done) { done(null); }, bluetooth: function (done) { done({ message: 'x', detail: 'Bluetooth permission not granted' }); } };
  B.unpair(q, B.deviceMessage(0).ask, null, function (m) { results.push(plain(m)); });
  while (tick(timers)) { /* every read */ }
  assert.deepStrictEqual(results, [{ title: 'Buds', line: 'Unpairing did not work.', note: 'Bluetooth permission not granted' }], 'a failed read is never "unpaired"');
});

test('unpair: while it runs nothing else can be unpaired, the page says Unpairing…; an adapter turned off is not "unpaired"', function () {
  var timers = [], B = load(timers), list = [dev('Buds', 'audio', false, 'bt2', true)], d = pairedDevice(list), results = [];
  answered(B, { on: true, canUnpair: true, devices: list });
  var ask = B.deviceMessage(0).ask;
  B.unpair(d, ask, null, function (m) { results.push(m); });
  assert.strictEqual(B.deviceMessage(0), null, 'no second question');
  assert.strictEqual(plain(B.sections({ f: {} }))[1].reason, 'Unpairing… ' + NOTE);
  B.unpair(d, ask, null, function (m) { results.push(m); });
  assert.deepStrictEqual(d.unpairs, ['bt2'], 'a second Yes does nothing');
  while (tick(timers)) { /* to the end */ }
  assert.strictEqual(results.length, 1);
  assert.strictEqual(plain(B.sections({ f: {} }))[1].reason, NOTE, 'over');
  assert.ok(B.deviceMessage(0).ask, 'it can be asked again');
  /* a read dropped for a newer one loses nothing: the timer still runs, the last look decides */
  timers = []; B = load(timers); results = [];
  answered(B, { on: true, canUnpair: true, devices: list });
  var late = { bluetoothUnpair: function (id, done) { done(null); }, bluetooth: function () { /* never answers */ } };
  B.unpair(late, B.deviceMessage(0).ask, null, function (m) { results.push(plain(m)); });
  while (tick(timers)) { /* to the end */ }
  assert.strictEqual(results.length, 1, 'done even with no answer');
  assert.strictEqual(results[0].line, 'Unpairing did not work.');
  /* off: getBondedDevices() is empty, which says nothing about the bond */
  timers = []; B = load(timers); results = [];
  answered(B, { on: true, canUnpair: true, devices: list });
  var off = { bluetoothUnpair: function (id, done) { done(null); }, bluetooth: function (done) { done(null, { on: false, canUnpair: true, devices: [] }); } };
  B.unpair(off, B.deviceMessage(0).ask, null, function (m) { results.push(plain(m)); });
  while (tick(timers)) { /* to the end */ }
  assert.strictEqual(results[0].line, 'Unpairing did not work.');
});

test('ES5 only: no let/const/class/arrow/template in web/bluetooth.js (docs/RULES.md rule 2)', function () {
  var code = SOURCE.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
  assert.ok(!/\b(let|const|class)\s/.test(code), 'let/const/class');
  assert.ok(!/=>/.test(code), 'arrow function');
  assert.ok(code.indexOf('`') < 0, 'template literal');
});

/* ---------- the patched web/main.js ---------- */

/* A function of the patched main.js, alone (see tests/wallpaper-groups.test.js). */
function mainFn(name) {
  var a = MAIN.indexOf('  function ' + name + '('), line, b;
  assert.ok(a >= 0, 'web/main.js has no function ' + name);
  line = MAIN.slice(a, MAIN.indexOf('\n', a));
  if (line.split('{').length === line.split('}').length) return line;
  b = MAIN.indexOf('\n  }\n', a);
  assert.ok(b > a, 'the end of ' + name);
  return MAIN.slice(a, b + 4);
}
function mainLine(start) {
  var a = MAIN.indexOf(start);
  assert.ok(a >= 0, 'web/main.js has no ' + start);
  return MAIN.slice(a, MAIN.indexOf('\n', a));
}

test('patched main.js: Settings lists Bluetooth after Apps on Home and before TV, valued by BT.summary()', function () {
  var ctx = { S: { c: { home: ['a', 'b'], bg: 'dark', layout: 'dock', font: 'manrope', focus: 'glass', material: 'soft' } },
    VALUE_NAMES: { dark: 'Dark', dock: 'Dock', glass: 'Glass', soft: 'Soft' }, FONTS: { manrope: { name: 'Manrope' } },
    materialApplies: function () { return false; }, wall: function () { return { name: 'x' }; },
    X: { summary: function (g) { return g; } }, BT: { summary: function () { return 'Connected: <Buds>'; } } };
  vm.createContext(ctx);
  vm.runInContext(mainFn('settingsRows'), ctx);
  var rows = plain(ctx.settingsRows());
  assert.deepStrictEqual(rows.map(function (r) { return r.id; }), ['background', 'layout', 'typography', 'focus', 'clock', 'screen', 'editor', 'bluetooth', 'samsung']);
  assert.deepStrictEqual(rows[7], { id: 'bluetooth', label: 'Bluetooth', value: 'Connected: <Buds>' });
  assert.ok(MAIN.indexOf("<span class=\"settings-group-name\">' + esc(r.label) + '</span><span class=\"settings-group-value\">' + esc(r.value) + '</span>") > 0,
    'the Settings row escapes its value (a device name)');
  assert.ok(MAIN.indexOf("bluetooth: 'Bluetooth'") > 0, 'the page is titled Bluetooth (DETAIL_NAMES)');
});

test('home-extras.css: all nine Settings rows fit between the title and the hint', function () {
  var css = fs.readFileSync(path.join(WEB, 'home-extras.css'), 'utf8'), style = fs.readFileSync(path.join(WEB, 'style.css'), 'utf8');
  var top = +/\n\.settings-overview\{top:(\d+)px\}/.exec(css)[1], h = +/\.settings-overview \.settings-group\{height:(\d+)px/.exec(css)[1];
  var m = +/\.settings-overview \.settings-group\+\.settings-group\{margin-top:(\d+)px\}/.exec(css)[1];
  var title = /\.s-title\{[^}]*top:(\d+)px;[^}]*line-height:(\d+)px/.exec(style), hint = +/\.settings-overview-hint\{[^}]*top:(\d+)px/.exec(style)[1];
  var n = (mainFn('settingsRows').match(/\{ id: '/g) || []).length;
  assert.strictEqual(n, 9);
  assert.ok(top >= +title[1] + +title[2], 'the list starts under the title (' + top + ')');
  assert.ok(top + n * h + (n - 1) * m <= hint, 'the last row ends at ' + (top + n * h + (n - 1) * m) + ', the hint is at ' + hint);
});

/* renderAppearance with a fake page: what the Bluetooth page draws, and its remote rows. */
function page(B, focus) {
  var els = {}, ctx;
  function el(id) { return els[id] || (els[id] = { id: id, hidden: false, className: '', setAttribute: function () {}, removeAttribute: function () {} }); }
  /* icon(name, cls) as markup that keeps both, so the forward arrow (cls setting-forward) can be told apart */
  ctx = { BT: B, S: { c: {}, pending: null, lookError: null, detailFocus: {} }, LOOK_NAMES: {}, DETAIL_NAMES: { bluetooth: 'Bluetooth' },
    appearanceEl: el('appearance'), appearanceRows: el('rows'), appearanceTitle: el('title'), appearanceBack: el('back'), appearanceStatus: el('status'), appearanceDesc: el('desc'),
    isSettingsDetail: function () { return true; }, PGV: { render: function () { return false; } }, AEV: { render: function () { return false; } },
    V: { appearance: { norm: function () {} } }, X: { parent: function () { return ''; }, applied: function () { return false; }, owns: function () { return false; } },
    setHTML: function (e, h) { e.html = h; }, icon: function (n, cls) { return '<i data-icon="' + n + '" class="' + (cls || '') + '"></i>'; }, imgSrc: function () { return ''; },
    materialApplies: function () { return true; }, FONTS: {} };
  vm.createContext(ctx);
  vm.runInContext([mainFn('esc'), mainFn('detailSections'), mainFn('detailRows'), mainFn('detailLocation'), mainFn('detailFocus'),
    mainFn('bluetoothArrows'), mainFn('renderAppearance')].join('\n'), ctx, { filename: 'main.js (Bluetooth page)' });
  var t = { id: 'appearance', group: 'bluetooth', f: { action: focus || 'bluetooth.device.0' } };
  ctx.renderAppearance(t);
  return { ctx: ctx, t: t, els: els };
}
var EVIL = '<img src=x onerror="alert(1)">&';

test('patched main.js: the Bluetooth page escapes device names, puts none in an action, arrows only on Pair new device', function () {
  var B = answered(load(), { on: true, canUnpair: true, devices: [dev(EVIL, 'audio', true, 'bt1', true), dev('Keyboard', 'input', false, 'bt2', false)] }), p = page(B);
  var html = p.els.rows.html;
  assert.strictEqual(p.els.title.textContent, 'Bluetooth');
  assert.ok(html.indexOf(EVIL) < 0 && html.indexOf('<img src=x') < 0, 'the name is escaped');
  assert.ok(html.indexOf('&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&amp;') > 0, 'and shown');
  assert.ok(html.indexOf('Connected · Audio') > 0 && html.indexOf('Not connected · Input') > 0, 'state and kind');
  (html.match(/data-action="[^"]*"/g) || []).forEach(function (a) { assert.ok(/^data-action="(bluetooth\.device\.\d+|bluetooth\.pair)"$/.test(a), a); });
  var buttons = html.split('<button').slice(1);
  assert.strictEqual(buttons.length, 3);
  assert.ok(buttons[0].indexOf('setting-forward') < 0 && buttons[1].indexOf('setting-forward') < 0, 'no forward arrow on a device');
  assert.ok(buttons[2].indexOf('Pair new device') > 0 && buttons[2].indexOf('setting-forward') > 0, 'Pair new device opens a page');
  assert.ok(html.indexOf(NOTE) > 0, 'the note');
  assert.ok(/ is-focus/.test(buttons[0]), 'focus drawn on the focused device');
  assert.ok(p.els.desc.html.indexOf('Unpair this device') > 0, 'the focused item\'s hint');
  assert.ok(html.indexOf('bt1') < 0 && html.indexOf('bt2') < 0, 'no token in the page, only an index');
  assert.ok(p.els.back.html.indexOf('Settings') > 0, 'Back says Settings');
});

test('patched main.js: the remote reaches Back, each device, then Pair new device, one row each, in that order', function () {
  var B = answered(load(), { on: true, devices: [dev('A', 'audio', true), dev('B', 'input', false)] }), p = page(B);
  var rows = p.ctx.detailRows(p.t).map(function (r) { return r.map(function (i) { return i.action; }); });
  assert.deepStrictEqual(plain(rows), [['settings.back'], ['bluetooth.device.0'], ['bluetooth.device.1'], ['bluetooth.pair']]);
  p = page(load(), 'bluetooth.pair');
  assert.deepStrictEqual(plain(p.ctx.detailRows(p.t).map(function (r) { return r[0].action; })), ['settings.back', 'bluetooth.pair'], 'before an answer: Pair new device only');
});

test('patched main.js: OK on Pair new device asks the TV; OK on a refused device shows the refusal in a message', function () {
  var log = [], B = answered(load(), { on: true, canUnpair: true, devices: [dev('Buds', 'input', false, 'bt1', false)] }), ctx;
  ctx = { BT: B, log: log, S: { lookError: null, pending: null }, X: {},
    push: function (v) { log.push(['push', v.id]); },
    FrameDevice: { bluetoothPair: function (done) { log.push('pair'); done(null); } },
    perform: function (title, run) { log.push('perform:' + title); run(function (err) { log.push('done:' + err); }); },
    message: function (title, line, note) { log.push(['message', title, line, note]); },
    detailBack: function () { log.push('back'); } };
  vm.createContext(ctx);
  vm.runInContext(mainFn('bluetoothOk') + '\n' + mainFn('detailOk'), ctx);
  ctx.detailOk({}, { action: 'bluetooth.pair', bt: 'pair' });
  assert.deepStrictEqual(plain(log), ['perform:Bluetooth', 'pair', 'done:null']);
  log.length = 0;
  ctx.detailOk({}, plain(B.sections({ f: {} }))[0].items[0]);
  assert.deepStrictEqual(plain(log), [['message', 'Buds', REFUSED, 'Not connected · Input']]);
  log.length = 0;
  ctx.detailOk({}, { action: 'bluetooth.device.9', bt: 'device', index: 9 });
  assert.deepStrictEqual(plain(log), [], 'a device that is gone shows nothing');
  var msg = mainLine("      return '<h2 class=\"msg-title\">'");
  assert.ok(msg.indexOf('esc(m.title)') > 0 && msg.indexOf('esc(m.line)') > 0 && msg.indexOf('esc(m.note)') > 0, 'the message escapes the name');
});

/* The message view's key handler, alone, on a stack of its own: what Yes, No and Back do. */
function messageKeys(unpaired, extra) {
  var a = MAIN.indexOf('      var A = messageContent(t).actions, a;'), b = MAIN.indexOf("\n    },\n    point: function (t, z, i) { if (z === 'act')", a), stack = [], ctx;
  assert.ok(a > 0 && b > a, 'the message view\'s key handler');
  ctx = { stack: stack, messageContent: function (t) { return t.content; }, pop: function () { stack.pop(); },
    bluetoothUnpair: function (ask) { unpaired.push(ask); }, push: function (v) { stack.push(v); },
    FrameDevice: {}, perform: function () { throw new Error('perform'); }, message: function () { throw new Error('message'); },
    appMenuAct: function () { throw new Error('appMenuAct'); }, X: {}, render: function () {} };
  Object.keys(extra || {}).forEach(function (k) { ctx[k] = extra[k]; });
  vm.createContext(ctx);
  vm.runInContext('function msgKey(t, k) {\n' + MAIN.slice(a, b) + '\n}\n' + mainFn('bluetoothOk'), ctx, { filename: 'main.js (message keys)' });
  return ctx;
}
function askUnpair(unpaired, name) {
  var B = answered(load(), { on: true, canUnpair: true, devices: [dev(name, 'audio', false, 'bt9', true)] }), ctx = messageKeys(unpaired, { BT: B });
  ctx.bluetoothOk(plain(B.sections({ f: {} }))[0].items[0]);
  return ctx;
}

test('patched main.js: OK on an unpairable device opens Yes/No with the focus on No; the question names it, with the note', function () {
  var ctx = askUnpair([], 'Buds'), t = ctx.stack[0];
  assert.strictEqual(ctx.stack.length, 1);
  assert.strictEqual(t.id, 'message');
  assert.strictEqual(t.f.b, 1, 'focus on No');
  assert.deepStrictEqual(plain(t.content.actions), [['yes', 'Yes'], ['no', 'No']]);
  assert.strictEqual(t.content.title, 'Unpair Buds?');
  assert.strictEqual(t.content.line, '', 'the question and nothing else (2026-11-04)');
  assert.strictEqual(t.content.note, '');
  assert.ok(t.msg.indexOf('Buds') < 0, 'no name in the view\'s key');
});

test('patched main.js: only Yes unpairs, with the id: OK on No, Back, and moving across do not', function () {
  var unpaired = [], ctx = askUnpair(unpaired, 'Buds');
  ctx.msgKey(ctx.stack[0], 'ok');
  assert.deepStrictEqual([unpaired.length, ctx.stack.length], [0, 0], 'OK on No (where the focus starts): closed, nothing unpaired');
  ctx = askUnpair(unpaired, 'Buds');
  ctx.msgKey(ctx.stack[0], 'back');
  assert.deepStrictEqual([unpaired.length, ctx.stack.length], [0, 0], 'Back: closed, nothing unpaired');
  ctx = askUnpair(unpaired, 'Buds');
  ctx.msgKey(ctx.stack[0], 'left');
  assert.deepStrictEqual([unpaired.length, ctx.stack.length, ctx.stack[0].f.b], [0, 1, 0], 'Left moves to Yes and does nothing else');
  ctx.msgKey(ctx.stack[0], 'right');
  ctx.msgKey(ctx.stack[0], 'ok');
  assert.deepStrictEqual([unpaired.length, ctx.stack.length], [0, 0], 'back to No, OK: nothing unpaired');
  ctx = askUnpair(unpaired, 'Buds');
  ctx.msgKey(ctx.stack[0], 'left');
  ctx.msgKey(ctx.stack[0], 'ok');
  assert.deepStrictEqual(plain(unpaired), [{ id: 'bt9', name: 'Buds' }], 'Yes: once, that device');
  assert.strictEqual(ctx.stack.length, 0, 'the question closes');
});

test('patched main.js: a device name in the question and the result is escaped', function () {
  var t = askUnpair([], EVIL).stack[0], ctx = { esc: null, messageContent: function (x) { return x.content; } }, html;
  vm.createContext(ctx);
  vm.runInContext(mainFn('esc') + '\nfunction view(t) {\n  var m = messageContent(t);\n' + mainLine("      return '<h2 class=\"msg-title\">'") + '\n}', ctx);
  html = ctx.view(t);
  assert.strictEqual(t.content.title, 'Unpair ' + EVIL + '?', 'the question names it');
  assert.ok(html.indexOf('<img') < 0 && html.indexOf('&lt;img src=x') > 0, 'and it is escaped: ' + html);
  assert.ok(html.indexOf('pair it again') < 0, 'no note under the question: ' + html);
});

test('patched main.js: Yes runs BT.unpair on the device layer; the result shows on the page, not over whatever replaced it', function () {
  var log = [], here = true, ask = { id: 'bt9', name: 'Buds' }, device = {}, ctx;
  ctx = { window: { FrameDevice: device }, top: function () { return here ? { id: 'appearance', group: 'bluetooth' } : { id: 'home' }; }, after: function () { log.push('after'); },
    message: function (a, b, c) { log.push(['message', a, b, c]); },
    BT: { unpair: function (d, a, changed, done) { log.push(d === device && a === ask ? 'unpair' : 'wrong'); changed(); done({ title: 'Buds', line: 'Unpaired.', note: '' }); } } };
  vm.createContext(ctx);
  vm.runInContext(mainFn('bluetoothUnpair'), ctx);
  ctx.bluetoothUnpair(ask);
  assert.deepStrictEqual(plain(log), ['unpair', 'after', ['message', 'Buds', 'Unpaired.', ''], 'after']);
  log.length = 0; here = false;
  ctx.bluetoothUnpair(ask);
  assert.deepStrictEqual(plain(log), ['unpair', 'after'], 'left the page: no message over it');
});

test('patched main.js: the stick is asked when Settings or the page opens and when shown again with Settings open', function () {
  var asked = 0, after = 0, stack, ctx;
  ctx = { S: {}, BT: { refresh: function (d, done) { asked++; done(); } }, window: { FrameDevice: {} },
    top: function () { return stack[stack.length - 1]; }, after: function () { after++; } };
  vm.createContext(ctx);
  vm.runInContext(mainFn('refreshBluetooth'), ctx);
  stack = [{ id: 'home' }]; ctx.S.stack = stack;
  ctx.refreshBluetooth(true); assert.deepStrictEqual([asked, after], [0, 0], 'shown again on Home: nothing asked');
  ctx.refreshBluetooth(); assert.deepStrictEqual([asked, after], [1, 0], 'asked; Home is not redrawn for it');
  stack.push({ id: 'settings' }); stack.push({ id: 'appearance', group: 'bluetooth' });
  ctx.refreshBluetooth(true); assert.deepStrictEqual([asked, after], [2, 1], 'shown again on the page: asked and redrawn');
  stack.pop(); ctx.refreshBluetooth(true); assert.deepStrictEqual([asked, after], [3, 2], 'on Settings: asked and redrawn');
  stack.push({ id: 'appearance', group: 'clock' }); ctx.refreshBluetooth(true); assert.deepStrictEqual([asked, after], [4, 2], 'another page on top: asked, not redrawn');
  assert.ok(mainFn('openSettings').indexOf('refreshBluetooth();') > 0, 'Settings opening asks');
  var opened = [], c2 = { openEditor: function () {}, openAppearance: function (id) { opened.push(id); }, refreshBluetooth: function () { opened.push('refresh'); } };
  vm.createContext(c2); vm.runInContext(mainFn('settingsOk'), c2);
  c2.settingsOk('bluetooth'); c2.settingsOk('clock');
  assert.deepStrictEqual(opened, ['bluetooth', 'refresh', 'clock'], 'opening the page asks');
  assert.ok(mainLine('    heldKeys = {}; HOLD.cancel(); X.visible(!document.hidden);').indexOf('if (!document.hidden) { refreshBluetooth(true);') > 0, 'shown again');
});

test('index.html loads bluetooth.js before main.js; tools/android-patch.py lists it in OURS', function () {
  var b = INDEX.indexOf('<script src="bluetooth.js"></script>'), m = INDEX.indexOf('<script src="main.js"></script>');
  assert.ok(b > 0 && m > b, 'script order ' + b + ' ' + m);
  assert.ok(INDEX.indexOf('<script src="device.js"></script>') < m);
  var patch = fs.readFileSync(path.join(__dirname, '..', 'tools', 'android-patch.py'), 'utf8');
  assert.ok(/\n    'bluetooth\.js': '[^']*Bluetooth/.test(patch), 'OURS entry');
});

var failed = 0;
cases.forEach(function (c) {
  try { c[1](); console.log('PASS ' + c[0]); }
  catch (e) { failed++; console.log('FAIL ' + c[0] + ': ' + (e && e.message)); }
});
if (failed) { console.log('BLUETOOTH_TEST_FAIL ' + failed); process.exit(1); }
console.log('BLUETOOTH_TEST_OK ' + cases.length);

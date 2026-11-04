/* Unit tests for web/photo-groups.js (window.CleanFramePhotoGroups): the group page's rules (action
   row, Yes/No confirm, scrolling window), Add group name validation and default name, the Add photo
   screen's code renewal timing and session (start, poll, renew, stop on Done/Back/leaving), the
   name screen's create/rename flow, the vendored web/qrcode-generator.js encoding through it, and
   the patched web/main.js keydown handler's held keys around the text box (tools/android-patch.py).
   The two screens' drawing needs a DOM and is checked in Chromium (docs/PHOTO-GROUPS.md); here the
   views run with a fake host, a fake FrameDevice and no document elements.
   No dependencies: node:assert + node:vm and a tiny runner. Run from the repo root:
     node tests/photo-groups.test.js
   Prints one line per case and exits non-zero on any failure. */
'use strict';
var assert = require('node:assert');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var WEB = path.join(__dirname, '..', 'web');
var SOURCE = fs.readFileSync(process.env.PHOTO_GROUPS_JS || path.join(WEB, 'photo-groups.js'), 'utf8');
var QR_SOURCE = fs.readFileSync(path.join(WEB, 'qrcode-generator.js'), 'utf8');
var MATERIALS_SOURCE = fs.readFileSync(path.join(WEB, 'materials.js'), 'utf8');

function plain(x) { return x === undefined ? undefined : JSON.parse(JSON.stringify(x)); }
function load(opts) {
  opts = opts || {};
  var timers = [], seq = 0, clock = { now: 1000000 };
  var ctx = {
    setTimeout: function (fn, ms) { var t = { id: ++seq, fn: fn, ms: ms }; timers.push(t); return t.id; },
    clearTimeout: function (id) { for (var i = 0; i < timers.length; i++) if (timers[i].id === id) timers.splice(i--, 1); },
    Date: { now: function () { return clock.now; } },
    document: { getElementById: function () { return null; }, activeElement: null, hidden: false, listeners: {},
      addEventListener: function (type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); } }
  };
  ctx.window = ctx;
  if (opts.device) ctx.FrameDevice = opts.device;
  vm.createContext(ctx);
  if (opts.qr) vm.runInContext(QR_SOURCE, ctx, { filename: 'qrcode-generator.js' });
  if (opts.materials) { vm.runInContext(MATERIALS_SOURCE, ctx, { filename: 'materials.js' }); if (opts.runJob) ctx.CleanFrameMaterials.runJob = opts.runJob; }
  vm.runInContext(SOURCE, ctx, { filename: 'photo-groups.js' });
  return { PG: ctx.CleanFramePhotoGroups, ctx: ctx, timers: timers, clock: clock,
    fire: function (ms) {   // run the first pending timer with that delay
      for (var i = 0; i < timers.length; i++) if (timers[i].ms === ms) { var t = timers.splice(i, 1)[0]; t.fn(); return true; }
      return false;
    } };
}
function PG() { return load().PG; }
function lib(names) {
  return { groups: [{ id: 'scenery', builtin: true }, { id: 'space', builtin: true }, { id: 'cyberpunk', builtin: true }].concat((names || []).map(function (n, i) {
    return { id: 'g' + ('0000000' + i).slice(-7) + 'x', name: n, created: i, builtin: false };
  })), photos: [] };
}

var cases = [];
function test(name, fn) { cases.push([name, fn]); }

/* ---------- Rules ---------- */

test('validName: trims; 1-24 characters; an emoji counts once; control characters refused', function () {
  var P = PG();
  assert.deepStrictEqual(plain(P.validName('  Holiday  ')), { ok: true, name: 'Holiday', reason: '' });
  assert.strictEqual(P.validName('x'.repeat(24)).ok, true);
  assert.deepStrictEqual(plain(P.validName('x'.repeat(25))), { ok: false, name: 'x'.repeat(25), reason: 'Use 24 characters or fewer.' });
  assert.strictEqual(P.validName('🌴'.repeat(24)).ok, true, '24 emoji are 24 characters');
  assert.strictEqual(P.validName('🌴'.repeat(25)).ok, false);
  ['', '   ', null, undefined, 7].forEach(function (v) { assert.strictEqual(P.validName(v).reason, 'Type a name for the group.', String(v)); });
  ['a\nb', 'a\tb', 'a\u0000', 'a\u007f', 'a b'].forEach(function (v) { assert.strictEqual(P.validName(v).reason, 'Use letters, numbers and spaces.', JSON.stringify(v)); });
  assert.strictEqual(P.validName('Café & “quotes”').ok, true);
});

test('defaultName: Group N for N = user groups + 1, skipping names in use (any case); built-ins do not count', function () {
  var P = PG();
  assert.strictEqual(P.defaultName(null), 'Group 1');
  assert.strictEqual(P.defaultName(lib()), 'Group 1');
  assert.strictEqual(P.defaultName(lib(['Holiday', 'Family'])), 'Group 3');
  assert.strictEqual(P.defaultName(lib(['group 2'])), 'Group 3');
  assert.strictEqual(P.defaultName(lib(['A', 'Group 3', 'GROUP 4'])), 'Group 5');
});

test('canAddGroup: at most 20 user groups', function () {
  var P = PG(), n19 = [], i;
  for (i = 0; i < 19; i++) n19.push('G' + i);
  assert.strictEqual(P.MAX_GROUPS, 20);
  assert.strictEqual(P.canAddGroup(lib(n19)), true);
  assert.strictEqual(P.canAddGroup(lib(n19.concat(['last']))), false);
  assert.strictEqual(P.canAddGroup(null), true);
});

test('countText / addedText', function () {
  var P = PG();
  assert.deepStrictEqual([P.countText(0), P.countText(1), P.countText(15)], ['0 photos', '1 photo', '15 photos']);
  assert.deepStrictEqual([P.addedText(0, 'Holiday'), P.addedText(1, 'Holiday'), P.addedText(3, 'Holiday')],
    ['No photos added to Holiday yet', '1 photo added to Holiday', '3 photos added to Holiday']);
});

test('actions: Add photo always; Remove only with uploads (Done while removing); Rename/Delete only for user groups', function () {
  var P = PG();
  function ids(info) { return plain(P.actions(info)).map(function (a) { return a.id + ':' + a.label; }); }
  assert.deepStrictEqual(ids({ builtin: true, uploads: false }), ['add:Add photo']);
  assert.deepStrictEqual(ids({ builtin: true, uploads: true }), ['add:Add photo', 'remove:Remove']);
  assert.deepStrictEqual(ids({ builtin: false, uploads: false }), ['add:Add photo', 'rename:Rename', 'delete:Delete']);
  assert.deepStrictEqual(ids({ builtin: false, uploads: true, removing: true }), ['add:Add photo', 'remove:Done', 'rename:Rename', 'delete:Delete']);
  assert.deepStrictEqual(plain(P.actions({ builtin: false, uploads: true })).map(function (a) { return a.name; }),
    ['Add photo', 'Remove photos', 'Rename group', 'Delete group']);
});

test('actions: Adjust only with a photo that can be framed again (Done while adjusting), after Remove', function () {
  var P = PG();
  function ids(info) { return plain(P.actions(info)).map(function (a) { return a.id + ':' + a.label; }); }
  assert.deepStrictEqual(ids({ builtin: false, uploads: true, adjustable: true }), ['add:Add photo', 'remove:Remove', 'adjust:Adjust', 'rename:Rename', 'delete:Delete']);
  assert.deepStrictEqual(ids({ builtin: true, uploads: true, adjusting: true }), ['add:Add photo', 'remove:Remove', 'adjust:Done']);
  assert.deepStrictEqual(ids({ builtin: true, uploads: true, adjustable: false }), ['add:Add photo', 'remove:Remove'], 'older photos only: no Adjust');
  assert.deepStrictEqual(plain(P.actions({ builtin: true, uploads: true, adjusting: true })).map(function (a) { return a.name; }),
    ['Add photo', 'Remove photos', 'Done adjusting photos']);
});

test('actionsKey: Left/Right along the row, Left at the start and Up go to Back, Down to the list', function () {
  var P = PG(), acts = [{}, {}, {}];
  assert.deepStrictEqual(plain(P.actionsKey(acts, 0, 'right')), { a: 1, go: null });
  assert.deepStrictEqual(plain(P.actionsKey(acts, 2, 'right')), { a: 2, go: null });
  assert.deepStrictEqual(plain(P.actionsKey(acts, 2, 'left')), { a: 1, go: null });
  assert.deepStrictEqual(plain(P.actionsKey(acts, 0, 'left')), { a: 0, go: 'back' });
  assert.deepStrictEqual(plain(P.actionsKey(acts, 1, 'up')), { a: 1, go: 'back' });
  assert.deepStrictEqual(plain(P.actionsKey(acts, 1, 'down')), { a: 1, go: 'list' });
  assert.deepStrictEqual(plain(P.actionsKey(acts, 9, 'ok')), { a: 2, go: null }, 'clamped');
});

test('confirmKey: Left = Yes, Right = No, OK answers the focused one, Back is always No', function () {
  var P = PG();
  assert.deepStrictEqual(plain(P.confirmKey(1, 'left')), { b: 0, answer: null });
  assert.deepStrictEqual(plain(P.confirmKey(0, 'right')), { b: 1, answer: null });
  assert.deepStrictEqual(plain(P.confirmKey(0, 'ok')), { b: 0, answer: 'yes' });
  assert.deepStrictEqual(plain(P.confirmKey(1, 'ok')), { b: 1, answer: 'no' });
  assert.deepStrictEqual(plain(P.confirmKey(0, 'back')), { b: 0, answer: 'no' });
  assert.deepStrictEqual(plain(P.confirmKey(1, 'up')), { b: 1, answer: null });
});

test('nameKey: back row <-> field <-> buttons; OK types, creates or cancels; Back cancels anywhere', function () {
  var P = PG();
  function k(zone, b, key) { var r = P.nameKey({ zone: zone, b: b }, key); return r.zone + '/' + r.b + '/' + r.act; }
  assert.strictEqual(k('field', 0, 'ok'), 'field/0/type');
  assert.strictEqual(k('field', 0, 'down'), 'buttons/0/null');
  assert.strictEqual(k('field', 1, 'up'), 'back/1/null');
  assert.strictEqual(k('back', 0, 'down'), 'field/0/null');
  assert.strictEqual(k('back', 0, 'ok'), 'back/0/cancel');
  assert.strictEqual(k('buttons', 0, 'right'), 'buttons/1/null');
  assert.strictEqual(k('buttons', 1, 'left'), 'buttons/0/null');
  assert.strictEqual(k('buttons', 0, 'ok'), 'buttons/0/create');
  assert.strictEqual(k('buttons', 1, 'ok'), 'buttons/1/cancel');
  assert.strictEqual(k('buttons', 0, 'up'), 'field/0/null');
  assert.strictEqual(k('field', 0, 'back'), 'field/0/cancel');
  assert.strictEqual(k('field', 0, 'left'), 'field/0/null');
});

test('uploadKey: Done closes; with an error Try again comes first; Back and the back row close', function () {
  var P = PG();
  function k(zone, b, key, err) { var r = P.uploadKey({ zone: zone, b: b }, key, err); return r.zone + '/' + r.b + '/' + r.act; }
  assert.strictEqual(k('buttons', 0, 'ok', false), 'buttons/0/close');
  assert.strictEqual(k('buttons', 0, 'ok', true), 'buttons/0/retry');
  assert.strictEqual(k('buttons', 1, 'ok', true), 'buttons/1/close');
  assert.strictEqual(k('buttons', 0, 'right', true), 'buttons/1/null');
  assert.strictEqual(k('buttons', 0, 'right', false), 'buttons/0/null');
  assert.strictEqual(k('buttons', 1, 'ok', false), 'buttons/0/close', 'b clamps when the error is gone');
  assert.strictEqual(k('buttons', 0, 'up', false), 'back/0/null');
  assert.strictEqual(k('back', 0, 'down', true), 'buttons/1/null', 'down from the top lands on Done');
  assert.strictEqual(k('back', 0, 'ok', false), 'back/0/close');
  assert.strictEqual(k('buttons', 0, 'back', true), 'buttons/0/close');
});

test('renewDelay: renew a minute before expiry, never sooner than 5 s; bad input retries in 10 s', function () {
  var P = PG();
  assert.strictEqual(P.RENEW_LEAD_MS, 60000);
  assert.strictEqual(P.renewDelay(1000 + 15 * 60000, 1000), 14 * 60000);
  assert.strictEqual(P.renewDelay(1000 + 61000, 1000), 5000);
  assert.strictEqual(P.renewDelay(1000 + 66000, 1000), 6000);
  assert.strictEqual(P.renewDelay(500, 1000), 5000, 'already expired');
  assert.strictEqual(P.renewDelay(NaN, 1000), 10000);
  assert.strictEqual(P.renewDelay('900000', 1000), 10000);
  assert.strictEqual(P.renewDelay(900000, undefined), 10000);
  assert.strictEqual(P.POLL_MS, 2000);
});

test('windowTop: keeps the focused row inside the visible window', function () {
  var P = PG();
  assert.strictEqual(P.windowTop(0, 2, 3, 4), 0, 'all fit');
  assert.strictEqual(P.windowTop(0, 3, 6, 4), 0);
  assert.strictEqual(P.windowTop(0, 4, 6, 4), 1);
  assert.strictEqual(P.windowTop(2, 1, 6, 4), 1);
  assert.strictEqual(P.windowTop(5, -1, 6, 4), 2, 'no focus: clamped');
  assert.strictEqual(P.windowTop(-3, -1, 6, 4), 0);
});

test('added: counts entries that were not there when the screen opened', function () {
  var P = PG();
  assert.strictEqual(P.added(['a', 'b'], [{ id: 'a' }, { id: 'b' }]), 0);
  assert.strictEqual(P.added(['a'], [{ id: 'a' }, { id: 'c' }, { id: 'd' }]), 2);
  assert.strictEqual(P.added([], [{ id: 'constructor' }]), 1);
  assert.strictEqual(P.added(null, null), 0);
});

/* ---------- QR (vendored qrcode-generator 1.4.4) ---------- */

test('qrMatrix: an upload URL encodes to a 33x33 matrix (version 4, M) with the three finder patterns', function () {
  var P = load({ qr: true }).PG, m = P.qrMatrix('http://192.168.0.6:8097/u/0123456789abcdef0123456789abcdef'), r, c;
  assert.strictEqual(m.n, 33);
  function finder(r0, c0) {
    for (r = 0; r < 7; r++) for (c = 0; c < 7; c++) {
      var ring = r === 0 || r === 6 || c === 0 || c === 6, core = r >= 2 && r <= 4 && c >= 2 && c <= 4;
      assert.strictEqual(m.dark(r0 + r, c0 + c), ring || core, 'finder at ' + r0 + ',' + c0);
    }
  }
  finder(0, 0); finder(0, 26); finder(26, 0);
  assert.strictEqual(P.qrMatrix('HELLO WORLD').n, 21, 'version 1');
  assert.throws(function () { load().PG.qrMatrix('x'); }, /qrcode-generator is not loaded/);
});

test('drawQr: whole-pixel modules with a quiet zone of at least 4 modules, white behind', function () {
  var P = load({ qr: true }).PG, rects = [], g = { fillRect: function (x, y, w, h) { rects.push([this.fillStyle, x, y, w, h]); } };
  var n = P.drawQr({ width: 320, getContext: function () { return g; } }, 'http://192.168.0.6:8097/u/0123456789abcdef0123456789abcdef');
  assert.strictEqual(n, 33);
  assert.deepStrictEqual(rects[0], ['#fff', 0, 0, 320, 320]);
  var dark = rects.slice(1);
  assert.ok(dark.length > 300 && dark.every(function (d) { return d[0] === '#000' && d[3] === 7 && d[4] === 7; }), 'module size 7');
  assert.ok(dark.every(function (d) { return d[1] >= 28 && d[2] >= 28 && d[1] + 7 <= 292 && d[2] + 7 <= 292; }), 'quiet zone');
});

/* ---------- The screens' behaviour (fake host and device, no DOM) ---------- */

function harness(opts) {
  opts = opts || {};
  var calls = [], answers = {}, stack = [{ id: 'wallpaper', g: 'gholiday1' }], walls = opts.walls || [{ id: 'u-gholiday1-p1' }];
  var device = {};
  ['uploadStart', 'uploadStop', 'addGroup', 'renameGroup'].concat(opts.materials ? ['materialJobs', 'saveMaterial'] : []).forEach(function (m) {
    device[m] = function () {
      var args = Array.prototype.slice.call(arguments), done = args.pop();
      calls.push([m].concat(args)); (answers[m] = answers[m] || []).push(done);
    };
  });
  if (opts.materials) device.onMaterialsWake = function (fn) { device.woken = fn; };
  var env = load({ device: device, materials: opts.materials, runJob: opts.runJob }), host = {
    refreshes: 0, opened: [], afters: 0,
    top: function () { return stack[stack.length - 1]; }, push: function (t) { stack.push(t); return t; }, pop: function () { stack.pop(); },
    after: function () { host.afters++; }, focusStage: function () {}, esc: String, icon: String, setHTML: function () {},
    library: function () { return opts.lib || lib(['Holiday']); }, members: function () { return walls; },
    refreshLibrary: function (done) { host.refreshes++; if (done) done(null); },
    openGroup: function (g, add) { host.opened.push([g, add]); }, groupLabel: function (g) { return g === 'gholiday1' ? 'Holiday' : g; }
  };
  var views = env.PG.views(host);
  return { env: env, views: views, host: host, stack: stack, calls: calls, walls: walls, device: device,
    answer: function (m, err, value) { answers[m].shift()(err, value); },
    names: function () { return calls.map(function (c) { return c[0]; }); } };
}
var START = { url: 'http://192.168.0.6:8097/u/abc', base: 'http://192.168.0.6:8097', code: '482913' };

test('Add photo: opening starts a code, polls the library every 2 s and renews a minute before expiry', function () {
  var h = harness(), t = h.views.openUpload('gholiday1');
  assert.strictEqual(h.host.top(), t);
  assert.ok(h.views.owns(t));
  assert.deepStrictEqual(h.calls, [['uploadStart', 'gholiday1']]);
  h.answer('uploadStart', null, { url: START.url, base: START.base, code: START.code, expires: h.env.clock.now + 15 * 60000 });
  assert.strictEqual(t.session.code, '482913');
  assert.deepStrictEqual(h.env.timers.map(function (x) { return x.ms; }).sort(function (a, b) { return a - b; }), [2000, 14 * 60000]);
  h.walls.push({ id: 'u-gholiday1-p2' });
  assert.ok(h.env.fire(2000)); assert.strictEqual(h.host.refreshes, 1);
  assert.ok(h.env.timers.some(function (x) { return x.ms === 2000; }), 'polls again');
  h.env.clock.now += 14 * 60000;
  assert.ok(h.env.fire(14 * 60000));
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStart'], 'renewed');
  h.answer('uploadStart', null, { url: START.url + '2', base: START.base, code: '111222', expires: h.env.clock.now + 15 * 60000 });
  assert.strictEqual(t.session.url, START.url + '2');
});

test('Add photo: Done stops the code once and leaves the screen; a late timer or answer does nothing', function () {
  var h = harness(), t = h.views.openUpload('gholiday1');
  h.answer('uploadStart', null, { url: START.url, base: START.base, code: START.code, expires: h.env.clock.now + 900000 });
  h.views.V.upload.key(t, 'ok');
  assert.strictEqual(h.host.top().id, 'wallpaper');
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStop']);
  assert.strictEqual(h.views.session(), null);
  assert.strictEqual(h.env.timers.length, 0, 'poll and renew cleared');
  h.views.sync(h.stack);
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStop'], 'not stopped twice');
});

test('Add photo: leaving any other way (Home, scene change) stops it on the next render', function () {
  var h = harness();
  h.views.openUpload('gholiday1');
  h.views.sync(h.stack);
  assert.deepStrictEqual(h.names(), ['uploadStart'], 'still open: still running');
  h.stack.length = 1; h.stack[0] = { id: 'home' };
  h.views.sync(h.stack);
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStop']);
  h.answer('uploadStart', null, { url: START.url, base: START.base, code: START.code, expires: h.env.clock.now + 900000 });
  assert.strictEqual(h.env.timers.length, 0, 'an answer after closing schedules nothing');
});

test('Add photo: closing it re-reads the library once, so a photo the phone removed just before leaves the group page', function () {
  var h = harness(), t = h.views.openUpload('gholiday1'), before;
  h.answer('uploadStart', null, { url: START.url, base: START.base, code: START.code, expires: h.env.clock.now + 900000 });
  before = h.host.refreshes;
  h.views.V.upload.key(t, 'ok');   // Done, inside the 2 s poll interval
  assert.strictEqual(h.host.refreshes, before + 1, 'read once on Done');
  h.views.sync(h.stack);
  assert.strictEqual(h.host.refreshes, before + 1, 'and not again');
  h = harness(); h.views.openUpload('gholiday1'); before = h.host.refreshes;
  h.stack.length = 1; h.stack[0] = { id: 'home' };
  h.views.sync(h.stack);           // Home or a scene change
  assert.strictEqual(h.host.refreshes, before + 1, 'read once when left any other way');
});

test('Add photo: shown again after the app stopped (Android cleared the token) -> asks for a new code at once', function () {
  var h = harness(), t = h.views.openUpload('gholiday1'), doc = h.env.ctx.document;
  h.answer('uploadStart', null, { url: START.url, base: START.base, code: START.code, expires: h.env.clock.now + 900000 });
  doc.hidden = true; doc.listeners.visibilitychange.forEach(function (fn) { fn(); });
  assert.deepStrictEqual(h.names(), ['uploadStart'], 'nothing while hidden');
  doc.hidden = false; doc.listeners.visibilitychange.forEach(function (fn) { fn(); });
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStart']);
  h.answer('uploadStart', null, { url: START.url + 'x', base: START.base, code: '999000', expires: h.env.clock.now + 900000 });
  assert.strictEqual(t.session.code, '999000');
  assert.strictEqual(h.env.timers.filter(function (x) { return x.ms === 840000; }).length, 1, 'one renewal timer, not two');
  h.views.V.upload.key(t, 'ok');
  doc.listeners.visibilitychange.forEach(function (fn) { fn(); });
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStart', 'uploadStop'], 'closed: no new code');
});

test('Add photo: a failed start shows the reason, Try again asks again', function () {
  var h = harness(), t = h.views.openUpload('gholiday1');
  h.answer('uploadStart', { message: 'Could not start adding photos.', detail: 'adb gone' });
  assert.strictEqual(t.session.error, 'adb gone');
  h.views.V.upload.key(t, 'ok');
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStart']);
  assert.strictEqual(t.session.error, '');
  h.views.V.upload.key(t, 'back');
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStart', 'uploadStop']);
});

test('Add photo: a renewal refused as busy shows the reason, schedules no retry of its own, Try again asks again', function () {
  var h = harness(), t = h.views.openUpload('gholiday1');
  h.answer('uploadStart', null, { url: START.url, base: START.base, code: START.code, expires: h.env.clock.now + 900000 });
  h.env.clock.now += 840000;
  assert.ok(h.env.fire(840000));
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStart']);
  h.answer('uploadStart', { message: 'Could not start adding photos.', detail: 'The TV is still starting up. Try again in a moment.' });
  assert.strictEqual(t.session.error, 'The TV is still starting up. Try again in a moment.');
  assert.strictEqual(t.session.url, '', 'no stale code left on screen');
  assert.deepStrictEqual(JSON.parse(JSON.stringify(h.env.PG.uploadProblem(t.session.error))), { title: 'Couldn’t start adding photos.', detail: 'The TV is still starting up. Try again in a moment.', network: false });
  assert.deepStrictEqual(h.env.timers.map(function (x) { return x.ms; }), [2000], 'only the library poll: no renewal loop');
  assert.ok(h.env.fire(2000));
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStart'], 'the poll does not ask for a code');
  h.views.V.upload.key(t, 'ok');
  assert.deepStrictEqual(h.names(), ['uploadStart', 'uploadStart', 'uploadStart'], 'Try again');
  assert.strictEqual(t.session.error, '');
  h.answer('uploadStart', null, { url: START.url + '3', base: START.base, code: '333444', expires: h.env.clock.now + 900000 });
  assert.strictEqual(t.session.code, '333444');
});

test('Add group: prefilled Group N; Create sends the trimmed name, re-reads, opens the new group on Add photo', function () {
  var h = harness(), t = h.views.openName('add');
  assert.strictEqual(t.value, 'Group 2');
  assert.strictEqual(t.f.zone, 'field');
  t.value = '  Garden ';
  h.views.V.groupname.key(t, 'down');
  assert.deepStrictEqual([t.f.zone, t.f.b], ['buttons', 0]);
  h.views.V.groupname.key(t, 'ok');
  assert.deepStrictEqual(h.calls, [['addGroup', 'Garden']]);
  h.views.V.groupname.key(t, 'ok');
  assert.strictEqual(h.calls.length, 1, 'busy: not sent twice');
  h.answer('addGroup', null, { id: 'gnewgroup' });
  assert.strictEqual(h.host.refreshes, 1);
  assert.strictEqual(h.host.top().id, 'wallpaper', 'the name screen closed');
  assert.deepStrictEqual(h.host.opened, [['gnewgroup', true]]);
});

test('Add group: a bad name or the 20-group limit never reaches the TV; a refusal shows its reason', function () {
  var h = harness(), t = h.views.openName('add'), names = [], i;
  t.value = '   '; t.f.zone = 'buttons';
  h.views.V.groupname.key(t, 'ok');
  assert.strictEqual(t.error, 'Type a name for the group.');
  assert.strictEqual(t.f.zone, 'field');
  for (i = 0; i < 20; i++) names.push('G' + i);
  h = harness({ lib: lib(names) }); t = h.views.openName('add'); t.f.zone = 'buttons';
  h.views.V.groupname.key(t, 'ok');
  assert.strictEqual(t.error, 'There can be at most 20 groups.');
  assert.strictEqual(h.calls.length, 0);
  h = harness(); t = h.views.openName('add'); t.f.zone = 'buttons';
  h.views.V.groupname.key(t, 'ok');
  h.answer('addGroup', { message: 'Could not add the group.', detail: 'Disk full' });
  assert.strictEqual(t.error, 'Disk full');
  assert.strictEqual(h.host.top(), t, 'stays open');
});

test('Rename group: prefilled with the name; Rename sends (gid, name) and returns to the group page; Cancel and Back send nothing', function () {
  var h = harness(), t = h.views.openName('rename', 'gholiday1');
  assert.strictEqual(t.value, 'Holiday');
  t.value = 'Trip'; t.f.zone = 'buttons';
  h.views.V.groupname.key(t, 'ok');
  h.answer('renameGroup', null);
  assert.deepStrictEqual(h.calls, [['renameGroup', 'gholiday1', 'Trip']]);
  assert.strictEqual(h.host.top().id, 'wallpaper');
  t = h.views.openName('rename', 'gholiday1'); t.f.zone = 'buttons'; t.f.b = 1;
  h.views.V.groupname.key(t, 'ok');
  assert.strictEqual(h.host.top().id, 'wallpaper');
  t = h.views.openName('add');
  h.views.V.groupname.key(t, 'back');
  assert.strictEqual(h.host.top().id, 'wallpaper');
  assert.strictEqual(h.calls.length, 1);
});

test('inputKey leaves every key to main.js unless the text box has the keyboard; render owns only its two screens', function () {
  var h = harness();
  h.views.openName('add');
  assert.strictEqual(h.views.inputKey({ keyCode: 40, type: 'keydown', preventDefault: function () { throw new Error('no'); } }), false);
  assert.strictEqual(h.views.render({ id: 'appearance' }), false);
  assert.strictEqual(h.views.owns({ id: 'wallpaper' }), false);
  assert.ok(h.views.owns({ id: 'groupname' }) && h.views.owns({ id: 'upload' }));
});

/* ---------- The materials queue glue (docs/PHOTO-GROUPS.md contract 4) ---------- */

var MJOB = { gid: 'gholiday1', pid: 'pabcdefgh01', wall: 'user-tmp/gholiday1/pabcdefgh01/wall.jpg', tone: 'dark', need: ['panel'] };
function matHarness() {
  var runs = [];
  var h = harness({ materials: true, runJob: function (job, ticket, env, done) { runs.push({ job: job, env: env, done: done }); } });
  h.runs = runs;
  return h;
}

test('materials: the queue starts with the page (a 0 ms wake), registers for FrameDevice.materialsWake, and lists jobs', function () {
  var h = matHarness();
  assert.ok(h.views.materials(), 'a queue exists');
  assert.strictEqual(typeof h.device.woken, 'function', 'onMaterialsWake got the queue');
  assert.deepStrictEqual(h.names(), [], 'nothing before the first task');
  assert.ok(h.env.fire(0));
  assert.deepStrictEqual(h.names(), ['materialJobs']);
  h.answer('materialJobs', null, { v: 1, jobs: [] });
  h.device.woken();
  assert.deepStrictEqual(h.names(), ['materialJobs', 'materialJobs'], 'Java\'s wake lists again');
});

test('materials: a job is drawn with the page\'s document and saved through FrameDevice; done:true re-reads the library', function () {
  var h = matHarness(), r;
  h.env.fire(0);
  h.answer('materialJobs', null, { v: 1, jobs: [MJOB] });
  assert.strictEqual(h.runs.length, 1);
  r = h.runs[0];
  assert.strictEqual(r.job.pid, 'pabcdefgh01');
  assert.strictEqual(r.env.document, h.env.ctx.document);
  r.env.save('gholiday1', 'pabcdefgh01', 'panel', 'data:image/jpeg;base64,x', function () {});
  assert.deepStrictEqual(h.calls[1].slice(0, 5), ['saveMaterial', 'gholiday1', 'pabcdefgh01', 'panel', 'data:image/jpeg;base64,x']);
  assert.strictEqual(h.host.refreshes, 0);
  r.done(null, { done: true });
  assert.strictEqual(h.host.refreshes, 1, 'the photo appears');
  assert.strictEqual(h.names().filter(function (n) { return n === 'materialJobs'; }).length, 2, 'looks for the next job');
});

test('materials: every 2 s while Add photo is open the queue is woken; the screen is redrawn as the count changes', function () {
  var h = matHarness(), t;
  h.env.fire(0); h.answer('materialJobs', null, { v: 1, jobs: [] });
  t = h.views.openUpload('gholiday1');
  assert.deepStrictEqual(h.names(), ['materialJobs', 'uploadStart', 'materialJobs'], 'opening wakes it');
  h.answer('uploadStart', null, { url: START.url, base: START.base, code: START.code, expires: h.env.clock.now + 900000 });
  h.answer('materialJobs', null, { v: 1, jobs: [MJOB] });
  assert.strictEqual(h.views.materials().status().preparing, 1);
  assert.ok(h.host.afters >= 1, 'redrawn');
  h.runs[0].done(null, { done: true });
  h.answer('materialJobs', null, { v: 1, jobs: [] });
  h.env.fire(2000);
  assert.strictEqual(h.names().filter(function (n) { return n === 'materialJobs'; }).length, 4, 'polled');
  h.views.V.upload.key(t, 'ok');
  assert.ok(!h.env.timers.some(function (x) { return x.ms === 2000; }), 'closed: no more polling');
});

test('materials: no queue without materials.js or without the bridge calls (web preview, older APK)', function () {
  assert.strictEqual(harness().views.materials(), null);
  var h = harness({ materials: true });
  assert.ok(h.views.materials());
  var env = load({ materials: true, device: { uploadStart: function () {} } });
  assert.strictEqual(env.PG.views({ refreshLibrary: function () {} }).materials(), null);
});

test('preparingText and uploadProblem: the Add photo screen\'s two new lines', function () {
  var P = PG();
  assert.strictEqual(P.preparingText(0, 0), '');
  assert.strictEqual(P.preparingText(1, 0), 'Preparing 1 photo…');
  assert.strictEqual(P.preparingText(3, 0), 'Preparing 3 photos…');
  assert.strictEqual(P.preparingText(2, 1), 'Still preparing 2 photos…');
  assert.deepStrictEqual(plain(P.uploadProblem('No network connection')),
    { title: 'No network connection.', detail: 'Connect the TV to Wi-Fi or Ethernet, then choose Try again.', network: true });
  assert.deepStrictEqual(plain(P.uploadProblem('The group is full.')), { title: 'Couldn’t start adding photos.', detail: 'The group is full.', network: false });
  assert.strictEqual(P.uploadProblem('').detail, 'Something went wrong.');
});

test('ES5 only: no let/const/arrow/class/template in web/photo-groups.js, web/qrcode-generator.js or web/materials.js', function () {
  [SOURCE, QR_SOURCE, MATERIALS_SOURCE].forEach(function (src) {
    var code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
    assert.ok(!/\b(let|const|class)\s/.test(code), 'let/const/class');
    assert.ok(!/=>/.test(code), 'arrow function');
    assert.ok(code.indexOf('`') < 0, 'template literal');
  });
});

/* ---------- The patched main.js key handler around the text box (DEVICE-CHECK 2) ---------- */

/* On the stick (2026-10-31) OK on the name field opened Android's keyboard, which swallowed that OK's
   keyup; main.js's heldKeys[13] stayed set and the first OK on Create was dropped as a held key. This
   runs the patched handler from web/main.js with a fake PGV and replays the stick's key sequence. */
function mainKeys() {
  var main = fs.readFileSync(path.join(WEB, 'main.js'), 'utf8');
  var a = main.indexOf('  var heldKeys = {};\n'), b = main.indexOf("  window.addEventListener('blur'", a);
  assert.ok(a > 0 && b > a, 'the heldKeys block is in web/main.js');
  var pressed = [], typingNow = { on: false }, docL = {};
  var ctx = {
    KEYS: { 13: 'ok', 37: 'left', 38: 'up', 39: 'right', 40: 'down', 27: 'back' }, pressed: pressed,
    press: function (k) { pressed.push(k); },
    holdStart: function () { return false; }, HOLD: { end: function () {}, cancel: function () {} },   // long press: not on a tile here
    X: { activity: function () { return false; }, keyUp: function () {} },   // Settings > Screen: Home is not dimmed here
    PGV: { inputKey: function (ev) {
      if (!typingNow.on) return false;
      if (ev.keyCode === 13 || ev.keyCode === 40) typingNow.on = false;   // leaves the field
      return true;
    } },
    AEV: { inputKey: function () { return false; } },   // Rename app (web/app-edit.js): not open here
    document: { addEventListener: function (t, fn) { docL[t] = fn; } }
  };
  vm.createContext(ctx);
  vm.runInContext(main.slice(a, b), ctx, { filename: 'main.js (heldKeys block)' });
  function ev(type, code) { docL[type]({ type: type, keyCode: code, repeat: false, preventDefault: function () {} }); }
  return { ev: ev, pressed: pressed, typing: typingNow };
}

test('patched main.js: the OK that opened the keyboard (keyup swallowed) does not swallow the next OK on Create', function () {
  var m = mainKeys();
  m.ev('keydown', 13);                  // OK on the field: main.js presses ok, the field takes the keyboard
  assert.deepStrictEqual(m.pressed, ['ok']);
  m.typing.on = true;                   // ...and the IME eats the keyup: no keyup 13 reaches the page
  m.ev('keydown', 229); m.ev('keyup', 229);   // typing (input text)
  m.ev('keydown', 40);                  // Down leaves the field for the buttons (Back hid the keyboard unseen)
  m.ev('keyup', 40);
  m.ev('keydown', 13);                  // OK on Create
  assert.deepStrictEqual(m.pressed, ['ok', 'ok'], 'the first OK on Create reaches press()');
  m.ev('keydown', 13);                  // still held (no keyup yet): ignored as before
  assert.deepStrictEqual(m.pressed, ['ok', 'ok']);
});

test('patched main.js: the keyboard Done key (Enter in the field) leaves no held OK behind either', function () {
  var m = mainKeys();
  m.ev('keydown', 13); m.typing.on = true;
  m.ev('keydown', 13);                  // Done on Android's keyboard: Enter to the field, taken by inputKey
  m.ev('keyup', 13);
  m.ev('keydown', 13);                  // OK on Create
  assert.deepStrictEqual(m.pressed, ['ok', 'ok']);
});

var failed = 0;
cases.forEach(function (c) {
  try { c[1](); console.log('PASS ' + c[0]); }
  catch (e) { failed++; console.log('FAIL ' + c[0] + ': ' + String((e && e.message) || e).split('\n')[0]); }
});
if (failed) { console.log('PHOTO_GROUPS_TEST_FAIL ' + failed); process.exit(1); }
console.log('PHOTO_GROUPS_TEST_OK ' + cases.length);

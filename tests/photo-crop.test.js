/* Unit tests for the crop and Adjust (2026-11-02: "chose position and zoom in and out"):
   web/photo-crop.js (window.CleanFramePhotoCrop) gives tests/upload/data/crop-vectors.json, the answers
   the stick's PhotoMath gives (tests/upload/PhotoMathTest.java checks those), and is served to the phone
   byte for byte (android/app/src/main/assets/upload/crop.js); move, zoom, soft, field and sendSize.
   web/photo-adjust.js (window.CleanFramePhotoAdjust): the remote keys on the Adjust screen, its lines,
   and the screen's flow with a fake host and device (the photo loads, Save hands "x,y,z" to recrop,
   recropState is followed until done, a failure is shown). The phone page (assets/upload/app.js,
   index.html) loads crop.js first and sends the crop field; the patched web/main.js wires Adjust into
   the group page and index.html loads both files before main.js.
   No dependencies: node:assert + node:vm. Run from the repo root: node tests/photo-crop.test.js
   Prints one line per case and exits non-zero on any failure. */
'use strict';
var assert = require('node:assert');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var ROOT = path.join(__dirname, '..');
function read(p) { return fs.readFileSync(path.join(ROOT, p), 'utf8'); }
var CROP = read('web/photo-crop.js'), ADJUST = read('web/photo-adjust.js'), MAIN = read('web/main.js'), INDEX = read('web/index.html');
var PHONE_JS = read('android/app/src/main/assets/upload/app.js'), PHONE_HTML = read('android/app/src/main/assets/upload/index.html');
var VECTORS = JSON.parse(read('tests/upload/data/crop-vectors.json')).cases;

var cases = [];
function test(name, fn) { cases.push([name, fn]); }
function plain(x) { return JSON.parse(JSON.stringify(x)); }
function load(extra) {
  var ctx = { window: {} }, k;
  for (k in extra || {}) ctx[k] = extra[k];
  vm.createContext(ctx);
  vm.runInContext(CROP, ctx, { filename: 'photo-crop.js' });
  ctx.window.CleanFramePhotoCrop.__ctx = ctx;
  return ctx.window.CleanFramePhotoCrop;
}
function loadAdjust(extra) {
  var ctx = { window: {}, setTimeout: function (fn) { ctx.timers.push(fn); }, timers: [] }, k;
  for (k in extra || {}) ctx[k] = extra[k];
  vm.createContext(ctx);
  vm.runInContext(CROP, ctx, { filename: 'photo-crop.js' });
  vm.runInContext(ADJUST, ctx, { filename: 'photo-adjust.js' });
  return ctx;
}
function close(a, b, what) { assert.ok(Math.abs(a - b) < 1e-6, what + ': ' + a + ' != ' + b); }

test('photo-crop.js gives crop-vectors.json (the stick\'s PhotoMath answers) for every case', function () {
  var C = load();
  assert.ok(VECTORS.length >= 40, 'cases ' + VECTORS.length);
  VECTORS.forEach(function (v) {
    var w = v.in[0], h = v.in[1], c = v.in[2] === null ? null : { x: v.in[2], y: v.in[3], z: v.in[4] };
    var k = C.clamp(w, h, c), f = C.frame(w, h, c), got = [k.x, k.y, k.z, f.x, f.y, f.w, f.h].concat(C.place(w, h, c));
    assert.strictEqual(got.length, v.out.length);
    got.forEach(function (x, i) { close(x, v.out[i], JSON.stringify(v.in) + ' [' + i + ']'); });
  });
});

test('the phone gets the same file: assets/upload/crop.js is web/photo-crop.js byte for byte, loaded before app.js', function () {
  assert.strictEqual(read('android/app/src/main/assets/upload/crop.js'), CROP);
  var a = PHONE_HTML.indexOf('<script src="/static/crop.js"></script>'), b = PHONE_HTML.indexOf('<script src="/static/app.js"></script>');
  assert.ok(a > 0 && b > a, 'crop.js before app.js');
});

test('move, zoomBy: in frame fractions, always clamped; soft past the photo\'s detail; field has 6 places', function () {
  var C = load(), c = C.clamp(4000, 3000, null);
  assert.deepStrictEqual(plain(C.move(4000, 3000, c, 0.1, 0)), plain(c), 'zoom 1 on 4:3: no room across');
  var z2 = C.zoomBy(4000, 3000, c, 2);
  close(z2.z, 2, 'zoom 2');
  close(C.move(4000, 3000, z2, 0.1, 0).x, 0.5 + 0.1 * 2000 / 4000, 'a tenth of the frame');
  close(C.move(4000, 3000, z2, 9, 9).x, 0.75, 'clamped to the edge');
  close(C.zoomBy(4000, 3000, c, 0.001).z, 0.75, 'down to the whole photo');
  close(C.zoomBy(4000, 3000, c, 99).z, C.Z_MAX, 'up to Z_MAX');
  assert.strictEqual(C.soft(4000, 3000, c), false, '4000 wide at zoom 1');
  assert.strictEqual(C.soft(4000, 3000, C.zoomBy(4000, 3000, c, 2.2)), true, '1818 wide at zoom 2.2');
  assert.strictEqual(C.soft(1920, 1080, c), false, 'exactly the wall');
  assert.strictEqual(C.field({ x: 1 / 3, y: 0.5, z: 1.23456789 }), '0.333333,0.5,1.234568');
  assert.ok(/^[0-9]{1,6}(\.[0-9]{1,12})?,[0-9]{1,6}(\.[0-9]{1,12})?,[0-9]{1,6}(\.[0-9]{1,12})?$/.test(C.field(C.clamp(4000, 3000, { x: 1e-9, y: 0.9999999, z: 0.75 }))),
    'what UploadServer and PendingPhotos parse');
});

test('sendSize: the phone\'s copy stays within SRC_MAX_SIDE and SRC_MAX_PIXELS, aspect kept, never enlarged', function () {
  var C = load();
  assert.deepStrictEqual(plain(C.sendSize(4000, 3000)), { w: 4000, h: 3000 }, '12 MP phone photo: as it is');
  assert.deepStrictEqual(plain(C.sendSize(800, 600)), { w: 800, h: 600 });
  [[8000, 6000], [9000, 3000], [3000, 9000], [6000, 6000], [4096, 4096]].forEach(function (s) {
    var o = C.sendSize(s[0], s[1]);
    assert.ok(o.w <= C.SRC_MAX_SIDE && o.h <= C.SRC_MAX_SIDE && o.w * o.h <= C.SRC_MAX_PIXELS, s + ' -> ' + o.w + 'x' + o.h);
    assert.ok(Math.abs(o.w / o.h - s[0] / s[1]) < 0.01, 'aspect ' + s);
  });
});

test('Adjust keys: arrows move the view (Move) or zoom (Zoom); OK/Back go to the buttons; the buttons act', function () {
  var A = loadAdjust().window.CleanFramePhotoAdjust, C = loadAdjust().window.CleanFramePhotoCrop;
  var s = { zone: 'image', mode: 'move', b: 0, w: 4000, h: 3000, c: C.clamp(4000, 3000, { x: 0.5, y: 0.5, z: 2 }) }, r;
  r = A.key(s, 'right'); close(r.s.c.x, 0.5 + A.MOVE_STEP * 0.5, 'right moves the view right');
  r = A.key(s, 'up'); assert.ok(r.s.c.y < 0.5, 'up');
  assert.strictEqual(A.key(s, 'right').s.c.z, 2, 'Move keeps the zoom');
  r = A.key({ zone: 'image', mode: 'zoom', b: 1, w: 4000, h: 3000, c: s.c }, 'up'); close(r.s.c.z, 2 * A.ZOOM_STEP, 'Up zooms in');
  r = A.key({ zone: 'image', mode: 'zoom', b: 1, w: 4000, h: 3000, c: s.c }, 'left'); close(r.s.c.z, 2 / A.ZOOM_STEP, 'Left zooms out');
  r = A.key(s, 'ok'); assert.deepStrictEqual([r.s.zone, r.s.b, r.act], ['bar', 0, null], 'OK: the buttons, on Move');
  r = A.key(s, 'back'); assert.deepStrictEqual([r.s.zone, r.act], ['bar', null], 'Back from the photo: the buttons, not cancel');
  var bar = { zone: 'bar', mode: 'move', b: 0, w: 4000, h: 3000, c: s.c };
  assert.strictEqual(A.key(bar, 'left').s.b, 0, 'stops at Move');
  assert.strictEqual(A.key({ zone: 'bar', mode: 'move', b: 5, w: 4000, h: 3000, c: s.c }, 'right').s.b, 5, 'stops at Cancel');
  r = A.key({ zone: 'bar', mode: 'move', b: 1, w: 4000, h: 3000, c: s.c }, 'ok'); assert.deepStrictEqual([r.s.mode, r.s.zone], ['zoom', 'image'], 'Zoom');
  r = A.key({ zone: 'bar', mode: 'move', b: 2, w: 4000, h: 3000, c: s.c }, 'ok'); close(r.s.c.z, 0.75, 'Whole photo');
  r = A.key({ zone: 'bar', mode: 'move', b: 3, w: 4000, h: 3000, c: s.c }, 'ok'); assert.deepStrictEqual(plain(r.s.c), { x: 0.5, y: 0.5, z: 1 }, 'Fill screen');
  assert.strictEqual(A.key({ zone: 'bar', mode: 'move', b: 4, w: 4000, h: 3000, c: s.c }, 'ok').act, 'save');
  assert.strictEqual(A.key({ zone: 'bar', mode: 'move', b: 5, w: 4000, h: 3000, c: s.c }, 'ok').act, 'cancel');
  assert.strictEqual(A.key(bar, 'back').act, 'cancel', 'Back on the buttons cancels');
  assert.strictEqual(A.key(bar, 'up').s.zone, 'image');
  assert.strictEqual(A.key({ zone: 'image', mode: 'move', b: 0, w: 0, h: 0, c: null }, 'back').act, 'cancel', 'still loading: Back leaves');
  assert.deepStrictEqual(plain(A.BUTTONS.map(function (b) { return b[1]; })), ['Move', 'Zoom', 'Whole photo', 'Fill screen', 'Save', 'Cancel']);
});

test('Adjust lines: what the arrows do; softer past the detail; black where the frame is empty', function () {
  var A = loadAdjust().window.CleanFramePhotoAdjust;
  assert.strictEqual(A.hint({ w: 0 }), 'Loading the photo…');
  assert.ok(/arrows move/.test(A.hint({ w: 1, zone: 'image', mode: 'move' })));
  assert.ok(/zooms in/.test(A.hint({ w: 1, zone: 'image', mode: 'zoom' })));
  assert.ok(/Back cancels/.test(A.hint({ w: 1, zone: 'bar', mode: 'move' })));
  assert.strictEqual(A.note({ w: 4000, h: 3000, c: { x: 0.5, y: 0.5, z: 1 } }), '');
  assert.ok(/softer/.test(A.note({ w: 4000, h: 3000, c: { x: 0.5, y: 0.5, z: 3 } })));
  assert.ok(/Black/.test(A.note({ w: 4000, h: 3000, c: { x: 0.5, y: 0.5, z: 0.8 } })));
});

test('Adjust flow: the photo loads, Save sends the crop, the screen closes once the stick is saving, done re-reads the library', function () {
  var images = [], log = [], states = ['working', 'saving', 'done'];
  function FakeImage() { images.push(this); }
  var ctx = loadAdjust({ Image: FakeImage }), A = ctx.window.CleanFramePhotoAdjust, stack = [{ id: 'wallpaper' }];
  var host = { top: function () { return stack[stack.length - 1]; }, push: function (t) { stack.push(t); return t; }, pop: function () { stack.pop(); log.push('pop'); },
    after: function () { log.push('after'); }, refreshLibrary: function () { log.push('library'); }, message: function (a, b) { log.push('message:' + b); },
    device: { recrop: function (g, p, c, done) { log.push('recrop:' + g + '/' + p + '/' + c); done(null); },
      recropState: function (g, p, done) { var s = states.shift(); log.push('state:' + s); done(null, { state: s, reason: '' }); } } };
  var PAV = A.views(host), w = { group: 'gholiday1', pid: 'pbeach00001', src: 'user/gholiday1/pbeach00001/src.jpg?v=5', crop: { x: 0.25, y: 0.5, z: 2 } };
  var t = PAV.open(w);
  assert.strictEqual(host.top(), t);
  assert.strictEqual(images[0].src, w.src, 'the upright copy is loaded');
  PAV.V.adjust.key(t, 'right'); assert.strictEqual(t.s.c, w.crop, 'nothing moves until it has loaded');
  images[0].naturalWidth = 4000; images[0].naturalHeight = 3000; images[0].onload();
  assert.deepStrictEqual(plain(t.s.c), { x: 0.25, y: 0.5, z: 2 }, 'opens on the photo\'s own crop');
  ['ok', 'right', 'right', 'right', 'right'].forEach(function (k) { PAV.V.adjust.key(t, k); });
  assert.strictEqual(t.s.b, 4, 'on Save');
  PAV.V.adjust.key(t, 'ok');
  assert.ok(t.busy, 'busy while saving');
  PAV.V.adjust.key(t, 'back'); assert.strictEqual(host.top(), t, 'keys ignored while saving');
  assert.deepStrictEqual(log.filter(function (l) { return /^recrop/.test(l); }), ['recrop:gholiday1/pbeach00001/0.25,0.5,2']);
  assert.strictEqual(host.top(), t, 'still open while working');
  ctx.timers.shift()();
  assert.strictEqual(host.top().id, 'wallpaper', 'closed once the stick is saving');
  ctx.timers.shift()();
  assert.deepStrictEqual(log.slice(-2), ['state:done', 'library'], 'done: the library is read again');
  assert.strictEqual(ctx.timers.length, 0, 'no more polling');
});

test('Adjust flow: a refusal or a failed re-make is shown on the screen; Cancel leaves without saving', function () {
  var images = [], log = [];
  function FakeImage() { images.push(this); }
  var ctx = loadAdjust({ Image: FakeImage }), A = ctx.window.CleanFramePhotoAdjust, stack = [{ id: 'wallpaper' }], answer = null, state = 'failed';
  var host = { top: function () { return stack[stack.length - 1]; }, push: function (t) { stack.push(t); return t; }, pop: function () { stack.pop(); },
    after: function () {}, refreshLibrary: function () { log.push('library'); }, message: function () { log.push('message'); },
    device: { recrop: function (g, p, c, done) { done(answer); }, recropState: function (g, p, done) { done(null, { state: state, reason: 'That photo could not be read.' }); } } };
  var PAV = A.views(host), t = PAV.open({ group: 'g', pid: 'p', src: 's' });
  images[0].naturalWidth = 1920; images[0].naturalHeight = 1080; images[0].onload();
  answer = { message: 'Could not save the new framing.', detail: 'This photo is still being saved. Try again in a moment.' };
  t.s.zone = 'bar'; t.s.b = 4; PAV.V.adjust.key(t, 'ok');
  assert.deepStrictEqual([t.busy, t.error], [false, 'This photo is still being saved. Try again in a moment.']);
  answer = null; PAV.V.adjust.key(t, 'ok');
  assert.deepStrictEqual([t.busy, t.error, host.top() === t], [false, 'That photo could not be read.', true]);
  t.s.b = 5; PAV.V.adjust.key(t, 'ok');
  assert.strictEqual(host.top().id, 'wallpaper', 'Cancel');
  var t2 = PAV.open({ group: 'g', pid: 'p', src: 'gone' });
  images[1].onerror();
  assert.strictEqual(t2.error, 'Could not open this photo.');
  PAV.V.adjust.key(t2, 'ok'); assert.strictEqual(host.top().id, 'wallpaper', 'a photo that cannot open: OK leaves');
  assert.deepStrictEqual(log, []);
});

test('the phone page frames each photo and sends the crop with an upright copy; one it cannot show goes as it is', function () {
  assert.ok(PHONE_JS.indexOf("if (item.crop) fd.append('crop', item.crop);") > 0);
  assert.ok(PHONE_JS.indexOf("fd.append('photo', item.body, item.file.name || 'photo.jpg');") > 0);
  assert.ok(PHONE_JS.indexOf("item.crop = PC.field(PC.clamp(cur.w, cur.h, cur.c));") > 0);
  assert.ok(PHONE_JS.indexOf("cv.toBlob(function (b) { done(b && b.size ? b : null); }, 'image/jpeg', JPEG_QUALITY);") > 0);
  assert.ok(PHONE_JS.indexOf("if (!cur.w) { enqueue(item);") > 0, 'not shown: sent without a crop');
  ['ed-frame', 'ed-zoom', 'ed-fit', 'ed-fill', 'ed-send', 'ed-skip', 'ed-rest'].forEach(function (id) {
    assert.ok(PHONE_HTML.indexOf('id="' + id + '"') > 0, id);
  });
  var code = PHONE_JS.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
  assert.ok(!/\b(let|const|class)\s/.test(code) && !/=>/.test(code) && code.indexOf('`') < 0, 'ES5');
});

test('patched main.js: Adjust mode on the group page opens the screen; index.html loads crop then Adjust before main.js', function () {
  ['  var PA = window.CleanFramePhotoAdjust;', '  var PAV = PA.views({', 'V.adjust = PAV.V.adjust;', '    PAV.render(t);\n  }',
    "    else if (id === 'adjust') { t.adjusting = !t.adjusting; t.removing = false; t.confirm = null; t.groupError = ''; }",
    "      else if (k === 'ok' && t.adjusting) openAdjust(t);", "adjustable: t.list.some(adjustable), adjusting: t.adjusting",
    "    if (t.adjusting) { t.adjusting = false; t.groupError = ''; return true; }",
    "  function bundleKey(look) { var w = wall(look.wall); return look.wall + '|' + look.material + (w && w.edited ? '|' + w.edited : ''); }"
  ].forEach(function (s) { assert.ok(MAIN.indexOf(s) > 0, s); });
  var a = INDEX.indexOf('<script src="photo-crop.js"></script>'), b = INDEX.indexOf('<script src="photo-adjust.js"></script>'), m = INDEX.indexOf('<script src="main.js"></script>');
  assert.ok(a > 0 && b > a && m > b);
  assert.ok(INDEX.indexOf('<link rel="stylesheet" href="photo-adjust.css">') > 0);
  [CROP, ADJUST].forEach(function (src) {
    var code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
    assert.ok(!/\b(let|const|class)\s/.test(code) && !/=>/.test(code) && code.indexOf('`') < 0, 'ES5');
  });
});

test('patched main.js: openAdjust opens only a photo with its upright copy; others say why', function () {
  var a = MAIN.indexOf('  function adjustable(w) {'), b = MAIN.indexOf('  function askRemove(t) {', a), opened = [];
  assert.ok(a > 0 && b > a);
  var ctx = { WG: { isUser: function (w) { return !!w.pid; } }, PA: { NO_SRC: 'old photo' }, PAV: { open: function (w) { opened.push(w.pid); } },
    groupPick: function (t) { return t.list[t.i]; } };
  vm.createContext(ctx); vm.runInContext(MAIN.slice(a, b) + '\nthis.openAdjust = openAdjust;', ctx);
  var t = { list: [{ pid: 'p1', src: 'x' }, { pid: 'p2' }, { id: 'misty' }], i: 0, groupError: 'x' };
  ctx.openAdjust(t); assert.deepStrictEqual([opened, t.groupError], [['p1'], '']);
  t.i = 1; ctx.openAdjust(t); assert.strictEqual(t.groupError, 'old photo');
  t.i = 2; ctx.openAdjust(t); assert.strictEqual(t.groupError, 'Only photos you added can be framed again.');
  assert.deepStrictEqual(opened, ['p1']);
});

test('patched main.js: an applied photo adjusted under the same id is loaded again; a preview of another is left alone', function () {
  var a = MAIN.indexOf('  function applyLibrary(lib) {'), b = MAIN.indexOf('  function refreshLibrary(done) {', a), k = MAIN.indexOf('  function bundleKey(look) {');
  assert.ok(a > 0 && b > a && k > 0);
  function run(shown, edited, pending) {
    var looks = [], entry = { id: 'u-g-p', edited: edited };
    var ctx = { LIB: null, WALLS: [], WALL_BASE: [], S: { stack: [], c: { bg: 'photo', wall: 'u-g-p', material: 'soft' }, shown: { wall: shown }, bundleKey: 'u-g-p|soft|5', pending: pending },
      WG: { update: function () {}, resolve: function () { return 'misty'; } }, wall: function (id) { return id === 'u-g-p' ? entry : null; },
      top: function () { return { id: 'home' }; }, relistGroup: function () {}, closeWallpaper: function () {}, setLook: function (p, o) { looks.push(JSON.stringify([p, o])); } };
    vm.createContext(ctx);
    vm.runInContext(MAIN.slice(k, MAIN.indexOf('\n', k)) + '\n' + MAIN.slice(a, b) + '\nthis.applyLibrary = applyLibrary;', ctx);
    ctx.applyLibrary({});
    return looks;
  }
  assert.deepStrictEqual(run('u-g-p', 6, null), ['[{},{"transient":true}]'], 'edited 5 -> 6: loaded again');
  assert.deepStrictEqual(run('u-g-p', 5, null), [], 'unchanged: nothing');
  assert.deepStrictEqual(run('u-other', 6, null), [], 'showing a preview of another photo: left alone');
  assert.deepStrictEqual(run('u-g-p', 6, { wall: 'x' }), [], 'a change already under way: left alone');
});

var failed = 0;
cases.forEach(function (c) {
  try { c[1](); console.log('PASS ' + c[0]); }
  catch (e) { failed++; console.log('FAIL ' + c[0] + ': ' + String((e && e.message) || e).split('\n')[0]); }
});
if (failed) { console.log('PHOTO_CROP_TEST_FAIL ' + failed); process.exit(1); }
console.log('PHOTO_CROP_TEST_OK ' + cases.length);

/* Unit tests for web/materials.js (window.CleanFrameMaterials, docs/PHOTO-GROUPS.md contract 4
   "Materials (page)"): the recipe numbers against upstream's own build-materials.mjs (parsed out of
   the vendored, sha-256-checked copy), the output sizes against upstream's committed golden-* files,
   the data URL checks, the pixel helpers, the job list filter, the drawing plan, the job queue state
   machine (one at a time, backoff, budget, done, forgotten jobs, a stopped page) and runJob's save
   order, cancellation and canvas release with a fake canvas. The drawing itself is checked against
   upstream's output in Chromium (one-off fidelity proof, docs/PHOTO-GROUPS.md), not here.
   No dependencies: node:assert + node:vm and a tiny runner. Run from the repo root:
     node tests/materials.test.js
   Prints one line per case and exits non-zero on any failure. */
'use strict';
var assert = require('node:assert');
var crypto = require('node:crypto');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var ROOT = path.join(__dirname, '..');
var SOURCE = fs.readFileSync(process.env.MATERIALS_JS || path.join(ROOT, 'web', 'materials.js'), 'utf8');
/* Upstream CleanFrame 58ac92a scripts/build-materials.mjs (tools/photo-upload/vendor/cleanframe/VENDOR.json). */
var UPSTREAM_SHA256 = 'c3dbc549ba6f1b22ca1a17eefc0220719a7b2e8453c2b2106b49fcaf1f923399';

function upstreamScript() {
  var found = [], seen = {};
  function walk(dir) {
    var entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
    entries.forEach(function (e) {
      var p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name === 'build-materials.mjs') found.push(p);
    });
  }
  walk(path.join(ROOT, 'docs', 'reference'));
  walk(path.join(ROOT, 'tools', 'photo-upload', 'vendor'));
  found = found.filter(function (p) {
    var text = fs.readFileSync(p);
    if (crypto.createHash('sha256').update(text).digest('hex') !== UPSTREAM_SHA256) return false;
    if (seen[p]) return false;
    seen[p] = 1; return true;
  });
  if (!found.length) throw new Error('no byte-exact copy of upstream build-materials.mjs (sha256 ' + UPSTREAM_SHA256.slice(0, 12) + '…) under docs/reference/ or tools/photo-upload/vendor/');
  return fs.readFileSync(found[0], 'utf8');
}

function load() {
  var ctx = { Math: Math, Object: Object, Array: Array, String: String, Error: Error };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(SOURCE, ctx, { filename: 'web/materials.js' });
  return ctx.CleanFrameMaterials;
}
function plain(x) { return x === undefined ? undefined : JSON.parse(JSON.stringify(x)); }

/* A minimal baseline JPEG header (SOI, APP0, SOF0 w x h) as a data URL, padded to `len` chars. */
function fakeJpeg(w, h, len) {
  var head = Buffer.from([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    0xFF, 0xC0, 0x00, 0x11, 0x08, h >> 8, h & 255, w >> 8, w & 255, 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01, 0xFF, 0xD9, 0x00]);   // 42 bytes: base64 without '='
  var url = 'data:image/jpeg;base64,' + head.toString('base64');
  if (len && url.length < len) url += 'A'.repeat(Math.floor((len - url.length) / 4) * 4);
  return url;
}
function fileUrl(rel) { return 'data:image/jpeg;base64,' + fs.readFileSync(path.join(ROOT, rel)).toString('base64'); }

var cases = [];
function test(name, fn) { cases.push([name, fn]); }

/* ---------- The recipe is upstream's ---------- */

test('RECIPE: every number matches upstream build-materials.mjs (vendored, sha-256 checked)', function () {
  var U = upstreamScript(), R = load().RECIPE, m, fields = [];
  function has(re, what) { var r = re.exec(U); assert.ok(r, 'upstream no longer has ' + what + ': ' + re); return r; }
  m = has(/const W = (\d+), H = (\d+), P = (\d+);/, 'W, H, P');
  assert.deepStrictEqual(plain(R.size), [+m[1], +m[2]]); assert.strictEqual(R.pad, +m[3]);
  m = has(/for \(const \[suffix, blur, saturation, quality\] of \[(.*?)\]\) \{/, 'the field table');
  m[1].replace(/\['([a-z-]+)', ([\d.]+), ([\d.]+), ([\d.]+)\]/g, function (all, k, b, s, q) { fields.push([k, +b, +s, +q]); return ''; });
  assert.strictEqual(fields.length, 3);
  assert.deepStrictEqual(plain(R.fields), fields);
  m = has(/if \(l > ([\d.]+)\) \{ const ratio = \(([\d.]+) \+ ([\d.]+) \* \(l - ([\d.]+)\)\) \/ l;/, 'the highlight knee');
  assert.deepStrictEqual([R.knee, R.knee, R.slope, R.knee], [+m[1], +m[2], +m[3], +m[4]]);
  m = has(/l = ([\d.]+) \* r \+ ([\d.]+) \* g \+ ([\d.]+) \* b/, 'linear luma');
  assert.deepStrictEqual(plain(R.luma), [+m[1], +m[2], +m[3]]);
  m = has(/v <= ([\d.]+) \? v \/ ([\d.]+) : Math\.pow\(\(v \+ ([\d.]+)\) \/ ([\d.]+), ([\d.]+)\)/, 'sRGB decode');
  assert.deepStrictEqual([R.srgb.cut, R.srgb.lin, R.srgb.a, 1 + R.srgb.a, R.srgb.gamma], [+m[1], +m[2], +m[3], +m[4], +m[5]]);
  m = has(/v <= ([\d.]+) \? ([\d.]+) \* v : ([\d.]+) \* Math\.pow\(v, 1 \/ ([\d.]+)\) - ([\d.]+)\)/, 'sRGB encode');
  assert.deepStrictEqual([R.srgb.encodeCut, R.srgb.lin, 1 + R.srgb.a, R.srgb.gamma, R.srgb.a], [+m[1], +m[2], +m[3], +m[4], +m[5]]);
  m = has(/const panel = canvas\((\d+), (\d+)\); panel\.getContext\('2d'\)\.drawImage\(frost, 0, 0, (\d+), (\d+)\);\s*files\.panel = panel\.toDataURL\('image\/jpeg', ([\d.]+)\)/, 'the panel');
  assert.deepStrictEqual([R.panel.w, R.panel.h, R.panel.w, R.panel.h, R.panel.quality], [+m[1], +m[2], +m[3], +m[4], +m[5]]);
  m = has(/createLinearGradient\(0, (\d+), 0, H\);\s*scrim\.addColorStop\(([\d.]+), '([^']+)'\); scrim\.addColorStop\(([\d.]+), '([^']+)'\); scrim\.addColorStop\(([\d.]+), '([^']+)'\);\s*bc\.fillStyle = scrim; bc\.fillRect\(0, (\d+), W, (\d+)\);/, 'the scrim');
  assert.strictEqual(R.soft.scrim.y0, +m[1]);
  assert.deepStrictEqual(plain(R.soft.scrim.stops), [[+m[2], m[3]], [+m[4], m[5]], [+m[6], m[7]]]);
  assert.deepStrictEqual([R.soft.scrim.y0, R.soft.scrim.height], [+m[8], +m[9]]);
  m = has(/sc\.filter = 'blur\((\d+)px\) saturate\(([\d.]+)\)'/, 'the Soft filter');
  assert.deepStrictEqual([R.soft.blur, R.soft.saturation], [+m[1], +m[2]]);
  m = has(/for \(let y = (\d+); y < (\d+); y\+\+\) for \(let x = (\d+); x < (\d+); x\+\+\)/, 'the band');
  assert.deepStrictEqual([R.soft.band.y0, R.soft.band.y1, R.soft.band.x0, R.soft.band.x1], [+m[1], +m[2], +m[3], +m[4]]);
  m = has(/d\[i \+ k\] = d\[i \+ k\] \* ([\d.]+) \+ mean\[k\] \* ([\d.]+);/, 'the band mix');
  assert.deepStrictEqual([R.soft.keep, R.soft.take], [+m[1], +m[2]]);
  m = has(/for \(let y = (\d+); y < (\d+); y \+= (\d+)\) for \(let x = (\d+); x < (\d+); x \+= (\d+)\)/, 'the luma sample');
  assert.deepStrictEqual([R.soft.sample.y0, R.soft.sample.y1, R.soft.sample.step, R.soft.sample.x0, R.soft.sample.x1, R.soft.sample.step], [+m[1], +m[2], +m[3], +m[4], +m[5], +m[6]]);
  m = has(/lumas\[Math\.floor\(lumas\.length \* ([\d.]+)\)\]/, 'the percentile');
  assert.strictEqual(R.soft.percentile, +m[1]);
  m = has(/Math\.max\(([\d.]+), Math\.min\(([\d.]+), \(high - ([\d.]+)\) \/ \(high - ([\d.]+)\)\)\)/, 'the tint formula');
  assert.deepStrictEqual([R.soft.tint.min, R.soft.tint.max, R.soft.tint.high, R.soft.tint.low], [+m[1], +m[2], +m[3], +m[4]]);
  m = has(/sc\.fillStyle = '(rgba\([\d,]+,)' \+ tint \+ '\)'/, 'the tint colour');
  assert.strictEqual(R.soft.colour, m[1]);
  m = has(/files\['study-soft'\] = soft\.toDataURL\('image\/jpeg', ([\d.]+)\)/, 'the Soft quality');
  assert.strictEqual(R.soft.quality, +m[1]);
});

test('KINDS and SIZES: the five files, at the size of upstream\'s committed golden-* files', function () {
  var M = load();
  assert.deepStrictEqual(plain(M.KINDS), ['panel', 'field-blur', 'field-clear', 'field-frost', 'study-soft']);
  [['panel', 'web/assets/wallpapers/golden-panel.jpg'], ['field-blur', 'web/assets/materials/golden-field-blur.jpg'],
   ['field-clear', 'web/assets/materials/golden-field-clear.jpg'], ['field-frost', 'web/assets/materials/golden-field-frost.jpg'],
   ['study-soft', 'web/assets/materials/golden-study-soft.jpg']].forEach(function (p) {
    var size = M.jpegSize(fileUrl(p[1]));
    assert.deepStrictEqual(plain(size), { w: M.SIZES[p[0]][0], h: M.SIZES[p[0]][1] }, p[1]);
  });
});

/* ---------- Data URL checks ---------- */

test('checkDataUrl: upstream\'s own files pass for their kind and fail for another', function () {
  var M = load(), panel = fileUrl('web/assets/wallpapers/golden-panel.jpg'), soft = fileUrl('web/assets/materials/golden-study-soft.jpg');
  assert.deepStrictEqual(plain(M.checkDataUrl('panel', panel)), { ok: true, reason: '' });
  assert.deepStrictEqual(plain(M.checkDataUrl('study-soft', soft)), { ok: true, reason: '' });
  assert.deepStrictEqual(plain(M.checkDataUrl('field-blur', panel)), { ok: false, reason: 'The field-blur image has the wrong size.' });
  assert.deepStrictEqual(plain(M.checkDataUrl('panel', soft)), { ok: false, reason: 'The panel image has the wrong size.' });
});

test('checkDataUrl: not JPEG, damaged base64, unknown kind, no SOF, over 1 MB -> refused with a reason', function () {
  var M = load(), ok = fakeJpeg(320, 180);
  assert.strictEqual(M.checkDataUrl('panel', ok).ok, true);
  assert.strictEqual(M.checkDataUrl('panel', ok.replace('image/jpeg', 'image/png')).reason, 'The panel image is not a JPEG.');
  assert.strictEqual(M.checkDataUrl('panel', null).reason, 'The panel image is not a JPEG.');
  assert.strictEqual(M.checkDataUrl('panel', ok + '*').reason, 'The panel image is damaged.');
  assert.strictEqual(M.checkDataUrl('panel', ok + 'AB').reason, 'The panel image is damaged.', 'length not a multiple of 4');
  assert.strictEqual(M.checkDataUrl('wall', ok).reason, 'Unknown material wall.');
  assert.strictEqual(M.checkDataUrl('panel', 'data:image/jpeg;base64,' + Buffer.from([0xFF, 0xD8, 0xFF, 0xDA, 0, 4, 0, 0, 0, 0, 0, 0]).toString('base64')).reason,
    'The panel image has the wrong size.', 'scan before any SOF');
  assert.strictEqual(M.checkDataUrl('panel', 'data:image/jpeg;base64,' + Buffer.from('not a jpeg at all').toString('base64')).reason, 'The panel image has the wrong size.');
  assert.strictEqual(M.MAX_DATA_URL, 1048576);
  assert.strictEqual(M.checkDataUrl('field-blur', fakeJpeg(1920, 1080, M.MAX_DATA_URL)).ok, true, 'exactly at the limit');
  assert.strictEqual(M.checkDataUrl('field-blur', fakeJpeg(1920, 1080, M.MAX_DATA_URL + 4)).reason, 'The field-blur image is too large.');
});

test('jpegSize: SOF0 and SOF2 (progressive), skips 0xFF fill bytes, ignores DHT (C4); null for junk', function () {
  var M = load(), b = Buffer.from(fakeJpeg(1920, 1080).split(',')[1], 'base64');
  assert.deepStrictEqual(plain(M.jpegSize(fakeJpeg(1920, 1080))), { w: 1920, h: 1080 });
  b[21] = 0xC2;
  assert.deepStrictEqual(plain(M.jpegSize('data:image/jpeg;base64,' + b.toString('base64'))), { w: 1920, h: 1080 });
  b[21] = 0xC4;
  assert.strictEqual(M.jpegSize('data:image/jpeg;base64,' + b.toString('base64')), null, 'a DHT is not a frame header');
  assert.deepStrictEqual(plain(M.jpegSize(fileUrl('web/assets/wallpapers/space-earthrise.jpg'))), { w: 1920, h: 1280 }, 'a progressive file');
  assert.strictEqual(M.jpegSize('data:image/png;base64,iVBORw0K'), null);
  assert.strictEqual(M.jpegSize(42), null);
});

test('qualities: upstream\'s quality first, then 0.06 steps down to 0.6', function () {
  var M = load();
  assert.deepStrictEqual(plain(M.qualities(0.94)), [0.94, 0.88, 0.82, 0.76, 0.7, 0.64]);
  assert.deepStrictEqual(plain(M.qualities(0.82)), [0.82, 0.76, 0.7, 0.64]);
  assert.deepStrictEqual(plain(M.qualities(0.6)), [0.6]);
});

/* ---------- Pixel helpers ---------- */

function lin(v) { v /= 255; return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
function enc(v) { return 255 * (v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055); }

test('compress: highlights over the knee come down as upstream computes them; the rest only gets alpha 255', function () {
  var M = load(), d = new Uint8ClampedArray([255, 255, 255, 0, 240, 250, 255, 7, 100, 120, 140, 0, 255, 0, 0, 3]);
  var want = new Uint8ClampedArray(d), i, r, g, b, l, ratio;
  for (i = 0; i < want.length; i += 4) {
    r = lin(want[i]); g = lin(want[i + 1]); b = lin(want[i + 2]); l = .2126 * r + .7152 * g + .0722 * b;
    if (l > .82) { ratio = (.82 + .4 * (l - .82)) / l; want[i] = enc(r * ratio); want[i + 1] = enc(g * ratio); want[i + 2] = enc(b * ratio); }
    want[i + 3] = 255;
  }
  M.compress(d);
  assert.deepStrictEqual(Array.from(d), Array.from(want));
  assert.ok(d[0] < 255 && d[0] > 230, 'white is compressed: ' + d[0]);
  assert.deepStrictEqual(Array.from(d.slice(8, 12)), [100, 120, 140, 255], 'mid-tones unchanged');
  assert.deepStrictEqual(Array.from(d.slice(12, 16)), [255, 0, 0, 255], 'saturated red is under the knee');
});

test('softTint: (high - 94) / (high - 8.87), held between 0.28 and 0.6', function () {
  var M = load();
  assert.strictEqual(M.softTint(255), Math.max(.28, Math.min(.6, (255 - 94) / (255 - 8.87))));
  assert.strictEqual(M.softTint(90), 0.28);
  assert.strictEqual(M.softTint(10000), 0.6);
  assert.ok(Math.abs(M.softTint(200) - 106 / 191.13) < 1e-12);
});

test('softMix: 65 % pixel + 35 % band mean, alpha 255; tint from the 95th-percentile sampled luma', function () {
  var M = load(), w = 1920, h = 1080, d = new Uint8ClampedArray(w * h * 4), i, x, y, tint, lumas = [];
  for (y = 0; y < h; y++) for (x = 0; x < w; x++) { i = (y * w + x) * 4; d[i] = y < 676 ? 20 : 200; d[i + 1] = y < 676 ? 20 : 180; d[i + 2] = y < 676 ? 20 : 160; d[i + 3] = 9; }
  tint = M.softMix(d, w);
  /* band (y 676..907) is all (200,180,160): top pixels -> 20*.65 + 200*.35 etc. */
  assert.deepStrictEqual(Array.from(d.slice(0, 4)), [Math.round(20 * .65 + 200 * .35), Math.round(20 * .65 + 180 * .35), Math.round(20 * .65 + 160 * .35), 255]);
  i = (800 * w + 500) * 4;
  assert.deepStrictEqual(Array.from(d.slice(i, i + 4)), [200, 180, 160, 255], 'band pixels mix with their own mean');
  for (y = 720; y < 908; y += 4) for (x = 112; x < 1808; x += 4) { i = (y * w + x) * 4; lumas.push(.2126 * d[i] + .7152 * d[i + 1] + .0722 * d[i + 2]); }
  lumas.sort(function (a, b) { return a - b; });
  assert.strictEqual(tint, M.softTint(lumas[Math.floor(lumas.length * .95)]));
});

/* ---------- Jobs ---------- */

var JOB = { gid: 'gholiday1', pid: 'pabcdefgh01', wall: 'user-tmp/gholiday1/pabcdefgh01/wall.jpg', tone: 'dark', need: ['panel', 'field-blur', 'field-clear', 'field-frost', 'study-soft'] };
function job(over) { var j = JSON.parse(JSON.stringify(JOB)), k; for (k in over || {}) j[k] = over[k]; return j; }

test('cleanJobs: keeps valid jobs (built-in groups too); drops bad ids, a wall path that is not its own, unknown kinds, empty need, duplicates', function () {
  var M = load(), out;
  out = M.cleanJobs({ v: 1, jobs: [job(), job({ gid: 'space', pid: 'pzzzzzzzzz1', wall: 'user-tmp/space/pzzzzzzzzz1/wall.jpg', need: ['panel', 'panel'], tone: 'light' }),
    job({ gid: '../x' }), job({ gid: 'gHOLIDAY1', wall: 'user-tmp/gHOLIDAY1/pabcdefgh01/wall.jpg' }), job({ pid: 'p123' }),
    job({ pid: 'pcccccccccc', wall: 'user-tmp/gholiday1/pabcdefgh01/wall.jpg' }), job({ pid: 'pdddddddddd', wall: 'user-tmp/gholiday1/pdddddddddd/../../x/wall.jpg' }),
    job({ pid: 'peeeeeeeeee', wall: 'https://evil.example/wall.jpg' }),
    job({ need: ['panel', 'wall'] }), job({ need: [] }), job({ need: 'panel' }), job(), null, 'x', { gid: 1 },
    job({ gid: 'cyberpunk', pid: 'pyyyyyyyyy1', wall: 'user-tmp/cyberpunk/pyyyyyyyyy1/wall.jpg' }),
    job({ gid: 'cyberpunkx', pid: 'pyyyyyyyyy2', wall: 'user-tmp/cyberpunkx/pyyyyyyyyy2/wall.jpg' })] });
  assert.deepStrictEqual(plain(out), [
    { gid: 'gholiday1', pid: 'pabcdefgh01', wall: JOB.wall, tone: 'dark', need: JOB.need },
    { gid: 'space', pid: 'pzzzzzzzzz1', wall: 'user-tmp/space/pzzzzzzzzz1/wall.jpg', tone: 'light', need: ['panel'] },
    { gid: 'cyberpunk', pid: 'pyyyyyyyyy1', wall: 'user-tmp/cyberpunk/pyyyyyyyyy1/wall.jpg', tone: 'dark', need: JOB.need }]);
  assert.deepStrictEqual(plain(M.cleanJobs(null)), []);
  assert.deepStrictEqual(plain(M.cleanJobs({ jobs: {} })), []);
});

test('plan: upstream drawing order; Frost is drawn for the panel even when Frost itself is done', function () {
  var M = load();
  assert.deepStrictEqual(plain(M.plan(JOB.need)), ['load', 'field-blur', 'field-frost', 'panel', 'field-clear', 'study-soft']);
  assert.deepStrictEqual(plain(M.plan(['panel'])), ['load', 'field-frost', 'panel']);
  assert.deepStrictEqual(plain(M.plan(['study-soft'])), ['load', 'study-soft']);
  assert.deepStrictEqual(plain(M.plan(['field-clear', 'field-blur'])), ['load', 'field-blur', 'field-clear']);
  assert.deepStrictEqual(plain(M.plan(['field-frost'])), ['load', 'field-frost']);
});

/* ---------- The queue ---------- */

function rig(opts) {
  opts = opts || {};
  var M = load(), timers = [], seq = 0, clock = { t: 1000 }, log = [], answers = [], runs = [], o, q;
  o = {
    now: function () { return clock.t; },
    setTimeout: function (fn, ms) { var t = { id: ++seq, fn: fn, at: clock.t + ms, ms: ms }; timers.push(t); return t.id; },
    clearTimeout: function (id) { for (var i = 0; i < timers.length; i++) if (timers[i].id === id) timers.splice(i--, 1); },
    jobs: function (done) { log.push('list'); answers.push(done); },
    run: function (j, ticket, done) { log.push('run ' + j.pid); runs.push({ job: j, ticket: ticket, done: done }); },
    finished: function (j) { log.push('finished ' + j.pid); },
    changed: function () { log.push('changed'); },
    report: function (j, reason) { log.push('report ' + (j ? j.pid : '-') + ' ' + reason); },
    budgetMs: opts.budgetMs || 60000
  };
  q = M.queue(o);
  return {
    M: M, q: q, log: log, runs: runs, timers: timers, clock: clock,
    answer: function (list, err) { var d = answers.shift(); assert.ok(d, 'a listing is pending'); d(err || null, err ? undefined : { v: 1, jobs: list }); },
    pendingLists: function () { return answers.length; },
    advance: function (ms) {   // run every timer due within ms, in time order
      var end = clock.t + ms, t;
      for (;;) {
        timers.sort(function (a, b) { return a.at - b.at || a.id - b.id; });
        if (!timers.length || timers[0].at > end) break;
        t = timers.shift(); clock.t = Math.max(clock.t, t.at); t.fn();
      }
      clock.t = end;
    },
    events: function (prefix) { return log.filter(function (l) { return l.indexOf(prefix) === 0; }); }
  };
}
var J1 = job({ pid: 'p000000000a', wall: 'user-tmp/gholiday1/p000000000a/wall.jpg' });
var J2 = job({ pid: 'p000000000b', wall: 'user-tmp/gholiday1/p000000000b/wall.jpg' });

test('queue: one job at a time; a wake while running asks for one more listing afterwards, not a second run', function () {
  var r = rig();
  r.q.wake();
  r.answer([J1, J2]);
  assert.deepStrictEqual(r.events('run'), ['run p000000000a']);
  r.q.wake(); r.q.wake();
  assert.strictEqual(r.pendingLists(), 0, 'no listing while a job runs');
  assert.strictEqual(r.q.status().state, 'running');
  r.runs[0].done(null, { done: true });
  assert.deepStrictEqual(r.events('finished'), ['finished p000000000a']);
  assert.strictEqual(r.pendingLists(), 1, 'one listing after the job');
  r.answer([J2]);
  assert.deepStrictEqual(r.events('run'), ['run p000000000a', 'run p000000000b']);
  r.runs[1].done(null, { done: true });
  r.answer([]);
  assert.strictEqual(r.q.status().state, 'idle');
  assert.strictEqual(r.pendingLists(), 0);
  assert.strictEqual(r.timers.length, 0, 'idle with nothing to retry: no timers at all');
});

test('queue: done:false is not finished; a job still listed after an attempt waits out its backoff (no hot loop)', function () {
  var r = rig(), i;
  r.q.wake(); r.answer([J1]);
  r.runs[0].done(null, { done: false });
  assert.deepStrictEqual(r.events('finished'), []);
  r.answer([J1]);
  assert.strictEqual(r.runs.length, 1, 'not run again at once');
  assert.strictEqual(r.q.status().state, 'idle');
  for (i = 0; i < 20; i++) { r.q.wake(); if (r.pendingLists()) r.answer([J1]); }
  assert.strictEqual(r.runs.length, 1, '20 wakes inside the window: still one attempt');
  r.advance(r.M.backoff(1));
  assert.strictEqual(r.pendingLists(), 1, 'the backoff timer lists again');
  r.answer([J1]);
  assert.strictEqual(r.runs.length, 2);
});

test('queue: a failure is reported, the next job runs, the failed one comes back after 4 s, 8 s, 16 s … capped at 5 min', function () {
  var r = rig(), M = load(), t0;
  assert.deepStrictEqual([1, 2, 3, 4, 10, 20].map(M.backoff), [4000, 8000, 16000, 32000, 300000, 300000]);
  r.q.wake(); r.answer([J1, J2]);
  r.runs[0].done('The photo could not be read.');
  assert.deepStrictEqual(r.events('report'), ['report p000000000a The photo could not be read.']);
  r.answer([J1, J2]);
  assert.deepStrictEqual(r.events('run'), ['run p000000000a', 'run p000000000b'], 'moves on to the next job');
  r.runs[1].done(null, { done: true });
  r.answer([J1]);
  assert.strictEqual(r.runs.length, 2);
  t0 = r.clock.t;
  r.advance(3999 - (r.clock.t - 1000));
  assert.strictEqual(r.runs.length, 2, 'not before 4 s');
  r.advance(1); r.answer([J1]);
  assert.strictEqual(r.runs.length, 3, 'retried at 4 s');
  r.runs[2].done('again');
  r.answer([J1]);
  r.advance(7999);
  assert.strictEqual(r.pendingLists(), 0, 'second retry not before 8 s');
  r.advance(1); r.answer([J1]);
  assert.strictEqual(r.runs.length, 4);
  assert.ok(t0 > 0);
});

test('queue: a job over its budget is cancelled and counted as failed; its late answer is ignored', function () {
  var r = rig({ budgetMs: 90000 });
  r.q.wake(); r.answer([J1, J2]);
  r.advance(89999);
  assert.strictEqual(r.runs[0].ticket.cancelled, false);
  r.advance(1);
  assert.strictEqual(r.runs[0].ticket.cancelled, true);
  assert.deepStrictEqual(r.events('report'), ['report p000000000a Preparing the photo took too long.']);
  r.answer([J1, J2]);
  assert.deepStrictEqual(r.events('run'), ['run p000000000a', 'run p000000000b']);
  r.runs[0].done(null, { done: true });
  assert.deepStrictEqual(r.events('finished'), [], 'the stale answer does nothing');
  assert.strictEqual(r.q.status().state, 'running', 'still running J2');
  r.runs[1].done(null, { done: true });
  assert.deepStrictEqual(r.events('finished'), ['finished p000000000b']);
});

test('queue: a run that throws, or a finished() that throws, is reported and the queue goes on', function () {
  var r = rig();
  r.q.wake();
  r.runs.push = function () { throw new Error('canvas blew up'); };
  r.answer([J1]);
  assert.deepStrictEqual(r.events('report'), ['report p000000000a canvas blew up']);
  assert.strictEqual(r.pendingLists(), 1);
});

test('queue: a job that is no longer listed is forgotten; listed again it runs at once', function () {
  var r = rig();
  r.q.wake(); r.answer([J1]);
  r.runs[0].done('boom');
  r.answer([]);   // photo deleted, or finished by another page
  r.q.wake(); r.answer([J1]);
  assert.strictEqual(r.runs.length, 2, 'no backoff left over');
});

test('queue: status counts the listed jobs and those that failed twice or more', function () {
  var r = rig();
  assert.deepStrictEqual(plain(r.q.status()), { preparing: 0, failing: 0, state: 'idle' });
  r.q.wake(); r.answer([J1, J2]);
  assert.deepStrictEqual(plain(r.q.status()), { preparing: 2, failing: 0, state: 'running' });
  r.runs[0].done('x'); r.answer([J1, J2]); r.runs[1].done(null, { done: true }); r.answer([J1]);
  r.advance(4000); r.answer([J1]); r.runs[2].done('x'); r.answer([J1]);
  assert.deepStrictEqual(plain(r.q.status()), { preparing: 1, failing: 1, state: 'idle' });
  assert.ok(r.events('changed').length >= 5, 'the screen is told about changes');
});

test('queue: a listing error is reported and the queue goes idle with no timer (the next wake or poll asks again)', function () {
  var r = rig();
  r.q.wake(); r.answer(null, { message: 'Could not read the photos being prepared.', detail: 'Update CleanFrame on the TV to use photo groups.' });
  assert.deepStrictEqual(r.events('report'), ['report - Update CleanFrame on the TV to use photo groups.']);
  assert.strictEqual(r.q.status().state, 'idle');
  assert.strictEqual(r.timers.length, 0);
  r.q.wake();
  assert.strictEqual(r.pendingLists(), 1);
});

test('queue: a wake during a listing lists once more after it (Java may have queued after the answer was made)', function () {
  var r = rig();
  r.q.wake(); r.q.wake();
  assert.strictEqual(r.pendingLists(), 1);
  r.answer([]);
  assert.strictEqual(r.pendingLists(), 1, 'the second wake is honoured');
  r.answer([J1]);
  assert.strictEqual(r.runs.length, 1);
});

test('queue: stop() (a page being replaced) ignores answers that arrive afterwards and never runs again', function () {
  var r = rig();
  r.q.wake(); r.answer([J1]);
  r.runs[0].done('x'); // backoff timer for J1 pending after the next listing
  r.q.stop();
  r.answer([J1, J2]);
  assert.strictEqual(r.runs.length, 1);
  r.q.wake(); r.advance(600000);
  assert.strictEqual(r.pendingLists(), 0);
  assert.strictEqual(r.runs.length, 1);
});

/* ---------- runJob with a fake canvas ---------- */

function fakeEnv(opts) {
  opts = opts || {};
  var canvases = [], saves = [], tasks = [], ctxLog = [];
  function Canvas() {
    var c = { width: 0, height: 0, toDataURL: function (type, q) {
      ctxLog.push('jpeg ' + c.width + 'x' + c.height + ' q' + q);
      if (opts.big && q > 0.8) return fakeJpeg(c.width, c.height, 1048576 + 4);
      return fakeJpeg(c.width, c.height); } };
    c.getContext = function () {
      var g = { filter: 'none', fillStyle: '' };
      if (opts.noFilter) delete g.filter;
      ['save', 'restore', 'translate', 'scale', 'fillRect', 'putImageData'].forEach(function (m) { g[m] = function () {}; });
      g.drawImage = function () { if ('filter' in g && g.filter !== 'none') ctxLog.push('draw ' + g.filter); };
      g.createLinearGradient = function () { return { addColorStop: function () {} }; };
      g.getImageData = function (x, y, w, h) { return { data: new Uint8ClampedArray(w * h * 4) }; };
      return g;
    };
    canvases.push(c); return c;
  }
  function Img() { var i = this; i.width = 0; i.height = 0; Object.defineProperty(i, 'src', { set: function (v) {
    i._src = v; if (!v) return;
    tasks.push(function () { if (opts.badImage) { if (i.onerror) i.onerror(); } else { i.width = 1920; i.height = 1280; if (i.onload) i.onload(); } });
  }, get: function () { return i._src; } }); }
  var env = {
    document: { createElement: function (t) { assert.strictEqual(t, 'canvas'); return Canvas(); } },
    Image: Img,
    later: function (fn) { tasks.push(fn); },
    save: function (g, p, kind, url, done) {
      saves.push(kind);
      tasks.push(function () {
        if (opts.saveFails === kind) done({ message: 'Could not save a prepared photo.', detail: 'The disk is full.' });
        else done(null, { done: saves.length === (opts.total || 5) });
      });
    }
  };
  return { env: env, canvases: canvases, saves: saves, ctxLog: ctxLog,
    drain: function (until) { var n = 0; while (tasks.length && !(until && until())) { tasks.shift()(); if (++n > 500) throw new Error('task storm'); } } };
}

test('runJob: saves the needed kinds in upstream order, done from the last save, every canvas freed', function () {
  var M = load(), f = fakeEnv(), out = [];
  M.runJob(job(), { cancelled: false }, f.env, function (err, res) { out.push([err, res]); });
  f.drain();
  assert.deepStrictEqual(out.map(plain), [[null, { done: true }]]);
  assert.deepStrictEqual(f.saves, ['field-blur', 'field-frost', 'panel', 'field-clear', 'study-soft']);
  assert.deepStrictEqual(f.ctxLog.filter(function (l) { return l.indexOf('draw') === 0; }),
    ['draw blur(28px) saturate(0.92)', 'draw blur(42px) saturate(0.92)', 'draw blur(2px) saturate(1.06)', 'draw blur(14px) saturate(1.12)']);
  assert.deepStrictEqual(f.ctxLog.filter(function (l) { return l.indexOf('jpeg') === 0; }),
    ['jpeg 1920x1080 q0.82', 'jpeg 1920x1080 q0.82', 'jpeg 320x180 q0.85', 'jpeg 1920x1080 q0.86', 'jpeg 1920x1080 q0.94']);
  f.canvases.forEach(function (c, i) { if (i > 0) assert.deepStrictEqual([c.width, c.height], [0, 0], 'canvas ' + i + ' freed'); });
});

test('runJob: only the remaining kinds of a half-done job (page rebuilt mid-job)', function () {
  var M = load(), f = fakeEnv({ total: 2 }), out = [];
  M.runJob(job({ need: ['panel', 'study-soft'] }), { cancelled: false }, f.env, function (err, res) { out.push([err, res]); });
  f.drain();
  assert.deepStrictEqual(f.saves, ['panel', 'study-soft']);
  assert.deepStrictEqual(out.map(plain), [[null, { done: true }]]);
});

test('runJob: a cancelled ticket stops before the next step, saves nothing more, frees everything', function () {
  var M = load(), f = fakeEnv(), out = [], ticket = { cancelled: false };
  M.runJob(job(), ticket, f.env, function (err) { out.push(err); });
  f.drain(function () { return f.saves.length === 2; });
  ticket.cancelled = true;
  f.drain();
  assert.deepStrictEqual(f.saves, ['field-blur', 'field-frost']);
  assert.deepStrictEqual(out, ['Stopped.']);
  f.canvases.forEach(function (c, i) { if (i > 0) assert.deepStrictEqual([c.width, c.height], [0, 0], 'canvas ' + i + ' freed'); });
});

test('runJob: a refused save, an unreadable photo, or no canvas filter -> one error with the reason', function () {
  var M = load(), f = fakeEnv({ saveFails: 'panel' }), out = [];
  M.runJob(job(), { cancelled: false }, f.env, function (err) { out.push(err); });
  f.drain();
  assert.deepStrictEqual(out, ['The disk is full.']);
  assert.deepStrictEqual(f.saves, ['field-blur', 'field-frost', 'panel']);
  f = fakeEnv({ badImage: true }); out = [];
  M.runJob(job(), { cancelled: false }, f.env, function (err) { out.push(err); });
  f.drain();
  assert.deepStrictEqual(out, ['The photo could not be read.']);
  f = fakeEnv({ noFilter: true }); out = [];
  M.runJob(job(), { cancelled: false }, f.env, function (err) { out.push(err); });
  f.drain();
  assert.deepStrictEqual(out, ['This TV’s browser cannot blur images (no canvas filter).']);
  assert.deepStrictEqual(f.saves, []);
});

test('runJob: a JPEG over 1 MB is re-encoded one quality step lower', function () {
  var M = load(), f = fakeEnv({ big: true, total: 1 }), out = [];
  M.runJob(job({ need: ['study-soft'] }), { cancelled: false }, f.env, function (err, res) { out.push([err, res]); });
  f.drain();
  assert.deepStrictEqual(f.ctxLog.filter(function (l) { return l.indexOf('jpeg') === 0; }), ['jpeg 1920x1080 q0.94', 'jpeg 1920x1080 q0.88', 'jpeg 1920x1080 q0.82', 'jpeg 1920x1080 q0.76']);
  assert.deepStrictEqual(out.map(plain), [[null, { done: true }]]);
});

var failed = 0, t0 = Date.now();
cases.forEach(function (c) {
  try { c[1](); console.log('ok   ' + c[0]); }
  catch (e) { failed++; console.log('FAIL ' + c[0] + '\n     ' + String(e && e.stack || e).split('\n').slice(0, 6).join('\n     ')); }
});
if (failed) { console.log('FAILED ' + failed + '/' + cases.length); process.exit(1); }
console.log('OK ' + cases.length + '/' + cases.length + ' passed in ' + (Date.now() - t0) + ' ms');

/* The phone page's client logic (android/app/src/main/assets/upload/app.js), offline: it runs in node's vm
   with a fake DOM, XMLHttpRequest, timers and clock. The upload server takes 4 connections at once
   (UploadServer.MAX_CONNECTIONS) and answers any more 503, so: at most 2 thumbs in flight; an unchanged
   list touches no image; a 503, and a reset where repeating is safe, retried with a bounded backoff;
   one poll timer however many sent photos wait, and one state request at a time; a thumb that keeps
   failing becomes a placeholder; a stalled thumb gives up its slot on a timeout, or with its row.
   No dependencies: node:assert + node:vm. Run from the repo root: node tests/upload-page.test.js
   Prints one line per case and exits non-zero on any failure. */
'use strict';
var assert = require('node:assert');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

var APP = fs.readFileSync(path.join(__dirname, '..', 'android/app/src/main/assets/upload/app.js'), 'utf8');
var TOKEN = '0123456789abcdef0123456789abcdef', POLL_MS = 2000, THUMB_TIMEOUT_MS = 15000;
var cases = [];
function test(name, fn) { cases.push([name, fn]); }

function El(tag, env) {
  this.tag = tag; this.children = []; this.attrs = {}; this.style = {}; this.parentNode = null;
  this.className = ''; this.textContent = ''; this.disabled = false; this.value = ''; this.env = env;
}
El.prototype.appendChild = function (c) {
  if (c.parentNode) c.parentNode.removeChild(c);
  this.children.push(c); c.parentNode = this; this.env.appended++;
  return c;
};
El.prototype.removeChild = function (c) {
  var i = this.children.indexOf(c);
  assert.ok(i >= 0, 'removeChild of a stranger');
  this.children.splice(i, 1); c.parentNode = null;
  return c;
};
El.prototype.replaceChild = function (n, o) {
  var i = this.children.indexOf(o);
  assert.ok(i >= 0, 'replaceChild of a stranger');
  this.children[i] = n; n.parentNode = this; o.parentNode = null;
  return o;
};
El.prototype.setAttribute = function (k, v) { this.attrs[k] = v; };
El.prototype.removeAttribute = function (k) { delete this.attrs[k]; };
El.prototype.addEventListener = function () {};
El.prototype.scrollIntoView = function () {};
El.prototype.focus = function () {};
Object.defineProperty(El.prototype, 'firstChild', { get: function () { return this.children[0] || null; } });

/* A fresh page: env.xhrs are the requests sent, env.timers the timers set, env.imgs the images made. */
function page() {
  var env = {
    xhrs: [], timers: [], imgs: [], els: {}, now: 1000000, appended: 0, srcSets: 0, inFlight: 0, maxInFlight: 0,
    el: function (id) { return env.els[id] || (env.els[id] = new El('div', env)); },
    open: function () { return env.xhrs.filter(function (x) { return !x.answered; }); },
    last: function () { var o = env.open(); assert.ok(o.length, 'a request is waiting'); return o[o.length - 1]; },
    /** Runs every timer due in the next ms, oldest first, including ones those set. */
    run: function (ms) {
      var until = env.now + ms, t;
      for (;;) {
        t = env.timers.filter(function (x) { return !x.fired && x.at <= until; }).sort(function (a, b) { return a.at - b.at; })[0];
        if (!t) break;
        t.fired = true;
        env.now = Math.max(env.now, t.at);
        t.fn();
      }
      env.now = until;
    },
    pending: function () { return env.timers.filter(function (x) { return !x.fired; }); }
  };
  function XHR() { this.upload = {}; this.status = 0; this.responseText = ''; this.answered = false; }
  XHR.prototype.open = function (m, u) { this.method = m; this.url = u; };
  XHR.prototype.setRequestHeader = function () {};
  XHR.prototype.send = function (b) { this.body = b; env.xhrs.push(this); };
  XHR.prototype.answer = function (status, obj) {
    assert.ok(!this.answered, 'answered twice');
    this.answered = true; this.status = status; this.responseText = JSON.stringify(obj);
    this.onload();
  };
  XHR.prototype.reset = function () { assert.ok(!this.answered, 'answered twice'); this.answered = true; this.onerror(); };
  function FormData() { this.parts = []; }
  FormData.prototype.append = function (k, v) { this.parts.push([k, v]); };
  function FakeDate() { this.getTime = function () { return env.now; }; }
  var doc = {
    getElementById: env.el,
    querySelectorAll: function () { return []; },
    createElement: function (tag) {
      var e = new El(tag, env), src = '';
      if (tag === 'img') {
        env.imgs.push(e);
        Object.defineProperty(e, 'src', {
          get: function () { return src; },
          set: function (v) {
            src = v; env.srcSets++; env.inFlight++; e.loading = true;
            env.maxInFlight = Math.max(env.maxInFlight, env.inFlight);
          }
        });
        e.removeAttribute = function (k) {
          if (k !== 'src' || !e.loading) return;
          e.loading = false; env.inFlight--; e.stopped = (e.stopped || 0) + 1;
        };
        e.done = function (ok) {
          assert.ok(e.loading, 'not loading');
          e.loading = false; env.inFlight--;
          (ok ? e.onload : e.onerror)();
        };
      }
      return e;
    }
  };
  var win = { addEventListener: function () {}, confirm: function () { return true; }, alert: function (m) { env.alerted = m; }, CleanFramePhotoCrop: {} };
  var ctx = {
    window: win, document: doc, location: { pathname: '/u/' + TOKEN, hash: '' }, XMLHttpRequest: XHR, FormData: FormData, Date: FakeDate,
    URL: { createObjectURL: function () { return 'blob:x'; }, revokeObjectURL: function () {} },
    setTimeout: function (fn, ms) { var t = { fn: fn, ms: ms, at: env.now + ms, fired: false }; env.timers.push(t); return env.timers.length; },
    clearTimeout: function (id) { if (id) env.timers[id - 1].fired = true; }
  };
  env.ctx = ctx;
  vm.createContext(ctx);
  vm.runInContext(APP, ctx, { filename: 'app.js' });
  return env;
}

function photo(id) {
  return { group: 'g1', id: id, name: id + '.jpg', added: 1, thumb: '/api/' + TOKEN + '/thumbs/g1/' + id + '.jpg' };
}
function state(ids) {
  return { ok: true, v: 1, group: 'g1', groups: [{ id: 'g1', name: 'Group', builtin: false, count: ids.length }], photos: ids.map(photo), pending: [] };
}
function isState(x) { return x.method === 'GET' && /\/state$/.test(x.url); }
function isUpload(x) { return x.method === 'POST' && /\/photos$/.test(x.url); }
function loaded(ids) {
  var env = page();
  env.last().answer(200, state(ids));
  return env;
}
/* Chooses n files and presses Send on each in the frame editor (none is framed: w stays 0), so they go in order. */
function choose(env, n) {
  var files = [], i;
  for (i = 0; i < n; i++) files.push({ name: 'f' + i + '.jpg', size: 10 });
  env.el('choose').onchange.call({ files: files, value: '' });
  for (i = 0; i < n; i++) env.el('ed-send').onclick();
}
function queueLine(env, i) { return env.el('queue').children[i]; }
function status(env, i) { return queueLine(env, i).children[1].textContent; }
function finishThumbs(env) { env.imgs.forEach(function (m) { if (m.loading) m.done(true); }); }
function uploaded(id) { return { ok: true, photo: { group: 'g1', id: id, name: id, added: 1, pending: true } }; }

test('at most 2 thumbs in flight, the rest follow as each ends', function () {
  var env = loaded(['a', 'b', 'c', 'd', 'e', 'f', 'g']);
  assert.strictEqual(env.imgs.length, 7, 'every photo has its image');
  assert.strictEqual(env.inFlight, 2, 'two fetched at once');
  for (var n = 3; n <= 7; n++) {
    var m = env.imgs.filter(function (i) { return i.loading; })[0];
    m.done(n % 2 === 0); // ends well or badly, its slot is free either way
    assert.ok(env.inFlight <= 2, 'still at most 2');
  }
  for (var guard = 0; guard < 200 && (env.inFlight || env.pending().length); guard++) {
    env.imgs.filter(function (i) { return i.loading; }).forEach(function (i) { i.done(true); });
    env.run(10000);
  }
  assert.strictEqual(env.maxInFlight, 2, 'never more than 2');
  assert.strictEqual(env.imgs.filter(function (i) { return !i.src; }).length, 0, 'every image was fetched');
});

test('an unchanged list is not rendered again: same elements, no image set again', function () {
  var env = loaded(['a', 'b', 'c']);
  finishThumbs(env);
  var imgs = env.imgs.length, sets = env.srcSets, row = env.el('photos').children[0], appended = env.appended;
  for (var i = 0; i < 3; i++) {
    env.el('rename-btn'); // nothing else asks: a new state comes from a load, here after a rename's Save
    env.el('name-input').value = 'x';
    env.el('rename-btn').onclick();
    env.el('name-form').onsubmit({ preventDefault: function () {} });
    env.last().answer(200, { ok: true });
    env.last().answer(200, state(['a', 'b', 'c']));
  }
  assert.strictEqual(env.imgs.length, imgs, 'no new image');
  assert.strictEqual(env.srcSets, sets, 'no thumb fetched again');
  assert.strictEqual(env.el('photos').children[0], row, 'the same row');
  assert.strictEqual(env.appended, appended, 'the list was not touched');
});

test('a changed list keeps its rows: only the new photo is fetched, a removed one goes', function () {
  var env = loaded(['a', 'b']);
  finishThumbs(env);
  var a = env.el('photos').children[0], sets = env.srcSets;
  choose(env, 1);
  env.xhrs.filter(isUpload)[0].answer(200, uploaded('c'));
  env.last().answer(200, state(['a', 'c']));
  assert.strictEqual(env.srcSets, sets + 1, 'one new thumb');
  assert.strictEqual(env.el('photos').children.length, 2, 'b went, c came');
  assert.strictEqual(env.el('photos').children[0], a, 'a kept');
});

test('a thumb that fails is retried with backoff, bounded, then a placeholder', function () {
  var env = loaded(['a']);
  var img = env.imgs[0], li = env.el('photos').children[0];
  img.done(false);
  assert.strictEqual(env.inFlight, 0, 'not at once');
  assert.deepStrictEqual(env.pending().map(function (t) { return t.ms; }), [500], 'first backoff');
  env.run(500);
  assert.strictEqual(env.inFlight, 1, 'fetched again');
  img.done(false); env.run(1000);
  img.done(false); env.run(2000);
  assert.strictEqual(env.srcSets, 4, 'four tries');
  img.done(false);
  env.run(60000);
  assert.strictEqual(env.srcSets, 4, 'and no more');
  assert.strictEqual(li.children[0].tag, 'span', 'the image gave way');
  assert.strictEqual(li.children[0].className, 'nothumb', 'to the placeholder');
  assert.strictEqual(env.inFlight, 0, 'the slot is free');
});

test('a thumb that recovers stays an image', function () {
  var env = loaded(['a']);
  var img = env.imgs[0], li = env.el('photos').children[0];
  img.done(false); env.run(500); img.done(true);
  env.run(60000);
  assert.strictEqual(li.children[0], img);
});

test('a waiting thumb whose row went is not fetched', function () {
  var env = loaded(['a', 'b', 'c']);
  var sets = env.srcSets;
  choose(env, 1);
  env.xhrs.filter(isUpload)[0].answer(200, uploaded('z'));
  env.last().answer(200, state(['a']));
  finishThumbs(env);
  assert.strictEqual(env.srcSets, sets, 'c was never fetched');
});

test('two thumbs that never finish give up their slots on the timeout, are stopped, and the next ones load', function () {
  var env = loaded(['a', 'b', 'c', 'd']);
  var a = env.imgs[0], b = env.imgs[1], c = env.imgs[2], d = env.imgs[3];
  env.run(THUMB_TIMEOUT_MS - 1);
  assert.ok(a.loading && b.loading && !c.src, 'not before the timeout');
  env.run(1);
  assert.ok(!a.loading && !b.loading, 'both stopped');
  assert.strictEqual(a.stopped, 1, 'the download itself is stopped');
  assert.ok(c.loading && d.loading, 'the next two load');
  assert.strictEqual(env.inFlight, 2);
  c.done(true); d.done(true);
  env.run(500); // the backoff: a and b are tried again
  assert.ok(a.loading && b.loading, 'a timeout is a failed try, retried');
});

test('a thumb that never finishes uses its tries and becomes the placeholder, its slot free', function () {
  var env = loaded(['a']);
  var img = env.imgs[0], li = env.el('photos').children[0];
  for (var i = 0; i < 10 && env.pending().length; i++) env.run(THUMB_TIMEOUT_MS + 8000);
  assert.strictEqual(env.srcSets, 4, 'four tries');
  assert.ok(!img.loading, 'stopped');
  assert.strictEqual(li.children[0].className, 'nothumb');
  assert.strictEqual(env.pending().length, 0, 'no timer left');
});

test('a timeout and a late load count once: no slot is released twice', function () {
  var env = loaded(['a', 'b', 'c', 'd', 'e']);
  var a = env.imgs[0], lateLoad = a.onload, lateError = a.onerror;
  env.run(THUMB_TIMEOUT_MS);
  assert.strictEqual(env.inFlight, 2, 'c and d');
  lateLoad(); lateError(); // the events of the stopped download, arriving anyway
  assert.strictEqual(env.inFlight, 2, 'e must wait: the slots were already released');
  assert.ok(!env.imgs[4].src, 'e not fetched');
  var timers = env.pending().filter(function (t) { return t.ms === 500; }).length;
  assert.strictEqual(timers, 2, 'one retry each for a and b, none from the late events');
});

test('a load counts once too: the timeout that follows it does nothing', function () {
  var env = loaded(['a', 'b', 'c']);
  env.imgs[0].done(true);
  assert.strictEqual(env.inFlight, 2, 'b and c');
  env.imgs[1].done(true);
  env.imgs[2].done(true);
  assert.strictEqual(env.pending().length, 0, 'every timeout cancelled');
});

test('leaving a group stops its thumbs and frees their slots for the next group', function () {
  var env = page(), s = state(['a', 'b', 'c']);
  s.groups.push({ id: 'g2', name: 'Two', builtin: false, count: 1 });
  s.photos.push({ group: 'g2', id: 'x', name: 'x.jpg', added: 1, thumb: '/api/' + TOKEN + '/thumbs/g2/x.jpg' });
  env.last().answer(200, s);
  var a = env.imgs[0], b = env.imgs[1], c = env.imgs[2];
  assert.ok(a.loading && b.loading, 'stalled');
  env.ctx.location.hash = '#g=g2';
  env.ctx.window.onhashchange();
  assert.strictEqual(a.stopped, 1, 'a stopped');
  assert.strictEqual(b.stopped, 1, 'b stopped');
  assert.ok(!c.src, 'c, waiting, never fetched');
  var x = env.imgs[3];
  assert.ok(x.loading, 'the new group loads at once');
  assert.strictEqual(env.inFlight, 1);
  assert.strictEqual(env.pending().length, 1, 'only its timeout: no retry for a row that went');
  x.done(true);
  env.run(60000);
  assert.strictEqual(env.srcSets, 3, 'nothing more fetched');
});

test('an upload answered 503 is retried with backoff and then succeeds', function () {
  var env = loaded([]);
  choose(env, 1);
  env.xhrs.filter(isUpload)[0].answer(503, { ok: false, error: 'busy', message: 'The TV is busy. Try again in a moment.' });
  assert.ok(/Trying again/.test(status(env, 0)), 'says so, not "failed": ' + status(env, 0));
  assert.notStrictEqual(queueLine(env, 0).className, 'failed');
  assert.strictEqual(env.xhrs.filter(isUpload).length, 1, 'not at once');
  env.run(500);
  var ups = env.xhrs.filter(isUpload);
  assert.strictEqual(ups.length, 2, 'sent again');
  assert.strictEqual(ups[1].body, ups[0].body, 'the same form');
  ups[1].answer(200, uploaded('p'));
  env.last().answer(200, state(['p']));
  assert.strictEqual(status(env, 0), 'On the TV');
});

test('an upload still refused gives up after its tries and says what the TV said', function () {
  var env = loaded([]), delays = [], sent = 0, msg = 'The TV is busy. Try again in a moment.';
  choose(env, 1);
  for (;;) {
    var ups = env.xhrs.filter(isUpload);
    if (ups.length === sent) break;
    sent = ups.length;
    ups[sent - 1].answer(503, { ok: false, error: 'busy', message: msg });
    var p = env.pending();
    if (p.length) delays.push(p[p.length - 1].ms);
    env.run(10000);
  }
  assert.strictEqual(sent, 6, 'six tries');
  assert.deepStrictEqual(delays.slice(0, 5), [500, 1000, 2000, 4000, 8000], 'backing off');
  env.xhrs.filter(isState).forEach(function (x) { if (!x.answered) x.answer(200, state([])); });
  assert.strictEqual(queueLine(env, 0).className, 'failed');
  assert.strictEqual(status(env, 0), msg);
});

test('an upload reset while sending is retried; reset once fully sent it is not (it could add the photo twice)', function () {
  var env = loaded([]);
  choose(env, 2);
  var first = env.xhrs.filter(isUpload)[0];
  first.reset();
  env.run(500);
  var second = env.xhrs.filter(isUpload)[1];
  assert.ok(second && second !== first, 'a reset before the body was sent is retried');
  second.upload.onload();
  second.reset();
  env.run(60000);
  env.open().filter(isState)[0].answer(200, state([]));
  env.run(60000);
  assert.strictEqual(env.xhrs.filter(isUpload).length, 3, 'not sent again: the next photo went instead');
  assert.strictEqual(queueLine(env, 0).className, 'failed');
  assert.ok(/Could not reach the TV/.test(status(env, 0)), status(env, 0));
});

test('an expired code is not retried, and stops the rest', function () {
  var env = loaded(['a']);
  choose(env, 1);
  env.xhrs.filter(isUpload)[0].answer(403, { ok: false, error: 'expired', message: 'x' });
  env.run(60000);
  assert.strictEqual(env.xhrs.filter(isUpload).length, 1);
  assert.strictEqual(queueLine(env, 0).className, 'failed');
  assert.strictEqual(env.el('fatal').textContent, 'This code has expired. Open Add photo on the TV again.');
});

test('other refusals (400, 413, 507) are final', function () {
  [400, 413, 507].forEach(function (code) {
    var env = loaded([]);
    choose(env, 1);
    env.xhrs.filter(isUpload)[0].answer(code, { ok: false, error: 'e', message: 'no ' + code });
    env.run(60000);
    assert.strictEqual(env.xhrs.filter(isUpload).length, 1, code + ' not retried');
    assert.strictEqual(status(env, 0), 'no ' + code);
  });
});

test('a Remove answered 503 is retried; reset, it is not sent again', function () {
  var env = loaded(['a']);
  var rm = env.el('photos').children[0].children[2];
  rm.onclick();
  var dels = function () { return env.xhrs.filter(function (x) { return x.method === 'DELETE'; }); };
  dels()[0].answer(503, { ok: false, error: 'busy', message: 'busy' });
  env.run(500);
  assert.strictEqual(dels().length, 2, '503: nothing was done, so again');
  dels()[1].reset();
  env.run(60000);
  assert.strictEqual(dels().length, 2, 'a reset may have removed it');
  assert.ok(/Could not reach/.test(env.alerted), 'told');
});

test('a state request answered 503 or reset is retried before anything is shown', function () {
  var env = page();
  env.last().answer(503, { ok: false, error: 'busy', message: 'busy' });
  assert.strictEqual(env.el('fatal').textContent, '', 'no alarm');
  env.run(500);
  env.last().reset();
  assert.strictEqual(env.el('fatal').textContent, '', 'no alarm');
  env.run(1000);
  env.last().answer(200, state(['a']));
  assert.strictEqual(env.el('photos').children.length, 1);
});

test('one poll timer, however many sent photos wait; one state request at a time', function () {
  var env = loaded([]);
  choose(env, 3);
  function ups() { return env.xhrs.filter(isUpload); }
  function polls() { return env.pending().filter(function (t) { return t.ms === POLL_MS; }).length; }
  for (var i = 0; i < 3; i++) {
    ups()[i].answer(200, uploaded('p' + i));
    assert.strictEqual(env.open().filter(isState).length, 1, 'one state request');
    env.open().filter(isState)[0].answer(200, state([]));
    assert.strictEqual(polls(), 1, 'one poll timer with ' + (i + 1) + ' waiting');
    assert.strictEqual(status(env, i), 'Preparing on the TV…');
  }
  env.run(POLL_MS);
  assert.strictEqual(env.open().filter(isState).length, 1, 'the timer asks once');
  assert.strictEqual(polls(), 0, 'no timer runs while it is asking');
  env.open().filter(isState)[0].answer(200, state(['p1']));
  assert.strictEqual(status(env, 1), 'On the TV');
  assert.strictEqual(polls(), 1, 'one timer for the two still waiting');
  env.run(POLL_MS);
  env.open().filter(isState)[0].answer(200, state(['p0', 'p1', 'p2']));
  assert.strictEqual(status(env, 0), 'On the TV');
  assert.strictEqual(status(env, 2), 'On the TV');
  assert.strictEqual(polls(), 0, 'nothing waits: polling stops');
});

test('asked for during a state request, a new one follows it (the first may be older than the change)', function () {
  var env = page();
  var first = env.last(), rm;
  first.answer(200, state(['a', 'b']));
  rm = env.el('photos').children[0].children[2];
  env.el('new-btn').onclick();
  env.el('name-input').value = 'x';
  env.el('name-form').onsubmit({ preventDefault: function () {} });
  env.last().answer(200, { ok: true, group: { id: 'g2' } });
  rm.onclick();
  env.xhrs.filter(function (x) { return x.method === 'DELETE'; })[0].answer(200, { ok: true });
  assert.strictEqual(env.open().filter(isState).length, 1, 'one at a time');
  env.open().filter(isState)[0].answer(200, state(['a', 'b']));
  assert.strictEqual(env.open().filter(isState).length, 1, 'and the one that was asked for');
  env.open().filter(isState)[0].answer(200, state(['b']));
  assert.strictEqual(env.el('photos').children.length, 1, 'the removal shows');
  assert.strictEqual(env.open().length, 0, 'and that is the end of it');
});

test('a photo that takes too long stops being polled and says so', function () {
  var env = loaded([]);
  choose(env, 1);
  env.xhrs.filter(isUpload)[0].answer(200, uploaded('p'));
  env.open().filter(isState)[0].answer(200, state([]));
  for (var i = 0; i < 70; i++) {
    env.run(POLL_MS);
    var o = env.open().filter(isState);
    if (o.length) o[0].answer(200, state([]));
  }
  assert.ok(/still preparing/.test(status(env, 0)), status(env, 0));
  assert.strictEqual(env.pending().filter(function (t) { return t.ms === POLL_MS; }).length, 0, 'polling stopped');
});

var failed = 0;
cases.forEach(function (c) {
  try { c[1](); console.log('ok   ' + c[0]); }
  catch (e) { failed++; console.log('FAIL ' + c[0] + ': ' + (e && e.stack || e)); }
});
console.log((cases.length - failed) + '/' + cases.length + ' passed');
process.exit(failed ? 1 : 0);

/* Materials for uploaded photos, drawn by the page on the stick (ours; ES5; docs/PHOTO-GROUPS.md
   contract 4 "Materials (page)"). Loaded by the patched index.html after device.js, before
   photo-groups.js, which runs the queue (on load, on FrameDevice.materialsWake, every 2 s while
   Add photo is open).

   A port of upstream CleanFrame's scripts/build-materials.mjs (58ac92a, MIT; vendored for
   reference, sha-256 in its VENDOR.json). Upstream already draws everything with canvas 2D in a
   blank page (its dock.html has no content, only `margin:0`), so every operation below is the same
   call with the same numbers: the cover crop to 1920x1080, the 96 px mirrored padding, the three
   field passes (`ctx.filter = 'blur(..px) saturate(..)'`), the linear-light highlight knee, the
   320x180 panel from Frost, and the Study Soft scrim + blur + band mix + tint, then JPEG at
   upstream's qualities. RECIPE holds every number; tests/materials.test.js parses them out of the
   vendored script and fails if they drift. Known differences (none in the drawing):
     - the Soft tint is always computed from the dock band (upstream fixes it only for its nine
       reviewed ids, which are never uploads);
     - a JPEG data URL over MAX_DATA_URL is re-encoded at a lower quality (QUALITY_STEP down to
       QUALITY_FLOOR), where upstream has no size limit (at upstream quality every committed
       material is under 150 KB, so this should never trigger);
     - the input is the stick's 1920x1280 wall.jpg, as for the Space gallery (upstream's own walls
       are 1920x1280 too); the cover crop takes the same centre 1920x1080.

   window.CleanFrameMaterials:
     pure (tests/materials.test.js):
       RECIPE, KINDS, SIZES, MAX_DATA_URL, BUDGET_MS, backoff(n)
       cleanJobs(answer)              the valid jobs of AndroidHome.materialJobs()
       checkDataUrl(kind, url)        { ok, reason }: JPEG data URL, size limit, exact dimensions
       jpegSize(url)                  { w, h } from the SOF marker, or null
       softTint(high), compress(data) (highlight knee, in place), softMix(data, w),
       qualities(q)                   the quality ladder for one material
       plan(need)                     the drawing steps for a job's `need`
       queue(o)                       the job queue state machine (below)
     browser:
       filterSupported(doc)           canvas 2D has ctx.filter
       runJob(job, ticket, env, done) draw and save one job's materials
*/
(function () {
  'use strict';
  var W = 1920, H = 1080, P = 96;
  var RECIPE = {
    size: [W, H], pad: P,
    /* [suffix, blur px, saturation, JPEG quality], in upstream's order. */
    fields: [['field-blur', 28, 0.92, 0.82], ['field-frost', 42, 0.92, 0.82], ['field-clear', 2, 1.06, 0.86]],
    knee: 0.82, slope: 0.4, luma: [0.2126, 0.7152, 0.0722],
    srgb: { cut: 0.04045, lin: 12.92, a: 0.055, gamma: 2.4, encodeCut: 0.0031308 },
    panel: { w: 320, h: 180, quality: 0.85 },
    soft: {
      scrim: { y0: 560, height: 520, stops: [[0, 'rgba(4,10,12,0)'], [0.7, 'rgba(4,10,12,.42)'], [1, 'rgba(4,10,12,.52)']] },
      blur: 14, saturation: 1.12,
      band: { x0: 96, x1: 1824, y0: 676, y1: 908 }, keep: 0.65, take: 0.35,
      sample: { x0: 112, x1: 1808, y0: 720, y1: 908, step: 4 }, percentile: 0.95,
      tint: { min: 0.28, max: 0.6, high: 94, low: 8.87 }, colour: 'rgba(4,10,12,', quality: 0.94
    }
  };
  var KINDS = ['panel', 'field-blur', 'field-clear', 'field-frost', 'study-soft'];
  var SIZES = { panel: [320, 180], 'field-blur': [W, H], 'field-clear': [W, H], 'field-frost': [W, H], 'study-soft': [W, H] };
  var MAX_DATA_URL = 1048576, QUALITY_STEP = 0.06, QUALITY_FLOOR = 0.6;
  var BUDGET_MS = 90000, BACKOFF_MS = 4000, BACKOFF_MAX_MS = 300000;
  var PREFIX = 'data:image/jpeg;base64,';
  var GID = /^(g[0-9a-z]{8}|scenery|space|cyberpunk)$/, PID = /^p[0-9a-z]{10}$/;

  function own(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function backoff(n) { return Math.min(BACKOFF_MAX_MS, BACKOFF_MS * Math.pow(2, Math.max(0, n - 1))); }

  /* ---------- pure parts ---------- */

  function cleanJobs(answer) {
    var out = [], seen = {};
    if (!answer || typeof answer !== 'object' || !Array.isArray(answer.jobs)) return out;
    answer.jobs.forEach(function (j) {
      var need = [], k;
      if (!j || typeof j !== 'object' || typeof j.gid !== 'string' || typeof j.pid !== 'string') return;
      if (!GID.test(j.gid) || !PID.test(j.pid)) return;
      if (j.wall !== 'user-tmp/' + j.gid + '/' + j.pid + '/wall.jpg') return;
      if (!Array.isArray(j.need)) return;
      for (k = 0; k < j.need.length; k++) {
        if (KINDS.indexOf(j.need[k]) < 0) return;
        if (need.indexOf(j.need[k]) < 0) need.push(j.need[k]);
      }
      if (!need.length || own(seen, j.gid + '/' + j.pid)) return;
      seen[j.gid + '/' + j.pid] = 1;
      out.push({ gid: j.gid, pid: j.pid, wall: j.wall, tone: j.tone === 'light' ? 'light' : 'dark', need: need });
    });
    return out;
  }

  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  /* The first `max` bytes of a base64 string (enough to reach a canvas JPEG's SOF marker). */
  function b64bytes(s, max) {
    var out = [], i, a, b, c, d;
    for (i = 0; i + 3 < s.length && out.length < max; i += 4) {
      a = B64.indexOf(s.charAt(i)); b = B64.indexOf(s.charAt(i + 1));
      c = s.charAt(i + 2) === '=' ? 0 : B64.indexOf(s.charAt(i + 2)); d = s.charAt(i + 3) === '=' ? 0 : B64.indexOf(s.charAt(i + 3));
      if (a < 0 || b < 0 || c < 0 || d < 0) return null;
      out.push((a << 2) | (b >> 4), ((b & 15) << 4) | (c >> 2), ((c & 3) << 6) | d);
    }
    return out;
  }
  function jpegSize(url) {
    var bytes, i, marker, len;
    if (typeof url !== 'string' || url.indexOf(PREFIX) !== 0) return null;
    bytes = b64bytes(url.slice(PREFIX.length, PREFIX.length + 87384), 65536);
    if (!bytes || bytes[0] !== 0xFF || bytes[1] !== 0xD8) return null;
    i = 2;
    while (i + 9 < bytes.length) {
      if (bytes[i] !== 0xFF) return null;
      marker = bytes[i + 1];
      if (marker === 0xFF) { i++; continue; }
      len = (bytes[i + 2] << 8) | bytes[i + 3];
      if (marker >= 0xC0 && marker <= 0xCF && marker !== 0xC4 && marker !== 0xC8 && marker !== 0xCC)
        return { h: (bytes[i + 5] << 8) | bytes[i + 6], w: (bytes[i + 7] << 8) | bytes[i + 8] };
      if (marker === 0xD9 || marker === 0xDA || len < 2) return null;
      i += 2 + len;
    }
    return null;
  }
  function checkDataUrl(kind, url) {
    var size;
    if (!own(SIZES, kind)) return { ok: false, reason: 'Unknown material ' + kind + '.' };
    if (typeof url !== 'string' || url.indexOf(PREFIX) !== 0) return { ok: false, reason: 'The ' + kind + ' image is not a JPEG.' };
    if (url.length > MAX_DATA_URL) return { ok: false, reason: 'The ' + kind + ' image is too large.' };
    if (!/^[A-Za-z0-9+\/]*={0,2}$/.test(url.slice(PREFIX.length)) || (url.length - PREFIX.length) % 4) return { ok: false, reason: 'The ' + kind + ' image is damaged.' };
    size = jpegSize(url);
    if (!size || size.w !== SIZES[kind][0] || size.h !== SIZES[kind][1]) return { ok: false, reason: 'The ' + kind + ' image has the wrong size.' };
    return { ok: true, reason: '' };
  }
  function qualities(q) {
    var out = [q], n;
    for (n = 1; q - n * QUALITY_STEP >= QUALITY_FLOOR - 1e-9; n++) out.push(Math.round((q - n * QUALITY_STEP) * 100) / 100);
    return out;
  }

  /* sRGB <-> linear exactly as upstream: a 256-entry table in, the formula out. */
  var S = RECIPE.srgb, LINEAR = [];
  (function () { var v; for (v = 0; v < 256; v++) LINEAR.push(v / 255 <= S.cut ? v / 255 / S.lin : Math.pow((v / 255 + S.a) / (1 + S.a), S.gamma)); }());
  function encode(v) { return 255 * (v <= S.encodeCut ? S.lin * v : (1 + S.a) * Math.pow(v, 1 / S.gamma) - S.a); }
  /* Highlights above the knee (linear luminance) are compressed; alpha set opaque. In place. */
  function compress(data) {
    var i, r, g, b, l, ratio, L = RECIPE.luma, K = RECIPE.knee;
    for (i = 0; i < data.length; i += 4) {
      r = LINEAR[data[i]]; g = LINEAR[data[i + 1]]; b = LINEAR[data[i + 2]]; l = L[0] * r + L[1] * g + L[2] * b;
      if (l > K) { ratio = (K + RECIPE.slope * (l - K)) / l; data[i] = encode(r * ratio); data[i + 1] = encode(g * ratio); data[i + 2] = encode(b * ratio); }
      data[i + 3] = 255;
    }
    return data;
  }
  function softTint(high) {
    var t = RECIPE.soft.tint;
    return Math.max(t.min, Math.min(t.max, (high - t.high) / (high - t.low)));
  }
  /* Study Soft's band mix on a w-wide RGBA array, in place: each pixel keeps 65 % of itself and takes
     35 % of the dock band's mean colour; returns the tint from the 95th-percentile band luma. */
  function softMix(d, w) {
    var s = RECIPE.soft, bd = s.band, sm = s.sample, mean = [0, 0, 0], count = 0, lumas = [], x, y, i, k, L = RECIPE.luma;
    for (y = bd.y0; y < bd.y1; y++) for (x = bd.x0; x < bd.x1; x++) { i = (y * w + x) * 4; for (k = 0; k < 3; k++) mean[k] += d[i + k]; count++; }
    for (k = 0; k < 3; k++) mean[k] /= count;
    for (i = 0; i < d.length; i += 4) {
      for (k = 0; k < 3; k++) d[i + k] = d[i + k] * s.keep + mean[k] * s.take;
      d[i + 3] = 255;
    }
    for (y = sm.y0; y < sm.y1; y += sm.step) for (x = sm.x0; x < sm.x1; x += sm.step) { i = (y * w + x) * 4; lumas.push(L[0] * d[i] + L[1] * d[i + 1] + L[2] * d[i + 2]); }
    lumas.sort(function (a, b) { return a - b; });
    return softTint(lumas[Math.floor(lumas.length * s.percentile)]);
  }
  /* The steps for a job, in upstream's drawing order. Frost is drawn (not saved) when only the
     panel needs it; the padded photo goes when the last field is drawn, the photo after Soft. */
  function plan(need) {
    var want = {}, steps = ['load'], lastField = -1, i;
    (need || []).forEach(function (k) { want[k] = true; });
    RECIPE.fields.forEach(function (f, n) { if (want[f[0]] || (f[0] === 'field-frost' && want.panel)) lastField = n; });
    for (i = 0; i <= lastField; i++) {
      if (want[RECIPE.fields[i][0]] || (RECIPE.fields[i][0] === 'field-frost' && want.panel)) steps.push(RECIPE.fields[i][0]);
      if (RECIPE.fields[i][0] === 'field-frost' && want.panel) steps.push('panel');
    }
    if (want['study-soft']) steps.push('study-soft');
    return steps;
  }

  /* ---------- the queue ----------
     o: { jobs(done(err, answer)), run(job, ticket, done(err, { done })), finished(job), changed(),
          report(job|null, reason), now(), setTimeout, clearTimeout, budgetMs? }
     - One job at a time. wake() while listing or running only asks for one more listing after it.
     - Each listing runs the first job that is not backing off, else goes idle (with one timer for
       the soonest backoff to end). Every attempt, failed or not, puts the job into backoff
       (BACKOFF_MS doubling to BACKOFF_MAX_MS, from the end of the attempt), so a job that never finishes, or that Java keeps
       listing after "done", is tried at most once per window and never loops hot.
     - A job gets budgetMs; then its ticket is cancelled (the runner stops at its next step and
       frees its canvases), the attempt counts as failed, and its late answer is ignored.
     - done:true from the last save calls finished(job) (the page re-reads the library).
     - A job no longer listed (finished elsewhere, photo or group deleted) is forgotten.
     - stop(): no more listings or runs; answers that arrive afterwards are ignored (a page that is
       being replaced). */
  function queue(o) {
    var state = 'idle', again = false, jobs = [], tries = {}, retry = 0, stopped = false, gen = 0;
    var budget = o.budgetMs || BUDGET_MS;
    function key(j) { return j.gid + '/' + j.pid; }
    function changed() { try { if (o.changed) o.changed(); } catch (e) { /* the screen redraws on its own poll */ } }
    function report(job, reason) { try { if (o.report) o.report(job, reason); } catch (e) { /* reporting must not stop the queue */ } }
    function reasonOf(err) { return String((err && (err.detail || err.message)) || err || 'Unknown error'); }
    function wake() {
      if (stopped) return;
      if (state !== 'idle') { again = true; return; }
      if (retry) { o.clearTimeout(retry); retry = 0; }
      list();
    }
    function list() {
      var my = gen;
      state = 'listing'; again = false;
      o.jobs(function (err, answer) {
        var t, pick = null, soon = Infinity, seen = {};
        if (stopped || my !== gen) return;
        if (err) { state = 'idle'; jobs = []; report(null, reasonOf(err)); changed(); if (again) wake(); return; }
        jobs = cleanJobs(answer); t = o.now();
        jobs.forEach(function (j) { seen[key(j)] = 1; });
        Object.keys(tries).forEach(function (k) { if (!own(seen, k)) delete tries[k]; });
        jobs.forEach(function (j) {
          var r = tries[key(j)];
          if (!r || r.until <= t) { if (!pick) pick = j; } else soon = Math.min(soon, r.until);
        });
        changed();
        if (pick) { run(pick); return; }
        state = 'idle';
        if (soon < Infinity) retry = o.setTimeout(function () { retry = 0; wake(); }, Math.max(1000, soon - t));
        if (again) wake();
      });
    }
    function run(job) {
      var k = key(job), r = tries[k] || { n: 0, failed: 0, reason: '' }, ticket = { cancelled: false }, over = false, timer, my = gen;
      r.n++; r.until = o.now() + backoff(r.n); tries[k] = r;
      state = 'running';
      function settle(err, res) {
        if (over || stopped || my !== gen) return;
        over = true; o.clearTimeout(timer);
        r.until = o.now() + backoff(r.n);   /* counted from the end of the attempt, so a slow job cannot jump the queue */
        if (err) { r.failed++; r.reason = reasonOf(err); report(job, r.reason); }
        else { r.failed = 0; r.reason = ''; if (res && res.done === true) { try { o.finished(job); } catch (e) { report(job, reasonOf(e)); } } }
        state = 'idle';
        changed();
        list();
      }
      timer = o.setTimeout(function () { ticket.cancelled = true; settle('Preparing the photo took too long.'); }, budget);
      try { o.run(job, ticket, settle); } catch (e) { ticket.cancelled = true; settle(e); }
    }
    function stop() {
      stopped = true; gen++;
      if (retry) { o.clearTimeout(retry); retry = 0; }
    }
    function status() {
      var failing = 0;
      jobs.forEach(function (j) { var r = tries[key(j)]; if (r && r.failed >= 2) failing++; });
      return { preparing: jobs.length, failing: failing, state: state };
    }
    return { wake: wake, stop: stop, status: status };
  }

  /* ---------- drawing (browser only) ---------- */

  function canvas(doc, w, h) { var c = doc.createElement('canvas'); c.width = w; c.height = h; return c; }
  function free(c) { if (c) { c.width = 0; c.height = 0; } }
  function filterSupported(doc) {
    var g = canvas(doc, 1, 1).getContext('2d'), ok;
    if (!g || !('filter' in g)) return false;
    g.filter = 'blur(2px)'; ok = g.filter === 'blur(2px)';
    return ok;
  }
  /* The photo, cover-cropped to 1920x1080 at its centre. */
  function cover(doc, image) {
    var photo = canvas(doc, W, H), pc = photo.getContext('2d'), scale = Math.max(W / image.width, H / image.height);
    pc.drawImage(image, (W - image.width * scale) / 2, (H - image.height * scale) / 2, image.width * scale, image.height * scale);
    return photo;
  }
  /* 96 px of mirrored photo round every edge, so the blur has photo to draw from. */
  function padded(doc, photo) {
    var c = canvas(doc, W + 2 * P, H + 2 * P), pad = c.getContext('2d'), x, y;
    for (y = -1; y <= 1; y++) for (x = -1; x <= 1; x++) {
      pad.save(); pad.translate(P + (x === 1 ? 2 * W : 0), P + (y === 1 ? 2 * H : 0));
      pad.scale(x === 0 ? 1 : -1, y === 0 ? 1 : -1); pad.drawImage(photo, 0, 0); pad.restore();
    }
    return c;
  }
  function field(doc, pad, spec) {
    var filtered = canvas(doc, W + 2 * P, H + 2 * P), fc = filtered.getContext('2d'), output, out, pixels;
    fc.filter = 'blur(' + spec[1] + 'px) saturate(' + spec[2] + ')'; fc.drawImage(pad, 0, 0);
    output = canvas(doc, W, H); out = output.getContext('2d'); out.drawImage(filtered, P, P, W, H, 0, 0, W, H);
    free(filtered);
    if (spec[0] !== 'field-clear') {
      pixels = out.getImageData(0, 0, W, H);
      compress(pixels.data);
      out.putImageData(pixels, 0, 0);
      pixels = null;
    }
    return output;
  }
  function panel(doc, frost) {
    var c = canvas(doc, RECIPE.panel.w, RECIPE.panel.h);
    c.getContext('2d').drawImage(frost, 0, 0, RECIPE.panel.w, RECIPE.panel.h);
    return c;
  }
  function soft(doc, photo) {
    var s = RECIPE.soft, base = canvas(doc, W, H), bc = base.getContext('2d'), scrim, i, out, sc, pixels, tint;
    bc.drawImage(photo, 0, 0);
    scrim = bc.createLinearGradient(0, s.scrim.y0, 0, H);
    for (i = 0; i < s.scrim.stops.length; i++) scrim.addColorStop(s.scrim.stops[i][0], s.scrim.stops[i][1]);
    bc.fillStyle = scrim; bc.fillRect(0, s.scrim.y0, W, s.scrim.height);
    out = canvas(doc, W, H); sc = out.getContext('2d');
    sc.filter = 'blur(' + s.blur + 'px) saturate(' + s.saturation + ')'; sc.drawImage(base, 0, 0); sc.filter = 'none';
    free(base);
    pixels = sc.getImageData(0, 0, W, H);
    tint = softMix(pixels.data, W);
    sc.putImageData(pixels, 0, 0); pixels = null;
    sc.fillStyle = s.colour + tint + ')'; sc.fillRect(0, 0, W, H);
    return out;
  }
  function quality(kind) {
    var i;
    if (kind === 'panel') return RECIPE.panel.quality;
    if (kind === 'study-soft') return RECIPE.soft.quality;
    for (i = 0; i < RECIPE.fields.length; i++) if (RECIPE.fields[i][0] === kind) return RECIPE.fields[i][3];
    return 0.82;
  }
  /* The JPEG data URL at upstream's quality, stepping down only if it is over the size limit. */
  function jpeg(c, kind) {
    var qs = qualities(quality(kind)), i, url, check = null;
    for (i = 0; i < qs.length; i++) {
      url = c.toDataURL('image/jpeg', qs[i]);
      check = checkDataUrl(kind, url);
      if (check.ok) return { url: url, quality: qs[i] };
      if (check.reason.indexOf('too large') < 0) break;
    }
    throw new Error(check ? check.reason : 'Could not encode ' + kind + '.');
  }

  /* env: { document, Image, later(fn, ms), save(gid, pid, kind, dataUrl, done(err, { done })) }.
     done(err) with a reason, or done(null, { done }) from the last save. Each step runs in its own
     task, checks the ticket first, and every canvas is freed (width = height = 0) when the job ends
     however it ends. */
  function runJob(job, ticket, env, done) {
    var doc = env.document, steps = plan(job.need), want = {}, photo = null, pad = null, frost = null, ended = false, last = { done: false };
    job.need.forEach(function (k) { want[k] = true; });
    function release() { free(photo); free(pad); free(frost); photo = pad = frost = null; }
    function end(err) {
      if (ended) return;
      ended = true; release();
      if (err) done(err); else done(null, last);
    }
    function next() {
      var step = steps.shift();
      if (!step) { end(null); return; }
      env.later(function () {
        if (ticket.cancelled) { end('Stopped.'); return; }
        try { STEP[step](step, next); } catch (e) { end('Could not draw ' + step + ': ' + ((e && e.message) || e)); }
      }, 0);
    }
    function save(kind, c, then, keep) {
      var out = jpeg(c, kind);
      if (!keep) free(c);
      env.save(job.gid, job.pid, kind, out.url, function (err, res) {
        if (ended) return;
        if (err) { end(err.detail || err.message || String(err)); return; }
        last = res || { done: false };
        then();
      });
    }
    var STEP = {
      load: function (s, then) {
        var img = new env.Image();
        if (!filterSupported(doc)) { end('This TV’s browser cannot blur images (no canvas filter).'); return; }
        img.onload = function () {
          img.onload = img.onerror = null;
          if (ended) return;
          if (!(img.width >= 64 && img.height >= 64)) { end('The photo could not be read.'); return; }
          try {
            photo = cover(doc, img);
            if (steps.length && steps[0] !== 'study-soft') pad = padded(doc, photo);
            if (!want['study-soft']) { free(photo); photo = null; }
          } catch (e) { end('Could not draw the photo: ' + ((e && e.message) || e)); return; }
          img.src = '';
          then();
        };
        img.onerror = function () { img.onload = img.onerror = null; end('The photo could not be read.'); };
        img.src = job.wall;
      },
      'field-blur': fieldStep, 'field-frost': fieldStep, 'field-clear': fieldStep,
      panel: function (s, then) { var c = panel(doc, frost); free(frost); frost = null; save('panel', c, then); },
      'study-soft': function (s, then) { var c = soft(doc, photo); free(photo); photo = null; save('study-soft', c, then); }
    };
    function fieldStep(kind, then) {
      var spec = RECIPE.fields.filter(function (f) { return f[0] === kind; })[0], c = field(doc, pad, spec), more;
      more = steps.some(function (s) { return s !== 'panel' && s !== 'study-soft'; });
      if (!more) { free(pad); pad = null; }
      if (kind === 'field-frost' && want.panel) {
        frost = c;
        if (!want['field-frost']) { then(); return; }
        save(kind, c, then, true);
        return;
      }
      save(kind, c, then);
    }
    next();
  }

  window.CleanFrameMaterials = {
    RECIPE: RECIPE, KINDS: KINDS, SIZES: SIZES, MAX_DATA_URL: MAX_DATA_URL, BUDGET_MS: BUDGET_MS,
    QUALITY_STEP: QUALITY_STEP, QUALITY_FLOOR: QUALITY_FLOOR, backoff: backoff,
    cleanJobs: cleanJobs, checkDataUrl: checkDataUrl, jpegSize: jpegSize, qualities: qualities,
    compress: compress, softTint: softTint, softMix: softMix, plan: plan, queue: queue,
    filterSupported: filterSupported, runJob: runJob
  };
}());

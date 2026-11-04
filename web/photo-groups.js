/* Photo groups: the Add group / Rename group screen, the Add photo screen and the small rules the
   group page uses (ours; ES5; docs/PHOTO-GROUPS.md "What the user sees"). Loaded by the patched
   index.html after qrcode-generator.js and wallpaper-groups.js, before main.js.
   window.CleanFramePhotoGroups:
     rules (pure, tested by tests/photo-groups.test.js):
       MAX_GROUPS, NAME_MAX, POLL_MS, RENEW_LEAD_MS
       validName(raw)          { ok, name, reason }: 1-24 characters after trimming, no control characters
       defaultName(lib)        "Group N", N = user groups + 1, skipping names already used
       canAddGroup(lib)        fewer than MAX_GROUPS user groups
       countText(n)            "15 photos" / "1 photo"
       addedText(n, group)     "No photos added to <group> yet" / "1 photo added to <group>" / "N photos added to <group>"
       actions(info)           the group page's action row for { builtin, uploads, removing, adjustable, adjusting }:
                               [{ id, label (short, on screen), name (full, for aria-label) }]
       actionsKey(acts, a, k)  D-pad on that row: { a, go: 'back' | 'list' | null }
       confirmKey(b, k)        Yes/No: { b, answer: 'yes' | 'no' | null }; starts on No (b = 1)
       nameKey(f, k)           D-pad on the name screen: { zone, b, act: 'type'|'create'|'cancel'|null }
       uploadKey(f, k, error)  D-pad on the Add photo screen: { zone, b, act: 'retry'|'close'|null }
       renewDelay(expires, now) ms until the upload code is renewed (RENEW_LEAD_MS before expiry)
       windowTop(top, focus, n, visible)  first visible row of a scrolled list that shows `focus`
       added(beforeIds, entries) how many entries are new since the screen opened
       preparingText(n, failing) "" / "Preparing 1 photo…" / "Still preparing 2 photos…" (a job retrying)
       uploadProblem(reason)   { title, detail } for an Add photo screen that could not start
                               ("No network connection" gets its own title and what to do)
       qrMatrix(text)          { n, dark(r, c) } from the vendored qrcode-generator (window.qrcode)
     views(host)               the two screens, wired to main.js's own helpers (see main.js patch),
                               and the materials queue (web/materials.js, docs/PHOTO-GROUPS.md
                               contract 4): started when the views are made (page load), woken by
                               FrameDevice.materialsWake and every POLL_MS while Add photo is open;
                               a finished photo re-reads the library so it appears in its group
*/
(function () {
  'use strict';
  var MAX_GROUPS = 20, NAME_MAX = 24, POLL_MS = 2000, RENEW_LEAD_MS = 60000, RENEW_MIN_MS = 5000, RENEW_RETRY_MS = 10000;
  var CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/;

  function chars(s) { return s.replace(/[\ud800-\udbff][\udc00-\udfff]/g, '_').length; }
  function validName(raw) {
    var name = typeof raw === 'string' ? raw.replace(/^\s+|\s+$/g, '') : '';
    if (!name) return { ok: false, name: name, reason: 'Type a name for the group.' };
    if (CONTROL.test(name)) return { ok: false, name: name, reason: 'Use letters, numbers and spaces.' };
    if (chars(name) > NAME_MAX) return { ok: false, name: name, reason: 'Use ' + NAME_MAX + ' characters or fewer.' };
    return { ok: true, name: name, reason: '' };
  }
  function userGroups(lib) {
    return (lib && lib.groups && typeof lib.groups.length === 'number' ? lib.groups : []).filter(function (g) {
      return g && g.builtin !== true && typeof g.id === 'string' && /^g[0-9a-z]{8}$/.test(g.id);
    });
  }
  function defaultName(lib) {
    var groups = userGroups(lib), used = {}, n = groups.length + 1;
    groups.forEach(function (g) { if (typeof g.name === 'string') used[g.name.toLowerCase()] = 1; });
    while (used['group ' + n]) n++;
    return 'Group ' + n;
  }
  function canAddGroup(lib) { return userGroups(lib).length < MAX_GROUPS; }
  function countText(n) { return n + (n === 1 ? ' photo' : ' photos'); }
  function addedText(n, group) { return n ? countText(n) + ' added to ' + group : 'No photos added to ' + group + ' yet'; }
  function actions(info) {
    var out = [{ id: 'add', label: 'Add photo', name: 'Add photo' }];
    if (info.uploads || info.removing) out.push({ id: 'remove', label: info.removing ? 'Done' : 'Remove', name: info.removing ? 'Done removing photos' : 'Remove photos' });
    if (info.adjustable || info.adjusting) out.push({ id: 'adjust', label: info.adjusting ? 'Done' : 'Adjust', name: info.adjusting ? 'Done adjusting photos' : 'Adjust photos' });
    if (!info.builtin) out.push({ id: 'rename', label: 'Rename', name: 'Rename group' }, { id: 'delete', label: 'Delete', name: 'Delete group' });
    return out;
  }
  function actionsKey(acts, a, k) {
    a = Math.max(0, Math.min(acts.length - 1, a));
    if (k === 'left' && a > 0) return { a: a - 1, go: null };
    if (k === 'right' && a < acts.length - 1) return { a: a + 1, go: null };
    if (k === 'up' || k === 'left') return { a: a, go: 'back' };
    if (k === 'down') return { a: a, go: 'list' };
    return { a: a, go: null };
  }
  function confirmKey(b, k) {
    if (k === 'left') return { b: 0, answer: null };
    if (k === 'right') return { b: 1, answer: null };
    if (k === 'ok') return { b: b, answer: b === 0 ? 'yes' : 'no' };
    if (k === 'back') return { b: b, answer: 'no' };
    return { b: b, answer: null };
  }
  /* Zones: back (top row), field (the text box), buttons (b: 0 Create/Rename, 1 Cancel). */
  function nameKey(f, k) {
    var z = f.zone, b = f.b || 0;
    if (k === 'back') return { zone: z, b: b, act: 'cancel' };
    if (z === 'back') {
      if (k === 'down') return { zone: 'field', b: b, act: null };
      return { zone: z, b: b, act: k === 'ok' ? 'cancel' : null };
    }
    if (z === 'field') {
      if (k === 'up') return { zone: 'back', b: b, act: null };
      if (k === 'down') return { zone: 'buttons', b: 0, act: null };
      return { zone: z, b: b, act: k === 'ok' ? 'type' : null };
    }
    if (k === 'up') return { zone: 'field', b: b, act: null };
    if (k === 'left') return { zone: z, b: 0, act: null };
    if (k === 'right') return { zone: z, b: 1, act: null };
    return { zone: z, b: b, act: k === 'ok' ? (b === 0 ? 'create' : 'cancel') : null };
  }
  /* Zones: back (top row), buttons (b: with an error 0 Try again, 1 Done; else 0 Done). */
  function uploadKey(f, k, error) {
    var z = f.zone, last = error ? 1 : 0, b = Math.max(0, Math.min(last, f.b || 0));
    if (k === 'back') return { zone: z, b: b, act: 'close' };
    if (z === 'back') {
      if (k === 'down' || k === 'right') return { zone: 'buttons', b: last, act: null };
      return { zone: z, b: b, act: k === 'ok' ? 'close' : null };
    }
    if (k === 'up') return { zone: 'back', b: b, act: null };
    if (k === 'left') return { zone: z, b: 0, act: null };
    if (k === 'right') return { zone: z, b: last, act: null };
    return { zone: z, b: b, act: k === 'ok' ? (error && b === 0 ? 'retry' : 'close') : null };
  }
  function renewDelay(expires, now) {
    if (typeof expires !== 'number' || !isFinite(expires) || typeof now !== 'number' || !isFinite(now)) return RENEW_RETRY_MS;
    return Math.max(RENEW_MIN_MS, expires - now - RENEW_LEAD_MS);
  }
  function windowTop(top, focus, n, visible) {
    if (n <= visible) return 0;
    if (focus >= 0 && focus < top) top = focus;
    if (focus >= top + visible) top = focus - visible + 1;
    return Math.max(0, Math.min(n - visible, top));
  }
  function added(beforeIds, entries) {
    var seen = {}, n = 0;
    (beforeIds || []).forEach(function (id) { seen[id] = 1; });
    (entries || []).forEach(function (w) { if (w && !Object.prototype.hasOwnProperty.call(seen, w.id)) n++; });
    return n;
  }
  function preparingText(n, failing) {
    if (!(n > 0)) return '';
    return (failing > 0 ? 'Still preparing ' : 'Preparing ') + countText(n) + '…';
  }
  function uploadProblem(reason) {
    reason = typeof reason === 'string' && reason ? reason : 'Something went wrong.';
    if (/network/i.test(reason)) return { title: reason.replace(/\.$/, '') + '.', detail: 'Connect the TV to Wi-Fi or Ethernet, then choose Try again.', network: true };
    return { title: 'Couldn’t start adding photos.', detail: reason, network: false };
  }
  function qrMatrix(text) {
    var make = window.qrcode, qr;
    if (typeof make !== 'function') throw new Error('qrcode-generator is not loaded');
    qr = make(0, 'M');
    qr.addData(String(text), 'Byte');
    qr.make();
    return { n: qr.getModuleCount(), dark: function (r, c) { return qr.isDark(r, c); } };
  }
  /* Black modules on white with a 4-module quiet zone, whole pixels per module. */
  function drawQr(canvas, text) {
    var m = qrMatrix(text), size = canvas.width, scale = Math.floor(size / (m.n + 8)), off = Math.floor((size - scale * m.n) / 2), g = canvas.getContext('2d'), r, c;
    g.fillStyle = '#fff'; g.fillRect(0, 0, size, size);
    g.fillStyle = '#000';
    for (r = 0; r < m.n; r++) for (c = 0; c < m.n; c++) if (m.dark(r, c)) g.fillRect(off + c * scale, off + r * scale, scale, scale);
    return m.n;
  }

  /* ---------- The two screens, drawn into main.js's settings panel (#appearance) ---------- */
  function views(host) {
    var session = null, INPUT = 'groupNameInput';
    function $(id) { return document.getElementById(id); }
    function device() { return window.FrameDevice; }
    function owns(t) { return !!t && (t.id === 'groupname' || t.id === 'upload'); }
    function focusClass(on) { return on ? ' is-focus' : ''; }
    function input() { return $(INPUT); }
    function typing() { var el = input(); return !!el && document.activeElement === el; }
    function leaveField() { var el = input(); if (el && document.activeElement === el) el.blur(); host.focusStage(); }
    /* A frame is written once per screen (the text box must survive re-renders with its text and
       focus); later renders only change classes and text. setHTML's cache is cleared so main.js
       redraws its own views over it afterwards. */
    function frame(el, key, html) {
      if (el._pgFrame === key) return false;
      el.innerHTML = html; el._pgFrame = key; el._html = null;
      return true;
    }
    function release(el) { if (el) el._pgFrame = null; }

    /* The materials queue: one per page, so a rebuilt page starts a fresh one (and Java lists the
       unfinished jobs again). Without materials.js or a bridge that has the two calls (web preview,
       an older APK) there is none and nothing is prepared here. */
    var M = window.CleanFrameMaterials, mq = null;
    function startMaterials() {
      var dev = device();
      if (!M || !dev || typeof dev.materialJobs !== 'function' || typeof dev.saveMaterial !== 'function') return;
      mq = M.queue({
        jobs: function (cb) { dev.materialJobs(cb); },
        run: function (job, ticket, cb) {
          M.runJob(job, ticket, { document: document, Image: window.Image, later: function (f, ms) { return setTimeout(f, ms); },
            save: function (g, p, k, u, d) { dev.saveMaterial(g, p, k, u, d); } }, cb);
        },
        finished: function () { host.refreshLibrary(); },
        changed: function () { if (session) host.after(); },
        report: function (job, reason) {
          if (window.console && console.warn) console.warn('CleanFrame materials: ' + (job ? job.gid + '/' + job.pid + ': ' : '') + reason);
        },
        now: function () { return Date.now(); },
        setTimeout: function (f, ms) { return setTimeout(f, ms); },
        clearTimeout: function (id) { clearTimeout(id); }
      });
      if (typeof dev.onMaterialsWake === 'function') dev.onMaterialsWake(mq.wake);
      setTimeout(mq.wake, 0);
    }
    function wakeMaterials() { if (mq) mq.wake(); }
    function preparing() { return mq ? mq.status() : { preparing: 0, failing: 0 }; }

    /* Add group (t.mode 'add') and Rename group ('rename', t.g). */
    function openName(mode, g) {
      var lib = host.library(), t;
      t = host.push({ id: 'groupname', mode: mode, g: g || null, key: 'n' + (++views.seq), value: mode === 'rename' ? host.groupLabel(g) : defaultName(lib),
        error: '', busy: false, f: { zone: 'field', b: 0 } });
      return t;
    }
    function nameDone(t) {
      if (host.top() === t) { leaveField(); host.pop(); }
    }
    function submitName(t) {
      var el = input(), v = validName(el ? el.value : t.value), dev = device();
      if (t.busy) return;
      t.value = el ? el.value : t.value;
      if (!v.ok) { t.error = v.reason; t.f.zone = 'field'; return; }
      if (t.mode === 'add' && !canAddGroup(host.library())) { t.error = 'There can be at most ' + MAX_GROUPS + ' groups.'; return; }
      t.busy = true; t.error = '';
      function fail(err) { t.busy = false; t.error = (err && (err.detail || err.message)) || 'Something went wrong.'; host.after(); }
      if (t.mode === 'add') dev.addGroup(v.name, function (err, res) {
        if (err) { fail(err); return; }
        host.refreshLibrary(function () {
          t.busy = false;
          if (host.top() !== t) return;
          nameDone(t); host.openGroup(res.id, true); host.after();
        });
      });
      else dev.renameGroup(t.g, v.name, function (err) {
        if (err) { fail(err); return; }
        host.refreshLibrary(function () { t.busy = false; nameDone(t); host.after(); });
      });
    }
    function nameKeyView(t, k) {
      var r = nameKey(t.f, k), el;
      t.f.zone = r.zone; t.f.b = r.b;
      if (r.act === 'cancel') { nameDone(t); return; }
      if (r.act === 'create') { submitName(t); return; }
      if (r.act === 'type') { el = input(); if (el) { t.error = ''; el.focus(); } }
    }
    function renderName(t) {
      var rows = $('appearanceRows'), add = t.mode === 'add', el, back = add ? 'Background' : host.groupLabel(t.g), html;
      $('appearance').className = 'appearance detail-groupname';
      rows.className = 'appearance-rows group-name';
      $('appearanceTitle').textContent = add ? 'Add group' : 'Rename group';
      host.setHTML($('appearanceBack'), '<button type="button" tabindex="-1" class="setting-choice setting-back' + focusClass(t.f.zone === 'back') + '" data-z="name-back" data-i="0" data-action="groupname.back">' + host.icon('left') + '<span>' + host.esc(back) + '</span></button>');
      html = '<section class="setting-section"><h3 class="setting-label"><label for="' + INPUT + '">Name</label></h3>'
        + '<div class="setting-choice name-field" data-z="name-field" data-i="0" data-action="groupname.field">'
        + '<input id="' + INPUT + '" class="name-input" type="text" maxlength="' + (NAME_MAX * 2) + '" autocomplete="off" autocapitalize="sentences" spellcheck="false" enterkeyhint="done" tabindex="-1"></div>'
        + '<p class="setting-reason name-error" aria-live="polite"></p></section>'
        + '<div class="name-buttons"><button type="button" tabindex="-1" class="setting-choice name-button" data-z="name-button" data-i="0" data-action="groupname.' + (add ? 'create' : 'rename') + '">' + (add ? 'Create' : 'Rename') + '</button>'
        + '<button type="button" tabindex="-1" class="setting-choice name-button" data-z="name-button" data-i="1" data-action="groupname.cancel">Cancel</button></div>';
      if (frame(rows, t.key, html)) {
        el = input(); el.value = t.value;
        el.addEventListener('input', function () { t.value = el.value; if (t.error) { t.error = ''; host.after(); } });
      }
      rows.querySelector('.name-field').className = 'setting-choice name-field' + focusClass(t.f.zone === 'field') + (typing() ? ' is-typing' : '');
      Array.prototype.forEach.call(rows.querySelectorAll('.name-button'), function (b, i) {
        b.className = 'setting-choice name-button' + focusClass(t.f.zone === 'buttons' && t.f.b === i) + (t.busy && i === 0 ? ' is-loading' : '');
      });
      rows.querySelector('.name-error').textContent = t.error || (t.busy ? (add ? 'Creating…' : 'Renaming…') : '');
      rows.querySelector('.name-error').className = 'setting-reason name-error' + (t.error ? ' setting-error' : '');
      host.setHTML($('appearanceStatus'), '');
      host.setHTML($('appearanceDesc'), '<span class="remote-key">OK</span><span>' + host.esc(
        typing() ? 'Type the name, then press OK on the keyboard' : t.f.zone === 'field' ? 'Type a name' : t.f.zone === 'back' || t.f.b === 1 ? 'Go back without saving' : add ? 'Create this group' : 'Save the new name') + '</span>');
    }

    /* Add photo: a code for the phone, live count, renewal, stop on close. */
    function members(g) { return host.members(g); }
    function stop() {
      var s = session;
      if (!s) return;
      session = null;
      clearTimeout(s.poll); clearTimeout(s.renew);
      if (device() && device().uploadStop) device().uploadStop(function () {});
      /* The library is re-read every POLL_MS only while the screen is open: read it once more, or a
         photo the phone removed (or added) in the last moments stays listed (stick, 2026-10-31). */
      host.refreshLibrary();
    }
    function start(t) {
      var s = { g: t.g, before: members(t.g).map(function (w) { return w.id; }), url: '', base: '', code: '', expires: 0, error: '', poll: 0, renew: 0 };
      stop();
      session = t.session = s;
      issue(s); poll(s); wakeMaterials();
    }
    function issue(s) {
      device().uploadStart(s.g, function (err, res) {
        if (session !== s) return;
        if (err) { s.error = err.detail || err.message; s.url = ''; host.after(); return; }
        s.error = ''; s.url = res.url; s.base = res.base; s.code = res.code; s.expires = res.expires;
        clearTimeout(s.renew);
        s.renew = setTimeout(function () { if (session === s) issue(s); }, renewDelay(s.expires, Date.now()));
        host.after();
      });
    }
    function poll(s) {
      s.poll = setTimeout(function () {
        if (session !== s) return;
        wakeMaterials();
        host.refreshLibrary(function () { if (session === s) { host.after(); poll(s); } });
      }, POLL_MS);
    }
    /* Android clears the upload token when the app stops (screensaver, another app) and when the
       page is rebuilt, so a screen shown again asks for a new code at once. */
    function shownAgain() {
      var s = session;
      if (!s || document.hidden) return;
      clearTimeout(s.renew);
      issue(s);
    }
    if (document.addEventListener) document.addEventListener('visibilitychange', shownAgain);
    function openUpload(g) {
      var t = host.push({ id: 'upload', g: g, key: 'u' + (++views.seq), session: null, f: { zone: 'buttons', b: 0 } });
      start(t);
      return t;
    }
    function closeUpload(t) { stop(); if (host.top() === t) host.pop(); }
    function uploadKeyView(t, k) {
      var s = t.session, r = uploadKey(t.f, k, !!(s && s.error));
      t.f.zone = r.zone; t.f.b = r.b;
      if (r.act === 'close') closeUpload(t);
      else if (r.act === 'retry') { s.error = ''; issue(s); }
    }
    function renderUpload(t) {
      var rows = $('appearanceRows'), s = t.session || {}, n = added(s.before, members(t.g)), canvas, err = !!s.error, html, buttons,
        problem = err ? uploadProblem(s.error) : null, prep = preparing(), where = s.code ? s.base : s.url;
      $('appearance').className = 'appearance detail-upload';
      rows.className = 'appearance-rows upload-screen';
      $('appearanceTitle').textContent = 'Add photo';
      host.setHTML($('appearanceBack'), '<button type="button" tabindex="-1" class="setting-choice setting-back' + focusClass(t.f.zone === 'back') + '" data-z="upload-back" data-i="0" data-action="upload.back">' + host.icon('left') + '<span>' + host.esc(host.groupLabel(t.g)) + '</span></button>');
      html = '<div class="upload-qr-plate"><canvas class="upload-qr" width="320" height="320" aria-label="QR code for the upload page"></canvas></div>'
        + '<p class="upload-scan"></p><p class="upload-base"></p><p class="upload-code"></p>'
        + '<p class="upload-added" aria-live="polite"><span class="upload-count"></span><span class="upload-preparing"></span></p><div class="upload-buttons"></div>';
      frame(rows, t.key, html);
      canvas = rows.querySelector('canvas');
      if (s.url && canvas._url !== s.url) { drawQr(canvas, s.url); canvas._url = s.url; }
      rows.querySelector('.upload-qr-plate').className = 'upload-qr-plate' + (s.url ? '' : ' is-empty');
      rows.querySelector('.upload-scan').textContent = err ? problem.title : s.url ? 'Scan with your phone’s camera, or open' : 'Getting an address…';
      rows.querySelector('.upload-scan').className = 'upload-scan' + (err ? ' setting-error' + (problem.network ? ' is-network' : '') : '');
      rows.querySelector('.upload-base').textContent = err ? problem.detail : where || '';
      rows.querySelector('.upload-base').className = 'upload-base' + (err ? ' is-detail' : where && !s.code ? ' is-long' : '');
      rows.querySelector('.upload-code').innerHTML = s.code && !err ? '<span class="upload-code-label">Code</span> ' + host.esc(s.code.slice(0, 3) + ' ' + s.code.slice(3)) : '';
      rows.querySelector('.upload-count').textContent = addedText(n, host.groupLabel(t.g));
      rows.querySelector('.upload-preparing').textContent = preparingText(prep.preparing, prep.failing);
      buttons = (err ? [['retry', 'Try again'], ['done', 'Done']] : [['done', 'Done']]).map(function (b, i) {
        return '<button type="button" tabindex="-1" class="setting-choice upload-button' + focusClass(t.f.zone === 'buttons' && t.f.b === i) + '" data-z="upload-button" data-i="' + i + '" data-action="upload.' + b[0] + '">' + b[1] + '</button>';
      }).join('');
      host.setHTML(rows.querySelector('.upload-buttons'), buttons);
      host.setHTML($('appearanceStatus'), '');
      host.setHTML($('appearanceDesc'), '<span class="remote-key">OK</span><span>' + (t.f.zone === 'back' || !err || t.f.b === 1 ? 'Finish adding photos' : problem.network ? 'Try again' : 'Get a new code') + '</span>');
    }

    var V = {
      groupname: {
        norm: function (t) { if (['back', 'field', 'buttons'].indexOf(t.f.zone) < 0) t.f.zone = 'field'; },
        key: nameKeyView,
        point: function (t, z, i) {
          if (z === 'name-back') nameDone(t);
          else if (z === 'name-field') { t.f.zone = 'field'; nameKeyView(t, 'ok'); }
          else if (z === 'name-button') { t.f.zone = 'buttons'; t.f.b = i; nameKeyView(t, 'ok'); }
        }
      },
      upload: {
        norm: function (t) { if (t.f.zone !== 'back') t.f.zone = 'buttons'; },
        key: uploadKeyView,
        point: function (t, z, i) {
          if (z === 'upload-back') closeUpload(t);
          else if (z === 'upload-button') { t.f.zone = 'buttons'; t.f.b = i; uploadKeyView(t, 'ok'); }
        }
      }
    };
    /* While the text box has the keyboard, the D-pad and Back leave it and every other key types:
       Enter/OK -> Create, Up -> the top row, Down -> the buttons, Back -> stays on the field.
       Returns true when main.js must not handle the key itself. */
    function inputKey(ev) {
      var t = host.top(), code = ev.keyCode;
      if (!owns(t) || t.id !== 'groupname' || !typing()) return false;
      if (code === 13 || code === 38 || code === 40 || code === 27 || code === 10009 || code === 461 || code === 9) {
        ev.preventDefault();
        if (ev.type !== 'keydown' || ev.repeat) return true;
        t.value = input().value;
        leaveField();
        if (code === 13 || code === 40) { t.f.zone = 'buttons'; t.f.b = 0; }
        else if (code === 38) t.f.zone = 'back';
        host.after();
      }
      return true;
    }
    function render(t) {
      if (t.id === 'groupname') { renderName(t); return true; }
      if (t.id === 'upload') { renderUpload(t); return true; }
      release($('appearanceRows'));
      return false;
    }
    /* Called on every render: an Add photo session ends as soon as its screen is gone, however it
       went (Done, Back, Home, a scene change). */
    function sync(stack) {
      if (session && !stack.some(function (t) { return t.id === 'upload' && t.session === session; })) stop();
      if (!stack.some(owns)) release($('appearanceRows'));
    }
    startMaterials();
    return { V: V, owns: owns, render: render, sync: sync, inputKey: inputKey, openName: openName, openUpload: openUpload,
      typing: typing, session: function () { return session; }, shownAgain: shownAgain, materials: function () { return mq; } };
  }
  views.seq = 0;

  window.CleanFramePhotoGroups = {
    MAX_GROUPS: MAX_GROUPS, NAME_MAX: NAME_MAX, POLL_MS: POLL_MS, RENEW_LEAD_MS: RENEW_LEAD_MS,
    validName: validName, defaultName: defaultName, canAddGroup: canAddGroup, countText: countText, addedText: addedText,
    actions: actions, actionsKey: actionsKey, confirmKey: confirmKey, nameKey: nameKey, uploadKey: uploadKey,
    renewDelay: renewDelay, windowTop: windowTop, added: added, preparingText: preparingText, uploadProblem: uploadProblem, qrMatrix: qrMatrix, drawQr: drawQr, views: views
  };
})();

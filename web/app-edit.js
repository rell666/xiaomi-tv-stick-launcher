/* Per-app name and icon (ours; ES5). 2026-11-01: "We also need to be able to edit the app
   icons and names. I would like a iptv icon that is in the clearframe style and can assign it to
   different apps. There will be others." Loaded by the patched index.html after app-marks.js and
   before main.js; window.CleanFrameAppEdit.

   An override changes only how an app LOOKS: its shown name and its white mark. Never its identity
   (main.js's app id, which is a brand like 'emby' for the default apps), its launch target (appId)
   or its place on Home. Two apps given the same mark stay two apps.

   Which mark an app shows (2026-11-02): the user's Change icon choice if there is one (a mark,
   or "App's own icon"); else the mark whose packages (web/app-marks.js) list the app's package; else
   its own icon.

   Store: localStorage 'cleanframe.tv.apps.v1' = { "<android package>": { "name"?: string,
   "mark"?: <mark id from web/app-marks.js>, or '' for an explicit "App's own icon" } }; no "mark"
   key = no choice. Upstream's saved layout (cleanframe.home.v1) is never
   rewritten for this: main.js saves an app's OWN name (ownName) in its appRecords. A bad or unknown
   value is ignored (the app's own name or icon is shown); an entry for an app uninstalled elsewhere
   is harmless (Uninstall on the long-press menu drops it, forget()); a failed save changes nothing and says so. Entries this page does not touch are written
   back as they were (an older build's or a newer build's marks survive).

   Records: main.js's app records ({ id, appId, name, brand, mark, icon }) are dressed in place:
   dress(a) keeps the app's own name and mark in a.own and sets a.name, a.mark and a.markId (the
   chosen or the package's mark, or '') to what is shown. main.js calls it after every place it sets an app's own
   values (start-up, the installed-apps list) and on every change here (onChange).

   Screens (views(host)), drawn in the settings panel like Rename group (web/photo-groups.js):
     appname  Rename: a text box typed with the remote's keyboard, then Save, Cancel, Original name
     appicon  Change icon: App's own icon first, then every mark in the library, each drawn as on a
              tile; focus starts on the current choice, OK applies it, Back cancels
   tests/app-edit.test.js covers the store, the rules, the screens' keys and the patched main.js. */
(function () {
  'use strict';
  var KEY = 'cleanframe.tv.apps.v1', NAME_MAX = 24, COLS = 4, ROWS = 4;
  /* A picker choice's name (web/home-extras.css .app-icon-name): at most LABEL_LINES lines of
     LABEL_LINE characters, word-wrapped, so "App's own icon" and every mark's name show whole. 146px
     of Manrope 500 at 22px is about 12 characters (11-11.5px each for names like "Dailymotion"). */
  var LABEL_LINE = 12, LABEL_LINES = 2, LABEL_PX = 11.5;
  var CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/;
  var SAVE_FAILED = 'Couldn’t save. Nothing changed.';

  function own(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function plainObject(v) { return !!v && typeof v === 'object' && !Array.isArray(v); }
  /* The same rule main.js uses for app ids and packages. */
  function safePkg(id) { return typeof id === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,160}$/.test(id) && ['constructor', 'prototype', '__proto__'].indexOf(id) < 0; }

  /* ---------- The mark library (web/app-marks.js) ---------- */
  function library() {
    var list = window.CleanFrameAppMarks, m = window.FrameIconMetrics || {}, seen = {};
    /* Only a mark whose SVG is measured can be drawn without stretching (markHtml). */
    return (Array.isArray(list) ? list : []).filter(function (e) {
      var ok = e && typeof e.id === 'string' && /^[a-z0-9]+$/.test(e.id) && !own(seen, e.id) && typeof e.name === 'string' && e.name
        && own(m, e.id);
      if (ok) seen[e.id] = 1;
      return ok;
    });
  }
  function markOf(id) {
    var l = library(), i;
    for (i = 0; i < l.length; i++) if (l[i].id === id) return l[i];
    return null;
  }
  /* The mark an app shows by default: the library mark whose packages list its package, or '' (the
     app keeps its own icon). A package listed under two marks gets neither (tests/app-edit.test.js
     fails on that); a bad packages value is no packages. */
  function autoMark(pkg) {
    var found = '';
    if (!safePkg(pkg)) return '';
    library().forEach(function (m) {
      if (Array.isArray(m.packages) && m.packages.indexOf(pkg) >= 0) found = found ? '!' : m.id;
    });
    return found === '!' ? '' : found;
  }
  /* A new stick's Home (2026-11-02: "only put the ones on that have a modded icon"; "if there is
     5+ put 5 apps on if there is less put less on"): the ids of the installed records that show a
     white mark, one app per mark, in the library's order (Emby, YouTube, Spotify, Rumble, Netflix…),
     at most max. records are main.js app records (id, markId), already dressed. */
  var STARTER = 5;
  function starter(records, max) {
    var l = library(), order = {}, seen = {}, picked = [];
    l.forEach(function (m, i) { order[m.id] = i; });
    (Array.isArray(records) ? records : []).forEach(function (a, i) {
      if (!a || typeof a.id !== 'string' || !a.id || typeof a.markId !== 'string' || !own(order, a.markId) || own(seen, a.markId)) return;
      seen[a.markId] = 1;
      picked.push({ id: a.id, rank: order[a.markId], i: i });
    });
    picked.sort(function (a, b) { return a.rank - b.rank || a.i - b.i; });
    return picked.slice(0, max > 0 ? max : STARTER).map(function (p) { return p.id; });
  }

  /* ---------- Names ---------- */
  function chars(s) { return s.replace(/[\ud800-\udbff][\udc00-\udfff]/g, '_').length; }
  /* { ok, name, reason }: 1-NAME_MAX characters after trimming, no control characters. */
  function validName(raw) {
    var name = typeof raw === 'string' ? raw.replace(/^\s+|\s+$/g, '') : '';
    if (!name) return { ok: false, name: name, reason: 'Type a name for the app.' };
    if (CONTROL.test(name)) return { ok: false, name: name, reason: 'Use letters, numbers and spaces.' };
    if (chars(name) > NAME_MAX) return { ok: false, name: name, reason: 'Use ' + NAME_MAX + ' characters or fewer.' };
    return { ok: true, name: name, reason: '' };
  }

  /* ---------- The store ---------- */
  function storage() { try { return window.localStorage || null; } catch (e) { return null; } }
  /* The raw saved object (kept so entries we do not touch are written back unchanged). */
  function readRaw() {
    var s = storage(), v;
    if (!s) return {};
    try { v = JSON.parse(s.getItem(KEY) || 'null'); } catch (e) { return {}; }
    return plainObject(v) ? v : {};
  }
  /* One entry as it applies now: { name?, mark? }; bad or unknown values left out. mark '' is the
     user's explicit "App's own icon" (kept even when a mark lists the app's package); no mark key is
     no choice (the package's mark, if any, else its own icon). Builds before 2026-11-02 never wrote
     mark '' and ignore it (no such mark), so they show the own icon for it too. */
  function cleanEntry(v) {
    var out = {}, n;
    if (!plainObject(v)) return out;
    if (typeof v.name === 'string') { n = validName(v.name); if (n.ok) out.name = n.name; }
    if (v.mark === '' || typeof v.mark === 'string' && markOf(v.mark)) out.mark = v.mark;
    return out;
  }
  var raw = readRaw(), listeners = [], revision = 0;
  function get(pkg) { return safePkg(pkg) && own(raw, pkg) ? cleanEntry(raw[pkg]) : {}; }
  /* Every package with an override that applies, { pkg: { name?, mark? } } (mark '' = App's own icon). */
  function all() {
    var out = {};
    Object.keys(raw).forEach(function (pkg) { var e = get(pkg); if (e.name || own(e, 'mark')) out[pkg] = e; });
    return out;
  }
  /* Replaces the whole store. { ok, reason }; nothing changes (in memory or on disk) unless the save
     succeeds. */
  function save(next, pkg, field) {
    var s = storage();
    try { if (!s) throw new Error('no storage'); s.setItem(KEY, JSON.stringify(next)); } catch (e) { return { ok: false, reason: SAVE_FAILED }; }
    raw = next; revision++;
    listeners.slice().forEach(function (fn) { try { fn(pkg, field); } catch (e) { /* one bad listener must not stop the rest */ } });
    return { ok: true, reason: '' };
  }
  /* Writes one field of one package (value null clears it). */
  function put(pkg, field, value) {
    var next = {}, entry = {};
    if (!safePkg(pkg)) return { ok: false, reason: 'Not an app.' };
    Object.keys(raw).forEach(function (k) { next[k] = raw[k]; });
    if (own(raw, pkg) && plainObject(raw[pkg])) Object.keys(raw[pkg]).forEach(function (k) { entry[k] = raw[pkg][k]; });
    if (value === null) delete entry[field];
    else entry[field] = value;
    if (Object.keys(entry).length) next[pkg] = entry;
    else delete next[pkg];
    return save(next, pkg, field);
  }
  /* The app has been uninstalled (main.js uninstallSettle): its whole entry goes. Nothing to drop
     writes nothing. */
  function forget(pkg) {
    var next = {};
    if (!safePkg(pkg)) return { ok: false, reason: 'Not an app.' };
    if (!own(raw, pkg)) return { ok: true, reason: '' };
    Object.keys(raw).forEach(function (k) { if (k !== pkg) next[k] = raw[k]; });
    return save(next, pkg, null);
  }
  /* name null = Original name. */
  function setName(pkg, name) {
    var v;
    if (name === null) return put(pkg, 'name', null);
    v = validName(name);
    if (!v.ok) return { ok: false, reason: v.reason };
    return put(pkg, 'name', v.name);
  }
  /* mark null = no choice (the package's mark, else its own icon); '' = App's own icon, explicitly. */
  function setMark(pkg, mark) {
    if (mark === null || mark === '') return put(pkg, 'mark', mark);
    if (!markOf(mark)) return { ok: false, reason: 'Not a mark CleanFrame has.' };
    return put(pkg, 'mark', mark);
  }
  function onChange(fn) { if (typeof fn === 'function') listeners.push(fn); }

  /* ---------- Records ---------- */
  /* fresh: the record's name/mark were just set to its own values (main.js refreshCatalog). */
  function dress(a, fresh) {
    var o;
    if (!a || typeof a !== 'object') return a;
    if (fresh || !a.own) a.own = { name: a.name, mark: !!a.mark };
    o = get(a.appId);
    a.name = o.name || a.own.name;
    /* The user's choice wins (a mark, or '' = App's own icon); else the package's mark, if any. */
    a.markId = own(o, 'mark') ? o.mark : autoMark(a.appId);
    a.mark = !!a.markId || a.own.mark;
    return a;
  }
  function ownName(a) { return a && a.own ? a.own.name : a ? a.name : ''; }
  /* Changes on every successful save: main.js folds it into its render caches' signatures. */
  function rev() { return revision; }
  /* The picker's choices: '' (App's own icon), then the library. */
  function choices() {
    return [{ id: '', name: 'App’s own icon' }].concat(library().map(function (m) { return { id: m.id, name: m.name }; }));
  }

  /* ---------- Keys (pure) ---------- */
  /* Rename: zones back (top row), field (the text box), buttons (b: 0 Save, 1 Cancel, 2 Original name). */
  var NAME_BUTTONS = [['save', 'Save'], ['cancel', 'Cancel'], ['original', 'Original name']];
  function nameKey(f, k) {
    var z = f.zone, b = Math.max(0, Math.min(NAME_BUTTONS.length - 1, f.b || 0));
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
    if (k === 'left') return { zone: z, b: Math.max(0, b - 1), act: null };
    if (k === 'right') return { zone: z, b: Math.min(NAME_BUTTONS.length - 1, b + 1), act: null };
    return { zone: z, b: b, act: k === 'ok' ? NAME_BUTTONS[b][0] : null };
  }
  /* Change icon: zones back and grid (i = choice, COLS per row). */
  function iconKey(f, k, n) {
    var i = Math.max(0, Math.min(n - 1, f.i || 0)), col = i % COLS;
    if (k === 'back') return { zone: f.zone, i: i, act: 'cancel' };
    if (f.zone === 'back') {
      if (k === 'down' || k === 'right') return { zone: 'grid', i: i, act: null };
      return { zone: 'back', i: i, act: k === 'ok' ? 'cancel' : null };
    }
    if (k === 'left') return col > 0 ? { zone: 'grid', i: i - 1, act: null } : { zone: 'back', i: i, act: null };
    if (k === 'right') return { zone: 'grid', i: col < COLS - 1 && i + 1 < n ? i + 1 : i, act: null };
    if (k === 'up') return i - COLS >= 0 ? { zone: 'grid', i: i - COLS, act: null } : { zone: 'back', i: i, act: null };
    if (k === 'down') return { zone: 'grid', i: i + COLS < n ? i + COLS : Math.floor(i / COLS) < Math.floor((n - 1) / COLS) ? n - 1 : i, act: null };
    return { zone: 'grid', i: i, act: k === 'ok' ? 'apply' : null };
  }
  /* First visible row of the picker so that row `row` shows. */
  function windowTop(top, row, rows, visible) {
    if (rows <= visible) return 0;
    if (row < top) top = row;
    if (row >= top + visible) top = row - visible + 1;
    return Math.max(0, Math.min(rows - visible, top));
  }

  /* ---------- The two screens, in main.js's settings panel (#appearance) ---------- */
  /* host: top, push, pop, after, esc, icon, setHTML, focusStage, app(id), where(target) -> the
     Back row's label, markHtml(id, w, h, size) and appMark(record, w, h, size) (main.js's own). */
  function views(host) {
    var INPUT = 'appNameInput';
    function $(id) { return document.getElementById(id); }
    function owns(t) { return !!t && (t.id === 'appname' || t.id === 'appicon'); }
    function focusClass(on) { return on ? ' is-focus' : ''; }
    function input() { return $(INPUT); }
    function typing() { var el = input(); return !!el && document.activeElement === el; }
    function leaveField() { var el = input(); if (el && document.activeElement === el) el.blur(); host.focusStage(); }
    /* Written once per screen, so the text box keeps its text and focus; later renders change classes. */
    function frame(el, key, html) {
      if (el._aeFrame === key) return false;
      el.innerHTML = html; el._aeFrame = key; el._html = null;
      return true;
    }
    function done(t) { if (host.top() === t) { leaveField(); host.pop(); } }
    function back(t) {
      host.setHTML($('appearanceBack'), '<button type="button" tabindex="-1" class="setting-choice setting-back' + focusClass(t.f.zone === 'back') + '" data-z="ae-back" data-i="0" data-action="' + t.id + '.back">'
        + host.icon('left') + '<span>' + host.esc(host.where(t.target)) + '</span></button>');
    }

    /* Rename */
    function openName(target) {
      var a = host.app(target.id);
      if (!a) return null;
      return host.push({ id: 'appname', target: target, appId: a.appId, key: 'an' + (++views.seq), value: a.name, error: '', f: { zone: 'field', b: 0 } });
    }
    function saveName(t, name) {
      var el = input(), r;
      t.value = el ? el.value : t.value;
      r = setName(t.appId, name === null ? null : t.value);
      if (!r.ok) { t.error = r.reason; if (name !== null && r.reason !== SAVE_FAILED) t.f.zone = 'field'; return; }
      done(t);
    }
    function nameKeyView(t, k) {
      var r = nameKey(t.f, k), el;
      t.f.zone = r.zone; t.f.b = r.b;
      if (r.act === 'cancel') done(t);
      else if (r.act === 'save') saveName(t);
      else if (r.act === 'original') saveName(t, null);
      else if (r.act === 'type') { el = input(); if (el) { t.error = ''; el.focus(); } }
    }
    function renderName(t) {
      var rows = $('appearanceRows'), a = host.app(t.target.id), el, html, hint;
      $('appearance').className = 'appearance detail-appname';
      rows.className = 'appearance-rows group-name app-name';
      $('appearanceTitle').textContent = 'Rename app';
      back(t);
      html = '<section class="setting-section"><h3 class="setting-label"><label for="' + INPUT + '">Name</label></h3>'
        + '<div class="setting-choice name-field" data-z="ae-field" data-i="0" data-action="appname.field">'
        + '<input id="' + INPUT + '" class="name-input" type="text" maxlength="' + (NAME_MAX * 2) + '" autocomplete="off" autocapitalize="sentences" spellcheck="false" enterkeyhint="done" tabindex="-1"></div>'
        + '<p class="setting-reason name-error" aria-live="polite"></p><p class="setting-reason app-name-own"></p></section>'
        + '<div class="name-buttons">' + NAME_BUTTONS.map(function (b, i) {
          return '<button type="button" tabindex="-1" class="setting-choice name-button" data-z="ae-button" data-i="' + i + '" data-action="appname.' + b[0] + '">' + b[1] + '</button>';
        }).join('') + '</div>';
      if (frame(rows, t.key, html)) {
        el = input(); el.value = t.value;
        el.addEventListener('input', function () { t.value = el.value; if (t.error) { t.error = ''; host.after(); } });
      }
      rows.querySelector('.name-field').className = 'setting-choice name-field' + focusClass(t.f.zone === 'field') + (typing() ? ' is-typing' : '');
      Array.prototype.forEach.call(rows.querySelectorAll('.name-button'), function (b, i) {
        b.className = 'setting-choice name-button' + focusClass(t.f.zone === 'buttons' && t.f.b === i);
      });
      rows.querySelector('.name-error').textContent = t.error;
      rows.querySelector('.name-error').className = 'setting-reason name-error' + (t.error ? ' setting-error' : '');
      rows.querySelector('.app-name-own').textContent = a ? 'Original name: ' + ownName(a) : '';
      host.setHTML($('appearanceStatus'), '');
      hint = typing() ? 'Type the name, then press OK on the keyboard' : t.f.zone === 'field' ? 'Type a name'
        : t.f.zone === 'back' || t.f.b === 1 ? 'Go back without saving' : t.f.b === 2 ? 'Show the app’s own name' : 'Save the new name';
      host.setHTML($('appearanceDesc'), '<span class="remote-key">OK</span><span>' + host.esc(hint) + '</span>');
    }

    /* Change icon */
    function openIcon(target) {
      var a = host.app(target.id), list = choices(), i;
      if (!a) return null;
      i = 0;
      list.forEach(function (c, n) { if (c.id === a.markId) i = n; });
      return host.push({ id: 'appicon', target: target, appId: a.appId, key: 'ai' + (++views.seq), list: list, top: 0, error: '', f: { zone: 'grid', i: i } });
    }
    function applyIcon(t) {
      var c = t.list[t.f.i], r;
      if (!c) return;
      r = setMark(t.appId, c.id || '');
      if (!r.ok) { t.error = r.reason; return; }
      done(t);
    }
    function iconKeyView(t, k) {
      var r = iconKey(t.f, k, t.list.length);
      t.f.zone = r.zone; t.f.i = r.i; t.error = r.act ? t.error : '';
      if (r.act === 'cancel') done(t);
      else if (r.act === 'apply') applyIcon(t);
    }
    /* What the tile would show for choice c: the app's own icon ('' = its own mark, monogram or
       Android icon) or that mark, at the dock tile's size. */
    function choiceMark(a, c) {
      if (c.id) return host.markHtml(c.id, 120, 72, 58);
      return host.appMark({ id: a.id, name: ownName(a), mark: a.own ? a.own.mark : a.mark, brand: a.brand, icon: a.icon, markId: '' }, 120, 72, 58);
    }
    function renderIcon(t) {
      var rows = $('appearanceRows'), a = host.app(t.target.id), n = t.list.length, nrows = Math.ceil(n / COLS), html, grid, applied;
      $('appearance').className = 'appearance detail-appicon';
      rows.className = 'appearance-rows app-icons';
      $('appearanceTitle').textContent = 'Change icon';
      back(t);
      applied = a ? a.markId : '';
      html = '<div class="app-icon-view"><div class="app-icon-grid">' + t.list.map(function (c, i) {
        return '<button type="button" tabindex="-1" class="setting-choice app-icon-choice" data-z="ae-icon" data-i="' + i + '" data-action="appicon.' + (c.id || 'own') + '" aria-label="' + host.esc(c.name) + '"'
          + ' style="left:' + (i % COLS) * 162 + 'px;top:' + Math.floor(i / COLS) * 158 + 'px">'
          + '<span class="app-icon-mark">' + (a ? choiceMark(a, c) : '') + '</span><span class="app-icon-name">' + host.esc(c.name) + '</span>'
          + '<span class="setting-tick" aria-hidden="true">' + host.icon('check') + '</span></button>';
      }).join('') + '</div></div><p class="setting-reason app-icon-error" aria-live="polite"></p>';
      if (frame(rows, t.key, html)) {
        Array.prototype.forEach.call(rows.querySelectorAll('img[data-native-icon]'), function (img) {
          function ready() { img.parentNode.classList.add('has-native-icon'); }
          function failed() { img.style.display = 'none'; img.parentNode.classList.remove('has-native-icon'); }
          img.onload = ready; img.onerror = failed;
          if (img.complete) { if (img.naturalWidth) ready(); else failed(); }
        });
      }
      t.top = windowTop(t.top, Math.floor(t.f.i / COLS), nrows, ROWS);
      grid = rows.querySelector('.app-icon-grid');
      grid.style.transform = 'translate3d(0,' + (-t.top * 158) + 'px,0)';
      Array.prototype.forEach.call(rows.querySelectorAll('.app-icon-choice'), function (b, i) {
        var row = Math.floor(i / COLS), shown = row >= t.top && row < t.top + ROWS;
        b.className = 'setting-choice app-icon-choice' + focusClass(t.f.zone === 'grid' && t.f.i === i) + (t.list[i].id === applied ? ' is-applied' : '') + (shown ? '' : ' is-off');
      });
      rows.querySelector('.app-icon-view').className = 'app-icon-view' + (t.top > 0 ? ' more-before' : '') + (t.top + ROWS < nrows ? ' more-after' : '');
      rows.querySelector('.app-icon-error').textContent = t.error;
      rows.querySelector('.app-icon-error').className = 'setting-reason app-icon-error' + (t.error ? ' setting-error' : '');
      host.setHTML($('appearanceStatus'), '');
      host.setHTML($('appearanceDesc'), '<span class="remote-key">OK</span><span>' + host.esc(t.f.zone === 'back' ? 'Go back without changing it'
        : t.list[t.f.i] && t.list[t.f.i].id === applied ? 'Keep this icon' : 'Use this icon') + '</span>');
    }

    var V = {
      appname: {
        norm: function (t) { if (['back', 'field', 'buttons'].indexOf(t.f.zone) < 0) t.f.zone = 'field'; },
        key: nameKeyView,
        point: function (t, z, i) {
          if (z === 'ae-back') done(t);
          else if (z === 'ae-field') { t.f.zone = 'field'; nameKeyView(t, 'ok'); }
          else if (z === 'ae-button') { t.f.zone = 'buttons'; t.f.b = i; nameKeyView(t, 'ok'); }
        }
      },
      appicon: {
        norm: function (t) {
          if (t.f.zone !== 'back') t.f.zone = 'grid';
          t.f.i = Math.max(0, Math.min(t.list.length - 1, t.f.i || 0));
        },
        key: iconKeyView,
        point: function (t, z, i) {
          if (z === 'ae-back') done(t);
          else if (z === 'ae-icon') { t.f.zone = 'grid'; t.f.i = i; iconKeyView(t, 'ok'); }
        }
      }
    };
    /* While the text box has the keyboard (as on Rename group): OK/Enter and Down go to Save, Up to
       the top row, Back leaves the box; every other key types. true = main.js must not handle it. */
    function inputKey(ev) {
      var t = host.top(), code = ev.keyCode;
      if (!t || t.id !== 'appname' || !typing()) return false;
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
      if (t.id === 'appname') { renderName(t); return true; }
      if (t.id === 'appicon') { renderIcon(t); return true; }
      var rows = $('appearanceRows');
      if (rows) rows._aeFrame = null;
      return false;
    }
    return { V: V, owns: owns, render: render, inputKey: inputKey, openName: openName, openIcon: openIcon, typing: typing };
  }
  views.seq = 0;

  window.CleanFrameAppEdit = { KEY: KEY, NAME_MAX: NAME_MAX, COLS: COLS, ROWS: ROWS, SAVE_FAILED: SAVE_FAILED,
    LABEL_LINE: LABEL_LINE, LABEL_LINES: LABEL_LINES, LABEL_PX: LABEL_PX,
    library: library, markOf: markOf, autoMark: autoMark, STARTER: STARTER, starter: starter, validName: validName, get: get, all: all, setName: setName, setMark: setMark, forget: forget,
    onChange: onChange, dress: dress, ownName: ownName, rev: rev, choices: choices, nameKey: nameKey, iconKey: iconKey,
    windowTop: windowTop, views: views };
}());

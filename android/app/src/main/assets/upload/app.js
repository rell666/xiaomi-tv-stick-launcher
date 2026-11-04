/* CleanFrame photo upload: the phone page (docs/PHOTO-GROUPS.md, "On the phone"), served by the TV
   app itself (UploadServer). Plain ES5 + XMLHttpRequest (upload progress), no libraries, nothing
   from outside this server. The token is the last part of the address (/u/<token>); every call
   goes to /api/<token>/... A sent photo shows "Preparing" until the TV has drawn its materials and
   it is listed in the group (state polled every 2 s by one timer, however many are waiting), then
   "On the TV".
   Connections: the server takes 4 at once (UploadServer.MAX_CONNECTIONS, one per request) and answers
   any more 503, so this page holds at most 1 state request, 1 upload and THUMBS_AT_ONCE thumbs, and an
   unchanged list touches no image. A 503 (nothing was done) is retried with backoff on every call, a
   reset only where repeating it cannot do anything twice: a GET, or an upload not yet fully sent.
   Framing (2026-11-02): each chosen photo is shown in a 16:9 frame first (drag to move, pinch or
   the slider to zoom; crop.js does the arithmetic, the same as the TV's). Send uploads an upright copy
   of at most crop.js's SRC_MAX_SIDE / SRC_MAX_PIXELS (the TV keeps it for Adjust) with the crop as
   "x,y,z"; a photo this phone cannot show is sent as it is, without a crop (the TV centres it). */
(function () {
  'use strict';
  var token = (location.pathname.match(/^\/u\/([0-9a-f]{32})$/) || [])[1];
  var api = '/api/' + token + '/';
  var EXPIRED = 'This code has expired. Open Add photo on the TV again.';
  var MAX_BYTES = 40 * 1024 * 1024;
  var POLL_MS = 2000, PREPARE_MS = 120000;
  var THUMBS_AT_ONCE = 2, THUMB_TRIES = 4, THUMB_TIMEOUT_MS = 15000, UPLOAD_TRIES = 6, CALL_TRIES = 4;
  var state = null, current = null, dead = false, queue = [], busy = false, nameMode = null;
  var loading = false, loadAgain = false, loadDone = [], watching = [], pollTimer = null;
  var rows = {}, listSig = null, thumbWait = [], thumbsBusy = 0;
  var PC = window.CleanFramePhotoCrop, JPEG_QUALITY = 0.92, framing = [], ed = null;

  function $(id) { return document.getElementById(id); }
  function show(el, on) { if (on) el.removeAttribute('hidden'); else el.setAttribute('hidden', ''); }
  function text(el, s) { el.textContent = s; }

  /* Milliseconds before attempt n + 1: 0.5, 1, 2, 4, 8, 8... s. */
  function backoff(n) { return Math.min(8000, 500 * Math.pow(2, n - 1)); }

  /* opts: tries (default CALL_TRIES), resend (an upload: a reset before it is fully sent is retried),
     progress, sent, retrying (told before each wait). */
  function request(method, path, body, opts, done) {
    var tries = opts.tries || CALL_TRIES, attempt = 0;
    function go() {
      var x = new XMLHttpRequest(), sent = false;
      attempt++;
      function again() {
        if (dead || attempt >= tries) return false;
        if (opts.retrying) opts.retrying();
        setTimeout(go, backoff(attempt));
        return true;
      }
      x.open(method, api + path, true);
      if (body && !(body instanceof FormData)) x.setRequestHeader('Content-Type', 'application/json');
      if (opts.progress && x.upload) x.upload.onprogress = function (e) { if (e.lengthComputable) opts.progress(e.loaded / e.total); };
      if (x.upload) x.upload.onload = function () { sent = true; if (opts.sent) opts.sent(); };
      x.onload = function () {
        var d = null;
        try { d = JSON.parse(x.responseText); } catch (e) { d = null; }
        if (x.status === 403 && d && d.error === 'expired') { expire(); return done(EXPIRED); }
        if (x.status >= 200 && x.status < 300 && d && d.ok) return done(null, d);
        if (x.status === 503 && again()) return;
        done((d && d.message) || ('Something went wrong (' + x.status + '). Try again.'));
      };
      x.onerror = function () {
        // Fully sent, the TV may have taken it and only the answer was lost: sending again would add it twice.
        if ((method === 'GET' || (opts.resend && !sent)) && again()) return;
        done('Could not reach the TV. Is the phone on the home Wi-Fi, and Add photo still open?');
      };
      x.ontimeout = x.onerror;
      x.send(body ? (body instanceof FormData ? body : JSON.stringify(body)) : null);
    }
    go();
  }

  function expire() {
    dead = true;
    text($('fatal'), EXPIRED);
    show($('fatal'), true);
    $('choose').disabled = true;
    $('choose-label').className += ' disabled';
    framing = [];
    show($('editor'), false);
    var b = document.querySelectorAll('button');
    for (var i = 0; i < b.length; i++) b[i].disabled = true;
  }

  function group(id) {
    for (var i = 0; state && i < state.groups.length; i++) if (state.groups[i].id === id) return state.groups[i];
    return null;
  }

  function wantedGroup() {
    var h = (location.hash.match(/^#g=([a-z0-9]+)$/) || [])[1];
    return h && group(h) ? h : state.group;
  }

  /* One state request at a time: asked for during one, a new one follows it (it may be older than the
     change the caller just made), and the caller's done waits for that one. */
  function load(done) {
    if (done) loadDone.push(done);
    if (loading) { loadAgain = true; return; }
    loading = true;
    var waiting = loadDone;
    loadDone = [];
    request('GET', 'state', null, {}, function (err, d) {
      var i;
      loading = false;
      if (err) {
        if (!dead) { text($('fatal'), err); show($('fatal'), true); }
      } else {
        show($('fatal'), false);
        state = d;
        current = wantedGroup();
        render();
      }
      settleWatches();
      if (dead) { waiting = waiting.concat(loadDone); loadDone = []; loadAgain = false; }
      if (loadAgain) { loadAgain = false; load(); }
      for (i = 0; i < waiting.length; i++) waiting[i](err || null);
    });
  }

  /* The list is changed only where it changed: a kept row keeps its image, so nothing is fetched again. */
  function render() {
    var g = group(current) || { name: '?', count: 0, builtin: true };
    var want = [], keys = [], sig, list, have = {}, goneRows = [], i, k;
    text($('group-name'), g.name);
    text($('group-count'), g.count === 1 ? '1 photo' : g.count + ' photos');
    document.title = g.name + ' · CleanFrame photos';
    $('rename-btn').disabled = dead || g.builtin;
    show($('rename-btn'), !g.builtin);
    for (i = 0; i < state.photos.length; i++) {
      if (state.photos[i].group !== current) continue;
      want.push(state.photos[i]);
      keys.push(state.photos[i].group + '/' + state.photos[i].id + '/' + state.photos[i].name);
    }
    show($('empty'), want.length === 0);
    sig = keys.join('\n');
    if (sig === listSig) return;
    listSig = sig;
    list = $('photos');
    for (i = 0; i < want.length; i++) {
      have[keys[i]] = rows[keys[i]] || photoItem(want[i]);
      list.appendChild(have[keys[i]].li); // moving a row does not fetch its image again
    }
    for (k in rows) {
      if (Object.prototype.hasOwnProperty.call(rows, k) && !Object.prototype.hasOwnProperty.call(have, k)) {
        rows[k].gone = true;
        list.removeChild(rows[k].li);
        goneRows.push(rows[k]);
      }
    }
    rows = have;
    // Only now: each stop frees a slot and pumps, and every row that went must already say so.
    for (i = 0; i < goneRows.length; i++) if (goneRows[i].stopThumb) goneRows[i].stopThumb();
    pumpThumbs();
  }

  // ---- thumbs: a queue, at most THUMBS_AT_ONCE fetched at a time; one that keeps failing is a placeholder.
  // An attempt holds its slot until it loads, fails, outlasts THUMB_TIMEOUT_MS or its row goes, once only:
  // two stalled downloads must not block every later thumb, in this group or the next.
  function pumpThumbs() {
    while (thumbsBusy < THUMBS_AT_ONCE && thumbWait.length) {
      var t = thumbWait.shift();
      if (!t.row.gone) fetchThumb(t);
    }
  }

  function fetchThumb(t) {
    var ended = false, timer;
    function end(failed) {
      if (ended) return;
      ended = true;
      clearTimeout(timer);
      t.img.onload = t.img.onerror = null;
      t.row.stopThumb = null;
      thumbsBusy--;
      if (failed) {
        if (t.tries >= THUMB_TRIES || dead) noThumb(t);
        else setTimeout(function () { thumbWait.push(t); pumpThumbs(); }, backoff(t.tries));
      }
      pumpThumbs();
    }
    thumbsBusy++;
    t.tries++;
    t.img.onload = function () { end(false); };
    t.img.onerror = function () { end(!t.row.gone); };
    t.row.stopThumb = function () { t.img.removeAttribute('src'); end(false); }; // removing src stops the download
    timer = setTimeout(function () { t.img.removeAttribute('src'); end(true); }, THUMB_TIMEOUT_MS);
    t.img.src = t.url;
  }

  function noThumb(t) {
    var ph = document.createElement('span');
    ph.className = 'nothumb';
    t.row.li.replaceChild(ph, t.img);
  }

  function photoItem(p) {
    var li = document.createElement('li'), row = { li: li, gone: false };
    if (p.thumb) {
      var img = document.createElement('img');
      img.alt = ''; img.width = 128; img.height = 85;
      li.appendChild(img);
      thumbWait.push({ row: row, img: img, url: p.thumb, tries: 0 });
    } else {
      var ph = document.createElement('span'); ph.className = 'nothumb'; li.appendChild(ph);
    }
    var name = document.createElement('span'); name.className = 'name'; text(name, p.name);
    li.appendChild(name);
    var rm = document.createElement('button');
    rm.type = 'button'; rm.className = 'remove'; text(rm, 'Remove'); rm.disabled = dead;
    rm.setAttribute('aria-label', 'Remove ' + p.name);
    rm.onclick = function () {
      if (!window.confirm('Remove "' + p.name + '" from the TV?')) return;
      rm.disabled = true; text(rm, 'Removing…');
      request('DELETE', 'photos/' + p.group + '/' + p.id, null, {}, function (err) {
        if (err) { rm.disabled = false; text(rm, 'Remove'); window.alert(err); return; }
        load();
      });
    };
    li.appendChild(rm);
    return row;
  }

  // ---- uploads: framed first (one at a time), then sent one at a time, each with its own line
  function addFiles(files) {
    for (var i = 0; i < files.length; i++) {
      var f = files[i], li = document.createElement('li');
      var name = document.createElement('span'); name.className = 'name'; text(name, f.name || 'Photo');
      var st = document.createElement('span'); st.className = 'status';
      var bar = document.createElement('span'); bar.className = 'bar';
      var fill = document.createElement('span'); fill.className = 'fill'; bar.appendChild(fill);
      li.appendChild(name); li.appendChild(st); li.appendChild(bar);
      $('queue').appendChild(li);
      var item = { file: f, group: current, li: li, st: st, fill: fill, body: f, crop: null };
      if (f.size > MAX_BYTES) { fail(item, 'Too big (over 40 MB).'); continue; }
      setStatus(item, 'waiting', 'Waiting to be framed…', 0);
      framing.push(item);
    }
    if (!ed) frameNext();
  }

  // ---- framing: the photo in a 16:9 frame, moved and zoomed with crop.js
  function frameNext() {
    var item = framing.shift(), img = $('ed-img'), url;
    if (ed && ed.url) URL.revokeObjectURL(ed.url);
    ed = null;
    show($('ed-rest'), framing.length > 0);
    if (!item || dead) { show($('editor'), false); return; }
    url = URL.createObjectURL(item.file);
    ed = { item: item, url: url, w: 0, h: 0, c: null, pointers: {}, last: null };
    text($('ed-title'), 'Frame ' + (item.file.name || 'the photo'));
    text($('ed-note'), 'Loading the photo…');
    setStatus(item, 'waiting', 'Framing…', 0);
    show($('editor'), true);
    img.onload = function () {
      if (!ed || ed.url !== url) return;
      ed.w = img.naturalWidth; ed.h = img.naturalHeight; ed.c = PC.clamp(ed.w, ed.h, null);
      drawFrame();
    };
    img.onerror = function () {
      if (!ed || ed.url !== url) return;
      text($('ed-note'), 'This phone cannot show this photo, so it cannot be framed here. Send it and the TV will centre it.');
      ed.w = 0;
    };
    img.src = url;
    $('editor').scrollIntoView({ block: 'start', behavior: 'smooth' });
  }

  function sliderOf(c) {
    var lo = PC.fitZoom(ed.w, ed.h);
    return Math.round(1000 * Math.log(c.z / lo) / Math.log(PC.Z_MAX / lo));
  }
  function zoomOf(v) {
    var lo = PC.fitZoom(ed.w, ed.h);
    return lo * Math.pow(PC.Z_MAX / lo, v / 1000);
  }

  function drawFrame() {
    var box = $('ed-frame'), img = $('ed-img'), bw = box.clientWidth, f, k, send;
    if (!ed || !ed.w || !bw) return;
    f = PC.frame(ed.w, ed.h, ed.c); k = bw / f.w;
    img.style.width = ed.w * k + 'px'; img.style.height = ed.h * k + 'px';
    img.style.left = -f.x * k + 'px'; img.style.top = -f.y * k + 'px';
    $('ed-zoom').value = String(sliderOf(ed.c));
    send = PC.sendSize(ed.w, ed.h);
    text($('ed-note'), PC.soft(send.w, send.h, ed.c) ? 'Zoomed in past the photo’s detail: it will look softer on the TV.'
      : ed.c.z < 1 ? 'The TV shows black where the frame is empty.' : '');
  }

  function pointerList() {
    var out = [], k;
    for (k in ed.pointers) if (Object.prototype.hasOwnProperty.call(ed.pointers, k)) out.push(ed.pointers[k]);
    return out;
  }
  function gesture() {
    var p = pointerList(), mid, dist;
    if (!p.length) return null;
    mid = p.length > 1 ? { x: (p[0].x + p[1].x) / 2, y: (p[0].y + p[1].y) / 2 } : { x: p[0].x, y: p[0].y };
    dist = p.length > 1 ? Math.max(1, Math.sqrt(Math.pow(p[0].x - p[1].x, 2) + Math.pow(p[0].y - p[1].y, 2))) : 0;
    return { mid: mid, dist: dist, n: p.length };
  }
  function pointerMoved() {
    var g = gesture(), box = $('ed-frame'), bw = box.clientWidth, bh = box.clientHeight;
    if (g && ed.last && g.n === ed.last.n) {
      if (g.dist && ed.last.dist) ed.c = PC.zoomBy(ed.w, ed.h, ed.c, g.dist / ed.last.dist);
      ed.c = PC.move(ed.w, ed.h, ed.c, -(g.mid.x - ed.last.mid.x) / bw, -(g.mid.y - ed.last.mid.y) / bh);
      drawFrame();
    }
    ed.last = g;
  }
  function onPointer(e) {
    if (!ed || !ed.w) return;
    e.preventDefault();
    if (e.type === 'pointerdown') { try { $('ed-frame').setPointerCapture(e.pointerId); } catch (x) { /* fine without */ } }
    if (e.type === 'pointerup' || e.type === 'pointercancel') delete ed.pointers[e.pointerId];
    else if (e.type === 'pointerdown' || ed.pointers[e.pointerId]) ed.pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    if (e.type === 'pointermove') pointerMoved();
    else ed.last = gesture();
  }
  ['pointerdown', 'pointermove', 'pointerup', 'pointercancel'].forEach(function (t) { $('ed-frame').addEventListener(t, onPointer); });
  $('ed-frame').addEventListener('wheel', function (e) {
    if (!ed || !ed.w) return;
    e.preventDefault();
    ed.c = PC.zoomBy(ed.w, ed.h, ed.c, Math.exp(-e.deltaY / 400));
    drawFrame();
  }, { passive: false });
  $('ed-zoom').oninput = function () {
    if (!ed || !ed.w) return;
    ed.c = PC.clamp(ed.w, ed.h, { x: ed.c.x, y: ed.c.y, z: zoomOf(Number(this.value)) });
    drawFrame();
  };
  $('ed-fit').onclick = function () { if (ed && ed.w) { ed.c = PC.clamp(ed.w, ed.h, { x: 0.5, y: 0.5, z: 0 }); drawFrame(); } };
  $('ed-fill').onclick = function () { if (ed && ed.w) { ed.c = PC.clamp(ed.w, ed.h, null); drawFrame(); } };
  window.addEventListener('resize', function () { if (ed) drawFrame(); });

  /* The upright copy the TV keeps (at most crop.js's limits) as a JPEG, or null (the original goes). */
  function uprightCopy(done) {
    var s = PC.sendSize(ed.w, ed.h), cv = document.createElement('canvas'), ctx;
    try {
      cv.width = s.w; cv.height = s.h; ctx = cv.getContext('2d');
      ctx.drawImage($('ed-img'), 0, 0, s.w, s.h);
      cv.toBlob(function (b) { done(b && b.size ? b : null); }, 'image/jpeg', JPEG_QUALITY);
    } catch (e) { done(null); }
  }

  function enqueue(item) {
    setStatus(item, 'waiting', 'Waiting…', 0);
    queue.push(item);
    next();
  }
  $('ed-send').onclick = function () {
    if (!ed) return;
    var cur = ed, item = cur.item;
    $('ed-send').disabled = true;
    if (!cur.w) { enqueue(item); $('ed-send').disabled = false; frameNext(); return; }
    item.crop = PC.field(PC.clamp(cur.w, cur.h, cur.c));
    uprightCopy(function (blob) {
      $('ed-send').disabled = false;
      if (blob) item.body = blob;
      enqueue(item);
      if (ed === cur) frameNext();
    });
  };
  $('ed-skip').onclick = function () {
    if (!ed) return;
    setStatus(ed.item, 'failed', 'Skipped', null);
    frameNext();
  };
  $('ed-rest').onclick = function () {
    var rest = framing; framing = [];
    rest.forEach(enqueue);
    show($('ed-rest'), false);
  };

  function setStatus(item, cls, msg, frac) {
    item.li.className = cls;
    text(item.st, msg);
    if (frac !== null) item.fill.style.width = Math.round(frac * 100) + '%';
  }
  function fail(item, msg) { setStatus(item, 'failed', msg, null); }

  function next() {
    if (busy || dead) return;
    var item = queue.shift();
    if (!item) return;
    busy = true;
    var fd = new FormData();
    fd.append('group', item.group);
    if (item.crop) fd.append('crop', item.crop);
    fd.append('photo', item.body, item.file.name || 'photo.jpg');
    setStatus(item, 'uploading', 'Uploading 0%', 0);
    request('POST', 'photos', fd, {
      tries: UPLOAD_TRIES,
      resend: true,
      progress: function (f) { setStatus(item, 'uploading', 'Uploading ' + Math.round(f * 100) + '%', f); },
      sent: function () { setStatus(item, 'preparing', 'Preparing…', 1); },
      retrying: function () { setStatus(item, 'waiting', 'The TV is busy. Trying again…', 0); }
    }, function (err, d) {
      busy = false;
      if (err) fail(item, err);
      else watching.push({ item: item, p: d.photo, since: new Date().getTime() });
      if (!err || !dead) load(function () { next(); });
      else next();
    });
  }

  // ---- a sent photo is "Preparing" until the TV lists it (its materials are drawn on the TV)
  function listed(p) {
    for (var i = 0; state && i < state.photos.length; i++) {
      if (state.photos[i].group === p.group && state.photos[i].id === p.id) return true;
    }
    return false;
  }

  /* After every state request: each sent photo that is listed is done; while any is not, one timer asks again. */
  function settleWatches() {
    var still = [], now = new Date().getTime();
    watching.forEach(function (w) {
      if (listed(w.p)) { setStatus(w.item, 'done', 'On the TV', 1); return; }
      setStatus(w.item, 'preparing', 'Preparing on the TV…', 1);
      if (dead) return;
      if (now - w.since > PREPARE_MS) {
        setStatus(w.item, 'preparing', 'Sent. The TV is still preparing it; keep Add photo open.', 1);
        return;
      }
      still.push(w);
    });
    watching = still;
    if (watching.length && pollTimer === null) {
      pollTimer = setTimeout(function () { pollTimer = null; load(); }, POLL_MS);
    }
  }

  // ---- rename / new group
  function openName(mode) {
    nameMode = mode;
    var g = group(current);
    text($('name-label'), mode === 'rename' ? 'New name for this group' : 'Name of the new group');
    $('name-input').value = mode === 'rename' && g ? g.name : '';
    show($('name-error'), false);
    show($('name-form'), true);
    $('name-input').focus();
  }

  $('name-form').onsubmit = function (e) {
    e.preventDefault();
    var name = $('name-input').value.replace(/^\s+|\s+$/g, '');
    if (!name) { text($('name-error'), 'Type a name.'); show($('name-error'), true); return; }
    $('name-save').disabled = true;
    var done = function (err, d) {
      $('name-save').disabled = false;
      if (err) { text($('name-error'), err); show($('name-error'), true); return; }
      show($('name-form'), false);
      if (nameMode === 'new') location.hash = 'g=' + d.group.id;
      load();
    };
    if (nameMode === 'rename') request('PATCH', 'groups/' + current, { name: name }, {}, done);
    else request('POST', 'groups', { name: name }, {}, done);
  };
  $('name-cancel').onclick = function () { show($('name-form'), false); };
  $('rename-btn').onclick = function () { openName('rename'); };
  $('new-btn').onclick = function () { openName('new'); };
  $('choose').onchange = function () { addFiles(this.files); this.value = ''; };
  window.onhashchange = function () { if (state) { current = wantedGroup(); render(); } };

  if (!token) { expire(); return; }
  load();
})();

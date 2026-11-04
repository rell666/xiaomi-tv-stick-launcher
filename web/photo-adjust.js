/* Adjust (ours; ES5; 2026-11-02: "chose position and zoom in and out to get the right area"):
   frame an uploaded photo again on the TV. The group page's Adjust mode opens it on a photo that has
   its upright copy (src.jpg, kept since 2026-11-02); the screen shows the photo full screen exactly as
   the wall will be (web/photo-crop.js does the arithmetic, the same as the stick's PhotoMath), with a
   row of buttons: Move, Zoom, Whole photo, Fill screen, Save, Cancel. In Move the arrows move the
   view; in Zoom Up/Right zoom in and Down/Left zoom out; OK or Back goes to the buttons; Back there
   cancels. Save hands the crop to FrameDevice.recrop() and follows recropState(): the stick re-makes
   the photo under the same id, the materials queue draws it, and the library is read again.
   window.CleanFramePhotoAdjust:
     BUTTONS, NO_SRC, MOVE_STEP, ZOOM_STEP
     key(s, k)        pure: one remote key on the screen's state s = { zone: 'image'|'bar', mode:
                      'move'|'zoom', b, c, w, h } (w = 0 until the photo has loaded): { s, act:
                      'save'|'cancel'|null }
     hint(s)          the line at the top for that state
     note(s)          the line under it: softer past the photo's detail, black where the frame is empty
     views(host)      { V: { adjust }, open(wall), render(t), owns(t) }; host: top, push, pop, after,
                      refreshLibrary, message, device (FrameDevice) */
(function () {
  'use strict';
  var PC = window.CleanFramePhotoCrop;
  var MOVE_STEP = 0.05, ZOOM_STEP = 1.1, POLL_MS = 500, POLL_MAX = 480;
  var BUTTONS = [['move', 'Move'], ['zoom', 'Zoom'], ['fit', 'Whole photo'], ['fill', 'Fill screen'], ['save', 'Save'], ['cancel', 'Cancel']];
  var NO_SRC = 'This photo was added before Adjust. Send it again from the phone to frame it.';

  function copy(s) { return { zone: s.zone, mode: s.mode, b: s.b, c: s.c, w: s.w, h: s.h }; }
  function key(s, k) {
    var n = copy(s), act = null, id;
    if (!n.w) return { s: n, act: k === 'back' ? 'cancel' : null };
    if (n.zone === 'image') {
      if (k === 'ok' || k === 'back') { n.zone = 'bar'; n.b = n.mode === 'zoom' ? 1 : 0; }
      else if (n.mode === 'zoom') {
        if (k === 'up' || k === 'right') n.c = PC.zoomBy(n.w, n.h, n.c, ZOOM_STEP);
        else if (k === 'down' || k === 'left') n.c = PC.zoomBy(n.w, n.h, n.c, 1 / ZOOM_STEP);
      } else if (k === 'left' || k === 'right' || k === 'up' || k === 'down') {
        n.c = PC.move(n.w, n.h, n.c, k === 'left' ? -MOVE_STEP : k === 'right' ? MOVE_STEP : 0, k === 'up' ? -MOVE_STEP : k === 'down' ? MOVE_STEP : 0);
      }
      return { s: n, act: null };
    }
    if (k === 'left') n.b = Math.max(0, n.b - 1);
    else if (k === 'right') n.b = Math.min(BUTTONS.length - 1, n.b + 1);
    else if (k === 'up') n.zone = 'image';
    else if (k === 'back') act = 'cancel';
    else if (k === 'ok') {
      id = BUTTONS[n.b][0];
      if (id === 'move' || id === 'zoom') { n.mode = id; n.zone = 'image'; }
      else if (id === 'fit') n.c = PC.clamp(n.w, n.h, { x: 0.5, y: 0.5, z: 0 });
      else if (id === 'fill') n.c = PC.clamp(n.w, n.h, null);
      else act = id;
    }
    return { s: n, act: act };
  }
  function hint(s) {
    if (!s.w) return 'Loading the photo…';
    if (s.zone === 'bar') return 'Choose Move or Zoom, then use the arrows. Back cancels.';
    return s.mode === 'zoom' ? 'Up zooms in, Down zooms out. OK for the buttons.' : 'The arrows move the view. OK for the buttons.';
  }
  function note(s) {
    if (!s.w) return '';
    if (PC.soft(s.w, s.h, s.c)) return 'Zoomed in past the photo’s detail: it will look softer.';
    return PC.clamp(s.w, s.h, s.c).z < 1 ? 'Black fills the rest of the screen.' : '';
  }

  function views(host) {
    var el = null, img = null, head = null, sub = null, bar = null, seq = 0;
    function owns(t) { return !!t && t.id === 'adjust'; }
    function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
    function build() {
      var stage = document.getElementById('stage');
      if (el || !stage) return !!el;
      el = document.createElement('div'); el.className = 'photo-adjust'; el.id = 'photoAdjust'; el.hidden = true;
      el.setAttribute('role', 'dialog'); el.setAttribute('aria-label', 'Adjust photo');
      el.innerHTML = '<img class="pa-img" alt=""><div class="pa-head"><p class="pa-hint"></p><p class="pa-note"></p></div><div class="pa-bar"></div>';
      stage.appendChild(el);
      img = el.querySelector('.pa-img'); head = el.querySelector('.pa-hint'); sub = el.querySelector('.pa-note'); bar = el.querySelector('.pa-bar');
      bar.innerHTML = BUTTONS.map(function (b, i) {
        return '<button type="button" tabindex="-1" class="pa-btn" data-i="' + i + '">' + esc(b[1]) + '</button>';
      }).join('');
      return true;
    }
    function open(w) {
      var t = host.push({ id: 'adjust', wall: w, gid: w.group, pid: w.pid, busy: false, error: '', seq: ++seq,
        s: { zone: 'image', mode: 'move', b: 0, w: 0, h: 0, c: w.crop || null } });
      var probe = new Image();
      probe.onload = function () {
        if (t.seq !== seq) return;
        t.s.w = probe.naturalWidth; t.s.h = probe.naturalHeight; t.s.c = PC.clamp(t.s.w, t.s.h, t.s.c);
        host.after();
      };
      probe.onerror = function () { if (t.seq === seq) { t.error = 'Could not open this photo.'; host.after(); } };
      probe.src = w.src;
      return t;
    }
    function fail(t, text) {
      if (host.top() === t) { t.busy = false; t.error = text; host.after(); }
      else host.message('Adjust', text);
    }
    function follow(t, n) {
      host.device.recropState(t.gid, t.pid, function (err, st) {
        var state = err ? 'failed' : st.state;
        if (state === 'failed') { fail(t, (st && st.reason) || (err && (err.detail || err.message)) || 'Could not save the new framing.'); return; }
        if (state !== 'working' && host.top() === t) { host.pop(); host.after(); }
        if (state === 'done') { host.refreshLibrary(); return; }
        if (n >= POLL_MAX) { fail(t, 'The TV is still saving the new framing.'); return; }
        setTimeout(function () { follow(t, n + 1); }, POLL_MS);
      });
    }
    function save(t) {
      t.busy = true; t.error = '';
      host.device.recrop(t.gid, t.pid, PC.field(PC.clamp(t.s.w, t.s.h, t.s.c)), function (err) {
        if (err) { fail(t, err.detail || err.message); return; }
        follow(t, 0);
      });
    }
    var V = {
      adjust: {
        key: function (t, k) {
          var r;
          if (t.busy) return;
          if (t.error && !t.s.w) { if (k === 'back' || k === 'ok') host.pop(); return; }
          r = key(t.s, k); t.s = r.s; t.error = '';
          if (r.act === 'cancel') host.pop();
          else if (r.act === 'save') save(t);
        }
      }
    };
    function render(t) {
      var s, f, k, btns, i;
      if (!owns(t)) { if (el) el.hidden = true; return; }
      if (!build()) return;
      s = t.s; el.hidden = false;
      el.className = 'photo-adjust at-' + s.zone + ' mode-' + s.mode + (t.busy ? ' is-busy' : '');
      if (s.w) {
        f = PC.frame(s.w, s.h, s.c); k = PC.WALL_W / f.w;
        if (img.getAttribute('src') !== t.wall.src) img.setAttribute('src', t.wall.src);
        img.style.width = s.w * k + 'px'; img.style.height = s.h * k + 'px';
        img.style.left = -f.x * k + 'px'; img.style.top = -f.y * k + 'px';
        img.hidden = false;
      } else img.hidden = true;
      head.textContent = t.busy ? 'Saving…' : hint(s);
      sub.textContent = t.error || note(s);
      sub.className = 'pa-note' + (t.error ? ' pa-error' : '');
      btns = bar.children;
      for (i = 0; i < btns.length; i++) {
        btns[i].className = 'pa-btn' + (s.zone === 'bar' && s.b === i ? ' is-focus' : '') + (BUTTONS[i][0] === s.mode ? ' is-on' : '');
      }
    }
    return { V: V, open: open, render: render, owns: owns };
  }

  window.CleanFramePhotoAdjust = { BUTTONS: BUTTONS, NO_SRC: NO_SRC, MOVE_STEP: MOVE_STEP, ZOOM_STEP: ZOOM_STEP,
    key: key, hint: hint, note: note, views: views };
})();

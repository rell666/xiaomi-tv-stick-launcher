/* Bluetooth in CleanFrame's own Settings (2026-11-03: fewer clicks than Android's menus).
   Settings has a Bluetooth row (after Apps on Home, before TV) whose value is summary(), and its page
   lists the paired devices with their state, then "Pair new device", which opens Android TV's "Pair
   accessory" page (FrameDevice.bluetoothPair). Android gives apps no way to connect a device, so the
   page's NOTE says headphones reconnect on their own when switched on.
   OK on a device: headphones and speakers (unpair true, and the TV can: canUnpair) ask "Unpair <name>?"
   Yes/No, starting on No (main.js's message view, Back = No), and Yes unpairs: unpair() calls
   FrameDevice.bluetoothUnpair with the device's token and re-reads the list over ~3 s (removeBond is
   asynchronous), then says what happened. Any other device says REFUSED: unpairing the remote would
   strand the stick, so the native side refuses it too (BluetoothReport.unpairable); canUnpair false
   says NO_UNPAIR on every device. An older APK has neither, and gets NO_UNPAIR.
   The stick's answer comes from FrameDevice.bluetooth (web/device.js): { on, canUnpair, devices: [{ name,
   kind, connected, id, unpair }] } or an error whose detail is the reason ("Bluetooth permission not
   granted", no adapter, an older APK without the method). main.js (tools/android-patch.py) calls refresh() when
   Settings or the Bluetooth page opens and when the page is shown again with Settings open (the user
   comes back from pairing), and draws sections(t) on its usual settings detail page. Names reach the
   page only as item labels and message titles, which main.js escapes (esc()); item actions carry an index,
   never a name. ES5, no DOM; window.CleanFrameBluetooth. tests/bluetooth.test.js. */
(function () {
  'use strict';
  var NOTE = 'Headphones reconnect on their own when switched on. Pair new ones here.';
  /* The same words as BluetoothReport.UNPAIR_REFUSED, the native refusal (tests/bluetooth.test.js). */
  var REFUSED = 'Remotes and other devices stay paired here so the TV can\'t lose its remote. Use TV settings to remove them.';
  var NO_UNPAIR = 'This TV can\'t unpair from here. Use TV settings to remove it.';
  var UNPAIRED = 'Unpaired.', NOT_UNPAIRED = 'Unpairing did not work.', STILL_PAIRED = 'It is still paired. Use TV settings to remove it.';
  /* After the TV accepts an unpair: READS looks at the list, READ_MS apart (~3 s). */
  var READS = 6, READ_MS = 500;
  var KINDS = { audio: 'Audio', input: 'Input', other: 'Other' };
  /* Device rows shown at once; the list scrolls (with arrows on its title) like Background's Groups. */
  var VISIBLE = 4;
  /* status: 'idle' (never asked), 'ok' or 'error'. A refresh keeps the last answer on screen until
     the next one arrives; only the newest request's answer is kept. */
  var st = { status: 'idle', on: false, canUnpair: false, devices: [], reason: '' }, seq = 0, working = false;

  function copy(s) {
    return { status: s.status, on: s.on, canUnpair: s.canUnpair, reason: s.reason,
      devices: s.devices.map(function (d) { return { name: d.name, kind: d.kind, connected: d.connected, id: d.id || '', unpair: d.unpair === true }; }) };
  }
  function state() { return copy(st); }
  /* Asks the stick again; done() runs once the answer is in (also when there is no way to ask). */
  function refresh(device, done) {
    var mine = ++seq;
    if (typeof done !== 'function') done = function () {};
    if (!device || typeof device.bluetooth !== 'function') {
      st = { status: 'error', on: false, canUnpair: false, devices: [], reason: 'Open CleanFrame on the TV to use Bluetooth.' };
      done();
      return;
    }
    device.bluetooth(function (err, v) {
      if (mine !== seq) return;   // an older request answering late
      if (err || !v) st = { status: 'error', on: false, canUnpair: false, devices: [], reason: String(err && (err.detail || err.message) || 'The TV gave no answer.') };
      else st = { status: 'ok', on: v.on === true, canUnpair: v.canUnpair === true, devices: (v.devices || []).slice(), reason: '' };
      done();
    });
  }
  function connectedAudio(s) {
    for (var i = 0; i < s.devices.length; i++) if (s.devices[i].connected && s.devices[i].kind === 'audio') return s.devices[i];
    return null;
  }
  /* The Settings row's value. */
  function summary(s) {
    var a, n;
    s = s || st;
    if (s.status === 'idle') return 'Checking…';
    if (s.status === 'error') return s.reason;
    if (!s.on) return 'Off';
    a = connectedAudio(s);
    if (a) return 'Connected: ' + a.name;
    n = s.devices.length;
    return n ? n + ' paired' : 'None paired';
  }
  function kindLabel(kind) { return Object.prototype.hasOwnProperty.call(KINDS, kind) ? KINDS[kind] : KINDS.other; }
  /* A device row's small text (its description line): its state, then its kind. */
  function noteOf(d) { return (d.connected ? 'Connected' : 'Not connected') + ' · ' + kindLabel(d.kind); }
  function windowTop(top, focus, n, visible) {
    if (n <= visible) return 0;
    if (focus >= 0 && focus < top) top = focus;
    if (focus >= top + visible) top = focus - visible + 1;
    return Math.max(0, Math.min(n - visible, top));
  }
  /* The line under "Pair new device": what the stick said when there is nothing to list, then NOTE. */
  function reason(s) {
    var why = working ? 'Unpairing…' : s.status === 'idle' ? 'Checking Bluetooth…' : s.status === 'error' ? s.reason
      : !s.on ? 'Bluetooth is off.' : !s.devices.length ? 'No devices paired yet.' : '';
    if (why && !/[.…!?]$/.test(why)) why += '.';
    return why ? why + ' ' + NOTE : NOTE;
  }
  /* What a device's row says while it is focused: what OK will do. */
  function hintOf(s, d) {
    if (!s.canUnpair) return 'To unpair it, use TV settings';
    return d.unpair === true && d.id ? 'Unpair this device' : 'Stays paired here';
  }
  /* The page, in main.js's detailSections() shape. Device items carry bt 'device' and their index
     (`bare`: no forward arrow; OK asks or says why not, deviceMessage); the pair item carries bt 'pair'. t.f.action is the
     focused item: the device list scrolls to keep it in view (t.bttop). `arrows(up, down)` gives the
     title's scroll marks (main.js passes its icon()); the title holds no device name. */
  function sections(t, arrows) {
    var s = st, out = [], focus = -1, items, i, n;
    items = s.status === 'ok' ? s.devices.map(function (d, j) {
      return { action: 'bluetooth.device.' + j, bt: 'device', index: j, label: d.name, description: noteOf(d), bare: true,
        hint: hintOf(s, d) };
    }) : [];
    t = t || { f: {} };
    for (i = 0; i < items.length; i++) if (items[i].action === (t.f && t.f.action)) focus = i;
    n = items.length;
    t.bttop = windowTop(t.bttop || 0, focus, n, VISIBLE);
    if (n) out.push({ id: 'btdevices', title: 'Paired devices' + (n > VISIBLE && typeof arrows === 'function' ? arrows(t.bttop > 0, t.bttop + VISIBLE < n) : ''),
      items: items, window: [t.bttop, t.bttop + VISIBLE] });
    out.push({ id: 'btpair', title: n ? '' : 'Paired devices', items: [{ action: 'bluetooth.pair', bt: 'pair', label: 'Pair new device',
      hint: 'Open Android’s pairing page' }], reason: reason(s) });
    return out;
  }
  /* What OK on a device shows (main.js's message(title, line, note)); with `ask` { id, name } it is
     the Yes/No question, and main.js passes `ask` to unpair() on Yes. Nothing while one is unpairing. */
  function deviceMessage(index) {
    var d = st.status === 'ok' && !working ? st.devices[index] : null;
    if (!d) return null;
    if (!st.canUnpair) return { title: d.name, line: NO_UNPAIR, note: noteOf(d) };
    if (d.unpair !== true || !d.id) return { title: d.name, line: REFUSED, note: noteOf(d) };
    return { title: 'Unpair ' + d.name + '?', line: '', note: '', ask: { id: d.id, name: d.name } };
  }
  function listed(id) {
    for (var i = 0; i < st.devices.length; i++) if (st.devices[i].id === id) return true;
    return false;
  }
  /* Yes: asks the TV to unpair ask.id, then re-reads the list until the device has gone (READS times,
     on a timer of its own, so a read dropped for a newer one loses nothing). changed() after each
     answer, to redraw; done({ title, line, note }) exactly once, with the result. */
  function unpair(device, ask, changed, done) {
    var left = READS, over = false;
    if (working) return;
    if (typeof changed !== 'function') changed = function () {};
    function finish(line, note) {
      if (over) return;
      over = true; working = false;
      if (typeof done === 'function') done({ title: ask.name, line: line, note: note || '' });
    }
    function gone() { return st.status === 'ok' && st.on && !listed(ask.id); }
    function last() {
      if (gone()) finish(UNPAIRED);
      else finish(NOT_UNPAIRED, st.status === 'ok' ? STILL_PAIRED : st.reason);
    }
    function read() {
      left--;
      refresh(device, function () {
        if (over) return;
        if (gone()) finish(UNPAIRED);
        else changed();
      });
      if (!over) setTimeout(left > 0 ? read : last, READ_MS);
    }
    working = true;
    if (!device || typeof device.bluetoothUnpair !== 'function') { finish(NOT_UNPAIRED, 'Update CleanFrame on the TV to unpair from here.'); return; }
    device.bluetoothUnpair(ask.id, function (err) {
      if (err) finish(NOT_UNPAIRED, String(err.detail || err.message || 'The TV gave no answer.'));
      else read();
    });
  }

  window.CleanFrameBluetooth = { NOTE: NOTE, VISIBLE: VISIBLE, refresh: refresh, state: state, summary: summary,
    sections: sections, deviceMessage: deviceMessage, noteOf: noteOf, unpair: unpair };
}());

/* Android TV device layer for cleanframe-tv. Replaces CleanFrame's Tizen device.js; same
   window.FrameDevice interface, backed by window.AndroidHome (MainActivity.Bridge). */
(function () {
  'use strict';
  var IDS = {
    emby: 'com.mb.android', youtube: 'com.google.android.youtube.tv',
    spotify: 'com.spotify.tv.android', rumble: 'com.rumble.battles',
    netflix: 'com.netflix.ninja', primevideo: 'com.amazon.amazonvideo.livingroom',
    disneyplus: 'com.disney.disneyplus', stremio: 'com.stremio.one',
    appletv: 'com.apple.atve.androidtv.appletv'
  };
  /* Other packages of the same app, from other sticks: they get the brand too (its white mark and
     its place on the dock). The Hugsun HS89 (Android 14) ships Emby for Android TV and Rockchip's
     own YouTube build. A package here must not be in IDS or under another brand (device.test.js). */
  var ALSO = {
    emby: ['tv.emby.embyatv'], youtube: ['com.rockchip.android.youtube.tv']
  };
  function api() { return window.AndroidHome; }
  /* Every FrameDevice callback runs asynchronously, on every path, so callers see one contract.
     done is optional everywhere (as in screenCall and photoCall): a caller that passes none gets no
     answer, never a TypeError thrown from a timer. */
  function later(done, err) {
    if (typeof done !== 'function') done = function () {};
    setTimeout(function () { done(err); }, 0);
  }
  function unavailable(done) {
    later(done, { message: 'Open CleanFrame on the TV to use this control.', detail: 'The web preview cannot control the TV.' });
  }
  /* The bridge (launch, settings, appInfo) returns "" on success or a reason; answer asynchronously
     like the Tizen API. Only "" is success: null, undefined or any other non-string is no answer. */
  function answer(result, done) {
    setTimeout(function () {
      if (result === '') done(null);
      else done({ message: 'The TV could not open this screen.', detail: typeof result === 'string' ? result : 'The TV gave no answer.' });
    }, 0);
  }
  /* A bridge call that throws still answers, so the page never waits out its 10 s busy lock. */
  function call(run, done) {
    var result;
    if (typeof done !== 'function') done = function () {};
    if (!api()) { unavailable(done); return; }
    try { result = run(api()); } catch (err) { result = String((err && err.message) || err || 'Unknown error'); }
    answer(result, done);
  }
  function launchApp(id, done) {
    call(function (tv) { return tv.launch(id); }, done);
  }
  /* launch(brand) and home() are dead code on Android, kept for parity with CleanFrame's Tizen
     FrameDevice interface (tests/device.test.js pins the exported keys; removing them means
     changing both). The patched main.js opens apps with launchApp() and never calls launch();
     it still names FrameDevice.home only on paths it cannot reach (tools/android-patch.py drops
     the samsung.home settings item, and nothing opens the 'leave' message). */
  function launch(brand, done) {
    if (!Object.prototype.hasOwnProperty.call(IDS, brand)) {
      later(done, { message: 'This app has no launch target.', detail: 'CleanFrame on this TV has no app for ' + String(brand) + '.' });
      return;
    }
    launchApp(IDS[brand], done);
  }
  /* Settings > Screen and the Sleep button (web/home-extras.js). Each bridge call answers a JSON
     string, {"ok":true,...} or {"ok":false,"reason"}; like photoCall, done runs once and always
     asynchronously, and a missing bridge or method (an older APK), a throw or bad JSON becomes
     { message, detail }. */
  var UPDATE_SCREEN = 'Update CleanFrame on the TV to use this.';
  function screenCall(message, run, check, done) {
    if (typeof done !== 'function') done = function () {};
    if (!api()) { unavailable(done); return; }
    setTimeout(function () {
      var value, err = null;
      try {
        value = JSON.parse(String(run(api())));
        if (!value || typeof value !== 'object' || value.ok !== true) {
          throw new Error(value && typeof value.reason === 'string' && value.reason ? value.reason : 'The TV gave no answer.');
        }
        value = check(value);
      } catch (e) {
        err = { message: message, detail: String((e && e.message) || e || 'Unknown error') };
      }
      if (err) done(err);
      else done(null, value);
    }, 0);
  }
  /* Minutes without a button press before Home dims, then before the stick sleeps (0 = never).
     {"ok":true,"canWrite":bool}: canWrite false = the TV has not let CleanFrame change its sleep time. */
  function screenPolicy(dimMin, sleepMin, done) {
    screenCall('Could not change the screen settings.', function (tv) {
      if (typeof tv.screenPolicy !== 'function') throw new Error(UPDATE_SCREEN);
      return tv.screenPolicy(String(dimMin), String(sleepMin));
    },
      function (v) { return { canWrite: v.canWrite === true }; }, done);
  }
  /* Sleep now: the stick sleeps within moments; Sleep after's timeout comes back once it has (or at
     sleepCancel). {"ok":true}; refused ({"ok":false,"reason"}) when the TV will not let CleanFrame
     change its sleep time. */
  function sleepNow(done) {
    screenCall('Could not put the TV to sleep.', function (tv) {
      if (typeof tv.sleepNow !== 'function') throw new Error(UPDATE_SCREEN);
      return tv.sleepNow();
    }, function () { return {}; }, done);
  }
  /* A button was pressed before it slept: Sleep after's timeout again. {"ok":true}. */
  function sleepCancel(done) {
    screenCall('Could not change the screen settings.', function (tv) {
      if (typeof tv.sleepCancel !== 'function') throw new Error(UPDATE_SCREEN);
      return tv.sleepCancel();
    }, function () { return {}; }, done);
  }
  /* Synchronous (home-extras.js reads it when Home would dim): { playing } or null when there is no
     bridge or no usable answer. */
  function sleepStatusNow() {
    var v;
    try {
      if (!api()) return null;
      v = JSON.parse(String(api().sleepStatus()));
      if (!v || typeof v !== 'object' || typeof v.playing !== 'boolean') return null;
      return { playing: v.playing };
    } catch (e) { return null; }
  }
  function home(done) {
    later(done, { message: 'CleanFrame is this TV’s Home screen.', detail: 'There is no other Home screen to open.' });
  }
  function settings(done) {
    call(function (tv) { return tv.settings(); }, done);
  }
  /* The long-press menu's App info (web/home-extras.js): Android's page for that installed package. */
  function appInfo(pkg, done) {
    call(function (tv) { return tv.appInfo(String(pkg)); }, done);
  }
  /* The long-press menu's Uninstall (2026-11-04). canUninstall is synchronous, like libraryNow:
     main.js builds the menu when it opens. The native side answers false for system apps, CleanFrame
     itself, Play/GMS and other core packages; only true or "true" count, and no bridge, a bridge
     without the method (an older APK), a throw or no package are all false: no Uninstall item.
     uninstall opens Android's own dialog, which does the confirming: "" (it opened) or a reason. */
  function canUninstall(pkg) {
    var tv = api(), v;
    if (!tv || typeof pkg !== 'string' || !pkg) return false;
    try {
      if (typeof tv.canUninstall !== 'function') return false;
      v = tv.canUninstall(pkg);
      return v === true || v === 'true';
    } catch (e) { return false; }
  }
  function uninstall(pkg, done) {
    call(function (tv) {
      if (typeof pkg !== 'string' || !pkg) return 'No app was chosen.';
      return typeof tv.uninstall === 'function' ? tv.uninstall(pkg) : 'Update CleanFrame on the TV to uninstall from here.';
    }, done);
  }
  function apps(done) {
    if (typeof done !== 'function') done = function () {};
    if (!api()) { unavailable(done); return; }
    /* done runs exactly once and outside the try, so a bad bridge answer cannot leave
       All apps loading and an error in done cannot call it twice. Entries keep the bridge's
       order (main.js sorts All apps by name itself), except that a brand's IDS package comes before
       its ALSO package: main.js gives the brand's id to the first it reads, so with both installed
       that tile opens the same package whatever order Android lists them in. */
    setTimeout(function () {
      var list, known = {}, result = [];
      try {
        list = JSON.parse(api().apps());
        if (!Array.isArray(list)) throw new Error('not a list');
        Object.keys(IDS).forEach(function (brand) { known[IDS[brand]] = brand; });
        Object.keys(ALSO).forEach(function (brand) { ALSO[brand].forEach(function (pkg) { known[pkg] = brand; }); });
        list.forEach(function (a) {
          if (!a || typeof a.appId !== 'string' || !a.appId) return;
          result.push({ appId: a.appId, name: typeof a.name === 'string' && a.name.trim() ? a.name.trim() : a.appId,
            brand: Object.prototype.hasOwnProperty.call(known, a.appId) ? known[a.appId] : '', icon: '/icons/' + a.appId + '.png' });
        });
        result.forEach(function (e, i) {
          var j, other;
          if (!e.brand || e.appId !== IDS[e.brand]) return;
          for (j = 0; j < i; j++) {
            if (result[j].brand === e.brand) { other = result[j]; result[j] = e; result[i] = other; return; }
          }
        });
      } catch (err) { result = null; }
      if (result) done(null, result);
      else done({ message: 'Could not read installed apps.' });
    }, 0);
  }
  /* Photo groups (docs/PHOTO-GROUPS.md, contract 2): every bridge method answers a JSON string.
     Each wrapper answers done exactly once and always asynchronously; a missing bridge or method,
     a throw, unparseable JSON or {"ok":false} become { message, detail } like the controls above. */
  var PHOTO_ERRORS = {
    library: 'Could not read your photos.', addGroup: 'Could not add the group.',
    renameGroup: 'Could not rename the group.', deleteGroup: 'Could not delete the group.',
    deletePhoto: 'Could not remove the photo.', uploadStart: 'Could not start adding photos.',
    uploadStop: 'Could not stop adding photos.', materialJobs: 'Could not read the photos being prepared.',
    saveMaterial: 'Could not save a prepared photo.', recrop: 'Could not save the new framing.',
    recropState: 'Could not check the new framing.'
  };
  function parse(name, args) {
    var tv = api(), raw;
    if (typeof tv[name] !== 'function') throw new Error('Update CleanFrame on the TV to use photo groups.');
    raw = tv[name].apply(tv, args);
    return JSON.parse(String(raw));
  }
  /* check(answer) returns the value for done(null, value), or throws with the reason. */
  function photoCall(name, args, check, done) {
    if (typeof done !== 'function') done = function () {};
    if (!api()) { unavailable(done); return; }
    setTimeout(function () {
      var value, err = null;
      try {
        if (args.indexOf(NO_ID) >= 0) throw new Error('No group or photo was given.');
        value = parse(name, args);
        if (!value || typeof value !== 'object') throw new Error('The TV gave no answer.');
        if (value.ok === false) throw new Error(typeof value.reason === 'string' && value.reason ? value.reason : 'The TV refused.');
        value = check(value);
      } catch (e) {
        err = { message: PHOTO_ERRORS[name], detail: String((e && e.message) || e || 'Unknown error') };
      }
      if (err) done(err);
      else done(null, value);
    }, 0);
  }
  function okOnly(value) { if (value.ok !== true) throw new Error('The TV gave no answer.'); return undefined; }
  function libraryOf(value) {
    if (!Array.isArray(value.groups) || !Array.isArray(value.photos)) throw new Error('Not a photo library.');
    return value;
  }
  function str(v) { return typeof v === 'string' ? v : String(v); }
  /* A group or photo id: undefined, null or "" would reach the bridge as "undefined", "null" or an
     empty id, so photoCall refuses it here (done once, asynchronously, like any other refusal). */
  var NO_ID = {};
  function id(v) { return v === undefined || v === null || v === '' ? NO_ID : str(v); }
  function library(done) { photoCall('library', [], libraryOf, done); }
  /* The one synchronous read: main.js needs the library before it restores the saved wallpaper
     at start-up (an uploaded photo saved as the background must not fall back to Scenery). The
     bridge call is synchronous anyway; null when there is no bridge or no usable answer. */
  function libraryNow() {
    var v;
    try {
      if (!api()) return null;
      v = parse('library', []);
      /* {"ok":false} is a refusal, as for library(), even when it carries lists */
      if (!v || typeof v !== 'object' || v.ok === false) return null;
      return libraryOf(v);
    } catch (e) { return null; }
  }
  function addGroup(name, done) {
    photoCall('addGroup', [str(name)], function (v) {
      if (v.ok !== true || typeof v.id !== 'string' || !/^g[0-9a-z]{8}$/.test(v.id)) throw new Error('The TV gave no group id.');
      return { id: v.id };
    }, done);
  }
  function renameGroup(gid, name, done) { photoCall('renameGroup', [id(gid), str(name)], okOnly, done); }
  function deleteGroup(gid, done) { photoCall('deleteGroup', [id(gid)], okOnly, done); }
  function deletePhoto(gid, pid, done) { photoCall('deletePhoto', [id(gid), id(pid)], okOnly, done); }
  /* v2 (contract 4): url is the stick's own page, http://<stick IPv4>:8097/u/<token>. base defaults
     to the url's origin and code may be absent (then the screen shows no code); when given, both are
     checked as before. No network -> {"ok":false,"reason":"No network connection"} -> the reason. */
  function uploadStart(gid, done) {
    photoCall('uploadStart', [id(gid)], function (v) {
      var origin = typeof v.url === 'string' ? /^https?:\/\/[^\/\s?#]+/.exec(v.url) : null,
        base = v.base === undefined && origin ? origin[0] : v.base;
      /* the url must be under base as a path (base + "/..."), not merely start with its characters:
         base http://192.168.0.5:8097 must not admit http://192.168.0.5:80970/... */
      /* and nothing in it may be whitespace (a newline or space after the origin would reach the QR) */
      if (v.ok !== true || !origin || /\s/.test(v.url) || typeof base !== 'string' || v.url.indexOf(base + '/') !== 0
        || !/^https?:\/\//.test(base) || (v.code !== undefined && (typeof v.code !== 'string' || !/^[0-9]{6}$/.test(v.code)))
        || typeof v.expires !== 'number' || !isFinite(v.expires)) throw new Error('The TV gave no upload address.');
      return { url: v.url, base: base, code: v.code === undefined ? '' : v.code, expires: v.expires };
    }, done);
  }
  function uploadStop(done) { photoCall('uploadStop', [], okOnly, done); }
  /* Materials for uploaded photos (contract 4, web/materials.js). materialJobs answers
     {"v":1,"jobs":[…]} (no "ok"); saveMaterial answers {"ok":true,"done":bool}. */
  function materialJobs(done) {
    photoCall('materialJobs', [], function (v) {
      if (!Array.isArray(v.jobs)) throw new Error('The TV gave no job list.');
      return v;
    }, done);
  }
  function saveMaterial(gid, pid, kind, dataUrl, done) {
    photoCall('saveMaterial', [id(gid), id(pid), str(kind), str(dataUrl)], function (v) {
      if (v.ok !== true || typeof v.done !== 'boolean') throw new Error('The TV gave no answer.');
      return { done: v.done };
    }, done);
  }
  /* Adjust (web/photo-adjust.js): recrop answers {"ok":true} at once and the stick re-makes the photo;
     recropState answers {"ok":true,"state":"working"|"saving"|"done"|"failed","reason"?}. */
  var RECROP_STATES = ['working', 'saving', 'done', 'failed'];
  function recrop(gid, pid, crop, done) { photoCall('recrop', [id(gid), id(pid), str(crop)], okOnly, done); }
  function recropState(gid, pid, done) {
    photoCall('recropState', [id(gid), id(pid)], function (v) {
      if (v.ok !== true || RECROP_STATES.indexOf(v.state) < 0) throw new Error('The TV gave no answer.');
      return { state: v.state, reason: typeof v.reason === 'string' ? v.reason : '' };
    }, done);
  }
  /* Bluetooth in CleanFrame's own Settings (web/bluetooth.js; 2026-11-03: fewer clicks than
     Android's menus). bluetooth() answers {"ok":true,"on":bool,"canUnpair":bool,"devices":[{"name","kind",
     "connected","id","unpair"}]} (connected first, then by name) or {"ok":false,"reason"}. Like photoCall, done runs exactly once
     and always asynchronously; a missing bridge or method (an older APK), a throw, bad JSON or
     {"ok":false} become { message, detail }. A device without a usable name or a boolean connected is
     dropped; an unknown kind is "other"; names are folded, cut at 80 characters and then trimmed
     (never leaving half of a surrogate pair). id is the bridge's opaque token ('' when absent or not
     a string); unpair and canUnpair are true only when the bridge says exactly true, and unpair needs
     an id, so an older APK simply has no unpairing. The list is
     sorted again here (connected first, then by name, case-insensitive) so the page never depends on
     the bridge's order. bluetoothPair() opens Android TV's "Pair accessory" page: "" or a reason, like
     settings(). bluetoothUnpair(id) unpairs one device by its token: "" or a reason, the same way (the
     bridge refuses anything but headphones and speakers itself; the removal finishes later, so the
     caller re-reads the list). */
  var BT_KINDS = ['audio', 'input', 'other'];
  function bluetoothOf(v) {
    var out = [], i, d, name, btId;
    if (typeof v.on !== 'boolean' || !Array.isArray(v.devices)) throw new Error('The TV gave no Bluetooth state.');
    for (i = 0; i < v.devices.length; i++) {
      d = v.devices[i];
      if (!d || typeof d !== 'object' || typeof d.name !== 'string' || typeof d.connected !== 'boolean') continue;
      /* fold whitespace, drop the leading space, cut at 80 UTF-16 units, then drop a high surrogate
         the cut left alone (it would show as a replacement mark) and the trailing space */
      name = d.name.replace(/\s+/g, ' ').replace(/^ /, '').slice(0, 80).replace(/[\uD800-\uDBFF]$/, '').replace(/ $/, '');
      if (!name) continue;
      btId = typeof d.id === 'string' ? d.id : '';
      out.push({ name: name, kind: BT_KINDS.indexOf(d.kind) >= 0 ? d.kind : 'other', connected: d.connected, id: btId, unpair: d.unpair === true && btId !== '', n: out.length });
    }
    out.sort(function (a, b) {
      var x = a.name.toLowerCase(), y = b.name.toLowerCase();
      return (b.connected ? 1 : 0) - (a.connected ? 1 : 0) || (x < y ? -1 : x > y ? 1 : 0) || a.n - b.n;
    });
    return { on: v.on, canUnpair: v.canUnpair === true,
      devices: out.map(function (x) { return { name: x.name, kind: x.kind, connected: x.connected, id: x.id, unpair: x.unpair }; }) };
  }
  function bluetooth(done) {
    if (typeof done !== 'function') done = function () {};
    if (!api()) { unavailable(done); return; }
    setTimeout(function () {
      var tv = api(), value, err = null;
      try {
        if (!tv || typeof tv.bluetooth !== 'function') throw new Error('Update CleanFrame on the TV to see Bluetooth here.');
        value = JSON.parse(String(tv.bluetooth()));
        if (!value || typeof value !== 'object') throw new Error('The TV gave no answer.');
        if (value.ok !== true) throw new Error(typeof value.reason === 'string' && value.reason ? value.reason : 'The TV gave no answer.');
        value = bluetoothOf(value);
      } catch (e) {
        err = { message: 'Could not read Bluetooth.', detail: String((e && e.message) || e || 'Unknown error') };
      }
      if (err) done(err);
      else done(null, value);
    }, 0);
  }
  function bluetoothPair(done) {
    call(function (tv) {
      return typeof tv.bluetoothPair === 'function' ? tv.bluetoothPair() : 'Update CleanFrame on the TV to pair from here.';
    }, done);
  }
  function bluetoothUnpair(deviceId, done) {
    call(function (tv) {
      if (typeof deviceId !== 'string' || !deviceId) return 'No device was chosen.';
      return typeof tv.bluetoothUnpair === 'function' ? tv.bluetoothUnpair(deviceId) : 'Update CleanFrame on the TV to unpair from here.';
    }, done);
  }
  /* MainActivity calls FrameDevice.materialsWake() after queueing a job. It returns at once; the
     handler (photo-groups.js's queue, set with onMaterialsWake) runs in its own task. A wake before
     a handler is set is kept and delivered when one is. */
  var wakeHandler = null, wakePending = false;
  function materialsWake() {
    if (!wakeHandler) { wakePending = true; return; }
    setTimeout(function () { try { if (wakeHandler) wakeHandler(); } catch (e) { /* the 2 s poll still runs */ } }, 0);
  }
  function onMaterialsWake(fn) {
    wakeHandler = typeof fn === 'function' ? fn : null;
    if (wakeHandler && wakePending) { wakePending = false; materialsWake(); }
  }
  /* The remote's Home key while CleanFrame is already Home: close panels back to the dock,
     one Back at a time, as the user would. With nothing open but focus on the utility row
     (main.js then renders #utils with class is-awake), one more Back sends focus to the dock:
     Back in the util zone sets zone 'apps' in both layouts (V.home.key and listHomeKey), and
     press() re-renders synchronously, so the next check sees the new state.
     The Adjust screen (#photoAdjust, web/photo-adjust.js) is open too while it is shown: it is not a
     sheet or a settings detail, so #sheet and #appearance are hidden under it. Escape is 'back' there
     (main.js KEYS[27]): the first leaves the photo for the buttons, the next cancels (or closes a
     photo that never loaded) and pops to the group page, whose own Backs then run as above. While it
     is saving it ignores keys, and the cap of 12 pairs stops the loop. 12, not 8: from Adjust to the
     dock is already up to 7 Backs (photo -> buttons, Cancel, the group page's Adjust mode, the group
     page, Background, Settings, the utility row). */
  function toHome() {
    var i, open;
    /* Home brightens a dimmed Home first (web/home-extras.js), like any other button. */
    try { if (window.CleanFrameExtras && window.CleanFrameExtras.wake) window.CleanFrameExtras.wake(); } catch (e) { /* keep going to the dock */ }
    function key(type) {
      var e = document.createEvent('Event');
      e.initEvent(type, true, true);
      e.keyCode = e.which = 27;
      document.dispatchEvent(e);
    }
    function shown(id) { var el = document.getElementById(id); return !!el && !el.hidden; }
    function awake() {
      var el = document.getElementById('utils');
      return !!el && /(^|\s)is-awake(\s|$)/.test(String(el.className || ''));
    }
    for (i = 0; i < 12; i++) {
      open = shown('photoAdjust') || shown('sheet') || shown('appearance') || awake();
      if (!open) return;
      key('keydown'); key('keyup');
    }
  }
  /* Tell MainActivity the moment the page has drawn its first control (the [data-action] its
     READY_JS probe looks for): the probe asks only every 2 s, and a start killed in that gap would
     wrongly count toward the 3-failed-starts guard. Once, then the observer is gone; without
     MutationObserver or the bridge (web preview) it does nothing and the probe still decides. */
  function watchReady() {
    var doc = window.document, Observer = window.MutationObserver, obs = null;
    function drawn() {
      return !!doc && typeof doc.querySelector === 'function' && doc.querySelector('[data-action]') !== null;
    }
    function tell() {
      if (obs) { obs.disconnect(); obs = null; }
      try { if (api()) api().ready(); } catch (err) { /* the native probe still sees the page */ }
    }
    if (drawn()) { tell(); return; }
    if (typeof Observer !== 'function' || !doc || !doc.documentElement) return;
    obs = new Observer(function () { if (obs && drawn()) tell(); });
    obs.observe(doc.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-action'] });
  }
  watchReady();
  window.FrameDevice = { launch: launch, launchApp: launchApp, screenPolicy: screenPolicy, sleepNow: sleepNow,
    sleepCancel: sleepCancel, sleepStatusNow: sleepStatusNow, home: home, settings: settings, appInfo: appInfo, canUninstall: canUninstall, uninstall: uninstall, apps: apps, toHome: toHome,
    library: library, libraryNow: libraryNow, addGroup: addGroup, renameGroup: renameGroup, deleteGroup: deleteGroup,
    deletePhoto: deletePhoto, uploadStart: uploadStart, uploadStop: uploadStop,
    materialJobs: materialJobs, saveMaterial: saveMaterial, materialsWake: materialsWake, onMaterialsWake: onMaterialsWake,
    recrop: recrop, recropState: recropState, bluetooth: bluetooth, bluetoothPair: bluetoothPair, bluetoothUnpair: bluetoothUnpair };
}());

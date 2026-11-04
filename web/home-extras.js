/* cleanframe-tv additions to CleanFrame's Home: the clock options (Settings > Clock), the
   long-press app menu (its Rename and Change icon screens are web/app-edit.js), Settings > Screen (dim, then sleep) and the Sleep button's menu (Sleep now). 2026-11-01. The preferences live in their own localStorage key, so
   upstream's saved layout (cleanframe.home.v1) is never rewritten by them and an older build
   simply ignores them.
   Loaded by the patched index.html before main.js; window.CleanFrameExtras. ES5 only.
   Pure logic plus one small store; tests/home-extras.test.js covers it offline. */
(function () {
  'use strict';
  var KEY = 'cleanframe.tv.extras.v1';
  /* Each preference: its choices in display order ([value, label]) and the default. */
  var PREFS = {
    clockFormat: { def: '12', choices: [['12', '12-hour'], ['12a', '12-hour with AM/PM'], ['24', '24-hour']] },
    clockSeconds: { def: 'off', choices: [['off', 'Off'], ['on', 'On']] },
    clockShow: { def: 'on', choices: [['on', 'Show'], ['off', 'Hide']] },
    /* Settings > Screen: minutes without a button press before Home dims, then minutes after the dim
       before the stick sleeps (MainActivity sets Android's screen-off timeout to their sum). */
    screenDim: { def: '5', choices: [['off', 'Off'], ['2', '2 minutes'], ['5', '5 minutes'], ['10', '10 minutes'], ['15', '15 minutes']] },
    screenSleep: { def: '30', choices: [['off', 'Off'], ['10', '10 minutes'], ['30', '30 minutes'], ['60', '1 hour'], ['120', '2 hours']] }
  };
  var GROUPS = {
    clock: [
      { id: 'clockFormat', title: 'Format' },
      { id: 'clockSeconds', title: 'Seconds', horizontal: true },
      { id: 'clockShow', title: 'Clock on Home', horizontal: true }
    ],
    /* Each Screen row opens its own list, like Background > Rotation. */
    screen: [
      { id: 'screenDim', title: 'Dim after', link: 'screenDim', hint: 'Choose when Home dims' },
      { id: 'screenSleep', title: 'Sleep after', link: 'screenSleep', hint: 'Choose when the TV sleeps',
        reason: 'Counted from the dim. Playing music or video keeps the TV awake; any button wakes it.' }
    ],
    screenDim: [{ id: 'screenDim', title: '', reason: 'The first button press only brightens Home.' }],
    screenSleep: [{ id: 'screenSleep', title: '', reason: 'Counted from the dim. Any button on the remote wakes the TV.' }]
  };
  var NAMES = { clock: 'Clock', screen: 'Screen', screenDim: 'Dim after', screenSleep: 'Sleep after' };
  /* A page opened from another settings page: its Back row names that page. */
  var PARENTS = { screenDim: 'Screen', screenSleep: 'Screen' };

  function own(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function valid(key, value) {
    return own(PREFS, key) && PREFS[key].choices.some(function (c) { return c[0] === value; });
  }
  function label(key, value) {
    var c = PREFS[key].choices.filter(function (x) { return x[0] === value; })[0];
    return c ? c[1] : '';
  }
  function defaults() {
    var out = {};
    Object.keys(PREFS).forEach(function (k) { out[k] = PREFS[k].def; });
    return out;
  }
  /* Saved values are checked one by one: a bad or unknown value falls back to its default. */
  function clean(saved) {
    var out = defaults();
    if (saved && typeof saved === 'object' && !Array.isArray(saved)) {
      Object.keys(PREFS).forEach(function (k) { if (own(saved, k) && valid(k, saved[k])) out[k] = saved[k]; });
    }
    return out;
  }
  function storage() { try { return window.localStorage || null; } catch (e) { return null; } }
  function read() {
    var s = storage(), raw;
    if (!s) return defaults();
    try { raw = s.getItem(KEY); return clean(raw ? JSON.parse(raw) : null); } catch (e) { return defaults(); }
  }

  var prefs = read(), listeners = [];
  function all() { return clean(prefs); }
  function get(key) { return prefs[key]; }
  /* Saves one preference. false (nothing changed) if the value is not allowed or the save fails. */
  function set(key, value) {
    var next = clean(prefs), s = storage();
    if (!valid(key, value)) return false;
    next[key] = value;
    try { if (!s) throw new Error('no storage'); s.setItem(KEY, JSON.stringify(next)); } catch (e) { return false; }
    prefs = next;
    listeners.slice().forEach(function (fn) { try { fn(key, value); } catch (e) { /* one bad listener must not stop the rest */ } });
    return true;
  }
  function onChange(fn) { if (typeof fn === 'function') listeners.push(fn); }

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  /* The Home clock's text for date d: '' when the clock is hidden. 24-hour keeps the leading zero. */
  function clockText(d, p) {
    var h = d.getHours(), text;
    p = p || prefs;
    if (p.clockShow === 'off') return '';
    text = p.clockFormat === '24' ? pad(h) + ':' + pad(d.getMinutes()) : (h % 12 || 12) + ':' + pad(d.getMinutes());
    if (p.clockSeconds === 'on') text += ':' + pad(d.getSeconds());
    if (p.clockFormat === '12a') text += h < 12 ? ' AM' : ' PM';
    return text;
  }
  /* How often the clock must redraw: every second only when seconds are shown. */
  function clockStep(p) {
    p = p || prefs;
    return p.clockShow !== 'off' && p.clockSeconds === 'on' ? 1000 : 60000;
  }
  function summary(group, p) {
    p = p || prefs;
    if (group === 'clock') {
      if (p.clockShow === 'off') return 'Hidden';
      return label('clockFormat', p.clockFormat) + (p.clockSeconds === 'on' ? ', seconds' : '');
    }
    if (group === 'screen') return 'Dim ' + brief(p.screenDim) + ', sleep ' + brief(p.screenSleep);
    return '';
  }
  function brief(v) { return v === 'off' ? 'off' : Number(v) % 60 ? v + ' min' : Number(v) / 60 + ' h'; }
  /* A settings page's sections in main.js's detailSections() shape. Each item carries `pref` and
     `value`; the patched main.js saves it with set() and ticks it when applied(). */
  /* A link row (Screen) carries `sub`, the page OK opens, and its own hint; its label is the saved value. */
  function sections(group) {
    return (GROUPS[group] || []).map(function (s) {
      var out = { id: s.id, title: s.title, horizontal: !!s.horizontal, reason: s.reason || '' };
      out.items = s.link ? [{ action: 'screen.open.' + s.link, sub: s.link, hint: s.hint, label: label(s.id, prefs[s.id]) }]
        : PREFS[s.id].choices.map(function (c) { return { action: 'pref.' + s.id + '.' + c[0], pref: s.id, value: c[0], label: c[1] }; });
      return out;
    });
  }
  function applied(item) { return !!item && !!item.pref && prefs[item.pref] === item.value; }
  function owns(group) { return own(GROUPS, group); }
  function title(group) { return NAMES[group] || ''; }
  function parent(group) { return own(PARENTS, group) ? PARENTS[group] : ''; }

  /* Long press (2026-11-01). OK held HOLD_MS on an app opens its menu; released sooner it is
     an ordinary OK, sent on release. start(tap, fire): fire() runs once if the key is still held at
     ms; end() (the key's release) runs tap() instead if fire has not run, and answers whether it did;
     cancel() (focus lost, page hidden) runs neither. */
  var HOLD_MS = 600;
  function Hold(ms) { this.ms = ms || HOLD_MS; this.timer = 0; this.tap = null; }
  Hold.prototype.start = function (tap, fire) {
    var h = this;
    h.cancel();
    h.tap = tap;
    h.timer = setTimeout(function () { h.timer = 0; h.tap = null; fire(); }, h.ms);
  };
  Hold.prototype.end = function () {
    var tap = this.tap;
    if (!this.timer) return false;
    clearTimeout(this.timer); this.timer = 0; this.tap = null;
    tap();
    return true;
  };
  Hold.prototype.cancel = function () {
    if (this.timer) clearTimeout(this.timer);
    this.timer = 0; this.tap = null;
  };
  Hold.prototype.active = function () { return !!this.timer; };
  /* The menu's buttons ([action, label]), in main.js's message-view shape. A dock app can be moved
     or removed; an All apps tile is added to or removed from Home. Both can be renamed and given
     another icon (web/app-edit.js; 2026-11-01). Uninstall (2026-11-04) only when the native
     side says the app can go (FrameDevice.canUninstall), after App info so that App info keeps its
     place; Cancel is always last. Seven buttons at most: a dock app's row (the longest) is 1,403 stage px wide from x 112 (measured
     in Chromium, Manrope), so it ends at 1,515 of 1,920 and needs no compacting. */
  var EDIT = [['rename', 'Rename'], ['icon', 'Change icon']];
  function appMenu(where, onHome, canUninstall) {
    var a = where === 'dock' ? [['move', 'Move']].concat(EDIT, [['remove', 'Remove from Home']])
      : [onHome ? ['remove', 'Remove from Home'] : ['add', 'Add to Home']].concat(EDIT);
    return a.concat([['info', 'App info']], canUninstall === true ? [['uninstall', 'Uninstall']] : [], [['back', 'Cancel']]);
  }
  function appMenuLine(where) {
    return where === 'dock' ? 'Move changes its place on Home. Rename and Change icon change only how it looks.'
      : 'Rename and Change icon change only how it looks. App info opens Android’s page for it.';
  }

  /* Screen (2026-11-01: "dim, then sleep till i press a button"; "playback is activity").
     Home dims after screenDim minutes without a button press while it is on screen; the first press
     only brightens it, and it clears when the page is shown again. It does not dim while the TV
     reports playback (FrameDevice.sleepStatusNow().playing). The stick's own sleep is Android's:
     screenPolicy() hands both values to MainActivity, which sets the screen-off timeout and keeps
     the screen on while something plays. */
  var MINUTE = 60000, dimEl = null, dimTimer = 0, dimmed = false, wakeKey = null, napping = false;
  function device() { return window.FrameDevice || null; }
  function minutes(v) { return v === 'off' ? 0 : Number(v); }
  function shown() { var d = window.document; return !d || !d.hidden; }
  function status() {
    var d = device(), s = null;
    try { s = d && d.sleepStatusNow ? d.sleepStatusNow() : null; } catch (e) { s = null; }
    return s && typeof s === 'object' ? s : null;
  }
  function layer(on) {
    var d = window.document;
    dimmed = on;
    if (!dimEl && on && d && d.body) {
      dimEl = d.createElement('div');
      dimEl.className = 'cf-dim';
      dimEl.setAttribute('aria-hidden', 'true');
      d.body.appendChild(dimEl);
    }
    if (dimEl) dimEl.className = 'cf-dim' + (on ? ' is-on' : '') + (on && napping ? ' is-asleep' : '');
  }
  function arm() {
    clearTimeout(dimTimer); dimTimer = 0;
    if (!minutes(prefs.screenDim) || !shown()) return;
    dimTimer = setTimeout(dimNow, minutes(prefs.screenDim) * MINUTE);
  }
  /* Dim at once (the timer's end; tools/stick-test.sh calls it so it need not wait minutes). Not while
     something plays: then it looks again after another full period. */
  function dimNow() {
    var s = status();
    dimTimer = 0;
    if (s && s.playing) { arm(); return false; }
    layer(true);
    return true;
  }
  /* A key went down. true = it only brightened Home (and its repeats are swallowed until it is
     released), so the page must not act on it. */
  function activity(code) {
    awake();
    if (dimmed) { layer(false); wakeKey = code; arm(); return true; }
    if (wakeKey !== null && code === wakeKey) return true;
    arm();
    return false;
  }
  function keyUp(code) { if (code === wakeKey) wakeKey = null; }
  /* The remote's Home key reaches the page as FrameDevice.toHome(), not as a key. */
  function wake() { var was = dimmed; awake(); layer(false); arm(); return was; }
  function visible(on) {
    wakeKey = null;
    if (!on) { clearTimeout(dimTimer); dimTimer = 0; return; }
    awake(); layer(false); arm();
  }
  function policy() {
    var d = device();
    if (d && d.screenPolicy) d.screenPolicy(minutes(prefs.screenDim), minutes(prefs.screenSleep), function () {});
  }
  function screenStart() {
    onChange(function (key) { if (key === 'screenDim' || key === 'screenSleep') { policy(); arm(); } });
    policy(); arm();
  }

  /* The Sleep button (it replaces CleanFrame's Art Mode / our Screensaver button). 2026-11-01:
     it and Settings > Screen > Sleep after are one setting with two doors (the countdown timer is
     gone), plus Sleep now, which puts the stick to sleep at once and leaves Sleep after as it was
     ("keeps the original setting after"). Sleep now blacks out Home and MainActivity writes Android's
     shortest timeout until the stick has slept; any key first (awake()) puts Sleep after back. */
  var SLEEP_NOW = 'now';
  function sleepActions() { return [[SLEEP_NOW, 'Sleep now']].concat(PREFS.screenSleep.choices); }
  function sleepMenu() {
    return { title: 'Sleep', line: 'Sleep after: ' + label('screenSleep', prefs.screenSleep) + '. The same setting as Settings › Screen.',
      note: 'Counted from the dim. Any button on the remote wakes the TV.', actions: sleepActions() };
  }
  /* The menu opens on the applied Sleep after choice. */
  function sleepIndex() {
    var a = sleepActions(), i;
    for (i = 1; i < a.length; i++) if (a[i][0] === prefs.screenSleep) return i;
    return 0;
  }
  /* A choice in the Sleep menu. done(err): Sleep after saves like Settings > Screen (onChange hands it
     to the TV); Sleep now is the TV's answer. */
  function sleepSet(value, done) {
    var d = device();
    if (typeof done !== 'function') done = function () {};
    if (value === SLEEP_NOW) {
      if (!d || !d.sleepNow) { done({ message: 'Open CleanFrame on the TV to use Sleep now.' }); return; }
      d.sleepNow(function (err) {
        if (!err) { napping = true; layer(true); }
        done(err || null);
      });
      return;
    }
    if (!valid('screenSleep', value)) { done({ message: 'Not a sleep time.' }); return; }
    done(set('screenSleep', value) ? null : { message: 'Could not save the sleep time.' });
  }
  /* A key, the Home key or the page shown again after Sleep now: the user is here, so Android's
     timeout goes back to Sleep after's (the TV also does this itself when it wakes). */
  function awake() {
    var d = device();
    if (!napping) return;
    napping = false;
    if (d && d.sleepCancel) d.sleepCancel(function () {});
  }

  window.CleanFrameExtras = { KEY: KEY, all: all, get: get, set: set, onChange: onChange, clean: clean,
    clockText: clockText, clockStep: clockStep, summary: summary, sections: sections, applied: applied,
    owns: owns, title: title, parent: parent, HOLD_MS: HOLD_MS, Hold: Hold, appMenu: appMenu, appMenuLine: appMenuLine,
    activity: activity, keyUp: keyUp, wake: wake, visible: visible, dimNow: dimNow, dimmed: function () { return dimmed; },
    screenStart: screenStart, sleepMenu: sleepMenu, sleepIndex: sleepIndex, sleepSet: sleepSet,
    napping: function () { return napping; } };
}());

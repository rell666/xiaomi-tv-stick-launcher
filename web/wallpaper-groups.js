/* Wallpaper groups for CleanFrame's Background page, group pages and rotation (ours; ES5, no DOM).
   An entry of window.CleanFrameWallpapers belongs to its "group" (e.g. "space", from
   assets/wallpapers/space-catalog.js, or "cyberpunk", from cyberpunk-catalog.js); an entry without
   one (the upstream gallery) is "scenery".
   Photos the user uploaded (docs/PHOTO-GROUPS.md, contract 3) are merged in from the stick's
   library as entries "u-<gid>-<pid>" with their group and a `files` map of user/<gid>/<pid>/ paths.
   main.js (through tools/android-patch.py) uses this for:
     clean(walls)       the catalogue main.js uses: entries without a usable id or name, or whose
                        id repeats an earlier entry's, are dropped (one bad appended entry must not
                        break the page or shadow an upstream wallpaper)
     merge(base, lib)   base + the library's photos (valid ids, in a listed group), each a new
                        entry with upstream's defaults filled in, ordered by added, group, id;
                        base is not changed
     update(walls, base, lib, keep)
                        merge into `walls` IN PLACE (main.js closures keep the same array); an id in
                        `keep` that would vanish (the applied or shown wallpaper) stays, marked
                        `stale`, so the page never renders a wallpaper it cannot find; returns true
                        when the list changed
     list(walls, lib)   [{ id, label, count, builtin, created, thumb }] for the Background page:
                        Scenery, Space, Cyberpunk, then the user's groups by `created` (empty
                        ones included)
     groups(walls)      [{ id, label }] of the groups that have entries: scenery, space, cyberpunk, then any
                        other group in catalogue order
     members(walls, g)  the entries of group g, in catalogue order (stale entries left out)
     groupOf(w)         the group id of entry w
     isUser(w)          true for an uploaded photo (it can be removed)
     labelOf(lib, g)    the group's name: Scenery/Space/Cyberpunk, a user group's own name, else
                        label(g)
     src(w, kind)       THE image path of entry w: kind is wall, thumb, panel, blur, clear, frost
                        or soft; w.files when present, else upstream's id-based paths
     next(walls, id)    the id after `id` within its own group, wrapping round (no longer used by
                        rotation; kept for its tests and older callers)
     random(walls, id, rnd)
                        rotation (2026-11-03: "it starts on a random photo"): a random id of
                        `id`'s own group, never `id` itself when the group has another photo (a group
                        of one stays put); rnd() in [0, 1) defaults to Math.random (tests inject it)
     resolve(walls, id) a saved id if it is still in the catalogue, else the first Scenery
                        wallpaper (deleted photo or group, or a rollback without Space)
   Tested by tests/wallpaper-groups.test.js. */
(function () {
  'use strict';
  var DEFAULT = 'scenery';
  var LABELS = { scenery: 'Scenery', space: 'Space', cyberpunk: 'Cyberpunk' };
  var ORDER = ['scenery', 'space', 'cyberpunk'];
  var ID = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/, GROUP = /^[a-z0-9][a-z0-9-]{0,31}$/;
  var USER_GROUP = /^g[0-9a-z]{8}$/, PHOTO = /^p[0-9a-z]{10}$/;
  /* Upstream's file names (main.js builds them from the id) and the stick's (contract 1). */
  var UPSTREAM = { wall: 'assets/wallpapers/%.jpg', thumb: 'assets/wallpapers/%-thumb.jpg', panel: 'assets/wallpapers/%-panel.jpg',
    blur: 'assets/materials/%-field-blur.jpg', clear: 'assets/materials/%-field-clear.jpg',
    frost: 'assets/materials/%-field-frost.jpg', soft: 'assets/materials/%-study-soft.jpg' };
  var FILES = { wall: 'wall.jpg', thumb: 'thumb.jpg', panel: 'panel.jpg', blur: 'field-blur.jpg',
    clear: 'field-clear.jpg', frost: 'field-frost.jpg', soft: 'study-soft.jpg' };
  var INK = ['light', 'mist', 'dark'];

  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function groupOf(w) {
    return w && typeof w.group === 'string' && GROUP.test(w.group) ? w.group : DEFAULT;
  }
  function isUser(w) { return !!(w && w.files && typeof w.pid === 'string'); }
  function label(g) {
    return has(LABELS, g) ? LABELS[g] : g.charAt(0).toUpperCase() + g.slice(1).replace(/-/g, ' ');
  }
  function clean(walls) {
    var out = [], seen = {}, i, w;
    if (!walls || typeof walls.length !== 'number') return out;
    for (i = 0; i < walls.length; i++) {
      w = walls[i];
      if (!w || typeof w !== 'object' || typeof w.id !== 'string' || !ID.test(w.id)) continue;
      if (typeof w.name !== 'string' || !w.name) continue;
      if (has(seen, w.id)) continue;
      seen[w.id] = 1; out.push(w);
    }
    return out;
  }
  /* The library's groups that can hold photos: the built-ins and valid user groups, by id. */
  function libGroups(lib) {
    var out = {}, list = lib && lib.groups, i, g;
    ORDER.forEach(function (id) { out[id] = { id: id, name: LABELS[id], created: 0, builtin: true }; });
    if (!list || typeof list.length !== 'number') return out;
    for (i = 0; i < list.length; i++) {
      g = list[i];
      if (!g || typeof g.id !== 'string' || !USER_GROUP.test(g.id) || has(out, g.id)) continue;
      if (typeof g.name !== 'string' || !g.name.replace(/\s+/g, '')) continue;
      out[g.id] = { id: g.id, name: g.name, created: typeof g.created === 'number' && isFinite(g.created) ? g.created : 0, builtin: false };
    }
    return out;
  }
  function pick(v, list, fallback) { return list.indexOf(v) >= 0 ? v : fallback; }
  /* Adjust (web/photo-adjust.js): w.src is the photo's upright copy when it has one, w.crop the crop
     its wall was made with, w.edited when it was last adjusted; every file path then carries ?v=edited,
     so the WebView loads the new files under the same id. */
  function entry(p, groups) {
    var g = p && typeof p.group === 'string' ? p.group : '', w, ut, base, k, v, c;
    if (!p || typeof p.id !== 'string' || !PHOTO.test(p.id) || !has(groups, g)) return null;
    base = 'user/' + g + '/' + p.id + '/';
    ut = p.ut && typeof p.ut === 'object' ? p.ut : {};
    w = { id: 'u-' + g + '-' + p.id, pid: p.id, group: g,
      name: typeof p.name === 'string' && p.name.replace(/\s+/g, '') ? p.name.slice(0, 80) : 'Photo',
      added: typeof p.added === 'number' && isFinite(p.added) ? p.added : 0,
      tone: pick(p.tone, ['dark', 'light'], 'dark'),
      ut: { tr: pick(ut.tr, INK, 'light'), br: pick(ut.br, INK, 'light') },
      frostVeil: typeof p.frostVeil === 'number' && p.frostVeil >= 0 && p.frostVeil <= 0.44 ? p.frostVeil : 0.12,
      listColumn: pick(p.listColumn, [0, 2, 3], 0), dockQuiet: p.dockQuiet === true, clockMist: p.clockMist === true,
      files: {} };
    v = typeof p.edited === 'number' && isFinite(p.edited) && p.edited > 0 ? '?v=' + p.edited : '';
    for (k in FILES) if (has(FILES, k)) w.files[k] = base + FILES[k] + v;
    if (v) w.edited = p.edited;
    if (p.src === true) w.src = base + 'src.jpg' + v;
    c = p.crop;
    if (c && typeof c === 'object' && [c.x, c.y, c.z].every(function (n) { return typeof n === 'number' && isFinite(n); }) && c.z > 0) {
      w.crop = { x: c.x, y: c.y, z: c.z };
    }
    return w;
  }
  /* Library photos in the order UserLibrary.library() emits them: `added` ascending, then group
     order (scenery, space, cyberpunk, then user groups by created), then id. So a group page lists its
     uploads by `added`, after the group's catalogue entries. */
  function groupRank(groups) {
    var user = [], rank = {}, k;
    for (k in groups) if (has(groups, k) && !groups[k].builtin) user.push(groups[k]);
    user.sort(function (a, b) { return a.created - b.created || (a.id < b.id ? -1 : 1); });
    ORDER.concat(user.map(function (g) { return g.id; })).forEach(function (id, i) { rank[id] = i; });
    return rank;
  }
  function merge(base, lib) {
    var out = clean(base).slice(), groups = libGroups(lib), seen = {}, photos = lib && lib.photos, add = [], rank, i, w;
    out.forEach(function (x) { seen[x.id] = 1; });
    if (!photos || typeof photos.length !== 'number') return out;
    for (i = 0; i < photos.length; i++) {
      w = entry(photos[i], groups);
      if (!w || has(seen, w.id)) continue;
      seen[w.id] = 1; add.push(w);
    }
    rank = groupRank(groups);
    add.sort(function (a, b) { return a.added - b.added || rank[a.group] - rank[b.group] || (a.pid < b.pid ? -1 : a.pid > b.pid ? 1 : 0); });
    return out.concat(add);
  }
  function update(walls, base, lib, keep) {
    var merged = merge(base, lib), before = walls.map(function (w) { return w.id; }).join('\n');
    (keep || []).forEach(function (id) {
      var old = find(walls, id);
      if (old && !find(merged, id)) { old.stale = true; merged.push(old); }
    });
    walls.length = 0;
    Array.prototype.push.apply(walls, merged);
    return walls.map(function (w) { return w.id; }).join('\n') !== before;
  }
  function groups(walls) {
    var seen = [], rest = [], i, g;
    for (i = 0; i < walls.length; i++) {
      if (walls[i].stale) continue;
      g = groupOf(walls[i]);
      if (seen.indexOf(g) < 0) seen.push(g);
      if (ORDER.indexOf(g) < 0 && rest.indexOf(g) < 0) rest.push(g);
    }
    return ORDER.filter(function (id) { return seen.indexOf(id) >= 0; }).concat(rest)
      .map(function (id) { return { id: id, label: label(id) }; });
  }
  function members(walls, g) {
    return walls.filter(function (w) { return !w.stale && groupOf(w) === g; });
  }
  function labelOf(lib, g) {
    var gs = libGroups(lib);
    return has(gs, g) ? gs[g].name : label(g);
  }
  function list(walls, lib) {
    var gs = libGroups(lib), ids = groups(walls).map(function (g) { return g.id; }), user = [], k;
    for (k in gs) if (has(gs, k) && !gs[k].builtin) user.push(gs[k]);
    user.sort(function (a, b) { return a.created - b.created || (a.id < b.id ? -1 : 1); });
    ids = ORDER.filter(function (id) { return ids.indexOf(id) >= 0 || !!lib; })
      .concat(ids.filter(function (id) { return ORDER.indexOf(id) < 0 && !has(gs, id); }))
      .concat(user.map(function (g) { return g.id; }));
    return ids.map(function (id) {
      var m = members(walls, id), g = has(gs, id) ? gs[id] : { name: label(id), created: 0, builtin: true };
      return { id: id, label: g.name, count: m.length, builtin: g.builtin, created: g.created, thumb: m.length ? src(m[0], 'thumb') : '' };
    });
  }
  function src(w, kind) {
    if (!has(UPSTREAM, kind)) throw new Error('Unknown image kind: ' + kind);
    if (w && w.files && typeof w.files[kind] === 'string') return w.files[kind];
    return UPSTREAM[kind].replace('%', w && typeof w.id === 'string' ? w.id : '');
  }
  function find(walls, id) {
    for (var i = 0; i < walls.length; i++) if (walls[i].id === id) return walls[i];
    return null;
  }
  function next(walls, id) {
    var w = find(walls, id), list, i;
    if (!w) return walls.length ? walls[0].id : id;
    list = members(walls, groupOf(w));
    i = list.indexOf(w);
    return list.length ? list[(i + 1) % list.length].id : w.id;
  }
  function random(walls, id, rnd) {
    var w = find(walls, id), list, r;
    if (!w) return walls.length ? walls[0].id : id;
    list = members(walls, groupOf(w)).filter(function (x) { return x !== w; });
    if (!list.length) return w.id;
    r = Number((typeof rnd === 'function' ? rnd : Math.random)());
    if (!(r >= 0 && r < 1)) r = 0;
    return list[Math.floor(r * list.length)].id;
  }
  function resolve(walls, id) {
    var w = typeof id === 'string' ? find(walls, id) : null, first;
    if (w && !w.stale) return id;
    first = members(walls, DEFAULT)[0] || members(walls, groupOf(walls[0]))[0] || walls[0];
    return first.id;
  }
  window.CleanFrameWallpaperGroups = {
    DEFAULT: DEFAULT, clean: clean, merge: merge, update: update, list: list, groups: groups, members: members,
    groupOf: groupOf, isUser: isUser, label: label, labelOf: labelOf, src: src, find: find, next: next, random: random, resolve: resolve
  };
})();

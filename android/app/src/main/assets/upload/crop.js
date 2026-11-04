/* The crop arithmetic for uploaded photos (ours; ES5): PhotoMath's crop (Java,
   android/.../PhotoMath.java) in JavaScript, for the TV's Adjust screen (web/photo-adjust.js) and the
   phone's upload page (the same file, served to the phone as /static/crop.js; tests check the two
   copies are byte for byte the same). tests/upload/data/crop-vectors.json holds the answers Java and
   this file must both give.
   A crop is { x, y, z } in the displayed (upright) picture: z = 1 is the largest 16:9 frame inside the
   picture, more zooms in (up to Z_MAX), less zooms out down to the whole picture (fitZoom; the rest of
   the TV's wall is black); x, y are the frame's centre as fractions of the picture, kept so the frame
   stays on it (centred on a side where it is larger than the picture).
   window.CleanFramePhotoCrop:
     WALL_W, WALL_H, Z_MAX, SRC_MAX_SIDE, SRC_MAX_PIXELS
     cover(w, h)        { w, h }: the 16:9 frame at zoom 1
     fitZoom(w, h)      the zoom that shows the whole picture
     clamp(w, h, c)     c (null: the centre) made valid: { x, y, z }
     frame(w, h, c)     { x, y, w, h }: the frame in the picture's pixels
     place(w, h, c)     [sx, sy, sw, sh, dx, dy, dw, dh]: the whole pixels inside the frame and where
                        they go on the 1920x1080 wall
     move(w, h, c, dx, dy)  c moved by dx, dy (fractions of the frame), clamped
     zoomBy(w, h, c, f) c zoomed by the factor f about its centre, clamped
     soft(w, h, c)      true when the frame is narrower than the wall (that crop will look softer)
     field(c)           "x,y,z" as the TV and the upload take it (6 decimals)
     sendSize(w, h)     { w, h }: the phone's upright copy, at most SRC_MAX_SIDE and SRC_MAX_PIXELS */
(function (root) {
  'use strict';
  var WALL_W = 1920, WALL_H = 1080, Z_MAX = 4, SRC_MAX_SIDE = 4096, SRC_MAX_PIXELS = 4096 * 3072;

  function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : d; }
  function cover(w, h) {
    return w * WALL_H >= h * WALL_W ? { w: h * WALL_W / WALL_H, h: h } : { w: w, h: w * WALL_H / WALL_W };
  }
  function fitZoom(w, h) { var c = cover(w, h); return Math.min(c.w / w, c.h / h); }
  function axis(v, span) {
    if (span >= 1 || typeof v !== 'number' || v !== v) return 0.5;
    return Math.max(span / 2, Math.min(1 - span / 2, v));
  }
  function clamp(w, h, c) {
    var c0 = cover(w, h), z;
    c = c || { x: 0.5, y: 0.5, z: 1 };
    z = Math.max(fitZoom(w, h), Math.min(Z_MAX, num(c.z, 1)));
    return { x: axis(c.x, c0.w / z / w), y: axis(c.y, c0.h / z / h), z: z };
  }
  function frame(w, h, c) {
    var k = clamp(w, h, c), c0 = cover(w, h), fw = c0.w / k.z, fh = c0.h / k.z;
    return { x: k.x * w - fw / 2, y: k.y * h - fh / 2, w: fw, h: fh };
  }
  function place(w, h, c) {
    var f = frame(w, h, c), k = WALL_W / f.w;
    var sx = Math.max(0, Math.floor(f.x + 1e-6)), sy = Math.max(0, Math.floor(f.y + 1e-6));
    var ex = Math.min(w, Math.ceil(f.x + f.w - 1e-6)), ey = Math.min(h, Math.ceil(f.y + f.h - 1e-6));
    var sw = Math.max(1, ex - sx), sh = Math.max(1, ey - sy);
    return [sx, sy, sw, sh, (sx - f.x) * k, (sy - f.y) * k, sw * k, sh * k];
  }
  function move(w, h, c, dx, dy) {
    var k = clamp(w, h, c), c0 = cover(w, h);
    return clamp(w, h, { x: k.x + dx * c0.w / k.z / w, y: k.y + dy * c0.h / k.z / h, z: k.z });
  }
  function zoomBy(w, h, c, f) {
    var k = clamp(w, h, c);
    return clamp(w, h, { x: k.x, y: k.y, z: k.z * f });
  }
  function soft(w, h, c) { return frame(w, h, c).w < WALL_W - 0.5; }
  function six(v) { return String(Math.round(v * 1e6) / 1e6); }
  function field(c) { return six(c.x) + ',' + six(c.y) + ',' + six(c.z); }
  function sendSize(w, h) {
    var s = Math.min(1, SRC_MAX_SIDE / Math.max(w, h), Math.sqrt(SRC_MAX_PIXELS / (w * h)));
    return { w: Math.max(1, Math.floor(w * s)), h: Math.max(1, Math.floor(h * s)) };
  }

  root.CleanFramePhotoCrop = { WALL_W: WALL_W, WALL_H: WALL_H, Z_MAX: Z_MAX, SRC_MAX_SIDE: SRC_MAX_SIDE, SRC_MAX_PIXELS: SRC_MAX_PIXELS,
    cover: cover, fitZoom: fitZoom, clamp: clamp, frame: frame, place: place, move: move, zoomBy: zoomBy, soft: soft, field: field,
    sendSize: sendSize };
})(window);

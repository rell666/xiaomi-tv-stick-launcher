"""Measure visible SVG ink the same way CleanFrame's scripts/build-icon-metrics.mjs does:
render at 240x240 (xMidYMid meet), bbox of alpha > 40, fill = sum(alpha/255) / bbox area.
An SVG with no pixel above the threshold (blank, fully transparent, or failing to render) has no
bbox: that is an error, never a metric (it used to print w=-240 h=-240 without complaint).
resvg_py and pillow are imported only to render (measure); ink_metrics needs neither, so
tests/icon_metrics.test.py can run without them."""
import io, json, pathlib, re, sys

class NoInk(ValueError):
    pass

def ink_metrics(alpha, size):
    """alpha(x, y) -> 0..255 over a size x size image. Returns the metrics dict, or raises NoInk."""
    left, top, right, bottom, ink = size, size, -1, -1, 0.0
    for y in range(size):
        for x in range(size):
            v = alpha(x, y); ink += v / 255
            if v > 40:
                left, top, right, bottom = min(left, x), min(top, y), max(right, x), max(bottom, y)
    if right < left or bottom < top:
        raise NoInk('no pixel with alpha > 40 at %dx%d (blank or transparent SVG?)' % (size, size))
    w, h = right - left + 1, bottom - top + 1
    return {'n': size, 'x': left, 'y': top, 'w': w, 'h': h, 'fill': ink / (w * h)}

def measure(path, size=240):
    import resvg_py
    from PIL import Image
    src = open(path, encoding='utf-8').read()
    src = re.sub(r'<svg\b', '<svg width="%d" height="%d" preserveAspectRatio="xMidYMid meet"' % (size, size), src, count=1)
    png = resvg_py.svg_to_bytes(svg_string=src, width=size, height=size)
    px = Image.open(io.BytesIO(bytes(png))).convert('RGBA').getchannel('A').load()
    return ink_metrics(lambda x, y: px[x, y], size)

def main(paths):
    out = {}
    for p in paths:
        # The mark id is the file's stem; only .svg files are marks (p[:-4] took 4 characters off any
        # name, so x/emby.png measured as "emby" if it rendered at all).
        path = pathlib.PurePath(p)
        if path.suffix.lower() != '.svg' or not path.stem:
            sys.exit('icon_metrics: %s: not an .svg file (want <id>.svg)' % p)
        try:
            out[path.stem] = measure(p)
        except NoInk as e:
            sys.exit('icon_metrics: %s: %s' % (p, e))
    print(json.dumps(out))

if __name__ == '__main__':
    main(sys.argv[1:])

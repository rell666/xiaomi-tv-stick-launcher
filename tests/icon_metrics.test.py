"""Offline tests for tools/icon_metrics.py's bounds logic (ink_metrics): a blank/transparent render
is an error, never a negative bbox; main() takes the mark id from an .svg file's stem and refuses any
other file. No resvg_py/pillow needed: the alpha channel is a function and measure() is stubbed.
Usage (from the repo root): python3 -I tests/icon_metrics.test.py"""
import importlib.util, io, json, pathlib, sys, contextlib

root = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('icon_metrics', root / 'tools' / 'icon_metrics.py')
im = importlib.util.module_from_spec(spec)
spec.loader.exec_module(im)   # must not need resvg_py or pillow
fails = 0

def case(name, ok, detail=''):
    global fails
    if ok: print('PASS', name)
    else: fails += 1; print('FAIL %s: %s' % (name, detail))

def raises_noink(alpha, size):
    try: return False, im.ink_metrics(alpha, size)
    except im.NoInk as e: return True, str(e)

ok, got = raises_noink(lambda x, y: 0, 240)
case('blank-svg-is-an-error', ok, 'got %r' % (got,))
ok, got = raises_noink(lambda x, y: 40, 16)   # faint everywhere: at the threshold, not above it
case('below-threshold-is-an-error', ok, 'got %r' % (got,))

m = im.ink_metrics(lambda x, y: 255 if (x, y) == (10, 20) else 0, 240)
case('one-pixel-bbox', m == {'n': 240, 'x': 10, 'y': 20, 'w': 1, 'h': 1, 'fill': 1.0}, repr(m))
m = im.ink_metrics(lambda x, y: 255 if 2 <= x <= 5 and 3 <= y <= 4 else 0, 8)
case('rect-bbox-and-fill', (m['x'], m['y'], m['w'], m['h'], m['fill']) == (2, 3, 4, 2, 1.0), repr(m))

# main() turns NoInk into a non-zero exit naming the file (measure stubbed: no renderer here).
im.measure = lambda p, size=240: im.ink_metrics(lambda x, y: 0, 8)
out = io.StringIO()
try:
    with contextlib.redirect_stdout(out): im.main(['x/blank.svg'])
    case('main-exits-on-blank', False, 'main returned and printed %r' % out.getvalue())
except SystemExit as e:
    case('main-exits-on-blank', isinstance(e.code, str) and 'x/blank.svg' in e.code and not out.getvalue(), repr(e.code))

# The mark id is the file's stem, and only an .svg is measured: the old p[:-4] cut any 4 characters
# (x/emby.png measured as "emby", x/emby as "").
im.measure = lambda p, size=240: {'n': size, 'path': p}
for bad in ('x/emby.png', 'x/emby', 'x/.svg', 'x/emby.svgz'):
    out = io.StringIO()
    try:
        with contextlib.redirect_stdout(out): im.main([bad])
        case('main-refuses-non-svg %s' % bad, False, 'main returned and printed %r' % out.getvalue())
    except SystemExit as e:
        case('main-refuses-non-svg %s' % bad, isinstance(e.code, str) and bad in e.code and 'not an .svg' in e.code and not out.getvalue(), repr(e.code))
out = io.StringIO()
with contextlib.redirect_stdout(out): im.main(['x/y/a.b.svg', 'x/IPTV.SVG'])
got = json.loads(out.getvalue())
case('main-id-is-the-stem', sorted(got) == ['IPTV', 'a.b'] and got['a.b']['path'] == 'x/y/a.b.svg', repr(got))

print('ICON_METRICS_TEST_OK' if not fails else 'ICON_METRICS_TEST_FAIL %d' % fails)
sys.exit(1 if fails else 0)

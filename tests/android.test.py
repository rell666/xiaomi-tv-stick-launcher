#!/usr/bin/env python3
"""Offline tests for the Android side (android/): no JVM, no device. They read the source and
manifests and assert the contracts the stick depends on:
  - the AndroidHome bridge: MainActivity.Bridge's @JavascriptInterface methods are exactly the ones
    web/device.js calls (tv.x(, api().x(, and by name through photoCall('x', ...) / parse('x', ...)),
    so a rename on either side fails here instead of on the TV;
  - MIME coverage: every file extension under web/ has its own type in MainActivity.mime(), and
    binary types are served without a character encoding;
  - the origin lock: https://cleanframe.local only, no file/content access, network loads blocked
    (DEVICE-CHECK 7), Safe Browsing and metrics off; INTERNET is held (for the upload page's
    listening socket, contract 4) but no outgoing-connection API appears anywhere in android/;
  - the failure-fallback constants (in FallbackPolicy.java, whose behaviour tests/fallback.test.sh
    runs), that FallbackPolicy stays plain Java and that MainActivity delegates to it rather than
    keeping its own counts; and the staging manifest (never a Home app);
  - page contracts: READY_JS's selector is one web/main.js renders, and every FrameDevice.<name>
    MainActivity runs via evaluateJavascript is exported by web/device.js;
  - the hang watch: the WebViewRenderProcessClient is installed only behind SDK >= 29 and handles
    both the unresponsive and responsive callbacks;
  - build.gradle: a failed `git status` fails the build instead of reading as a clean tree;
  - photo groups (docs/PHOTO-GROUPS.md contract 2): the bridge has exactly the spec's methods (names
    and arity) and none can throw to the page; web/device.js calls all of them; the /home/user/ route serves only what UserLibrary.photoFile() allows, as image/jpeg, else
    404; R is named in one method (DEVICE-CHECK 1); no upload token or upload server outlives the
    screen, and stopping it never waits on the library (UploadGate); the upload address is the stick's LAN IPv4 (UploadServer.pickAddress, port 8097), no
    Gradle URL; UserLibrary stays plain Java and tests/fallback.test.sh compiles it;
  - Settings > Screen and the Sleep button: the bridge calls never throw to the page, SleepControl is
    a receiver nobody else can send to, Home re-applies the timeout on every resume, ScreenPolicy stays
    plain Java and tests/fallback.test.sh compiles it, and the screensaver bridge is gone;
  - uploads to the stick (contract 4, v2): the material bridge (materialJobs, saveMaterial) and the
    /home/user-tmp/ route; UploadServer, PendingPhotos, PhotoMath, Jpeg, Readability and
    PhotoProcessor stay plain Java and tests/upload.test.sh compiles them; photos are prepared on one
    background thread; start-up removes stale unfinished photos, on its own thread (never the UI
    thread) and under the library's lock; onResume's SleepControl calls cannot crash Home;
  - the app's shell tests (tests/fallback.test.sh, tests/upload.test.sh): no malformed "# shellcheck"
    directive and no "NAME= value" (shellcheck's SC1073/SC1007, checked by pattern).
Python 3 standard library only. Run from the repo root:
    python3 -I tests/android.test.py
Prints one line per case (and a total) and exits non-zero on any failure."""
import pathlib, re, sys, time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA_REL = 'android/app/src/main/java/home/cleanframe/tv/MainActivity.java'
POLICY_REL = 'android/app/src/main/java/home/cleanframe/tv/FallbackPolicy.java'
ICON_REL = 'android/app/src/main/java/home/cleanframe/tv/IconCache.java'
LIB_REL = 'android/app/src/main/java/home/cleanframe/tv/UserLibrary.java'
SRV_REL = 'android/app/src/main/java/home/cleanframe/tv/UploadServer.java'
GATE_REL = 'android/app/src/main/java/home/cleanframe/tv/UploadGate.java'
PENDING_REL = 'android/app/src/main/java/home/cleanframe/tv/PendingPhotos.java'
UPLOAD_TEST_REL = 'tests/upload.test.sh'
# Plain-Java classes of the upload side (tests/upload.test.sh compiles them without Android).
PLAIN_UPLOAD = ['UploadServer', 'PendingPhotos', 'PhotoMath', 'Jpeg', 'Readability', 'PhotoProcessor']
# Bridge methods of contract 4 (v2); web/device.js calls both (through photoCall), checked like the rest.
MATERIAL_BRIDGE = {'materialJobs': 0, 'saveMaterial': 4}
# Every permission the app holds, in manifest order: INTERNET (contract 4's listening socket), then
# SleepControl's: a screen wake lock while something plays and the screen-off timeout (an app-op
# granted over adb; no device admin, 2026-11-01). SYSTEM_ALERT_WINDOW left with the timer's banner.
# Then Settings > Bluetooth's list of bonded devices: BLUETOOTH (11 and older), BLUETOOTH_CONNECT (12+,
# granted over adb with pm grant). No SCAN, ADMIN or location permission: the app pairs and connects nothing.
# Then the long-press menu's Uninstall: REQUEST_DELETE_PACKAGES opens Android's own dialog and removes nothing.
PERMISSIONS = ['INTERNET', 'WAKE_LOCK', 'WRITE_SETTINGS', 'SCHEDULE_EXACT_ALARM', 'USE_EXACT_ALARM', 'BLUETOOTH', 'BLUETOOTH_ADMIN', 'BLUETOOTH_CONNECT', 'REQUEST_DELETE_PACKAGES']
# The Bluetooth bridge (contract given to the web side 2026-11-03; names and arity fixed).
BLUETOOTH_BRIDGE = {'bluetooth': 0, 'bluetoothPair': 0, 'bluetoothUnpair': 1}
# The Uninstall bridge (contract given to the web side 2026-11-04; names and arity fixed).
UNINSTALL_BRIDGE = {'canUninstall': 1, 'uninstall': 1}
UNINSTALL_REL = 'android/app/src/main/java/home/cleanframe/tv/UninstallPolicy.java'
BLUETOOTH_REL = 'android/app/src/main/java/home/cleanframe/tv/BluetoothReport.java'
BONDS_REL = 'android/app/src/main/java/home/cleanframe/tv/BluetoothBonds.java'
PROXIES_REL = 'android/app/src/main/java/home/cleanframe/tv/ProfileProxies.java'
SLEEP_REL = 'android/app/src/main/java/home/cleanframe/tv/SleepControl.java'
SCREEN_REL = 'android/app/src/main/java/home/cleanframe/tv/ScreenPolicy.java'
SPEC_REL = 'docs/PHOTO-GROUPS.md'
FALLBACK_TEST_REL = 'tests/fallback.test.sh'
DEVICE_REL = 'web/device.js'
MANIFEST_REL = 'android/app/src/main/AndroidManifest.xml'
STAGING_REL = 'android/app/src/staging/AndroidManifest.xml'
A = '{http://schemas.android.com/apk/res/android}'
T = '{http://schemas.android.com/tools}'

# Extensions under web/ that may lack a line in mime(), each with the reason. Empty on purpose:
# mime()'s fallback is application/octet-stream, which is wrong for anything the page loads.
MIME_ALLOW = {
}


class Fail(Exception):
    pass


def check(cond, file, msg):
    if not cond:
        raise Fail('%s: %s' % (file, msg))


def read(rel):
    try:
        return (ROOT / rel).read_text(encoding='utf-8')
    except OSError as e:
        raise Fail('%s: cannot read (%s)' % (rel, e.strerror))


def strip_comments(src):
    """Drop // and /* */ comments, keeping string and char literals intact (Java and our ES5 JS,
    which has no regex literals)."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c in '"\'':
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1])
            i = j + 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            out.append(' ')
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def block_after(src, pattern, file, what):
    """The text between the braces of the first block whose header matches pattern."""
    m = re.search(pattern, src)  # every pattern ends at the opening brace
    check(m, file, 'cannot find %s' % what)
    start = m.end() - 1
    depth = 0
    for i in range(start, len(src)):
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
    raise Fail('%s: unbalanced braces in %s' % (file, what))


def method(src, name):
    return block_after(src, r'\b%s\s*\([^)]*\)\s*(?:throws [\w.,\s]+)?\{' % re.escape(name), JAVA_REL,
                       'method %s()' % name)


_java = _policy = None


def java():
    global _java
    if _java is None:
        _java = strip_comments(read(JAVA_REL))
    return _java


def policy():
    global _policy
    if _policy is None:
        _policy = strip_comments(read(POLICY_REL))
    return _policy


def mime_table():
    """[(extension, type)] from mime(): one `if (p.endsWith(".x") || ...) return "type";` per line."""
    body = method(java(), 'mime')
    check('toLowerCase' in body, JAVA_REL, 'mime() no longer lower-cases the path; .JPG would fall through')
    table = []
    for cond, typ in re.findall(r'if\s*\((.*?)\)\s*return\s*"([^"]+)"\s*;', body):
        exts = re.findall(r'p\.endsWith\("\.([^"]+)"\)', cond)
        check(exts, JAVA_REL, 'mime() line without p.endsWith(".ext"): if (%s)' % cond)
        table += [(e, typ) for e in exts]
    check(table, JAVA_REL, 'mime() has no `if (p.endsWith(".x")) return "type";` lines')
    return table


def mime_of(ext):
    for e, typ in mime_table():
        if ext.endswith('.' + e):  # first match wins, as in Java
            return typ
    return None


def charset_of(typ):
    """Evaluate charset() for typ from its startsWith("...")/equals("...") tests."""
    body = method(java(), 'charset')
    prefixes = re.findall(r'mime\.startsWith\("([^"]+)"\)', body)
    exact = re.findall(r'mime\.equals\("([^"]+)"\)', body)
    check(prefixes or exact, JAVA_REL, 'charset() has no mime.startsWith/equals tests to read')
    return 'utf-8' if any(typ.startswith(p) for p in prefixes) or typ in exact else None


def const(name, rel=POLICY_REL):
    src = policy() if rel == POLICY_REL else java()
    m = re.search(r'static\s+final\s+(?:long|int)\s+%s\s*=\s*([0-9_ *+L]+);' % name, src)
    check(m, rel, 'constant %s is missing or not a plain int/long expression' % name)
    return eval(m.group(1).replace('_', '').replace('L', ''), {'__builtins__': {}})


def manifest(rel):
    try:
        return ET.parse(str(ROOT / rel)).getroot()
    except (OSError, ET.ParseError) as e:
        raise Fail('%s: cannot parse (%s)' % (rel, e))


def categories(filt):
    return {c.get(A + 'name') for c in filt.findall('category')}


# ---------- Bridge contract ----------

def bridge_methods():
    body = block_after(java(), r'class\s+Bridge\b[^{]*\{', JAVA_REL, 'class Bridge')
    return set(re.findall(r'@JavascriptInterface\s+public\s+[\w<>\[\]]+\s+(\w+)\s*\(', body))


def device_calls():
    src = strip_comments(read(DEVICE_REL))
    # Every way device.js reaches the bridge must be one this test reads: api() (returns
    # window.AndroidHome) and the `tv` argument call() passes it.
    refs = re.findall(r'\bAndroidHome\b', src)
    check(len(refs) == 1 and re.search(r'function api\(\)\s*\{\s*return window\.AndroidHome;\s*\}', src), DEVICE_REL,
          'AndroidHome is used outside api(); teach tests/android.test.py the new access pattern')
    check(re.search(r'run\(api\(\)\)', src), DEVICE_REL, 'call() no longer passes api() to its callback')
    for param in re.findall(r'call\(function \((\w+)\)', src):
        check(param == 'tv', DEVICE_REL, 'a call() callback names the bridge %r, not tv' % param)
    names = set(re.findall(r'(?:\bapi\(\)|\btv)\.(\w+)\s*\(', src))
    # The photo and material methods are reached by name (review 687edfd: this test read only the
    # dotted forms, so it said "device.js calls none" of the nine it does call): photoCall('<name>', ...)
    # -> parse(name, args) -> tv[name].apply(tv, args). That path, and only that one, is read here.
    dyn = re.findall(r'(?:\bapi\(\)|\btv)\s*\[', src)
    if dyn or re.search(r'(?<![\w.])(?:parse|photoCall)\(', src):
        parse_fn = block_after(src, r'function parse\((\w+), (\w+)\)\s*\{', DEVICE_REL, 'function parse(name, args)')
        check(len(re.findall(r'(?:\bapi\(\)|\btv)\s*\[', parse_fn)) == len(dyn), DEVICE_REL,
              'the bridge is indexed by name outside parse(): teach tests/android.test.py the new access pattern')
        check(re.search(r'\btv\[name\]\.apply\(tv,\s*args\)', parse_fn), DEVICE_REL, 'parse() no longer calls tv[name].apply(tv, args)')
        photo_fn = block_after(src, r'function photoCall\((\w+), (\w+), (\w+), (\w+)\)\s*\{', DEVICE_REL, 'function photoCall(name, ...)')
        for fn, args in (('parse', re.findall(r'(?<![\w.])(?<!function )parse\(\s*([^,)]*)', src)),
                         ('photoCall', re.findall(r'(?<![\w.])(?<!function )photoCall\(\s*([^,)]*)', src))):
            inner = re.findall(r'(?<![\w.])parse\(\s*([^,)]*)', photo_fn) if fn == 'parse' else []
            for a in args:
                if a == 'name' and a in inner:
                    continue  # photoCall's own parse(name, args): its callers' literals are read below
                check(re.fullmatch(r"'\w+'", a), DEVICE_REL,
                      '%s(%s, ...) names the bridge method by a non-literal: teach tests/android.test.py' % (fn, a))
                names.add(a.strip("'"))
        check(re.findall(r'(?<![\w.])parse\(\s*([^,)]*)', photo_fn) == ['name'], DEVICE_REL, 'photoCall() does not parse(name, args)')
    return names


def test_bridge_has_methods():
    m = bridge_methods()
    check(m, JAVA_REL, 'Bridge has no @JavascriptInterface public methods')
    return 'Bridge: ' + ', '.join(sorted(m))


def test_bridge_matches_device_js():
    java_side, js_side = bridge_methods(), device_calls()
    check(js_side, DEVICE_REL, 'no AndroidHome calls found (api().x( / tv.x( / photoCall(\'x\' forms)')
    # No tolerance (review 687edfd): the web side of the photo and material bridges has landed, so every
    # Bridge method must be called and every call must exist, both ways.
    check(not js_side - java_side, JAVA_REL,
          'Bridge lacks %s, which web/device.js calls' % ', '.join(sorted(js_side - java_side)))
    check(not java_side - js_side, DEVICE_REL,
          'never calls Bridge.%s (unused bridge surface, or a rename)' % ', '.join(sorted(java_side - js_side)))
    return '%d methods, each called by web/device.js' % len(java_side)


def test_bridge_only_annotated_public():
    body = block_after(java(), r'class\s+Bridge\b[^{]*\{', JAVA_REL, 'class Bridge')
    public = set(re.findall(r'public\s+[\w<>\[\]]+\s+(\w+)\s*\(', body))
    check(public == bridge_methods(), JAVA_REL,
          'Bridge public methods without @JavascriptInterface: %s' % ', '.join(sorted(public - bridge_methods())))


# ---------- MIME ----------

def web_extensions():
    found = {}
    for path in sorted((ROOT / 'web').rglob('*')):
        if path.is_file():
            ext = path.suffix.lower().lstrip('.')
            found.setdefault(ext, str(path.relative_to(ROOT)))
    return found


def test_mime_covers_web():
    exts = web_extensions()
    check(exts, 'web/', 'no files found')
    missing = ['.%s (e.g. %s)' % (e or '<none>', f) for e, f in sorted(exts.items())
               if e not in MIME_ALLOW and mime_of('.' + e if e else '') is None]
    check(not missing, JAVA_REL, 'mime() has no type for %s' % '; '.join(missing))
    return '%d extensions under web/: %s' % (len(exts), ' '.join(sorted(exts)))


def test_mime_known_types():
    want = {'ttf': 'font/ttf', 'woff2': 'font/woff2', 'html': 'text/html', 'js': 'application/javascript',
            'css': 'text/css', 'svg': 'image/svg+xml', 'png': 'image/png', 'jpg': 'image/jpeg'}
    for ext, typ in sorted(want.items()):
        got = mime_of('.' + ext)
        check(got == typ, JAVA_REL, 'mime() gives .%s %s, want %s' % (ext, got, typ))
    m = re.search(r'return\s*"([^"]+)"\s*;\s*$', method(java(), 'mime').strip())
    check(m and m.group(1) == 'application/octet-stream', JAVA_REL,
          'mime() fallback is %s, want application/octet-stream' % (m and m.group(1)))


def test_binary_types_have_no_charset():
    body = method(java(), 'shouldInterceptRequest')
    check(re.search(r'new WebResourceResponse\(\s*(\w+)\s*,\s*charset\(\s*\1\s*\)', body), JAVA_REL,
          'shouldInterceptRequest serves assets without charset(type); fonts/images would get "utf-8"')
    for ext, typ in mime_table():
        text = typ.startswith('text/') or typ in ('application/javascript', 'application/json',
                                                   'application/xml', 'image/svg+xml')
        got = charset_of(typ)
        check(got == ('utf-8' if text else None), JAVA_REL,
              'charset(%s) for .%s is %r, want %r' % (typ, ext, got, 'utf-8' if text else None))


# ---------- Origin lock ----------

def test_origin_constants():
    src = java()
    check(re.search(r'static\s+final\s+String\s+HOST\s*=\s*"cleanframe\.local"\s*;', src), JAVA_REL,
          'HOST is not "cleanframe.local"')
    check(re.search(r'static\s+final\s+String\s+START\s*=\s*"https://"\s*\+\s*HOST\s*\+\s*"/home/', src), JAVA_REL,
          'START is not "https://" + HOST + "/home/..."')


def lock_checks(body):
    """(scheme, host) checked by this method body, directly or through ours()."""
    if re.search(r'\bours\(', body):
        body = body + method(java(), 'ours')
    return (bool(re.search(r'"https"\.equals\(\s*\w+\.getScheme\(\)\s*\)', body)),
            bool(re.search(r'HOST\.equals\(\s*\w+(?:\.getUrl\(\))?\.getHost\(\)\s*\)', body)))


def test_both_methods_lock_scheme_and_host():
    for name in ('shouldInterceptRequest', 'shouldOverrideUrlLoading'):
        scheme, host = lock_checks(method(java(), name))
        check(scheme, JAVA_REL, '%s() does not require the https scheme' % name)
        check(host, JAVA_REL, '%s() does not require HOST' % name)
    body = method(java(), 'shouldInterceptRequest')
    check(re.search(r'if\s*\(\s*!ours\(\w+\)[^;]*return\s+status\(403', body), JAVA_REL,
          'shouldInterceptRequest() does not answer 403 for a foreign origin')
    check(re.search(r'return\s+!ours\(', method(java(), 'shouldOverrideUrlLoading')), JAVA_REL,
          'shouldOverrideUrlLoading() does not cancel navigation to a foreign origin')


def test_webview_settings():
    src = java()
    for call in ('setAllowFileAccess(false)', 'setAllowContentAccess(false)', 'setBlockNetworkLoads(true)'):
        check(call in src, JAVA_REL, 'missing s.%s' % call)
    check(not re.search(r'setBlockNetworkLoads\(\s*false', src), JAVA_REL, 'unblocks network loads')
    # DEVICE-CHECK 7: whether the page still loads with network loads blocked is checked on the stick;
    # the comment above the call must say so (drop the line if the check fails).
    raw = read(JAVA_REL)
    at = raw.find('setBlockNetworkLoads(true)')
    check('DEVICE-CHECK 7' in raw[max(0, at - 600):at], JAVA_REL, 'the comment above setBlockNetworkLoads(true) does not name DEVICE-CHECK 7')
    for bad in (r'setAllowFileAccess\(true\)', r'setAllowContentAccess\(true\)',
                r'setAllowFileAccessFromFileURLs\(true\)', r'setAllowUniversalAccessFromFileURLs\(true\)',
                r'MIXED_CONTENT_ALWAYS_ALLOW'):
        check(not re.search(bad, src), JAVA_REL, 'widens the origin lock: %s' % bad.replace('\\', ''))


# Every API that opens a connection FROM the app (contract 4: "it does not go online"). ServerSocket
# (and the Socket accept() returns) is allowed; nothing may dial out.
OUTGOING = [
    (r'\bopenConnection\s*\(', 'URL.openConnection'),
    (r'\bopenStream\s*\(', 'URL.openStream'),
    (r'\bHttpURLConnection\b', 'HttpURLConnection'),
    (r'\bHttpsURLConnection\b', 'HttpsURLConnection'),
    (r'\bURLConnection\b', 'URLConnection'),
    (r'\bnew\s+Socket\s*\(', 'new Socket('),
    (r'\bSSLSocket', 'SSLSocket'),
    (r'\bSocketFactory\b', 'SocketFactory'),
    (r'\bSocketChannel\b', 'SocketChannel'),
    (r'\bDatagramSocket\b', 'DatagramSocket'),
    (r'\bDatagramChannel\b', 'DatagramChannel'),
    (r'\bMulticastSocket\b', 'MulticastSocket'),
    (r'\.connect\s*\(', '.connect('),
    (r'\bokhttp3?\b|\bOkHttp', 'OkHttp'),
    (r'\bjava\.net\.http\b|\bHttpClient\b', 'java.net.http / HttpClient'),
    (r'\bDownloadManager\b', 'DownloadManager'),
    (r'\bVolley\b|\bRetrofit\b|\bCronet', 'an HTTP library'),
    # Bluetooth (2026-11-03): the app reads bonded devices and their links only. No socket, no
    # discovery or scan, no GATT or L2CAP connection, no pairing of its own (TV Settings' page pairs).
    (r'\bBluetoothSocket\b|\bBluetoothServerSocket\b', 'BluetoothSocket'),
    (r'\b(?:create|listenUsing)\w*Rfcomm\w*\s*\(', 'createRfcomm* / listenUsingRfcomm*'),
    (r'\b(?:create|listenUsing)\w*L2cap\w*\s*\(', 'L2CAP channel'),
    (r'\bstartDiscovery\s*\(', 'startDiscovery'),
    (r'\bBluetoothLeScanner\b|\bstartLeScan\s*\(|\bstartScan\s*\(', 'a Bluetooth scan'),
    (r'\bconnectGatt\s*\(', 'connectGatt'),
    (r'\bcreateBond\s*\(', 'createBond'),
    # Unpairing goes through the one reflective lookup test_bluetooth_unpair checks, never a direct call.
    (r'\.removeBond\s*\(', 'a direct removeBond()'),
    # getUuids() reads what Android already knows; this one connects to the device to ask.
    (r'\bfetchUuidsWithSdp\s*\(', 'fetchUuidsWithSdp'),
]


def android_sources():
    found = sorted(p for p in (ROOT / 'android').rglob('*') if p.suffix in ('.java', '.kt')
                   and 'build' not in p.relative_to(ROOT / 'android').parts)
    check(found, 'android/', 'no Java sources found')
    return found


def test_manifest_internet_but_no_outgoing_connections():
    root = manifest(MANIFEST_REL)
    perms = [p.get(A + 'name') or '' for p in root.iter() if p.tag.startswith('uses-permission')]
    check(perms == ['android.permission.' + n for n in PERMISSIONS], MANIFEST_REL,
          'permissions are %s; want exactly %s (INTERNET for the upload page\'s listening socket, the rest for '
          'Settings > Screen and the Sleep button)' % (perms, ', '.join(PERMISSIONS)))
    hits = []
    for f in android_sources():
        src = strip_comments(f.read_text(encoding='utf-8'))
        for rx, what in OUTGOING:
            for m in re.finditer(rx, src):
                hits.append('%s: %s (line %d)' % (f.relative_to(ROOT), what, src.count('\n', 0, m.start()) + 1))
    check(not hits, 'android/', 'outgoing-connection API: ' + '; '.join(hits))
    srv = strip_comments(read(SRV_REL))
    check(re.search(r'\.bind\(\s*new\s+InetSocketAddress\(\s*addr\s*,\s*port\s*\)', srv) and 'new ServerSocket()' in srv, SRV_REL,
          'UploadServer does not bind an unbound ServerSocket to the one address it is given')
    check(not re.search(r'new\s+ServerSocket\(\s*\w', srv), SRV_REL, 'a ServerSocket bound in its constructor (the wildcard address)')
    # the checks themselves must catch what they claim
    for bad in ('new URL(u).openConnection()', 'new Socket("h", 1)', 'SocketChannel.open(a)', 'new DatagramSocket()',
                'HttpClient.newHttpClient()', 's.connect(a)', 'new okhttp3.OkHttpClient()',
                'BluetoothSocket s', 'd.createRfcommSocketToServiceRecord(u)', 'd.createInsecureRfcommSocketToServiceRecord(u)',
                'a.listenUsingRfcommWithServiceRecord(n, u)', 'a.startDiscovery()', 'a.getBluetoothLeScanner().startScan(cb)',
                'd.connectGatt(c, false, cb)', 'a2dp.connect(d)', 'd.createBond()', 'd.createL2capChannel(5)', 'd.removeBond()', 'd.fetchUuidsWithSdp()'):
        check(any(re.search(rx, bad) for rx, _ in OUTGOING), 'tests/android.test.py', 'the outgoing-API list misses ' + bad)
    for good in ('new ServerSocket()', 'server.accept()', 'Socket c = s.accept();', 'InetSocketAddress',
                 'a.getBondedDevices()', 'a.getProfileProxy(c, l, p)', 'proxy.getConnectedDevices()', 'onServiceConnected('):
        check(not any(re.search(rx, good) for rx, _ in OUTGOING), 'tests/android.test.py', 'the outgoing-API list refuses ' + good)
    return '%d sources scanned' % len(android_sources())


def test_manifest_meta():
    root = manifest(MANIFEST_REL)
    meta = {m.get(A + 'name'): m.get(A + 'value') for m in root.iter('meta-data')}
    check(meta.get('android.webkit.WebView.EnableSafeBrowsing') == 'false', MANIFEST_REL,
          'meta-data android.webkit.WebView.EnableSafeBrowsing is not "false"')
    check(meta.get('android.webkit.WebView.MetricsOptOut') == 'true', MANIFEST_REL,
          'meta-data android.webkit.WebView.MetricsOptOut is not "true"')


# ---------- Fallback constants and staging ----------

def test_fallback_constants():
    check(const('READY_TIMEOUT_MS') >= 30000, POLICY_REL, 'READY_TIMEOUT_MS < 30000: a slow first start would hit the fallback')
    for name in ('MAX_RENDER_CRASHES', 'MAX_FRONT_MEMORY_KILLS', 'MAX_FAILED_STARTS'):
        check(const(name) >= 2, POLICY_REL, '%s < 2: one death would leave Home for the fallback' % name)
    check(const('RENDER_DEATH_WINDOW_MS') > 0 and const('RETRY_COOLDOWN_MS') > 0, POLICY_REL,
          'RENDER_DEATH_WINDOW_MS / RETRY_COOLDOWN_MS must be positive')


def test_policy_is_plain_java():
    src = policy()
    imports = re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', src, re.M)
    bad = [i for i in imports if not i.startswith('java.')]
    check(not bad, POLICY_REL, 'imports %s: tests/fallback.test.sh compiles it with plain javac, no Android '
          'classes' % ', '.join(bad))
    check(not re.search(r'\b(?:android|androidx|org\.json)\.', src), POLICY_REL,
          'names an android/androidx/org.json class inline; it must stay plain Java')
    check(re.search(r'^package\s+home\.cleanframe\.tv\s*;', src, re.M), POLICY_REL,
          'not in package home.cleanframe.tv (tests/java/FallbackPolicyTest.java uses its package-private API)')


def test_mainactivity_delegates_to_policy():
    src = java()
    check(re.search(r'new\s+FallbackPolicy\s*\(\s*SystemClock::elapsedRealtime\s*,', src), JAVA_REL,
          'FallbackPolicy is not built on SystemClock.elapsedRealtime (the clock the death windows assume)')
    # Each lifecycle/WebView event reaches the policy from the method that receives it.
    for meth, call in (('onCreate', 'policy.created('), ('onResume', 'policy.resumed()'),
                       ('onPause', 'policy.paused()'), ('onNewIntent', 'policy.home('),
                       ('onRenderProcessGone', 'policy.renderGone('), ('onTrimMemory', 'policy.trimMemory('),
                       ('startPage', 'policy.pageStarted()'), ('showFallback', 'policy.fallbackShown('),
                       ('hang', 'policy.unresponsive()'), ('hang', 'policy.terminated('),
                       ('onRenderProcessResponsive', 'policy.responsive()'), ('markReady', 'policy.pageDrew()'),
                       ('startReadyChecks', 'policy.readyChecksStarted()'), ('stopReadyChecks', 'policy.readyChecksStopped()'),
                       ('onStop', 'policy.stopped()'), ('markReady', 'armStable()'), ('onResume', 'armStable()'), ('onPause', 'armStable()'),
                       ('armStable', 'policy.stableLeft()')):
        check(call in method(src, meth), JAVA_REL, '%s() does not call %s...)' % (meth, call.rstrip('()')))
    # ...and MainActivity keeps none of the decision state itself (it would drift from the tested copy).
    for field in ('ArrayDeque', 'recordDeath', 'failedAt', 'unresponsiveSince', 'hangTerminated', 'rebuildOnResume',
                  'fallbackByHand', 'RENDER_DEATH_WINDOW_MS', 'RETRY_COOLDOWN_MS', 'HANG_TIMEOUT_MS',
                  'MAX_RENDER_CRASHES', 'MAX_FRONT_MEMORY_KILLS', 'MAX_FAILED_STARTS', 'STABLE_MS'):
        check(not re.search(r'\b%s\b' % field, src), JAVA_REL,
              'still has its own %s: the fallback decisions belong in FallbackPolicy (tested by '
              'tests/fallback.test.sh)' % field)
    body = block_after(src, r'private\s+final\s+Runnable\s+readyDeadline\s*=\s*\(\)\s*->\s*\{', JAVA_REL,
                       'readyDeadline runnable')
    stable = block_after(src, r'private\s+final\s+Runnable\s+stableCheck\s*=\s*\(\)\s*->\s*\{', JAVA_REL, 'stableCheck runnable')
    check('policy.stableDue()' in stable, JAVA_REL, 'the stableCheck timer does not call policy.stableDue(): the start count would never clear')
    check('policy.readyDeadline()' in body and 'showFallback(' in body, JAVA_REL,
          'the readyDeadline timer does not ask policy.readyDeadline() before showing the fallback')
    # Review 30e7356 (G3): the timer is armed for what is left of the page's on-screen allowance
    # (FallbackPolicy.readyLeft(), behaviour in tests/java/FallbackPolicyTest.java), never a fresh 60 s.
    start = method(src, 'startReadyChecks')
    check(re.search(r'postDelayed\(\s*readyDeadline\s*,\s*policy\.readyLeft\(\)\s*\)', start)
          and 'READY_TIMEOUT_MS' not in start, JAVA_REL,
          'startReadyChecks() arms the ready deadline for a fresh READY_TIMEOUT_MS instead of policy.readyLeft()')


def test_release_manifest_is_home():
    acts = [a for a in manifest(MANIFEST_REL).iter('activity') if a.get(A + 'name') == '.MainActivity']
    check(acts, MANIFEST_REL, 'no .MainActivity')
    cats = [categories(f) for f in acts[0].findall('intent-filter')]
    check(any('android.intent.category.HOME' in c for c in cats), MANIFEST_REL, '.MainActivity has no HOME filter')
    check(any('android.intent.category.LEANBACK_LAUNCHER' in c for c in cats), MANIFEST_REL,
          '.MainActivity has no LEANBACK_LAUNCHER filter')


def test_staging_manifest_not_home():
    root = manifest(STAGING_REL)
    removed = [categories(f) for f in root.iter('intent-filter') if f.get(T + 'node') == 'remove']
    check(any('android.intent.category.HOME' in c for c in removed), STAGING_REL,
          'no intent-filter with tools:node="remove" and category HOME: staging would become a Home app')
    check(not any('android.intent.category.LEANBACK_LAUNCHER' in c for c in removed), STAGING_REL,
          'removes the LEANBACK_LAUNCHER filter: staging could not be opened from the app list')
    other = [e.tag for e in root.iter() if e.get(T + 'node') in ('remove', 'removeAll', 'replace')
             and e.tag != 'intent-filter']
    check(not other, STAGING_REL, 'also removes/replaces %s' % ', '.join(other))


# ---------- Page contracts Java relies on ----------

MAIN_REL = 'web/main.js'
GRADLE_REL = 'android/app/build.gradle'


def test_ready_selector_rendered_by_main_js():
    m = re.search(r'static\s+final\s+String\s+READY_JS\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', java())
    check(m, JAVA_REL, 'READY_JS is missing or not a single string literal')
    sel = re.search(r"querySelector\('([^']+)'\)", m.group(1))
    check(sel, JAVA_REL, "READY_JS %r has no document.querySelector('...'); teach this test the new form" % m.group(1))
    attrs = re.findall(r'\[([\w-]+)', sel.group(1))
    check(attrs and re.fullmatch(r'(?:\[[\w-]+\])+', sel.group(1)), JAVA_REL,
          'READY_JS selector %r is not plain [attribute] tests; teach this test to check it' % sel.group(1))
    main = read(MAIN_REL)
    for a in attrs:
        rendered = re.search(r"""\b%s="|setAttribute\(\s*'%s'""" % (re.escape(a), re.escape(a)), main)
        check(rendered, MAIN_REL, 'never renders %s (no %s="..." markup or setAttribute(\'%s\')), so READY_JS %r '
              'would never see the page ready' % (a, a, a, sel.group(1)))
    return 'READY_JS waits for %s' % sel.group(1)


def device_exports():
    src = strip_comments(read(DEVICE_REL))
    m = re.search(r'window\.FrameDevice\s*=\s*\{([^}]*)\}\s*;', src)
    check(m, DEVICE_REL, 'no `window.FrameDevice = { ... };` export found')
    pairs = re.findall(r'(\w+)\s*:\s*(\w+)', m.group(1))
    check(pairs, DEVICE_REL, 'window.FrameDevice export has no name: function pairs')
    for key, fn in pairs:
        check(re.search(r'\bfunction\s+%s\s*\(' % re.escape(fn), src), DEVICE_REL,
              'FrameDevice.%s is exported as %s, which device.js never defines' % (key, fn))
    return {k for k, _ in pairs}


def test_java_framedevice_calls_exist():
    called = set()
    for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', java()):  # evaluateJavascript text is in string literals
        called |= set(re.findall(r'\bFrameDevice\.(\w+)', lit))
    check(called, JAVA_REL, 'no FrameDevice.<name> calls found in string literals; if none are intended, drop this case')
    missing = called - device_exports()  # no tolerance: the web side's material step has landed
    check(not missing, DEVICE_REL, 'window.FrameDevice does not export %s, which MainActivity.java calls via '
          'evaluateJavascript' % ', '.join(sorted(missing)))
    return 'Java calls FrameDevice.' + ', '.join(sorted(called))


def sdk29_blocks(src):
    out, pat = [], re.compile(r'if\s*\(\s*Build\.VERSION\.SDK_INT\s*>=\s*(?:29|Build\.VERSION_CODES\.Q)\s*\)\s*\{')
    for m in pat.finditer(src):
        out.append(block_after(src[m.start():], pat.pattern, JAVA_REL, 'SDK >= 29 block'))
    return out


def test_render_process_client_guarded():
    src = java()
    calls = len(re.findall(r'\.setWebViewRenderProcessClient\(', src))
    check(calls == 1, JAVA_REL, 'want exactly one setWebViewRenderProcessClient( call, found %d' % calls)
    check(any('.setWebViewRenderProcessClient(' in b for b in sdk29_blocks(src)), JAVA_REL,
          'setWebViewRenderProcessClient is not inside `if (Build.VERSION.SDK_INT >= 29) {`: '
          'it would throw NoSuchMethodError on API 26-28')
    cls = re.search(r'class\s+(\w+)\s+extends\s+WebViewRenderProcessClient\s*\{', src)
    check(cls, JAVA_REL, 'no class extending WebViewRenderProcessClient')
    body = block_after(src, re.escape(cls.group(0)), JAVA_REL, 'class %s' % cls.group(1))
    unresp = block_after(body, r'void\s+onRenderProcessUnresponsive\s*\([^)]*\)\s*\{', JAVA_REL,
                         '%s.onRenderProcessUnresponsive()' % cls.group(1))
    resp = block_after(body, r'void\s+onRenderProcessResponsive\s*\([^)]*\)\s*\{', JAVA_REL,
                       '%s.onRenderProcessResponsive()' % cls.group(1))
    # The timing (HANG_TIMEOUT_MS, only while in front) is FallbackPolicy.unresponsive(), run by
    # tests/fallback.test.sh; here: the callback reaches it with a way to terminate this renderer.
    check(re.search(r'\bhang\(', unresp), JAVA_REL, 'onRenderProcessUnresponsive() does not report to hang()')
    check(re.search(r'renderer\s*!=\s*null\s*&&\s*renderer\.terminate\(\)', unresp), JAVA_REL,
          'onRenderProcessUnresponsive() never terminates the hung renderer (renderer != null && renderer.terminate())')
    hang = method(src, 'hang')
    check(re.search(r'policy\.terminated\(\s*\w+\.getAsBoolean\(\)\s*\)', hang), JAVA_REL,
          'hang() does not terminate on TERMINATE and pass the result to policy.terminated()')
    check(len(re.findall(r'showFallback\(\s*policy\.reason\(\)', hang)) >= 2, JAVA_REL,
          'hang() has no fallback both for a failed terminate() and for FALLBACK')
    check('policy.responsive()' in resp, JAVA_REL,
          'onRenderProcessResponsive() does not clear the hang clock (policy.responsive())')
    pol = method(policy(), 'unresponsive')
    for need, why in (('HANG_TIMEOUT_MS', 'waits no bounded time before acting'),
                      ('resumed', 'does not check that Home is in front')):
        check(need in pol, POLICY_REL, 'unresponsive() %s (no %s)' % (why, need))
    check(re.search(r'new\s+%s\s*\(' % cls.group(1), ''.join(sdk29_blocks(src))), JAVA_REL,
          '%s is instantiated outside the SDK >= 29 check' % cls.group(1))
    check(const('HANG_TIMEOUT_MS') >= 10000, POLICY_REL,
          'HANG_TIMEOUT_MS < 10000: a brief stall on the 2 GB stick would kill the page')


def test_home_and_back_ping_the_page():
    # Home/Back reach the page via evaluateJavascript, which is not input, so WebView never reports
    # a page that froze while only they were pressed: each press must ping it (FallbackPolicy runs
    # the timing; tests/fallback.test.sh tests it).
    src = java()
    check('pingPage()' in method(src, 'onNewIntent'), JAVA_REL, 'onNewIntent() (Home) does not pingPage()')
    check('pingPage()' in method(src, 'dispatchKeyEvent'), JAVA_REL, 'dispatchKeyEvent() (Back) does not pingPage()')
    ping = method(src, 'pingPage')
    check('policy.pingNeeded()' in ping, JAVA_REL, 'pingPage() does not ask policy.pingNeeded()')
    answer = block_after(ping, r'evaluateJavascript\([^{;]*->\s*\{', JAVA_REL, 'pingPage() evaluateJavascript callback')
    check('policy.pong()' in answer and re.search(r'if\s*\(\s*w\s*!=\s*web\s*\)\s*return', answer), JAVA_REL,
          'pingPage()\'s round-trip answer does not call policy.pong() for the current WebView only')
    check('pingCheck' in ping and 'PING_REPORT_MS' in ping, JAVA_REL, 'pingPage() does not schedule pingCheck')
    body = block_after(src, r'private\s+final\s+Runnable\s+pingCheck\s*=\s*\(\)\s*->\s*\{', JAVA_REL,
                       'pingCheck runnable')
    check(re.search(r'policy\.pingOverdue\(\)\)\s*hang\(', body), JAVA_REL,
          'pingCheck does not report an overdue ping to hang()')
    check(re.search(r'postDelayed\(\s*this\.pingCheck\s*,\s*policy\.pingCheckIn\(\)\s*\)', body), JAVA_REL,
          'pingCheck does not re-arm at the ping\'s next due time (postDelayed(this.pingCheck, policy.pingCheckIn()))')
    # Android 10+ delivers Home as pause, onNewIntent, resume: the press's ping (pingNeeded() while
    # paused only remembers it) and an outstanding ping's check (onPause removed it) are both picked
    # up after policy.resumed() (2026-10-31 review, area A; measured on the stick).
    resume = method(src, 'onResume')
    rebuild = re.search(r'if\s*\(\s*policy\.resumed\(\)\s*\)\s*\{', resume)
    check(rebuild, JAVA_REL, 'onResume() does not branch on policy.resumed()')
    check(resume.find('resumePing()') > rebuild.end(), JAVA_REL,
          'onResume() does not call resumePing() after policy.resumed() (a Home press on Android 10+ sends no ping)')
    rp = method(src, 'resumePing')
    pend = re.search(r'if\s*\(\s*policy\.pingPending\(\)\s*\)\s*\{([^}]*)\}', rp)
    check(pend and 'pingPage()' in pend.group(1), JAVA_REL, 'resumePing() does not pingPage() when policy.pingPending()')
    check(re.search(r'policy\.pingOutstanding\(\)', rp) and
          re.search(r'postDelayed\(\s*pingCheck\s*,\s*policy\.pingCheckIn\(\)\s*\)', rp), JAVA_REL,
          'resumePing() does not re-arm an outstanding ping\'s check at policy.pingCheckIn()')
    paused = method(policy(), 'paused')
    for field in ('unresponsiveSince', 'pingSentAt'):
        check(field not in paused, POLICY_REL, 'paused() clears %s: the pause around every Home press would '
              'restart the hang clock (resumed() decides, after SHORT_PAUSE_MS)' % field)
    check('SHORT_PAUSE_MS' in method(policy(), 'resumed'), POLICY_REL, 'resumed() does not use SHORT_PAUSE_MS')
    check(const('SHORT_PAUSE_MS') < 5000, POLICY_REL, 'SHORT_PAUSE_MS >= 5000: leaving Home for another app '
          'and coming back would keep the hang clock')
    for meth in ('dropPage', 'onPause'):
        check('removeCallbacks(pingCheck)' in method(src, meth), JAVA_REL, '%s() leaves pingCheck running' % meth)
    check(any('terminateRenderer29(' in b for b in sdk29_blocks(src)), JAVA_REL,
          'getWebViewRenderProcess() is not reached only behind `if (Build.VERSION.SDK_INT >= 29) {`')


def test_back_in_the_list_goes_through_policy():
    # The Back key on the native list (no page) asks policy.back(), which clears the counts for the
    # list opened by hand, as its button does, and ignores a failure list (tests/fallback.test.sh).
    body = method(java(), 'dispatchKeyEvent')
    check(re.search(r'if\s*\(\s*web\s*==\s*null\s*&&\s*policy\.back\(\)\s*\)\s*startPage\(\)', body), JAVA_REL,
          'dispatchKeyEvent(): Back with no page does not `if (web == null && policy.back()) startPage()`')
    check(not re.search(r'policy\.byHand\(\)', body), JAVA_REL,
          'dispatchKeyEvent() decides on policy.byHand(): Back would leave the list without clearing the counts')
    btn = method(java(), 'showFallback')
    check('policy.tryAgain(byHand)' in btn, JAVA_REL, "showFallback(): the list's first button no longer calls policy.tryAgain(byHand)")


def test_fallback_buttons_report_errors():
    # start() and launch() return "" or a message; the native list's buttons must show it (a Toast),
    # not drop it, or a press that fails looks like a press that did nothing.
    body = method(java(), 'showFallback')
    check(re.search(r'(?<![\w.])(?:start|launch)\(', body), JAVA_REL, 'showFallback() has no start()/launch() buttons to check')
    for m in re.finditer(r'(?<![\w.])(start|launch)\(', body):
        before = body[:m.start()].rstrip()
        check(re.search(r'\breport\(\s*[^,()]+,\s*$', before), JAVA_REL,
              'showFallback(): a %s() result is not passed to report(what, ...): its error would be dropped'
              % m.group(1))
    rep = method(java(), 'report')
    check(re.search(r'Toast\.makeText\([^;]*\)\.show\(\)', rep) and re.search(r'isEmpty\(\)', rep), JAVA_REL,
          'report() does not show a non-empty error with Toast.makeText(...).show()')


def test_icon_cache_bounded_and_versioned():
    # The cache itself (byte budget, LRU, invalidation) is tests/java/IconCacheTest.java; here: that
    # MainActivity uses it keyed by lastUpdateTime and draws without holding a lock.
    src = java()
    check(re.search(r'private\s+final\s+IconCache\s+iconCache\s*=\s*new\s+IconCache\(', src), JAVA_REL,
          'iconCache is not an IconCache (bounded, invalidated on app update)')
    check(not re.search(r'\bnew\s+HashMap\b', src), JAVA_REL, 'an unbounded HashMap is back')
    check(not re.search(r'synchronized[^;{]*\bbyte\[\]\s+icon\s*\(', src), JAVA_REL,
          'icon() is synchronized: every icon request waits while one is drawn')
    body = method(src, 'icon')
    check(not re.search(r'synchronized\s*\(', body), JAVA_REL, 'icon() draws inside a synchronized block')
    check(re.search(r'\.lastUpdateTime\b', body), JAVA_REL, 'icon() does not read the package lastUpdateTime')
    check(re.search(r'iconCache\.get\(\s*pkg\s*,\s*updated\s*\)', body)
          and re.search(r'iconCache\.put\(\s*pkg\s*,\s*updated\s*,', body), JAVA_REL,
          'icon() does not key the cache by (pkg, lastUpdateTime)')
    check(const('ICON_CACHE_BYTES', JAVA_REL) <= 8 * 1024 * 1024, JAVA_REL,
          'ICON_CACHE_BYTES above 8 MB on a 2 GB stick')
    icache = strip_comments(read(ICON_REL))
    check(not re.search(r'^\s*import\s+(?!java\.)', icache, re.M), ICON_REL, 'imports a non-java.* class; it must stay plain Java')


def test_page_ready_signal():
    # device.js tells MainActivity the page drew (same selector as READY_JS) as soon as it does, so a
    # start killed before the 2 s probe is not a failed start. The bridge call arrives on the
    # JavaBridge thread: it must hop to the main thread and ignore an older WebView.
    m = re.search(r'static\s+final\s+String\s+READY_JS\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', java())
    check(m, JAVA_REL, 'READY_JS is missing')
    sel = re.search(r"querySelector\('([^']+)'\)", m.group(1)).group(1)
    dev = strip_comments(read(DEVICE_REL))
    check("querySelector('%s')" % sel in dev, DEVICE_REL,
          "the ready signal does not test READY_JS's own selector %s" % sel)
    check(re.search(r'api\(\)\.ready\(\)', dev), DEVICE_REL, 'never calls AndroidHome.ready()')
    body = block_after(java(), r'class\s+Bridge\b[^{]*\{', JAVA_REL, 'class Bridge')
    ready = block_after(body, r'public\s+String\s+ready\s*\(\s*\)\s*\{', JAVA_REL, 'Bridge.ready()')
    check(re.search(r'ui\.post\(', ready), JAVA_REL, 'Bridge.ready() does not post to the main thread')
    check(re.search(r'if\s*\(\s*owner\s*==\s*web\s*\)\s*markReady\(\)', ready), JAVA_REL,
          'Bridge.ready() does not markReady() for its own (current) WebView only')
    check(re.search(r'addJavascriptInterface\(\s*new\s+Bridge\(\s*web\s*\)', java()), JAVA_REL,
          'the bridge is not created with the WebView it belongs to')


def test_git_dirty_fails_closed():
    src = read(GRADLE_REL)
    helper = re.search(r'def\s+git\s*=\s*\{.*?\n\}', src, re.S)
    check(helper and re.search(r'exitValue\s*==\s*0\s*\?.*:\s*null', helper.group(0)), GRADLE_REL,
          'the git() helper no longer returns null on a failed command, so failure cannot be told from empty output')
    check(not re.search(r"&&\s*git\(\s*'status'", src), GRADLE_REL,
          "git('status', ...) is used as a truth value: a failed status (null) would read as a clean tree")
    m = re.search(r"(\w+)\s*=\s*git\(\s*'status'\s*,\s*'--porcelain'\s*\)", src)
    check(m, GRADLE_REL, "no `x = git('status', '--porcelain')` to check for failure")
    check(re.search(r'if\s*\(\s*%s\s*==\s*null\s*\)\s*throw\s+new\s+GradleException' % m.group(1), src[m.end():]),
          GRADLE_REL, "a failed git status (%s == null) does not fail the build" % m.group(1))
    check(re.search(r"%s\s*\?\s*'-dirty'\s*:\s*''" % m.group(1), src[m.end():]), GRADLE_REL,
          "gitDirty is not `%s ? '-dirty' : ''` after the failure check" % m.group(1))


# ---------- Photo groups (docs/PHOTO-GROUPS.md contract 2) ----------

_lib = None


def lib_src():
    global _lib
    if _lib is None:
        _lib = strip_comments(read(LIB_REL))
    return _lib


def spec_bridge():
    """{name: arity} of the bridge methods docs/PHOTO-GROUPS.md contract 2 lists."""
    doc = read(SPEC_REL)
    m = re.search(r'^## Contract 2\b.*?^- Bridge \(`window\.AndroidHome`.*?$(.*?)^- (?!\s)', doc, re.S | re.M)
    check(m, SPEC_REL, 'cannot find the "- Bridge (`window.AndroidHome`" list in contract 2')
    found = re.findall(r'`(\w+)\(([^)`]*)\)`', m.group(1))
    check(found, SPEC_REL, 'the contract 2 bridge list names no `method(args)`')
    return {name: len([a for a in args.split(',') if a.strip()]) for name, args in found}


def bridge_body():
    return block_after(java(), r'class\s+Bridge\b[^{]*\{', JAVA_REL, 'class Bridge')


def bridge_method(name):
    return block_after(bridge_body(), r'public\s+String\s+%s\s*\([^)]*\)\s*\{' % name, JAVA_REL, 'Bridge.%s()' % name)


def test_photo_bridge_matches_spec():
    spec = spec_bridge()
    check(set(spec) == {'library', 'addGroup', 'renameGroup', 'deleteGroup', 'deletePhoto', 'uploadStart', 'uploadStop'},
          SPEC_REL, 'contract 2 bridge list changed (%s): update this test and both sides' % ', '.join(sorted(spec)))
    body = bridge_body()
    for name, arity in sorted(spec.items()):
        m = re.search(r'@JavascriptInterface\s+public\s+(\w+)\s+%s\s*\(([^)]*)\)' % name, body)
        check(m, JAVA_REL, 'Bridge lacks @JavascriptInterface %s(), which docs/PHOTO-GROUPS.md lists' % name)
        check(m.group(1) == 'String', JAVA_REL, 'Bridge.%s() returns %s, not a JSON String' % (name, m.group(1)))
        params = [a for a in m.group(2).split(',') if a.strip()]
        check(len(params) == arity, JAVA_REL, 'Bridge.%s() takes %d arguments, the spec %d' % (name, len(params), arity))
        check(all(re.fullmatch(r'\s*String\s+\w+\s*', a) for a in params), JAVA_REL,
              'Bridge.%s() arguments are not all String (the page passes strings)' % name)
    return 'spec: ' + ', '.join('%s/%d' % kv for kv in sorted(spec.items()))


def test_photo_bridge_device_js_calls_all():
    photo, js = set(spec_bridge()), device_calls()
    used = js & photo
    check(used == photo, DEVICE_REL, 'calls %s but not %s: the page needs the whole photo bridge'
          % (', '.join(sorted(used)) or 'none', ', '.join(sorted(photo - used))))
    return 'device.js calls %d/%d photo methods' % (len(used), len(photo))


def test_photo_bridge_never_throws():
    for name in sorted(spec_bridge()):
        body = bridge_method(name)
        wrapped = re.fullmatch(r'\s*return\s+withLib\(.*\);\s*', body, re.S)
        check(wrapped or re.search(r'catch\s*\(\s*Throwable\b', body), JAVA_REL,
              'Bridge.%s() neither is `return withLib(...)` nor catches Throwable: an exception would reach the page' % name)
    w = method(java(), 'withLib')
    check(re.search(r'try\s*\{.*call\.apply\(.*\}\s*catch\s*\(\s*Throwable\b', w, re.S), JAVA_REL,
          'withLib() does not run the call inside try { ... } catch (Throwable ...)')
    check(re.search(r'catch\s*\(\s*Throwable\b[^)]*\)\s*\{\s*return\s+UserLibrary\.emptyLibrary\(\)', bridge_method('library')),
          JAVA_REL, 'Bridge.library() does not answer UserLibrary.emptyLibrary() when it fails')
    # every public UserLibrary answer catches what it can throw (RuntimeException and IOException)
    for name in ('addGroup', 'renameGroup', 'deleteGroup', 'deletePhoto', 'uploadStart', 'uploadStop', 'library'):
        body = block_after(lib_src(), r'synchronized\s+String\s+%s\s*\([^)]*\)\s*\{' % name, LIB_REL, 'UserLibrary.%s()' % name)
        check(re.search(r'catch\s*\([^)]*RuntimeException', body), LIB_REL, 'UserLibrary.%s() does not catch RuntimeException' % name)


def test_user_route():
    src = java()
    check(re.search(r'static\s+final\s+String\s+USER_ROUTE\s*=\s*"/home/user/"\s*;', src), JAVA_REL,
          'USER_ROUTE is not "/home/user/" (the page\'s relative user/<gid>/<pid>/<file>.jpg paths)')
    body = method(src, 'shouldInterceptRequest')
    guard = re.search(r'path\.contains\("\.\."\)\)\s*return\s+status\(403', body)
    user = re.search(r'if\s*\(\s*path\.startsWith\(\s*USER_ROUTE\s*\)\s*\)\s*\{', body)
    assets = re.search(r'if\s*\(\s*path\.startsWith\(\s*"/home/"\s*\)\s*\)\s*\{', body)
    check(guard and user and assets, JAVA_REL, 'shouldInterceptRequest() lacks the ".." 403 guard, the USER_ROUTE branch or the asset branch')
    check(guard.start() < user.start() < assets.start(), JAVA_REL,
          'the USER_ROUTE branch must come after the origin/".." guard and before the /home/ assets (which would 404 it)')
    branch = block_after(body[user.start():], r'if\s*\(\s*path\.startsWith\(\s*USER_ROUTE\s*\)\s*\)\s*\{', JAVA_REL, 'USER_ROUTE branch')
    check(re.search(r'\.photoFile\(\s*path\.substring\(\s*USER_ROUTE\.length\(\)\s*\)\s*\)', branch), JAVA_REL,
          'the USER_ROUTE branch does not resolve the file with UserLibrary.photoFile(path.substring(USER_ROUTE.length()))')
    check(re.search(r'new\s+WebResourceResponse\(\s*"image/jpeg"\s*,\s*null\s*,\s*new\s+FileInputStream\(\s*f\s*\)\s*\)', branch),
          JAVA_REL, 'the USER_ROUTE branch does not serve photoFile()\'s file as image/jpeg with no charset')
    check(re.search(r'if\s*\(\s*f\s*!=\s*null\s*\)', branch) and re.search(r'return\s+status\(\s*404\s*,[^;]*;\s*$', branch.strip()),
          JAVA_REL, 'the USER_ROUTE branch does not end in 404 for anything photoFile() refuses')
    check(re.search(r'catch\s*\(\s*Throwable\b', branch), JAVA_REL, 'the USER_ROUTE branch can throw on WebView\'s IO thread')
    for bad in ('getAssets', 'new File(', 'Paths.get', 'getExternalFilesDir'):
        check(bad not in branch, JAVA_REL, 'the USER_ROUTE branch builds a path itself (%s); only photoFile() decides' % bad)
    # what photoFile() allows: the seven names and src.jpg (Adjust, 2026-11-02), valid ids, canonical path under R, a regular file
    lib = lib_src()
    m = re.search(r'PHOTO_FILES\s*=\s*Collections\.unmodifiableList\(\s*Arrays\.asList\(([^)]*)\)\s*\)', lib)
    check(m, LIB_REL, 'PHOTO_FILES is not an unmodifiable Arrays.asList(...)')
    names = re.findall(r'"([^"]+)"', m.group(1))
    want = ['wall.jpg', 'thumb.jpg', 'panel.jpg', 'field-blur.jpg', 'field-clear.jpg', 'field-frost.jpg', 'study-soft.jpg']
    check(sorted(names) == sorted(want), LIB_REL, 'PHOTO_FILES is %s, want the seven of contract 1' % names)
    served = re.search(r'List<String>\s+s\s*=\s*new\s+ArrayList<>\(\s*PHOTO_FILES\s*\)\s*;((?:\s*s\.add\("[^"]+"\)\s*;)*)\s*SERVED\s*=\s*Collections\.unmodifiableList\(\s*s\s*\)', lib)
    check(served and re.findall(r'"([^"]+)"', served.group(1)) == ['src.jpg'], LIB_REL, 'SERVED is not PHOTO_FILES plus src.jpg alone')
    pf = block_after(lib, r'File\s+photoFile\s*\(\s*String\s+\w+\s*\)\s*\{', LIB_REL, 'photoFile()')
    for need, why in ((r'\.length\s*!=\s*3', 'exactly three segments'), (r'!validGid\(', 'a valid gid'),
                      (r'!validPid\(', 'a valid pid'), (r'!SERVED\.contains\(', 'one of the seven names or src.jpg'),
                      (r'getCanonicalPath\(\).*startsWith\(', 'the canonical path under R'), (r'isFile\(\)', 'a regular file')):
        check(re.search(need, pf, re.S), LIB_REL, 'photoFile() does not require %s' % why)
    for pat, rx in (('USER_GID', r'"g\[0-9a-z\]\{8\}"'), ('PID', r'"p\[0-9a-z\]\{10\}"')):
        check(re.search(r'Pattern\s+%s\s*=\s*Pattern\.compile\(\s*%s\s*\)' % (pat, rx), lib), LIB_REL,
              '%s is not the contract 1 pattern' % pat)
    return 'serves ' + ' '.join(names) + ' src.jpg'


def test_library_root_single():
    src, raw = java(), read(JAVA_REL)
    check(len(re.findall(r'getExternalFilesDir\(', src)) == 1, JAVA_REL, 'getExternalFilesDir( must appear once (libraryRoot())')
    root = method(src, 'libraryRoot')
    check(re.search(r'getExternalFilesDir\(\s*null\s*\)', root) and re.search(r'new\s+File\(\s*\w+\s*,\s*"cleanframe"\s*\)', root),
          JAVA_REL, 'libraryRoot() is not getExternalFilesDir(null) + "cleanframe"')
    at = raw.find('private File libraryRoot(')
    doc = raw[raw.rfind('/**', 0, at):at] if at > 0 else ''
    # DEVICE-CHECK 1 (stick, 2026-10-31): adb shell can neither read nor write R, and only the app needs
    # to, so R stays here (no /sdcard/CleanFrame/ fallback); the comment records that outcome.
    check('DEVICE-CHECK 1' in doc and 'adb shell cannot read or write' in doc and doc.rstrip().endswith('*/'), JAVA_REL,
          'libraryRoot()\'s own comment does not record DEVICE-CHECK 1 (adb shell cannot read or write R)')
    # lib(), and stopUpload() (only to remove the token file: review 687edfd, building the library there
    # ran its start-up work on the UI thread)
    stop = method(src, 'stopUpload')
    check(len(re.findall(r'\blibraryRoot\(\)', src)) == 3 and 'libraryRoot()' in method(src, 'lib') and 'libraryRoot()' in stop
          and re.search(r'UserLibrary\.removeToken\(\s*root\.toPath\(\)\s*\)', stop), JAVA_REL,
          'libraryRoot() is used outside lib() and stopUpload()\'s token removal: R must come from one place')
    check(len(re.findall(r'new\s+UserLibrary\(', src)) == 1, JAVA_REL, 'more than one UserLibrary is built')
    check(re.search(r'new\s+UserLibrary\(\s*root\s*,\s*System::currentTimeMillis\s*,\s*new\s+SecureRandom\(\)\s*\)', src), JAVA_REL,
          'UserLibrary is not built on the wall clock (epoch ms in tokens and dates) and SecureRandom (tokens)')
    for bad in ('/sdcard', 'getExternalStorageDirectory', 'Environment.'):
        check(bad not in src, JAVA_REL, 'names another storage root (%s): R comes from libraryRoot() only' % bad)


def test_upload_token_never_outlives_the_screen():
    src = java()
    stop = method(src, 'stopUpload')
    gated = block_after(stop, r'uploadGate\.stop\(\s*\(\)\s*->\s*\{', JAVA_REL, 'stopUpload()\'s uploadGate.stop(() -> {...})')
    gone = [gated.find(x) for x in ('UserLibrary.removeToken(root.toPath())', 'srv.stop()')]
    check(gone[0] >= 0, JAVA_REL, 'stopUpload() does not remove the token file through uploadGate.stop()')
    check(gone[1] > gone[0], JAVA_REL, 'stopUpload() does not stop the upload server (through uploadGate.stop(), after the token)')
    on_stop = method(src, 'onStop')
    off, st = re.search(r'started\s*=\s*false', on_stop), on_stop.find('stopUpload()')
    check(off and st > off.end(), JAVA_REL, 'onStop() does not clear `started` and then stopUpload()')
    check(re.search(r'started\s*=\s*true', method(src, 'onStart')), JAVA_REL, 'onStart() does not set started')
    drop = method(src, 'dropPage')
    nul, st = re.search(r'\bweb\s*=\s*null\s*;', drop), drop.find('stopUpload()')
    check(nul and st > nul.end(), JAVA_REL, 'dropPage() (every rebuild) does not stopUpload() after web = null')
    check(not re.search(r'return\s*;', drop[:st]), JAVA_REL, 'dropPage() can return before stopUpload()')
    check('dropPage()' in method(src, 'startPage'), JAVA_REL, 'startPage() no longer drops the old page first')
    up = bridge_method('uploadStart')
    check(re.search(r'uploadGate\.start\(\s*\(\)\s*->\s*owner\s*==\s*web\s*&&\s*started\s*,\s*UserLibrary\.fail\(NOT_SHOWN\)\s*,', up),
          JAVA_REL, 'Bridge.uploadStart() does not go through uploadGate.start(), refusing a dropped page or Home off screen')
    order = [up.find(x) for x in ('uploadGate.start(', 'lanAddress()', 'fail(NO_NETWORK)', 'UploadServer.baseUrl(ip)', '.uploadStart(gid, base)',
                                  'server(l).start(ip, UploadServer.PORT)', 'l.uploadStop()', 'MainActivity::isOk', 'srv.stop()', 'l.uploadStop()')]
    order[-1] = up.rfind('l.uploadStop()')
    check(all(i >= 0 for i in order) and order == sorted(order) and order[-1] > order[6], JAVA_REL,
          'Bridge.uploadStart() is not: inside uploadGate.start(), LAN address (else NO_NETWORK), token for http://<ip>:8097, '
          'then the server on that address (and the token dropped if it cannot start); then the undo: server stopped, token removed')
    check(re.search(r'static\s+final\s+String\s+NO_NETWORK\s*=\s*"No network connection"\s*;', src), JAVA_REL,
          'NO_NETWORK is not "No network connection" (the agreed answer)')
    check(re.search(r'private\s+volatile\s+WebView\s+web\s*;', src) and re.search(r'private\s+volatile\s+boolean\s+started\s*;', src),
          JAVA_REL, 'web and started must be volatile: Bridge.uploadStart() reads them on the JavaBridge thread')
    check(re.search(r'if\s*\(\s*owner\s*==\s*web\s*\)\s*stopUpload\(\)', bridge_method('uploadStop')), JAVA_REL,
          'Bridge.uploadStop() does not stopUpload() for the current page')
    # review 006cfe9: deleting the group that holds the live code dropped the code but left the server
    # listening until Add photo closed; UserLibrary runs the hook only then (tests/java/UserLibraryTest.java)
    check(re.fullmatch(r'\s*return\s+withLib\(\s*l\s*->\s*l\.deleteGroup\(\s*gid\s*,\s*MainActivity\.this::stopUpload\s*\)\s*\);\s*',
                       bridge_method('deleteGroup')), JAVA_REL,
          'Bridge.deleteGroup() does not pass stopUpload() to UserLibrary.deleteGroup(gid, codeDropped): '
          'deleting the code\'s group would leave the upload page listening')


def test_ui_thread_never_waits_on_the_library():
    """Review 999ba07 (L1): Bridge.uploadStart held uploadLock across UserLibrary.uploadStart() (the library's
    lock: a scan of every photo, or waiting behind the start-up clean-up's removeStale) while stopUpload()
    took uploadLock on the UI thread in onStop: an ANR on the only launcher. Now UploadGate orders the two
    (its behaviour: tests/java/UploadGateTest.java); here, that MainActivity uses it and nothing else."""
    src = java()
    check('uploadLock' not in src, JAVA_REL, 'uploadLock is back: stopUpload() and uploadStart() are ordered by UploadGate only')
    check(re.search(r'private\s+final\s+UploadGate\s+uploadGate\s*=\s*new\s+UploadGate\(\)\s*;', src), JAVA_REL, 'no UploadGate')
    check(re.search(r'private\s+volatile\s+UploadServer\s+uploadServer\s*;', src), JAVA_REL,
          'uploadServer must be volatile: made on the JavaBridge thread, stopped from the UI thread')
    for ui in ('stopUpload', 'onStop', 'dropPage'):
        body = method(src, ui)
        check('synchronized' not in body, JAVA_REL, '%s() (UI thread) takes a lock of its own: only uploadGate.stop() may order it' % ui)
        check(not re.search(r'\b(?:lib|pending|awaitCleanup)\(\)|\bl\.\w+\(', body), JAVA_REL,
              '%s() (UI thread) reaches the library or waits for the clean-up' % ui)
    up = bridge_method('uploadStart')
    check('synchronized' not in up, JAVA_REL, 'Bridge.uploadStart() holds a lock across its work (stopUpload() could wait on it)')
    gate = strip_comments(read(GATE_REL))
    imports = re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', gate, re.M)
    check(all(i.startswith('java.') for i in imports) and not re.search(r'\b(?:android|androidx)\.', gate), GATE_REL,
          'must stay plain Java (tests/fallback.test.sh compiles it)')
    check(re.search(r'^CLASSES=\([^)]*"\$SRC/UploadGate\.java"', read(FALLBACK_TEST_REL), re.M), FALLBACK_TEST_REL,
          'CLASSES does not compile UploadGate.java')
    check((ROOT / 'tests/java/UploadGateTest.java').is_file(), 'tests/java/UploadGateTest.java', 'missing')




def test_upload_base_is_the_lan_address():
    src, gradle = java(), read(GRADLE_REL)
    check('uploadUrl' not in gradle and 'UPLOAD_URL' not in gradle, GRADLE_REL,
          'still has the v1 upload URL property (the address is now the stick\'s own)')
    check('BuildConfig.UPLOAD_URL' not in src and 'UPLOAD_URL' not in src, JAVA_REL, 'still reads the v1 BuildConfig.UPLOAD_URL')
    la = method(src, 'lanAddress')
    check('NetworkInterface.getNetworkInterfaces()' in la and 'UploadServer.pickAddress(' in la, JAVA_REL,
          'lanAddress() does not pick from NetworkInterface with UploadServer.pickAddress()')
    srv = strip_comments(read(SRV_REL))
    check(re.search(r'static\s+final\s+int\s+PORT\s*=\s*8097\s*;', srv), SRV_REL, 'PORT is not 8097')
    check(re.search(r'return\s+"http://"\s*\+\s*a\.getHostAddress\(\)\s*\+\s*":"\s*\+\s*PORT\s*;', method(srv, 'baseUrl')), SRV_REL,
          'baseUrl() is not http://<ip>:PORT')
    check(re.search(r'instanceof\s+java\.net\.Inet4Address', method(srv, 'pickAddress')), SRV_REL, 'pickAddress() does not take IPv4 only')
    check(not re.search(r'"https?://(?!" \+ HOST)', src), JAVA_REL, 'MainActivity hard-codes an http(s) address')


def test_material_bridge_and_tmp_route():
    body = bridge_body()
    for name, arity in sorted(MATERIAL_BRIDGE.items()):
        m = re.search(r'@JavascriptInterface\s+public\s+String\s+%s\s*\(([^)]*)\)' % name, body)
        check(m, JAVA_REL, 'Bridge lacks @JavascriptInterface String %s()' % name)
        params = [x for x in m.group(1).split(',') if x.strip()]
        check(len(params) == arity and all(re.fullmatch(r'\s*String\s+\w+\s*', x) for x in params), JAVA_REL,
              'Bridge.%s() does not take %d String arguments' % (name, arity))
        check(re.fullmatch(r'\s*return\s+withLib\(.*pending\(\)\.%s\(.*\);\s*' % name, bridge_method(name), re.S), JAVA_REL,
              'Bridge.%s() is not `return withLib(l -> pending().%s(...))` (it could throw to the page)' % (name, name))
    check(re.search(r'saveMaterial\(\s*gid\s*,\s*pid\s*,\s*kind\s*,\s*dataUrl\s*\)', bridge_method('saveMaterial')), JAVA_REL,
          'saveMaterial(gid, pid, kind, dataUrl) is not passed through in that order')
    src = java()
    check(re.search(r'static\s+final\s+String\s+USER_TMP_ROUTE\s*=\s*"/home/user-tmp/"\s*;', src), JAVA_REL,
          'USER_TMP_ROUTE is not "/home/user-tmp/" (same origin as the page: an untainted canvas)')
    body = method(src, 'shouldInterceptRequest')
    guard = re.search(r'path\.contains\("\.\."\)\)\s*return\s+status\(403', body)
    tmp = re.search(r'if\s*\(\s*path\.startsWith\(\s*USER_TMP_ROUTE\s*\)\s*\)\s*\{', body)
    assets = re.search(r'if\s*\(\s*path\.startsWith\(\s*"/home/"\s*\)\s*\)\s*\{', body)
    check(guard and tmp and assets and guard.start() < tmp.start() < assets.start(), JAVA_REL,
          'the USER_TMP_ROUTE branch must come after the origin/".." guard and before the /home/ assets')
    branch = block_after(body[tmp.start():], r'if\s*\(\s*path\.startsWith\(\s*USER_TMP_ROUTE\s*\)\s*\)\s*\{', JAVA_REL, 'USER_TMP_ROUTE branch')
    check(re.search(r'\.tmpWall\(\s*path\.substring\(\s*USER_TMP_ROUTE\.length\(\)\s*\)\s*\)', branch)
          and re.search(r'new\s+WebResourceResponse\(\s*"image/jpeg"\s*,\s*null\s*,\s*new\s+FileInputStream\(\s*f\s*\)\s*\)', branch)
          and re.search(r'catch\s*\(\s*Throwable\b', branch) and re.search(r'return\s+status\(\s*404\s*,[^;]*;\s*$', branch.strip()),
          JAVA_REL, 'the USER_TMP_ROUTE branch does not serve only PendingPhotos.tmpWall()\'s file as image/jpeg, else 404, never throwing')
    for bad in ('getAssets', 'new File(', 'Paths.get', 'getExternalFilesDir'):
        check(bad not in branch, JAVA_REL, 'the USER_TMP_ROUTE branch builds a path itself (%s)' % bad)
    pend = strip_comments(read(PENDING_REL))
    tw = block_after(pend, r'File\s+tmpWall\s*\(\s*String\s+\w+\s*\)\s*\{', PENDING_REL, 'tmpWall()')
    for need, why in ((r'\.length\s*!=\s*3', 'three segments'), (r'!UserLibrary\.validGid\(', 'a valid gid'),
                      (r'!UserLibrary\.validPid\(', 'a valid pid'), (r'TMP_ROUTE_FILE\.equals\(', 'wall.jpg only'),
                      (r'getCanonicalPath\(\).*startsWith\(', 'the canonical path under R'), (r'isFile\(\)', 'a regular file')):
        check(re.search(need, tw, re.S), PENDING_REL, 'tmpWall() does not require %s' % why)
    wake = re.search(r'static\s+final\s+String\s+MATERIALS_WAKE_JS\s*=\s*"([^"]*)"\s*;', src)
    check(wake and wake.group(1) == 'window.FrameDevice&&FrameDevice.materialsWake&&FrameDevice.materialsWake()', JAVA_REL,
          'MATERIALS_WAKE_JS is not the agreed guarded call')
    wm = method(src, 'wakeMaterials')
    check(re.search(r'ui\.post\(', wm) and 'evaluateJavascript(MATERIALS_WAKE_JS' in wm and re.search(r'if\s*\(\s*w\s*!=\s*null\s*\)', wm),
          JAVA_REL, 'wakeMaterials() does not run the wake on the UI thread, only while a page is alive')
    check('this::wakeMaterials' in method(src, 'server'), JAVA_REL, 'the server is not told to wake the page after queueing')


def test_upload_processing_and_startup():
    src = java()
    check(re.search(r'ExecutorService\s+processing\s*=\s*Executors\.newSingleThreadExecutor\(', src), JAVA_REL,
          'photos are not prepared on one background thread (Executors.newSingleThreadExecutor)')
    check('new AndroidPhotoProcessor(processing)' in method(src, 'server'), JAVA_REL, 'the processor does not use that thread')
    check('processing.shutdownNow()' in method(src, 'onDestroy'), JAVA_REL, 'onDestroy() leaves the processing thread')
    check('setBlockNetworkLoads(true)' in method(src, 'startPage'), JAVA_REL, 'setBlockNetworkLoads(true) is not set on every new WebView')
    gradle = read(GRADLE_REL)
    check(re.search(r"main\.assets\.srcDirs\s*=\s*\['\.\./\.\./web'\s*,\s*'src/main/assets'\]", gradle), GRADLE_REL,
          'the APK assets are not web/ + src/main/assets (the phone page in assets/upload/)')
    for f in ('index.html', 'app.js', 'style.css'):
        check((ROOT / 'android/app/src/main/assets/upload' / f).is_file(), 'android/app/src/main/assets/upload/' + f, 'missing')
    check(not (ROOT / 'web' / 'upload').exists(), 'web/upload', 'would clash with the phone page\'s assets/upload/')
    page = read('android/app/src/main/assets/upload/app.js') + read('android/app/src/main/assets/upload/index.html')
    for gone in ('restore', 'Restore'):
        check(gone not in page, 'android/app/src/main/assets/upload/', 'the phone page still mentions %r (no archive in v2)' % gone)


def test_library_startup_off_the_ui_thread():
    """Review 687edfd: onCreate -> startPage -> dropPage -> stopUpload -> lib() ran removeStale() (a walk
    of every group, JSON reads, deleteTree) and the cache sweep on the main thread at every process start,
    holding the activity's lock. Now lib() only starts that work on its own thread, nothing the UI thread
    runs makes the library, and uploadStart() waits for the sweep before the server can write a temp file."""
    src = java()
    lib = method(src, 'lib')
    task = re.search(r'new\s+FutureTask<\w*>\(\s*\(\)\s*->\s*\{(.*?)\}\s*\)\s*;', lib, re.S)
    check(task and re.search(r'\bp\.removeStale\(\)', task.group(1)) and re.search(r'\btmp\.listFiles\(\)', task.group(1)), JAVA_REL,
          'lib() does not put the start-up clean-up (stale unfinished photos, leftover upload temp files) in a task')
    rest = lib.replace(task.group(0), '')
    check('removeStale' not in rest and 'listFiles' not in rest, JAVA_REL, 'lib() still runs clean-up on the calling thread')
    check(re.search(r'new\s+Thread\(\s*task\s*,\s*"[\w-]+"\s*\)', rest) and re.search(r'\bt\.start\(\)', rest), JAVA_REL,
          'lib() does not run the clean-up task on its own thread')
    for ui in ('onCreate', 'onStart', 'onResume', 'onPause', 'onStop', 'onDestroy', 'startPage', 'dropPage', 'stopUpload', 'showFallback'):
        check(not re.search(r'\b(?:lib|pending)\(\)', method(src, ui)), JAVA_REL,
              '%s() (UI thread) makes the photo library, and with it the start-up clean-up' % ui)
    up = bridge_method('uploadStart')
    wait, gate = up.find('awaitCleanup()'), up.find('uploadGate.start(')
    check(0 <= wait < gate, JAVA_REL, 'Bridge.uploadStart() does not wait for the clean-up (before uploadGate.start()) before the server starts')
    # review 7c2b1e5: the wait was 30 s inside a synchronous bridge call (WebView's unresponsive renderer,
    # HangWatch); now at most CLEANUP_WAIT_MS, then a busy answer the page shows with Try again
    check(re.search(r'if\s*\(\s*!\s*awaitCleanup\(\)\s*\)\s*return\s+UserLibrary\.fail\(\s*BUSY\s*\)\s*;', up), JAVA_REL,
          'Bridge.uploadStart() does not answer BUSY when the clean-up is not over in time')
    m = re.search(r'static\s+final\s+long\s+CLEANUP_WAIT_MS\s*=\s*([\d_]+)L?\s*;', src)
    check(m and 0 < int(m.group(1).replace('_', '')) <= 3000, JAVA_REL, 'CLEANUP_WAIT_MS is not a few seconds at most')
    aw = method(src, 'awaitCleanup')
    check('UploadGate.awaitDone(task, CLEANUP_WAIT_MS)' in aw and not re.search(r'\.get\(', aw), JAVA_REL,
          'awaitCleanup() does not wait through UploadGate.awaitDone(task, CLEANUP_WAIT_MS) (tests/java/UploadGateTest.java)')
    pend = strip_comments(read(PENDING_REL))
    stale = block_after(pend, r'synchronized\s+int\s+removeStale\s*\(\s*\)\s*\{', PENDING_REL, 'removeStale()')
    check(re.fullmatch(r'\s*synchronized\s*\(\s*lib\s*\)\s*\{\s*return\s+removeStaleLocked\(\)\s*;\s*\}\s*', stale), PENDING_REL,
          'removeStale() does not hold the library\'s lock (it runs beside the page\'s bridge calls now)')


def test_late_photo_writes_nothing():
    """Review 999ba07 (L3): f.cancel(true) cannot stop a decode, so a photo past its deadline went on to
    write meta.json. AndroidPhotoProcessor runs each photo through PhotoProcessor.Attempt (behaviour:
    tests/upload/PhotoAttemptTest.java) and commits before its first write."""
    rel = 'android/app/src/main/java/home/cleanframe/tv/AndroidPhotoProcessor.java'
    src = strip_comments(read(rel))
    proc = block_after(src, r'public\s+void\s+process\s*\([^)]*\)\s*throws[\w\s,]*\{', rel, 'process()')
    check(re.fullmatch(r'\s*Attempt\.run\(\s*worker\s*,\s*TIMEOUT_S\s*\*\s*1000\s*,\s*QUEUE_S\s*\*\s*1000\s*,\s*a\s*->\s*prepare\([^;]*,\s*a\s*\)\s*\)\s*;\s*', proc),
          rel, 'process() does not run prepare() through PhotoProcessor.Attempt (deadline from its start, queue capped: review 30e7356 A2)')
    prep = block_after(src, r'static\s+void\s+prepare\s*\([^)]*\)\s*throws[\w\s,]*\{', rel, 'prepare()')
    commit, first = prep.find('attempt.commit(dir)'), prep.find('Files.write(')
    check(0 <= commit < first, rel, 'prepare() writes before attempt.commit(dir): a photo given up on could still be written')
    check(prep.count('attempt.commit(') == 1 and 'cancel(' not in src, rel, 'one commit point, and no cancel() of its own')


def test_whole_decode_is_memory_bounded():
    """Review 687edfd: decodeWhole held a 16 MP bitmap and its cut at once (~103 MB). Its sample size is
    PhotoMath.wholeSample()'s (8 MP whole, under 64 MB with the cut; tests/upload/PhotoMathTest.java)."""
    rel = 'android/app/src/main/java/home/cleanframe/tv/AndroidPhotoProcessor.java'
    src = strip_comments(read(rel))
    body = block_after(src, r'Bitmap\s+decodeWhole\s*\([^)]*\)\s*\{', rel, 'decodeWhole()')
    at, dec = body.find('o.inSampleSize = PhotoMath.wholeSample(w, h, o.inSampleSize);'), body.find('BitmapFactory.decodeFile(')
    check(0 <= at < dec, rel, 'decodeWhole() does not take PhotoMath.wholeSample()\'s sample size before decoding')
    check(len(re.findall(r'BitmapFactory\.decodeFile\(\s*path\s*,\s*o\s*\)', src)) == 1, rel,
          'a second whole-picture decode outside decodeWhole()')


def test_no_unused_string_constants():
    """Review 687edfd: PendingPhotos.NOT_DONE was declared and never used. Every `static final String X =`
    under android/ is named again somewhere in android/'s own sources (tests alone do not count)."""
    srcs = {p: strip_comments(p.read_text(encoding='utf-8')) for p in sorted((ROOT / 'android').rglob('*.java'))}
    every = '\n'.join(srcs.values())
    unused = []
    for p, s in srcs.items():
        for name in re.findall(r'static\s+final\s+String\s+(\w+)\s*=', s):
            if len(re.findall(r'\b%s\b' % name, every)) < 2:
                unused.append('%s.%s' % (p.stem, name))
    check(not unused, 'android/', 'unused String constants (dead code): ' + ', '.join(unused))


def test_upload_classes_plain_java():
    ut = read(UPLOAD_TEST_REL)
    for name in PLAIN_UPLOAD:
        rel = 'android/app/src/main/java/home/cleanframe/tv/%s.java' % name
        src = strip_comments(read(rel))
        imports = re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', src, re.M)
        bad = [i for i in imports if not i.startswith('java.')]
        check(not bad, rel, 'imports %s: tests/upload.test.sh compiles it with plain javac' % ', '.join(bad))
        check(not re.search(r'\b(?:android|androidx|org\.json)\.', src), rel, 'names an android/androidx/org.json class inline')
        check('"$SRC/%s.java"' % name in ut, UPLOAD_TEST_REL, 'does not compile %s.java' % name)
    for t in ('UploadTests', 'ReadabilityTest', 'PhotoMathTest', 'PendingPhotosTest', 'PhotoAttemptTest', 'UploadServerTest'):
        check((ROOT / 'tests/upload' / (t + '.java')).is_file(), 'tests/upload/%s.java' % t, 'missing')
    return ', '.join(PLAIN_UPLOAD)


def test_user_library_plain_java():
    src = lib_src()
    imports = re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', src, re.M)
    bad = [i for i in imports if not i.startswith('java.')]
    check(not bad, LIB_REL, 'imports %s: tests/fallback.test.sh compiles it with plain javac' % ', '.join(bad))
    check(not re.search(r'\b(?:android|androidx|org\.json)\.', src), LIB_REL, 'names an android/androidx/org.json class inline')
    check(re.search(r'^package\s+home\.cleanframe\.tv\s*;', src, re.M), LIB_REL, 'not in package home.cleanframe.tv')
    fb = read(FALLBACK_TEST_REL)
    check(re.search(r'^CLASSES=\([^)]*"\$SRC/UserLibrary\.java"', fb, re.M), FALLBACK_TEST_REL,
          'CLASSES does not compile UserLibrary.java, so tests/java/UserLibraryTest.java cannot run')
    check((ROOT / 'tests/java/UserLibraryTest.java').is_file(), 'tests/java/UserLibraryTest.java', 'missing')
    for name, want in (('MAX_GROUPS', 20), ('MAX_PHOTOS_PER_GROUP', 60), ('MAX_PHOTOS', 300), ('NAME_MAX', 24)):
        m = re.search(r'\b%s\s*=\s*(\d+)\b' % name, src)
        check(m and int(m.group(1)) == want, LIB_REL, '%s is not %d (contract 1)' % (name, want))
    m = re.search(r'TOKEN_TTL_MS\s*=\s*([0-9 *]+)L\s*;', src)
    check(m and eval(m.group(1), {'__builtins__': {}}) == 15 * 60 * 1000, LIB_REL, 'TOKEN_TTL_MS is not 15 minutes')



# ---------- Settings > Screen and the Sleep menu ----------

def test_screen_bridge_never_throws():
    for name, call in (('screenPolicy', 'SleepControl.policy('), ('sleepNow', 'SleepControl.now('),
                       ('sleepCancel', 'SleepControl.cancel('), ('sleepStatus', 'SleepControl.status(')):
        body = bridge_method(name)
        check(call in body and re.search(r'catch\s*\(\s*Throwable\b', body), JAVA_REL,
              'Bridge.%s() does not call %s...) inside a catch (Throwable): an exception would reach the page' % (name, call))
    check('screensaver' not in bridge_methods(), JAVA_REL, 'the screensaver bridge is back (the Sleep button replaced it)')
    check('Somnambulator' not in strip_comments(java()), JAVA_REL, 'still starts the screensaver')
    check('sleepTimer' not in bridge_methods(), JAVA_REL, 'the countdown timer bridge is back (Sleep is one setting since 2026-11-01)')


def test_sleep_receiver_private():
    recv = [r for r in manifest(MANIFEST_REL).iter('receiver') if r.get(A + 'name') == '.SleepControl']
    check(len(recv) == 1, MANIFEST_REL, 'no single <receiver android:name=".SleepControl">')
    check(recv[0].get(A + 'exported') == 'false', MANIFEST_REL, '.SleepControl is exported: any app could change the screen timeout')
    check(not recv[0].findall('intent-filter'), MANIFEST_REL, '.SleepControl has an intent-filter (it only takes its own alarms)')
    src = strip_comments(read(SLEEP_REL))
    check(re.search(r'new\s+Intent\(\s*c\s*,\s*SleepControl\.class\s*\)', src) and 'FLAG_IMMUTABLE' in src, SLEEP_REL,
          'its alarms are not explicit, immutable intents to SleepControl')


def test_exact_alarms_never_throw():
    """Android 12+ throws on setExact without the permission; from onResume that crashed Home (2026-11-01)."""
    src = strip_comments(read(SLEEP_REL))
    calls = re.findall(r'\.setExact(?:AndAllowWhileIdle)?\s*\(', src)
    check(len(calls) == 1, SLEEP_REL, 'setExact is called %d times; only SleepControl.exact() may call it' % len(calls))
    body = method(src, 'exact')
    check('canScheduleExactAlarms()' in body and re.search(r'catch\s*\(\s*SecurityException\b', body)
          and re.search(r'\bam\.set\s*\(', body), SLEEP_REL,
          'exact() does not check canScheduleExactAlarms(), catch SecurityException and fall back to set()')
    for want, cap in (('SCHEDULE_EXACT_ALARM', '32'), ('USE_EXACT_ALARM', None)):
        el = [e for e in manifest(MANIFEST_REL).iter('uses-permission') if e.get(A + 'name') == 'android.permission.' + want]
        check(len(el) == 1 and el[0].get(A + 'maxSdkVersion') == cap, MANIFEST_REL,
              '%s is not declared once with maxSdkVersion %s' % (want, cap))


def test_resume_applies_screen_policy():
    body = method(java(), 'onResume')
    check('SleepControl.woke(this)' in body and 'SleepControl.check(this)' in body, JAVA_REL,
          'onResume() does not end Sleep now, re-apply the screen timeout and restart the playback watch')
    # Review 687edfd: a RuntimeException from these already crashed Home on every start once (SleepControl
    # .exact(), 2026-11-01). Each is caught on its own (one failing never skips the other) and logged.
    for call in ('woke', 'check'):
        check(re.search(r'try\s*\{\s*SleepControl\.%s\(this\);\s*\}\s*catch\s*\(\s*(?:RuntimeException|Throwable)\s+(\w+)\s*\)\s*'
                        r'\{\s*Log\.[wei]\(TAG,[^;]*\1\s*\)\s*;\s*\}' % call, body), JAVA_REL,
              'onResume() does not catch and log a RuntimeException from SleepControl.%s(this) on its own' % call)
    # review 7c2b1e5: onReceive ran check()/restore() unguarded, from the background in Home's process
    recv = method(strip_comments(read(SLEEP_REL)), 'onReceive')
    check(re.fullmatch(r'\s*try\s*\{.*\bcheck\(c\);.*\brestore\(c\);.*\}\s*catch\s*\(\s*(?:RuntimeException|Throwable)\s+(\w+)\s*\)\s*'
                       r'\{\s*Log\.[wei]\([^;]*\1\s*\)\s*;\s*\}\s*', recv, re.S), SLEEP_REL,
          'SleepControl.onReceive() does not run all of its work inside one try that catches and logs a RuntimeException')
    woke = method(strip_comments(read(SLEEP_REL)), 'woke')
    check('K_SLEEPING, 0' in woke and 'apply(c)' in woke, SLEEP_REL,
          'woke() does not clear Sleep now and write Sleep after\'s timeout again')


def test_sleep_now_keeps_sleep_after():
    """2026-11-01: Sleep now "keeps the original setting after": it never writes K_SLEEP / K_DIM."""
    src = strip_comments(read(SLEEP_REL))
    now = method(src, 'now')
    check('canWrite(' in now and 'NO_WRITE' in now, SLEEP_REL, 'now() does not refuse when the TV will not let it write the timeout')
    check('ScreenPolicy.sleepingUntil(' in now and 'ACTION_RESTORE' in now and 'apply(c)' in now, SLEEP_REL,
          'now() does not write the short timeout and schedule the restore')
    check('K_SLEEP,' not in now and 'K_DIM' not in now, SLEEP_REL, 'now() changes the saved Sleep after / Dim after')
    check('restore(c)' in method(src, 'cancel'), SLEEP_REL, 'cancel() does not restore Sleep after\'s timeout')


def test_screen_policy_plain_java_and_tested():
    src = read(SCREEN_REL)
    bad = [i for i in re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', src, re.M) if not i.startswith('java.')]
    check(not bad and not re.search(r'\b(?:android|androidx|org\.json)\.', strip_comments(src)), SCREEN_REL,
          'ScreenPolicy must stay plain Java (tests/fallback.test.sh compiles it with plain javac)')
    check('$SRC/ScreenPolicy.java' in read(FALLBACK_TEST_REL), FALLBACK_TEST_REL, 'does not compile ScreenPolicy.java')
    check((ROOT / 'tests/java/ScreenPolicyTest.java').is_file(), 'tests/java/', 'no ScreenPolicyTest.java')
    sleep = strip_comments(read(SLEEP_REL))
    for d in ('ScreenPolicy.timeoutNow(', 'ScreenPolicy.sleepingUntil(', 'ScreenPolicy.holdForPlayback(', 'ScreenPolicy.parse('):
        check(d in sleep, SLEEP_REL, 'SleepControl does not ask %s...): the decision would be untested' % d.rstrip('('))



def test_app_shell_tests_parse_for_shellcheck():
    """The app's shell tests (tests/*.test.sh but tools.test.sh, which tests/tools.test.sh lints with the
    tools): no "# shellcheck" comment that is not a key=value directive (SC1073/SC1072: shellcheck stops
    reading the file) and no "NAME= value" (SC1007: runs value as a command). shellcheck is not assumed installed."""
    bad_sc = re.compile(r'^\s*#\s*shellcheck(\s|$)')
    good_sc = re.compile(r'#\s*shellcheck(\s+(disable|enable|source|source-path|shell|external-sources)=\S+)+(\s+#.*)?$')
    empty = re.compile(r'(^|;)\s*((local|export|declare)\s+)?[A-Za-z_]\w*=\s+[^\s#;&|)]')
    for sample, want in (('JAVAC= JAVA=', True), ("JAVAC=''; JAVA=''", False), ('# shellcheck is not installed', True),
                         ('# shellcheck disable=SC2034  # why', False)):
        got = bool(bad_sc.search(sample) and not good_sc.search(sample)) or bool(empty.search(sample) and not sample.lstrip().startswith('#'))
        check(got == want, 'tests/android.test.py', 'lint self-test: %r flagged=%s' % (sample, got))
    files = sorted(p for p in (ROOT / 'tests').glob('*.test.sh') if p.name != 'tools.test.sh')
    check(files, 'tests/', 'no app shell tests found')
    for f in files:
        for n, line in enumerate(f.read_text(encoding='utf-8').splitlines(), 1):
            rel = 'tests/%s:%d' % (f.name, n)
            check(not (bad_sc.search(line) and not good_sc.search(line)), rel, 'not a shellcheck directive: %r' % line.strip())
            check(line.lstrip().startswith('#') or not empty.search(line), rel, "NAME= value (SC1007; write NAME=''): %r" % line.strip())


def test_settings_pages_open_fresh():
    # 2026-11-02, Xiaomi: App info for Emby showed YouTube's page. TV Settings' task stays in the background
    # after Home, and NEW_TASK alone brings it back with its old top page; CLEAR_TASK opens a fresh one.
    src = java()
    fresh = method(src, 'startFresh')
    check('FLAG_ACTIVITY_CLEAR_TASK' in fresh and re.search(r'\bstart\(', fresh), JAVA_REL,
          'startFresh() does not add FLAG_ACTIVITY_CLEAR_TASK and go through start()')
    for name in ('appInfo', 'settings', 'bluetoothPair'):
        body = method(src, name)
        check('startFresh(' in body and not re.search(r'(?<!\w)start\(', body), JAVA_REL,
              '%s() does not open its Settings page with startFresh()' % name)


def test_bluetooth_bridge():
    """Settings > Bluetooth (2026-11-03). bluetooth() answers from the adapter and the profile proxies
    opened in onCreate (never waiting for one) and BluetoothReport (plain Java, tests/java/BluetoothReportTest.java)
    builds the JSON; bluetoothPair() opens TV Settings' pairing page (CONNECT_INPUT: the Bluetooth settings
    actions resolve to nothing on either stick) fresh; no address leaves the app; BLUETOOTH_CONNECT is never
    asked for with a dialog."""
    src, body = java(), bridge_body()
    for name, arity in sorted(BLUETOOTH_BRIDGE.items()):
        m = re.search(r'@JavascriptInterface\s+public\s+String\s+%s\s*\(([^)]*)\)' % name, body)
        check(m and len([a for a in m.group(1).split(',') if a.strip()]) == arity, JAVA_REL,
              'Bridge.%s() with %d arguments returning String (the contract given to web/device.js) is missing' % (name, arity))
    bt = bridge_method('bluetooth')
    check(re.fullmatch(r'\s*try\s*\{\s*return\s+bluetoothReport\(\)\s*;\s*\}\s*catch\s*\(\s*Throwable\s+\w+\s*\)\s*\{\s*return\s+BluetoothReport\.fail\([^;]*\)\s*;\s*\}\s*', bt),
          JAVA_REL, 'Bridge.bluetooth() is not `try { return bluetoothReport(); } catch (Throwable e) { return BluetoothReport.fail(...); }`')
    check(re.search(r'static\s+final\s+String\s+CONNECT_INPUT\s*=\s*"com\.google\.android\.intent\.action\.CONNECT_INPUT"\s*;', src)
          and 'startFresh(new Intent(CONNECT_INPUT))' in bridge_method('bluetoothPair'), JAVA_REL,
          'bluetoothPair() does not open CONNECT_INPUT with startFresh()')
    report = method(src, 'bluetoothReport')
    for want in ('BluetoothReport.reason(', 'BluetoothReport.ok(', 'getBondedDevices()', 'BLUETOOTH_CONNECT', 'catch (SecurityException'):
        check(want in report, JAVA_REL, 'bluetoothReport() does not use %s' % want)
    check(not re.search(r'getProfileProxy|\bwait\(|\.await\(|\.get\(\s*\d|sleep\(', report), JAVA_REL,
          'bluetoothReport() asks for or waits on a proxy: it must answer from the cached ones at once')
    check('openBluetoothProxies()' in method(src, 'onCreate') and 'closeBluetoothProxies()' in method(src, 'onDestroy'), JAVA_REL,
          'the profile proxies are not opened in onCreate() and closed in onDestroy()')
    check(src.count('getProfileProxy(') == 1 and 'getProfileProxy(' in method(src, 'openBluetoothProxies')
          and src.count('closeProfileProxy(') == 1 and 'closeProfileProxy(' in method(src, 'closeProxy'), JAVA_REL,
          'getProfileProxy()/closeProfileProxy() outside openBluetoothProxies()/closeProxy()')
    # review 7c2b1e5: a proxy delivered after onDestroy was stored on the destroyed activity and never
    # closed, and onServiceDisconnected dropped the only reference. ProfileProxies owns them now.
    check(re.search(r'private\s+final\s+ProfileProxies<BluetoothProfile>\s+btProxies\s*=\s*new\s+ProfileProxies<>\(\s*this::closeProxy\s*\)\s*;', src),
          JAVA_REL, 'the profile proxies are not held by ProfileProxies (closer: closeProxy)')
    opened = method(src, 'openBluetoothProxies')
    check(re.search(r'onServiceConnected\([^)]*\)\s*\{\s*btProxies\.connected\(\s*profile\s*,\s*proxy\s*\)\s*;\s*\}', opened)
          and re.search(r'onServiceDisconnected\([^)]*\)\s*\{\s*btProxies\.disconnected\(\s*profile\s*\)\s*;', opened), JAVA_REL,
          'the profile listener does not hand every proxy to btProxies.connected()/disconnected()')
    check(re.fullmatch(r'\s*btProxies\.destroy\(\)\s*;\s*', method(src, 'closeBluetoothProxies')), JAVA_REL,
          'closeBluetoothProxies() is not btProxies.destroy()')
    check(re.match(r'\s*closeBluetoothProxies\(\)\s*;', method(src, 'onDestroy')), JAVA_REL,
          'onDestroy() does not mark the proxies destroyed first')
    check(not re.search(r'\bbt(?:A2dp|Headset|Hid)\b', strip_comments(src)), JAVA_REL, 'a proxy is kept outside btProxies')
    pp_src = read(PROXIES_REL)
    bad = [i for i in re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', pp_src, re.M) if not i.startswith('java.')]
    check(not bad and not re.search(r'\b(?:android|androidx)\.', strip_comments(pp_src)), PROXIES_REL,
          'ProfileProxies must stay plain Java (tests/fallback.test.sh compiles it with plain javac)')
    check('$SRC/ProfileProxies.java' in read(FALLBACK_TEST_REL), FALLBACK_TEST_REL, 'does not compile ProfileProxies.java')
    check((ROOT / 'tests/java/ProfileProxiesTest.java').is_file(), 'tests/java/', 'no ProfileProxiesTest.java')
    for f in android_sources():
        code = strip_comments(f.read_text(encoding='utf-8'))
        rel = str(f.relative_to(ROOT))
        check(not re.search(r'\bgetAddress\s*\(', code), rel, 'reads a Bluetooth device address (never part of the answer)')
        check(not re.search(r'\brequestPermissions\s*\(|ACTION_REQUEST_ENABLE|ACTION_REQUEST_DISCOVERABLE', code), rel,
              'asks for a permission or for Bluetooth with a dialog: BLUETOOTH_CONNECT is granted over adb, Home never asks')
    for want, cap in (('BLUETOOTH', '30'), ('BLUETOOTH_ADMIN', '30'), ('BLUETOOTH_CONNECT', None)):
        el = [e for e in manifest(MANIFEST_REL).iter('uses-permission') if e.get(A + 'name') == 'android.permission.' + want]
        check(len(el) == 1 and el[0].get(A + 'maxSdkVersion') == cap, MANIFEST_REL,
              '%s is not declared once with maxSdkVersion %s' % (want, cap))
    staging = manifest(STAGING_REL)
    check(not [e for e in staging.iter() if 'permission' in e.tag], STAGING_REL,
          'the staging manifest touches permissions: it must inherit the release ones (Bluetooth included) unchanged')
    rep_src = read(BLUETOOTH_REL)
    bad = [i for i in re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', rep_src, re.M) if not i.startswith('java.')]
    check(not bad and not re.search(r'\b(?:android|androidx|org\.json)\.', strip_comments(rep_src)), BLUETOOTH_REL,
          'BluetoothReport must stay plain Java (tests/fallback.test.sh compiles it with plain javac)')
    check('$SRC/BluetoothReport.java' in read(FALLBACK_TEST_REL), FALLBACK_TEST_REL, 'does not compile BluetoothReport.java')
    check((ROOT / 'tests/java/BluetoothReportTest.java').is_file(), 'tests/java/', 'no BluetoothReportTest.java')
    check(not re.search(r'\{\s*"ok"|\\"devices\\"|"devices"', strip_comments(report)), JAVA_REL,
          'bluetoothReport() writes JSON itself: BluetoothReport builds it')


def test_bluetooth_unpair():
    """Settings > Bluetooth's Unpair (2026-11-03): BluetoothDevice.removeBond is not public SDK, so it is
    looked up by reflection, once, in removeBondMethod(), and invoked in one place. Unpairing the remote
    would strand the stick, so BluetoothBonds decides on the device's state read now and the bridge never
    throws. Plain-Java logic: tests/java/BluetoothReportTest.java, tests/java/BluetoothBondsTest.java."""
    src = java()
    for f in android_sources():
        code = strip_comments(f.read_text(encoding='utf-8'))
        rel = str(f.relative_to(ROOT))
        want = 1 if rel == JAVA_REL else 0
        check(code.count('removeBond"') == want, rel, 'removeBond must be named exactly once, in MainActivity.removeBondMethod()')
        check(len(re.findall(r'\.getMethod\s*\(|\.getDeclaredMethod\s*\(|\.invoke\s*\(', code)) == (2 if rel == JAVA_REL else 0), rel,
              'reflection other than the one removeBond lookup and its one invoke')
    check(re.search(r'BluetoothDevice\.class\.getMethod\(\s*"removeBond"\s*\)', method(src, 'removeBondMethod')), JAVA_REL,
          'removeBondMethod() does not look removeBond up on BluetoothDevice')
    check('catch (Throwable' in method(src, 'removeBondMethod') and 'return null' in method(src, 'removeBondMethod'), JAVA_REL,
          'a removeBond the TV hides must read as canUnpair false, not a crash')
    now = method(src, 'bluetoothUnpairNow')
    check('.invoke(' in now and 'btBonds.unpair(' in now and re.search(r'btEntry\(', now), JAVA_REL,
          'bluetoothUnpairNow() must go through btBonds.unpair() with the state read now (btEntry)')
    check('BLUETOOTH_ADMIN' in now and 'BLUETOOTH_CONNECT' in now, JAVA_REL, 'bluetoothUnpairNow() does not check its permission')
    check('removeBondMethod() != null' in method(src, 'bluetoothReport'), JAVA_REL, 'canUnpair is not "removeBond resolved"')
    check(re.search(r'try\s*\{\s*return\s+bluetoothUnpairNow\(\s*id\s*\)\s*;\s*\}\s*catch\s*\(\s*Throwable\s+\w+\s*\)\s*\{\s*return\s+BluetoothReport\.UNPAIR_FAILED\s*;\s*\}', bridge_method('bluetoothUnpair')),
          JAVA_REL, 'Bridge.bluetoothUnpair() must answer a reason, never throw')
    entry = method(src, 'btEntry')
    check('d.getType()' in entry and 'links(btProxies.get(PROFILE_HID_HOST))' in entry
          and re.search(r'input\s*!=\s*null\s*&&\s*input\.contains\(\s*d\s*\)\s*,\s*input\s*!=\s*null\s*\)', entry), JAVA_REL,
          'btEntry() must read the type, and hand on whether the HID links could be read at all (hidKnown)')
    check('c.getDeviceClass()' in entry and 'btUuids(d)' in entry and 'd.getUuids()' in method(src, 'btUuids'), JAVA_REL,
          'btEntry() must read the full device class and the UUIDs (unpairable decides on what the device is)')
    check('emptyList' not in method(src, 'links') and not re.search(r'\?\s*\w*\s*:', method(src, 'links')), JAVA_REL,
          'links(): a null list from a proxy must stay null, "cannot tell", not become "nothing connected"')
    rep_code = strip_comments(read(BLUETOOTH_REL))
    check('kind(' not in method(rep_code, 'unpairable') and 'audioLink' not in method(rep_code, 'unpairable'), BLUETOOTH_REL,
          'unpairable() must not read kind() or a connected audio link: it decides on what the device is')
    bonds = read(BONDS_REL)
    bad = [i for i in re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', bonds, re.M) if not i.startswith('java.')]
    check(not bad and not re.search(r'\b(?:android|androidx|org\.json)\.', strip_comments(bonds)), BONDS_REL,
          'BluetoothBonds must stay plain Java (tests/fallback.test.sh compiles it with plain javac)')
    check(not re.search(r'hashCode\s*\(|\.toString\s*\(|SecureRandom', strip_comments(bonds)), BONDS_REL,
          'a token must come from the counter, nothing of the device (BluetoothDevice.toString() and hashCode() are its address)')
    check('$SRC/BluetoothBonds.java' in read(FALLBACK_TEST_REL), FALLBACK_TEST_REL, 'does not compile BluetoothBonds.java')
    check((ROOT / 'tests/java/BluetoothBondsTest.java').is_file(), 'tests/java/', 'no BluetoothBondsTest.java')


# Removing a package without Android's dialog (or installing one): none of these may appear under android/.
SILENT_PACKAGE_APIS = [
    (r'\bPackageInstaller\b', 'PackageInstaller'),
    (r'\bgetPackageInstaller\s*\(', 'getPackageInstaller'),
    (r'\bdeletePackage\w*\b', 'deletePackage'),
    (r'\bDELETE_PACKAGES\b(?<!REQUEST_DELETE_PACKAGES)', 'DELETE_PACKAGES'),
    (r'\bACTION_UNINSTALL_PACKAGE\b', 'ACTION_UNINSTALL_PACKAGE'),
    (r'["\s]pm\s+uninstall\b', 'pm uninstall'),
]


def test_uninstall():
    """The long-press menu's Uninstall (2026-11-04). canUninstall() and uninstall() answer from
    UninstallPolicy (plain Java, tests/java/UninstallPolicyTest.java) on what Android says now; a refused
    package never reaches Android's dialog, which is the only way anything is removed; neither can throw
    to the page."""
    src, body = java(), bridge_body()
    for name, arity in sorted(UNINSTALL_BRIDGE.items()):
        m = re.search(r'@JavascriptInterface\s+public\s+String\s+%s\s*\(([^)]*)\)' % name, body)
        check(m and len([a for a in m.group(1).split(',') if a.strip()]) == arity, JAVA_REL,
              'Bridge.%s() with %d arguments returning String (the contract given to web/device.js) is missing' % (name, arity))
    can = strip_comments(bridge_method('canUninstall'))
    check(re.search(r'try\s*\{\s*return\s+uninstallRefusal\(\s*pkg\s*\)\.isEmpty\(\)\s*\?\s*"true"\s*:\s*"false"\s*;\s*\}\s*catch\s*\(\s*Throwable\s+\w+\s*\)\s*\{\s*return\s+"false"\s*;', can),
          JAVA_REL, 'Bridge.canUninstall() must answer "true"/"false" from uninstallRefusal(), and "false" on any throw')
    un = strip_comments(bridge_method('uninstall'))
    check(re.search(r'String\s+why\s*=\s*uninstallRefusal\(\s*pkg\s*\)\s*;\s*if\s*\(\s*!why\.isEmpty\(\)\s*\)\s*return\s+why\s*;', un), JAVA_REL,
          'Bridge.uninstall() must check uninstallRefusal() itself and return its reason first')
    check(un.find('uninstallRefusal(') < un.find('startFresh(') and 'new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))' in un, JAVA_REL,
          'Bridge.uninstall() does not open ACTION_DELETE on package:<pkg> with startFresh() after the check')
    check(re.search(r'catch\s*\(\s*Throwable\s+\w+\s*\)\s*\{\s*return\s+UninstallPolicy\.FAILED\s*;', un), JAVA_REL, 'Bridge.uninstall() can throw to the page')
    check(strip_comments(src).count('ACTION_DELETE') == 1, JAVA_REL, 'ACTION_DELETE outside Bridge.uninstall()')
    ref = method(src, 'uninstallRefusal')
    for want in ('UninstallPolicy.refusal(', 'getApplicationInfo(', 'FLAG_SYSTEM', 'FLAG_UPDATED_SYSTEM_APP', 'CATEGORY_HOME', 'getPackageName()'):
        check(want in ref, JAVA_REL, 'uninstallRefusal() does not use %s' % want)
    hits = []
    for f in android_sources():
        code = strip_comments(f.read_text(encoding='utf-8'))
        for rx, what in SILENT_PACKAGE_APIS:
            if re.search(rx, code):
                hits.append('%s: %s' % (f.relative_to(ROOT), what))
    check(not hits, 'android/', 'an API that changes packages without Android\'s dialog: ' + '; '.join(hits))
    for sample, what in (('PackageInstaller i', 'PackageInstaller'), ('pm.deletePackageAsUser(p)', 'deletePackage')):
        check(any(re.search(rx, sample) for rx, _ in SILENT_PACKAGE_APIS), 'tests/android.test.py', 'the ban misses %s' % what)
    plain = read(UNINSTALL_REL)
    check(not re.search(r'^\s*import\s+(?!java\.)', plain, re.M) and not re.search(r'\b(?:android|androidx|org\.json)\.', re.sub(r'"[^"\n]*"', '""', strip_comments(plain))), UNINSTALL_REL,
          'UninstallPolicy must stay plain Java (tests/fallback.test.sh compiles it with plain javac)')
    check('$SRC/UninstallPolicy.java' in read(FALLBACK_TEST_REL), FALLBACK_TEST_REL, 'does not compile UninstallPolicy.java')
    check((ROOT / 'tests/java/UninstallPolicyTest.java').is_file(), 'tests/java/', 'no UninstallPolicyTest.java')


def test_bridge_calls_from_a_dropped_page_refused():
    """Review 30e7356 (nit): only uploadStart/uploadStop checked owner == web. A dropped page cannot
    open apps or Settings, nor change the library."""
    for name in ('launch', 'settings', 'appInfo', 'bluetoothPair', 'bluetoothUnpair', 'uninstall'):
        check(re.match(r'\s*if\s*\(\s*owner\s*!=\s*web\s*\)\s*return\s+NOT_SHOWN\s*;', bridge_method(name)), JAVA_REL,
              'Bridge.%s() does not first refuse a dropped page (owner != web)' % name)
    w = block_after(bridge_body(), r'private\s+String\s+withLib\s*\([^)]*\)\s*\{', JAVA_REL, 'Bridge.withLib()')
    check(re.fullmatch(r'\s*if\s*\(\s*owner\s*!=\s*web\s*\)\s*return\s+UserLibrary\.fail\(\s*NOT_SHOWN\s*\)\s*;\s*return\s+MainActivity\.this\.withLib\(\s*call\s*\)\s*;\s*', w),
          JAVA_REL, 'Bridge.withLib() does not refuse a dropped page before MainActivity.withLib()')


def test_lan_address_skips_unreadable_interfaces():
    """Review 30e7356 (A5): one interface throwing in isUp() made the whole lookup say "No network connection"."""
    la = method(java(), 'lanAddress')
    loop = block_after(la, r'for\s*\(\s*NetworkInterface\s+n\s*:[^{]*\{', JAVA_REL, 'lanAddress() loop')
    check(re.fullmatch(r'\s*try\s*\{\s*list\.add\(.*\);\s*\}\s*catch\s*\(\s*IOException\s*\|\s*RuntimeException\s+\w+\s*\)\s*\{\s*\}\s*', loop, re.S),
          JAVA_REL, 'lanAddress() does not catch IOException | RuntimeException for each interface on its own')


CASES = [(name[5:].replace('_', ' '), fn) for name, fn in list(globals().items())
         if name.startswith('test_') and callable(fn)]


def main():
    t0, failed = time.monotonic(), 0
    for name, fn in CASES:
        try:
            detail = fn()
            print('ok   %s%s' % (name, ' (%s)' % detail if detail else ''))
        except Fail as e:
            failed += 1
            print('FAIL %s: %s' % (name, e))
        except Exception as e:  # a broken test is a failure, never a pass
            failed += 1
            print('FAIL %s: test error %s: %s' % (name, type(e).__name__, e))
    print('%d/%d passed in %.2f s' % (len(CASES) - failed, len(CASES), time.monotonic() - t0))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

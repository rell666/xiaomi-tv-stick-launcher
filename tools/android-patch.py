#!/usr/bin/env python3
"""Apply the Android changes to a CleanFrame web/ tree, or verify that a tree is exactly
upstream + this patch + the files we own.

    tools/android-patch.py web            apply (no-op if already applied)
    tools/android-patch.py --verify web   exit non-zero unless web/ is exactly upstream + patch + OURS
    tools/android-patch.py --write-manifest COMMIT
                                          rebuild tools/upstream-manifest.json (see below)

Every replacement is (old, new, count): an unpatched tree has each `old` exactly `count` times,
a patched tree each `new`. All the patched files are checked before anything is written; they are written
together or not at all. A tree that is partly patched, or where upstream moved the code, fails
naming the anchor instead of being silently half-applied (write_all: temp files first, then
os.replace, the old bytes put back if a move fails). Files are read and written as bytes
(decoded UTF-8, no newline translation), so a CRLF-converted file never matches."""
import hashlib, json, os, pathlib, subprocess, sys

# Pinned upstream: github.com/nthnclrk/CleanFrame app/ at this SHA (also recorded in README.md),
# vendored unmodified into web/ in commit 065aa2b.
UPSTREAM_SHA = '58ac92a7b8f80047ca26e5b7097f5e1616762db0'

# tools/upstream-manifest.json: SHA-256 of EVERY upstream file under web/ at UPSTREAM_SHA. --verify
# requires each one on disk with that hash, except the patched files (FILES, checked by
# reverse-applying the patch) and the upstream files in OURS (changed on purpose). To take a new
# upstream: copy upstream app/ over web/ in its own commit (before restoring OURS or patching), whose
# message names the full upstream SHA ("Vendor CleanFrame app/ at upstream <sha>"), set UPSTREAM_SHA
# above, then regenerate from that commit (it refuses a commit holding any of OURS but
# CHANGED_UPSTREAM, one with patched files, or one whose message does not name UPSTREAM_SHA unless
# the manifest already pins UPSTREAM_SHA from it):
#     python3 tools/android-patch.py --write-manifest <that-commit>
# For the current pin that is: python3 tools/android-patch.py --write-manifest 065aa2b
MANIFEST = pathlib.Path(__file__).resolve().parent / 'upstream-manifest.json'

# Every file under web/ that is NOT pristine upstream, with why. A file on disk that is neither in
# the manifest nor here fails --verify (stray keystores, editor backups, untracked junk: the APK
# packages whatever is on disk). Upstream paths listed here are exempt from the hash check.
# The QR generator for the Add photo screen, vendored unmodified (tests/patch.test.py checks the
# sha-256; tests/photo-groups.test.js encodes with it).
QR_FILE = 'qrcode-generator.js'
QR_SHA256 = '18ae399f81182bc9de916e9c77b195df20cc58d6f2d55a62b085a299f1bf1780'
QR_REASON = ('qrcode-generator 1.4.4 by Kazuhiko Arase (MIT, LICENSES/qrcode-generator-MIT.txt), qrcode.js unmodified '
             'from https://registry.npmjs.org/qrcode-generator/-/qrcode-generator-1.4.4.tgz (npm integrity '
             'sha512-HM7yY8O2ilqhmULxGMpcHSF1EhJJ9yBj8gvDEuZ6M+KGJ0YY2hKpnXvRD+hZPLrDVck3ExIGhmPtSdcjC+guuw==; '
             'git tag js1.4.4 of github.com/kazuhikoarase/qrcode-generator); sha256 ' + QR_SHA256)
OURS = {
    'device.js': 'replaces upstream\'s Tizen device layer with the Android one (AndroidHome bridge)',
    'assets/PHOTO-CREDIT.md': 'upstream file; we inlined the photographer table (docs/ not vendored)',
    'assets/icons/ADDITIONAL-SOURCES.json': 'upstream file; we appended the provenance of every mark we added (emby, rumble, iptv and the mark library)',
    'assets/icons/emby.svg': 'mark for the Emby default app (simple-icons, CC0)',
    'assets/icons/rumble.svg': 'mark for the Rumble default app (simple-icons, CC0)',
    'LICENSES/README.txt': 'licence notices shipped in the APK',
    'LICENSES/CleanFrame-MIT.txt': 'CleanFrame\'s MIT licence, shipped in the APK',
    'LICENSES/cleanframe-tv-MIT.txt': 'this repo\'s MIT licence, shipped in the APK',
    'wallpaper-groups.js': 'wallpaper groups (Scenery, Space, Cyberpunk, the user\'s photo groups): catalogue merge, group list, image paths, rotation; loaded by the patched index.html',
    'wallpaper-groups.css': 'styles for the Background page\'s Groups, the group page, Add group and Add photo',
    'photo-groups.js': 'the Add group / Rename group and Add photo screens and the group page\'s rules (docs/PHOTO-GROUPS.md)',
    'materials.js': 'draws an uploaded photo\'s panel and four materials in the WebView: an ES5 port of upstream scripts/build-materials.mjs (docs/PHOTO-GROUPS.md contract 4)',
    QR_FILE: QR_REASON,
    'LICENSES/qrcode-generator-MIT.txt': 'qrcode-generator\'s MIT licence (Kazuhiko Arase), shipped in the APK',
    'assets/wallpapers/space-catalog.js': 'the "Space" wallpaper group: appends 15 entries with group "space" after catalog.js',
    'assets/wallpapers/SPACE-SOURCES.json': 'source page, original URL, credit and licence of each Space wallpaper',
    'assets/SPACE-PHOTO-CREDIT.md': 'credit table for the Space wallpapers (CC BY 4.0 attribution, NASA public domain)',
    'home-extras.js': 'our Home additions: clock options, the long-press app menu, Settings > Screen (dim, sleep) and the Sleep menu (window.CleanFrameExtras); loaded by the patched index.html before main.js',
    'photo-crop.js': 'the crop arithmetic for uploaded photos (PhotoMath in JavaScript); also served to the phone as /static/crop.js (byte for byte the same file)',
    'photo-adjust.js': 'Adjust: frame an uploaded photo again on the TV (move, zoom, Save through FrameDevice.recrop)',
    'photo-adjust.css': 'styles for the Adjust screen',
    'bluetooth.js': 'Settings > Bluetooth: the row\'s value, the paired-devices page, Pair new device and Unpair (window.CleanFrameBluetooth); loaded by the patched index.html before main.js',
    'home-extras.css': 'styles for our Home additions (compact Settings rows, the dim layer, the Rename app and Change icon screens)',
    'app-edit.js': 'per-app name and icon overrides (own localStorage key) and the Rename app / Change icon screens (window.CleanFrameAppEdit); loaded by the patched index.html before main.js',
    'app-marks.js': 'the mark library Change icon offers: one { id, name, svg } per mark (window.CleanFrameAppMarks); loaded by the patched index.html before app-edit.js',
    'assets/icons/iptv.svg': 'original IPTV mark (cleanframe-tv, 24-unit grid; ADDITIONAL-SOURCES.json), assignable to any app with Change icon',
    'assets/icons/jellyfin.svg': 'mark for Jellyfin (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/plex.svg': 'mark for Plex (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/kodi.svg': 'mark for Kodi (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/vlc.svg': 'mark for VLC (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/itvx.svg': 'mark for ITVX (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/channel4.svg': 'mark for Channel 4 (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/now.svg': 'mark for NOW (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/paramountplus.svg': 'mark for Paramount+ (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/dazn.svg': 'mark for DAZN (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/mubi.svg': 'mark for MUBI (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/crunchyroll.svg': 'mark for Crunchyroll (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/twitch.svg': 'mark for Twitch (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/youtubemusic.svg': 'mark for YouTube Music (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/tidal.svg': 'mark for TIDAL (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/deezer.svg': 'mark for Deezer (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/youtubekids.svg': 'mark for YouTube Kids (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/googleplay.svg': 'mark for Google Play (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/vimeo.svg': 'mark for Vimeo (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/dailymotion.svg': 'mark for Dailymotion (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/soundcloud.svg': 'mark for SoundCloud (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/audible.svg': 'mark for Audible (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/pocketcasts.svg': 'mark for Pocket Casts (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/steam.svg': 'mark for Steam (for Steam Link) (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/playstation.svg': 'mark for PlayStation (for PS Remote Play) (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/nordvpn.svg': 'mark for NordVPN (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/expressvpn.svg': 'mark for ExpressVPN (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/surfshark.svg': 'mark for Surfshark (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/protonvpn.svg': 'mark for Proton VPN (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/mullvad.svg': 'mark for Mullvad (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/fubo.svg': 'mark for Fubo (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/starz.svg': 'mark for Starz (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/showtime.svg': 'mark for Showtime (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/sky.svg': 'mark for Sky (simple-icons 16.36.0, CC0; ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/bbciplayer.svg': 'original BBC iPlayer mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/my5.svg': 'original My5 mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/britbox.svg': 'original BritBox mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/plutotv.svg': 'original Pluto TV mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/discoveryplus.svg': 'original discovery+ mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/amazonmusic.svg': 'original Amazon Music mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/smarttube.svg': 'original SmartTube mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/tivimate.svg': 'original TiviMate mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/plexamp.svg': 'original Plexamp mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/xbox.svg': 'original Xbox mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/geforcenow.svg': 'original GeForce NOW mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/moonlight.svg': 'original Moonlight mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/purevpn.svg': 'original PureVPN mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/downloader.svg': 'original Downloader mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/xplore.svg': 'original X-plore mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
    'assets/icons/airscreen.svg': 'original AirScreen mark drawn for cleanframe-tv (ADDITIONAL-SOURCES.json), in the mark library',
}
# The upstream files in OURS (changed on purpose): the only entries of OURS that the upstream-copy
# commit may hold (--write-manifest refuses a commit with any other file of ours).
CHANGED_UPSTREAM = ('device.js', 'assets/PHOTO-CREDIT.md', 'assets/icons/ADDITIONAL-SOURCES.json')
assert all(rel in OURS for rel in CHANGED_UPSTREAM)
# Files we own that may be absent: allowed on disk like OURS, but not required. Empty since the
# Space gallery shipped (2026-10-31); space-catalog.js is now required (OURS). Without it the
# picker would show Scenery alone (index.html's request is a 404).
OPTIONAL = {
}
# The "Space" wallpaper group (2026-10-31): NASA public-domain and ESA/Webb, ESA/Hubble
# CC BY 4.0 images. Each id has the seven JPEGs an upstream wallpaper has, at the paths main.js
# builds from the id. tests/space-wallpapers.test.py checks this list against space-catalog.js.
SPACE_WALLPAPERS = ('cliffs', 'quintet', 'tarantula', 'nursery', 'deepfield', 'whirlpool', 'sombrero',
                    'cartwheel', 'crab', 'bubble', 'saturn', 'earthrise', 'earthset', 'aurora', 'moon')
SPACE_FILES = {'assets/wallpapers/%s.jpg': '1920x1280 background', 'assets/wallpapers/%s-thumb.jpg': 'thumbnail',
               'assets/wallpapers/%s-panel.jpg': 'panel backdrop',
               'assets/materials/%s-field-blur.jpg': 'Blur material', 'assets/materials/%s-field-clear.jpg': 'Tint material',
               'assets/materials/%s-field-frost.jpg': 'Frost material', 'assets/materials/%s-study-soft.jpg': 'Soft material'}
for _name in SPACE_WALLPAPERS:
    for _path, _what in SPACE_FILES.items():
        OURS[_path % ('space-' + _name)] = 'Space wallpaper space-%s: %s (credit in assets/SPACE-PHOTO-CREDIT.md)' % (_name, _what)
# The "Cyberpunk" wallpaper group (2026-11-01): neon night-city photographs from Wikimedia
# Commons under CC0, CC BY or CC BY-SA. Same seven JPEGs per id as Space, at the paths main.js builds
# from the id. tests/cyberpunk-wallpapers.test.py checks this list against cyberpunk-catalog.js.
CYBERPUNK_WALLPAPERS = ('mongkok', 'kabukicho', 'overpass', 'portland', 'yasukuni', 'crimson', 'towers', 'monsoon',
                        'pudong', 'goldengai', 'harbour', 'nathan', 'lanterns', 'fog', 'shibaura')
OURS.update({
    'assets/wallpapers/cyberpunk-catalog.js': 'the "Cyberpunk" wallpaper group: appends 15 entries with group "cyberpunk" after space-catalog.js',
    'assets/wallpapers/CYBERPUNK-SOURCES.json': 'source page, original URL, credit and licence of each Cyberpunk wallpaper',
    'assets/CYBERPUNK-PHOTO-CREDIT.md': 'credit table for the Cyberpunk wallpapers (CC BY / CC BY-SA attribution, CC0)',
})
for _name in CYBERPUNK_WALLPAPERS:
    for _path, _what in SPACE_FILES.items():
        OURS[_path % ('cyberpunk-' + _name)] = ('Cyberpunk wallpaper cyberpunk-%s: %s (credit in assets/CYBERPUNK-PHOTO-CREDIT.md)'
                                                % (_name, _what))

MAIN = [
    # (old, new, expected count of old in upstream)
    # Default Home apps: Android packages instead of the Tizen ones.
    ("""    { id: 'stremio', name: 'Stremio', mark: true, appId: 'IkWsFHtOY9.Stremio' },
    { id: 'netflix', name: 'Netflix', mark: true, appId: 'org.tizen.netflix-app' },
    { id: 'disneyplus', name: 'Disney+', mark: true, appId: 'MCmYXNxgcu.DisneyPlus' },
    { id: 'youtube', name: 'YouTube', mark: true, appId: '9Ur5IzDKqV.TizenYouTube' },
    { id: 'appletv', name: 'Apple TV', mark: true, appId: 'com.samsung.tv.aria-video' },
    { id: 'primevideo', name: 'Prime Video', mark: true, appId: 'org.tizen.primevideo' },
    { id: 'abciview', name: 'ABC iview', mark: true, appId: 'mWK87Xghap.ABCiview' },
    { id: 'sbs', name: 'SBS On Demand', mark: true, appId: 'OyE7OuxlUi.SBSOnDemand' },
    { id: 'applemusic', name: 'Apple Music', mark: true, appId: 'org.tizen.apple.apple-music' }
""", """    { id: 'emby', name: 'Emby', mark: true, appId: 'com.mb.android' },
    { id: 'youtube', name: 'YouTube', mark: true, appId: 'com.google.android.youtube.tv' },
    { id: 'spotify', name: 'Spotify', mark: true, appId: 'com.spotify.tv.android' },
    { id: 'rumble', name: 'Rumble', mark: true, appId: 'com.rumble.battles' }
""", 1),
    # The Art Mode control started the Android screensaver. Since 2026-11-01 it is the Sleep button
    # ("Screen and Sleep" below relabels it and answers it first). DEAD CODE, KEPT ON PURPOSE: the
    # patched utilAction returns on u === 'art' (openSleepMenu) before the line the second pair
    # writes, so that branch is unreachable and FrameDevice.art no longer exists; the first pair's
    # label is overwritten by UTIL.art = ['Sleep', 'sleep']. Neither changes behaviour. They stay
    # byte-for-byte as older commits patched them because an older SHA's patch test, run by
    # tools/rollback.sh against HEAD, must still recognise HEAD's main.js as patched. Do not remove
    # or "tidy" them; removing them changes main.js (and its manifest round-trip) for no gain.
    ("art: ['Art Mode', 'art']", "art: ['Screensaver', 'art']", 1),
    ("if (u === 'art') perform('Art Mode', FrameDevice.art);", "if (u === 'art') perform('Screensaver', FrameDevice.art);", 1),
    # Samsung settings group -> Android TV settings; there is no other Home screen to open.
    ("{ id: 'samsung', label: 'Samsung', value: 'Home and TV settings' }", "{ id: 'samsung', label: 'TV', value: 'Android TV settings' }", 1),
    ("rotation: 'Rotation', samsung: 'Samsung' }", "rotation: 'Rotation', samsung: 'TV' }", 1),
    ("""      { action: 'samsung.home', label: 'Samsung Home', description: 'Open Samsung’s Home screen' },
      { action: 'samsung.settings', label: 'TV settings', description: 'Picture, sound and network settings' }""",
     """      { action: 'samsung.settings', label: 'TV settings', description: 'Picture, sound, network and apps' }""", 1),
    ("group === 'rotation' ? 'rotation.' + S.c.mode : 'samsung.home');", "group === 'rotation' ? 'rotation.' + S.c.mode : 'samsung.settings');", 1),
]
MAIN += [
    # Android WebView widens innerWidth to the 1920px stage it is fitting (measured on the stick:
    # innerWidth 1920, clientWidth 960 at DPR 2), so fit() chose scale(1). Measure the viewport.
    ("""    var scale = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    stage.style.transform = 'scale(' + scale + ')';
    stage.style.left = ((window.innerWidth - 1920 * scale) / 2) + 'px';
    stage.style.top = ((window.innerHeight - 1080 * scale) / 2) + 'px';""",
     """    var vw = document.documentElement.clientWidth || window.innerWidth, vh = document.documentElement.clientHeight || window.innerHeight;
    var scale = Math.min(vw / 1920, vh / 1080);
    stage.style.transform = 'scale(' + scale + ')';
    stage.style.left = ((vw - 1920 * scale) / 2) + 'px';
    stage.style.top = ((vh - 1080 * scale) / 2) + 'px';""", 1),
]
# Back on Home: a launcher stays put instead of offering "Open Samsung Home?".
MAIN += [("else if (k === 'back') openMessage('leave');", "else if (k === 'back') return;", 2)]
# Restoring saved app records kept a brand only for upstream's non-default brands (hbomax,
# spotify, tubi), so after a restart non-default apps device.js branded (netflix, primevideo,
# disneyplus, stremio, appletv) fell back to a monogram until All apps was opened. Keep any brand
# whose mark is measured in FrameIconMetrics (every measured mark has its SVG; an unmeasured one
# would be stretched).
MAIN += [("brand: ['hbomax', 'spotify', 'tubi'].indexOf(a.brand) >= 0 ? a.brand : '', mark: ['hbomax', 'spotify', 'tubi'].indexOf(a.brand) >= 0,",
          "brand: typeof a.brand === 'string' && !!window.FrameIconMetrics && Object.prototype.hasOwnProperty.call(window.FrameIconMetrics, a.brand) ? a.brand : '', "
          "mark: typeof a.brand === 'string' && !!window.FrameIconMetrics && Object.prototype.hasOwnProperty.call(window.FrameIconMetrics, a.brand),", 1)]

# Wallpaper groups and photo groups (2026-10-31; docs/PHOTO-GROUPS.md). The gallery is split
# into groups: "Scenery" (upstream's entries, which have no "group"), "Space"
# (assets/wallpapers/space-catalog.js) and the user's own groups, whose photos the stick's library
# holds (contract 1) and web/wallpaper-groups.js merges in as entries "u-<gid>-<pid>" with a `files`
# map (contract 3). The pure logic is web/wallpaper-groups.js (window.CleanFrameWallpaperGroups) and
# web/photo-groups.js (window.CleanFramePhotoGroups, which also draws the Add group / Rename and Add
# photo screens), tested offline by tests/wallpaper-groups.test.js and tests/photo-groups.test.js.
#  - Every wallpaper and material path goes through ONE helper, imgSrc(id, kind) -> WG.src(), which
#    uses w.files when present and upstream's id-based paths otherwise.
#  - Settings > Background: Background type (now one row of three), Groups (one row per group with
#    its count, a tick on the active group = the applied wallpaper's group, then "Add group"; four
#    rows visible, the list scrolls) and Rotation. The old Wallpaper row is gone.
#  - OK on a group opens its group page: the old picker (view 'wallpaper') listing that group only,
#    titled with the group's name. Its group-switcher row (3b95ace) is replaced by an action row:
#    Add photo, Remove photos (when it has uploaded photos), Rename group and Delete group (user
#    groups). Up from the first photo reaches the row; Left/Right move along it; Down returns to the
#    list; Up again goes to the Back row. Remove photos is a mode: OK on an uploaded photo asks
#    'Remove "<name>"?' Yes/No (No focused; Back = No); Back leaves the mode. Delete group asks too.
#    An empty group shows "No photos yet" with Add photo focused.
#  - The library is read synchronously once at start-up (an uploaded photo saved as the wallpaper
#    must not fall back), again whenever Settings opens and every 2 s while Add photo is open; the
#    catalogue (WALLS) is updated in place, open group pages re-list, and a removed applied
#    wallpaper falls back to the first Scenery one (WG.resolve).
#  - Timed and "each time Home opens" rotation stay inside the applied wallpaper's group, user groups
#    included: since 2026-11-03 a random other photo of it (WG.random; see the rotation anchors).
MAIN += [
    ("  var WALLS = window.CleanFrameWallpapers || [{ id: 'misty', name: 'Misty coast', tone: 'light', ut: { tr: 'mist', br: 'mist' }, frostVeil: .20 }];",
     "  var WG = window.CleanFrameWallpaperGroups, PG = window.CleanFramePhotoGroups, WALL_BASE = WG.clean(window.CleanFrameWallpapers);\n"
     "  if (!WALL_BASE.length) WALL_BASE = [{ id: 'misty', name: 'Misty coast', tone: 'light', ut: { tr: 'mist', br: 'mist' }, frostVeil: .20 }];\n"
     "  var LIB = window.FrameDevice && FrameDevice.libraryNow ? FrameDevice.libraryNow() : null, WALLS = WG.merge(WALL_BASE, LIB);", 1),
    ("wall: wall(modern.wall || s.wallpaper) ? modern.wall || s.wallpaper : WALLS[0].id,",
     "wall: WG.resolve(WALLS, modern.wall || s.wallpaper),", 1),
    # Rotation is random within the group (2026-11-03: "if the photos rotate it would be best if
    # it starts on a random photo"): every rotating mode starts on a random photo at start-up (upstream:
    # only "Each time Home opens", in order), and each timed change picks one too (WG.random).
    ("    if (saved && initial.mode === 'open') initial.wall = WALLS[(wallIndex(initial.wall) + 1) % WALLS.length].id;",
     "    if (saved && initial.mode !== 'fixed') initial.wall = WG.random(WALLS, initial.wall);", 1),
    ("setLook({ wall: WALLS[(wallIndex(S.c.wall) + 1) % WALLS.length].id }, { row: 'bg' });", "setLook({ wall: WG.random(WALLS, S.c.wall) }, { row: 'bg' });", 1),
    # Turning rotation on (Fixed -> any other mode) moves to a random photo of the group at once.
    ("  function applyRotation(value) {\n    var c;\n", "  function applyRotation(value) {\n    var c, on = value !== 'fixed' && S.c.mode === 'fixed';\n", 1),
    ("    if (!write(c, 'Wallpaper rotation')) S.lookError = { type: 'save', patch: { mode: value }, row: 'mode', transient: false };\n",
     "    if (!write(c, 'Wallpaper rotation')) S.lookError = { type: 'save', patch: { mode: value }, row: 'mode', transient: false };\n"
     "    else if (on) setLook({ wall: WG.random(WALLS, c.wall) }, { row: 'bg' });\n", 1),
    # While rotating the group page ticks no photo (2026-11-03: "there should be a tick on rotate
    # not a selected photo"): the photo on screen is marked is-showing ("Now showing" in its status),
    # not is-applied, and the tick is on Background's Rotation row and the rotating group's row
    # (groupSection, detailSections). Fixed keeps upstream's tick on the applied photo.
    ("row.setAttribute('aria-checked', String(w.id === S.c.wall));", "row.setAttribute('aria-checked', String(w.id === S.c.wall && !rotating()));", 1),
    ("setHTML(row.querySelector('.setting-tick'), w.id === S.c.wall ? icon('check') : '');",
     "setHTML(row.querySelector('.setting-tick'), w.id === S.c.wall && !rotating() ? icon('check') : '');", 1),
    ("shown.id === S.c.wall ? 'Applied' : 'Preview';", "shown.id === S.c.wall ? rotating() ? 'Now showing' : 'Applied' : 'Preview';", 1),
    ("  function openSettings() { push({ id: 'settings', f: { id: S.settingsFocus } }); }",
     "  function openSettings() { push({ id: 'settings', f: { id: S.settingsFocus } }); refreshLibrary(); refreshBluetooth(); }", 1),
    ("""  function openWallpaper() {
    var i = wallIndex(S.c.wall);
    S.err = null; S.wallError = null;
    push({ id: 'wallpaper', i: i, top: clamp(i - 2, 0, Math.max(0, WALLS.length - WALL_VISIBLE)), f: { zone: 'photo' },""",
     """  function openWallpaper(g, focusAdd) {
    var list, i, t;
    g = g || WG.groupOf(wall(S.c.wall)); list = WG.members(WALLS, g); i = Math.max(0, list.indexOf(wall(S.c.wall)));
    S.err = null; S.wallError = null;
    t = push({ id: 'wallpaper', g: g, list: list, i: i, top: clamp(i - 2, 0, Math.max(0, list.length - WALL_VISIBLE)), f: { zone: list.length && !focusAdd ? 'photo' : 'acts', a: 0, b: 1 },
      removing: false, adjusting: false, confirm: null, busy: false, groupError: '', gone: false,""", 1),
    # Opening a group the applied wallpaper is not in previews its first photo, as a move would.
    ("      previewTimer: 0, previewDue: null, applyPending: null, failedId: null, failedApply: false, retryBundle: null });\n  }",
     "      previewTimer: 0, previewDue: null, applyPending: null, failedId: null, failedApply: false, retryBundle: null });\n"
     "    if (list.length && !focusAdd && list.indexOf(wall(S.c.wall)) < 0) previewWallpaper(t, 0, false);\n  }", 1),
    ("  function isSettingsDetail(t) { return t.id === 'appearance' || t.id === 'wallpaper'; }",
     """  var NO_WALL = { id: '', name: '' }, GROUP_VISIBLE = 4;
  function groupPick(t) { return t.list[t.i] || NO_WALL; }
  function pickedWall(p) { return groupPick(p); }
  function groupLabel(g) { return WG.labelOf(LIB, g); }
  function activeGroup() { return WG.groupOf(wall(S.c.wall)); }
  function rotating() { return S.c.mode !== 'fixed'; }
  function groupRows() { return WG.list(WALLS, LIB); }
  function relistGroup(t) {
    var id = groupPick(t).id, j;
    t.list = WG.members(WALLS, t.g);
    for (j = 0; j < t.list.length; j++) if (t.list[j].id === id) t.i = j;
    t.i = clamp(t.i, 0, Math.max(0, t.list.length - 1));
    t.gone = !groupRows().some(function (g) { return g.id === t.g; });
    groupNorm(t);
  }
  /* The stick's photo library (re-)read: update the catalogue in place, re-list open group pages,
     and leave a removed applied wallpaper for the first Scenery one. */
  function applyLibrary(lib) {
    var w;
    LIB = lib;
    WG.update(WALLS, WALL_BASE, lib, [S.c.wall, S.wallShown, S.shown && S.shown.wall, S.pending && S.pending.target && S.pending.target.wall]);
    S.stack.forEach(function (t) { if (t.id === 'wallpaper') relistGroup(t); });
    if (top().id === 'wallpaper' && top().gone) closeWallpaper(top());
    w = wall(S.c.wall);
    if (!w || w.stale) setLook({ wall: WG.resolve(WALLS, S.c.wall) }, { row: 'bg' });
    // the applied photo was adjusted (same id, new edited): load its new files
    else if (S.c.bg === 'photo' && S.shown && S.shown.wall === S.c.wall && S.bundleKey !== bundleKey(S.c) && !S.pending) setLook({}, { transient: true });
  }
  function refreshLibrary(done) {
    if (!window.FrameDevice || !FrameDevice.library) { if (done) done(null); return; }
    FrameDevice.library(function (err, lib) {
      if (!err) applyLibrary(lib);
      if (done) done(err); else after();
    });
  }
  function groupNorm(t) {
    var acts = groupActs(t);
    t.f.a = clamp(t.f.a || 0, 0, acts.length - 1);
    if (t.removing && !t.list.some(WG.isUser)) t.removing = false;
    if (t.adjusting && !t.list.some(adjustable)) t.adjusting = false;
    if (!t.list.length && (t.f.zone === 'photo' || t.f.zone === 'retry')) t.f.zone = 'acts';
    if (t.f.zone === 'confirm' && !t.confirm) t.f.zone = t.list.length ? 'photo' : 'acts';
  }
  function groupActs(t) {
    var row = groupRows().filter(function (g) { return g.id === t.g; })[0];
    return PG.actions({ builtin: !row || row.builtin, uploads: t.list.some(WG.isUser), removing: t.removing,
      adjustable: t.list.some(adjustable), adjusting: t.adjusting });
  }
  function groupAct(t, id) {
    if (t.busy) return;
    t.groupError = '';
    if (id === 'add') PGV.openUpload(t.g);
    else if (id === 'remove') { t.removing = !t.removing; t.adjusting = false; t.confirm = null; }
    else if (id === 'adjust') { t.adjusting = !t.adjusting; t.removing = false; t.confirm = null; t.groupError = ''; }
    else if (id === 'rename') PGV.openName('rename', t.g);
    else if (id === 'delete') { t.confirm = { kind: 'group' }; t.f.zone = 'confirm'; t.f.b = 1; }
  }
  function groupActsKey(t, k) {
    var acts = groupActs(t), r;
    if (k === 'ok') { groupAct(t, acts[t.f.a].id); return; }
    r = PG.actionsKey(acts, t.f.a, k); t.f.a = r.a;
    if (r.go === 'back') t.f.zone = 'back';
    else if (r.go === 'list' && t.list.length) previewWallpaper(t, t.i, false);
  }
  /* Adjust (web/photo-adjust.js): a photo added since 2026-11-02 has its upright copy (w.src). */
  function adjustable(w) { return WG.isUser(w) && !!w.src; }
  function openAdjust(t) {
    var w = groupPick(t);
    if (!adjustable(w)) { t.groupError = WG.isUser(w) ? PA.NO_SRC : 'Only photos you added can be framed again.'; return; }
    t.groupError = ''; PAV.open(w);
  }
  function askRemove(t) {
    var w = groupPick(t);
    if (!WG.isUser(w)) { t.groupError = 'Only photos you added can be removed.'; return; }
    t.confirm = { kind: 'photo', id: w.id, pid: w.pid, name: w.name }; t.f.zone = 'confirm'; t.f.b = 1; t.groupError = '';
  }
  function groupConfirmKey(t, k) {
    var c = t.confirm, r = PG.confirmKey(t.f.b, k);
    t.f.b = r.b;
    if (!r.answer || !c) return;
    t.confirm = null; t.f.zone = c.kind === 'photo' && t.list.length ? 'photo' : 'acts';
    if (r.answer === 'yes') groupDelete(t, c);
  }
  function groupDelete(t, c) {
    t.busy = true; t.groupError = '';
    function done(err) {
      t.busy = false;
      if (err) { t.groupError = err.detail || err.message; after(); return; }
      if (c.kind === 'group') { t.gone = true; if (top() === t) closeWallpaper(t); }
      else if (S.wallShown === c.id && S.c.wall !== c.id) restoreLook();
      refreshLibrary();
    }
    if (c.kind === 'group') FrameDevice.deleteGroup(t.g, done);
    else FrameDevice.deletePhoto(t.g, c.pid, done);
  }
  function groupBack(t) {
    if (t.f.zone === 'confirm') { groupConfirmKey(t, 'back'); return true; }
    if (t.removing) { t.removing = false; t.groupError = ''; return true; }
    if (t.adjusting) { t.adjusting = false; t.groupError = ''; return true; }
    return false;
  }
  function groupReturn(t) { return 'background.group.' + (!t.gone ? t.g : activeGroup() === t.g ? WG.DEFAULT : activeGroup()); }
  function groupHint(t) {
    var id = (groupActs(t)[t.f.a] || {}).id;
    if (t.f.zone === 'confirm') return t.f.b === 1 ? (t.confirm && t.confirm.kind === 'group' ? 'Keep this group' : 'Keep this photo') : t.confirm && t.confirm.kind === 'group' ? 'Delete this group' : 'Remove this photo';
    return id === 'add' ? 'Add photos from your phone' : id === 'adjust' ? (t.adjusting ? 'Stop adjusting photos' : 'Choose a photo to frame again') : id === 'remove' ? (t.removing ? 'Stop removing photos' : 'Choose photos to remove') : id === 'rename' ? 'Rename this group' : 'Delete this group';
  }
  function groupStatus(t) {
    var c = t.confirm, q;
    if (c) {
      q = c.kind === 'photo' ? 'Remove “' + c.name + '”?' : 'Delete “' + groupLabel(t.g) + '”' + (t.list.length ? ' and its ' + PG.countText(t.list.length) : '') + '?';
      return '<div class="group-confirm" role="alertdialog"><p class="group-question">' + esc(q) + '</p>'
        + '<div class="group-confirm-buttons">' + ['Yes', 'No'].map(function (label, i) {
          return '<button type="button" tabindex="-1" class="setting-choice group-confirm-button' + (t.f.zone === 'confirm' && t.f.b === i ? ' is-focus' : '') + '" data-z="wall-confirm" data-i="' + i + '" data-action="group.confirm.' + label.toLowerCase() + '">' + label + '</button>';
        }).join('') + '</div></div>';
    }
    return t.groupError ? '<p class="setting-error group-error">' + esc(t.groupError) + '</p>' : t.busy ? '<p class="group-busy">Working…</p>' : '';
  }
  function groupSection(t, flat) {
    var active = activeGroup(), focus = -1, i, items = groupRows().map(function (g) {
      return { action: 'background.group.' + g.id, group: g.id, label: g.label, note: PG.countText(g.count) + (rotating() && g.id === active ? ' · Rotating' : ''), tick: g.id === active, thumbnail: true, thumb: g.thumb, disabled: flat };
    }), full = !PG.canAddGroup(LIB);
    items.push({ action: 'background.addgroup', label: 'Add group', add: true, disabled: flat || full });
    for (i = 0; i < items.length; i++) if (items[i].action === t.f.action) focus = i;
    t.gtop = PG.windowTop(t.gtop || 0, focus, items.length, GROUP_VISIBLE);
    return { id: 'groups', title: 'Groups' + (items.length > GROUP_VISIBLE ? '<span class="group-more" aria-hidden="true">' + (t.gtop > 0 ? icon('up') : '') + (t.gtop + GROUP_VISIBLE < items.length ? icon('down') : '') + '</span>' : ''),
      items: items, window: [t.gtop, t.gtop + GROUP_VISIBLE], reason: !flat && full ? 'There can be at most ' + PG.MAX_GROUPS + ' groups.' : '' };
  }
  /* Bluetooth (web/bluetooth.js): the stick is asked when Settings or the Bluetooth page opens, and
     when the page is shown again while Settings is open (back from Android's pairing page). */
  function refreshBluetooth(ifOpen) {
    if (ifOpen && !S.stack.some(function (v) { return v.id === 'settings'; })) return;
    BT.refresh(window.FrameDevice, function () {
      var t = top();
      if (t.id === 'settings' || t.id === 'appearance' && t.group === 'bluetooth') after();
    });
  }
  function bluetoothArrows(up, down) { return '<span class="group-more" aria-hidden="true">' + (up ? icon('up') : '') + (down ? icon('down') : '') + '</span>'; }
  function bluetoothOk(item) {
    var m;
    if (item.bt === 'pair') perform('Bluetooth', function (done) { FrameDevice.bluetoothPair(done); });
    else if (item.bt === 'device' && (m = BT.deviceMessage(item.index))) {
      if (m.ask) push({ id: 'message', msg: 'btunpair.' + m.ask.id, btUnpair: m.ask, f: { b: 1 }, content: { title: m.title, line: m.line, note: m.note, actions: [['yes', 'Yes'], ['no', 'No']] } });
      else message(m.title, m.line, m.note);
    }
  }
  /* Yes on "Unpair <name>?": the page shows the list as it changes, then what happened, unless the
     user has left it by then (the list is read again when it next opens). */
  function bluetoothUnpair(ask) {
    function here() { var t = top(); return t.id === 'appearance' && t.group === 'bluetooth'; }
    BT.unpair(window.FrameDevice, ask, function () { if (here()) after(); }, function (m) { if (here()) message(m.title, m.line, m.note); after(); });
  }
  var PGV = PG.views({ top: top, push: push, pop: pop, after: function () { after(); }, esc: esc, icon: icon, setHTML: setHTML,
    focusStage: function () { focusStage(); }, library: function () { return LIB; }, members: function (g) { return WG.members(WALLS, g); },
    refreshLibrary: refreshLibrary, openGroup: function (g, focusAdd) { openWallpaper(g, focusAdd); }, groupLabel: groupLabel });
  var AEV = AE.views({ top: top, push: push, pop: pop, after: function () { after(); }, esc: esc, icon: icon, setHTML: setHTML,
    focusStage: function () { focusStage(); }, app: app, where: function (target) { return target && target.where === 'apps' ? 'All apps' : 'Home'; },
    markHtml: markHtml, appMark: appMark });
  var PAV = PA.views({ top: top, push: push, pop: pop, after: function () { after(); }, refreshLibrary: function () { refreshLibrary(); },
    message: function (title, text) { message(title, text, ''); }, device: window.FrameDevice });
  function isSettingsDetail(t) { return t.id === 'appearance' || t.id === 'wallpaper' || PGV.owns(t) || AEV.owns(t); }""", 1),
    # Opening the Bluetooth page asks the stick again (web/bluetooth.js).
    ("    if (id === 'editor') openEditor('settings');\n    else openAppearance(id);\n",
     "    if (id === 'editor') openEditor('settings');\n    else { openAppearance(id); if (id === 'bluetooth') refreshBluetooth(); }\n", 1),
    ("  var V = {};\n", "  var V = {};\n  V.groupname = PGV.V.groupname; V.upload = PGV.V.upload; V.appname = AEV.V.appname; V.appicon = AEV.V.appicon; V.adjust = PAV.V.adjust;\n", 1),
    # The group page indexes its own group's list (t.list), which can be empty.
    ("WALLS[t.i]", "pickedWall(t)", 6),
    ("id = WALLS[index].id;", "id = t.list[index].id;", 1),
    ("      t.i = clamp(t.i, 0, WALLS.length - 1);", "      t.i = clamp(t.i, 0, Math.max(0, t.list.length - 1)); groupNorm(t);", 1),
    ("      t.top = clamp(t.top, 0, Math.max(0, WALLS.length - WALL_VISIBLE));", "      t.top = clamp(t.top, 0, Math.max(0, t.list.length - WALL_VISIBLE));", 1),
    ("      if (k === 'back') { closeWallpaper(t); return; }", "      if (k === 'back') { if (!groupBack(t)) closeWallpaper(t); return; }", 1),
    ("""      if (t.f.zone === 'back') {
        if (k === 'ok') closeWallpaper(t);
        else if (k === 'down' || k === 'right') t.f.zone = 'photo';
        return;
      }""", """      if (t.f.zone === 'back') {
        if (k === 'ok') closeWallpaper(t);
        else if (k === 'down' || k === 'right') t.f.zone = 'acts';
        return;
      }
      if (t.f.zone === 'acts') { groupActsKey(t, k); return; }
      if (t.f.zone === 'confirm') { groupConfirmKey(t, k); return; }""", 1),
    ("      if (k === 'left' || k === 'up' && t.i === 0) { t.f.zone = 'back'; t.applyPending = null; }",
     "      if (k === 'left') { t.f.zone = 'back'; t.applyPending = null; }\n"
     "      else if (k === 'up' && t.i === 0) { t.f.zone = 'acts'; t.applyPending = null; }", 1),
    ("      else if (k === 'down' && t.i < WALLS.length - 1) previewWallpaper(t, t.i + 1, false);",
     "      else if (k === 'down' && t.i < t.list.length - 1) previewWallpaper(t, t.i + 1, false);", 1),
    ("      else if (k === 'ok') { if (t.retryBundle) applyWallpaper(t, t.retryBundle); else applyWallpaper(t); }",
     "      else if (k === 'ok' && t.removing) askRemove(t);\n"
     "      else if (k === 'ok' && t.adjusting) openAdjust(t);\n"
     "      else if (k === 'ok') { if (t.retryBundle) applyWallpaper(t, t.retryBundle); else applyWallpaper(t); }", 1),
    ("      else if (z === 'wall-choice' && i >= 0 && i < WALLS.length) {",
     "      else if (z === 'wall-act') { t.f.zone = 'acts'; t.f.a = i; groupActsKey(t, 'ok'); }\n"
     "      else if (z === 'wall-confirm') { t.f.zone = 'confirm'; t.f.b = i; groupConfirmKey(t, 'ok'); }\n"
     "      else if (z === 'wall-choice' && t.removing && i >= 0 && i < t.list.length) { t.i = i; t.f.zone = 'photo'; askRemove(t); }\n"
     "      else if (z === 'wall-choice' && t.adjusting && i >= 0 && i < t.list.length) { t.i = i; t.f.zone = 'photo'; openAdjust(t); }\n"
     "      else if (z === 'wall-choice' && i >= 0 && i < t.list.length) {", 1),
    ("    if (top().id === 'appearance' && top().group === 'background') detailFocus(top(), { action: 'background.wallpaper' }, 0);",
     "    if (top().id === 'appearance' && top().group === 'background') detailFocus(top(), { action: groupReturn(t) }, 0);", 1),
    # The group page's look: title, action row, empty state, remove mode, confirm, hints.
    ("    appearanceTitle.textContent = 'Wallpaper';", "    appearanceTitle.textContent = groupLabel(t.g);", 1),
    ("    appearanceRows.className = 'appearance-rows wallpaper-picker';",
     "    appearanceRows.className = 'appearance-rows wallpaper-picker has-actions' + (t.removing ? ' is-removing' : '');", 1),
    ("""    setHTML(appearanceRows, '<div class="wallpaper-edge wallpaper-before" aria-hidden="true">'""",
     """    setHTML(appearanceRows, '<div class="group-actions" role="toolbar" aria-label="Group"></div><p class="group-empty"></p><div class="wallpaper-edge wallpaper-before" aria-hidden="true">'""", 1),
    ("    keyed(rows, WALLS.map(function (w) { return { key: w.id, wall: w }; }), function (row, item, i) {",
     """    setHTML(appearanceRows.querySelector('.group-actions'), groupActs(t).map(function (a, ai) {
      return '<button type="button" tabindex="-1" class="setting-choice group-action' + (t.f.zone === 'acts' && t.f.a === ai ? ' is-focus' : '') + (a.id === 'remove' && t.removing ? ' is-current' : '')
        + '" aria-label="' + esc(a.name) + '" data-z="wall-act" data-i="' + ai + '" data-action="group.' + a.id + '">' + esc(a.label) + '</button>';
    }).join(''));
    setHTML(appearanceRows.querySelector('.group-empty'), t.list.length ? '' : 'No photos yet');
    keyed(rows, t.list.map(function (w) { return { key: w.id, wall: w }; }), function (row, item, i) {""", 1),
    ("""'<span class="wallpaper-thumb">' + (visible ? '<img src="' + WP + w.id + '-thumb.jpg" alt="">' : '') + '</span>""",
     """'<span class="wallpaper-thumb">' + (visible ? '<img src="' + esc(imgSrc(w.id, 'thumb')) + '" alt="">' : '') + '</span>""", 1),
    ("      row.className = 'setting-choice wallpaper-choice' + (t.f.zone === 'photo' && t.i === i ? ' is-focus' : '') + (w.id === S.c.wall ? ' is-applied' : '');",
     "      row.className = 'setting-choice wallpaper-choice' + (t.f.zone === 'photo' && t.i === i ? ' is-focus' : '') + (w.id === S.c.wall ? rotating() ? ' is-showing' : ' is-applied' : '')"
     " + (t.removing ? WG.isUser(w) ? ' is-removable' : ' is-locked' : t.adjusting ? adjustable(w) ? ' is-adjustable' : ' is-locked' : '');", 1),
    ("row.setAttribute('aria-setsize', String(WALLS.length));", "row.setAttribute('aria-setsize', String(t.list.length));", 1),
    ("(t.top + WALL_VISIBLE < WALLS.length ? ' is-on' : '')", "(t.top + WALL_VISIBLE < t.list.length ? ' is-on' : '')", 1),
    ("""<span class="wallpaper-count">' + (wallIndex(shown.id) + 1) + ' of ' + WALLS.length + '</span>""",
     """<span class="wallpaper-count">' + esc(groupLabel(WG.groupOf(shown))) + ' · ' + (WG.members(WALLS, WG.groupOf(shown)).indexOf(shown) + 1) + ' of ' + WG.members(WALLS, WG.groupOf(shown)).length + '</span>""", 1),
    ("""    if (t.failedId) status += '<button type="button" tabindex="-1" class="setting-choice setting-retry' + (t.f.zone === 'retry' ? ' is-focus' : '') + '" data-z="wall-retry" data-action="wallpaper.retry">Try again</button>';""",
     """    if (t.failedId) status += '<button type="button" tabindex="-1" class="setting-choice setting-retry' + (t.f.zone === 'retry' ? ' is-focus' : '') + '" data-z="wall-retry" data-action="wallpaper.retry">Try again</button>';
    status = t.confirm ? groupStatus(t) : status + groupStatus(t);""", 1),
    ("    hint = t.f.zone === 'back' ? 'Return to Background' : t.f.zone === 'retry' ? 'Retry this photo' : 'Apply selected photo';",
     "    hint = t.f.zone === 'back' ? 'Return to Background' : t.f.zone === 'retry' ? 'Retry this photo' : t.f.zone === 'acts' || t.f.zone === 'confirm' ? groupHint(t)"
     " : t.removing ? WG.isUser(groupPick(t)) ? 'Remove this photo' : 'Only photos you added can be removed' : t.adjusting ? adjustable(groupPick(t)) ? 'Frame this photo again' : 'This photo cannot be framed again' : 'Apply selected photo';", 1),
    ("    if (t.f.zone === 'photo' && candidate.id === S.c.wall && !S.pending && !t.failedId) hint = '';",
     "    if (t.f.zone === 'photo' && candidate.id === S.c.wall && !S.pending && !t.failedId && !t.removing && !t.adjusting) hint = '';", 1),
    ("queuedApplying: !!t.applyPending,",
     "group: t.g, groups: groupRows().map(function (g) { return g.label; }), actions: groupActs(t).map(function (a) { return a.id; }), "
     "removing: t.removing, confirm: t.confirm ? t.confirm.kind : null, queuedApplying: !!t.applyPending,", 1),
    # Settings > Background: the Groups section replaces the Wallpaper row.
    ("""      { id: 'bg', title: 'Background type', items: options('bg', ['photo', 'light', 'dark']) },
      { id: 'wall', title: 'Wallpaper', items: [{ action: 'background.wallpaper', label: wall(S.c.wall).name, thumbnail: true, disabled: flat }] },
      { id: 'rotation', title: 'Rotation', items: [{ action: 'background.rotation', label: mode().name, disabled: flat }], reason: flat ? 'Choose Photo to use wallpaper and rotation.' : '' }""",
     """      { id: 'bg', title: 'Background type', horizontal: true, items: options('bg', ['photo', 'light', 'dark']) },
      groupSection(t, flat),
      { id: 'rotation', title: 'Rotation', items: [{ action: 'background.rotation', label: mode().name, disabled: flat, tick: !flat && rotating() ? true : undefined }], reason: flat ? 'Choose Photo to use groups and rotation.' : '' }""", 1),
    ("        + section.items.map(function (item) {",
     "        + section.items.filter(function (item, n) { return !section.window || n >= section.window[0] && n < section.window[1]; }).map(function (item) {", 1),
    ("""            : '<img class="setting-swatch" src="' + WP + S.c.wall + '-thumb.jpg" alt="">';
          body += '<span class="setting-copy"><span class="setting-name">' + esc(item.label) + '</span>'
            + (item.description ? '<span class="setting-description">' + esc(item.description) + '</span>' : '') + '</span>';
          if (!item.axis) body += icon('right', 'setting-forward');""",
     """            : item.thumb === '' ? '<span class="setting-swatch swatch-empty" aria-hidden="true"></span>'
            : '<img class="setting-swatch" src="' + esc(item.thumb || imgSrc(S.c.wall, 'thumb')) + '" alt="">';
          if (item.add) body += '<span class="setting-swatch swatch-add" aria-hidden="true">' + icon('plus') + '</span>';
          body += '<span class="setting-copy"><span class="setting-name">' + esc(item.label) + (item.note ? '<span class="setting-note"> · ' + esc(item.note) + '</span>' : '') + '</span>'
            + (item.description ? '<span class="setting-description">' + esc(item.description) + '</span>' : '') + '</span>';
          if (item.tick !== undefined) body += '<span class="setting-tick" aria-hidden="true">' + (item.tick ? icon('check') : '') + '</span>';
          if (!item.axis && !item.pref && !item.bare) body += icon('right', 'setting-forward');""", 1),
    ("    else if (item.action === 'background.wallpaper') openWallpaper();",
     "    else if (item.group) openWallpaper(item.group);\n    else if (item.action === 'background.addgroup') PGV.openName('add');", 1),
    ("selected.action === 'background.wallpaper' ? 'Choose a wallpaper' :",
     "selected.group ? 'Open ' + selected.label : selected.action === 'background.addgroup' ? 'Make a new group' :", 1),
    ("    if (action === 'appearance.bg' && top().id === 'appearance' && top().group === 'background') action = 'background.wallpaper';",
     "    if (action === 'appearance.bg' && top().id === 'appearance' && top().group === 'background') action = 'background.group.' + activeGroup();", 1),
    # The one image path helper (wall, thumb, panel and the four materials).
    ("""  function matSrc(id, material) { return 'assets/materials/' + id + (material === 'soft' ? '-study-soft.jpg' : material === 'tint' ? '-field-clear.jpg' : '-field-frost.jpg'); }
  function fieldSrc(id, material) { return 'assets/materials/' + id + (material === 'soft' ? '-field-blur.jpg' : material === 'tint' ? '-field-clear.jpg' : '-field-frost.jpg'); }""",
     """  function imgSrc(id, kind) { return WG.src(wall(id) || { id: id }, kind); }
  function matSrc(id, material) { return imgSrc(id, material === 'soft' ? 'soft' : material === 'tint' ? 'clear' : 'frost'); }
  function fieldSrc(id, material) { return imgSrc(id, material === 'soft' ? 'blur' : material === 'tint' ? 'clear' : 'frost'); }""", 1),
    ("    var imgs = { photo: makeImg('photo', WP + look.wall + '.jpg'), bg: makeImg('sheet-bg', WP + look.wall + '-panel.jpg') }, keys, left, allOk = true;",
     "    var imgs = { photo: makeImg('photo', imgSrc(look.wall, 'wall')), bg: makeImg('sheet-bg', imgSrc(look.wall, 'panel')) }, keys, left, allOk = true;", 1),
    # The Add group / Rename and Add photo screens (web/photo-groups.js) in the settings panel.
    ("    if (t.id === 'wallpaper') { renderWallpaper(t); return; }",
     "    if (PGV.render(t) || AEV.render(t)) return;\n    if (t.id === 'wallpaper') { renderWallpaper(t); return; }", 1),
    ("  function render() {\n    var t = top(),", "  function render() {\n    PGV.sync(S.stack);\n    var t = top(),", 1),
    # The group name's text box keeps every key but the D-pad, OK and Back while it has the keyboard.
    # Any key it takes also forgets the held keys: the OK that put the box in typing opened Android's
    # keyboard, which swallows that OK's keyup, so heldKeys[13] stayed set and the first OK on Create
    # was dropped as a repeat (stick, DEVICE-CHECK 2, 2026-10-31).
    ("    var code = ev.keyCode, k = KEYS[code];\n    if (code === 9) { ev.preventDefault(); return; }",
     "    var code = ev.keyCode, k = KEYS[code];\n    if (X.activity(code)) { ev.preventDefault(); return; }\n    if (PGV.inputKey(ev) || AEV.inputKey(ev)) { heldKeys = {}; return; }\n    if (code === 9) { ev.preventDefault(); return; }", 1),
]

# Dock focus (2026-11-01): with focus on the top-right controls, the last dock app kept a
# lit plate and near-full opacity ("remembered", upstream's is-sel / on-sel), which on a TV reads as
# a second selection. Only the controls show focus now; Down still returns to that app (the anchor
# is kept in S.sel). The arrange action row and the settings preview keep the marker.
MAIN += [
    ("tileSel = hs.zone === 'util' || hs.zone === 'act' || hs.zone === 'ctl' || hs.zone === 'preview';",
     "tileSel = hs.zone === 'act' || hs.zone === 'preview';", 1),
    ("remembered = hs.zone === 'util' || hs.zone === 'act' || hs.zone === 'ctl' || hs.zone === 'preview';",
     "remembered = hs.zone === 'act' || hs.zone === 'preview';", 1),
]

# Clock options (2026-11-01): Settings > Clock picks 12-hour, 12-hour with AM/PM or 24-hour,
# seconds and whether the clock shows. web/home-extras.js (window.CleanFrameExtras, X) owns the
# preferences in their own localStorage key and the clock's text; tests/home-extras.test.js. Its
# page items carry `pref`/`value` instead of `axis`: detailOk saves them with setPref(), the
# appearance page ticks them with X.applied(), and a save failure shows the usual "Couldn't save"
# with Try again. The clock redraws every second only while seconds are shown.
MAIN += [
    ("  var ADAPTIVE_INK = window.CleanFrameAdaptiveInk || {};",
     "  var X = window.CleanFrameExtras;\n  var BT = window.CleanFrameBluetooth;\n  var AE = window.CleanFrameAppEdit;\n  var PA = window.CleanFramePhotoAdjust;\n  var ADAPTIVE_INK = window.CleanFrameAdaptiveInk || {};", 1),
    ("    var d = new Date(), h = d.getHours() % 12 || 12, m = d.getMinutes();\n    return h + ':' + (m < 10 ? '0' : '') + m;",
     "    return X.clockText(new Date());", 1),
    ("clockTimer = setTimeout(updateClock, 60000 - (Date.now() % 60000) + 30);",
     "clockTimer = setTimeout(updateClock, X.clockStep() - (Date.now() % X.clockStep()) + 30);", 1),
    ("      { id: 'editor', label: 'Apps on Home', value: n + (n === 1 ? ' app' : ' apps') },",
     "      { id: 'clock', label: 'Clock', value: X.summary('clock') },\n"
     "      { id: 'screen', label: 'Screen', value: X.summary('screen') },\n"
     "      { id: 'editor', label: 'Apps on Home', value: n + (n === 1 ? ' app' : ' apps') },\n"
     "      { id: 'bluetooth', label: 'Bluetooth', value: BT.summary() },", 1),
    ("var DETAIL_NAMES = { background: 'Background',",
     "var DETAIL_NAMES = { clock: 'Clock', screen: 'Screen', screenDim: 'Dim after', screenSleep: 'Sleep after', bluetooth: 'Bluetooth', background: 'Background',", 1),
    ("    if (t.group === 'typography') return [{ id: 'font', title: '', items: options('font', LOOK_VALUES.font) }];",
     "    if (X.owns(t.group)) return X.sections(t.group);\n"
     "    if (t.group === 'bluetooth') return BT.sections(t, bluetoothArrows);\n"
     "    if (t.group === 'typography') return [{ id: 'font', title: '', items: options('font', LOOK_VALUES.font) }];", 1),
    ("  function detailBack() { cancelLook(); S.err = null; pop(); }",
     "  function setPref(key, value) {\n"
     "    S.err = null;\n"
     "    S.lookError = X.set(key, value) ? null : { type: 'save', patch: {}, row: 'pref', pref: key, value: value };\n"
     "  }\n"
     "  function detailBack() { cancelLook(); S.err = null; pop(); }", 1),
    ("    if (item.action === 'settings.retry') {",
     "    if (item.action === 'settings.retry' && error && error.pref) setPref(error.pref, error.value);\n"
     "    else if (item.action === 'settings.retry') {", 1),
    ("    } else if (item.axis === 'mode') applyRotation(item.value);",
     "    } else if (item.sub) openAppearance(item.sub);\n"
     "    else if (item.pref) setPref(item.pref, item.value);\n"
     "    else if (item.bt) bluetoothOk(item);\n"
     "    else if (item.axis === 'mode') applyRotation(item.value);", 1),
    # A page with no axis (Clock): upstream's test matched the Back row (undefined === undefined),
    # so the page opened on Back. Prefs are matched by X.applied; an axis test needs an axis. A
    # Clock page has one applied choice per section: focus the first (Format), not the last.
    ("        if (item.axis === axis && item.value === S.c[axis]) applied = item;",
     "        if (!applied && (item.pref ? X.applied(item) : axis && item.axis === axis && item.value === S.c[axis])) applied = item;", 1),
    ("      var applied = item.axis && S.c[item.axis] === item.value;",
     "      var applied = item.pref ? X.applied(item) : item.axis && S.c[item.axis] === item.value;", 1),
    ("""        + (item.axis ? ' role="radio" aria-checked="' + !!applied + '"' : '')""",
     """        + (item.axis || item.pref ? ' role="radio" aria-checked="' + !!applied + '"' : '')""", 1),
    ("""        + (item.axis ? '<span class="setting-tick" aria-hidden="true">'""",
     """        + (item.axis || item.pref ? '<span class="setting-tick" aria-hidden="true">'""", 1),
    ("""'<div class="setting-options"' + (section.items[0].axis ? ' role="radiogroup\"""",
     """'<div class="setting-options"' + (section.items[0].axis || section.items[0].pref ? ' role="radiogroup\"""", 1),
    ("selected.axis ? 'Apply selected setting'", "selected.hint ? selected.hint : selected.axis || selected.pref ? 'Apply selected setting'", 1),
    ("    out.undo = S.undo.map(function (u) { return u.label; }); out.storageOk = storageOk;",
     "    out.extras = X.all(); out.bluetooth = BT.state(); out.appEdits = AE.all();\n"
     "    out.selectedApp = app(S.sel) ? { id: S.sel, appId: app(S.sel).appId, name: app(S.sel).name, markId: app(S.sel).markId || '' } : null;\n"
     "    out.undo = S.undo.map(function (u) { return u.label; }); out.storageOk = storageOk;", 1),
    ("  initialise();\n  setLook({}, { transient: true, startup: true });",
     "  initialise();\n  X.onChange(function () { updateClock(); render(); });\n  X.screenStart();\n  AE.onChange(appsChanged);\n  setLook({}, { transient: true, startup: true });"
     # Read the installed apps at start-up, not only when All apps opens: a default dock app's
     # package is upstream's (the Xiaomi's) until the list says otherwise, so on the Hugsun the Emby
     # tile opened "This app is not installed" (com.mb.android, not tv.emby.embyatv) after every
     # start until All apps had been opened once (2026-11-02).
     "\n  refreshCatalog();", 1),
]

# Uninstall on the long-press menu (2026-11-04). FrameDevice.uninstall opens Android's own dialog,
# which does the confirming; CleanFrame asks nothing. Only the installed list says what happened: each
# app sent to the dialog stays in `uninstalling` until a read of the list no longer has its package, and
# the list is read when Home comes back (visibility or focus) while any is waiting. Cancel in the dialog
# therefore changes nothing. When the package has gone: off Home (unless it was the last: upstream's "Home
# needs one app", and the tile then says "This app is not installed"), out of Recently removed and of
# the undo history, and its name and icon override dropped. An app that disappears any other way is
# left as it always was. `uninstalling` is memory only: a page rebuilt while the dialog was open
# leaves the tile, which says not installed and can be removed by hand.
UNINSTALL_JS = """  var uninstalling = [];
  function uninstallApp(a, id) {
    perform(a.name, function (done) {
      FrameDevice.uninstall(a.appId, function (err) {
        if (!err) {
          uninstalling = uninstalling.filter(function (u) { return u.appId !== a.appId; });
          uninstalling.push({ id: id, appId: a.appId, name: a.name });
        }
        done(err);
      });
    });
  }
  function uninstallReturned() { if (uninstalling.length) refreshCatalog(); }
  function uninstallSettle(entries) {
    uninstalling = uninstalling.filter(function (u) {
      var h = without(S.c.home, u.id), r = without(S.c.recent, u.id);
      if (entries.some(function (e) { return e.appId === u.appId; })) return true;
      if (!h.length) h = S.c.home.slice();
      if ((h.length !== S.c.home.length || r.length !== S.c.recent.length) && !write(next(h, r), 'Uninstall ' + u.name)) return true;
      S.undo = S.undo.filter(function (x) { return x.snap.home.indexOf(u.id) < 0 && x.snap.recent.indexOf(u.id) < 0; });
      delete S.removedAt[u.id];
      AE.forget(u.appId);
      S.note = u.name + ' uninstalled.';
      return false;
    });
  }
"""

# Long press (2026-11-01): OK held X.HOLD_MS (600 ms) on a dock app or an All apps tile opens a
# small menu in upstream's message view: Move (the Apps on Home editor with that app picked up),
# Rename and Change icon (web/app-edit.js, see "Per-app name and icon" below), Remove from Home or Add
# to Home, App info (FrameDevice.appInfo -> Android's page for the package), Uninstall when the native
# side allows it (see UNINSTALL_JS) and Cancel. A short OK on those tiles is sent on release instead of on press; everywhere else OK
# is unchanged. Losing focus or hiding the page cancels a hold without opening anything. The hold
# itself is X.Hold (web/home-extras.js); tests/home-extras.test.js replays the patched handlers.
MAIN += [
    ("  var heldKeys = {};\n",
     """  var HOLD = new X.Hold();
  function holdTarget() {
    var t = top(), items;
    if (busy) return null;
    if (t.id === 'home' && t.f.zone === 'apps' && S.c.home.length) { ensureSel(); return { where: 'dock', id: S.sel }; }
    if (t.id === 'apps' && t.f.zone === 'grid') { items = appsItems(); if (items[t.f.i]) return { where: 'apps', id: items[t.f.i] }; }
    return null;
  }
  function holdStart() {
    var target = holdTarget();
    if (!target || !app(target.id)) return false;
    HOLD.start(function () { press('ok'); }, function () { openAppMenu(target); norm(); render(); syncRotation(); });
    return true;
  }
  function openAppMenu(target) {
    var a = app(target.id);
    push({ id: 'message', msg: 'appmenu.' + target.where + '.' + target.id, appMenu: target, f: { b: 0 },
      content: { title: a.name, line: X.appMenuLine(target.where), note: '', actions: X.appMenu(target.where, S.c.home.indexOf(target.id) >= 0, FrameDevice.canUninstall(a.appId)) } });
  }
  function appMenuAct(target, act) {
    var a = app(target.id), i = S.c.home.indexOf(target.id), e;
    if (!a) return;
    begin();
    if (act === 'move' && i >= 0) {
      openEditor('home'); e = top();
      e.f.zone = 'home'; e.f.i = i; e.f.part = 'name';
      e.lifted = { id: target.id, from: i, order: S.c.home.slice(), snap: snap() };
    } else if (act === 'remove') { if (homeRemove(target.id)) S.note = a.name + ' removed from Home.'; }
    else if (act === 'add') { if (homeAdd(target.id)) S.note = a.name + ' added to Home.'; }
    else if (act === 'info') perform(a.name, function (done) { FrameDevice.appInfo(a.appId, done); });
    else if (act === 'uninstall') uninstallApp(a, target.id);
    else if (act === 'rename') AEV.openName(target);
    else if (act === 'icon') AEV.openIcon(target);
  }
  /* Rename / Change icon saved (web/app-edit.js): every record shows its override again and All apps
     re-sorts by the shown name, each open All apps keeping its focus on the same app. */
  function appsChanged() {
    var keep = S.stack.map(function (v) { return v.id === 'apps' ? ALPHA[v.f.i] : null; });
    APPS.forEach(function (a) { AE.dress(a); });
    ALPHA.sort(function (a, b) { return app(a).name.toLowerCase().localeCompare(app(b).name.toLowerCase()); });
    S.stack.forEach(function (v, n) { if (keep[n] && ALPHA.indexOf(keep[n]) >= 0) v.f.i = ALPHA.indexOf(keep[n]); });
    render();
  }
  function openSleepMenu() {
    push({ id: 'message', msg: 'sleep.' + Date.now(), sleepMenu: true, f: { b: X.sleepIndex() }, content: X.sleepMenu() });
  }
  var heldKeys = {};
""", 1),
    ("    if (k === 'ok' || k === 'back') { if (ev.repeat || heldKeys[code]) return; heldKeys[code] = true; }\n    press(k);",
     "    if (k === 'ok' || k === 'back') { if (ev.repeat || heldKeys[code]) return; heldKeys[code] = true; }\n"
     "    if (k === 'ok' && holdStart()) return;\n    press(k);", 1),
    ("  document.addEventListener('keyup', function (ev) { delete heldKeys[ev.keyCode]; });",
     "  document.addEventListener('keyup', function (ev) { X.keyUp(ev.keyCode); if (KEYS[ev.keyCode] === 'ok') HOLD.end(); delete heldKeys[ev.keyCode]; });", 1),
    ("  window.addEventListener('blur', function () { heldKeys = {}; });",
     "  window.addEventListener('blur', function () { heldKeys = {}; HOLD.cancel(); });", 1),
    ("    heldKeys = {};\n    if (document.hidden) { pauseRotation(); clearTimeout(clockTimer); }",
     "    heldKeys = {}; HOLD.cancel(); X.visible(!document.hidden); if (!document.hidden) { refreshBluetooth(true); uninstallReturned(); }\n    if (document.hidden) { pauseRotation(); clearTimeout(clockTimer); }", 1),
    # Android's uninstall dialog may only cover the page's window: Home coming back is then focus, not visibility.
    ("  window.addEventListener('focus', function () { release(); focusStage(); after(); updateClock(); });",
     "  window.addEventListener('focus', function () { release(); uninstallReturned(); focusStage(); after(); updateClock(); });", 1),
    ("      else if (k === 'ok') { a = A[t.f.b][0]; pop(); if (a === 'samsung') perform('Samsung Home', FrameDevice.home); }",
     "      else if (k === 'ok') { a = A[t.f.b][0]; pop(); if (a === 'samsung') perform('Samsung Home', FrameDevice.home); else if (t.appMenu) appMenuAct(t.appMenu, a);"
     " else if (t.sleepMenu) X.sleepSet(a, function (err) { if (err) message('Sleep', err.message, err.detail || ''); render(); });"
     " else if (t.btUnpair && a === 'yes') bluetoothUnpair(t.btUnpair); }", 1),
]

# Screen and Sleep (2026-11-01). Settings > Screen has two rows, Dim after and Sleep after,
# each opening its own list like Background > Rotation (link items carry `sub`; their pages' Back row
# names Screen through X.parent). The dim layer, its first-key rule and the sleep timer's state are
# web/home-extras.js (X.activity / X.keyUp / X.visible / X.wake, X.sleep*); the keydown, keyup and
# visibility hooks are in the long-press block above and the PGV keydown entry. Nothing shows under
# the clock (2026-11-01: "i don't want a sleep time display"). tests/home-extras.test.js.
# The Art Mode control is the Sleep button (2026-11-01: "we don't need a screen saver"): a moon,
# labelled Sleep, opening the Sleep menu (openSleepMenu, in the long-press block): Sleep now, then
# Sleep after's own choices, the same setting as Settings > Screen (2026-11-01: one setting,
# two doors; Sleep now "keeps the original setting after"). It is relabelled
# and answered here rather than by changing the Art Mode anchors above (see there); their
# screensaver branch is never reached and FrameDevice.art no longer exists.
MAIN += [
    ("  var SVG = {\n    art: ", "  var SVG = {\n    sleep: '<path d=\"M19.5 14.6A7.8 7.8 0 0 1 9.4 4.5a7.8 7.8 0 1 0 10.1 10.1z\"/>',\n    art: ", 1),
    ("  function utils() { return ['art', 'apps', 'settings']; }",
     "  UTIL.art = ['Sleep', 'sleep'];\n  function utils() { return ['art', 'apps', 'settings']; }", 1),
    ("  function utilAction(u) {\n", "  function utilAction(u) {\n    if (u === 'art') { openSleepMenu(); return; }\n", 1),
    ("t.group === 'rotation' ? 'Background' : 'Settings'", "t.group === 'rotation' ? 'Background' : X.parent(t.group) || 'Settings'", 4),
]

# Per-app name and icon (2026-11-01: "We also need to be able to edit the app icons and names. I
# would like a iptv icon that is in the clearframe style and can assign it to different apps. There
# will be others."). The long-press menu's Rename and Change icon (web/home-extras.js X.appMenu) open
# web/app-edit.js's screens (AEV, next to PGV above; appMenuAct and appsChanged in the long-press
# block). The overrides live in their own localStorage key, keyed by Android package; an override
# changes only how an app looks, never its id, launch target or place on Home:
#  - AE.dress(a) keeps the record's own name/mark in a.own and sets the shown a.name, a.mark and
#    a.markId: on every record at start-up, after refreshCatalog sets one, and on every change
#    (appsChanged). serialise() saves the OWN name, so cleanframe.home.v1 never holds an override.
#  - appMark draws a.markId's mark when set; setContent folds AE.rev() into every cached tile's
#    signature, so a dock, list, All apps or editor tile redraws when a name or mark changes.
MAIN += [
    ("if (a.mark && (maskSupport || S.c.bg !== 'light')) return markHtml(a.brand || a.id, w, h, size);",
     "if (a.mark && (maskSupport || S.c.bg !== 'light')) return markHtml(a.markId || a.brand || a.id, w, h, size);", 1),
    ("    sig += '|' + catalog.revision + '|' + fontRevision;", "    sig += '|' + catalog.revision + '|' + fontRevision + '|' + AE.rev();", 1),
    ("out.appRecords.push({ id: a.id, appId: a.appId, name: a.name, brand: a.brand || '', icon: a.icon || '' });",
     "out.appRecords.push({ id: a.id, appId: a.appId, name: AE.ownName(a), brand: a.brand || '', icon: a.icon || '' });", 1),
    ("    var saved = load(), initial; S.c = clean(saved);",
     "    var saved = load(), initial; S.c = clean(saved); APPS.forEach(function (a) { AE.dress(a); });", 1),
    ("        a.name = PRIMARY.indexOf(id) >= 0 ? PRIMARY_APPS[PRIMARY.indexOf(id)].name : String(entry.name).slice(0, 160);",
     "        a.name = PRIMARY.indexOf(id) >= 0 ? AE.ownName(PRIMARY_APPS[PRIMARY.indexOf(id)]) : String(entry.name).slice(0, 160);\n"
     "        AE.dress(a, true);", 1),
]

# Starter Home (2026-11-02: a new stick showed Rumble on Home although Rumble was not installed;
# "only put the ones on that have a modded icon", "if there is 5+ put 5 apps on if there is less put
# less on"). Only on a first start (no saved layout): when the installed-apps list arrives (it is read
# at start-up, above), Home becomes the installed apps that show a white mark, one per mark, in the
# mark library's order, at most AE.STARTER (5; AE.starter, web/app-edit.js); if none has a mark, the
# first installed app by name. Once only, and never if Home was already changed. A saved layout
# (every stick set up before this) is never touched.
MAIN += [
    ('    S.sel = S.c.lastApp; S.selIdx = S.c.home.indexOf(S.sel);\n',
     '    S.sel = S.c.lastApp; S.selIdx = S.c.home.indexOf(S.sel); S.starter = !saved;\n', 1),
    ('      catalog.loaded = true; catalog.error = null; catalog.revision++;\n',
     '      catalog.loaded = true; catalog.error = null; catalog.revision++;\n      uninstallSettle(entries);\n      if (S.starter) starterHome();\n', 1),
    # ALSO (web/device.js) can give two installed packages one brand. The brand's id is the first's; the
    # other takes its own package as its id, so both show and each opens its own package. (The same
    # package listed twice stays one app: the line after this drops it.)
    ("        var id = PRIMARY.indexOf(entry.brand) >= 0 ? entry.brand : entry.appId, a;\n",
     "        var id = PRIMARY.indexOf(entry.brand) >= 0 ? entry.brand : entry.appId, a;\n"
     "        if (id !== entry.appId && ALPHA.indexOf(id) >= 0 && app(id).appId !== entry.appId) id = entry.appId;\n", 1),
    ('  /* ---------- Navigation targets ---------- */\n',
     UNINSTALL_JS +
     "  function starterHome() {\n    var ids, c;\n    S.starter = false;\n    if (S.c.home.join() !== PRIMARY.join()) return;\n    ids = AE.starter(ALPHA.map(app), AE.STARTER);\n    if (!ids.length) ids = ALPHA.slice(0, 1);\n    if (!ids.length) return;\n    c = next(ids, []); c.lastApp = ids[0];\n    if (write(c, 'Apps on Home')) { S.sel = ids[0]; S.selIdx = 0; }\n  }\n\n  /* ---------- Navigation targets ---------- */\n", 1),
]

# Adjust (2026-11-02: "chose position and zoom in and out to get the right area"): the group
# page's Adjust mode (above, in the group page block) opens web/photo-adjust.js on a photo with its
# upright copy; the screen is drawn over the stage after everything else, and an applied photo whose
# files changed under the same id (its `edited`, web/wallpaper-groups.js) gets a new bundle key, so
# applyLibrary() loads its new files.
MAIN += [
    ("  function bundleKey(look) { return look.wall + '|' + look.material; }",
     "  function bundleKey(look) { var w = wall(look.wall); return look.wall + '|' + look.material + (w && w.edited ? '|' + w.edited : ''); }", 1),
    ('    renderAppearance(t);\n    renderFocusStatus(t);\n  }\n',
     '    renderAppearance(t);\n    renderFocusStatus(t);\n    PAV.render(t);\n  }\n', 1),
]

# index.html loads the Space and Cyberpunk galleries (space-catalog.js, then cyberpunk-catalog.js,
# append their entries to window.CleanFrameWallpapers after catalog.js), the wallpaper groups, the vendored QR generator, the
# material drawing for uploaded photos (materials.js, run by photo-groups.js) and the photo-group
# screens and our Home additions (home-extras.js, home-extras.css, then the mark library app-marks.js and
# app-edit.js, then the crop arithmetic photo-crop.js and the Adjust screen photo-adjust.js/.css, then
# Settings > Bluetooth, bluetooth.js; all ours),
# in that order, before main.js.
INDEX = [
    ('<link rel="stylesheet" href="style.css">\n',
     '<link rel="stylesheet" href="style.css">\n<link rel="stylesheet" href="wallpaper-groups.css">\n<link rel="stylesheet" href="home-extras.css">\n<link rel="stylesheet" href="photo-adjust.css">\n', 1),
    ('<script src="assets/wallpapers/catalog.js"></script>\n',
     '<script src="assets/wallpapers/catalog.js"></script>\n<script src="assets/wallpapers/space-catalog.js"></script>\n'
     '<script src="assets/wallpapers/cyberpunk-catalog.js"></script>\n'
     '<script src="wallpaper-groups.js"></script>\n<script src="qrcode-generator.js"></script>\n<script src="materials.js"></script>\n<script src="photo-groups.js"></script>\n<script src="home-extras.js"></script>\n'
     '<script src="app-marks.js"></script>\n<script src="app-edit.js"></script>\n<script src="photo-crop.js"></script>\n<script src="photo-adjust.js"></script>\n'
     '<script src="bluetooth.js"></script>\n', 1),
]

# Marks we added (the new defaults' brands, our own IPTV mark and the mark library's Simple Icons marks; README
# 'Adding a mark'), measured with tools/icon_metrics.py.
METRICS = {"emby": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.4450165441176444},
           "rumble": {"n": 240, "x": 10, "y": 0, "w": 219, "h": 240, "fill": 0.6443185752230832},
           "iptv": {"n": 240, "x": 15, "y": 15, "w": 210, "h": 200, "fill": 0.6648242763772135},
           "jellyfin": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.44884858387799204},
           "plex": {"n": 240, "x": 0, "y": 64, "w": 240, "h": 112, "fill": 0.43707195378151004},
           "kodi": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.3419908088235272},
           "vlc": {"n": 240, "x": 14, "y": 0, "w": 212, "h": 240, "fill": 0.3847164416080892},
           "itvx": {"n": 240, "x": 0, "y": 79, "w": 240, "h": 82, "fill": 0.4481063685636831},
           "channel4": {"n": 240, "x": 31, "y": 0, "w": 178, "h": 240, "fill": 0.3690257582433694},
           "now": {"n": 240, "x": 0, "y": 83, "w": 240, "h": 74, "fill": 0.6605482688570841},
           "paramountplus": {"n": 240, "x": 0, "y": 24, "w": 240, "h": 192, "fill": 0.6774387254901857},
           "dazn": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.4062346132897561},
           "mubi": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.429533224400867},
           "crunchyroll": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.5626739515250493},
           "twitch": {"n": 240, "x": 17, "y": 0, "w": 206, "h": 240, "fill": 0.44167642299637944},
           "youtubemusic": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.6836181917211257},
           "tidal": {"n": 240, "x": 0, "y": 40, "w": 240, "h": 160, "fill": 0.332828329248363},
           "deezer": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.5616276552287521},
           "youtubekids": {"n": 240, "x": 0, "y": 22, "w": 240, "h": 196, "fill": 0.7556158296651946},
           "googleplay": {"n": 240, "x": 12, "y": 0, "w": 216, "h": 240, "fill": 0.4526278443476098},
           "vimeo": {"n": 240, "x": 0, "y": 16, "w": 240, "h": 208, "fill": 0.49921631473101613},
           "dailymotion": {"n": 240, "x": 12, "y": 0, "w": 216, "h": 240, "fill": 0.31127163519728285},
           "soundcloud": {"n": 240, "x": 0, "y": 66, "w": 240, "h": 108, "fill": 0.551279956427008},
           "audible": {"n": 240, "x": 0, "y": 45, "w": 240, "h": 149, "fill": 0.2945375488002789},
           "pocketcasts": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.5530042892156802},
           "steam": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.5741806236383378},
           "playstation": {"n": 240, "x": 0, "y": 26, "w": 240, "h": 188, "fill": 0.38361198025309096},
           "nordvpn": {"n": 240, "x": 0, "y": 24, "w": 240, "h": 191, "fill": 0.5601559559251209},
           "expressvpn": {"n": 240, "x": 0, "y": 23, "w": 240, "h": 194, "fill": 0.6158403241021447},
           "surfshark": {"n": 240, "x": 23, "y": 0, "w": 194, "h": 240, "fill": 0.6663669058688714},
           "protonvpn": {"n": 240, "x": 0, "y": 11, "w": 240, "h": 218, "fill": 0.5215175091443253},
           "mullvad": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.45547167755990714},
           "fubo": {"n": 240, "x": 0, "y": 71, "w": 240, "h": 98, "fill": 0.4633079898626097},
           "starz": {"n": 240, "x": 0, "y": 92, "w": 240, "h": 56, "fill": 0.5788512488328696},
           "showtime": {"n": 240, "x": 0, "y": 0, "w": 240, "h": 240, "fill": 0.4599619417211245},
           "sky": {"n": 240, "x": 0, "y": 46, "w": 240, "h": 148, "fill": 0.3741505476064257},
           "bbciplayer": {"n": 240, "x": 32, "y": 6, "w": 176, "h": 230, "fill": 0.409176354336198},
           "my5": {"n": 240, "x": 46, "y": 6, "w": 163, "h": 229, "fill": 0.6280534985714451},
           "britbox": {"n": 240, "x": 20, "y": 13, "w": 200, "h": 213, "fill": 0.3986551597164657},
           "plutotv": {"n": 240, "x": 4, "y": 73, "w": 232, "h": 89, "fill": 0.31156319559982254},
           "discoveryplus": {"n": 240, "x": 10, "y": 10, "w": 225, "h": 220, "fill": 0.7233779362249897},
           "amazonmusic": {"n": 240, "x": 10, "y": 56, "w": 217, "h": 146, "fill": 0.3138341682231888},
           "smarttube": {"n": 240, "x": 5, "y": 15, "w": 230, "h": 207, "fill": 0.7580668915329185},
           "tivimate": {"n": 240, "x": 19, "y": 14, "w": 202, "h": 191, "fill": 0.37513226222529467},
           "plexamp": {"n": 240, "x": 5, "y": 35, "w": 230, "h": 170, "fill": 0.3236038313023393},
           "xbox": {"n": 240, "x": 5, "y": 5, "w": 230, "h": 230, "fill": 0.4449411023388519},
           "geforcenow": {"n": 240, "x": 5, "y": 30, "w": 231, "h": 180, "fill": 0.5604467645644079},
           "moonlight": {"n": 240, "x": 5, "y": 5, "w": 230, "h": 230, "fill": 0.6278924348567363},
           "purevpn": {"n": 240, "x": 5, "y": 5, "w": 230, "h": 230, "fill": 0.5285364913451128},
           "downloader": {"n": 240, "x": 21, "y": 10, "w": 209, "h": 224, "fill": 0.4140969904708263},
           "xplore": {"n": 240, "x": 10, "y": 18, "w": 222, "h": 177, "fill": 0.46586466825748574},
           "airscreen": {"n": 240, "x": 10, "y": 55, "w": 219, "h": 125, "fill": 0.5028989524576915}}
_HEAD = 'window.FrameIconMetrics = {'
ICONS = [(_HEAD, _HEAD + ','.join('"%s":%s' % (k, json.dumps(v, separators=(',', ':')))
                                  for k, v in METRICS.items()) + ',', 1)]

FILES = [('main.js', MAIN), ('assets/icons/metrics.js', ICONS), ('index.html', INDEX)]
NAMES = ', '.join(name for name, _ in FILES)


class PatchError(Exception):
    pass


class PartlyPatched(PatchError):
    """Some anchors (or files) are patched and others are not: a half-applied or hand-reverted
    tree, reported before any NEW UPSTREAM diagnosis (an upstream change cannot be judged on it)."""


def _short(s):
    return repr(s.strip().split('\n')[0][:70])


def state(text, pairs, name):
    """'unpatched' or 'patched' if every anchor agrees, else PatchError naming the anchor.
    Occurrences of `old` inside `new` (and vice versa) are discounted, so an anchor that is a
    prefix of its replacement still classifies cleanly."""
    states = []
    for old, new, want in pairs:
        n_old, n_new = text.count(old), text.count(new)
        if n_old == want and n_new == want * old.count(new):
            states.append('unpatched')
        elif n_new == want and n_old == want * new.count(old):
            states.append('patched')
        else:
            raise PatchError('%s: anchor %s matches neither upstream (want %d x old, found %d) '
                             'nor patched (want %d x new, found %d)'
                             % (name, _short(old), want, n_old, want, n_new))
    if len(set(states)) > 1:
        mixed = [_short(o) for (o, _, _), s in zip(pairs, states) if s != states[0]]
        raise PartlyPatched('%s: partly patched (%s for most anchors, not for %s)'
                         % (name, states[0], ', '.join(mixed)))
    return states[0]


def apply(text, pairs):
    for old, new, _ in pairs:
        text = text.replace(old, new)
    return text


def unapply(text, pairs):
    for old, new, _ in reversed(pairs):
        text = text.replace(new, old)
    return text


def _sha(b):
    return hashlib.sha256(b).hexdigest()


def manifest():
    try:
        m = json.loads(MANIFEST.read_bytes())
    except (OSError, ValueError) as e:
        raise PatchError('cannot read %s: %s' % (MANIFEST.name, e))
    if not isinstance(m, dict):
        raise PatchError('%s: not a JSON object' % MANIFEST.name)
    if m.get('upstream_sha') != UPSTREAM_SHA:
        raise PatchError('%s is for upstream %s, UPSTREAM_SHA is %s (regenerate it: --write-manifest)'
                         % (MANIFEST.name, m.get('upstream_sha'), UPSTREAM_SHA))
    files = m.get('files')
    if not isinstance(files, dict) or not files:
        raise PatchError('%s: no "files" table (regenerate it: --write-manifest)' % MANIFEST.name)
    # The patched files are looked up by name (verify, apply): each must be there, as must every
    # entry be a sha256, or a damaged manifest surfaces as a KeyError traceback, not a PatchError.
    missing = [name for name, _ in FILES if name not in files]
    if missing:
        raise PatchError('%s: no entry for %s (regenerate it: --write-manifest)'
                         % (MANIFEST.name, ', '.join(missing)))
    bad = sorted(k for k, v in files.items()
                 if not isinstance(v, str) or len(v) != 64 or v.strip('0123456789abcdef'))
    if bad:
        raise PatchError('%s: entry for %s is not a sha256' % (MANIFEST.name, ', '.join(bad[:5])))
    return files


def read(web):
    return {name: (web / name).read_bytes().decode('utf-8') for name, _ in FILES}


TMP_SUFFIX = '.android-patch-tmp'


def write_all(web, out, old):
    """Write every file in `out` (name -> bytes) or none of them. Each new file is first written in
    full to a temp file beside it (same directory, so same file system); only when every temp file is
    written are they moved into place with os.replace, which is atomic per file. If a move fails, the
    files already moved get their old bytes (`old`) back. Temp files never outlive the call: one left
    in web/ would be a stray to --verify (and to the APK)."""
    tmps = {}
    try:
        for name in out:
            tmp = web / (name + TMP_SUFFIX)
            # 'x': never reuse (or follow) something already there. Only a temp file this call has
            # created is registered for clean-up: one that was already there (a concurrent run's, or
            # anything else at that path) makes the call refuse and is left exactly as it was.
            f = open(str(tmp), 'xb')
            tmps[name] = tmp
            with f:
                f.write(out[name])
                f.flush()
                os.fsync(f.fileno())
        done = []
        try:
            for name in out:
                os.replace(str(tmps[name]), str(web / name))
                done.append(name)
        except OSError as e:
            failed, restore_failed = name, []
            for back in done:
                try:
                    (web / back).write_bytes(old[back])
                except OSError:
                    restore_failed.append(back)
            if restore_failed:
                what = 'COULD NOT RESTORE %s: restore them from git' % ', '.join(restore_failed)
            elif done:
                what = 'put back %s, nothing was changed' % ', '.join(done)
            else:
                what = 'nothing was changed'
            raise PatchError('could not write %s (%s); %s' % (failed, e, what))
    except OSError as e:
        raise PatchError('could not write the patched files, nothing was changed: %s' % e)
    finally:
        for tmp in tmps.values():
            try:
                os.unlink(str(tmp))
            except OSError:
                pass


def upstream_of(text, pairs, name):
    """sha256 of the upstream this file was made from, or None if the anchors do not classify."""
    try:
        st = state(text, pairs, name)
    except PatchError:
        return None
    return _sha((unapply(text, pairs) if st == 'patched' else text).encode('utf-8'))


def verify(web):
    """Raise PatchError unless web/ is exactly pinned upstream + this patch + OURS."""
    files = manifest()
    errors = []
    for name, pairs in FILES:
        try:
            text = (web / name).read_bytes().decode('utf-8')
            if state(text, pairs, name) != 'patched':
                raise PatchError('%s: not patched (run tools/android-patch.py web)' % name)
            got = _sha(unapply(text, pairs).encode('utf-8'))
            if got != files[name]:
                raise PatchError('%s: is not upstream %s + this patch (edited outside the patch? '
                                 'reverse-applied sha256 %s, want %s)'
                                 % (name, UPSTREAM_SHA[:7], got, files[name]))
            if apply(unapply(text, pairs), pairs) != text:
                raise PatchError('%s: patch does not round-trip' % name)
        except (OSError, UnicodeDecodeError) as e:
            errors.append('%s: %s' % (name, e))
        except PatchError as e:
            errors.append(str(e))
    on_disk = set()
    for p in sorted(web.rglob('*')):
        rel = p.relative_to(web).as_posix()
        if p.is_symlink():
            errors.append('%s: is a symlink (not allowed in web/)' % rel)
        elif p.is_file():
            on_disk.add(rel)
    patched = {name for name, _ in FILES}
    for rel in sorted(files):
        if rel in patched or rel in OURS:
            continue
        if rel not in on_disk:
            errors.append('%s: upstream file is missing' % rel)
        elif _sha((web / rel).read_bytes()) != files[rel]:
            errors.append('%s: differs from upstream %s (hand edit? it would be lost at the next '
                          'upstream copy; change it via tools/android-patch.py or list it in OURS)'
                          % (rel, UPSTREAM_SHA[:7]))
    for rel in sorted(OURS):
        if rel not in on_disk:
            errors.append('%s: listed in OURS but missing' % rel)
    if QR_FILE in on_disk and _sha((web / QR_FILE).read_bytes()) != QR_SHA256:
        errors.append('%s: is not the vendored qrcode-generator 1.4.4 (sha256 %s, recorded %s)'
                      % (QR_FILE, _sha((web / QR_FILE).read_bytes()), QR_SHA256))
    for rel in sorted(on_disk - set(files) - set(OURS) - set(OPTIONAL)):
        errors.append('%s: neither upstream nor in OURS (stray file? the APK would package it)' % rel)
    if errors:
        raise PatchError(errors[0] if len(errors) == 1 else
                         '%d problems:\n  %s' % (len(errors), '\n  '.join(errors)))
    return len(files), len(OURS)


def write_manifest(commit):
    """tools/upstream-manifest.json from web/ at COMMIT, which must be the unmodified upstream copy:
    its patched files unpatched, none of OUR files there (only upstream files we later changed,
    CHANGED_UPSTREAM, may be), and the commit naming UPSTREAM_SHA in its message (or already the
    manifest's from_commit for UPSTREAM_SHA). Every refusal
    happens before the manifest is touched."""
    top = MANIFEST.parent.parent
    git = lambda *a: subprocess.run(['git', '-C', str(top)] + list(a), check=True,
                                    capture_output=True).stdout
    full = git('rev-parse', '--verify', '%s^{commit}' % commit).decode().strip()
    files = {}
    for line in git('ls-tree', '-r', '-z', commit, 'web/').split(b'\0'):
        if not line:
            continue
        meta, path = line.split(b'\t', 1)
        mode, kind, obj = meta.split()
        if kind != b'blob' or mode not in (b'100644', b'100755'):
            raise PatchError('%s at %s: unexpected %s %s' % (path, commit, mode, kind))
        files[path.decode()[len('web/'):]] = _sha(git('cat-file', 'blob', obj.decode()))
    for name, pairs in FILES:
        text = git('show', '%s:web/%s' % (commit, name)).decode('utf-8')
        try:
            st = state(text, pairs, name)
        except PatchError as e:   # e.g. patched by an older version of this file (fewer anchors)
            raise PatchError('%s at %s is already patched (or not upstream: %s): give the upstream-copy commit'
                             % (name, commit, e))
        if st != 'unpatched':
            raise PatchError('%s at %s is already patched: give the upstream-copy commit' % (name, commit))
    # Our files at that commit mean it is not the bare upstream copy (ours restored, or a later
    # commit with the patched files reverted): pinning it would pin them as "upstream" and --verify
    # would stop checking them. The upstream files we change on purpose are upstream's there.
    ours = sorted(rel for rel in files if rel in OURS and rel not in CHANGED_UPSTREAM)
    if ours:
        raise PatchError('%s at %s %s ours (OURS in tools/android-patch.py), not upstream: give the commit that '
                         'copied upstream app/ over web/, before our files were restored'
                         % (', '.join(ours[:5]) + (' and %d more' % (len(ours) - 5) if len(ours) > 5 else ''),
                            commit, 'is' if len(ours) == 1 else 'are'))
    # The commit must be the copy of UPSTREAM_SHA: its message names the full SHA (065aa2b says
    # "Vendor CleanFrame app/ at upstream <sha>"), or the manifest already pins UPSTREAM_SHA from
    # this very commit (regenerating the same pin). So a stale UPSTREAM_SHA, or the copy of another
    # upstream, is not pinned under this one's name.
    message = git('log', '-1', '--format=%B', full).decode('utf-8', 'replace')
    try:
        current = json.loads(MANIFEST.read_bytes())
    except (OSError, ValueError):
        current = None
    same_pin = (isinstance(current, dict) and current.get('upstream_sha') == UPSTREAM_SHA
                and current.get('from_commit') == full)
    if UPSTREAM_SHA not in message and not same_pin:
        raise PatchError('%s does not name upstream %s in its message: set UPSTREAM_SHA to the upstream that '
                         'commit copied (or give the commit that copied %s)' % (commit, UPSTREAM_SHA, UPSTREAM_SHA[:7]))
    out = {'upstream_sha': UPSTREAM_SHA,
           'from_commit': full,
           'regenerate': 'python3 tools/android-patch.py --write-manifest <upstream-copy commit>',
           'files': dict(sorted(files.items()))}
    MANIFEST.write_bytes((json.dumps(out, indent=1) + '\n').encode('utf-8'))
    print('wrote %s: %d upstream files at %s' % (MANIFEST.name, len(files), UPSTREAM_SHA[:7]))


USAGE = ('usage: tools/android-patch.py [WEB]            apply (WEB defaults to web)\n'
         '       tools/android-patch.py --verify [WEB]\n'
         '       tools/android-patch.py --write-manifest COMMIT')


def main(argv):
    args = argv[1:]
    # Exactly the three forms in the docstring: --verify only first, at most one WEB, no other option.
    # An extra argument or a misplaced/unknown option used to be ignored (apply when --verify was meant).
    if args[:1] == ['--write-manifest']:
        ok = len(args) == 2 and not args[1].startswith('-')
    else:
        rest = args[1:] if args[:1] == ['--verify'] else args
        ok = len(rest) <= 1 and not any(a.startswith('-') for a in rest)
    if not ok:
        print('android-patch: unexpected arguments %r\n%s' % (args, USAGE), file=sys.stderr)
        return 2
    try:
        if args[:1] == ['--write-manifest']:
            write_manifest(args[1])
            return 0
        verify_only = args[:1] == ['--verify']
        args = args[1:] if verify_only else args
        web = pathlib.Path(args[0] if args else 'web')
        if verify_only:
            n, o = verify(web)
            print('verified: %s is upstream %s (%d files) + android patch + %d of ours'
                  % (web, UPSTREAM_SHA[:7], n, o))
            return 0
        files = manifest()
        texts = read(web)
        # A partly patched tree first: it cannot be judged against upstream, and calling it NEW
        # UPSTREAM sends whoever reads it off reviewing anchors that never moved.
        states, partly = {}, []
        for name, pairs in FILES:
            try:
                states[name] = state(texts[name], pairs, name)
            except PartlyPatched as e:
                partly.append(str(e))
            except PatchError:
                pass
        if not partly and len(states) == len(FILES) and len(set(states.values())) > 1:
            partly.append('partly patched: %s' % ', '.join('%s is %s' % kv for kv in states.items()))
        if partly:
            raise PatchError('; '.join(partly) + ' (restore the patched files from git, then apply)')
        new = [name for name, pairs in FILES if upstream_of(texts[name], pairs, name) != files[name]]
        new_msg = ('NEW UPSTREAM: %s %s not the pinned upstream %s. Review every anchor in '
                   'tools/android-patch.py against it, then set UPSTREAM_SHA and regenerate the '
                   'manifest (--write-manifest <upstream-copy commit>)'
                   % (' and '.join(new), 'is' if len(new) == 1 else 'are', UPSTREAM_SHA[:7]))
        try:
            states = {name: state(texts[name], pairs, name) for name, pairs in FILES}
        except PatchError as e:
            raise PatchError('%s.\n  anchor failure: %s' % (new_msg, e) if new else str(e))
        if set(states.values()) == {'patched'}:
            print('already applied: %s' % NAMES)
        else:
            out = {name: apply(texts[name], pairs) for name, pairs in FILES}
            for name, pairs in FILES:  # the result must classify as patched before anything is written
                if state(out[name], pairs, name + ' (after apply)') != 'patched':
                    raise PatchError('%s: apply did not produce a patched file' % name)
            write_all(web, {name: out[name].encode('utf-8') for name, _ in FILES},
                      {name: texts[name].encode('utf-8') for name, _ in FILES})
            print('patched %s' % NAMES)
        if new:
            print('android-patch: %s; --verify fails until then.' % new_msg, file=sys.stderr)
        return 0
    except (PatchError, OSError, UnicodeDecodeError, subprocess.CalledProcessError) as e:
        print('android-patch: %s' % e, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))

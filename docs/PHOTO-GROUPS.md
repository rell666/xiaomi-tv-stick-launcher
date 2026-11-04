# Photo groups and uploads — design (v2, 2026-10-31: direct to the stick)

2026-10-31: *"I would like the groups on the Background page and select from there. Also
would like an Add group, and in the group page Add photo. Would like an easy way to upload photos."*
Built on branch `feature/photo-groups`; ~~not tested on the stick~~ → **tested on the stick as the
staging app, 2026-10-31 (branch `device-fixes`; results at the end)**; not deployed as Home. Everything
below marked **DEVICE-CHECK** must be verified on the stick before merge.

## What the user sees

**Settings → Background**

| Section | Rows |
|---|---|
| Background type | Photo · Light · Dark (unchanged) |
| Groups | one row per group: *Scenery · 15 photos*, *Space · 15 photos*, then user groups in creation order; a tick on the **active group** (the group of the applied wallpaper); last row **+ Add group** |
| Rotation | rotates only within the active group, to a random other photo of it (2026-11-03: also at start-up and when turned on); while it rotates the tick is on Rotation and the group, not on a photo |

The old single *Wallpaper* row and the group switcher row inside the picker (`3b95ace`) are removed:
the group is chosen here. Groups are disabled (with the existing reason line) when the type is not Photo.

**Group page** (OK on a group): title = group name, Back row → *Background*.
- The photo list: the existing wallpaper picker, filtered to this group (thumbnails, preview on
  focus, OK applies the photo and so makes this group active).
- **Add photo** (every group, built-in ones included): opens the Add photo screen.
- **Remove photos** (only when the group has uploaded photos): a mode in which OK on an uploaded
  photo asks *Remove "<name>"?* Yes / No. Built-in photos cannot be removed.
- **Rename group**, **Delete group** (user groups only; Delete asks to confirm; if the applied
  wallpaper was in it, the first Scenery wallpaper is applied).
- An empty group shows *No photos yet* with **Add photo** focused.

**+ Add group**: a screen with one text field (Android's on-screen keyboard, prefilled *Group N*),
**Create** and **Cancel**. Create opens the new group's page with **Add photo** focused.
Names: 1–24 characters after trimming, no control characters; at most 20 user groups.

**Add photo screen**: a QR code, the address under it (~~`http://192.168.0.6:8097`~~ → `http://<stick IP>:8097`, v2), a 6-digit
code, *Scan with your phone's camera*, and a live line *N photos added*. New photos appear in the
group while the screen is open (it re-reads the library every 2 s, and once more when the screen
closes, so a photo removed from the phone just before Done leaves the group page too). **Done** returns to the group
page. The code lasts 15 minutes and is renewed while the screen stays open; closing the screen
ends it.

**On the phone** (the page the QR opens, served by a helper service in v1): the group's name, a big **Choose
photos** button (`<input type=file accept="image/*" multiple>`, so the phone's own photo picker
opens), one progress line per photo (uploading → preparing → on the TV), and a list of the
group's photos with **Remove**. Also **Rename group** and **New group**. No app to install, no
account. A phone that cannot scan types the address and the 6-digit code.

## Why photos go straight to the stick (v2)

2026-10-31, after seeing v1 (which sent photos through a service on the build machine): *"I'd like the
direct version."* So the TV app receives and prepares photos itself, and the build machine plays no part.

```
phone ──HTTP (LAN, only while Add photo is open)──▶ stick: the app's own upload page
                                                      │ Java: decode, orient, crop, resize, strip
                                                      │ metadata, readability fields
                                                      ▼
                                                    the page (WebView = Chromium) draws the panel
                                                    and 4 materials, hands them back to Java
                                                      ▼
                                                    R/groups/<gid>/photos/<pid>/  (contract 1)
```

**The accepted trade:** the app now holds the **INTERNET permission**, because Android has no
LAN-only permission and a listening socket needs it. The guarantee changes from "the home screen
*cannot* go online" to "it *does not*": it opens no outgoing connection (a gate test fails on any
outgoing-connection API in the app's code), the WebView's origin lock stays, and the upload page
listens only while the Add photo screen is open, behind a 15-minute token. There is no copy of
the photos off the stick: uninstalling the app loses them, like the saved layout.

~~v1: photos through a service on the build machine (`tools/photo-upload`, port 8097, archive mirror)~~ →
removed in v2; its phone page, readability rules and test vectors were moved into the app.

## Contract 1 — storage on the stick

Root **R** = the app's external files dir + `/cleanframe`, i.e.
`/sdcard/Android/data/<package>/files/cleanframe/` (`home.cleanframe.tv`; the staging app has its
own). No permission is needed for the app to read or write it. **DEVICE-CHECK 1 (stick,
2026-10-31): adb shell can neither read nor write it** on this Android 11 user build (`Permission
denied` on `/sdcard/Android/data/<package>/` once the app has created it; uid 2000 is refused the
other apps' dirs). In v2 only the app writes R, so R stays here; ~~fall back to `/sdcard/CleanFrame/`
plus `READ_EXTERNAL_STORAGE`~~ → not needed: `tools/stick-test.sh` no longer touches R over adb and
checks everything through the app's upload server (curl, as a phone) and the page's `user/` route
(DevTools).

```
R/groups/<gid>/group.json                     user groups only
R/groups/<gid>/photos/<pid>/wall.jpg          1920x1080, the background (~~1920x1280~~ until 2026-11-02; older photos keep it)
                            thumb.jpg         512x288 (~~512x341~~ likewise)
                            src.jpg           optional: the upright copy Adjust starts from (since 2026-11-02)
                            panel.jpg         upstream panel backdrop size
                            field-blur.jpg  field-clear.jpg  field-frost.jpg  study-soft.jpg
                            meta.json         written LAST
R/upload-token.json                           while the Add photo screen is open
```

- `gid`: user groups `^g[0-9a-z]{8}$` (random); the built-ins `scenery`, `space` and `cyberpunk` may also have
  `R/groups/scenery/photos/…` for uploads added to them (they have no `group.json`).
- `pid`: `^p[0-9a-z]{10}$` (random).
- `group.json`: `{"v":1,"id":"<gid>","name":"<name>","created":<ms>}`.
- `meta.json`: `{"v":1,"id":"<pid>","name":"<display name>","added":<ms>,"tone":"dark|light",
  "ut":{"tr":"…","br":"…"},"frostVeil":<0..0.3>,"listColumn":<int>,"dockQuiet":<bool>,
  "clockMist":<bool>}` (same value sets as upstream catalog entries).
- **No shared mutable file.** Each group and each photo is its own directory, so the app and the
  server never rewrite the same file. Writers create `…/photos/.<pid>.tmp/`, fill it (meta.json
  last), then rename it to `…/photos/<pid>/` (one `mv`). Readers ignore dot-entries, and any photo
  directory without a valid meta.json and all seven files.
- Deleting is removing the directory. A group's directory is removed with its photos.
- `upload-token.json`: `{"v":1,"token":"<32 hex>","code":"<6 digits>","group":"<gid>","expires":<ms>}`,
  written by the app (mode private to the app; adb can read it), removed when the screen closes.
- Limits: 20 user groups, 60 photos per group, 300 photos in all (~0.7 MB each, ~200 MB).

## Contract 2 — the app (Android)

- `UserLibrary` (plain Java, no Android types; clock, random and filesystem root injected) owns
  every rule above: id and name validation, listing (skipping anything malformed), create, rename
  and delete group, delete photo, issue and clear the upload token, limits. MainActivity only
  wires it up. Tested offline like `FallbackPolicy` (`tests/java/`).
- New route `https://cleanframe.local/home/user/<gid>/<pid>/<file>.jpg` in `shouldInterceptRequest`
  → `R/groups/<gid>/photos/<pid>/<file>.jpg`, only for the seven fixed file names and valid ids,
  canonical path checked to stay under R; anything else 404. `image/jpeg`.
- Bridge (`window.AndroidHome`, every method returns a JSON string):
  - `library()` → `{"v":1,"groups":[{"id","name","created","builtin"}],"photos":[{"group","id",…meta}]}`
    (built-ins `scenery`, `space`, `cyberpunk` listed first with `builtin:true`);
  - `addGroup(name)` → `{"ok":true,"id":"<gid>"}` or `{"ok":false,"reason":"…"}`;
  - `renameGroup(gid, name)`, `deleteGroup(gid)`, `deletePhoto(gid, pid)` → `{"ok":…}`;
  - `uploadStart(gid)` → `{"ok":true,"url":"<base>/u/<token>","base":"<base>","code":"<6 digits>","expires":<ms>}`;
  - `uploadStop()` → `{"ok":true}`.
- ~~Upload base URL from `BuildConfig`, set by the Gradle property `cleanframe.uploadUrl`
  (default `http://192.168.0.6:8097`)~~ → v2: `base` is `http://<stick LAN IPv4>:8097`
  (`UploadServer.baseUrl`, the address its server is bound to); no Gradle property.
- ~~Still **no INTERNET permission**~~ → v2: INTERNET, and no outgoing-connection API anywhere in
  `android/` (`tests/android.test.py`).
- As built (`pg-android`): R comes from `MainActivity.libraryRoot()` only. `library()` gives the
  built-ins as `{"id":"scenery","name":"Scenery","created":0,"builtin":true}` (then Space, then
  Cyberpunk), and
  each photo as `group`, `id` and the meta fields (not `v`, no unknown keys). Photos are ordered by
  `added`, then group order, then id: the order `expected.json` gives. meta.json is checked against
  upstream's value sets: tone `dark|light`; ut.tr/br `light|mist|dark`; frostVeil a number 0–0.44
  (upstream uses .12 .20 .30 .36 .44); listColumn an integer 0–3; name 1–200 characters with no
  control characters; `added` ≥ 0. JSON files over 16 KB are treated as malformed. `reason` is
  a sentence the page can show as it is. `uploadStart` refuses a full group (60, or 300 in all), a
  page that has been dropped, and a Home that is not on screen. It ~~reads `base` from `BuildConfig.UPLOAD_URL`~~ is given `base` (v2: the LAN address);
  the build fails unless that is `http://<host>:<port>`. `deleteGroup` also removes a token issued
  for that group, and stops the upload page (MainActivity's `stopUpload()`; review 006cfe9). `uploadStart` on the same group while its token is live renews it (same token
  and code, `expires` = now + 15 min, written atomically), so a phone already on the URL is not cut
  off; a different group, an expired token or none gets a fresh one. JSON is hand-written: a writer, plus a strict RFC 8259 reader (no org.json, so the test runs
  under plain `javac`).

## Contract 3 — the page (web)

- `web/device.js` wraps the bridge calls in the existing async style (`FrameDevice.library(done)`
  etc.; one answer, always async, errors as reasons).
- Uploaded photos become ordinary wallpaper entries: id `u-<gid>-<pid>`, `group: <gid>`, the meta
  fields, and `files: {wall, thumb, panel, blur, clear, frost, soft}` holding `user/<gid>/<pid>/…`
  paths. main.js (through `tools/android-patch.py`) builds every image path through one helper
  that uses `w.files` when present and the upstream `id` convention otherwise.
- `web/wallpaper-groups.js` grows: merge library photos into the catalogue, group list with
  labels and counts (built-ins first, then user groups by `created`), resolve a saved id that no
  longer exists (deleted photo or group) to the first Scenery wallpaper.
- The QR code is drawn by a vendored MIT QR generator (ES5, exact version, sha-256 recorded,
  licence in `web/LICENSES/`), listed in `OURS`.
- ES5 only; D-pad focus model kept; the CleanFrame look reused (`setting-choice`, thumbnails).
- **DEVICE-CHECK 2:** the WebView text field brings up the TV keyboard and returns the text.

## Contract 4 — the upload page on the stick (v2)

Part of the app (`android/`), plain Java, no third-party libraries.

- **Server:** a minimal HTTP/1.1 server on `ServerSocket`, port **8097**, bound to the stick's
  Wi-Fi/Ethernet IPv4 address only (not loopback, not IPv6 wildcard). Started by `uploadStart`,
  stopped by `uploadStop`, `onStop` and page rebuild; never running otherwise. One request at a
  time is enough (uploads are sequential); a small thread pool with a hard cap is fine. Timeouts
  on every socket read. No keep-alive needed.
- **URL shown on the TV** = `http://<stick IPv4>:8097/u/<token>` (the IP read from the active
  network interface; if there is none, the Add photo screen says so).
- **Auth = the token**, held in memory by `UserLibrary` (the token file of contract 1 is no longer
  needed; keep or drop it, but nothing outside the app reads it). Constant-time compare, expiry,
  renewal keeps the token (unchanged rule). `GET /` asks for the 6-digit code (≤ 10 wrong codes a
  minute from one client address and 60 from all, then refuse; review 999ba07 L2) and redirects to `/u/<token>`.
- **Endpoints** (same as v1 so the phone page carries over): `GET /u/<token>` (page),
  `/static/{app.js,style.css,crop.js}`, `/api/<token>/state`, `POST /api/<token>/photos` (multipart, one
  image, streamed to a temp file in the app's cache dir, never held whole in memory; optional field
  `crop` = `x,y,z`, a malformed one is refused 400 `bad-crop`; a temp file that cannot be written,
  a full disk, is refused 507 `storage-full`, "TV storage is full…", and removed),
  `DELETE /api/<token>/photos/<gid>/<pid>`, `POST /api/<token>/groups`,
  `PATCH /api/<token>/groups/<gid>`, `GET /api/<token>/thumbs/<gid>/<pid>.jpg`.
  No restore endpoint (there is no archive). Headers: CSP, no-referrer, no-store (a thumb: private, short-lived, its address holds the token); no stack traces.
- **Limits:** 40 MB body (Content-Length checked before reading), 60 megapixels (from the header
  via `inJustDecodeBounds`), JPEG/PNG/WebP (HEIC if the stick's decoder accepts it: on the Xiaomi
  stick it did not, DEVICE-CHECK 11, so a HEIC is refused with its own message), ≥ 64 px,
  plus the library limits of contract 1.
- **Processing (Java):** `BitmapFactory` with `inSampleSize` so the decoded bitmap is the smallest
  power-of-two reduction still ≥ the size it is drawn at (memory on the 2 GB stick); orientation from
  `android.media.ExifInterface`; ~~cover-crop 3:2 at the centre; wall 1920x1280, thumb 512x341~~ →
  the crop (below; none = the centred 16:9), wall 1920x1080 JPEG q87, thumb 512x288 q80 (2026-11-02).
  Re-encoding a `Bitmap` writes no EXIF/XMP/GPS; a test proves no metadata survives.
- **Crop and Adjust (2026-11-02: "chose position and zoom in and out to get the right area";
  "keep the quality high").** A crop is `x,y,z` in the upright picture: `z = 1` is the largest 16:9
  frame inside it, more zooms in (up to 4), less zooms out down to the whole picture (the rest of the
  wall black); `x`, `y` are the frame's centre as fractions, kept so the frame stays on the picture.
  `PhotoMath` (Java) and `web/photo-crop.js` (the TV, and the phone as `/static/crop.js`, the same
  file) do the arithmetic; `tests/upload/data/crop-vectors.json` holds the answers both must give.
  The phone page shows each chosen photo in a 16:9 frame (drag, pinch or the slider; Whole photo,
  Fill the screen), then sends an upright JPEG copy of at most 4096 px a side and 4096x3072 pixels
  (q0.92; a 12 MP phone photo goes at full size) with the crop. The stick cuts the frame out of that
  copy and only then scales it to 1920x1080, so quality is lost only when the frame is narrower than
  1920 px (the page says "softer"). It keeps the copy as `src.jpg` with its metadata segments cut out
  byte for byte (`Jpeg.strip`, no re-encoding); an upload that is not such a copy (an old page, a
  rotated or bigger file) gets a `src.jpg` made on the stick of at most 4 MP; none is kept when the
  stick has under 500 MB free (the photo is still added, without Adjust). On the TV, a group page's
  **Adjust** mode opens a photo that has `src.jpg` full screen as the wall will be: Move or Zoom with
  the arrows, Whole photo, Fill screen, Save, Cancel. Save calls `AndroidHome.recrop(gid, pid, crop)`
  (answers at once) and `recropState(gid, pid)` (`working` → `saving` → `done`, or `failed` with its
  reason): the stick re-makes the photo from `src.jpg` into `.<pid>.tmp` with `edited` in its
  meta.json, the page draws its materials as for any upload, and the finished photo is swapped in
  under the SAME id (the old one renamed to `.<pid>.del-*` first), so an applied wallpaper stays
  applied. `library()` lists `src` (when there is one), `crop` and `edited`; the page adds `?v=<edited>`
  to the photo's file paths and to its bundle key, so it loads the new files. Photos added before
  2026-11-02 have no `src.jpg` and cannot be adjusted (send them again).
  Readability fields: the Space-gallery luminance rules ported to plain Java and tested against
  the same vectors as the v1 Python (`upstream-readability.json` + synthetic cases).
- **Materials (page):** the panel and the 4 materials are drawn by the page, in the WebView, by an
  ES5 port of upstream's `scripts/build-materials.mjs` (kept for reference byte-exact, with its SHA,
  as `docs/reference/build-materials.mjs`) using canvas (`ctx.filter` blur and the same
  compositing), from ~~`user/<gid>/<pid>/` tmp images~~ → `https://cleanframe.local/home/user-tmp/<gid>/<pid>/wall.jpg`
  (that one file, same origin as the page, so the canvas is not tainted). `AndroidHome.materialJobs()`
  → `{"v":1,"jobs":[{"gid","pid","wall":"user-tmp/<gid>/<pid>/wall.jpg","tone","need":[...]}]}`
  lists every tmp photo with wall, thumb and meta.json that still lacks materials (`need`: the
  missing ones of panel, field-blur, field-clear, field-frost, study-soft). The page returns each as
  a JPEG data URL through `AndroidHome.saveMaterial(gid, pid, kind, dataUrl)` →
  `{"ok":true,"done":<bool>}` or `{"ok":false,"reason"}`; Java validates (`data:image/jpeg;base64,`,
  at most 1 MB, a whole JPEG of that kind's size: panel 320x180, the others 1920x1080, as upstream's
  files) and writes it atomically. After queueing a job Java calls
  `window.FrameDevice&&FrameDevice.materialsWake&&FrameDevice.materialsWake()` on a live page. When all five
  exist plus wall, thumb and meta.json, Java renames `.<pid>.tmp` to `<pid>` (contract 1). A job
  the page did not finish (page rebuilt, app stopped) is retried on the next page load; tmp dirs
  older than 1 hour are removed.
- **Phone status:** the phone page polls `state`; a photo shows *preparing* until it is renamed
  into place, then *on the TV*.
- **No outgoing connections:** no `URL.openConnection`, `HttpURLConnection`, `Socket(host, …)`,
  `SocketChannel.connect`, `DatagramSocket` send, or any HTTP client anywhere in `android/`.
  `tests/android.test.py` enforces this; only `ServerSocket` is allowed.
- **WebView:** the origin lock stays exactly as it is. Try `setBlockNetworkLoads(true)` too
  (DEVICE-CHECK 7: the page must still load through `shouldInterceptRequest`; if not, leave it off
  and rely on the origin lock and its tests).

## Tests (offline, run by `tools/check.py`)

- `tests/java/UserLibraryTest.java` — ids, names, limits, traversal attempts, malformed files
  skipped, tmp directories ignored, token issue/expiry/clear.
- `tests/fixtures/user-library/` — a committed example R tree. The Java test, the JS tests and the
  server tests all read it, so the three sides cannot drift apart on the layout.
- `tests/wallpaper-groups.test.js`, `tests/device.test.js`, `tests/patch.test.py` — extended.
- ~~`tests/photo-upload.test.py` (v1 service, fake adb)~~ → v2: `tests/upload.test.sh` runs
  `tests/upload/*.java`: UploadServer end to end over a loopback socket with a stub processor
  (token, code and rate limit, every endpoint, limits, traversal, multipart edge cases, header
  injection, timeouts, start/stop), PendingPhotos against `tests/upload/fixture/R` (materialJobs,
  saveMaterial validation, finalize, start-up clean-up), PhotoMath/Jpeg (crop, inSampleSize,
  orientation, planted EXIF/GPS/XMP/IPTC/COM found), Readability parity with v1's vectors
  (`tests/upload/data/`). The Android encoder's output is checked at run time by `Jpeg.parse()`
  (no metadata segment is written); on the stick by `tools/stick-test.sh` (DEVICE-CHECK 10).
- `tools/stick-test.sh` cases for the Background groups, Add group, Add photo and Remove written
  now, ~~**not run**~~ → run on the stick 2026-10-31 (DEVICE-CHECK 3; they read R through the app's
  server and the page, never adb: DEVICE-CHECK 1).

## DEVICE-CHECKs before merge

1. ~~adb shell can write R~~ → no longer needed in v2 (only the app writes R). **Checked
   2026-10-31: adb cannot even read R** on the stick, so the stick-test plants nothing over adb: it
   uploads through the app's server and reads back through the server and the page.
2. The text field brings up the keyboard and returns the name.
3. The new stick-test cases pass on staging; then `--recovery`, release, `--reboot` as usual.
4. A phone on the LAN scans the QR, uploads a photo, and it appears on the TV within ~15 s.
5. Memory and scrolling with 60 photos in one group on the 2 GB stick.
6. ~~The v1 service survives a reboot~~ → no service in v2.
7. `setBlockNetworkLoads(true)` leaves the page working (else drop it).
8. A 12 MP phone photo is prepared on the stick in ≤ 15 s without memory trouble, and the
   page-drawn materials look like upstream's (compare one photo side by side).
9. A phone can reach the stick on port 8097 (no client isolation on the Wi-Fi).
10. `tools/stick-test.sh --staging` uploads a JPEG with a planted GPS tag from the build machine with `curl`: it is
    prepared, its materials drawn by the page, it appears in the group with no planted string in
    `wall.jpg`/`thumb.jpg`, and it is removed again.
11. HEIC: the stick's BitmapFactory/BitmapRegionDecoder decode an iPhone HEIC (else it is refused
    with the type message), and EXIF orientation is read from it.

### Results on the stick (2026-10-31)

Harness: Xiaomi TV Stick 4K (`192.168.0.55`, Android TV 11 user build, 2 GB, Gboard for TV, WebView
156.0.8078.47), the **staging** app (`home.cleanframe.tv.staging`) built from `device-fixes`, driven
from the build machine with adb, DevTools and curl. Uploads were sent from it (wired), not a phone.

| # | Result | Evidence |
|---|---|---|
| 1 | adb **cannot** read or write R | `Permission denied` on `/sdcard/Android/data/<pkg>/` once the app made it; stick-test now goes through the server and the page |
| 2 | PASS after a fix | IME comes up on OK (`mInputShown=true`), `input text` arrives; the first OK on Create after typing was dropped (held-key bug, fixed); Gboard's ✓ sends Enter to the field, one OK then creates the group |
| 3 | PASS after fixes | `tools/stick-test.sh --staging` 66/67 on `e06af73`: the one FAIL found a photo removed from the phone staying listed (fixed); the final clean commit is run with `--staging` and `--recovery` before promotion (its staging-ok marker records the pass); release and `--reboot` belong to the promotion |
| 4 | open | needs a real phone on the Wi-Fi |
| 5 | PASS | 60 x 12 MP photos into one group: 60/60 on the TV in 216 s (upload answer 1–5 s each); app PSS 144 MB → 290–340 MB while uploading and scrolling, MemAvailable never below 539 MB; scrolled to photo 60 and back; 0 renderer deaths, 0 ANRs, 0 kills |
| 7 | PASS, kept | with `setBlockNetworkLoads(true)`: 29 resources, all 200 from `cleanframe.local`, 0 broken images, all three fonts load (the two Study fonts on demand) |
| 8 | PASS | 12 MP (4000x3000) JPEG with EXIF orientation 6 and GPS: prepared (curl answer) 3.9 s, on the TV 8.5 s; app PSS 139 → 249 MB peak (+36 MB in Java's step, the rest while the page draws), WebView renderer 49 → 81 MB; orientation right (wall.jpg vs the upright crop 41.1 dB PSNR, vs unrotated 8.8 dB); materials vs desktop Chromium (Playwright headless shell, same wall.jpg, same `materials.js`): 47.3–49.2 dB PSNR, max pixel difference 6–12; no EXIF/GPS in wall/thumb |
| 9 | PASS from the build machine | port 8097 answers (200) while Add photo is open, refuses connections when it is closed; client isolation on the Wi-Fi still needs a phone (4) |
| 10 | PASS | in the stick-test (planted GPS string absent from wall.jpg, thumb.jpg and the server's thumbnail; delete through the API) |
| 11 | refused, cleanly | the stick's platform decoder failed every HEIC tried (12 MP, 2560x1920, 1920x1440; x265 8-bit 4:2:0, pillow-heif): "all codecs failed to extract frame"; the phone is now told "This TV cannot open this HEIC photo. Please send it as a JPEG." (415) after 7–15 s. A real iPhone HEIC (grid tiles) is untested; iOS Safari usually converts to JPEG on upload |

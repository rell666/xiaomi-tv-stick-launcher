# Project rules

Read these before changing anything here. Later code comments cite them by number.


**What this is.** An Android TV home screen, built for the Xiaomi TV Stick 4K (Android TV 11,
32-bit userspace). `web/` is CleanFrame's `app/`
(github.com/nthnclrk/CleanFrame, MIT) and `android/` wraps it in a full-screen WebView.

1. **`web/` is upstream plus one patch.** Never hand-edit `web/main.js` or
   `web/assets/icons/metrics.js`; change `tools/android-patch.py`, which applies every Android
   change by exact match and fails if upstream moved the code. `web/device.js` is ours outright
   (the Android replacement for CleanFrame's Tizen device layer). To take a new upstream: copy
   upstream `app/` over `web/` in its own commit, restore our additions (`web/device.js`,
   `web/LICENSES/`, `emby.svg`/`rumble.svg` and their `ADDITIONAL-SOURCES.json` entries, the
   `PHOTO-CREDIT.md` table), run `tools/android-patch.py web`, then set `UPSTREAM_SHA` in
   `tools/android-patch.py`, run `tools/android-patch.py --write-manifest <upstream-copy commit>`
   and update the upstream SHA in `README.md`. `tools/check.py` fails unless every file under
   `web/` on disk is pinned upstream (hash-checked against `tools/upstream-manifest.json`), the
   patched `main.js`/`metrics.js`/`index.html`, or listed with a reason in `OURS` in `tools/android-patch.py`;
   any other file, edit or deletion fails. A new file we own must be added to `OURS`.
   The mark library (`web/app-marks.js`, Change icon; README "Adding a mark") lists every white mark
   an app can be given: adding one is its SVG in `web/assets/icons/` (white, 24-unit viewBox, our own
   or with source and licence in `ADDITIONAL-SOURCES.json`), its `tools/icon_metrics.py` metrics in
   `METRICS` and its file in `OURS`, and one line in `app-marks.js`; `tests/app-edit.test.js` checks all
   three. Per-app names and icons (`web/app-edit.js`) only change how an app looks: never its id,
   launch target or place on Home, and never upstream's saved layout (`cleanframe.home.v1`).
   The "Space" wallpaper group is ours: `web/assets/wallpapers/space-*` and
   `web/assets/materials/space-*` (seven JPEGs per id, at the paths `main.js` builds from the id),
   `space-catalog.js` (appends its entries, each with `"group": "space"`, after upstream
   `catalog.js`), `SPACE-SOURCES.json` and `web/assets/SPACE-PHOTO-CREDIT.md`. They are listed in
   `OURS` through `SPACE_WALLPAPERS` in `tools/android-patch.py`. A Space image must be NASA
   public domain (no "courtesy of" or third-party copyright line) or ESA/Webb or ESA/Hubble CC BY 4.0,
   recorded with source page, original URL, credit, licence and download date; full-size originals
   stay out of the repo. Adding or removing one changes `SPACE_WALLPAPERS`, the catalogue, both
   credit files and the count in `tests/space-wallpapers.test.py` together.
   The "Cyberpunk" wallpaper group is ours in the same way: `web/assets/wallpapers/cyberpunk-*` and
   `web/assets/materials/cyberpunk-*` (seven JPEGs per id), `cyberpunk-catalog.js` (appends its
   entries, each with `"group": "cyberpunk"`, after `space-catalog.js`; `index.html` loads it through
   the patch), `CYBERPUNK-SOURCES.json` and `web/assets/CYBERPUNK-PHOTO-CREDIT.md`, listed in `OURS`
   through `CYBERPUNK_WALLPAPERS`. A Cyberpunk image must come from a **Wikimedia Commons** file page
   (Flickr imports hosted there count, once Commons' Flickr review has passed) under **CC0, public
   domain, CC BY 2.0/3.0/4.0 or CC BY-SA 2.0/3.0/4.0** — never NC, ND, non-free or unclear — with no
   personality-rights or trademark warning on the page and no face, person or brand logo as its
   subject. Record source page, original URL, size, sha256, the credit exactly as the page asks,
   licence and licence URL, evidence and download date; CC BY-SA derivatives stay under the same
   licence. Adding or removing one changes `CYBERPUNK_WALLPAPERS`, the catalogue, both credit files
   and the count in `tests/cyberpunk-wallpapers.test.py` together. The built-in groups (Scenery,
   Space, Cyberpunk) are also named, in order, in `web/wallpaper-groups.js` (`LABELS`, `ORDER`),
   `web/materials.js` (`GID`) and `UserLibrary.BUILTINS` (uploads to a built-in group); a new one
   changes all three and their tests together.
2. **Keep CleanFrame's own rules for `web/`:** ES5 JavaScript only (`tools/check.py` lexes every `.js` in
   `OURS` and the code `android-patch.py` injects, and fails on ES2015+ syntax), remote focus/navigation
   preserved, no live blur or pixel analysis on the animation path.
3. **Gate before every commit: `python3 tools/check.py`.** It also runs inside `build.sh`. It
   runs every `tests/*.test.{js,sh,py}` and `tests/java/` (through `fallback.test.sh`), found
   automatically, so there is no list here to go stale (a test or script in a subdirectory of `tests/`
   fails the gate, since it would never run, unless that directory has its own runner
   `tests/<dir>.test.*` or is `java/` or `fixtures/`). Tests going missing fails too: every entry of the two lists
   below must exist (a rollback records its SHA in `tools/rolled-back-to`, and that SHA's entries, plus any listed test added since, count instead of
   `APP_TESTS`), a test file with no code fails, and a test must end (last stdout line) with its end marker
   (`<NAME>_OK...`, `OK n/n` or `n/n passed`). Each test's header names the files it
   covers, and `tools/rollback.sh` sorts every top-level entry of `tests/` into `APP_TESTS` (they
   test `android/`, `web/` or the patch tool and are rolled back with them) or `HEAD_TESTS` (they
   test `build.sh`, `install.sh`, `tools/*.sh`, `tools/check.py` or `tools/icon_metrics.py`, which a
   rollback keeps). A new test must be added to exactly one of those lists: `tests/tools.test.sh`
   fails on an unclassified one, and `rollback.sh` refuses to run. A test that would cover both
   sides is split (as `gradle.test.py`, the metadata, and `build-verification.test.py`, `build.sh`'s
   refusals, were). The gate needs `node` (Node 22+, which `tools/devtools-eval.mjs` needs) and a JDK 17 (`$JAVA_HOME`, else
   `~/android-toolchain/jdk17`, else `javac`/`java` on `PATH`; none is a failure, not a skip).
   A change to a file a test covers needs a case in its test. Never end a pipeline with an early-exit consumer (`head`, `grep -q`/`-m`/`-l`)
   under `pipefail` — it kills the producer with SIGPIPE and the step fails at random (it made
   `rollback.sh` refuse ~20% of the time): use `first()` (`sed -n 1p`) or no pipe;
   `tests/tools.test.sh` enforces this. The adb helpers (`adbt`, `a`, `first`, `homepkg`, `top`, and `ui`, the
   uiautomator dump retried `UI_TRIES` times) live only in `tools/adb-lib.sh`, sourced by `install.sh` and
   `tools/stick-test.sh`; `tools.test.sh` fails on a copy, or a raw `uiautomator dump`, anywhere else. The test-recursion guard is a token file made by
   `check.py` (`CLEANFRAME_NESTED_GUARD`); an exported variable cannot skip tests. Tests are committed
   with the change they cover; a check that was only run by hand does not count as a test.
4. **Build only with `./build.sh` from a clean, committed tree.** The APK's `versionName`
   carries the git SHA; `./install.sh` refuses unless that SHA reads back off the stick.
   `--dirty` builds are for testing on the stick only and are never left as Home: `install.sh`
   refuses any release APK whose SHA is not a clean commit, whatever the flags. `build.sh`
   refuses outside a full git clone (shallow, tarball, `git archive`): versionCode is the commit
   count and the gate's tests read old commits. `CLEANFRAME_VERSION_CODE` is only for a Gradle-only
   build outside git, never through `build.sh` (README). It records the APK's sha256 in
   `dist/<apk>.sha256` and refuses an APK not signed by the key pinned in `tools/signer-cert.sha256`.
   **Gradle dependencies are SHA-256-verified** by `android/gradle/verification-metadata.xml`
   (`tests/gradle.test.py` checks it offline). When a plugin or dependency version changes,
   regenerate it with `./gradlew --no-daemon --refresh-dependencies --write-verification-metadata
   sha256 assembleRelease assembleStaging` in `android/` (both build types; command in README) and
   **review the metadata diff** before committing: only the intended components change. Never
   trust whole groups, add `<trusted-artifacts>`, turn off `verify-metadata`, set
   `org.gradle.dependency.verification` or add a Gradle init script to get a build through (`build.sh`
   refuses the last two, and runs Gradle with `--dependency-verification strict`); ask the repository's owner instead.
   `build.sh` refuses a tree without the metadata file (`tools/rollback.sh` keeps HEAD's for a SHA from
   before cdd234f; if that SHA needs an artifact HEAD's does not pin, regenerate and review, as above).
   A **release** build or install (not `--staging`) must be a commit on `origin/main`: push, then build;
   `build.sh` and `install.sh` refuse an unpushed or feature-branch commit (staging takes any commit).
5. **The signing key lives outside the repo** in `~/.config/cleanframe-tv/` (mode 600). Never
   commit it, never print it. Losing it means the next build cannot update the installed app in
   place: it must be uninstalled first, which wipes the saved layout. It is backed up **only on
   request** with `tools/backup-signing-key.sh`, which keeps the newest 3 in the directory it is given
   (README). It is deliberately never on a schedule. The backup archive is **not encrypted** (keystore
   and passwords together), so keep it somewhere private.
6. **Stage before Home.** Every change is tested as the staging app first:
   `./build.sh --staging` → `./install.sh dist/cleanframe-tv-<sha>-staging.apk` →
   `tools/stick-test.sh --staging`. The staging app (`home.cleanframe.tv.staging`, "CleanFrame
   (staging)") has no HOME filter, so installing it never changes Home or brings up Android's
   Home chooser; never make it Home. A full pass of a clean commit writes
   `dist/cleanframe-tv-<sha>.<stick>.staging-ok`, and `install.sh` refuses the release APK for `<sha>`
   without the marker for the stick it installs on. Markers are **per stick** (`STICK=` picks one; README
   "Two sticks"): `<stick>` is the identity read off the device (`stickid` in `tools/adb-lib.sh`: model and
   serial, never the address), also recorded as `stick_id=`; a pass on one stick never promotes on another,
   and a marker without it, or a stick whose identity cannot be read, is refused.
   The marker records the tested APK's sha256, read off the stick, which must equal the
   one `build.sh` recorded (`dist/<apk>.sha256`); `install.sh` also refuses a release APK whose own
   sha256 differs from its recorded one, and any APK not signed by the pinned key
   (`tools/signer-cert.sha256`). `--skip-staging` is for emergencies only (Home broken, no time to
   stage), bypasses the staging and recovery gates (not the sha256 or signer checks) and must
   be recorded in the commit log. A staging run deletes that SHA's old marker before testing, and
   every install (staging or release, with or without `--make-home`) and every stick test fails if
   HOME stops resolving to `home.cleanframe.tv`. Then `./install.sh dist/cleanframe-tv-<sha>.apk --make-home`,
   `tools/stick-test.sh` must end `STICK_TEST_OK` (it exits non-zero on any FAIL), and a reboot
   must come back to CleanFrame: `tools/stick-test.sh --reboot`. When a change touches
   `android/` (all of it is the native safety net, not just `MainActivity.java`) or `web/device.js`,
   also run `tools/stick-test.sh --recovery` on the staging app before promoting it (`--slow` adds the
   60 s Home retry). `install.sh` enforces it: a full clean `--recovery` pass writes
   `dist/cleanframe-tv-<sha>.<stick>.recovery-ok` (same tested APK sha256 and stick as the staging marker), and a release
   install is refused without it when `git diff --name-only <base>..<sha>` touches `android/` or
   `web/device.js`, `<base>` being the SHA installed as Home as read off the stick (unreadable, or not
   a commit here: required). Every `--recovery` run deletes that SHA's recovery marker for that stick
   first; a recovery FAIL or abort also deletes its staging marker for that stick.
   **No other launcher is assumed installed.** If
   the page cannot run (no WebView, it never draws within 60 s on screen, its process crashes or stops
   responding (~15 s, Android 10+) 3
   times in 10 minutes, is killed for memory 5 times in 10 minutes while on screen, or 3 starts in
   a row died before it had lasted 30 s on screen, or Home was left for another app with it drawn) the app shows its own native list: retry, TV settings and every
   app; Home retries after 60 s. Memory kills while another app is in front are rebuilt without
   limit when Home returns. Holding Back opens the same list by hand. A build
   that crashes natively on every start still ends at Android's `FallbackHome`.
   **Roll back by rolling forward:** `tools/rollback.sh <good-sha>` commits that SHA's
   `android/`, `web/`, `tools/android-patch.py` with its `tools/upstream-manifest.json` (the pair
   always comes from one commit; the manifest is removed if that commit has none) and its app
   tests (`APP_TESTS`, rule 3), and keeps HEAD's tool tests (`HEAD_TESTS`) even when that SHA
   predates them; it refuses if any test at HEAD is unclassified. `android/gradle/verification-metadata.xml`
   comes from that SHA, or from HEAD when the SHA has none (rule 4). It is gated by both: that SHA's
   app tests on its code, HEAD's tool tests on the tools; it refuses a SHA whose
   `build.gradle` hard-codes `versionCode` (116d096, 17a2d96). Then push, stage and install as usual (a SHA
   from before the staging build needs `--skip-staging`; the script says so). An APK from `dist/` built from an
   older commit cannot be reinstalled: the stick refuses an older `versionCode` (the commit count;
   user build, app not debuggable), and `install.sh` refuses it first. Uninstalling to get round
   that wipes the saved layout — last resort only.
   **Stick setup is part of a release install** (`tools/stick-setup.sh`, README "Stick setup"): privacy settings
   every time, the app questions once. It runs before Home is set, and a failure still ends through the Home checks.
   A staging install never runs it. It never offers `PROTECTED`, never removes a system app, changes no app before
   the typed `yes`, and records every change for `--undo`; `tests/stick-setup.test.sh` holds it to that.
7. **Scored reviews follow `docs/REVIEW.md`** (rubric, evidence rules, reviewer prompt). A score
   is quoted with its rubric version and commit; scores from before rubric v1 are each
   reviewer's own judgement and are not comparable.
8. **The stick trusts one machine's ADB key.** A new key needs OK pressed on the TV.
9. **Photo uploads go straight to the stick** (docs/PHOTO-GROUPS.md, contract 4 v2): the app's own `UploadServer` listens on the stick's LAN IPv4, port 8097, only while
   the Add photo screen is open. The app holds INTERNET for that socket only:
   `tests/android.test.py` fails on any outgoing-connection API under `android/` (Bluetooth included:
   no `BluetoothSocket`, `createRfcomm*`/`listenUsingRfcomm*`, L2CAP, `startDiscovery` or scan, GATT,
   `createBond` or `connect()`; Settings > Bluetooth reads bonded devices and their links, through
   `BLUETOOTH`/`BLUETOOTH_CONNECT`, the latter granted over adb with `pm grant`, never asked for on
   Home; pairing is TV Settings' own page. Its one change is unpairing headphones and speakers:
   `BluetoothDevice.removeBond`, by the one reflective lookup in `MainActivity.removeBondMethod()`, with
   `BLUETOOTH_ADMIN` (maxSdkVersion 30) or `BLUETOOTH_CONNECT`. The native side refuses everything else
   (`BluetoothReport.unpairable`, a positive allowlist on what the device is: classic or dual mode, an audio
   device class, UUIDs known with an audio profile and neither HID nor HOGP, not a peripheral, no input link;
   being connected as audio qualifies nothing): the stick trusts adb from one host and pairing a remote
   needs the remote, so unpairing the remote would strand it. Never loosen that, and never let
   `tools/stick-test.sh` unpair anything), and the WebView's
   origin lock and `setBlockNetworkLoads(true)` stay. Its plain-Java classes are tested by
   `tests/upload.test.sh` (APP_TESTS, with `tests/upload/`), which must stay fast (~2 s; its cases
   run in parallel): `tests/tools.test.sh` runs every test again inside each rollback clone. No
   token or code is ever logged. The v1 service (`tools/photo-upload/`) is removed; upstream's `build-materials.mjs` is kept byte-exact as
   `docs/reference/build-materials.mjs` for the page's port (THIRD_PARTY_NOTICES.md); never edit it.
10. **Uninstall only ever opens Android's own confirm dialog** (long-press menu):
   `ACTION_DELETE` from `Bridge.uninstall`, with `REQUEST_DELETE_PACKAGES`. `UninstallPolicy` refuses system
   apps, this app, HOME providers and what the TV needs (its `REFUSED` list), natively, on every call. Never
   loosen that, and never add a silent API: `tests/android.test.py` fails on `PackageInstaller`,
   `deletePackage` and the like.

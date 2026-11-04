#!/bin/bash
# Build the signed release APK from a committed, clean tree (G9: the APK's versionName carries
# the git SHA, and install.sh reads it back off the stick).
# Usage: ./build.sh [--staging] [--dirty]
#   --staging  build home.cleanframe.tv.staging ("CleanFrame (staging)", same key, NOT a Home app)
#              to dist/cleanframe-tv-<sha>-staging.apk; test it with tools/stick-test.sh --staging
#   --dirty    allow an uncommitted tree (stick testing only; never left as Home)
set -euo pipefail
cd "$(dirname "$0")"
STAGING=0; DIRTY=0
for arg in "$@"; do
  case $arg in --staging) STAGING=1 ;; --dirty) DIRTY=1 ;; *) echo "usage: $0 [--staging] [--dirty]" >&2; exit 2 ;; esac
done
# Gradle dependency verification (android/gradle/verification-metadata.xml, README) must not be
# weakened from outside the file: org.gradle.dependency.verification=lenient|off in a
# gradle.properties (as a key, or anywhere in a value or continuation line, e.g.
# org.gradle.jvmargs=-Dorg.gradle.dependency.verification=lenient; only # and ! comment lines are
# ignored), or as -D in GRADLE_OPTS/JAVA_OPTS/JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS (the JVM reads
# the last three itself), would let a changed artifact through with a warning, and a Gradle init script
# (init.gradle[.kts] or init.d/ in the Gradle user home or a wrapper distribution) can change any
# setting. Any such setting, and any init script at all, is refused; Gradle is also run with
# --dependency-verification strict. tests/build-verification.test.py covers this. Gradle reads
# gradle.properties from the Gradle user home, the project and its installation directory (the wrapper's
# unpacked distribution, wrapper/dists/<dist>/<hash>/gradle-<v>/gradle.properties): all three are scanned.
GUH=${GRADLE_USER_HOME:-$HOME/.gradle}
for f in "$GUH/gradle.properties" android/gradle.properties "$GUH"/wrapper/dists/*/*/gradle-*/gradle.properties; do
  if [ -f "$f" ] && grep -qE '^[[:space:]]*([^#![:space:]].*)?org\.gradle\.dependency\.verification' "$f"; then
    echo "refusing: $f mentions org.gradle.dependency.verification (as a key or in a value such as org.gradle.jvmargs); dependency verification must stay strict (remove it)" >&2; exit 1
  fi
done
case "${GRADLE_OPTS:-} ${JAVA_OPTS:-} ${JAVA_TOOL_OPTIONS:-} ${_JAVA_OPTIONS:-} ${JDK_JAVA_OPTIONS:-}" in *org.gradle.dependency.verification*)
  echo "refusing: GRADLE_OPTS/JAVA_OPTS/JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS set org.gradle.dependency.verification; dependency verification must stay strict" >&2; exit 1 ;;
esac
# (Gradle runs only *.gradle and *.gradle.kts from an init.d/; every distribution ships an
# init.d/readme.txt, which is not one.)
for f in "$GUH/init.gradle" "$GUH/init.gradle.kts" "$GUH"/init.d/*.gradle "$GUH"/init.d/*.gradle.kts \
         "$GUH"/wrapper/dists/*/*/*/init.d/*.gradle "$GUH"/wrapper/dists/*/*/*/init.d/*.gradle.kts; do
  [ ! -e "$f" ] || { echo "refusing: Gradle init script $f would run in this build and can weaken dependency verification (move it away)" >&2; exit 1; }
done
# No metadata file, no build (S5: a gate that cannot verify refuses). Without it Gradle verifies nothing
# and the signed APK would carry unverified dependencies. A tree from before cdd234f (2026-10-31, which
# added it) gets HEAD's from tools/rollback.sh; no dependency or plugin version has changed since
# 116d096, so it covers every older SHA, and --dependency-verification strict fails the build if one
# ever needs an artifact it does not pin (then regenerate and review it, README; never weaken it).
if [ ! -f android/gradle/verification-metadata.xml ]; then
  echo "refusing: no android/gradle/verification-metadata.xml: Gradle dependencies would go unverified into the APK." >&2
  echo "An older SHA is rolled back with tools/rollback.sh, which keeps HEAD's metadata when that SHA has none;" >&2
  echo "otherwise regenerate it and review the diff (README, \"Build and install\"). Never build without it." >&2
  exit 1
fi
# Only a full git clone builds: versionCode is the commit count, versionName carries the SHA
# install.sh reads back off the stick, and tools/check.py's tests read old commits (patch.test.py
# needs the upstream copy, 065aa2b). A shallow clone, a tarball or a `git archive` is refused here,
# before anything runs. CLEANFRAME_VERSION_CODE/_NAME (android/app/build.gradle) are for a
# Gradle-only build outside git (./gradlew in android/), never through build.sh: in a full clone a
# leftover export (or Gradle property) would silently replace the commit count and the SHA, i.e.
# the provenance install.sh reads back. Refuse rather than unset: a stale override is a mistake
# the person should see, not one to paper over.
if [ "$(git rev-parse --show-toplevel 2>/dev/null || true)" != "$(pwd -P)" ] \
   || [ "$(git rev-parse --is-shallow-repository 2>/dev/null || true)" != false ]; then
  echo "refusing: $(pwd -P) is not a full git clone (shallow clone, tarball or git archive?): a build needs" >&2
  echo "the whole history (versionCode = commit count; tools/check.py reads old commits). Clone it in full or" >&2
  echo "run git fetch --unshallow. CLEANFRAME_VERSION_CODE is only for ./gradlew in android/, not build.sh." >&2
  exit 1
fi
for v in CLEANFRAME_VERSION_CODE CLEANFRAME_VERSION_NAME \
         ORG_GRADLE_PROJECT_CLEANFRAME_VERSION_CODE ORG_GRADLE_PROJECT_CLEANFRAME_VERSION_NAME; do
  [ -z "${!v:-}" ] || { echo "refusing: $v is set in a full git checkout; it would override the commit's provenance (unset it)" >&2; exit 1; }
done
case "${GRADLE_OPTS:-} ${JAVA_OPTS:-} ${JAVA_TOOL_OPTIONS:-} ${_JAVA_OPTIONS:-} ${JDK_JAVA_OPTIONS:-}" in *CLEANFRAME_VERSION_*)
  echo "refusing: GRADLE_OPTS/JAVA_OPTS/JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS set CLEANFRAME_VERSION_*; it would override the commit's provenance" >&2; exit 1 ;;
esac
# All three places Gradle reads gradle.properties from (as for the verification setting above), and anywhere in
# a line that is no comment: -Dorg.gradle.project.CLEANFRAME_VERSION_CODE=1 in org.gradle.jvmargs sets it too.
for f in "$GUH/gradle.properties" android/gradle.properties "$GUH"/wrapper/dists/*/*/gradle-*/gradle.properties; do
  if [ -f "$f" ] && grep -qE '^[[:space:]]*([^#![:space:]].*)?CLEANFRAME_VERSION_(CODE|NAME)' "$f"; then
    echo "refusing: $f sets CLEANFRAME_VERSION_*; it would override the commit's provenance" >&2; exit 1
  fi
done
T=${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}
export JAVA_HOME=$T/jdk17 ANDROID_HOME=$T/sdk
BT=34.0.0   # the build-tools build.sh runs itself (apksigner, aapt2); Gradle's own is read off the SDK below
# android/local.properties is ignored by git and Android's Gradle plugin prefers its sdk.dir to $ANDROID_HOME, so an
# old one could build with another SDK while the provenance below reads $ANDROID_HOME's. Absent, or naming
# that same SDK, is fine; anything else is refused. It is read as java.util.Properties reads it:
# =, : or whitespace after the key, comment lines, continuation lines, backslash escapes, the last entry wins; a
# relative path is under android/. What cannot be read with confidence (non-ASCII bytes, a bad \\u escape, an empty
# value, a file that cannot be opened) is refused too.
if [ -e android/local.properties ] || [ -L android/local.properties ]; then
  python3 -I - android/local.properties "$ANDROID_HOME" <<'PROPS' || exit 1
import os, re, sys
path, sdk = sys.argv[1:3]
def stop(why): sys.exit('refusing: android/local.properties %s (remove the file or the line)' % why)
try:
    data = open(path, 'rb').read().decode('ascii')
except (OSError, UnicodeDecodeError) as e:
    stop('cannot be read with confidence (%s)' % e)
def unescape(s):
    def one(m):
        c = m.group(1)
        if c[0] == 'u':
            if not re.fullmatch('u[0-9a-fA-F]{4}', c): stop('has a bad \\\\u escape')
            return chr(int(c[1:], 16))
        return {'t': '\t', 'n': '\n', 'r': '\r', 'f': '\f'}.get(c, c)
    return re.sub(r'\\(u.{0,4}|.)', one, s, flags=re.S)
value, lines, i = None, re.split(r'\r\n|\r|\n', data), 0
while i < len(lines):
    line = lines[i].lstrip(' \t\f'); i += 1
    if not line or line[0] in '#!': continue
    while len(line) - len(line.rstrip('\\')) & 1:   # an odd number of backslashes continues the line
        line = line[:-1] + (lines[i].lstrip(' \t\f') if i < len(lines) else ''); i += 1
    m = re.match(r'((?:\\.|[^\\=: \t\f])*)[ \t\f]*[=:]?[ \t\f]*(.*)', line, re.S)
    if unescape(m.group(1)) == 'sdk.dir': value = unescape(m.group(2))
if value is None: sys.exit(0)
if not value: stop('has an empty sdk.dir')
if os.path.realpath(os.path.join('android', value)) != os.path.realpath(sdk):
    stop("has sdk.dir=%s, but this build's provenance reads %s" % (value, sdk))
PROPS
fi
if [ -n "$(git status --porcelain)" ] && [ $DIRTY = 0 ]; then
  echo "refusing: working tree is dirty (commit first, or pass --dirty for a test build)" >&2; exit 1
fi
# A release APK (not --staging; a clean tree, so its name carries no -dirty) is only built from a commit
# that is already on origin/main (pushed: README's release order is push, then build). A feature branch
# or an unpushed commit can still be built and tested as the staging app. install.sh checks the same.
if [ $STAGING = 0 ] && [ -z "$(git status --porcelain)" ]; then
  if ! git rev-parse -q --verify 'refs/remotes/origin/main^{commit}' >/dev/null; then
    echo "refusing: no origin/main here, so this release build cannot be shown to be pushed (git fetch origin, or build --staging)" >&2; exit 1
  fi
  if ! git merge-base --is-ancestor HEAD refs/remotes/origin/main; then
    echo "refusing: HEAD $(git rev-parse --short=12 HEAD) is not on origin/main (unpushed, or another branch): a release is built" >&2
    echo "only from main's pushed history. Merge it to main and git push (then build), or ./build.sh --staging to test it." >&2
    exit 1
  fi
fi
test -f "${CLEANFRAME_SIGNING:-$HOME/.config/cleanframe-tv/signing.properties}" || { echo "refusing: no signing.properties (see README)" >&2; exit 1; }
python3 tools/check.py
if [ $STAGING = 1 ]; then TASK=assembleStaging; OUT=staging/app-staging.apk; SUFFIX=-staging
else TASK=assembleRelease; OUT=release/app-release.apk; SUFFIX=; fi
(cd android && ./gradlew --no-daemon -q --dependency-verification strict "$TASK")
SHA=$(git rev-parse --short=12 HEAD); [ -n "$(git status --porcelain)" ] && SHA=$SHA-dirty
DEST=dist/cleanframe-tv-$SHA$SUFFIX.apk
# A previous build's sidecars for this name go first, so a refusal below can never leave an older
# .sha256 or .provenance beside a missing (or newer) APK; every refusal removes all three. The two
# sidecars are written as <name>.part and moved into place together at the very end, .sha256 last
# (install.sh reads it): a build killed part-way leaves an APK with both sidecars or with neither,
# never a .sha256 without its .provenance.
drop_build() { rm -f "$DEST" "$DEST.sha256" "$DEST.provenance" "$DEST.sha256.part" "$DEST.provenance.part"; }
rm -f "$DEST.sha256" "$DEST.provenance" "$DEST.sha256.part" "$DEST.provenance.part"
mkdir -p dist && cp "android/app/build/outputs/apk/$OUT" "$DEST"
# The signer must be the pinned release key (tools/signer-cert.sha256, the certificate's SHA-256 as
# apksigner prints it), and the APK's own sha256 is recorded beside it: install.sh refuses a release
# APK that differs from it, and the stick test records the tested APK's in its markers. The build's
# provenance (toolchain versions) goes beside it in <apk>.provenance (below).
CERTS=$("$ANDROID_HOME/build-tools/$BT/apksigner" verify --print-certs "$DEST" 2>&1) || {
  drop_build; echo "refusing: apksigner verify failed for $DEST: ${CERTS//$'\n'/ }; removed it" >&2; exit 1; }
GOTCERT=$(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' <<<"$CERTS")
WANTCERT=$(tr -d '[:space:]' < tools/signer-cert.sha256)
if ! [[ $WANTCERT =~ ^[0-9a-f]{64}$ ]] || [ "$GOTCERT" != "$WANTCERT" ]; then
  drop_build
  echo "refusing: $DEST is signed by '${GOTCERT//$'\n'/ }', not the pinned key in tools/signer-cert.sha256 ('$WANTCERT'); removed it" >&2; exit 1
fi
(cd dist && sha256sum "${DEST#dist/}") > "$DEST.sha256.part"
# Provenance (S4, README "Build"): what made this APK, in dist/<apk>.provenance beside its sha256, as
# key=value lines: the commit; versionName and versionCode read off the APK itself (aapt2); its
# sha256; and the toolchain: the JDK Gradle ran on ($JAVA_HOME's java -version, every line), the build-tools
# Gradle used (the only one installed: Gradle cannot use one that is not, so more than one is refused
# unless android/app/build.gradle pins buildToolsVersion), the SDK platform's revision (android.jar of
# compileSdk, its source.properties), and Gradle (the wrapper's pinned distribution and its distributionSha256Sum). Nothing
# gates on it (install.sh and the stick test read only the .apk and .sha256); it answers "what built
# this?" later. A field that cannot be read refuses the build and removes the APK, its sha256 and any
# provenance; so does a versionName that is not 0.1.0+<this build's SHA> (the provenance install.sh
# reads back off the stick would not be this commit's), or a versionCode that is not this commit's count
# (android/app/build.gradle; the stick compares it to refuse a downgrade).
WRAPPER=android/gradle/wrapper/gradle-wrapper.properties
BADGE=$("$ANDROID_HOME/build-tools/$BT/aapt2" dump badging "$DEST" 2>/dev/null || true)
P_VN=$(sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" <<<"$BADGE")
P_VC=$(sed -n "s/^package: .* versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGE")
P_JDK=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -e '/^[[:space:]]*$/d' | paste -sd'|' - | sed 's/|/ | /g' || true)
APPGRADLE=android/app/build.gradle
props_rev() { sed -n 's/^Pkg\.Revision=\([0-9][0-9.]*\)[[:space:]]*$/\1/p' "$1/source.properties" 2>/dev/null || true; }
P_BT=; BTPIN=$(sed -n "s/^[[:space:]]*buildToolsVersion[[:space:]]*[=(]*[[:space:]]*['\"]\([0-9.]*\)['\"].*/\1/p" "$APPGRADLE" 2>/dev/null || true)
BTDIRS=("$ANDROID_HOME"/build-tools/*/)
if [ -n "$BTPIN" ]; then P_BT=$(props_rev "$ANDROID_HOME/build-tools/$BTPIN")
elif [ ${#BTDIRS[@]} = 1 ] && [ -d "${BTDIRS[0]}" ]; then P_BT=$(props_rev "${BTDIRS[0]%/}")
fi
P_COMPILESDK=$(sed -n 's/^[[:space:]]*compileSdk[[:space:]]*=*[[:space:]]*\([0-9][0-9]*\)[[:space:]]*$/\1/p' "$APPGRADLE" 2>/dev/null || true)
P_PLATFORM=; [ -z "$P_COMPILESDK" ] || P_PLATFORM=$(props_rev "$ANDROID_HOME/platforms/android-$P_COMPILESDK")
[ -z "$P_PLATFORM" ] || P_PLATFORM="android-$P_COMPILESDK r$P_PLATFORM"
P_GRADLE=$(sed -n 's/^distributionUrl=.*gradle-\([0-9][0-9.]*\)-\(bin\|all\)\.zip[[:space:]]*$/\1/p' "$WRAPPER" 2>/dev/null || true)
P_GSUM=$(sed -n 's/^distributionSha256Sum=\([0-9a-f]\{64\}\)[[:space:]]*$/\1/p' "$WRAPPER" 2>/dev/null || true)
P_SUM=$(sed -n 's/^\([0-9a-f]\{64\}\)[[:space:]].*/\1/p' "$DEST.sha256.part")
for f in P_VN P_VC P_JDK P_BT P_PLATFORM P_GRADLE P_GSUM P_SUM; do
  if [ -z "${!f}" ]; then
    drop_build
    echo "refusing: could not read ${f#P_} for $DEST.provenance (aapt2 badging, \$JAVA_HOME/bin/java -version, the SDK's build-tools/ (one installed, or buildToolsVersion in $APPGRADLE) and platforms/android-<compileSdk>/source.properties, $WRAPPER); removed the APK" >&2; exit 1
  fi
done
if [ "$P_VN" != "0.1.0+$SHA" ]; then
  drop_build
  echo "refusing: $DEST has versionName '$P_VN', not 0.1.0+$SHA (android/app/build.gradle); removed the APK" >&2; exit 1
fi
WANT_VC=$(git rev-list --count HEAD)
if [ "$P_VC" != "$WANT_VC" ]; then
  drop_build
  echo "refusing: $DEST has versionCode '$P_VC', not $WANT_VC, this commit's count (android/app/build.gradle); removed the APK" >&2; exit 1
fi
printf 'apk=%s\nsha=%s\nversionName=%s\nversionCode=%s\napk_sha256=%s\njdk=%s\nbuild_tools=%s\nplatform=%s\ngradle=%s\ngradle_sha256=%s\nbuilt=%s\n' \
  "${DEST#dist/}" "$SHA" "$P_VN" "$P_VC" "$P_SUM" "$P_JDK" "$P_BT" "$P_PLATFORM" "$P_GRADLE" "$P_GSUM" "$(date -Is)" > "$DEST.provenance.part"
mv -f "$DEST.provenance.part" "$DEST.provenance" && mv -f "$DEST.sha256.part" "$DEST.sha256"
cat "$DEST.sha256"
echo "provenance: $DEST.provenance (JDK, build-tools $P_BT, platform $P_PLATFORM, Gradle $P_GRADLE)"
echo "BUILD_OK $DEST"

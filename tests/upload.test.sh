#!/bin/bash
# Offline tests for the upload side of the app (docs/PHOTO-GROUPS.md, contract 4, v2: phones send
# photos straight to the stick): the plain-Java classes UploadServer (HTTP server, multipart parser,
# code rate limit), PendingPhotos (unfinished photos, materialJobs/saveMaterial), PhotoMath and Jpeg
# (crop, inSampleSize, EXIF orientation, metadata check), Readability (v1's luminance rules, against
# v1's vectors in tests/upload/data/) and PhotoProcessor, with UserLibrary, compiled with plain javac
# together with tests/upload/*.java and run in ONE JVM (tests/upload/UploadTests.java runs the cases in
# parallel: tests/tools.test.sh runs it again inside each of its rollback clones). The server
# runs end to end over a real loopback socket (127.0.0.1/127.0.0.2, random ports), never on the LAN.
# Reads tests/fixtures/user-library/R, tests/upload/fixture/R, android/app/src/main/assets/upload/
# and web/assets (upstream material sizes).
# JDK: $JAVA_HOME, then ~/android-toolchain/jdk17, then javac/java on PATH; none is a FAILURE, not a
# skip. Classes and temp dirs go under $TMPDIR (else ~/scratch), removed on exit.
# Run from anywhere:  bash tests/upload.test.sh   -> UPLOAD_TEST_OK, or exit 1
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=android/app/src/main/java/home/cleanframe/tv
CLASSES=("$SRC/UserLibrary.java" "$SRC/Readability.java" "$SRC/PhotoMath.java" "$SRC/Jpeg.java" "$SRC/PhotoProcessor.java"
         "$SRC/PendingPhotos.java" "$SRC/UploadServer.java")   # plain Java only: android.* is not on this classpath

JAVAC=''; JAVA=''
for home in "${JAVA_HOME:-}" "$HOME/android-toolchain/jdk17"; do
  if [ -n "$home" ] && [ -x "$home/bin/javac" ] && [ -x "$home/bin/java" ]; then
    JAVAC=$home/bin/javac JAVA=$home/bin/java; break
  fi
done
if [ -z "$JAVAC" ]; then JAVAC=$(command -v javac || true); JAVA=$(command -v java || true); fi
if [ -z "$JAVAC" ] || [ -z "$JAVA" ]; then
  echo "FAIL upload test: no JDK found (\$JAVA_HOME, ~/android-toolchain/jdk17, javac+java on PATH); not skipping, the gate fails closed"
  exit 1
fi

TMPROOT=${TMPDIR:-$HOME/scratch}
mkdir -p "$TMPROOT"
OUT=$(mktemp -d "$TMPROOT/cf-upload-test.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
FAST=(-J-XX:TieredStopAtLevel=1 -J-XX:+UseSerialGC)
"$JAVAC" "${FAST[@]}" -encoding UTF-8 -nowarn -d "$OUT/classes" "${CLASSES[@]}" tests/upload/*.java
mkdir -p "$OUT/tmp"
"$JAVA" -Djava.io.tmpdir="$OUT/tmp" -Djava.awt.headless=true -cp "$OUT/classes" home.cleanframe.tv.UploadTests
echo "UPLOAD_TEST_OK"

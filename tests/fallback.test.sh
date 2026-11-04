#!/bin/bash
# Behavioural tests for MainActivity's fallback/recovery decisions, offline: compiles the plain-Java
# classes it delegates to (FallbackPolicy: crash/memory-kill windows, start guard, ready deadline,
# hang -> terminate -> fallback, Home retry, Try again; IconCache: byte-bounded LRU invalidated by
# app updates; UserLibrary: the photo library of docs/PHOTO-GROUPS.md, against tests/fixtures/user-library; ScreenPolicy: Settings > Screen and the sleep timer; UploadGate: the Add photo screen's start and stop, the UI thread never waiting on the library; BluetoothReport: the bridge's bluetooth() answer and which devices may be unpaired; BluetoothBonds: the page's device tokens and the unpair decision; ProfileProxies: the Bluetooth profile proxies' ownership, closed in onDestroy or at once when late; UninstallPolicy: which apps the long-press menu may offer to uninstall) together with tests/java/*.java using plain javac/java (no Gradle, no JUnit, nothing
# downloaded), and runs every tests/java/*Test.java main (FallbackPolicy with a fake clock and store).
# JDK: $JAVA_HOME, then ~/android-toolchain/jdk17 (build.sh's), then javac/java on PATH. None found
# is a FAILURE, not a skip: a gate that cannot run its test fails closed.
# Compiled classes, and the tests' temp dirs (java.io.tmpdir), go to a temp dir under $TMPDIR (else
# ~/scratch), removed on exit.
# Run from anywhere:  bash tests/fallback.test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=android/app/src/main/java/home/cleanframe/tv
CLASSES=("$SRC/FallbackPolicy.java" "$SRC/IconCache.java" "$SRC/UserLibrary.java" "$SRC/PhotoMath.java" "$SRC/Readability.java" "$SRC/PhotoProcessor.java" "$SRC/ScreenPolicy.java" "$SRC/UploadGate.java" "$SRC/BluetoothReport.java" "$SRC/BluetoothBonds.java" "$SRC/ProfileProxies.java" "$SRC/UninstallPolicy.java")   # plain Java only: android.* is not on this classpath

JAVAC=''; JAVA=''
for home in "${JAVA_HOME:-}" "$HOME/android-toolchain/jdk17"; do
  if [ -n "$home" ] && [ -x "$home/bin/javac" ] && [ -x "$home/bin/java" ]; then
    JAVAC=$home/bin/javac JAVA=$home/bin/java; break
  fi
done
if [ -z "$JAVAC" ]; then JAVAC=$(command -v javac || true); JAVA=$(command -v java || true); fi
if [ -z "$JAVAC" ] || [ -z "$JAVA" ]; then
  echo "FAIL fallback test: no JDK found (\$JAVA_HOME, ~/android-toolchain/jdk17, javac+java on PATH); not skipping, the gate fails closed"
  exit 1
fi

TMPROOT=${TMPDIR:-$HOME/scratch}
mkdir -p "$TMPROOT"
OUT=$(mktemp -d "$TMPROOT/cf-fallback-test.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVAC" -encoding UTF-8 -nowarn -d "$OUT" "${CLASSES[@]}" tests/java/*.java
RAN=0
for t in tests/java/*Test.java; do
  name=$(basename "$t" .java)
  echo "== $name"
  "$JAVA" -Djava.io.tmpdir="$OUT" -cp "$OUT" "home.cleanframe.tv.$name"
  RAN=$((RAN + 1))
done
[ $RAN -gt 0 ] || { echo "FAIL fallback test: no tests/java/*Test.java ran"; exit 1; }
echo "FALLBACK_TEST_OK ($RAN test classes)"

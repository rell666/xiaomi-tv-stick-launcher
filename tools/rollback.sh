#!/bin/bash
# Roll the app back to an earlier commit by rolling FORWARD: a new commit restores that commit's
# android/, web/, tools/android-patch.py, tools/upstream-manifest.json and the tests of that code.
# tests/ is split by what each entry tests, in two explicit lists below: APP_TESTS test the code a
# rollback restores (android/, web/, the patch tool and its manifest) and come from the SHA (absent
# there = removed); HEAD_TESTS test what a rollback does NOT restore (build.sh, install.sh, tools/*.sh,
# tools/check.py, tools/icon_metrics.py), so HEAD's copies are kept, and a rollback to an SHA from
# before them still runs them. Fail-closed: every top-level entry of HEAD's tests/ must be in
# exactly one list or nothing is changed (tests/tools.test.sh checks the working tree the same way,
# so a new test cannot be committed unclassified). An entry only the SHA has (a test since removed)
# comes with the SHA's tests. An older APK from dist/ cannot be installed over a newer one:
# versionCode is the commit count, and this user-build stick refuses a downgrade of a non-debuggable
# app (INSTALL_FAILED_VERSION_DOWNGRADE). Uninstalling first wipes the saved layout.
# Usage: tools/rollback.sh <good-sha>    then stage and install it like any other commit (README)
#
# tools/android-patch.py is restored with web/ because it holds the patch pairs and the upstream pin
# (UPSTREAM_SHA) that describe that SHA's web/. Since 22e4894 the upstream file hashes live beside
# it in tools/upstream-manifest.json, which android-patch.py refuses unless its upstream_sha equals
# UPSTREAM_SHA, so the manifest always travels WITH the patch file: taken from the same commit as
# the patch file, or removed when that commit has none (older patch files carry their hashes
# inline and never read it). The CURRENT tools/check.py then gates the result. A SHA from before
# the --verify gate (344579e) has a patch file without verify(), which check.py cannot use: then the
# earliest later android-patch.py that has verify() AND passes check.py on that SHA's web/ is used
# (it provably describes exactly that web/), with that commit's manifest. If none does, nothing is
# committed: roll back by hand.
# A SHA whose android/app/build.gradle hard-codes versionCode (116d096, 17a2d96: `versionCode 1`)
# is refused: its APK could never be installed over the stick's newer one.
# The final tools/check.py runs the restored tree's tests exactly as for any commit, so a rollback
# commits only if that SHA's own tests pass on that SHA's code.
# Any failure after the trees are removed resets the index and worktree to HEAD.
# tools/rolled-back-to records <good-sha>: this file stays at HEAD, so its APP_TESTS can list tests newer
# than the restored tree, and tools/check.py (which fails when a listed test is missing) then requires that
# SHA's tests instead. It goes in the rollback commit; it is not what makes a rollback "something to do".
# A SHA from before the staging build type cannot be built with ./build.sh --staging; the next
# steps printed then use --skip-staging on the release install.
# Gradle's dependency verification (android/gradle/verification-metadata.xml, added in cdd234f) is part
# of android/ and comes from the SHA when it has one. A SHA from before it has none: HEAD's is kept (and
# the output says so), because build.sh refuses a tree without it. No dependency or plugin version has
# changed since 116d096, so HEAD's covers every older SHA; if one ever needs an artifact HEAD's does not
# pin, Gradle (--dependency-verification strict) fails the build: regenerate the file and review the
# diff (README), never weaken verification.
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -eq 1 ] || { echo "usage: $0 <good-sha>" >&2; exit 2; }
[ -z "$(git status --porcelain)" ] || { echo "refusing: working tree is dirty" >&2; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "refusing: not on main" >&2; exit 1; }
GOOD=$(git rev-parse --verify -q "$1^{commit}") || { echo "refusing: $1 is not a commit" >&2; exit 1; }
git merge-base --is-ancestor "$GOOD" HEAD || { echo "refusing: $1 is not in main's history" >&2; exit 1; }
PATCH=tools/android-patch.py; MANIFEST=tools/upstream-manifest.json; SRC=
META=android/gradle/verification-metadata.xml; META_KEPT=0; ROLLED=tools/rolled-back-to
# One line each (tests/tools.test.sh reads them). tests/gradle.test.py checks android/'s Gradle files
# against android/gradle/verification-metadata.xml, so it is an APP test; build.sh's refusals of a
# weakened verification moved to tests/build-verification.test.py, a HEAD test.
# tests/fixtures (the shared photo-library example, docs/PHOTO-GROUPS.md) is APP data: only app tests
# read it (wallpaper-groups.test.js, java/UserLibraryTest.java, upload.test.sh), so it comes from the SHA
# with them (HEAD's, read by an older SHA's tests, refused every rollback to 5681020..849c54e).
# tests/upload.test.sh + tests/upload/ test the app's own upload server (android/), so they are APP tests.
HEAD_TESTS=(tests/tools.test.sh tests/stick-setup.test.sh tests/check.test.py tests/stick-lib.test.py tests/icon_metrics.test.py tests/build-verification.test.py)
APP_TESTS=(tests/android.test.py tests/brands.test.py tests/space-wallpapers.test.py tests/cyberpunk-wallpapers.test.py tests/device.test.js tests/fallback.test.sh tests/java tests/gradle.test.py tests/manifest.test.py tests/patch.test.py tests/wallpaper-groups.test.js tests/photo-groups.test.js tests/upload.test.sh tests/upload tests/fixtures tests/materials.test.js tests/home-extras.test.js tests/app-edit.test.js tests/photo-crop.test.js tests/bluetooth.test.js tests/upload-page.test.js)
in_list() { local x p=$1; shift; for x; do [ "$x" = "$p" ] && return 0; done; return 1; }
bad=; AT_HEAD=$(git ls-tree --name-only HEAD tests/)   # a git failure stops here (set -e)
while IFS= read -r p; do
  [ -n "$p" ] || continue
  if in_list "$p" "${HEAD_TESTS[@]}"; then in_list "$p" "${APP_TESTS[@]}" && bad+=" $p(both lists)"
  elif ! in_list "$p" "${APP_TESTS[@]}"; then bad+=" $p"; fi
done <<<"$AT_HEAD"
[ -z "$bad" ] || { echo "refusing: unclassified tests at HEAD:$bad; add each to HEAD_TESTS or APP_TESTS in $0 (nothing changed)" >&2; exit 1; }
for p in android web "$PATCH"; do   # tests/ is optional at the SHA
  git cat-file -e "$GOOD:$p" 2>/dev/null || { echo "refusing: ${GOOD:0:12} has no $p; nothing changed" >&2; exit 1; }
done
GRADLE=android/app/build.gradle
if git grep -q -E -e '^[[:space:]]*versionCode[[:space:]]+[0-9]+[[:space:]]*$' "$GOOD" -- "$GRADLE" 2>/dev/null \
   || ! git grep -q -F -e "'rev-list', '--count'" "$GOOD" -- "$GRADLE" 2>/dev/null; then
  echo "refusing: ${GOOD:0:12}'s $GRADLE does not set versionCode from the commit count (it hard-codes it)," >&2
  echo "so its APK can never be installed over the stick's newer one; pick a later SHA. Nothing changed." >&2
  exit 1
fi
# The manifest that goes with the patch file at commit $1: checked out from $1, or removed.
take_manifest() {
  if git cat-file -e "$1:$MANIFEST" 2>/dev/null; then git checkout "$1" -- "$MANIFEST"
  else git rm -qf --ignore-unmatch "$MANIFEST"; fi
}
# No pipeline here: `git show | grep -q` under pipefail failed ~1 run in 5 (grep -q exits at the
# first match, git show dies of SIGPIPE, the pipeline is false). tests/tools.test.sh bans the pattern.
has_verify() { git grep -q -G -e '^def verify(' "$1" -- "$PATCH" 2>/dev/null; }

DONE=0
undo() { [ $DONE = 1 ] || { git reset -q --hard HEAD; echo "rollback aborted: index and worktree reset to HEAD; nothing committed" >&2; }; }
trap undo EXIT
git rm -rq android web "$PATCH"
git rm -rq --ignore-unmatch tests
git checkout "$GOOD" -- android web "$PATCH"
if ! git cat-file -e "$GOOD:$META" 2>/dev/null; then
  git checkout HEAD -- "$META"; META_KEPT=1
  echo "${GOOD:0:12} has no $META (it predates dependency verification): keeping HEAD's, so ./build.sh verifies its dependencies"
fi
take_manifest "$GOOD"
if git cat-file -e "$GOOD:tests" 2>/dev/null; then git checkout "$GOOD" -- tests; fi
for k in "${HEAD_TESTS[@]}"; do   # HEAD's copy, whatever the SHA had
  git rm -rqf --ignore-unmatch "$k"
  if git cat-file -e "HEAD:$k" 2>/dev/null; then git checkout HEAD -- "$k"; fi
done
echo "$GOOD" > "$ROLLED"; git add -- "$ROLLED"   # before the first check.py (header)
if ! has_verify "$GOOD"; then
  echo "${GOOD:0:12} predates android-patch.py --verify; looking for a later patch file that verifies its web/"
  for c in $(git rev-list --reverse "$GOOD..HEAD" -- "$PATCH"); do
    has_verify "$c" || continue
    git checkout "$c" -- "$PATCH"; take_manifest "$c"   # a pair: the patch file needs ITS manifest
    # Only finding a patch file here: --skip-nested skips tests/tools.test.sh (it spawns rollback
    # clones and tests HEAD's tools, which this loop does not change); the final check runs it.
    # A rejected candidate says why (check.py's message and any FAIL lines): a failing test, not only
    # a patch file that does not fit, can reject one, and the search then moves on to a later one.
    if why=$(python3 tools/check.py --skip-nested 2>&1 >/dev/null); then SRC=$c; break; fi
    echo "  not ${c:0:12}: $(sed -n 1p <<<"$why")"
    sed -n 's/^[[:space:]|]*\(FAIL .*\)/    \1/p' <<<"$why" | sed -n 1,10p
  done
  [ -n "$SRC" ] || { echo "refusing: no later $PATCH verifies ${GOOD:0:12}'s web/; roll back by hand (restore android/ + web/, write a patch file with verify() for it, run tools/check.py, commit)" >&2; exit 1; }
  echo "using $PATCH from ${SRC:0:12} (verifies ${GOOD:0:12}'s web/)"
fi
if git diff --cached --quiet -- . ":!$ROLLED"; then
  echo "nothing to roll back: android/, web/, $PATCH, $MANIFEST and the app tests already match ${GOOD:0:12}" >&2; exit 1
fi
python3 tools/check.py || { echo "check failed" >&2; exit 1; }
git commit -q -m "Roll back android/, web/, $PATCH, $MANIFEST and their tests to ${GOOD:0:12}" \
  -m "Rolled forward with tools/rollback.sh: the stick refuses an older versionCode.${SRC:+ $PATCH and $MANIFEST taken from ${SRC:0:12}, the first patch file with verify() that passes on this web/.} $MANIFEST goes with $PATCH (removed when that commit has none).$([ $META_KEPT = 0 ] || echo " $META kept from HEAD (${GOOD:0:12} predates it).") Kept from HEAD (they test the tools, which are not rolled back): ${HEAD_TESTS[*]}."
DONE=1
N=$(git rev-parse --short=12 HEAD)
echo "ROLLBACK_COMMIT $N (app = ${GOOD:0:12})"
if git cat-file -e "$GOOD:android/app/src/staging/AndroidManifest.xml" 2>/dev/null; then
  # install.sh wants a --recovery pass when android/ or web/device.js differ from the SHA installed as Home.
  # Which SHA that is, only the stick knows; a change from the commit this rolled back from needs it for certain.
  RECOVERY=; git diff --quiet HEAD^ HEAD -- android web/device.js || RECOVERY=" && tools/stick-test.sh --recovery"
  echo "next: git push && ./build.sh --staging && ./install.sh dist/cleanframe-tv-$N-staging.apk && tools/stick-test.sh --staging$RECOVERY"
  if [ -n "$RECOVERY" ]; then echo "(--recovery: this rollback changes android/ or web/device.js, and install.sh refuses the release without that pass)"
  else echo "(no --recovery here: this rollback leaves android/ and web/device.js alone; install.sh asks for it if the SHA installed as Home differs there)"; fi
  echo "then: ./build.sh && ./install.sh dist/cleanframe-tv-$N.apk --make-home && tools/stick-test.sh"
  echo "(Home broken and no time to stage: add --skip-staging to the release install, and log that you did)"
  echo "(the stick test is HEAD's: it SKIPs, naming it, each feature section ${GOOD:0:12} predates; every other check must pass)"
else
  echo "NOTE: ${GOOD:0:12} predates the staging build type (no android/app/src/staging/, no 'staging' in"
  echo "build.gradle), so ./build.sh --staging cannot build it and no staging pass can exist for $N."
  echo "next: git push && ./build.sh && ./install.sh dist/cleanframe-tv-$N.apk --make-home --skip-staging && tools/stick-test.sh"
  echo "(--skip-staging is forced by the old tree, not an emergency; record it in the commit log)"
fi

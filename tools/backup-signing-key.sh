#!/bin/bash
# On-demand backup of the APK signing key; keeps the newest 3. It is never run on a schedule.
# Each run is checked by restoring it to scratch and comparing byte for byte before it counts.
# A run that dies part-way leaves <ts>.partial (in the backup directory) and cf-key-verify.* (in ~/scratch),
# both holding the key: every run deletes such leftovers at start and its own on exit.
# Runs are serialised by an flock on ~/scratch/cf-key-backup.lock, held from before the first
# prune until exit: a second run while one is going refuses at once (it never waits). So any
# leftover the prune finds is stale (its run is dead), never a concurrent run's work in progress.
# The directory is CLEANFRAME_KEY_BACKUP=<dir> (tests; no mount check then), else dest= in
# ~/.config/cleanframe-tv/backup.conf, whose mount=<path> refuses unless that path is mounted.
# With neither it refuses: there is no default.
set -euo pipefail
SRC=$HOME/.config/cleanframe-tv
CONF=$SRC/backup.conf
conf() { [ -f "$CONF" ] && sed -n "s/^$1=//p" "$CONF" | sed -n 1p; return 0; }
DEST=${CLEANFRAME_KEY_BACKUP:-$(conf dest)}
KEEP=3

[ -n "$DEST" ] || { echo "refusing: no backup directory (CLEANFRAME_KEY_BACKUP, or dest= in $CONF)" >&2; exit 1; }
MNT=$(conf mount)
if [ -z "${CLEANFRAME_KEY_BACKUP:-}" ] && [ -n "$MNT" ]; then
  mountpoint -q "$MNT" || { echo "refusing: $MNT is not mounted" >&2; exit 1; }
fi
[ -f "$SRC/release.jks" ] && [ -f "$SRC/signing.properties" ] || { echo "refusing: no key in $SRC" >&2; exit 1; }

umask 077
mkdir -p "$DEST" "$HOME/scratch" && chmod 700 "$DEST"
command -v flock >/dev/null || { echo "refusing: no flock(1) (util-linux) to keep two runs apart" >&2; exit 1; }
LOCK=$HOME/scratch/cf-key-backup.lock
exec 9>>"$LOCK"
flock -n 9 || { echo "refusing: another tools/backup-signing-key.sh is running ($LOCK is locked); nothing changed" >&2; exit 1; }
prune() { rm -rf "$DEST"/*.partial "$HOME"/scratch/cf-key-verify.*; }   # only under the lock (above)
prune   # leftovers of killed runs (they hold the key); no other run can be live, we hold the lock
ts=$(date -u +%Y%m%dT%H%M%SZ)
[ ! -e "$DEST/$ts" ] || { echo "refusing: $DEST/$ts already exists (two runs in one second); run again" >&2; exit 1; }
tmp="$DEST/$ts.partial"
chk=$(mktemp -d "$HOME/scratch/cf-key-verify.XXXXXX")
trap 'rm -rf "$chk" "$tmp"; prune' EXIT
mkdir "$tmp"

tar -C "$HOME" -czf "$tmp/cleanframe-tv-signing.tar.gz" .config/cleanframe-tv
(cd "$tmp" && sha256sum cleanframe-tv-signing.tar.gz > SHA256SUMS && sha256sum -c --quiet SHA256SUMS)
tar -xzf "$tmp/cleanframe-tv-signing.tar.gz" -C "$chk"
cmp "$chk/.config/cleanframe-tv/release.jks" "$SRC/release.jks"
cmp "$chk/.config/cleanframe-tv/signing.properties" "$SRC/signing.properties"

mv "$tmp" "$DEST/$ts"
date -u +%FT%TZ > "$DEST/last-success"
# Newest $KEEP by name (UTC timestamps sort in time order); a glob, not ls | head.
runs=("$DEST"/2*Z)
for ((i = 0; i < ${#runs[@]} - KEEP; i++)); do rm -rf "${runs[i]}"; done
runs=("$DEST"/2*Z)
echo "KEY_BACKUP_OK $DEST/$ts (kept: ${runs[*]##*/})"

#!/usr/bin/env bash
# Encrypt a consistent SQLite snapshot, or restore into a new database.
set -euo pipefail
umask 077
if [[ $# != 4 ]]; then
  echo "Usage: snapshot.sh backup DATABASE PUBLIC_CERT OUTPUT.cms | restore INPUT.cms PRIVATE_KEY NEW_DATABASE" >&2
  exit 64
fi
mode=$1 source_file=$2 key_file=$3 target=$4
[[ -f "$source_file" && -f "$key_file" && ! -e "$target" ]]
work=$(mktemp -d "$(dirname "$target")/.blink-snapshot.XXXXXXXX")
trap 'rm -rf "$work"' EXIT
case "$mode" in
  backup)
    # The shell command operates on a constant filename even when input paths contain spaces.
    db=$(cd "$(dirname "$source_file")" && pwd)/$(basename "$source_file")
    (cd "$work" && sqlite3 "$db" '.timeout 10000' '.backup consistent.sqlite')
    [[ $(sqlite3 "$work/consistent.sqlite" 'PRAGMA integrity_check') == ok ]]
    openssl cms -encrypt -aes-256-cbc -binary -outform DER -in "$work/consistent.sqlite" -out "$work/result" "$key_file"
    ;;
  restore)
    openssl cms -decrypt -binary -inform DER -in "$source_file" -inkey "$key_file" -out "$work/result"
    [[ $(sqlite3 "$work/result" 'PRAGMA integrity_check') == ok ]]
    sqlite3 "$work/result" "BEGIN IMMEDIATE; DELETE FROM sessions; DELETE FROM devices; UPDATE metadata SET value=lower(hex(randomblob(16))) WHERE name='epoch'; COMMIT; PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null
    ;;
  *) exit 64 ;;
esac
ln "$work/result" "$target"

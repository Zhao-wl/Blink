#!/bin/bash
# The job opens its own verified SSH connection even when the development tunnel is closed.
set -euo pipefail
: "${BLINK_SSH_HOST:?Missing staging host}"
root="$HOME/Library/Application Support/BlinkDevelopment"
keys="$root/ssh"
work=$(mktemp -d /tmp/blink-backup.XXXXXXXX)
ctl="$work/control"
destination="blink-tunnel@$BLINK_SSH_HOST"
close_tunnel() {
  ssh -F /dev/null -S "$ctl" -O exit "$destination" >/dev/null 2>&1 || true
  rmdir "$work" 2>/dev/null || true
}
trap close_tunnel EXIT
ssh -F /dev/null -fMNS "$ctl" -o BatchMode=yes -o ConnectTimeout=10 -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=\"$keys/hosts\"" -o ExitOnForwardFailure=yes \
  -i "$keys/tunnel" -L 127.0.0.1:18766:127.0.0.1:18765 "$destination"
BLINK_BACKUP_PORT=18766 /usr/bin/python3 "$(dirname "$0")/fetch-backups.py" "$root/backups"

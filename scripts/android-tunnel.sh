#!/usr/bin/env bash
# Set BLINK_SSH_HOST to the authorized ECS IPv4 address.
set -euo pipefail
: "${BLINK_SSH_HOST:?Set BLINK_SSH_HOST to the staging host}"
keys="${BLINK_KEYS:-$HOME/Library/Application Support/BlinkDevelopment/ssh}"
exec ssh -F /dev/null -NT -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=\"$keys/hosts\"" -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -i "$keys/tunnel" -L 127.0.0.1:18765:127.0.0.1:18765 "blink-tunnel@$BLINK_SSH_HOST"

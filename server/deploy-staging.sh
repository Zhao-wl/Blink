#!/usr/bin/env bash
# Manually replay a tested commit through the same restricted staging publisher.
set -euo pipefail
[[ $# == 1 && $1 =~ ^[0-9a-f]{40}$ ]] || { echo "Usage: deploy-staging.sh FULL_COMMIT_SHA" >&2; exit 64; }
exec sudo -u blink-publish env SSH_ORIGINAL_COMMAND="$1" /usr/local/libexec/blink-publish

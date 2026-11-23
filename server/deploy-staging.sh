#!/usr/bin/env bash
# Run from a verified release directory on the staging ECS.
set -euo pipefail
cd "$(dirname "$0")"
PYTHONDONTWRITEBYTECODE=1 python3 test_api.py
app=/opt/blink-staging
unit=/etc/systemd/system/blink-staging.service
backup=$(mktemp -d)
cp "$unit" "$backup/unit"
if [[ -f "$app/api.py" ]]; then cp "$app/api.py" "$backup/api.py"; fi
rollback() {
  if [[ -f "$backup/api.py" ]]; then install -m 644 "$backup/api.py" "$app/api.py"; fi
  install -m 644 "$backup/unit" "$unit"
  systemctl daemon-reload
  systemctl restart blink-staging
}
trap rollback ERR
install -m 644 api.py "$app/api.py.new"
mv "$app/api.py.new" "$app/api.py"
install -m 644 blink-staging.service "$unit"
systemctl daemon-reload
systemctl restart blink-staging
curl -fsS --retry 5 --retry-connrefused http://127.0.0.1:18765/healthz
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:18765/v1/sync)
test "$code" = 401
printf '\nBlink sync API active.\n'

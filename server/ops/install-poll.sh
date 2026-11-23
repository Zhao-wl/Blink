#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
install -m 755 poll.py /usr/local/libexec/blink-poll.py
sed -n 's/^.*\(ssh-ed25519 [A-Za-z0-9+\/=]*\).*$/\1/p' /var/empty/blink-publish/.ssh/authorized_keys > /etc/blink-staging-deploy.pub
cat > /etc/systemd/system/blink-publish.service <<'UNIT'
[Unit]
Description=Apply CI-signed Blink staging release
[Service]
Type=oneshot
User=blink-publish
Group=blink-publish
PrivateTmp=yes
ExecStart=/usr/bin/python3 -B /usr/local/libexec/blink-poll.py
UNIT
cat > /etc/systemd/system/blink-publish.timer <<'UNIT'
[Unit]
Description=Check for approved Blink staging releases
[Timer]
OnBootSec=1min
OnUnitInactiveSec=2min
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now blink-publish.timer

#!/usr/bin/env bash
# Run once on staging with the recipient's public certificate and a SHA-256 access digest.
set -euo pipefail
[[ $# == 2 && -f "$1" && $2 =~ ^[a-f0-9]{64}$ ]]
openssl x509 -in "$1" -noout
install -m 644 "$1" /etc/blink-backup-recipient.pem
printf '%s\n' "$2" > /etc/blink-backup-access.sha256
install -m 755 "$(dirname "$0")/snapshot.sh" /usr/local/libexec/blink-snapshot
cat > /usr/local/libexec/blink-daily-backup <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
umask 077
cd /var/lib/blink-staging
mkdir -p encrypted
file="encrypted/$(date -u +%F).cms"
if [[ ! -s "$file" ]]; then /usr/local/libexec/blink-snapshot backup blink.sqlite3 /etc/blink-backup-recipient.pem "$file"; fi
SCRIPT
chmod 755 /usr/local/libexec/blink-daily-backup
cat > /etc/systemd/system/blink-backup.service <<'UNIT'
[Unit]
Description=Encrypt a consistent Blink staging database backup
[Service]
Type=oneshot
User=blink-staging
DynamicUser=yes
StateDirectory=blink-staging
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ExecStart=/usr/local/libexec/blink-daily-backup
UNIT
cat > /etc/systemd/system/blink-backup.timer <<'UNIT'
[Unit]
Description=Daily encrypted Blink staging backup
[Timer]
OnCalendar=*-*-* 02:00:00 UTC
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now blink-backup.timer
systemctl start blink-backup.service

#!/usr/bin/env bash
# Once, via an authenticated administrator console. Only public keys are arguments.
set -euo pipefail
cd "$(dirname "$0")"
[[ $# == 2 && $1 == ssh-ed25519\ * && $2 == ssh-ed25519\ * ]]
for user in blink-tunnel blink-publish; do
  if ! id "$user" >/dev/null 2>&1; then useradd -M -d "/var/empty/$user" -s /bin/bash "$user"; fi
  install -d -o root -g root -m 755 "/var/empty/$user/.ssh"
done
printf 'restrict,port-forwarding,permitopen="127.0.0.1:18765",command="/bin/false" %s\n' "$1" > /var/empty/blink-tunnel/.ssh/authorized_keys
printf 'restrict,command="/usr/local/libexec/blink-publish" %s\n' "$2" > /var/empty/blink-publish/.ssh/authorized_keys
chmod 644 /var/empty/blink-{tunnel,publish}/.ssh/authorized_keys
restorecon -RF /var/empty/blink-{tunnel,publish} 2>/dev/null || true
if ! grep -q '^Match User blink-tunnel' /etc/ssh/sshd_config; then
  printf '\nMatch User blink-tunnel\n    AllowTcpForwarding local\n    AllowStreamLocalForwarding no\n    PermitTunnel no\nMatch all\n' >> /etc/ssh/sshd_config
  /usr/sbin/sshd -t
  systemctl reload sshd
fi
install -D -o root -g root -m 755 publish /usr/local/libexec/blink-publish
printf 'blink-publish ALL=(root) NOPASSWD: /usr/bin/systemctl restart blink-staging.service\n' > /etc/sudoers.d/blink-publish
chmod 440 /etc/sudoers.d/blink-publish
visudo -cf /etc/sudoers.d/blink-publish
d=/var/lib/blink-publish
install -d -o blink-publish -g blink-publish -m 755 "$d"
if [[ ! -L "$d/current" ]]; then
  install -d -m 755 "$d/initial"
  install -m 644 /opt/blink-staging/api.py "$d/initial/api.py"
  ln -s initial "$d/current"
fi
install -d /etc/systemd/system/blink-staging.service.d
printf '[Service]\nExecStart=\nExecStart=/usr/bin/python3 -B /var/lib/blink-publish/current/api.py\n' > /etc/systemd/system/blink-staging.service.d/release.conf
systemctl daemon-reload
systemctl restart blink-staging
curl -fsS --retry 5 --retry-connrefused http://127.0.0.1:18765/healthz

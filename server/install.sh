#!/bin/bash
# Run on the VPN server as root, from this directory: sudo ./install.sh
set -euo pipefail
cd "$(dirname "$0")"
install -d -m 700 /etc/wgctl /opt/wgctl
install -m 644 wgctl.py /opt/wgctl/wgctl.py
install -m 644 wgctl.service /etc/systemd/system/wgctl.service
if [ ! -s /etc/wgctl/token ]; then
    (umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(24))' > /etc/wgctl/token)
fi
systemctl daemon-reload
systemctl enable wgctl
systemctl restart wgctl
echo "wgctl running on http://10.100.0.1:8080"
echo "App token: $(cat /etc/wgctl/token)"

"""Tiny HTTP API to enable/disable individual WireGuard peers.

Listens only on the tunnel address, so it is unreachable from the internet.
Disabling a peer removes exactly that peer's public key from wg0; every
other peer is untouched. Disabled peers are remembered across reboots.

  GET  /peers                  -> [{name, ip, enabled, handshake}]
  POST /peers/<name>/enable
  POST /peers/<name>/disable

Every request needs header "Authorization: Bearer <token>" (token in TOKEN_FILE).
"""
import hmac
import json
import os
import re
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

IFACE = "wg0"
CONF = f"/etc/wireguard/{IFACE}.conf"
STATE = "/etc/wgctl/disabled.json"
TOKEN_FILE = "/etc/wgctl/token"
LISTEN = ("10.100.0.1", 8080)


def wg(*args):
    return subprocess.run(["wg", *args], check=True, capture_output=True, text=True).stdout


def load_peers():
    """Parse '# name' comments above each [Peer] in wg0.conf -> {name: {pub, ip}}."""
    peers, name, cur = {}, None, None
    for line in open(CONF):
        line = line.strip()
        if line.startswith("#"):
            name = line[1:].strip()
        elif line == "[Peer]":
            cur = peers.setdefault(name, {})
        elif cur is not None and "=" in line:
            key, val = (s.strip() for s in line.split("=", 1))
            if key == "PublicKey":
                cur["pub"] = val
            elif key == "AllowedIPs":
                cur["ip"] = val
    return peers


def load_disabled():
    try:
        return set(json.load(open(STATE)))
    except FileNotFoundError:
        return set()


def save_disabled(names):
    tmp = STATE + ".tmp"
    json.dump(sorted(names), open(tmp, "w"))
    os.replace(tmp, STATE)


def set_enabled(peer, enabled):
    if enabled:
        wg("set", IFACE, "peer", peer["pub"], "allowed-ips", peer["ip"])
    else:
        wg("set", IFACE, "peer", peer["pub"], "remove")


def apply_state():
    """wg-quick re-adds every peer from wg0.conf on start; strip the disabled ones again."""
    peers = load_peers()
    for name in load_disabled():
        if name in peers:
            set_enabled(peers[name], False)


def handshakes():
    """{pubkey: unix time of latest handshake} for peers currently on the interface."""
    out = {}
    for row in wg("show", IFACE, "dump").splitlines()[1:]:
        cols = row.split("\t")
        out[cols[0]] = int(cols[4])
    return out


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        got = self.headers.get("Authorization", "")
        return hmac.compare_digest(got, f"Bearer {TOKEN}")

    def do_GET(self):
        if not self._authorized():
            return self._send(401, {"error": "bad token"})
        if self.path != "/peers":
            return self._send(404, {"error": "not found"})
        disabled, hs, now = load_disabled(), handshakes(), time.time()
        me = self.client_address[0]
        self._send(200, [
            {
                "name": name,
                "ip": p["ip"],
                "enabled": name not in disabled,
                "self": p["ip"].split("/")[0] == me,
                "handshake_ago": int(now - hs[p["pub"]]) if hs.get(p["pub"]) else None,
            }
            for name, p in load_peers().items()
        ])

    def do_POST(self):
        if not self._authorized():
            return self._send(401, {"error": "bad token"})
        m = re.fullmatch(r"/peers/([^/]+)/(enable|disable)", self.path)
        peers = load_peers()
        if not m or m[1] not in peers:
            return self._send(404, {"error": "not found"})
        name, enable = m[1], m[2] == "enable"
        peer = peers[name]
        # Refuse to cut off the device that is making the request - it would lock you out.
        if not enable and peer["ip"].split("/")[0] == self.client_address[0]:
            return self._send(409, {"error": "refusing to disable the device you are using"})
        set_enabled(peer, enable)
        disabled = load_disabled()
        disabled.discard(name) if enable else disabled.add(name)
        save_disabled(disabled)
        self._send(200, {"name": name, "enabled": enable})


if __name__ == "__main__":
    TOKEN = open(TOKEN_FILE).read().strip()
    apply_state()
    ThreadingHTTPServer(LISTEN, Handler).serve_forever()

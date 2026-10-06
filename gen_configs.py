"""Generate WireGuard keys and configs for the server and each peer.

Usage: python3 gen_configs.py <endpoint-hostname>
Writes configs/ (secret - do not commit). Re-running keeps existing keys.
"""
import base64
import json
import os
import sys

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives import serialization

ENDPOINT = sys.argv[1] if len(sys.argv) > 1 else "vpn.forgenerative.ai"
PORT = 51820
SUBNET = "10.100.0.0/24"
PEERS = {"homepc": 2, "work": 3, "phone": 4, "laptop": 5}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "configs")
KEYFILE = os.path.join(OUT, "keys.json")


def b64(raw):
    return base64.b64encode(raw).decode()


def new_keypair():
    priv = X25519PrivateKey.generate()
    return {
        "private": b64(priv.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())),
        "public": b64(priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)),
    }


os.makedirs(OUT, exist_ok=True)
keys = json.load(open(KEYFILE)) if os.path.exists(KEYFILE) else {}
for name in ["server", *PEERS]:
    keys.setdefault(name, new_keypair())
json.dump(keys, open(KEYFILE, "w"), indent=2)

server = [
    "[Interface]",
    "Address = 10.100.0.1/24",
    f"ListenPort = {PORT}",
    f"PrivateKey = {keys['server']['private']}",
]
for name, octet in PEERS.items():
    server += ["", f"# {name}", "[Peer]", f"PublicKey = {keys[name]['public']}", f"AllowedIPs = 10.100.0.{octet}/32"]
open(os.path.join(OUT, "server-wg0.conf"), "w").write("\n".join(server) + "\n")

for name, octet in PEERS.items():
    lines = [
        "[Interface]",
        f"PrivateKey = {keys[name]['private']}",
        f"Address = 10.100.0.{octet}/24",
        "",
        "[Peer]",
        f"PublicKey = {keys['server']['public']}",
        f"Endpoint = {ENDPOINT}:{PORT}",
        f"AllowedIPs = {SUBNET}",
        "PersistentKeepalive = 25",
    ]
    open(os.path.join(OUT, f"{name}.conf"), "w", newline="\r\n").write("\n".join(lines) + "\n")

print(f"Wrote configs to {OUT}")

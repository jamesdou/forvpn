"""Tiny HTTP API to enable/disable individual WireGuard peers.

Listens only on the tunnel address, so it is unreachable from the internet.
Disabling a peer removes exactly that peer's public key from wg0; every
other peer is untouched. Disabled peers are remembered across reboots.

A background monitor records connect/disconnect/new-IP events and runs
timers ("on for 2 hours") and daily schedules ("off at 01:00").

  GET    /peers?tz=ZONE             -> [{name, ip, domain, enabled, self, handshake_ago, timer, public_ip, traffic}]
  GET    /peers/<name>/traffic?tz=ZONE  -> live speed, session, today/7 days/month, last 14 days
  GET    /connections               -> devices online now (live speed, session) and device-to-device links
  POST   /peers/<name>/enable       body {"minutes": N} optional: revert after N minutes
  POST   /peers/<name>/disable      body {"minutes": N} optional
  GET    /events?since=ID&peer=NAME&limit=N
  GET    /schedules
  POST   /schedules                 body {peer, action: enable|disable, time: "HH:MM", days: "0123456", tz}
  DELETE /schedules/<id>
  POST   /peers/<name>/rdp_mode     body {"mode": "direct"|"tunnel"}: Remote Desktop access on that PC
  POST   /peers/<name>/ssh          body {"enabled": bool}: SSH server on that PC
  POST   /peers/<name>/shell        body {"enabled": bool}: terminal access (device key + Windows password)
  POST   /peers/<name>/keys         body {"name", "key"}: allow a device's SSH public key
  DELETE /peers/<name>/keys/<name>

Device helper (rdp-agent.ps1), identified by its VPN address instead of the token -
WireGuard guarantees a packet from 10.100.0.x came from that device's key:
  GET    /agent                     -> {"rdp_mode", "ssh_enabled", "keys", "shell_enabled"} for the calling device
  POST   /agent/status              body {"applied", "sshd", "error", "keys", "shell"}

Every request needs header "Authorization: Bearer <token>" (token in TOKEN_FILE).
"""
import base64
import hashlib
import hmac
import json
import os
import re
import socket
import sqlite3
import subprocess
import threading
import time
import urllib.request
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

IFACE = "wg0"
CONF = f"/etc/wireguard/{IFACE}.conf"
STATE = "/etc/wgctl/disabled.json"
TOKEN_FILE = "/etc/wgctl/token"
DB_FILE = "/var/lib/wgctl/wgctl.db"
NTFY_FILE = "/etc/wgctl/ntfy.json"  # {"url": "https://ntfy.sh/<topic>", "quiet_peers": ["phone"]}
DOMAIN = "forgenerative.ai"
DOMAIN_ALIASES = {"homepc": "home"}  # devices whose DNS name differs from their peer name
DOMAIN_REFRESH = 600

domains = {}  # peer name -> DNS name verified to point at that peer's VPN address


def refresh_domains():
    """Show a name only if public DNS really points it at the device, so the app is never wrong."""
    found = {}
    for name, p in load_peers().items():
        host = f"{DOMAIN_ALIASES.get(name, name)}.{DOMAIN}"
        try:
            if socket.gethostbyname(host) == p["ip"].split("/")[0]:
                found[name] = host
        except OSError:
            pass  # no such record
    domains.clear()
    domains.update(found)
LISTEN = ("10.100.0.1", 8080)
ONLINE_WINDOW = 180  # WireGuard re-handshakes every ~2 min while keepalives flow
OFFLINE_AFTER = 70  # clients send a keepalive every 25s; ~2 missed ones means the tunnel is down
EARLY_HANDSHAKE = 90  # routine rekeys are >=120s apart; a sooner handshake means a restarted tunnel
TICK = 10  # monitor interval; under 60s so every schedule minute is seen
RETENTION_DAYS = 90

lock = threading.RLock()  # guards wg changes, disabled.json and the db
db = None


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


def dump():
    """{pubkey: (latest handshake, endpoint ip, endpoint ip:port, bytes received, bytes sent)}."""
    out = {}
    for row in wg("show", IFACE, "dump").splitlines()[1:]:
        cols = row.split("\t")
        endpoint = None if cols[2] == "(none)" else cols[2]
        ip = endpoint.rsplit(":", 1)[0].strip("[]") if endpoint else None
        out[cols[0]] = (int(cols[4]), ip, endpoint, int(cols[5]), int(cols[6]))
    return out


# ---------------------------------------------------------------- storage

def open_db():
    os.makedirs(os.path.dirname(DB_FILE), exist_ok=True)
    conn = sqlite3.connect(DB_FILE, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY, ts REAL, peer TEXT, kind TEXT, detail TEXT, source TEXT);
        CREATE TABLE IF NOT EXISTS known_ips (
            peer TEXT, ip TEXT, first_seen REAL, PRIMARY KEY (peer, ip));
        CREATE TABLE IF NOT EXISTS timers (
            peer TEXT PRIMARY KEY, action TEXT, at REAL);
        CREATE TABLE IF NOT EXISTS rdp_mode (
            peer TEXT PRIMARY KEY, mode TEXT, ts REAL);
        CREATE TABLE IF NOT EXISTS agent_status (
            peer TEXT PRIMARY KEY, applied TEXT, sshd INTEGER, error TEXT, ts REAL);
        CREATE TABLE IF NOT EXISTS ssh_wanted (
            peer TEXT PRIMARY KEY, enabled INTEGER, keys TEXT);
        CREATE TABLE IF NOT EXISTS last_ip (
            peer TEXT PRIMARY KEY, ip TEXT, ts REAL);
        CREATE TABLE IF NOT EXISTS usage (
            peer TEXT, hour INTEGER, up INTEGER, down INTEGER, PRIMARY KEY (peer, hour));
        CREATE TABLE IF NOT EXISTS schedules (
            id INTEGER PRIMARY KEY, peer TEXT, action TEXT, time TEXT, days TEXT, tz TEXT);
    """)
    for table, column in (("agent_status", "keys TEXT"), ("agent_status", "shell INTEGER"),
                          ("ssh_wanted", "shell INTEGER")):  # columns added after the first release
        try:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column}")
        except sqlite3.OperationalError:
            pass
    # Backfill from history for devices not seen since last_ip was added: their latest
    # "from <ip>" connection, dated by their most recent event of any kind.
    conn.execute("""
        INSERT OR IGNORE INTO last_ip
        SELECT e.peer, substr(e.detail, 6), (SELECT MAX(ts) FROM events WHERE peer = e.peer)
        FROM events e
        WHERE e.id = (SELECT MAX(id) FROM events
                      WHERE peer = e.peer AND kind IN ('connected', 'reconnected') AND detail LIKE 'from %')
    """)
    conn.commit()
    return conn


def log_event(peer, kind, detail="", source="monitor", ts=None):
    with lock:
        db.execute("INSERT INTO events (ts, peer, kind, detail, source) VALUES (?, ?, ?, ?, ?)",
                   (ts or time.time(), peer, kind, detail, source))
        db.commit()
    threading.Thread(target=push, args=(peer, kind, detail, source), daemon=True).start()


KEY_LINE = re.compile(r"(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)) ([A-Za-z0-9+/]+={0,2})")
KEY_NAME = re.compile(r"[A-Za-z0-9._-]{1,32}")


def parse_key(key, name):
    """Return a clean "type base64 name" line, or None. Plain keys only: no SSH options, no extra lines."""
    m = KEY_LINE.fullmatch(" ".join(str(key).split()[:2]))
    if not m or not KEY_NAME.fullmatch(str(name)):
        return None
    try:
        blob = base64.b64decode(m[2], validate=True)
    except ValueError:
        return None
    # The blob starts with its own key type; a mismatch means a mangled paste.
    n = int.from_bytes(blob[:4], "big")
    if blob[4:4 + n].decode(errors="replace") != m[1]:
        return None
    return f"{m[1]} {m[2]} {name}"


def describe_key(line):
    kind, b64, *rest = line.split()
    digest = base64.b64encode(hashlib.sha256(base64.b64decode(b64)).digest()).decode().rstrip("=")
    return {"name": rest[-1] if rest else "", "type": kind.replace("ssh-", ""), "fingerprint": "SHA256:" + digest}


def ssh_wanted(name):
    """(enabled, [key lines] or None if not known yet) for a PC."""
    with lock:
        r = db.execute("SELECT * FROM ssh_wanted WHERE peer = ?", (name,)).fetchone()
    if not r:
        return True, None
    return bool(r["enabled"]), (json.loads(r["keys"]) if r["keys"] is not None else None)


def shell_wanted(name):
    """True/False for terminal access, or None if not known yet (the PC's setting is left alone)."""
    with lock:
        r = db.execute("SELECT shell FROM ssh_wanted WHERE peer = ?", (name,)).fetchone()
    return None if not r or r["shell"] is None else bool(r["shell"])


def save_ssh_wanted(name, enabled, keys, shell="keep"):
    shell = shell_wanted(name) if shell == "keep" else shell
    with lock:
        db.execute("INSERT OR REPLACE INTO ssh_wanted (peer, enabled, keys, shell) VALUES (?, ?, ?, ?)",
                   (name, int(enabled), json.dumps(keys) if keys is not None else None,
                    None if shell is None else int(shell)))
        db.commit()


def push(peer, kind, detail, source):
    """Instant phone notification via ntfy, if NTFY_FILE configures a topic."""
    try:
        cfg = json.load(open(NTFY_FILE))
    except (FileNotFoundError, ValueError):
        return
    # A new SSH key is a way in, so it alerts even when added from the app.
    security = kind in ("new_ip", "ssh_key_added") or (kind == "ssh_shell" and detail.endswith("on"))
    # Same rules as the app: skip changes you made yourself and your own phone's comings and goings.
    if not security and (source == "app" or peer in cfg.get("quiet_peers", [])):
        return
    by = {"timer": " (timer)", "schedule": " (schedule)"}.get(source, "")
    title = {
        "connected": f"{peer} connected",
        "disconnected": f"{peer} disconnected",
        "reconnected": f"{peer} reconnected",
        "rdp_mode": f"{peer}: Remote Desktop access changed",
        "ssh_key_added": f"{peer}: SSH key added",
        "ssh_key_removed": f"{peer}: SSH key removed",
        "ssh": f"{peer}: SSH server changed",
        "ssh_shell": f"{peer}: terminal access changed",
        "new_ip": f"{peer} connected from a new address",
        "enabled": f"{peer} turned on{by}",
        "disabled": f"{peer} turned off{by}",
    }.get(kind, f"{peer}: {kind}")
    body = detail + (". If this wasn't you, turn the device off in VPN Switch." if kind == "new_ip" else
                     ". If this wasn't you, remove the key in VPN Switch." if kind == "ssh_key_added" else "")
    req = urllib.request.Request(cfg["url"], data=(body or title).encode(), headers={
        "Title": title,
        "Priority": "high" if security else "default",
        "Tags": {"connected": "green_circle", "disconnected": "white_circle",
                 "reconnected": "arrows_counterclockwise", "new_ip": "warning",
                 "enabled": "large_blue_circle", "disabled": "black_circle"}.get(kind, "bell"),
    })
    try:
        urllib.request.urlopen(req, timeout=10).close()
    except Exception as e:
        print(f"ntfy push failed: {e}", flush=True)


def change_peer(name, enable, source):
    """Turn one peer on/off, remember it, and log who did it. Returns False if unknown."""
    with lock:
        peers = load_peers()
        if name not in peers:
            return False
        set_enabled(peers[name], enable)
        disabled = load_disabled()
        was_enabled = name not in disabled
        disabled.discard(name) if enable else disabled.add(name)
        save_disabled(disabled)
        if was_enabled != enable:
            log_event(name, "enabled" if enable else "disabled", source=source)
        return True


def fmt_duration(seconds):
    m = int(seconds // 60)
    if m < 60:
        return f"{m} min"
    h, m = divmod(m, 60)
    return f"{h} h {m} min" if h < 24 else f"{h // 24} d {h % 24} h"


# ---------------------------------------------------------------- monitor

SERVICES = {3389: "Remote Desktop", 22: "SSH", 445: "File sharing", 139: "File sharing", 5900: "VNC",
            80: "Web", 443: "Web", 53: "DNS", 8080: "Web"}


def ensure_flow_tracking():
    """Have the kernel track connections between devices, so /connections can list them.
    The rule has no action (-j): it only matches and counts, allows and blocks nothing, and sits
    at the end of FORWARD so it can never override a rule added later."""
    rule = ["FORWARD", "-i", IFACE, "-o", IFACE, "-m", "conntrack", "--ctstate", "NEW,RELATED,ESTABLISHED"]
    try:
        subprocess.run(["sysctl", "-qw", "net.netfilter.nf_conntrack_acct=1", "net.netfilter.nf_conntrack_timestamp=1"],
                       check=True, capture_output=True)
        if subprocess.run(["iptables", "-C", *rule], capture_output=True).returncode != 0:
            subprocess.run(["iptables", "-A", *rule], check=True, capture_output=True)
    except Exception as e:
        print(f"flow tracking unavailable: {e}", flush=True)


link_samples = {}  # (src, dst, port) -> (bytes sent, bytes received, time) for live link speed


def device_links():
    """Open connections between two of your devices (never to/from the VPN server itself)."""
    try:
        out = subprocess.run(["conntrack", "-L", "-o", "extended,ktimestamp"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return []
    names = {p["ip"].split("/")[0]: n for n, p in load_peers().items()}
    links = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 3 or parts[0] != "ipv4" or parts[2] not in ("tcp", "udp"):
            continue
        if parts[2] == "tcp" and "ESTABLISHED" not in parts:
            continue  # handshakes and closing connections aren't "present"
        if "[UNREPLIED]" in parts:
            continue
        fields = {}
        for k, v in re.findall(r"([a-z-]+)=(\S+)", line):
            fields.setdefault(k, []).append(v)
        src, dst = fields.get("src", [None])[0], fields.get("dst", [None])[0]
        if src not in names or dst not in names:
            continue
        sport, port = int(fields["sport"][0]), int(fields["dport"][0])
        sent = int(fields.get("bytes", ["0"])[0])
        received = int(fields.get("bytes", ["0", "0"])[1]) if len(fields.get("bytes", [])) > 1 else 0
        # Tracking can pick up a connection mid-stream from the server's side's first packet,
        # recording it backwards (e.g. work:3389 -> homepc:61602). The side on a known service
        # port, or the non-temporary port, is the one that was connected to.
        if (sport in SERVICES and port not in SERVICES) or (port >= 49152 > sport):
            src, dst, sport, port, sent, received = dst, src, port, sport, received, sent
        # TCP and UDP to the same service (Remote Desktop uses both) merge into one link.
        key = (names[src], names[dst], port)
        age = int(fields.get("delta-time", ["0"])[0])
        l = links.setdefault(key, {"sent": 0, "received": 0, "seconds": 0})
        l["sent"] += sent
        l["received"] += received
        l["seconds"] = max(l["seconds"], age)
    now = time.time()
    result = []
    for (a, b, port), l in links.items():
        prev = link_samples.get((a, b, port))
        link_samples[(a, b, port)] = (l["sent"], l["received"], now)
        rate_sent = rate_received = 0
        if prev and now > prev[2]:
            rate_sent = max(0, l["sent"] - prev[0]) / (now - prev[2])
            rate_received = max(0, l["received"] - prev[1]) / (now - prev[2])
        result.append({"from": a, "to": b, "port": port, "service": SERVICES.get(port, f"port {port}"),
                       "seconds": l["seconds"], "sent": l["sent"], "received": l["received"],
                       "sent_rate": round(rate_sent), "received_rate": round(rate_received)})
    for key in [k for k in link_samples if k not in links]:
        del link_samples[key]  # forget closed connections
    return sorted(result, key=lambda x: (x["from"], x["to"], x["port"]))


speeds = {}  # name -> {"up": bytes/s, "down": bytes/s, "session_up", "session_down"}; from the device's side
monitor = None


def local_zone(tz):
    try:
        return ZoneInfo(tz) if tz else ZoneInfo("UTC")
    except Exception:
        return ZoneInfo("UTC")


def usage_since(name, since):
    with lock:
        r = db.execute("SELECT COALESCE(SUM(up), 0), COALESCE(SUM(down), 0) FROM usage WHERE peer = ? AND hour >= ?",
                       (name, int(since))).fetchone()
    return {"up": r[0], "down": r[1]}


def day_starts(tz, days):
    """Local midnights (unix time) for the last `days` days, oldest first, plus tomorrow's."""
    z = local_zone(tz)
    today = datetime.now(z).replace(hour=0, minute=0, second=0, microsecond=0)
    return [(today - timedelta(days=d)).timestamp() for d in range(days - 1, -1, -1)] + [(today + timedelta(days=1)).timestamp()]


def traffic_summary(name, tz):
    sp = speeds.get(name, {})
    today = usage_since(name, day_starts(tz, 1)[0])
    return {"down_rate": round(sp.get("down", 0)), "up_rate": round(sp.get("up", 0)),
            "today_down": today["down"], "today_up": today["up"]}


def traffic_detail(name, tz):
    z = local_zone(tz)
    starts = day_starts(tz, 14)
    with lock:
        rows = db.execute("SELECT hour, up, down FROM usage WHERE peer = ? AND hour >= ?", (name, int(starts[0]))).fetchall()
    days = []
    for a, b in zip(starts, starts[1:]):
        up = sum(r["up"] for r in rows if a <= r["hour"] < b)
        down = sum(r["down"] for r in rows if a <= r["hour"] < b)
        days.append({"date": datetime.fromtimestamp(a, z).strftime("%Y-%m-%d"), "up": up, "down": down})
    month_start = datetime.now(z).replace(day=1, hour=0, minute=0, second=0, microsecond=0).timestamp()
    sp = speeds.get(name, {})
    since = monitor.online_since.get(name) if monitor else None
    return {
        "down_rate": round(sp.get("down", 0)), "up_rate": round(sp.get("up", 0)),
        "session": {"seconds": int(time.time() - since), "down": sp.get("session_down", 0), "up": sp.get("session_up", 0)}
                   if since else None,
        "today": usage_since(name, starts[-2]),
        "week": usage_since(name, starts[-8]),
        "month": usage_since(name, month_start),
        "days": days,
    }


class Monitor:
    """Turns WireGuard traffic and handshakes into connected/disconnected/reconnected/new_ip events."""

    def __init__(self):
        self.online_since = {}  # name -> unix time the current session started
        self.seen = {}  # name -> {rx, rx_at, hs, ep}: last observed counters
        self.counters = {}  # name -> (rx, tx, time) for traffic measurement
        self.session_of = {}  # name -> online_since value the session totals belong to
        self.last_fired = {}  # schedule id -> "YYYY-MM-DD HH:MM" already run
        self.last_cleanup = 0
        self.last_domains = 0
        self.observe(seed=True)

    def observe(self, seed=False):
        now = time.time()
        live = dump()
        for name, p in load_peers().items():
            if p["pub"] not in live:
                # Turned off (key removed from wg0): offline right away, and forget its counters
                # so turning it back on starts fresh instead of looking like a tunnel restart.
                self.seen.pop(name, None)
                if name in self.online_since:
                    started = self.online_since.pop(name)
                    # source "app" keeps it out of push alerts; the "disabled" event already covers it
                    log_event(name, "disconnected", f"turned off, online for {fmt_duration(max(0, now - started))}",
                              source="app")
                continue
            hs, ip, endpoint, rx = live[p["pub"]][:4]
            s = self.seen.get(name)
            if s is None:
                # First sight: a recent handshake means it is online right now.
                recent = hs > 0 and now - hs < ONLINE_WINDOW
                s = self.seen[name] = {"rx": rx, "rx_at": now if recent else 0, "hs": hs, "ep": endpoint}
            elif rx != s["rx"]:
                s["rx"], s["rx_at"] = rx, now
            # Received bytes grow with every 25s keepalive, so silence means the tunnel is down.
            online = s["rx_at"] > 0 and now - s["rx_at"] < OFFLINE_AFTER
            was_online = name in self.online_since
            if online and not was_online:
                self.online_since[name] = now
                if not seed:
                    log_event(name, "connected", f"from {ip}" if ip else "")
            elif not online and was_online:
                started = self.online_since.pop(name)
                last = s["rx_at"]
                log_event(name, "disconnected", f"online for {fmt_duration(max(0, last - started))}", ts=last)
            elif online and hs and s["hs"] and hs != s["hs"]:
                # A fresh handshake mid-session from a new port, or sooner than a routine rekey,
                # means the tunnel was restarted (or the device switched networks) in between ticks.
                if endpoint != s["ep"] or hs - s["hs"] < EARLY_HANDSHAKE:
                    self.online_since[name] = now
                    log_event(name, "reconnected", f"from {ip}" if ip else "")
            if hs:
                s["hs"], s["ep"] = hs, endpoint
            if ip:
                self.check_ip(name, ip, seed)
                if online:
                    self.remember_ip(name, ip, s, now)

    def measure(self):
        """Live speed and hourly totals from WireGuard's byte counters (server rx = device upload)."""
        now = time.time()
        live = dump()
        hour = int(now // 3600) * 3600
        rows = []
        for name, p in load_peers().items():
            if p["pub"] not in live:
                self.counters.pop(name, None)
                speeds.pop(name, None)
                continue
            rx, tx = live[p["pub"]][3], live[p["pub"]][4]
            prev = self.counters.get(name)
            self.counters[name] = (rx, tx, now)
            if not prev:
                continue
            # Counters restart from zero when a device is turned back on or wg0 restarts.
            up = rx - prev[0] if rx >= prev[0] else rx
            down = tx - prev[1] if tx >= prev[1] else tx
            sp = speeds.setdefault(name, {})
            dt = max(0.001, now - prev[2])
            sp["up"], sp["down"] = up / dt, down / dt
            since = self.online_since.get(name)
            if self.session_of.get(name) != since:
                self.session_of[name] = since
                sp["session_up"] = sp["session_down"] = 0
            if since:
                sp["session_up"] = sp.get("session_up", 0) + up
                sp["session_down"] = sp.get("session_down", 0) + down
            if up or down:
                rows.append((name, hour, up, down))
        if rows:
            with lock:
                db.executemany("INSERT INTO usage VALUES (?, ?, ?, ?) ON CONFLICT (peer, hour) "
                               "DO UPDATE SET up = up + excluded.up, down = down + excluded.down", rows)
                db.commit()

    def remember_ip(self, name, ip, s, now):
        """Keep each device's latest public address so it can be shown after it goes offline."""
        if ip == s.get("saved_ip") and now - s.get("saved_at", 0) < 300:
            return
        s["saved_ip"], s["saved_at"] = ip, now
        with lock:
            db.execute("INSERT OR REPLACE INTO last_ip VALUES (?, ?, ?)", (name, ip, now))
            db.commit()

    def check_ip(self, name, ip, seed):
        with lock:
            known = {r["ip"] for r in db.execute("SELECT ip FROM known_ips WHERE peer = ?", (name,))}
            if ip in known:
                return
            db.execute("INSERT INTO known_ips VALUES (?, ?, ?)", (name, ip, time.time()))
            db.commit()
        # A device's very first address is not news; later new ones might be a stolen config.
        if known and not seed:
            log_event(name, "new_ip", f"connected from new address {ip}")

    def run_timers(self):
        now = time.time()
        with lock:
            due = db.execute("SELECT * FROM timers WHERE at <= ?", (now,)).fetchall()
            for t in due:
                db.execute("DELETE FROM timers WHERE peer = ?", (t["peer"],))
                db.commit()
                change_peer(t["peer"], t["action"] == "enable", source="timer")

    def run_schedules(self):
        with lock:
            rows = db.execute("SELECT * FROM schedules").fetchall()
        for s in rows:
            try:
                local = datetime.now(ZoneInfo(s["tz"]))
            except Exception:
                local = datetime.now()
            key = local.strftime("%Y-%m-%d %H:%M")
            if local.strftime("%H:%M") != s["time"] or str(local.weekday()) not in s["days"]:
                continue
            if self.last_fired.get(s["id"]) == key:
                continue
            self.last_fired[s["id"]] = key
            change_peer(s["peer"], s["action"] == "enable", source="schedule")

    def refresh_dns(self):
        if time.time() - self.last_domains >= DOMAIN_REFRESH:
            self.last_domains = time.time()
            refresh_domains()

    def cleanup(self):
        if time.time() - self.last_cleanup < 86400:
            return
        self.last_cleanup = time.time()
        with lock:
            db.execute("DELETE FROM events WHERE ts < ?", (time.time() - RETENTION_DAYS * 86400,))
            db.execute("DELETE FROM usage WHERE hour < ?", (time.time() - RETENTION_DAYS * 86400,))
            db.commit()

    def loop(self):
        while True:
            time.sleep(TICK)
            for step in (self.run_timers, self.run_schedules, self.observe, self.measure, self.refresh_dns, self.cleanup):
                try:
                    step()
                except Exception as e:  # keep monitoring even if one step hiccups
                    print(f"monitor: {step.__name__}: {e}", flush=True)


# ---------------------------------------------------------------- http

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
        if hmac.compare_digest(got, f"Bearer {TOKEN}"):
            return True
        self._send(401, {"error": "bad token"})
        return False

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n))
        except ValueError:
            return {}

    def _is_requester(self, peer):
        return peer["ip"].split("/")[0] == self.client_address[0]

    def _calling_peer(self):
        """Name of the device whose VPN address sent this request, or None."""
        for name, p in load_peers().items():
            if self._is_requester(p):
                return name
        return None

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/agent":
            name = self._calling_peer()
            if not name:
                return self._send(403, {"error": "unknown device"})
            with lock:
                r = db.execute("SELECT mode FROM rdp_mode WHERE peer = ?", (name,)).fetchone()
            enabled, keys = ssh_wanted(name)
            return self._send(200, {"name": name, "rdp_mode": r["mode"] if r else "direct",
                                    "ssh_enabled": enabled, "keys": keys, "shell_enabled": shell_wanted(name)})
        if not self._authorized():
            return
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/peers":
            return self._send(200, self.list_peers(q.get("tz")))
        m = re.fullmatch(r"/peers/([^/]+)/traffic", url.path)
        if m:
            if m[1] not in load_peers():
                return self._send(404, {"error": "not found"})
            return self._send(200, traffic_detail(m[1], q.get("tz")))
        if url.path == "/connections":
            return self._send(200, self.connections())
        if url.path == "/events":
            sql, args = "SELECT * FROM events WHERE id > ?", [int(q.get("since", 0))]
            if q.get("peer"):
                sql += " AND peer = ?"
                args.append(q["peer"])
            sql += " ORDER BY id DESC LIMIT ?"
            args.append(min(int(q.get("limit", 100)), 500))
            with lock:
                rows = [dict(r) for r in db.execute(sql, args)]
                latest = db.execute("SELECT COALESCE(MAX(id), 0) FROM events").fetchone()[0]
            return self._send(200, {"events": rows, "latest": latest})
        if url.path == "/schedules":
            with lock:
                rows = [dict(r) for r in db.execute("SELECT * FROM schedules ORDER BY peer, time")]
            return self._send(200, rows)
        self._send(404, {"error": "not found"})

    def connections(self):
        now = time.time()
        online = monitor.online_since if monitor else {}
        out = []
        for name, p in load_peers().items():
            sp = speeds.get(name, {})
            out.append({
                "name": name, "ip": p["ip"], "domain": domains.get(name), "online": name in online,
                "self": self._is_requester(p),
                "since_seconds": int(now - online[name]) if name in online else None,
                "down_rate": round(sp.get("down", 0)), "up_rate": round(sp.get("up", 0)),
                "session_down": sp.get("session_down", 0), "session_up": sp.get("session_up", 0),
            })
        return {"devices": out, "links": device_links()}

    def list_peers(self, tz=None):
        disabled, live, now = load_disabled(), dump(), time.time()
        with lock:
            timers = {r["peer"]: r for r in db.execute("SELECT * FROM timers")}
            last_ips = {r["peer"]: r for r in db.execute("SELECT * FROM last_ip")}
            modes = {r["peer"]: r["mode"] for r in db.execute("SELECT * FROM rdp_mode")}
            agents = {r["peer"]: r for r in db.execute("SELECT * FROM agent_status")}
        out = []
        for name, p in load_peers().items():
            hs, ip = live.get(p["pub"], (0, None))[:2]
            t = timers.get(name)
            if ip and hs and now - hs < ONLINE_WINDOW:
                public = {"ip": ip, "current": True, "ago": 0}
            elif name in last_ips:
                r = last_ips[name]
                public = {"ip": r["ip"], "current": False, "ago": int(now - r["ts"])}
            else:
                public = None
            out.append({
                "name": name,
                "ip": p["ip"],
                "enabled": name not in disabled,
                "self": self._is_requester(p),
                "handshake_ago": int(now - hs) if hs else None,
                "timer": {"action": t["action"], "in": max(0, int(t["at"] - now))} if t else None,
                "public_ip": public,
                "domain": domains.get(name),
                # only for PCs running the helper; "applied" is what the PC reports it actually did
                "agent": self.agent_view(name, modes, agents[name], now) if name in agents else None,
                "traffic": traffic_summary(name, tz),
            })
        return out

    @staticmethod
    def agent_view(name, modes, a, now):
        enabled, wanted = ssh_wanted(name)
        reported = json.loads(a["keys"]) if a["keys"] else []
        keys = wanted if wanted is not None else reported
        return {
            "rdp_mode": modes.get(name, "direct"),
            "applied": a["applied"],
            "sshd": bool(a["sshd"]),
            "ssh_enabled": enabled,
            "error": a["error"] or None,
            "seen_ago": int(now - a["ts"]),
            # each key with whether the PC has actually applied it yet
            "keys": [dict(describe_key(k), applied=k in reported) for k in keys],
            "keys_pending": sorted(set(keys) ^ set(reported)) != [],
            "shell_enabled": shell_wanted(name),
            "shell_applied": None if a["shell"] is None else bool(a["shell"]),
        }

    def do_POST(self):
        if self.path == "/agent/status":
            return self.agent_status(self._body())
        if not self._authorized():
            return
        body = self._body()
        if self.path == "/schedules":
            return self.add_schedule(body)
        m = re.fullmatch(r"/peers/([^/]+)/rdp_mode", self.path)
        if m:
            return self.set_rdp_mode(m[1], body.get("mode"))
        m = re.fullmatch(r"/peers/([^/]+)/ssh", self.path)
        if m:
            return self.set_ssh(m[1], body.get("enabled"))
        m = re.fullmatch(r"/peers/([^/]+)/shell", self.path)
        if m:
            return self.set_shell(m[1], body.get("enabled"))
        m = re.fullmatch(r"/peers/([^/]+)/keys", self.path)
        if m:
            return self.add_key(m[1], body.get("name"), body.get("key"))
        m = re.fullmatch(r"/peers/([^/]+)/(enable|disable)", self.path)
        peers = load_peers()
        if not m or m[1] not in peers:
            return self._send(404, {"error": "not found"})
        name, enable = m[1], m[2] == "enable"
        # Refuse to cut off the device that is making the request - it would lock you out.
        minutes = body.get("minutes")
        if (not enable or minutes) and self._is_requester(peers[name]):
            return self._send(409, {"error": "refusing to disable the device you are using"})
        with lock:
            # A manual change replaces any pending timer for this device.
            db.execute("DELETE FROM timers WHERE peer = ?", (name,))
            if minutes:
                db.execute("INSERT INTO timers VALUES (?, ?, ?)",
                           (name, "disable" if enable else "enable", time.time() + float(minutes) * 60))
            db.commit()
            change_peer(name, enable, source="app")
        self._send(200, {"name": name, "enabled": enable})

    def agent_status(self, b):
        name = self._calling_peer()
        if not name:
            return self._send(403, {"error": "unknown device"})
        reported = b.get("keys")
        # All-or-nothing: a garbled report must never read as "this PC has no keys".
        if not (isinstance(reported, list) and all(isinstance(k, str) for k in reported)):
            reported = None
        with lock:
            prev = db.execute("SELECT applied FROM agent_status WHERE peer = ?", (name,)).fetchone()
            shell = b.get("shell") if isinstance(b.get("shell"), bool) else None
            db.execute("INSERT OR REPLACE INTO agent_status (peer, applied, sshd, error, ts, keys, shell) VALUES (?, ?, ?, ?, ?, ?, ?)",
                       (name, str(b.get("applied", "")), int(bool(b.get("sshd"))), str(b.get("error") or ""), time.time(),
                        json.dumps(reported) if reported is not None else None, None if shell is None else int(shell)))
            db.commit()
        enabled, wanted = ssh_wanted(name)
        if wanted is None and reported is not None:
            # First report from a helper that manages keys: adopt the PC's current keys as the list.
            save_ssh_wanted(name, enabled, [k for k in reported if parse_key(k, k.split()[-1])])
            enabled, wanted = ssh_wanted(name)
        if shell is not None and shell_wanted(name) is None and wanted is not None:
            save_ssh_wanted(name, enabled, wanted, shell)  # likewise adopt the PC's terminal setting
        if prev and prev["applied"] != b.get("applied"):
            label = "tunnel only" if b.get("applied") == "tunnel" else "direct"
            log_event(name, "rdp_mode", f"Remote Desktop access is now {label}", source="agent")
        self._send(200, {"ok": True})

    def set_rdp_mode(self, name, mode):
        if name not in load_peers() or mode not in ("direct", "tunnel"):
            return self._send(400, {"error": "need a known device and mode direct or tunnel"})
        enabled, keys = ssh_wanted(name)
        if mode == "tunnel":
            if keys is not None and not keys:
                return self._send(409, {"error": "add an SSH key first, or nothing could get through the tunnel"})
            if not enabled:
                save_ssh_wanted(name, True, keys)  # tunnel only needs the SSH server
        with lock:
            db.execute("INSERT OR REPLACE INTO rdp_mode VALUES (?, ?, ?)", (name, mode, time.time()))
            db.commit()
        self._send(200, {"name": name, "rdp_mode": mode})

    def _rdp_mode(self, name):
        with lock:
            r = db.execute("SELECT mode FROM rdp_mode WHERE peer = ?", (name,)).fetchone()
        return r["mode"] if r else "direct"

    def set_ssh(self, name, enabled):
        if name not in load_peers() or not isinstance(enabled, bool):
            return self._send(400, {"error": "need a known device and enabled true/false"})
        if not enabled and self._rdp_mode(name) == "tunnel":
            return self._send(409, {"error": "switch Remote Desktop to Direct first; tunnel only needs SSH"})
        _, keys = ssh_wanted(name)
        save_ssh_wanted(name, enabled, keys)
        log_event(name, "ssh", f"SSH server turned {'on' if enabled else 'off'}", source="app")
        self._send(200, {"name": name, "ssh_enabled": enabled})

    def set_shell(self, name, enabled):
        if name not in load_peers() or not isinstance(enabled, bool):
            return self._send(400, {"error": "need a known device and enabled true/false"})
        enabled_ssh, keys = ssh_wanted(name)
        if keys is None:
            return self._send(409, {"error": "the PC's helper hasn't reported yet; try again in a minute"})
        save_ssh_wanted(name, enabled_ssh, keys, enabled)
        log_event(name, "ssh_shell", f"terminal access turned {'on' if enabled else 'off'}", source="app")
        self._send(200, {"name": name, "shell_enabled": enabled})

    def add_key(self, name, key_name, key):
        if name not in load_peers():
            return self._send(404, {"error": "not found"})
        line = parse_key(key or "", key_name or "")
        if not line:
            return self._send(400, {"error": "need a name (letters, digits, . _ -) and a public key line starting ssh-ed25519, ssh-rsa or ecdsa-"})
        enabled, keys = ssh_wanted(name)
        if keys is None:
            return self._send(409, {"error": "the PC's helper hasn't reported its keys yet; try again in a minute"})
        if any(k.split()[-1] == key_name for k in keys):
            return self._send(409, {"error": f"a key named {key_name} already exists"})
        if any(k.split()[1] == line.split()[1] for k in keys):
            return self._send(409, {"error": "that key is already allowed"})
        save_ssh_wanted(name, enabled, keys + [line])
        log_event(name, "ssh_key_added", f"key {key_name} ({describe_key(line)['fingerprint'][:20]}...)", source="app")
        self._send(200, {"name": name, "key": describe_key(line)})

    def remove_key(self, name, key_name):
        enabled, keys = ssh_wanted(name)
        if name not in load_peers() or keys is None or not any(k.split()[-1] == key_name for k in keys):
            return self._send(404, {"error": "no such key"})
        remaining = [k for k in keys if k.split()[-1] != key_name]
        if not remaining and self._rdp_mode(name) == "tunnel":
            return self._send(409, {"error": "that's the last key and Remote Desktop is tunnel only; switch to Direct first"})
        save_ssh_wanted(name, enabled, remaining)
        log_event(name, "ssh_key_removed", f"key {key_name}", source="app")
        self._send(200, {"removed": key_name})

    def add_schedule(self, b):
        peers = load_peers()
        if b.get("peer") not in peers or b.get("action") not in ("enable", "disable"):
            return self._send(400, {"error": "need peer and action"})
        if not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", str(b.get("time", ""))):
            return self._send(400, {"error": "time must be HH:MM"})
        days = "".join(sorted(set(re.sub(r"[^0-6]", "", str(b.get("days", "0123456")))))) or "0123456"
        if b["action"] == "disable" and self._is_requester(peers[b["peer"]]):
            return self._send(409, {"error": "refusing to schedule turning off the device you are using"})
        with lock:
            cur = db.execute("INSERT INTO schedules (peer, action, time, days, tz) VALUES (?, ?, ?, ?, ?)",
                             (b["peer"], b["action"], b["time"], days, b.get("tz") or "UTC"))
            db.commit()
        self._send(200, {"id": cur.lastrowid})

    def do_DELETE(self):
        if not self._authorized():
            return
        k = re.fullmatch(r"/peers/([^/]+)/keys/([^/]+)", self.path)
        if k:
            return self.remove_key(k[1], k[2])
        m = re.fullmatch(r"/schedules/(\d+)", self.path)
        if not m:
            return self._send(404, {"error": "not found"})
        with lock:
            db.execute("DELETE FROM schedules WHERE id = ?", (int(m[1]),))
            db.commit()
        self._send(200, {"deleted": int(m[1])})


if __name__ == "__main__":
    TOKEN = open(TOKEN_FILE).read().strip()
    db = open_db()
    apply_state()
    ensure_flow_tracking()
    refresh_domains()
    monitor = Monitor()
    threading.Thread(target=monitor.loop, daemon=True).start()
    ThreadingHTTPServer(LISTEN, Handler).serve_forever()

"""Tiny HTTP API to enable/disable individual WireGuard peers.

Listens only on the tunnel address, so it is unreachable from the internet.
Disabling a peer removes exactly that peer's public key from wg0; every
other peer is untouched. Disabled peers are remembered across reboots.

A background monitor records connect/disconnect/new-IP events and runs
timers ("on for 2 hours") and daily schedules ("off at 01:00").

  GET    /peers                     -> [{name, ip, domain, enabled, self, handshake_ago, timer, public_ip}]
  POST   /peers/<name>/enable       body {"minutes": N} optional: revert after N minutes
  POST   /peers/<name>/disable      body {"minutes": N} optional
  GET    /events?since=ID&peer=NAME&limit=N
  GET    /schedules
  POST   /schedules                 body {peer, action: enable|disable, time: "HH:MM", days: "0123456", tz}
  DELETE /schedules/<id>

Every request needs header "Authorization: Bearer <token>" (token in TOKEN_FILE).
"""
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
from datetime import datetime
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
    """{pubkey: (latest handshake unix time, endpoint ip, endpoint ip:port, bytes received)}."""
    out = {}
    for row in wg("show", IFACE, "dump").splitlines()[1:]:
        cols = row.split("\t")
        endpoint = None if cols[2] == "(none)" else cols[2]
        ip = endpoint.rsplit(":", 1)[0].strip("[]") if endpoint else None
        out[cols[0]] = (int(cols[4]), ip, endpoint, int(cols[5]))
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
        CREATE TABLE IF NOT EXISTS last_ip (
            peer TEXT PRIMARY KEY, ip TEXT, ts REAL);
        CREATE TABLE IF NOT EXISTS schedules (
            id INTEGER PRIMARY KEY, peer TEXT, action TEXT, time TEXT, days TEXT, tz TEXT);
    """)
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


def push(peer, kind, detail, source):
    """Instant phone notification via ntfy, if NTFY_FILE configures a topic."""
    try:
        cfg = json.load(open(NTFY_FILE))
    except (FileNotFoundError, ValueError):
        return
    security = kind == "new_ip"
    # Same rules as the app: skip changes you made yourself and your own phone's comings and goings.
    if not security and (source == "app" or peer in cfg.get("quiet_peers", [])):
        return
    by = {"timer": " (timer)", "schedule": " (schedule)"}.get(source, "")
    title = {
        "connected": f"{peer} connected",
        "disconnected": f"{peer} disconnected",
        "reconnected": f"{peer} reconnected",
        "new_ip": f"{peer} connected from a new address",
        "enabled": f"{peer} turned on{by}",
        "disabled": f"{peer} turned off{by}",
    }.get(kind, f"{peer}: {kind}")
    body = detail + (". If this wasn't you, turn the device off in WG Switch." if security else "")
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

class Monitor:
    """Turns WireGuard traffic and handshakes into connected/disconnected/reconnected/new_ip events."""

    def __init__(self):
        self.online_since = {}  # name -> unix time the current session started
        self.seen = {}  # name -> {rx, rx_at, hs, ep}: last observed counters
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
            hs, ip, endpoint, rx = live[p["pub"]]
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
            db.commit()

    def loop(self):
        while True:
            time.sleep(TICK)
            for step in (self.run_timers, self.run_schedules, self.observe, self.refresh_dns, self.cleanup):
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

    def do_GET(self):
        if not self._authorized():
            return
        url = urlparse(self.path)
        if url.path == "/peers":
            return self._send(200, self.list_peers())
        if url.path == "/events":
            q = {k: v[0] for k, v in parse_qs(url.query).items()}
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

    def list_peers(self):
        disabled, live, now = load_disabled(), dump(), time.time()
        with lock:
            timers = {r["peer"]: r for r in db.execute("SELECT * FROM timers")}
            last_ips = {r["peer"]: r for r in db.execute("SELECT * FROM last_ip")}
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
            })
        return out

    def do_POST(self):
        if not self._authorized():
            return
        body = self._body()
        if self.path == "/schedules":
            return self.add_schedule(body)
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
    refresh_domains()
    threading.Thread(target=Monitor().loop, daemon=True).start()
    ThreadingHTTPServer(LISTEN, Handler).serve_forever()

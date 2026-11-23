"""Blink's loopback staging API. Requires Python 3.6+ and SQLite."""
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
import uuid
from contextlib import closing
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

DB = os.environ.get("BLINK_DB", "/var/lib/blink-staging/blink.sqlite3")
SESSION_SECONDS = 30 * 86400
LIMIT = 200
SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
 id INTEGER PRIMARY KEY, name TEXT UNIQUE NOT NULL, salt BLOB NOT NULL, digest BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS sessions (
 token BLOB PRIMARY KEY, owner INTEGER NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS records (
 seq INTEGER PRIMARY KEY AUTOINCREMENT, owner INTEGER NOT NULL, record_id TEXT NOT NULL,
 kind INTEGER, occurred INTEGER, deleted INTEGER NOT NULL, UNIQUE(owner,record_id));
CREATE INDEX IF NOT EXISTS record_changes ON records(owner,seq);
"""


class Problem(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def check(ok, msg="invalid_request"):
    if not ok:
        raise Problem(400, msg)


def initialize():
    with closing(sqlite3.connect(DB)) as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript(SCHEMA)


def token_hash(token):
    return hashlib.sha256(token.encode()).digest()


def derive(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 600000)


def account(db, path, body):
    name, password = body.get("name"), body.get("password")
    check(isinstance(name, str) and 1 <= len(name.strip()) <= 64)
    check(isinstance(password, str) and 10 <= len(password) <= 128)
    name = name.strip().casefold()
    now = int(time.time())
    if path == "/v1/register":
        salt = secrets.token_bytes(16)
        digest = derive(password, salt)
        try:
            owner = db.execute("INSERT INTO users(name,salt,digest) VALUES(?,?,?)",
                               (name, salt, digest)).lastrowid
        except sqlite3.IntegrityError:
            raise Problem(409, "name_unavailable")
    else:
        row = db.execute("SELECT id,salt,digest FROM users WHERE name=?", (name,)).fetchone()
        digest = derive(password, row[1] if row else bytes(16))
        if not row or not hmac.compare_digest(digest, row[2]):
            raise Problem(401, "invalid_credentials")
        owner = row[0]
    token = secrets.token_urlsafe(32)
    db.execute("DELETE FROM sessions WHERE expires<=?", (now,))
    db.execute("INSERT INTO sessions VALUES(?,?,?)", (token_hash(token), owner, now+SESSION_SECONDS))
    return {"token": token, "expires": now+SESSION_SECONDS}


def sync(db, owner, body):
    changes, cursor = body.get("records", []), body.get("cursor", 0)
    check(type(cursor) is int and 0 <= cursor < 2**63)
    check(isinstance(changes, list) and len(changes) <= LIMIT)
    db.execute("BEGIN IMMEDIATE")
    for item in changes:
        check(isinstance(item, dict))
        rid, deleted = item.get("id"), item.get("deleted", False)
        try:
            rid = str(uuid.UUID(rid)) if isinstance(rid, str) else None
        except ValueError:
            rid = None
        check(rid and type(deleted) is bool)
        old = db.execute("SELECT kind,occurred,deleted FROM records WHERE owner=? AND record_id=?",
                         (owner, rid)).fetchone()
        kind, occurred = item.get("k"), item.get("at")
        if not deleted:
            check(type(kind) is int and kind in (0, 1, 2))
            check(type(occurred) is int and 0 <= occurred <= 253402300799999)
        if old:
            if old[2]:
                continue  # A revoked check-in cannot be resurrected by an offline device.
            if not deleted:
                if old[:2] != (kind, occurred):
                    raise Problem(409, "record_id_conflict")
                continue
            db.execute("DELETE FROM records WHERE owner=? AND record_id=?", (owner, rid))
        db.execute("INSERT INTO records(owner,record_id,kind,occurred,deleted) VALUES(?,?,?,?,?)",
                   (owner, rid, None if deleted else kind, None if deleted else occurred, int(deleted)))
    rows = db.execute("SELECT seq,record_id,kind,occurred,deleted FROM records "
                      "WHERE owner=? AND seq>? ORDER BY seq LIMIT ?", (owner, cursor, LIMIT+1)).fetchall()
    page = rows[:LIMIT]
    return {"records": [{"id": r[1], "deleted": True} if r[4] else
                        {"id": r[1], "k": r[2], "at": r[3]} for r in page],
            "cursor": page[-1][0] if page else cursor, "more": len(rows) > LIMIT}


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def reply(self, status, obj):
        data = json.dumps(obj, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/healthz":
            self.reply(200, {"service": "blink", "status": "ok"})
        else:
            self.reply(404, {"error": "not_found"})

    def do_POST(self):
        try:
            if self.path not in ("/v1/register", "/v1/login", "/v1/logout", "/v1/sync"):
                raise Problem(404, "not_found")
            check(self.headers.get_content_type() == "application/json")
            length = int(self.headers.get("Content-Length", "0"))
            check(0 < length <= 131072)
            body = json.loads(self.rfile.read(length).decode())
            check(isinstance(body, dict))
            with closing(sqlite3.connect(DB, timeout=10)) as db, db:
                if self.path in ("/v1/register", "/v1/login"):
                    result = account(db, self.path, body)
                else:
                    auth = self.headers.get("Authorization", "")
                    key = token_hash(auth[7:]) if auth.startswith("Bearer ") else b""
                    row = db.execute("SELECT owner FROM sessions WHERE token=? AND expires>?",
                                     (key, int(time.time()))).fetchone()
                    if not row:
                        raise Problem(401, "invalid_session")
                    if self.path == "/v1/logout":
                        db.execute("DELETE FROM sessions WHERE token=?", (key,))
                        result = {"ok": True}
                    else:
                        result = sync(db, row[0], body)
            self.reply(200, result)
        except Problem as exc:
            self.reply(exc.status, {"error": exc.message})
        except (ValueError, UnicodeError, RecursionError):
            self.reply(400, {"error": "invalid_request"})
        except sqlite3.OperationalError:
            self.reply(503, {"error": "storage_unavailable"})


class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    initialize()
    Server(("127.0.0.1", int(os.environ.get("BLINK_PORT", "18765"))), Handler).serve_forever()

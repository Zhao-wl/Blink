"""Versioned account data shared by Blink devices (works on the staging Python 3.6 runtime)."""
import collections
import datetime
import hashlib
import hmac
import json
import re
import secrets
import threading
import time
import uuid

SCHEMA = """
CREATE TABLE IF NOT EXISTS profiles (owner INTEGER PRIMARY KEY, account TEXT UNIQUE NOT NULL, recovery BLOB);
CREATE TABLE IF NOT EXISTS devices (token BLOB PRIMARY KEY, device TEXT NOT NULL, label TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS objects (
 rev INTEGER PRIMARY KEY AUTOINCREMENT, owner INTEGER NOT NULL, key TEXT NOT NULL, value TEXT,
 mutation TEXT NOT NULL, UNIQUE(owner,key));
CREATE INDEX IF NOT EXISTS object_updates ON objects(owner,rev);
CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS reminder_devices (
 owner INTEGER PRIMARY KEY, device TEXT, requested TEXT, expires INTEGER NOT NULL,
 revision INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS pairs (
 code TEXT PRIMARY KEY, secret BLOB UNIQUE NOT NULL, device TEXT NOT NULL, label TEXT NOT NULL,
 platform TEXT NOT NULL, expires INTEGER NOT NULL, owner INTEGER, token TEXT);
"""
FEATURES = ["day", "presence", "pair"]
RECENT_MS = 3 * 60 * 1000
PAIR_SECONDS = 120
PLATFORMS = ("android", "desktop", "ios", "web")
attempts = collections.OrderedDict()
attempt_lock = threading.Lock()


def migrate(db):
    """Adds presence columns to devices created by earlier releases."""
    columns = {row[1] for row in db.execute("PRAGMA table_info(devices)")}
    for name, kind in (("platform", "TEXT"), ("active", "INTEGER"), ("exclusive", "INTEGER"), ("seen", "INTEGER")):
        if name not in columns:
            db.execute("ALTER TABLE devices ADD COLUMN %s %s" % (name, kind))


def allowed(peer):
    now = time.monotonic()
    with attempt_lock:
        times = [stamp for stamp in attempts.pop(peer, []) if stamp > now-60]
        if len(attempts) >= 4096:
            attempts.popitem(last=False)
        attempts[peer] = times
        if len(times) >= 20:
            return False
        times.append(now)
        return True


def is_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def valid_day(value):
    try:
        return datetime.datetime.strptime(value, "%Y-%m-%d").strftime("%Y-%m-%d") == value
    except (TypeError, ValueError):
        return False


def number(v, lo, hi):
    return type(v) is int and lo <= v <= hi


def hm(value):
    return isinstance(value, str) and re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", value)


def ranges(items):
    out = []
    for a, b in sorted(items):
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return out


def valid(key, value):
    parts = key.split("/")
    kind = parts[0]
    if kind == "hours" and len(parts) == 1:
        return (isinstance(value, list) and len(value) == 6 and all(hm(x) for x in value)
                and value[0] <= value[1] <= value[2] <= value[3] and value[0] < value[3]
                and value[2] <= value[4] <= value[5])
    if kind == "pref" and len(parts) == 2 and parts[1] in ("eye", "screen", "water", "rest", "length"):
        return number(value, 0 if parts[1] != "length" else 1, 1440)
    if kind == "block" and len(parts) == 2 and is_uuid(parts[1]):
        return value is None or (isinstance(value, dict) and value.get("id") == parts[1]
            and valid_day(value.get("day")) and hm(value.get("at"))
            and isinstance(value.get("title"), str) and 0 < len(value["title"]) <= 100
            and number(value.get("minutes"), 1, 1440) and type(value.get("screen")) is bool
            and type(value.get("done", False)) is bool)
    if len(parts) < 2 or not valid_day(parts[1]):
        return False
    if kind == "day" and len(parts) == 2:
        return value is None or (isinstance(value, dict) and set(value) <= {"start", "end", "off", "work"}
            and all(hm(value[k]) for k in ("start", "end") if k in value)
            and all(type(value[k]) is bool for k in ("off", "work") if k in value))
    if kind == "baseline" and len(parts) == 2:
        return isinstance(value, list) and len(value) == 2 and all(number(x, 0, 86400000) for x in value)
    if kind == "correction" and len(parts) == 2:
        return number(value, -86400000, 86400000)
    if kind == "shift" and len(parts) == 2:
        if value is None:
            return True
        start = int(datetime.datetime.strptime(parts[1], "%Y-%m-%d").replace(
            tzinfo=datetime.timezone(datetime.timedelta(hours=8))).timestamp()*1000)
        return (isinstance(value, dict) and type(value.get("work")) is bool and
                number(value.get("from"), start, start+86400000-1) and
                number(value.get("until"), value["from"]+1, start+86400000))
    if kind == "spans" and len(parts) == 3 and is_uuid(parts[2]):
        return isinstance(value, list) and len(value) <= 4096 and all(
            isinstance(x, list) and len(x) == 2 and number(x[0], 0, 86399999) and
            number(x[1], x[0]+1, 86400000) for x in value)
    return False


def sync_objects(db, owner, body, check):
    changes, cursor = body.get("changes", []), body.get("cursor", 0)
    check(isinstance(changes, list) and len(changes) <= 100 and number(cursor, 0, 2**63-1))
    epoch = db.execute("SELECT value FROM metadata WHERE name='epoch'").fetchone()[0]
    if body.get("epoch") and body["epoch"] != epoch:
        return {"reset": True, "epoch": epoch}
    conflicts, accepted = [], []
    for change in changes:
        check(isinstance(change, dict))
        key, v, mid, base = (change.get(k) for k in ("key", "value", "mutation", "base"))
        check(isinstance(key, str) and len(key) <= 100 and valid(key, v) and is_uuid(mid) and number(base, 0, 2**63-1))
        row = db.execute("SELECT rev,value,mutation FROM objects WHERE owner=? AND key=?", (owner,key)).fetchone()
        rev, current, previous = (row[0], json.loads(row[1]), row[2]) if row else (0,None,None)
        if row and (mid == previous or v == current):
            accepted.append(mid)
            continue
        if key.startswith("spans/"):
            # A device owns its spans: replace when it saw the latest copy, otherwise merge.
            v = ranges(v if base == rev else (current or []) + v)
        elif base != rev:
            conflicts.append({"key":key,"value":current,"rev":rev,"mutation":mid})
            continue
        db.execute("DELETE FROM objects WHERE owner=? AND key=?", (owner,key))
        db.execute("INSERT INTO objects(owner,key,value,mutation) VALUES(?,?,?,?)",
                   (owner,key,json.dumps(v,separators=(',',':')),mid))
        accepted.append(mid)
    rows = db.execute("SELECT rev,key,value FROM objects WHERE owner=? AND rev>? ORDER BY rev LIMIT 101",
                      (owner,cursor)).fetchall()
    return {"changes":[{"rev": r, "key": k, "value": json.loads(v)} for r,k,v in rows[:100]],
            "cursor":rows[min(len(rows),100)-1][0] if rows else cursor,"more":len(rows)>100,
            "accepted":accepted,"conflicts":conflicts,"epoch":epoch,"features":FEATURES}


def identity(db, owner):
    db.execute("INSERT OR IGNORE INTO profiles(owner,account) VALUES(?,?)",(owner,str(uuid.uuid4())))
    return db.execute("SELECT account FROM profiles WHERE owner=?",(owner,)).fetchone()[0]


def recovery(db, owner):
    code=secrets.token_urlsafe(24)
    db.execute("UPDATE profiles SET recovery=? WHERE owner=?",(hashlib.sha256(code.encode()).digest(),owner))
    return code


def revoke_sessions(db,owner,keep=b""):
    db.execute("DELETE FROM devices WHERE token IN (SELECT token FROM sessions WHERE owner=? AND token!=?)",(owner,keep))
    db.execute("DELETE FROM sessions WHERE owner=? AND token!=?",(owner,keep))


def attach_device(db, owner, token, body, check):
    device = body.get("device", str(uuid.uuid4()))
    label = body.get("label", "Android")
    platform = body.get("platform", "android")
    check(is_uuid(device) and isinstance(label, str) and len(label) <= 80 and platform in PLATFORMS)
    db.execute("DELETE FROM sessions WHERE owner=? AND token IN (SELECT token FROM devices WHERE device=?)", (owner, device))
    db.execute("DELETE FROM devices WHERE token NOT IN (SELECT token FROM sessions)")
    now = int(time.time()*1000)
    db.execute("INSERT INTO devices(token,device,label,platform,active,exclusive,seen) VALUES(?,?,?,?,?,0,?)",
               (token, device, label, platform, now, now))


def pairing(db, path, body, check, problem, token_hash, session_seconds):
    """A new device shows a 6-digit code; a signed-in device approves it; the new device polls."""
    now = int(time.time())
    db.execute("DELETE FROM pairs WHERE expires<=?", (now - 60,))
    if path == "/v2/pair/new":
        device, label, platform = body.get("device"), body.get("label", "新设备"), body.get("platform", "android")
        check(is_uuid(device) and isinstance(label, str) and len(label) <= 80 and platform in PLATFORMS)
        for _ in range(20):
            code = "%06d" % secrets.randbelow(1000000)
            if not db.execute("SELECT 1 FROM pairs WHERE code=?", (code,)).fetchone():
                break
        else:
            raise problem(429, "try_later")
        secret = secrets.token_urlsafe(32)
        db.execute("INSERT INTO pairs(code,secret,device,label,platform,expires) VALUES(?,?,?,?,?,?)",
                   (code, token_hash(secret), device, label, platform, now + PAIR_SECONDS))
        return {"code": code, "secret": secret, "expires": PAIR_SECONDS}
    secret = body.get("secret")
    check(isinstance(secret, str) and len(secret) <= 100)
    row = db.execute("SELECT code,expires,owner,token FROM pairs WHERE secret=?", (token_hash(secret),)).fetchone()
    if not row:
        raise problem(410, "pair_unknown")
    code, expires, owner, token = row
    if owner is None:
        if expires <= now:
            raise problem(410, "pair_expired")
        return {"pending": True}
    db.execute("DELETE FROM pairs WHERE code=?", (code,))
    account_id = db.execute("SELECT account FROM profiles WHERE owner=?", (owner,)).fetchone()[0]
    name = db.execute("SELECT name FROM users WHERE id=?", (owner,)).fetchone()[0]
    return {"token": token, "expires": now + session_seconds, "account": account_id, "name": name}


def approve(db, owner, body, check, problem, token_hash, session_seconds):
    code = body.get("code")
    check(isinstance(code, str) and re.fullmatch(r"[0-9]{6}", code))
    now = int(time.time())
    row = db.execute("SELECT device,label,platform FROM pairs WHERE code=? AND expires>? AND owner IS NULL",
                     (code, now)).fetchone()
    if not row:
        raise problem(404, "pair_unknown")
    device, label, platform = row
    token = secrets.token_urlsafe(32)
    db.execute("INSERT INTO sessions VALUES(?,?,?)", (token_hash(token), owner, now + session_seconds))
    identity(db, owner)
    attach_device(db, owner, token_hash(token), {"device": device, "label": label, "platform": platform}, check)
    db.execute("UPDATE pairs SET owner=?, token=? WHERE code=?", (owner, token, code))
    return {"label": label}


def decide(me, devices, now):
    """The device in use reminds (desktop first); with nobody active, the most recent phone does."""
    if any(d["exclusive"] for d in devices):
        return me["exclusive"]
    recent = [d for d in devices if now - d["active"] <= RECENT_MS]
    if not recent:
        phones = [d for d in devices if d["platform"] != "desktop"] or devices
        return me in phones and me["active"] >= max(d["active"] for d in phones)
    desktops = [d for d in recent if d["platform"] == "desktop"]
    pool = desktops or recent
    return me in pool and me["active"] >= max(d["active"] for d in pool)


def presence(db, owner, token, body, check):
    """Records this device's activity and says whether it should show a work reminder now."""
    idle, round_id = body.get("idle", 0), body.get("round")
    platform, exclusive = body.get("platform"), body.get("exclusive", False)
    check(number(idle, 0, 400 * 86400000) and (round_id is None or is_uuid(round_id))
          and (platform is None or platform in PLATFORMS) and type(exclusive) is bool)
    now = int(time.time() * 1000)
    check(db.execute("SELECT device FROM devices WHERE token=?", (token,)).fetchone() is not None)
    db.execute("UPDATE devices SET active=max(coalesce(active,0),?), seen=?, exclusive=?, platform=coalesce(?,platform) "
               "WHERE token=?", (now - idle, now, int(exclusive), platform, token))
    rows = db.execute("SELECT d.token,d.device,d.label,coalesce(d.platform,'android'),coalesce(d.active,0),coalesce(d.exclusive,0) "
                      "FROM devices d JOIN sessions s ON s.token=d.token WHERE s.owner=? AND s.expires>?",
                      (owner, now // 1000)).fetchall()
    devices = [{"token": t, "id": d, "label": l, "platform": p, "active": a, "exclusive": bool(x)} for t, d, l, p, a, x in rows]
    me = next(d for d in devices if d["token"] == token)
    confirmed = bool(round_id and db.execute(
        "SELECT 1 FROM records WHERE owner=? AND record_id=? AND deleted=0", (owner, round_id)).fetchone())
    return {"deliver": decide(me, devices, now) and not confirmed, "confirmed": confirmed, "serverTime": now,
            "devices": [{"id": d["id"], "label": d["label"], "platform": d["platform"], "active": d["active"],
                         "exclusive": d["exclusive"], "current": d["token"] == token} for d in devices]}


def manage(db, owner, key, path, body, check, problem, derive, token_hash=None, session_seconds=0):
    if path == "/v2/presence":
        return presence(db, owner, key, body, check)
    if path == "/v2/pair/approve":
        return approve(db, owner, body, check, problem, token_hash, session_seconds)
    if path == "/v2/rename":
        device, label = body.get("device"), body.get("label")
        check(is_uuid(device) and isinstance(label, str) and 0 < len(label.strip()) <= 40)
        db.execute("UPDATE devices SET label=? WHERE device=? AND token IN (SELECT token FROM sessions WHERE owner=?)",
                   (label.strip(), device, owner))
        return {"ok": True}
    if path == "/v2/devices":
        rows=db.execute("SELECT coalesce(d.device,''),coalesce(d.label,'旧版设备'),s.expires,s.token=?,"
                        "coalesce(d.platform,'android'),coalesce(d.active,0),coalesce(d.exclusive,0) "
                        "FROM sessions s LEFT JOIN devices d ON d.token=s.token WHERE s.owner=? AND s.expires>?",
                        (key,owner,int(time.time()))).fetchall()
        devices=[dict(id=i,label=n,expires=e,current=bool(c)) for i,n,e,c,_,_,_ in rows]
        # Older clients asking for the retired reminder lease get the legacy shape: they remind locally.
        if "action" in body:
            return {"devices":devices}
        for d,row in zip(devices,rows):
            d.update(platform=row[4],active=row[5],exclusive=bool(row[6]))
        return {"devices":devices}
    if path == "/v2/revoke":
        device=body.get("device")
        check(is_uuid(device))
        db.execute("DELETE FROM sessions WHERE owner=? AND token IN (SELECT token FROM devices WHERE device=?)",(owner,device))
        db.execute("DELETE FROM devices WHERE token NOT IN (SELECT token FROM sessions)")
        return {"ok":True}
    check(path in ("/v2/recovery-code","/v2/password","/v2/delete"))
    password=body.get("password")
    check(isinstance(password,str) and len(password)<=128)
    salt, digest=db.execute("SELECT salt,digest FROM users WHERE id=?",(owner,)).fetchone()
    if not hmac.compare_digest(digest, derive(password,salt)):
        raise problem(401,"invalid_credentials")
    if path=="/v2/recovery-code":
        return {"recovery":recovery(db,owner)}
    if path=="/v2/password":
        new=body.get("newPassword")
        check(isinstance(new,str) and 8<=len(new)<=128)
        salt=secrets.token_bytes(16)
        db.execute("UPDATE users SET salt=?,digest=? WHERE id=?",(salt,derive(new,salt),owner))
        revoke_sessions(db,owner,key)
        return {"recovery":recovery(db,owner)}
    revoke_sessions(db,owner)
    db.execute("DELETE FROM pairs WHERE owner=?",(owner,))
    for table in ("records","objects","profiles","reminder_devices"):
        db.execute("DELETE FROM "+table+" WHERE owner=?",(owner,))
    db.execute("DELETE FROM users WHERE id=?",(owner,))
    return {"ok":True}

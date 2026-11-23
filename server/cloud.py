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
"""
attempts = collections.OrderedDict()
attempt_lock = threading.Lock()


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
            v = ranges((current or []) + v)
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
            "accepted":accepted,"conflicts":conflicts,"epoch":epoch}


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


def manage(db, owner, key, path, body, check, problem, derive):
    if path == "/v2/devices":
        rows=db.execute("SELECT coalesce(d.device,''),coalesce(d.label,'旧版设备'),s.expires,s.token=? "
                        "FROM sessions s LEFT JOIN devices d ON d.token=s.token WHERE s.owner=? AND s.expires>?",
                        (key,owner,int(time.time()))).fetchall()
        return {"devices":[dict(id=i,label=n,expires=e,current=bool(c)) for i,n,e,c in rows]}
    if path == "/v2/revoke":
        device=body.get("device")
        check(is_uuid(device))
        db.execute("DELETE FROM sessions WHERE owner=? AND token IN (SELECT token FROM devices WHERE device=?)",(owner,device))
        db.execute("DELETE FROM devices WHERE token NOT IN (SELECT token FROM sessions)")
        return {"ok":True}
    password=body.get("password")
    check(isinstance(password,str) and len(password)<=128)
    salt, digest=db.execute("SELECT salt,digest FROM users WHERE id=?",(owner,)).fetchone()
    if not hmac.compare_digest(digest, derive(password,salt)):
        raise problem(401,"invalid_credentials")
    if path=="/v2/recovery-code":
        return {"recovery":recovery(db,owner)}
    if path=="/v2/password":
        new=body.get("newPassword")
        check(isinstance(new,str) and 10<=len(new)<=128)
        salt=secrets.token_bytes(16)
        db.execute("UPDATE users SET salt=?,digest=? WHERE id=?",(salt,derive(new,salt),owner))
        revoke_sessions(db,owner,key)
        return {"recovery":recovery(db,owner)}
    check(path=="/v2/delete")
    revoke_sessions(db,owner)
    for table in ("records","objects","profiles"):
        db.execute("DELETE FROM "+table+" WHERE owner=?",(owner,))
    db.execute("DELETE FROM users WHERE id=?",(owner,))
    return {"ok":True}

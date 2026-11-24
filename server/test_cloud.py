import hashlib
import json
from pathlib import Path
from unittest.mock import patch
from urllib.request import Request
from urllib.error import HTTPError
import test_api
import secrets
import uuid
import cloud
import time


class ExtendedTest(test_api.SyncTest):
    # Exercise the same real HTTP/SQLite harness without duplicating the v1 cases.
    test_two_devices = None
    test_pagination_and_atomic_rejection = None

    def setUp(self):
        super().setUp()
        cloud.attempts.clear()
        self.device=str(uuid.uuid4())
        self.creds={"name":"extended","password":secrets.token_urlsafe(),"device":self.device}
        self.auth=self.post("register",self.creds)
        self.token=self.auth["token"]

    def v2(self,path,body,token=None):
        if path=="reminders":
            path="devices"
            body={"action":"status",**body}
        return self.post("/v2/"+path,body,self.token if token is None else token)

    def test_preferences_and_intervals(self):
        def change(key,value,base=0):
            return dict(key=key,value=value,base=base,mutation=str(uuid.uuid4()))
        initial=change("pref/eye",20)
        first=self.v2("sync",{"changes":[initial]})
        rev=first["cursor"]
        self.assertEqual(first["changes"][0]["value"],20)
        self.assertEqual(self.v2("sync",{"changes":[initial],"cursor":rev})["changes"],[])
        changed=change("pref/eye",30)
        self.assertEqual(self.v2("sync",{"changes":[changed]})["conflicts"][0]["rev"],rev)
        key="spans/2026-11-23/"+self.device
        self.v2("sync",{"changes":[change(key,[[0,100]])]})
        end=self.v2("sync",{"changes":[change(key,[[50,150]])],"cursor":rev})
        self.assertEqual(end["changes"][0]["value"],[[0,150]])
        self.assertTrue(self.v2("sync",{"epoch":"previous"})["reset"])

    def test_encrypted_backups_require_separate_access(self):
        root = Path(self.tmp.name)
        access = secrets.token_urlsafe()
        digest = hashlib.sha256(access.encode()).hexdigest()
        (root/"access").write_text(digest)
        (root/"encrypted").mkdir()
        (root/"encrypted"/"2026-11-23.cms").write_bytes(b"encrypted sample")
        url = "http://127.0.0.1:%s/ops/backups" % self.server.server_port
        with patch("api.BACKUP_ACCESS_FILE",str(root/"access")):
            with self.assertRaises(HTTPError) as error:
                self.http.open(Request(url,headers={"Authorization":"Bearer "+self.token}))
            self.assertEqual(error.exception.code,404)
            headers = {"Authorization":"Bearer "+access}
            with self.http.open(Request(url,headers=headers)) as result:
                self.assertEqual(json.load(result)["dates"],["2026-11-23"])
            with self.http.open(Request(url+"/2026-11-23",headers=headers)) as result:
                body = result.read()
                self.assertEqual(body,b"encrypted sample")
                self.assertEqual(result.headers["X-Checksum-SHA256"],hashlib.sha256(body).hexdigest())

    def test_two_confirmations_for_one_work_reminder(self):
        reminder = {"id":"b11e0000-0000-8000-8002-000001c800ff","k":2,"at":1000}
        first = self.post("sync",{"records":[reminder]},self.token)
        replay = self.post("sync",{"cursor":first["cursor"],"records":[dict(reminder,at=3000)]},self.token)
        self.assertEqual(replay["canonical"],[reminder])
        self.assertEqual(self.post("sync",{},self.token)["records"],[reminder])

    def test_the_device_in_use_reminds(self):
        phone, desktop = self.token, self.post("login", dict(self.creds, device=str(uuid.uuid4())))["token"]
        # Desktop in use: the phone stays quiet, the desktop reminds.
        self.assertTrue(self.v2("presence", {"idle": 1000, "platform": "desktop"}, desktop)["deliver"])
        self.assertFalse(self.v2("presence", {"idle": 0, "platform": "android"}, phone)["deliver"])
        # Desktop idle for 10 minutes: the active phone takes over.
        self.v2("presence", {"idle": 600000, "platform": "desktop"}, desktop)
        with patch("cloud.time.time", return_value=time.time() + 600):
            self.assertTrue(self.v2("presence", {"idle": 0, "platform": "android"}, phone)["deliver"])
            self.assertFalse(self.v2("presence", {"idle": 900000, "platform": "desktop"}, desktop)["deliver"])
        # A confirmed round reminds nobody, and "only this device" wins.
        round_id = "b11e0000-0000-8000-8000-000001c800ff"
        self.post("sync", {"records": [{"id": round_id, "k": 0, "at": 1000}]}, phone)
        self.assertTrue(self.v2("presence", {"idle": 0, "round": round_id}, desktop)["confirmed"])
        self.v2("presence", {"idle": 0, "exclusive": True}, phone)
        self.assertFalse(self.v2("presence", {"idle": 0}, desktop)["deliver"])
        listed = self.v2("devices", {})["devices"]
        self.assertTrue(any(d["exclusive"] for d in listed))
        # Retired lease calls from older clients get the legacy shape and remind locally.
        legacy = self.v2("reminders", {})
        self.assertNotIn("revision", legacy)
        self.assertTrue(all("platform" not in d for d in legacy["devices"]))

    def test_pairing_code_signs_in_a_new_device(self):
        newcomer = str(uuid.uuid4())
        ticket = self.post("/v2/pair/new", {"device": newcomer, "label": "公司电脑", "platform": "desktop"})
        self.assertRegex(ticket["code"], r"^[0-9]{6}$")
        self.assertTrue(self.post("/v2/pair/poll", {"secret": ticket["secret"]})["pending"])
        self.assertEqual(self.v2("pair/approve", {"code": "000000" if ticket["code"] != "000000" else "111111"})["status"], 404)
        self.assertEqual(self.v2("pair/approve", {"code": ticket["code"]})["label"], "公司电脑")
        session = self.post("/v2/pair/poll", {"secret": ticket["secret"]})
        self.assertEqual(session["account"], self.auth["account"])
        self.assertEqual(self.v2("sync", {}, session["token"])["epoch"], self.v2("sync", {})["epoch"])
        self.assertEqual(self.post("/v2/pair/poll", {"secret": ticket["secret"]})["status"], 410)
        labels = {d["label"]: d for d in self.v2("devices", {})["devices"]}
        self.assertEqual(labels["公司电脑"]["platform"], "desktop")
        self.v2("rename", {"device": newcomer, "label": "家里电脑"})
        self.assertIn("家里电脑", {d["label"] for d in self.v2("devices", {})["devices"]})

    def test_expired_pairing_code_and_day_overrides(self):
        ticket = self.post("/v2/pair/new", {"device": str(uuid.uuid4())})
        with patch("cloud.time.time", return_value=time.time() + 200):
            self.assertEqual(self.post("/v2/pair/poll", {"secret": ticket["secret"]})["status"], 410)
            self.assertEqual(self.v2("pair/approve", {"code": ticket["code"]})["status"], 404)
        change = lambda key, value: dict(key=key, value=value, base=0, mutation=str(uuid.uuid4()))
        reply = self.v2("sync", {"changes": [change("day/2026-11-24", {"start": "10:30", "off": False})]})
        self.assertIn("day", reply["features"])
        self.assertEqual(reply["changes"][0]["value"], {"start": "10:30", "off": False})
        self.assertEqual(self.v2("sync", {"changes": [change("day/2026-11-25", {"ask": 1})]})["status"], 400)

    def test_spans_shrink_when_the_owner_saw_the_latest_copy(self):
        key = "spans/2026-11-23/" + self.device
        first = self.v2("sync", {"changes": [dict(key=key, value=[[0, 100]], base=0, mutation=str(uuid.uuid4()))]})
        rev = first["changes"][0]["rev"]
        cut = self.v2("sync", {"changes": [dict(key=key, value=[[0, 40]], base=rev, mutation=str(uuid.uuid4()))], "cursor": rev})
        self.assertEqual(cut["changes"][0]["value"], [[0, 40]])

    def test_sessions_slide_forward_when_used(self):
        import api
        with patch("api.time.time", return_value=time.time() + 40 * 86400):
            self.assertIn("epoch", self.v2("sync", {}))
        with patch("api.time.time", return_value=time.time() + 120 * 86400):
            self.assertIn("epoch", self.v2("sync", {}))
        self.assertEqual(api.SESSION_SECONDS, 90 * 86400)

    def test_recovery_revocation_and_deletion(self):
        other=self.post("login",dict(self.creds,device=str(uuid.uuid4())))["token"]
        self.assertEqual(len(self.v2("devices",{})["devices"]),2)
        self.v2("revoke",{"device":self.device},other)
        self.assertEqual(self.v2("sync",{})["status"],401)
        pw=secrets.token_urlsafe()
        reset=self.v2("reset",dict(name="extended",recovery=self.auth["recovery"],newPassword=pw))
        self.assertIn("recovery",reset)
        self.assertEqual(self.v2("reset",dict(name="extended",recovery=self.auth["recovery"],newPassword=pw))["status"],401)
        new=self.post("login",dict(self.creds,password=pw))["token"]
        self.assertEqual(self.v2("delete",dict(password=pw),new),{"ok":True})
        self.assertEqual(self.v2("sync",{},new)["status"],401)
        self.assertNotEqual(self.post("register",self.creds)["account"],self.auth["account"])

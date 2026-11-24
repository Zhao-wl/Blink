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

    def test_primary_reminder_waits_for_old_device_acknowledgement(self):
        second = str(uuid.uuid4())
        other = self.post("login", dict(self.creds, device=second))["token"]
        self.assertIsNone(self.v2("reminders", {})["device"])
        main = self.v2("reminders", {"action": "select", "revision": 0})
        self.assertEqual(main["device"], self.device)
        self.assertGreater(main["expiresAt"], main["serverTime"])
        stale = self.v2("reminders", {"action": "select", "revision": 0}, other)
        self.assertEqual(stale["status"], 409)
        waiting = self.v2("reminders", {"action": "select", "revision": main["revision"]}, other)
        self.assertEqual((waiting["device"], waiting["requested"]), (self.device, second))
        self.assertEqual(waiting["expiresAt"], main["expiresAt"])
        release = {"action": "release", "revision": waiting["revision"]}
        self.assertEqual(self.v2("reminders", release, other)["device"], self.device)
        self.assertEqual(self.v2("reminders", release)["device"], second)
        active = self.v2("reminders", {}, other)
        self.assertGreater(active["expiresAt"], active["serverTime"])
        self.assertEqual(self.v2("reminders", release)["device"], second)

    def test_primary_reminder_handoff_after_offline_expiry(self):
        second = str(uuid.uuid4())
        other = self.post("login", dict(self.creds, device=second))["token"]
        with patch("cloud.time.time", return_value=time.time()):
            main = self.v2("reminders", {"action": "select", "revision": 0})
            self.v2("reminders", {"action": "select", "revision": main["revision"]}, other)
        with patch("cloud.time.time", return_value=(main["expiresAt"] + 1) / 1000):
            active = self.v2("reminders", {}, other)
            self.assertEqual(active["device"], second)
            self.assertIsNone(active["requested"])
            self.assertGreater(active["expiresAt"], active["serverTime"])
            self.assertEqual(self.v2("reminders", {})["device"], second)

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

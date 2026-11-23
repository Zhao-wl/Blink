import test_api
import secrets
import uuid
import cloud


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

    def test_two_confirmations_for_one_work_reminder(self):
        reminder = {"id":"b11e0000-0000-8000-8002-000001c800ff","k":2,"at":1000}
        first = self.post("sync",{"records":[reminder]},self.token)
        replay = self.post("sync",{"cursor":first["cursor"],"records":[dict(reminder,at=3000)]},self.token)
        self.assertEqual(replay["canonical"],[reminder])
        self.assertEqual(self.post("sync",{},self.token)["records"],[reminder])

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

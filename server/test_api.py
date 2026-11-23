"""HTTP integration test using an isolated database and two device sessions."""
import json
import os
import secrets
import tempfile
import threading
import unittest
import uuid
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

import api


class SyncTest(unittest.TestCase):
    def setUp(self):
        self.http = build_opener(ProxyHandler({}))
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        api.DB = os.path.join(self.tmp.name, "test.db")
        api.initialize()
        self.server = api.Server(("127.0.0.1", 0), api.Handler)
        thread = threading.Thread(target=self.server.serve_forever)
        thread.daemon = True
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def post(self, route, body, token=""):
        url = "http://127.0.0.1:%s/v1/%s" % (self.server.server_port, route)
        request = Request(url, json.dumps(body).encode(), {
            "Content-Type": "application/json", "Authorization": "Bearer " + token})
        try:
            with self.http.open(request, timeout=15) as response:
                return json.load(response)
        except HTTPError as exc:
            with exc:
                return {"status": exc.code, **json.load(exc)}

    def test_two_devices(self):
        creds = {"name": "test", "password": secrets.token_urlsafe(24)}
        a = self.post("register", creds)["token"]
        b = self.post("login", creds)["token"]
        other = self.post("register", dict(creds, name="other"))["token"]
        self.assertEqual(self.post("login", dict(creds, password="invalid-password"))["status"], 401)
        log = {"id": str(uuid.uuid4()), "k": 2, "at": 1795400000000}
        first = self.post("sync", {"records": [log]}, a)
        self.assertEqual(first["records"], [log])
        self.assertEqual(self.post("sync", {"records": [log]}, b), first)
        self.assertEqual(self.post("sync", {}, other)["records"], [])
        self.assertEqual(self.post("sync", {"records": [dict(log, k=1)]}, b)["status"], 409)
        undo = {"id": log["id"], "deleted": True}
        delta = self.post("sync", {"records": [undo], "cursor": first["cursor"]}, b)
        self.assertEqual(delta["records"], [undo])
        self.assertEqual(self.post("sync", {"records": [log], "cursor": first["cursor"]}, a), delta)
        api.initialize()
        self.assertEqual(self.post("sync", {}, a)["records"], [undo])
        self.assertEqual(self.post("logout", {}, a), {"ok": True})
        self.assertEqual(self.post("sync", {}, a)["status"], 401)
        self.assertEqual(self.post("sync", {}, b)["records"], [undo])

    def test_pagination_and_atomic_rejection(self):
        token = self.post("register", {"name": "page", "password": secrets.token_urlsafe()})["token"]
        logs = [{"id": str(uuid.uuid4()), "k": 0, "at": i} for i in range(201)]
        self.post("sync", {"records": logs[:200]}, token)
        result = self.post("sync", {"records": logs[-1:]}, token)
        self.assertTrue(result["more"])
        end = self.post("sync", {"cursor": result["cursor"]}, token)
        self.assertEqual(end["records"], logs[-1:])
        self.assertFalse(end["more"])
        bad = [dict(logs[0], deleted=True), {"id": "bad"}]
        self.assertEqual(self.post("sync", {"records": bad}, token)["status"], 400)
        self.assertEqual(self.post("sync", {}, token)["records"][0], logs[0])


if __name__ == "__main__":
    unittest.main()

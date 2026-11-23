"""Exercise the production publisher against a local release archive and fake systemd."""
import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest


@unittest.skipUnless(sys.platform.startswith("linux"), "staging publisher requires Linux flock and GNU mv")
class PublishTest(unittest.TestCase):
    def test_failed_probe_restores_previous_service(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sha = "a" * 40
            (root / "previous").mkdir()
            (root / "current").symlink_to("previous")
            archive = root / "source.tar.gz"
            with tarfile.open(str(archive), "w:gz") as tar:
                for file in ("api.py", "cloud.py"):
                    item = tarfile.TarInfo("Blink-%s/server/%s" % (sha, file))
                    item.size = 4
                    tar.addfile(item, io.BytesIO(b"pass"))
            source = (Path(__file__).parent / "ops" / "publish").read_text()
            script = root / "publish"
            script.write_text(source.replace("cd /var/lib/blink-publish", 'cd "$DRILL_ROOT"'))
            def command(name, body):
                path = root / name
                path.write_text("#!/bin/sh\n" + body + "\n")
                path.chmod(0o700)
            command("curl", 'case "$*" in *codeload*) for dest do :; done; cp "$DRILL_ROOT/source.tar.gz" "$dest";; *) exit 22;; esac')
            command("sudo", 'readlink current >> "$DRILL_ROOT/restarts"')
            env = dict(os.environ, DRILL_ROOT=str(root), SSH_ORIGINAL_COMMAND=sha,
                       PATH=str(root) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["bash", str(script)], env=env)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(os.readlink(str(root / "current")), "previous")
            self.assertEqual((root / "restarts").read_text().splitlines(), ["release-"+sha, "previous"])

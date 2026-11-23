"""Read only the CI-signed staging pointer, then invoke the restricted publisher."""
import base64
import io
import json
import os
import re
import subprocess
import tarfile
import tempfile
from cryptography.hazmat.primitives.serialization import load_ssh_public_key

URL = "https://codeload.github.com/Zhao-wl/Blink/tar.gz/refs/heads/staging-api-channel"
ROOT = "/var/lib/blink-publish"


def main():
    archive = subprocess.check_output(["curl","-fsSL","--max-time","40",URL])
    with tarfile.open(fileobj=io.BytesIO(archive),mode="r:gz") as tar:
        match = next(m for m in tar if m.name.endswith("/release.json") and m.isfile() and m.size<4096)
        marker=json.load(tar.extractfile(match))
    sha,serial=marker["sha"],marker["serial"]
    if not re.fullmatch(r"[a-f0-9]{40}",sha) or type(serial) is not int or serial<=0:
        raise ValueError("Invalid release marker")
    with open("/etc/blink-staging-deploy.pub","rb") as f:
        pub=load_ssh_public_key(f.read())
    pub.verify(base64.b64decode(marker["signature"],validate=True),
               ("blink-staging-v1 %d %s" % (serial,sha)).encode())
    cursor=os.path.join(ROOT,"published")
    if os.path.exists(cursor):
        with open(cursor) as f:
            if int(f.read())>=serial:return
    subprocess.run(["/usr/local/libexec/blink-publish"],env=dict(os.environ,SSH_ORIGINAL_COMMAND=sha),check=True)
    with tempfile.NamedTemporaryFile(mode="w",dir=ROOT,delete=False) as f:
        f.write(str(serial));f.flush();os.fsync(f.fileno())
    os.replace(f.name,cursor)


if __name__=="__main__":
    main()

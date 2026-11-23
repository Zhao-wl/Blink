"""Fetch missing encrypted staging backups through the existing loopback tunnel."""
import datetime
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path
from urllib.request import ProxyHandler, Request, build_opener

store = Path(sys.argv[1])
token = (store / 'access').read_text().strip()
port = int(os.environ.get('BLINK_BACKUP_PORT', '18765'))
opener = build_opener(ProxyHandler({}))


def request(path):
    return opener.open(Request('http://127.0.0.1:%d/ops/backups' % port + path,
                               headers={'Authorization':'Bearer '+token}), timeout=60)


with request('') as response:
    dates = json.load(response)['dates']
for date in dates:
    if datetime.date.fromisoformat(date).isoformat() != date:
        raise ValueError('Unexpected backup date')
    target = store / (date+'.cms')
    if target.exists():
        continue
    with request('/'+date) as response, tempfile.NamedTemporaryFile(dir=store, delete=False) as out:
        temp = Path(out.name)
        try:
            digest = hashlib.sha256()
            for chunk in iter(lambda: response.read(65536), b''):
                out.write(chunk)
                digest.update(chunk)
            if out.tell() != int(response.headers['Content-Length']) or digest.hexdigest() != response.headers['X-Checksum-SHA256']:
                raise ValueError('Incomplete encrypted backup')
            out.flush()
            os.fsync(out.fileno())
            os.link(str(temp), str(target))
        finally:
            temp.unlink()

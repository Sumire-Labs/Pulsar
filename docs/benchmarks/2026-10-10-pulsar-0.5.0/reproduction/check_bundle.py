from pathlib import Path
import json, zipfile, hashlib, re
from collect import ROOT
for folder,name in [('publication','benchmark-data-20261010.zip'),('personal-comparison','four-engine-raw-data.zip')]:
    path=ROOT/folder/name
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        digests=json.loads(z.read('SHA256SUMS.json'))
        for entry,digest in digests.items():
            b=z.read(entry)
            assert hashlib.sha256(b).hexdigest()==digest
            if entry.endswith(('.json','.cfg')):
                assert not re.search(rb'(?i)(accessToken|clientToken|refreshToken|--accessToken|--username|--uuid)',b),entry
        assert not any(n.endswith(('.jar','.mca','.dat','.log')) for n in z.namelist())
    print(folder,'verified files=',len(digests),'sha256=',hashlib.sha256(path.read_bytes()).hexdigest())

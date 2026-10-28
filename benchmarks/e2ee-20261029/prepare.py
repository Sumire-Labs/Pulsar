import concurrent.futures, hashlib, json, re, shutil, time, urllib.request, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BASE = ROOT / 'server-template'
DOWNLOADS = ROOT / 'downloads'
BASE.mkdir(exist_ok=True)

def extract(z, prefix='', skip=()):
    for item in z.infolist():
        if not item.filename.startswith(prefix): continue
        relative = item.filename[len(prefix):]
        if not relative or any(relative == p or relative.startswith(p + '/') for p in skip): continue
        target = (BASE / relative).resolve()
        if not target.is_relative_to(BASE.resolve()): raise ValueError('Archive path escapes template: ' + relative)
        if item.is_dir(): target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(item) as source, target.open('wb') as out: shutil.copyfileobj(source, out)

with zipfile.ZipFile(DOWNLOADS / 'E2EE-server-v1.87.3.zip') as z:
    setup = z.read('server-setup-config.yaml').decode()
    extract(z)
ignore_block = setup.split('  formatSpecific:',1)[1].split('  baseInstallPath:',1)[0]
ignored = set(map(int,re.findall(r'^\s+-\s+(\d+)\s*#',ignore_block,re.M)))
with zipfile.ZipFile(DOWNLOADS / 'E2EE-v1.87.3.zip') as z:
    manifest = json.loads(z.read('manifest.json'))
    extract(z, 'overrides/', ('config/thaumicjei_itemstack_aspects.json','minemenu','resourcepacks','resources','schematics'))
(ROOT / 'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')

def fetch(entry):
    url = entry['downloadUrl']
    from urllib.parse import urlparse, unquote, quote, urlunparse
    name = unquote(urlparse(url).path.rsplit('/',1)[-1])
    parsed=urlparse(url)
    request_url=urlunparse(parsed._replace(path=quote(parsed.path,safe="/%:@!$&'()*+,;=-._~")))
    if not name or '/' in name or '\\' in name or ':' in name: raise ValueError('Invalid filename')
    destination = BASE / 'mods' / name
    destination.parent.mkdir(exist_ok=True)
    last = None
    for attempt in range(4):
        try:
            if not destination.exists() or not zipfile.is_zipfile(destination):
                part = destination.with_suffix(destination.suffix + '.part')
                request = urllib.request.Request(request_url, headers={'User-Agent':'Pulsar-E2EE-Benchmark/1.0'})
                with urllib.request.urlopen(request,timeout=40) as source, part.open('wb') as out:
                    shutil.copyfileobj(source,out)
                if not zipfile.is_zipfile(part): raise ValueError('Not a jar: '+name)
                part.replace(destination)
            with zipfile.ZipFile(destination) as z:
                bad = z.testzip()
                if bad: raise ValueError('CRC failed: '+name+':'+bad)
            return dict(entry, filename=name, bytes=destination.stat().st_size,
                        sha256=hashlib.file_digest(destination.open('rb'),'sha256').hexdigest())
        except Exception as error:
            last = error
            time.sleep(attempt+1)
    raise RuntimeError(name+': '+str(last))

entries = [x for x in manifest['files'] if x['projectID'] not in ignored]
for url, destination in re.findall(r'^\s+- url: (https://\S+)\s*\n\s+destination: (mods/\S+)', setup, re.M):
    entries.append({'projectID':None,'fileID':None,'downloadUrl':url,'required':True})
results, failures = [], []
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    jobs = {pool.submit(fetch,entry):entry for entry in entries}
    for index, job in enumerate(concurrent.futures.as_completed(jobs),1):
        try: results.append(job.result())
        except Exception as error: failures.append(str(error)); print('FAILED',error,flush=True)
        if index%25==0 or index==len(jobs): print(f'DOWNLOAD {index}/{len(jobs)} failures={len(failures)}',flush=True)
(ROOT/'download-provenance.json').write_text(json.dumps({'pack':'v1.87.3','ignored_project_ids':sorted(ignored),
    'files':sorted(results,key=lambda x:x['filename']),'failures':failures},indent=2),encoding='utf-8')
if failures: raise SystemExit('Incomplete download; see download-provenance.json')
print('TEMPLATE_READY', BASE, 'mods='+str(len(list((BASE/'mods').glob('*.jar')))),flush=True)

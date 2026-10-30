"""Archive raw measurements, diagnostics and verification without mod binaries or worlds."""
import hashlib,json,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent
OLD=ROOT.parent/'e2ee-20261029'
files={}
names=[f'{v}-optimized-{i}' for v in ('alfheim','scalar','rgb') for i in (1,2,3)]
names+=['rgb-profile-before','rgb-profile-table','rgb-notification-check','rgb-notification-check-2',
        'rgb-bedrock-diagnostic','rgb-bedrock-methods','rgb-final-check','scalar-final-check','rgb-vanilla-final-check']
for name in names:
    directory=OLD/'runs'/name
    for pattern in ('trial-status.json','benchmark-inventory.json','world-source.json','process-memory.csv',
                    '*-histogram.txt','*-heap.txt','benchmark-results/*.json','BBOP-Extended/lightbench-results/*.json','*.jfr'):
        for p in directory.glob(pattern):files['runs/'+name+'/'+p.relative_to(directory).as_posix()]=p
    if '-optimized-' not in name and (directory/'console.log').exists():
        files['runs/'+name+'/console.log']=directory/'console.log'
for p in ROOT.glob('*.json'):
    if p.name!='evidence-sha256.json':files[p.name]=p
files['results.md']=ROOT/'results.md'
for name in ('download-provenance.json','normalized-configs.json','measured-summary.json','results.md'):
    files['baseline/'+name]=OLD/name
for p in (ROOT.parents[1]/'build/test-results/test').glob('TEST-*.xml'):
    files['test-results/'+p.name]=p
manifest={name:hashlib.sha256(p.read_bytes()).hexdigest() for name,p in sorted(files.items())}
with zipfile.ZipFile(ROOT/'measurement-evidence.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for name,p in sorted(files.items()):archive.write(p,name)
    archive.writestr('evidence-sha256.json',json.dumps(manifest,indent=2))
(ROOT/'evidence-sha256.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Archived',len(manifest),'files;', (ROOT/'measurement-evidence.zip').stat().st_size,'bytes')

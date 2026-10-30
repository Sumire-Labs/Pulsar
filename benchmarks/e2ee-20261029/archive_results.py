"""Package only measurement evidence, without redistributing mod binaries/worlds."""
import hashlib,json,zipfile
from pathlib import Path
root=Path(__file__).resolve().parent
files=[]
for run in sorted((root/'runs').iterdir()):
    if not run.is_dir() or not (run/'trial-status.json').exists(): continue
    for pattern in ('trial-status.json','benchmark-inventory.json','world-source.json',
                    'process-memory.csv','*-histogram.txt','*-heap.txt',
                    'benchmark-results/*.json','BBOP-Extended/lightbench-results/*.json'):
        files.extend(run.glob(pattern))
for name in ('results.md','measured-summary.json','download-provenance.json','normalized-configs.json'):
    files.append(root/name)
manifest={str(p.relative_to(root)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(files))}
with zipfile.ZipFile(root/'measurement-evidence.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as out:
    for p in sorted(set(files)):out.write(p,str(p.relative_to(root)))
    out.writestr('evidence-sha256.json',json.dumps(manifest,indent=2))
(root/'evidence-sha256.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Archived',len(manifest),'files;', (root/'measurement-evidence.zip').stat().st_size,'bytes')

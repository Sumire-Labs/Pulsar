from pathlib import Path
import json,hashlib,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1]
tests=[ET.parse(p).getroot() for p in (R/'repo/build/test-results/test').glob('TEST-*.xml')]
counts={k:sum(int(x.attrib.get(k,0)) for x in tests) for k in ['tests','failures','errors','skipped']}
assert counts['tests']==180 and counts['failures']==counts['errors']==counts['skipped']==0,counts
p=R/'stage.ps1';t=p.read_text(encoding='utf-8');t=t.replace("main{'pulsar-main.jar'}","main{'pulsar-main.jar'} coords{'pulsar-coords.jar'} air{'pulsar-air.jar'} skyunswitch{'pulsar-skyunswitch.jar'}")
p.write_bytes(t.encode())
(R/'selected-profile.txt').write_bytes(b'off')
manifest={'source':'d9b357b','tests':counts}
for key,folder in [('common_mods',R/'common-mods'),('artifacts',R/'artifacts')]:
 manifest[key]=[{'file':p.relative_to(folder).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(folder.rglob('*.jar'))]
(R/'manifest-final.json').write_bytes(json.dumps(manifest,indent=2).encode())
(R/'screen-protocol.json').write_bytes(json.dumps({'source':'d9b357b','screening_runs':10,'command':'lightbench updates 10000 5000','conditions':['pulsar-main','pulsar-coords','pulsar-air','pulsar-skyunswitch','starlight-main'],'orders':[[0,4,1,2,3],[3,2,1,4,0]],'profiling':False,'scope':'Exploratory single-class changes. All four phases use the same command/plan; do not pool with prior studies. Confirm a promising candidate separately before adopting or assigning causal importance.'},indent=2).encode())
print(counts)

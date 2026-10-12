from pathlib import Path
import json,hashlib,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1]
tests=[ET.parse(p).getroot() for p in (R/'repo/build/test-results/test').glob('TEST-*.xml')]
counts={k:sum(int(x.attrib.get(k,0)) for x in tests) for k in ['tests','failures','errors','skipped']}
assert counts['tests']==182 and counts['failures']==counts['errors']==counts['skipped']==0,counts
p=R/'stage.ps1';t=p.read_text(encoding='utf-8')
t=t.replace("skyunswitch{'pulsar-skyunswitch.jar'}","skyunswitch{'pulsar-skyunswitch.jar'} skyblock{'pulsar-skyblock.jar'} skyint{'pulsar-skyint.jar'} skyblockint{'pulsar-skyblockint.jar'}")
needle='$hash=$null';assert t.count(needle)==1
t=t.replace(needle,'''if($Command -eq 'hotpathaudit'){Copy-Item -LiteralPath "$root\\artifacts\\hotpath-audit.jar" -Destination "$game\\mods\\hotpath-audit.jar"}
'''+needle)
p.write_bytes(t.encode())
manifest={'source':'d9b357b','tests':counts}
for key,folder in [('common_mods',R/'common-mods'),('artifacts',R/'artifacts')]:
 manifest[key]=[{'file':p.relative_to(folder).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(folder.rglob('*.jar'))]
(R/'manifest-final.json').write_bytes(json.dumps(manifest,indent=2).encode())
(R/'confirm-protocol.json').write_bytes(json.dumps({'source':'d9b357b','formal_runs':24,'command':'lightbench updates 10000 10000','cases':['pulsar-main','pulsar-skyunswitch','pulsar-skyblock','pulsar-skyint','pulsar-skyblockint','starlight-main'],'orders':[[0,5,1,2,3,4],[4,3,2,1,5,0],[2,0,4,5,1,3],[3,1,5,4,0,2]],'profiling':False,'scope':'Separate confirmation after exploratory screening; warm10000 per phase, fresh JVM/world each trial. All4 phases use the same plan. Never pool screening and confirmation data. Sky-loop selection used screening; Block factor effects are new controlled comparisons.'},indent=2).encode())
print(counts)

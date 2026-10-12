from pathlib import Path
import shutil,zipfile,json,hashlib,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1];P=Path('E:/Github/Pulsar');O=Path('E:/Github/pulsar-hotpath-attribution-20261012')
built=max((p for p in (P/'build/libs').glob('*.jar') if not p.name.endswith(('-dev.jar','-sources.jar'))),key=lambda p:p.stat().st_mtime)
shutil.copyfile(built,R/'artifacts/pulsar-actual.jar');shutil.copyfile(O/'artifacts/pulsar-main.jar',R/'artifacts/pulsar-origin.jar')
proof={}
with zipfile.ZipFile(built) as main,zipfile.ZipFile(R/'artifacts/pulsar-block.jar') as tested:
 for name in ['ScalarSkyEngine.class','ScalarBlockEngine.class']:
  n='com/sumirelabs/pulsar/light/engine/'+name;proof[name]=main.read(n)==tested.read(n)
assert all(proof.values()),proof
tests=[ET.parse(p).getroot() for p in (P/'build/test-results/test').glob('TEST-*.xml')];counts={k:sum(int(x.attrib.get(k,0)) for x in tests) for k in ['tests','failures','errors','skipped']};assert counts['tests']==180 and counts['failures']==counts['errors']==counts['skipped']==0,counts
(R/'analysis/main-proof.json').write_bytes(json.dumps({'actual_jar':built.name,'sha256':hashlib.sha256(built.read_bytes()).hexdigest(),'engine_classes_identical_to_measured_candidate':proof,'tests':counts,'scheduler':'production equivalent recent-worker spin without diagnostic ownership helpers'},indent=2).encode())
p=R/'stage.ps1';t=p.read_text(encoding='utf-8');t=t.replace("both{'pulsar-both.jar'}","both{'pulsar-both.jar'} actual{'pulsar-actual.jar'} origin{'pulsar-origin.jar'}");p.write_bytes(t.encode())
p=R/'manifest-final.json';m=json.loads(p.read_text(encoding='utf-8'))
for name in ['pulsar-actual.jar','pulsar-origin.jar']:m['artifacts'].append({'file':name,'sha256':hashlib.sha256((R/'artifacts'/name).read_bytes()).hexdigest()})
p.write_bytes(json.dumps(m,indent=2).encode());print(json.dumps(counts))

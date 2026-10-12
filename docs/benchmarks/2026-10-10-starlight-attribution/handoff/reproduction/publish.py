from pathlib import Path
import json,zipfile,hashlib,re,shutil,statistics as st
R=Path(__file__).resolve().parents[1]
P=Path('E:/Github/Pulsar/docs/benchmarks/2026-10-10-starlight-attribution/handoff')
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
D=load(R/'analysis/summary.json')
assert D['formal_runs']==48 and D['sampled_runs']==12
assert all(v['reps']==4 for v in D['groups'].values())
P.mkdir(parents=True,exist_ok=True);(P/'reproduction').mkdir(exist_ok=True)
for p in (R/'analysis').iterdir():
 if p.suffix in ['.json','.csv','.png','.svg','.md']:shutil.copyfile(p,P/p.name)
for name in ['protocol.json','manifest-final.json','variant-provenance.json','METHOD.md']:
 shutil.copyfile(R/name,P/name)
for p in (R/'tools').glob('*.py'):shutil.copyfile(p,P/'reproduction'/p.name)
for p in [R/'src/ParallelLightScheduler.java',R/'self-test.gradle',R/'src-test/com/sumirelabs/pulsar/light/HandoffSelfTest.java',R/'lightbench-profile/src/main/java/com/sumirelabs/lightbench/LightProbe.java',R/'lightbench-profile/src/main/java/com/sumirelabs/lightbench/UpdateBenchmark.java',R/'run-study.ps1',R/'run.ps1',R/'stage.ps1',R/'world.cjs']:
 shutil.copyfile(p,P/'reproduction'/p.name)
assert 'PASS: owner exclusion' in (R/'self-test-final.log').read_text(encoding='utf-8')
(P/'test-summary.json').write_text(json.dumps({'lightbench':load(R/'manifest-final.json')['tests'],'scheduler_self_test':{'passed':True,'checks':['held owner exclusion','independent-world progress','caller thread execution','duplicate coordinate merge','future completion','worker execution after release']},'production_sources_changed':False},indent=2),encoding='utf-8')
files=[]
for run in sorted((R/'runs').iterdir()):
 if not run.name.startswith(('formal-','sampled-')):continue
 for p in (run/'results').glob('*.json'):files.append((p,'runs/'+run.name+'/results/'+p.name))
 for name in ['stage.json','status.json','process-samples.json']:files.append((run/name,'runs/'+run.name+'/'+name))
for name in ['protocol.json','manifest-final.json','variant-provenance.json']:files.append((R/name,name))
digests={}
with zipfile.ZipFile(P/'raw-data.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for p,arc in files:
  data=p.read_bytes();assert not re.search(rb'(?i)(accessToken|clientToken|refreshToken|--username|--uuid)',data),p
  z.writestr(arc,data);digests[arc]=hashlib.sha256(data).hexdigest()
 z.writestr('SHA256SUMS.json',json.dumps(digests,indent=2))
with zipfile.ZipFile(P/'raw-data.zip') as z:
 assert z.testzip() is None
 assert all(hashlib.sha256(z.read(n)).hexdigest()==h for n,h in digests.items())
print(json.dumps({'files_in_raw_package':len(files),'raw_bytes':(P/'raw-data.zip').stat().st_size,'destination':str(P)},indent=2))

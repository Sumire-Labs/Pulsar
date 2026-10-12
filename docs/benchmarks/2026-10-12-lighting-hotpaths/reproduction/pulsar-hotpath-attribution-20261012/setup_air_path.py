from pathlib import Path
import zipfile,shutil,copy,json,hashlib,xml.etree.ElementTree as ET
O=Path(__file__).resolve().parents[1];F=Path('E:/Github/pulsar-empty-spin-20261012');R=Path('E:/Github/pulsar-airpath-20261012');R.mkdir(exist_ok=True)
for name in ['common-mods','frozen','templates']:shutil.copytree(F/name,R/name,dirs_exist_ok=True)
for name in ['runs','artifacts','tools','analysis']:(R/name).mkdir(exist_ok=True)
for name in ['stage.ps1','run.ps1','world.cjs']:
 t=(F/name).read_text(encoding='utf-8-sig').replace(str(F).replace('/','\\'),str(R).replace('/','\\'));(R/name).write_bytes(t.encode())
shutil.copyfile(F/'tools/validate.py',R/'tools/validate.py')
for name in ['hotpath-audit.jar','starlight-0a7d94e-clean.jar']:shutil.copyfile(F/'artifacts'/name,R/'artifacts'/name)
base=F/'artifacts/pulsar-base.jar';shutil.copyfile(base,R/'artifacts/pulsar-base.jar')
built=max((p for p in (O/'repo/build/libs').glob('*.jar') if not p.name.endswith(('-dev.jar','-sources.jar'))),key=lambda p:p.stat().st_mtime)
prefix='com/sumirelabs/pulsar/light/engine/';cases={'sky':{prefix+'ScalarSkyEngine.class'},'block':{prefix+'ScalarBlockEngine.class'},'both':{prefix+'ScalarSkyEngine.class',prefix+'ScalarBlockEngine.class'}}
proof={}
with zipfile.ZipFile(base) as old,zipfile.ZipFile(built) as new:
 for name,changed in cases.items():
  path=R/'artifacts'/f'pulsar-{name}.jar'
  with zipfile.ZipFile(path,'w') as out:
   for info in old.infolist():out.writestr(copy.copy(info),new.read(info.filename) if info.filename in changed else old.read(info.filename))
  with zipfile.ZipFile(path) as out:assert out.testzip() is None;assert all(out.read(n)==old.read(n) for n in old.namelist() if n not in changed)
  proof[name]={'changed_entries':sorted(changed),'all_other_entries_identical':True,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
(R/'variant-provenance.json').write_bytes(json.dumps(proof,indent=2).encode())
p=R/'stage.ps1';t=p.read_text(encoding='utf-8');a=t.index('$artifact=switch($Engine)');b=t.index('\nif($Command',a)
t=t[:a]+'''$artifact=switch($Engine){starlight{'starlight-0a7d94e-clean.jar'} pulsar{switch($variant){base{'pulsar-base.jar'} sky{'pulsar-sky.jar'} block{'pulsar-block.jar'} both{'pulsar-both.jar'} default{throw 'Unknown air-path candidate'}}} default{throw 'Unsupported engine'}}'''+t[b:]
p.write_bytes(t.encode())
for name,value in [('selected-variant.txt','base'),('selected-pool.txt','auto'),('selected-spin.txt','5000'),('selected-wait.txt','legacy')]: (R/name).write_bytes(value.encode())
tests=[ET.parse(p).getroot() for p in (O/'repo/build/test-results/test').glob('TEST-*.xml')];counts={k:sum(int(x.attrib.get(k,0)) for x in tests) for k in ['tests','failures','errors','skipped']};assert counts['tests']==181 and counts['failures']==counts['errors']==counts['skipped']==0,counts
m={'source':'d9b357b','tests':counts}
for key,folder in [('common_mods',R/'common-mods'),('artifacts',R/'artifacts')]:m[key]=[{'file':p.relative_to(folder).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(folder.rglob('*.jar'))]
(R/'manifest-final.json').write_bytes(json.dumps(m,indent=2).encode())
(R/'protocol.json').write_bytes(json.dumps({'formal_runs':20,'command':'lightbench updates 10000 10000','cases':['pulsar-base','pulsar-sky','pulsar-block','pulsar-both','starlight-base'],'orders':[[0,4,1,2,3],[3,2,1,4,0],[2,0,4,3,1],[1,3,4,0,2]],'spin_ns':5000,'wait':'legacy','profiling':False,'scope':'Independent channel-specific air-path specializations. Already-matched early level guard proves plain-air writes, source flags0/emission0, generic contextual/fluid/sided paths retained. All conditions share corrected diagnostic scheduler, auto10workers, offline launch. Empty-cache normalization discarded: runtime input had0non-null emptysections.'},indent=2).encode())
print(counts)

from pathlib import Path
import shutil,zipfile,copy,json,hashlib,sys,xml.etree.ElementTree as ET
O=Path(__file__).resolve().parents[1];R=Path('E:/Github/pulsar-hotpath-compact-20261012');H=Path('E:/Github/pulsar-handoff-20261010');mode=sys.argv[1]
if mode=='init':
 R.mkdir(exist_ok=True)
 for name in ['artifacts','runs','analysis','tools']:(R/name).mkdir(exist_ok=True)
 for name in ['common-mods','frozen','templates']:shutil.copytree(H/name,R/name,dirs_exist_ok=True)
 for name in ['stage.ps1','run.ps1','world.cjs']:
  t=(H/name).read_text(encoding='utf-8-sig').replace(str(H).replace('/','\\'),str(R).replace('/','\\'));(R/name).write_bytes(t.encode())
 for name in ['starlight-0a7d94e-clean.jar','hotpath-audit.jar']:shutil.copyfile(O/'artifacts'/name,R/'artifacts'/name)
 shutil.copyfile(O/'tools/validate.py',R/'tools/validate.py')
 p=R/'stage.ps1';t=p.read_text(encoding='utf-8');a=t.index('$artifact=switch($Engine)');b=t.index('\n$hash=',a)
 t=t[:a]+'''$artifact=switch($Engine){starlight{'starlight-0a7d94e-clean.jar'} pulsar{switch($variant){manual{'pulsar-manual.jar'} compact{'pulsar-compact.jar'} zero{'pulsar-zero.jar'} default{throw 'Unknown compact candidate'}}} default{throw 'Unsupported engine'}}
if($Command -eq 'hotpathaudit'){Copy-Item -LiteralPath "$root\\artifacts\\hotpath-audit.jar" -Destination "$game\\mods\\hotpath-audit.jar"}'''+t[b:]
 p.write_bytes(t.encode())
 for name,value in [('selected-pool.txt','auto'),('selected-wait.txt','legacy'),('selected-variant.txt','manual')]: (R/name).write_bytes(value.encode())
 # Mode selection must not implicitly enable sampling; these are native trials.
 t=p.read_text(encoding='utf-8');t=t.replace('if($variant -eq "on")','if($variant -eq "on")');p.write_bytes(t.encode())
 mode='manual'
base=O/'artifacts/pulsar-skyunswitch.jar'
prefix='com/sumirelabs/pulsar/light/engine/'
patches={}
with zipfile.ZipFile(H/'artifacts/pulsar-diagnostic.jar') as diag:
 for n in ['com/sumirelabs/pulsar/light/ParallelLightScheduler.class','com/sumirelabs/pulsar/light/ParallelLightScheduler$Claim.class']:patches[n]=diag.read(n)
if mode!='manual':
 built=max((p for p in (O/'repo/build/libs').glob('*.jar') if not p.name.endswith(('-dev.jar','-sources.jar'))),key=lambda p:p.stat().st_mtime)
 with zipfile.ZipFile(built) as new:
  patches[prefix+'ScalarSkyEngine.class']=new.read(prefix+'ScalarSkyEngine.class')
  if mode=='zero':patches[prefix+'ScalarBlockEngine.class']=new.read(prefix+'ScalarBlockEngine.class')
  if mode=='compact':
   with zipfile.ZipFile(base) as original:assert original.read(prefix+'ScalarBlockEngine.class')==new.read(prefix+'ScalarBlockEngine.class'),'Discarded Block changes leaked into compact candidate'
path=R/'artifacts'/f'pulsar-{mode}.jar'
with zipfile.ZipFile(base) as old,zipfile.ZipFile(path,'w') as out:
 for info in old.infolist():out.writestr(copy.copy(info),patches.get(info.filename,old.read(info.filename)))
with zipfile.ZipFile(base) as old,zipfile.ZipFile(path) as out:
 assert out.testzip() is None
 assert set(out.namelist())==set(old.namelist())
 assert all(out.read(n)==old.read(n) for n in old.namelist() if n not in patches)
proofpath=R/'variant-provenance.json';proof=json.loads(proofpath.read_text(encoding='utf-8')) if proofpath.exists() else {}
proof[mode]={'base':'previous-stage pulsar-skyunswitch.jar','changed_entries':sorted(patches),'other_entries_identical':True,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
proofpath.write_bytes(json.dumps(proof,indent=2).encode())
if mode=='zero':
 tests=[ET.parse(p).getroot() for p in (O/'repo/build/test-results/test').glob('TEST-*.xml')];counts={k:sum(int(x.attrib.get(k,0)) for x in tests) for k in ['tests','failures','errors','skipped']};assert counts['tests']==181 and counts['failures']==counts['errors']==counts['skipped']==0
 manifest={'source':'d9b357b','tests':counts,'scheduler':'same diagnostic late-partner scheduler in all Pulsar conditions'}
 for key,folder in [('common_mods',R/'common-mods'),('artifacts',R/'artifacts')]:manifest[key]=[{'file':p.relative_to(folder).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(folder.rglob('*.jar'))]
 (R/'manifest-final.json').write_bytes(json.dumps(manifest,indent=2).encode())
print('Packaged',mode)

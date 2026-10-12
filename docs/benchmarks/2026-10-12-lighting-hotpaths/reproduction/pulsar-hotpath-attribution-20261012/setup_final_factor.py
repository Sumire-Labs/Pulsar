from pathlib import Path
import shutil,zipfile,copy,json,hashlib,xml.etree.ElementTree as ET
O=Path(__file__).resolve().parents[1];C=Path('E:/Github/pulsar-hotpath-compact-20261012');R=Path('E:/Github/pulsar-empty-spin-20261012');R.mkdir(exist_ok=True)
for name in ['common-mods','frozen','templates']:shutil.copytree(C/name,R/name,dirs_exist_ok=True)
for name in ['artifacts','runs','tools','analysis']:(R/name).mkdir(exist_ok=True)
for name in ['stage.ps1','run.ps1','world.cjs']:
 t=(C/name).read_text(encoding='utf-8-sig').replace(str(C).replace('/','\\'),str(R).replace('/','\\'));(R/name).write_bytes(t.encode())
for name in ['validate.py']:shutil.copyfile(C/'tools'/name,R/'tools'/name)
for name in ['starlight-0a7d94e-clean.jar','hotpath-audit.jar']:shutil.copyfile(C/'artifacts'/name,R/'artifacts'/name)
built=max((p for p in (O/'repo/build/libs').glob('*.jar') if not p.name.endswith(('-dev.jar','-sources.jar'))),key=lambda p:p.stat().st_mtime)
base=C/'artifacts/pulsar-zero.jar';scheduler='com/sumirelabs/pulsar/light/ParallelLightScheduler.class';cache='com/sumirelabs/pulsar/light/engine/LightEngineCache.class'
proof={}
with zipfile.ZipFile(base) as old,zipfile.ZipFile(built) as new:
 for name in ['base','empty']:
  patches={scheduler:(O/'spin-classes'/scheduler).read_bytes()}
  if name=='empty':patches[cache]=new.read(cache)
  path=R/'artifacts'/f'pulsar-{name}.jar'
  with zipfile.ZipFile(path,'w') as out:
   for info in old.infolist():out.writestr(copy.copy(info),patches.get(info.filename,old.read(info.filename)))
  with zipfile.ZipFile(path) as out:
   assert out.testzip() is None;assert all(out.read(n)==old.read(n) for n in old.namelist() if n not in patches)
  proof[name]={'changed_entries':sorted(patches),'all_other_entries_identical':True,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
(R/'variant-provenance.json').write_bytes(json.dumps(proof,indent=2).encode())
p=R/'stage.ps1';t=p.read_text(encoding='utf-8');a=t.index('$artifact=switch($Engine)');b=t.index('\nif($Command',a)
t=t[:a]+'''$artifact=switch($Engine){starlight{'starlight-0a7d94e-clean.jar'} pulsar{switch($variant){base{'pulsar-base.jar'} empty{'pulsar-empty.jar'} default{throw 'Unknown final candidate'}}} default{throw 'Unsupported engine'}}'''+t[b:]
needle='$rel|ConvertTo-Json|Set-Content "$game\\config\\relauncher.json" -Encoding UTF8';assert t.count(needle)==1
t=t.replace(needle,'''$spin=(Get-Content "$root\\selected-spin.txt" -Raw).Trim()
$rel.args += " -Dpulsar.diag.idleSpinNs=$spin"
'''+needle)
t=t.replace('$manifest=@{wait_mode=$waitMode;','$manifest=@{idle_spin_ns=$spin;wait_mode=$waitMode;')
p.write_bytes(t.encode())
for name,value in [('selected-pool.txt','auto'),('selected-variant.txt','base'),('selected-wait.txt','legacy'),('selected-spin.txt','0')]: (R/name).write_bytes(value.encode())
tests=[ET.parse(p).getroot() for p in (O/'repo/build/test-results/test').glob('TEST-*.xml')];counts={k:sum(int(x.attrib.get(k,0)) for x in tests) for k in ['tests','failures','errors','skipped']};assert counts['tests']==185 and counts['failures']==counts['errors']==counts['skipped']==0,counts
manifest={'source':'d9b357b','tests':counts,'diagnostic_hold':'busy-spin until prior reservation released; removes former1ms polling wait outside timing'}
for key,folder in [('common_mods',R/'common-mods'),('artifacts',R/'artifacts')]:manifest[key]=[{'file':p.relative_to(folder).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(folder.rglob('*.jar'))]
(R/'manifest-final.json').write_bytes(json.dumps(manifest,indent=2).encode())
cases=[['pulsar','base','0','legacy'],['pulsar','base','5000','legacy'],['pulsar','empty','0','legacy'],['pulsar','empty','5000','legacy'],['pulsar','empty','5000','gatedpoll'],['pulsar','empty','5000','caller'],['starlight','base','0','legacy']]
orders=[[0,6,1,2,3,4,5],[5,4,3,2,1,6,0],[2,0,4,6,1,5,3],[3,5,1,6,4,0,2]]
(R/'protocol.json').write_bytes(json.dumps({'formal_runs':28,'cases':cases,'orders':orders,'command':'lightbench updates 10000 10000','profiling':False,'launch':'offline Serrecable, all conditions identical','scope':'Factorial empty-section cache normalization and recent-worker bounded5us spin, plus corrected producer-held worker/poll versus caller controls. Same scheduler class bytes; spin setting0/5000. Do not pool other epochs.'},indent=2).encode())
print(json.dumps({'root':str(R),'tests':counts,'proof':proof},indent=2))

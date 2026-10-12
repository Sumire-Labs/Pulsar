from pathlib import Path
import zipfile,json,hashlib,copy
R=Path(__file__).resolve().parents[1]
built=max((p for p in (R/'repo/build/libs').glob('*.jar') if not p.name.endswith(('-dev.jar','-sources.jar'))),key=lambda p:p.stat().st_mtime)
base=R/'artifacts/pulsar-main.jar';prefix='com/sumirelabs/pulsar/light/engine/'
cases={'coords':{prefix+'PulsarEngine.class'},'air':{prefix+'LightEngineCache.class'},'skyunswitch':{prefix+'ScalarSkyEngine.class'}}
proof={}
with zipfile.ZipFile(base) as old,zipfile.ZipFile(built) as new:
 for name,changed in cases.items():
  outpath=R/'artifacts'/f'pulsar-{name}.jar'
  with zipfile.ZipFile(outpath,'w') as out:
   for info in old.infolist():out.writestr(copy.copy(info),new.read(info.filename) if info.filename in changed else old.read(info.filename))
  with zipfile.ZipFile(outpath) as out:
   assert out.testzip() is None
   assert all(old.read(n)==out.read(n) for n in old.namelist() if n not in changed)
  proof[name]={'changed_classes':sorted(changed),'other_entries_identical':True,'sha256':hashlib.sha256(outpath.read_bytes()).hexdigest()}
(R/'variant-provenance.json').write_bytes(json.dumps(proof,indent=2).encode())
print(json.dumps(proof,indent=2))

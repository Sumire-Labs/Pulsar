from pathlib import Path
import zipfile,json,hashlib,copy,sys
R=Path(__file__).resolve().parents[1];mode=sys.argv[1]
source=R/'repo/src/main/java/com/sumirelabs/pulsar/light/engine/ScalarBlockEngine.java'
if mode in ['int','both']:
    t=(R/'sources'/('ScalarBlockEngine.java' if mode=='int' else 'ScalarBlockEngine-unswitch.java')).read_text(encoding='utf-8')
    t=t.replace('encodeCoords(','encodeBlockCoords(')
    at=t.rfind('}')
    t=t[:at]+'''    static long encodeBlockCoords(final int x, final int z, final int y, final int encodeOffset) {
        return (x + (z << COORD_X_BITS) + (y << (COORD_X_BITS + COORD_Z_BITS)) + encodeOffset) & COORD_MASK;
    }
'''+t[at:]
    source.write_bytes(t.encode());print('Prepared',mode);exit()
built=max((p for p in (R/'repo/build/libs').glob('*.jar') if not p.name.endswith(('-dev.jar','-sources.jar'))),key=lambda p:p.stat().st_mtime)
if mode=='saveunswitch':(R/'sources/ScalarBlockEngine-unswitch.java').write_bytes(source.read_bytes())
name={'saveunswitch':'skyblock','saveint':'skyint','saveboth':'skyblockint'}[mode]
block='com/sumirelabs/pulsar/light/engine/ScalarBlockEngine.class';sky='com/sumirelabs/pulsar/light/engine/ScalarSkyEngine.class'
with zipfile.ZipFile(R/'artifacts/pulsar-main.jar') as old,zipfile.ZipFile(built) as new,zipfile.ZipFile(R/'artifacts/pulsar-skyunswitch.jar') as skyjar:
 patches={block:new.read(block),sky:skyjar.read(sky)}
 path=R/'artifacts'/f'pulsar-{name}.jar'
 with zipfile.ZipFile(path,'w') as out:
  for info in old.infolist():out.writestr(copy.copy(info),patches.get(info.filename,old.read(info.filename)))
 with zipfile.ZipFile(path) as out:
  assert out.testzip() is None
  assert all(old.read(n)==out.read(n) for n in old.namelist() if n not in patches)
 if mode=='saveunswitch':
  with zipfile.ZipFile(R/'artifacts/hotpath-audit.jar','w') as out:
   out.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nModType: CRL\n\n')
   for n in new.namelist():
    if n.startswith('com/sumirelabs/hotpathaudit/') and n.endswith('.class'):out.writestr(n,new.read(n))
proof=json.loads((R/'variant-provenance.json').read_text(encoding='utf-8'))
proof[name]={'changed_classes':sorted(patches),'other_entries_identical':True,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
(R/'variant-provenance.json').write_bytes(json.dumps(proof,indent=2).encode())
print('Packaged',name)

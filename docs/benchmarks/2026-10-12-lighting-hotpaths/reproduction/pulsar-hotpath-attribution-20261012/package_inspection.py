from pathlib import Path
import zipfile,json,hashlib
O=Path(__file__).resolve().parents[1];R=Path('E:/Github/pulsar-empty-spin-20261012')
built=max((p for p in (O/'repo/build/libs').glob('*.jar') if not p.name.endswith(('-dev.jar','-sources.jar'))),key=lambda p:p.stat().st_mtime)
path=R/'artifacts/hotpath-inspection.jar'
with zipfile.ZipFile(built) as new,zipfile.ZipFile(path,'w') as out:
 out.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nModType: CRL\n\n')
 for n in new.namelist():
  if n.startswith('com/sumirelabs/hotpathaudit/') and n.endswith('.class'):out.writestr(n,new.read(n))
p=R/'stage.ps1';t=p.read_text(encoding='utf-8');needle='$hash=$null';assert t.count(needle)==1
t=t.replace(needle,'''if($Command -eq 'hotpathaudit inspect'){Copy-Item -LiteralPath "$root\\artifacts\\hotpath-inspection.jar" -Destination "$game\\mods\\hotpath-inspection.jar"}
'''+needle);p.write_bytes(t.encode())
p=R/'manifest-final.json';m=json.loads(p.read_text(encoding='utf-8'));m['artifacts'].append({'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()});p.write_bytes(json.dumps(m,indent=2).encode())
print('Packaged standalone runtime inspection (excluded from native trials)')

from pathlib import Path
import shutil,json,hashlib,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1]
built=R/'lightbench-profile/build/libs/lightbench-1.1.0-bench9.jar'
shutil.copyfile(built,R/'common-mods'/built.name)
roots=[ET.parse(p).getroot() for p in (R/'lightbench-profile/build/test-results/test').glob('TEST-*.xml')]
tests={k:sum(int(x.attrib.get(k,0)) for x in roots) for k in ['tests','failures','errors','skipped']}
assert tests['tests']>=60 and tests['failures']==tests['errors']==tests['skipped']==0,tests
manifest={'main':'87e75ec','tests':tests}
for key,folder in [('artifacts',R/'artifacts'),('common_mods',R/'common-mods')]:
 manifest[key]=[{'file':p.relative_to(folder).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(folder.rglob('*.jar'))]
(R/'manifest-final.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps(tests))

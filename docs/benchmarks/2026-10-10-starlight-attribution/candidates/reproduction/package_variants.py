from pathlib import Path
import zipfile,json,hashlib,copy,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1]
original=R/'artifacts/pulsar-baseline.jar'
built=next(p for p in (R/'repo/build/libs').glob('*.jar') if not p.name.endswith(('-dev.jar','-sources.jar')))
late={'com/sumirelabs/pulsar/light/ParallelLightScheduler.class','com/sumirelabs/pulsar/light/ParallelLightScheduler$Claim.class'}
mask={'com/sumirelabs/pulsar/light/engine/ScalarBlockEngine.class','com/sumirelabs/pulsar/light/engine/ScalarSkyEngine.class','com/sumirelabs/pulsar/light/engine/PropagationDirections.class'}
proof={}
with zipfile.ZipFile(original) as old,zipfile.ZipFile(built) as new:
    original_names=set(old.namelist())
    for name,changed in [('late',late),('mask',mask),('both',late|mask)]:
        path=R/'artifacts'/('pulsar-'+name+'.jar')
        with zipfile.ZipFile(path,'w') as out:
            for info in old.infolist():out.writestr(copy.copy(info),new.read(info.filename) if info.filename in changed else old.read(info.filename))
            for entry in changed-original_names:out.writestr(copy.copy(new.getinfo(entry)),new.read(entry))
        with zipfile.ZipFile(path) as out:
            assert out.testzip() is None
            assert all(out.read(p)==old.read(p) for p in original_names if p not in changed)
        proof[name]=dict(changed_entries=sorted(changed),all_other_entries_identical=True,sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    proof['unmodified_light_classes_match_rebuild']={p:old.read(p)==new.read(p) for p in ['com/sumirelabs/pulsar/light/LightQueue.class','com/sumirelabs/pulsar/light/engine/PulsarEngine.class']}
roots=[ET.parse(p).getroot() for p in (R/'repo/build/test-results/test').glob('TEST-*.xml')]
proof['tests']={k:sum(int(x.attrib.get(k,0)) for x in roots) for k in ['tests','failures','errors','skipped']}
assert proof['tests']['failures']==proof['tests']['errors']==proof['tests']['skipped']==0
(R/'variant-provenance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
print(json.dumps(proof,indent=2))

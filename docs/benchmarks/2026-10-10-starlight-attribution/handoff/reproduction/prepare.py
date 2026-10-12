from pathlib import Path
import json,zipfile,copy,hashlib,shutil
R=Path(__file__).resolve().parents[1]
L=R/'lightbench-profile/src/main/java/com/sumirelabs/lightbench/LightProbe.java'
t=L.read_text(encoding='utf-8')
t=t.replace('WAIT_MODE.equals("caller")) this.diagnosticBegin','(WAIT_MODE.equals("caller") || WAIT_MODE.equals("gated"))) this.diagnosticBegin')
# spotless may have inserted newlines; handle token expression independent of formatting.
t=t.replace('this.engine == Engine.PULSAR && WAIT_MODE.equals("caller"))\n            this.diagnosticBegin','this.engine == Engine.PULSAR && (WAIT_MODE.equals("caller") || WAIT_MODE.equals("gated")))\n            this.diagnosticBegin')
t=t.replace('if (WAIT_MODE.equals("caller")) {','if (WAIT_MODE.equals("caller") || WAIT_MODE.equals("gated")) {')
t=t.replace('} else if (WAIT_MODE.equals("future") && affectedPosition != null) {','} else if ((WAIT_MODE.equals("future") || WAIT_MODE.equals("gated")) && affectedPosition != null) {\n                    if(WAIT_MODE.equals("gated")) this.diagnosticEnd.invoke(null,this.diagnosticWorld);')
assert 'WAIT_MODE.equals("gated")' in t
if 'if(WAIT_MODE.equals("gated")) this.diagnosticEnd.invoke' not in L.read_text(encoding='utf-8'):
    L.write_text(t,encoding='utf-8')
main=R/'artifacts/pulsar-main.jar';profile=R/'artifacts/pulsar-old-profile.jar'
proof={}
with zipfile.ZipFile(main) as base,zipfile.ZipFile(profile) as prof:
    changed={p.relative_to(R/'classes').as_posix():p.read_bytes() for p in (R/'classes').rglob('*.class')}
    assert len(changed)==2
    instrumented={n:prof.read(n) for n in base.namelist() if n.endswith('.class') and n not in changed and prof.read(n)!=base.read(n)}
    # Production runtime except scheduler is identical to frozen source; metadata can differ.
    assert set(instrumented)=={
      'com/sumirelabs/pulsar/light/ChunkTasks.class','com/sumirelabs/pulsar/light/LightEngineWorker.class',
      'com/sumirelabs/pulsar/light/engine/PulsarEngine.class','com/sumirelabs/pulsar/light/engine/ScalarBlockEngine.class',
      'com/sumirelabs/pulsar/light/engine/SkyLightColumnProcessor.class',
      'com/sumirelabs/pulsar/light/engine/PulsarEngine$AxisDirection.class'},set(instrumented)
    for name,patches in [('diagnostic',changed),('sampled',changed|instrumented)]:
        path=R/'artifacts'/f'pulsar-{name}.jar'
        with zipfile.ZipFile(path,'w') as out:
            for info in base.infolist():out.writestr(copy.copy(info),patches.get(info.filename,base.read(info.filename)))
        with zipfile.ZipFile(path) as out:
            assert out.testzip() is None
            assert all(out.read(n)==base.read(n) for n in base.namelist() if n not in patches)
        proof[name]={'changed_entries':sorted(patches),'all_other_entries_identical':True,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
(R/'variant-provenance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
# Stage records independent wait policy and instrumentation condition.
p=R/'stage.ps1';t=p.read_text(encoding='utf-8')
t=t.replace('$rel.args += " -Dbenchprobe.enabled="+$(if($variant -eq "on"){"true"}else{"false"})','$rel.args += " -Dbenchprobe.enabled="+$(if($variant -eq "on"){"true"}else{"false"})\n$waitMode=(Get-Content "$root\\selected-wait.txt" -Raw).Trim()\n$rel.args += " -Dhandoff.wait=$waitMode"')
a=t.index('$artifact=switch($Engine)');b=t.index('\n$hash=',a)
t=t[:a]+'''$artifact=switch($Engine){starlight{'starlight-0a7d94e-clean.jar'} pulsar{if($variant -eq 'original'){'pulsar-main.jar'}elseif($variant -eq 'on'){'pulsar-sampled.jar'}else{'pulsar-diagnostic.jar'}} default{throw 'Unsupported engine'}}'''+t[b:]
t=t.replace('$manifest=@{pool=$pool;','$manifest=@{wait_mode=$waitMode;pool=$pool;')
p.write_text(t,encoding='utf-8')
for name,value in [('selected-pool.txt','auto'),('selected-variant.txt','off'),('selected-wait.txt','legacy')]: (R/name).write_text(value,encoding='utf-8')
shutil.copyfile(Path('E:/Github/pulsar-attribution-candidates-20261010/tools/validate.py'),R/'tools/validate.py')
protocol={'source_main':'87e75ec (production scheduler3347942)','formal_runs':40,'cases':['pulsar-legacy','pulsar-future','pulsar-gated','pulsar-caller','starlight-legacy'],'repetitions':4,'orders':[[0,1,2,3,4],[4,3,2,1,0],[2,0,4,1,3],[3,1,4,0,2]],'blocks':'lightbench blockupdates 5000 10000','sky':'lightbench updates 2000 1000','profiling':False,'supplemental':'2 reps x blocks/sky x gated/caller, sampled schema3','primary_comparison':'gated versus caller: same producer hold and batch, shared claim/execute path; release to worker versus caller execution. Future versus gated additionally changes producer/consumer overlap; legacy versus future changes completion wait policy. No per-instruction or cache attribution.','exclusions':'No propagation/shadow semantic changes; other comparisons deferred.'}
(R/'protocol.json').write_text(json.dumps(protocol,indent=2),encoding='utf-8')
print(json.dumps(proof,indent=2))

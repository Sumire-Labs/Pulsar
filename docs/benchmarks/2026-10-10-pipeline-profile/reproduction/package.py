from pathlib import Path
import json,hashlib,zipfile,subprocess,shutil,re,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1]
def package():
    out=R/'analysis';assert json.loads((R/'progress.json').read_text(encoding='utf-8-sig'))['status'] in ['measurements-complete','complete']
    repro=out/'reproduction';repro.mkdir(exist_ok=True)
    for repo,leaf in [('pulsar-instrumented','pulsar-instrumentation.patch'),('lightbench-instrumented','lightbench-instrumentation.patch')]:
        patch=subprocess.run(['git','-C',str(R/repo),'diff','--no-ext-diff'],check=True,capture_output=True).stdout
        (repro/leaf).write_bytes(patch)
    shutil.copyfile(R/'pulsar-instrumented/src/main/java/com/sumirelabs/pulsar/light/PhaseTiming.java',repro/'PhaseTiming.java')
    shutil.copyfile(R/'lightbench-instrumented/src/main/java/com/sumirelabs/lightbench/PulsarTimingBridge.java',repro/'PulsarTimingBridge.java')
    for p in (R/'tools').glob('*'):
        if p.suffix in ['.py','.java'] and p.name != 'instrument.py':shutil.copyfile(p,repro/p.name)
    shutil.copyfile(R/'manifest-final.json',out/'manifest.json')
    shutil.copyfile(R/'algorithm-comparison.md',out/'algorithm-comparison.md')
    tests={}
    for repo in ['pulsar-instrumented','lightbench-instrumented']:
        roots=[ET.parse(p).getroot() for p in (R/repo/'build/test-results/test').glob('TEST-*.xml')]
        tests[repo]={k:sum(int(x.attrib.get(k,0)) for x in roots) for k in ['tests','failures','errors','skipped']}
    (out/'test-summary.json').write_text(json.dumps(tests,indent=2),encoding='utf-8')
    paths=[]
    for run in sorted((R/'runs').glob('formal-*')):
        for p in (run/'results').glob('*.json'):paths.append((p,'runs/'+run.name+'/results/'+p.name))
        for leaf in ['stage.json','status.json','process-samples.json']:paths.append((run/leaf,'runs/'+run.name+'/'+leaf))
    paths.extend([(R/'manifest-final.json','manifest-final.json'),(R/'probe-self-test.json','probe-self-test.json'),(out/'test-summary.json','test-summary.json')])
    digests={}
    with zipfile.ZipFile(out/'diagnostic-raw-data.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p,arc in paths:
            b=p.read_bytes()
            assert not re.search(rb'(?i)(accessToken|clientToken|refreshToken|--username|--uuid)',b),p
            z.writestr(arc,b);digests[arc]=hashlib.sha256(b).hexdigest()
        z.writestr('SHA256SUMS.json',json.dumps(digests,indent=2))
    with zipfile.ZipFile(out/'diagnostic-raw-data.zip') as z:
        assert z.testzip() is None
        for arc,digest in digests.items():assert hashlib.sha256(z.read(arc)).hexdigest()==digest
    report=out/'README.md';text=report.read_text(encoding='utf-8')
    text+='\n## Files\n\n[Probe overhead](probe-overhead.png) and [engine stages](engine-stages.png), also available as SVG.\n\n'
    text+='![Probe controls](probe-overhead.png)\n\n![Engine stages](engine-stages.png)\n\n'
    text+='[Moonrise/Starlight source comparison](algorithm-comparison.md). [Raw diagnostic/native JSON](diagnostic-raw-data.zip), '
    text+='[source manifest](manifest.json), [test counts](test-summary.json), [reproduction files](reproduction/).\n'
    report.write_text(text,encoding='utf-8')
    sums={p.relative_to(out).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in out.rglob('*') if p.is_file() and p.name!='SHA256SUMS.json'}
    (out/'SHA256SUMS.json').write_text(json.dumps(sums,indent=2),encoding='utf-8')
    print(json.dumps(dict(tests=tests,raw_files=len(paths),archive_bytes=(out/'diagnostic-raw-data.zip').stat().st_size),indent=2))
if __name__=='__main__':package()

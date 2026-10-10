from pathlib import Path
import json,hashlib,zipfile,shutil,re,subprocess,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1]
def package():
    assert json.loads((R/'progress.json').read_text(encoding='utf-8-sig'))['status'] in ['measurements-complete','analysis','complete']
    out=R/'analysis';repro=out/'reproduction';repro.mkdir(exist_ok=True)
    shutil.copyfile(R/'probe-src/BenchTiming.java',repro/'BenchTiming.java')
    for p in (R/'tools').glob('*'):
        if p.is_file() and p.suffix in ['.py','.java'] and p.name!='preserve_cold.py':shutil.copyfile(p,repro/p.name)
    for name in ['manifest-final.json','protocol.json','starlight-profile-provenance.json','probe-self-test.json']:
        shutil.copyfile(R/name,out/name)
    tests={}
    for repo in ['pulsar-profile','lightbench-profile','starlight-tests']:
        folder=R/repo/'build/test-results/test' if repo=='lightbench-profile' else R/'test-evidence'/repo
        roots=[ET.parse(p).getroot() for p in folder.glob('TEST-*.xml')]
        tests[repo]={k:sum(int(x.attrib.get(k,0)) for x in roots) for k in ['tests','failures','errors','skipped']}
    assert tests['pulsar-profile']['tests']==175 and tests['lightbench-profile']['tests']==60 and tests['starlight-tests']['tests']==7
    assert all(v['failures']==v['errors']==v['skipped']==0 for v in tests.values())
    (out/'test-summary.json').write_text(json.dumps(tests,indent=2),encoding='utf-8')
    files=[]
    for run in sorted((R/'runs').glob('formal-*')):
        for p in (run/'results').glob('*.json'):files.append((p,'runs/'+run.name+'/results/'+p.name))
        for name in ['stage.json','status.json','process-samples.json']:files.append((run/name,'runs/'+run.name+'/'+name))
    files.extend((R/name,name) for name in ['manifest-final.json','protocol.json','starlight-profile-provenance.json'])
    digests={}
    with zipfile.ZipFile(out/'matched-raw-data.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p,arc in files:
            data=p.read_bytes();assert not re.search(rb'(?i)(accessToken|clientToken|refreshToken|--username|--uuid)',data),p
            z.writestr(arc,data);digests[arc]=hashlib.sha256(data).hexdigest()
        z.writestr('SHA256SUMS.json',json.dumps(digests,indent=2))
    with zipfile.ZipFile(out/'matched-raw-data.zip') as z:
        assert z.testzip() is None
        assert all(hashlib.sha256(z.read(n)).hexdigest()==h for n,h in digests.items())
    print(json.dumps(dict(tests=tests,raw_files=len(files),raw_bytes=(out/'matched-raw-data.zip').stat().st_size),indent=2))
if __name__=='__main__':package()

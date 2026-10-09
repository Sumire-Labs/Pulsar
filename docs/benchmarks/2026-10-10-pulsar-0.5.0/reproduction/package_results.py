"""Curated raw-data bundles: no game saves, binaries, authentication files or full logs."""
from pathlib import Path
import json, hashlib, zipfile, csv, io
from collect import ROOT, load

def package(private=False):
    engines={"vanilla","alfheim","pulsar","starlight"} if private else {"vanilla","alfheim","pulsar"}
    folder=ROOT/("personal-comparison" if private else "publication")
    name="four-engine-raw-data.zip" if private else "benchmark-data-20261010.zip"
    files=[]
    for run in sorted((ROOT/"runs").glob("formal-v9-*")):
        stage=load(run/"stage.json")
        if stage["engine"] not in engines:continue
        for leaf in ["stage.json","status.json","process-samples.json"]:
            path=run/leaf
            if path.exists():files.append((path,"runs/"+run.name+"/"+leaf))
        for path in (run/"results").glob("*.json"):
            files.append((path,"runs/"+run.name+"/results/"+path.name))
    for leaf in ["manifest-final.json","protocol.json","supported-cases.json",
                 "pilot-generation-parity.json","starlight-provenance.json"]:
        files.append((ROOT/leaf,leaf))
    files.append((ROOT/"frozen/config/pulsar.cfg","config/pulsar.cfg"))
    for path in (ROOT/'tools').glob('*'):
        if path.is_file() and path.suffix in ('.py','.java'):
            files.append((path,'tools/'+path.name))
    files.append((ROOT/'analysis/final-audit.json','analysis/final-audit.json'))
    for family in ['blocks','sky','flatgen']:
        for path in (ROOT/'analysis'/('native-'+family)).glob('*/comparison.csv'):
            files.append((path,'analysis/native-'+family+'/verified/comparison.csv'))
    def payload(path,arc):
        if not private and arc.endswith('comparison.csv'):
            rows=list(csv.DictReader(io.StringIO(path.read_text(encoding='utf-8-sig'))))
            output=io.StringIO(); writer=csv.DictWriter(output,fieldnames=list(rows[0]));writer.writeheader()
            writer.writerows(row for row in rows if row['engine'] in engines)
            return output.getvalue().encode()
        if not private and arc=='analysis/final-audit.json':
            audit=json.loads(path.read_text())
            audit['native_comparator_crosschecks']=[x for x in audit['native_comparator_crosschecks'] if x['engine'] in engines]
            audit['timing_drift']=[x for x in audit['timing_drift'] if not x['run'].endswith('starlight')]
            return json.dumps(audit,indent=2).encode()
        return path.read_bytes()
    digests={arc:hashlib.sha256(payload(path,arc)).hexdigest() for path,arc in files}
    with zipfile.ZipFile(folder/name,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path,arc in files:archive.writestr(arc,payload(path,arc))
        archive.writestr("SHA256SUMS.json",json.dumps(digests,indent=2))
        archive.writestr("README.txt",
            "Controlled Minecraft1.12.2/Cleanroom Lightbench results.\n"
            "See publication README, protocol and manifest. All timestamps in raw reports are UTC.\n"
            "Warmup files are retained and must not be counted as scored trials.\n"
            "Invalid light/capped workloads are not valid speed scores. No equal-output ranking where hashes differ.\n"
            "Render callback intervals are not presented FPS; default client loaded chunk counts differ.\n"
            "No Minecraft saves, mod binaries, account files or full game logs are distributed in this bundle.\n")
    return folder/name

if __name__=="__main__":
    if load(ROOT/"progress.json")["status"] not in ("measurements-complete","analysis","complete"):
        raise SystemExit("Raw publication bundles require completed measurements.")
    print(package(False))
    print(package(True))

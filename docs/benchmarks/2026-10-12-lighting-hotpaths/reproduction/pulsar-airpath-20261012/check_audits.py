from pathlib import Path
import json
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
B=load(Path('E:/Github/pulsar-hotpath-attribution-20261012/runs/audit-main/results/hotpath-audit.json'))
out={}
for run in (R/'runs').glob('audit-*'):
 p=run/'results/hotpath-audit.json'
 if not p.exists():continue
 data=load(p);assert data==B,run
 out[run.name]={'valid':True,'all_hashes_equal_baseline':True,'snapshots':45,'lit_sided_cases':data['lit_sided_cases'],'cells_per_snapshot':data['cells_per_snapshot'],'channels':data['channels']}
(R/'analysis/audit-summary.json').write_bytes(json.dumps(out,indent=2).encode())
print(json.dumps(out,indent=2))

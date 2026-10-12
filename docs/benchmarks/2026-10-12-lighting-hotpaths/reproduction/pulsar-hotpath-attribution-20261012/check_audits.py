from pathlib import Path
import json
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
baseline=load(R/'runs/audit-main/results/hotpath-audit.json')
assert baseline['valid'] and baseline['lit_sided_cases']>0 and len(baseline['cases'])==15
results={}
for run in (R/'runs').glob('audit-*'):
 path=run/'results/hotpath-audit.json'
 if not path.exists():continue
 data=load(path);assert data==baseline,run
 results[run.name]={'valid':True,'all_case_hashes_equal_baseline':True,'lit_sided_cases':data['lit_sided_cases'],'snapshots':len(data['cases'])*3,'cells_per_snapshot':data['cells_per_snapshot'],'channels':data['channels']}
(R/'analysis/audit-summary.json').write_bytes(json.dumps(results,indent=2).encode())
print(json.dumps(results,indent=2))

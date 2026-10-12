from pathlib import Path
import json,re
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
fields=['sky_tasks','block_tasks','sky_positions','block_positions','sky_increase_entries','sky_decrease_entries','block_increase_entries','block_decrease_entries','sky_engines','block_engines']
rows={};counts={}
for run in (R/'runs').glob('sampled-*'):
 if not (run/'status.json').exists():continue
 stage=load(run/'stage.json');rep=int(re.search(r'-r(\d+)-',run.name).group(1))
 for row in load(run/'results/sampled-timing.json')['samples']:
  if '-sky-' in run.name and not row['phase'].startswith('sky_'):continue
  if '-blocks-' in run.name and not row['phase'].startswith('block_'):continue
  key=(rep,row['phase'],row['index']);rows.setdefault(key,{})[stage['wait_mode']]=row
  counts.setdefault(row['phase'],{}).setdefault(stage['wait_mode'],set()).add(tuple(row[f] for f in fields))
differences=[];compared=0
for key,modes in rows.items():
 if len(modes)!=3:continue
 compared+=1
 for mode in ['gated','gatedpoll']:
  diff={f:[modes[mode][f],modes['caller'][f]] for f in fields if modes[mode][f]!=modes['caller'][f]}
  if diff:differences.append({'key':key,'mode':mode,'different_fields':diff})
result={'fields':fields,'compared_phase_index_pairs':compared,'different_work_pairs':len(differences),'differences':differences,'unique_work_counts':{phase:{mode:[dict(zip(fields,x)) for x in sorted(values)] for mode,values in modes.items()} for phase,modes in counts.items()}}
(R/'analysis/work-equality.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps({'compared_phase_index_pairs':compared,'different_work_pairs':len(differences)}))

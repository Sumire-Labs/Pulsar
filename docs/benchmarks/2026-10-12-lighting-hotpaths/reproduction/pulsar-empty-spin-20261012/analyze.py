from pathlib import Path
import json,statistics as st,re,csv
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
rows=[]
for run in sorted((R/'runs').glob('formal-*')):
 if not (run/'status.json').exists():continue
 assert load(run/'status.json')['valid'],run
 s=load(run/'stage.json');b=load(next((run/'results').glob('*-updates-*.json')))['benchmark'];assert b['preflight']['initial_light_verified'] and b['preflight']['post_measurement_volume_verified']
 for p in b['phases']:
  assert p['all_samples_verified'];samples=p['samples'];n=len(samples);assert n==10000
  row={'run':run.name,'rep':int(re.search(r'-r(\d+)-',run.name).group(1)),'engine':s['engine'],'variant':s['variant'],'spin':int(s['idle_spin_ns']),'wait':s['wait_mode'],'phase':p['name']}
  for k in ['submission_nanos','barrier_nanos','completion_nanos']:
   row[k.replace('_nanos','_us')]=st.mean(x[k] for x in samples)/1000
   row['late_'+k.replace('_nanos','_us')]=st.mean(x[k] for x in samples[n//4:])/1000
  row['p50_us']=st.median(x['completion_nanos'] for x in samples)/1000;rows.append(row)
groups=[]
for key in sorted({(x['engine'],x['variant'],x['spin'],x['wait'],x['phase']) for x in rows}):
 rs=[x for x in rows if (x['engine'],x['variant'],x['spin'],x['wait'],x['phase'])==key]
 groups.append({'engine':key[0],'variant':key[1],'spin':key[2],'wait':key[3],'phase':key[4],'reps':len(rs),**{k:{'median':st.median(x[k] for x in rs),'min':min(x[k] for x in rs),'max':max(x[k] for x in rs)} for k in rs[0] if k.endswith('_us')}})
contrasts=[]
pairs=[(('empty',0,'legacy'),('base',0,'legacy')),(('empty',5000,'legacy'),('base',5000,'legacy')),(('base',5000,'legacy'),('base',0,'legacy')),(('empty',5000,'legacy'),('empty',0,'legacy')),(('empty',5000,'gatedpoll'),('empty',5000,'caller')),(('empty',5000,'gatedpoll'),('empty',5000,'legacy'))]
for phase in sorted({x['phase'] for x in rows}):
 for a,b in pairs:
  aa={x['rep']:x for x in rows if x['engine']=='pulsar' and x['phase']==phase and (x['variant'],x['spin'],x['wait'])==a};bb={x['rep']:x for x in rows if x['engine']=='pulsar' and x['phase']==phase and (x['variant'],x['spin'],x['wait'])==b}
  ds=[aa[k]['completion_us']-bb[k]['completion_us'] for k in sorted(aa.keys()&bb.keys())]
  if ds:contrasts.append({'phase':phase,'a':a,'b':b,'paired_deltas_us':ds,'median':st.median(ds),'min':min(ds),'max':max(ds)})
out={'runs':len({x['run'] for x in rows}),'groups':groups,'contrasts':contrasts,'trials':rows};(R/'analysis/summary.json').write_bytes(json.dumps(out,indent=2).encode())
if rows:
 with (R/'analysis/trials.csv').open('w',encoding='utf-8',newline='') as f:
  w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
print('runs',out['runs'])
for x in groups:print(x['engine'],x['variant'],x['spin'],x['wait'],x['phase'],x['reps'],round(x['completion_us']['median'],2))

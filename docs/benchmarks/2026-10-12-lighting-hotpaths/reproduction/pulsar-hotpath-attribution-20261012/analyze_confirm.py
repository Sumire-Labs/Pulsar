from pathlib import Path
import json,statistics as st,csv,re
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
rows=[]
for run in sorted((R/'runs').glob('confirm-*')):
 if not (run/'status.json').exists():continue
 assert load(run/'status.json')['valid'],run
 stage=load(run/'stage.json');b=load(next((run/'results').glob('*-updates-*.json')))['benchmark']
 assert b['preflight']['initial_light_verified'] and b['preflight']['post_measurement_volume_verified']
 for phase in b['phases']:
  assert phase['all_samples_verified']
  samples=phase['samples'];n=len(samples);assert n==10000
  rows.append({'run':run.name,'rep':int(re.search(r'-r(\d+)-',run.name).group(1)),'engine':stage['engine'],'variant':stage['variant'],'phase':phase['name'],'completion_us':st.mean(x['completion_nanos'] for x in samples)/1000,'p50_us':st.median(x['completion_nanos'] for x in samples)/1000,'late_completion_us':st.mean(x['completion_nanos'] for x in samples[n//4:])/1000})
groups=[]
for key in sorted({(x['engine'],x['variant'],x['phase']) for x in rows}):
 rs=[x for x in rows if (x['engine'],x['variant'],x['phase'])==key]
 groups.append({'engine':key[0],'variant':key[1],'phase':key[2],'reps':len(rs),**{k:{'median':st.median(x[k] for x in rs),'min':min(x[k] for x in rs),'max':max(x[k] for x in rs)} for k in ['completion_us','p50_us','late_completion_us']}})
contrasts=[]
for phase in sorted({x['phase'] for x in rows}):
 for a,b in [('skyunswitch','main'),('skyblock','skyunswitch'),('skyint','skyunswitch'),('skyblockint','skyblock'),('skyblockint','skyint')]:
  aa={x['rep']:x for x in rows if x['engine']=='pulsar' and x['phase']==phase and x['variant']==a};bb={x['rep']:x for x in rows if x['engine']=='pulsar' and x['phase']==phase and x['variant']==b}
  deltas=[aa[k]['completion_us']-bb[k]['completion_us'] for k in sorted(aa.keys()&bb.keys())]
  if deltas:contrasts.append({'phase':phase,'a':a,'b':b,'paired_deltas_us':deltas,'median':st.median(deltas),'min':min(deltas),'max':max(deltas)})
result={'runs':len({x['run'] for x in rows}),'groups':groups,'contrasts':contrasts,'trials':rows}
(R/'analysis/confirm-summary.json').write_bytes(json.dumps(result,indent=2).encode())
if rows:
 with (R/'analysis/confirm-trials.csv').open('w',encoding='utf-8',newline='') as f:
  writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
print('runs',result['runs'])
for x in groups:print(x['engine'],x['variant'],x['phase'],x['reps'],round(x['completion_us']['median'],2))

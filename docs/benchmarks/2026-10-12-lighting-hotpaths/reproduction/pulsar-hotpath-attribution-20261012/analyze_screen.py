from pathlib import Path
import json,statistics as st,csv
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
rows=[]
for run in sorted((R/'runs').glob('screen-*')):
 if not (run/'status.json').exists():continue
 assert load(run/'status.json')['valid'],run
 stage=load(run/'stage.json');b=load(next((run/'results').glob('*-updates-*.json')))['benchmark']
 assert b['preflight']['post_measurement_volume_verified']
 for phase in b['phases']:
  assert phase['all_samples_verified']
  samples=phase['samples'];n=len(samples)
  rows.append({'run':run.name,'engine':stage['engine'],'variant':stage['variant'],'phase':phase['name'],'completion_us':st.mean(x['completion_nanos'] for x in samples)/1000,'p50_us':st.median(x['completion_nanos'] for x in samples)/1000,'late_completion_us':st.mean(x['completion_nanos'] for x in samples[n//4:])/1000})
groups=[]
for key in sorted({(x['engine'],x['variant'],x['phase']) for x in rows}):
 rs=[x for x in rows if (x['engine'],x['variant'],x['phase'])==key]
 groups.append({'engine':key[0],'variant':key[1],'phase':key[2],'reps':len(rs),**{k:{'median':st.median(x[k] for x in rs),'min':min(x[k] for x in rs),'max':max(x[k] for x in rs)} for k in ['completion_us','p50_us','late_completion_us']}})
result={'runs':len({x['run'] for x in rows}),'groups':groups,'trials':rows}
(R/'analysis/screen-summary.json').write_bytes(json.dumps(result,indent=2).encode())
print('runs',result['runs'])
for x in groups:print(x['engine'],x['variant'],x['phase'],x['reps'],round(x['completion_us']['median'],2))

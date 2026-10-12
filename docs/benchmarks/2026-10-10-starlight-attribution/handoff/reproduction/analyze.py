from pathlib import Path
import json,statistics as st,csv,sys,re,math
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(R/'tools'))
from validate import validate
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
mean=st.mean
rows=[];sampled=[]
for run in sorted((R/'runs').iterdir()):
 if not run.name.startswith(('formal-','sampled-')) or not (run/'status.json').exists():continue
 validate(run)
 stage=load(run/'stage.json');report=load(next((run/'results').glob('*-updates-*.json')))['benchmark'];wait=load(run/'results/handoff-wait.json')
 for phase in report['phases']:
  if '-sky-' in run.name and not phase['name'].startswith('sky_'):continue
  if '-blocks-' in run.name and not phase['name'].startswith('block_'):continue
  samples=phase['samples'];n=len(samples)
  row={'run':run.name,'engine':stage['engine'],'mode':stage['wait_mode'],'variant':stage['variant'],'phase':phase['name'],'n':n,'await_calls':wait['await_calls'],'await_false':wait['await_false']}
  for k in ['submission_nanos','barrier_nanos','completion_nanos']:
   row[k.replace('_nanos','_us')]=mean(x[k] for x in samples)/1000
   row['late_'+k.replace('_nanos','_us')]=mean(x[k] for x in samples[n//4:])/1000
  ordered=sorted(x['completion_nanos'] for x in samples)
  row['edit_p50_us']=st.median(ordered)/1000
  row['edit_p95_us']=ordered[math.ceil(.95*n)-1]/1000
  row['edit_p99_us']=ordered[math.ceil(.99*n)-1]/1000
  row['edit_max_us']=ordered[-1]/1000
  rows.append(row)
 if stage['variant']=='on':
  diag=load(run/'results/sampled-timing.json')
  for phase in report['phases']:
   if '-sky-' in run.name and not phase['name'].startswith('sky_'):continue
   if '-blocks-' in run.name and not phase['name'].startswith('block_'):continue
   selected=[x for x in diag['samples'] if x['phase']==phase['name']]
   derived=[]
   for x in selected:
    first=min(x['sky_first_task_start_nanos'],x['block_first_task_start_nanos'])
    last=max(x['sky_last_task_end_nanos'],x['block_last_task_end_nanos'])
    body=x['sky_task_body_nanos']+x['block_task_body_nanos']
    assert first>=x['submission_nanos'] and x['completion_nanos']>=last
    derived.append({'completion_us':x['completion_nanos']/1000,'submit_us':x['submission_nanos']/1000,'dispatch_us':(first-x['submission_nanos'])/1000,'body_us':body/1000,'tail_us':(x['completion_nanos']-last)/1000,'between_tasks_us':(last-first-body)/1000,'nonbody_us':(x['completion_nanos']-body)/1000,
      'sky_tasks':x['sky_tasks'],'block_tasks':x['block_tasks'],'sky_positions':x['sky_positions'],'block_positions':x['block_positions'],
      'sky_inc':x['sky_increase_entries'],'sky_dec':x['sky_decrease_entries'],'block_inc':x['block_increase_entries'],'block_dec':x['block_decrease_entries'],
      'sky_first':int(x['sky_first_task_start_nanos']<x['block_first_task_start_nanos'])})
   sampled.append({'run':run.name,'mode':stage['wait_mode'],'phase':phase['name'],'n':len(selected),**{k:mean(x[k] for x in derived) for k in derived[0]}})
formal=[r for r in rows if r['run'].startswith('formal-')]
groups={}
for phase in sorted({r['phase'] for r in formal}):
 for engine,mode in sorted({(r['engine'],r['mode']) for r in formal}):
  rs=[r for r in formal if r['phase']==phase and r['engine']==engine and r['mode']==mode]
  if not rs:continue
  groups[phase+'/'+engine+'/'+mode]={'reps':len(rs),**{k:{'median':st.median(r[k] for r in rs),'min':min(r[k] for r in rs),'max':max(r[k] for r in rs)} for k in rs[0] if k.endswith('_us')},'await_calls':[r['await_calls'] for r in rs],'await_false':[r['await_false'] for r in rs]}
contrasts={}
for phase in sorted({r['phase'] for r in formal}):
 for a,b in [('gatedpoll','caller'),('gated','gatedpoll'),('future','legacy'),('legacy','caller'),('legacy','gatedpoll')]:
  aa={int(re.search(r'-r(\d+)-',r['run']).group(1)):r for r in formal if r['phase']==phase and r['engine']=='pulsar' and r['mode']==a}
  bb={int(re.search(r'-r(\d+)-',r['run']).group(1)):r for r in formal if r['phase']==phase and r['engine']=='pulsar' and r['mode']==b}
  paired=[aa[k]['completion_us']-bb[k]['completion_us'] for k in sorted(aa.keys()&bb.keys())]
  if paired:contrasts[phase+'/'+a+'-minus-'+b]={'paired_run_mean_deltas_us':paired,'median_delta_us':st.median(paired),'min_delta_us':min(paired),'max_delta_us':max(paired)}
summary={'formal_runs':len({r['run'] for r in formal}),'sampled_runs':len({r['run'] for r in sampled}),'groups':groups,'contrasts':contrasts,'sampled':sampled}
out=R/'analysis';out.mkdir(exist_ok=True)
(out/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
for name,data in [('trials.csv',rows),('sampled.csv',sampled)]:
 if data:
  with (out/name).open('w',newline='',encoding='utf-8') as f:
   writer=csv.DictWriter(f,fieldnames=list(data[0]));writer.writeheader();writer.writerows(data)
print(json.dumps({'formal_runs':summary['formal_runs'],'sampled_runs':summary['sampled_runs'],'completion_us':{k:round(v['completion_us']['median'],2) for k,v in groups.items()}},indent=2))

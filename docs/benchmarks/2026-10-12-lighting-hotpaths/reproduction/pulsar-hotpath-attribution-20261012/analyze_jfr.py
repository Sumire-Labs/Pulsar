from pathlib import Path
import json,collections
R=Path(__file__).resolve().parents[1]
results={}
for engine in ['pulsar','starlight']:
 p=R/'runs'/('profile-'+engine)/'execution-samples.json'
 if not p.exists():continue
 events=json.loads(p.read_text(encoding='utf-8'))['recording']['events']
 groups={}
 for e in events:
  v=e['values'];stack=v.get('stackTrace')
  if not stack:continue
  frames=stack['frames'];names=[f['method']['type']['name'].replace('/','.')+'.'+f['method']['name'] for f in frames]
  if not any('blocksChangedInChunk' in name for name in names):continue
  lane='sky' if any('ScalarSkyEngine' in name or 'SkyStarLightEngine' in name for name in names) else 'block'
  if engine=='pulsar' and not v.get('sampledThread',{}).get('javaName','').startswith('Pulsar-'):continue
  group=groups.setdefault(lane,{'count':0,'leaf':collections.Counter(),'line':collections.Counter(),'inclusive':collections.Counter()})
  group['count']+=1;group['leaf'][names[0]]+=1;group['line'][names[0]+':'+str(frames[0]['lineNumber'])]+=1
  for name in set(names):group['inclusive'][name]+=1
 results[engine]={lane:{'samples':d['count'],**{k:[{'location':name,'samples':n,'percent':100*n/d['count']} for name,n in d[k].most_common(30)] for k in ['leaf','line','inclusive']}} for lane,d in groups.items()}
(R/'analysis/jfr-hotspots.json').write_bytes(json.dumps(results,indent=2).encode())
for engine,lanes in results.items():
 for lane,d in lanes.items():
  print(engine,lane,d['samples'])
  for row in d['line'][:10]:print(round(row['percent'],1),row['location'])

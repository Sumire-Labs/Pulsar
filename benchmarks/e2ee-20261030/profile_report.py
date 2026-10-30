import collections,hashlib,json,re
from pathlib import Path

ROOT=Path(__file__).resolve().parent
RUNS=ROOT.parent/'e2ee-20261029/runs'
output={}
for name in ('rgb-profile-before','rgb-final-check'):
    directory=RUNS/name
    events=json.loads((directory/'samples.json').read_text())['recording']['events']
    total=context=0;top=collections.Counter()
    for event in events:
        value=event['values']
        if value['sampledThread']['javaName']!='Server thread':continue
        total+=1
        names=[frame['method']['type']['name']+'.'+frame['method']['name']
               for frame in (value.get('stackTrace') or {}).get('frames',[])]
        context+=any('ContextualLightManager' in method or 'TrackedLightSources' in method for method in names)
        if names:top[names[0]]+=1
    histogram=(directory/'idle-histogram.txt').read_text()
    pattern=r'^\s*\d+:\s+(\d+)\s+\d+\s+com.sumirelabs.pulsar.light.TrackedLightSources\$Source$'
    tracked=int(re.search(pattern,histogram,re.M)[1])
    inventory=json.loads((directory/'benchmark-inventory.json').read_text())
    output[name]={'server_thread_samples':total,'contextual_tracking_samples':context,
        'tracked_source_objects':tracked,'top_frame_counts':dict(top.most_common(15)),
        'jfr_sha256':hashlib.sha256((directory/'idle.jfr').read_bytes()).hexdigest(),
        'pulsar_jars':{k:v for k,v in inventory['mods'].items() if k.startswith('pulsar-')}}
output['notes']='20-second idle windows, 1 ms JFR ExecutionSample period. Sampling evidence only; excluded from final unprofiled medians.'
(ROOT/'profiles.json').write_text(json.dumps(output,indent=2),encoding='utf-8')
print('Profile summary written')

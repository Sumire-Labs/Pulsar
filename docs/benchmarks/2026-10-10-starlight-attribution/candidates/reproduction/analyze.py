from pathlib import Path
import json,statistics,csv,math
from validate import validate,load
R=Path(__file__).resolve().parents[1]
def stats(x):
    x=sorted(x);return dict(mean=statistics.mean(x),p50=statistics.median(x),p95=x[math.ceil(.95*len(x))-1],p99=x[math.ceil(.99*len(x))-1])
def analyze():
    assert load(R/'progress.json')['status'] in ['measurements-complete','analysis','complete']
    runs=sorted((R/'runs').glob('formal-*'));assert len(runs)==40;rows=[]
    for run in runs:
        validate(run);_,family,rep,engine,variant=run.name.split('-')
        b=load(next((run/'results').glob('*-updates-*.json')))['benchmark']
        for phase in b['phases']:
            m={k:stats([x[k] for x in phase['samples']]) for k in ['completion_nanos','submission_nanos','barrier_nanos']}
            cut=len(phase['samples'])//4;m['late_completion_nanos']=stats([x['completion_nanos'] for x in phase['samples'][cut:]])
            rows.append(dict(run=run.name,family=family,repetition=int(rep[1:]),engine=engine,variant=variant,phase=phase['name'],metrics=m))
    groups=[]
    cases=[('pulsar',v) for v in ['baseline','mask','late','both']]+[('starlight','baseline')]
    for family in ['blocks','sky']:
        for phase in sorted({r['phase'] for r in rows if r['family']==family}):
            for e,v in cases:
                cohort=[r for r in rows if (r['family'],r['phase'],r['engine'],r['variant'])==(family,phase,e,v)];assert len(cohort)==4
                metrics={}
                for key in cohort[0]['metrics']:
                    x=[r['metrics'][key]['mean'] for r in cohort]
                    metrics[key]=dict(median=statistics.median(x),min=min(x),max=max(x),mean=statistics.mean(x),median_run_p50=statistics.median(r['metrics'][key]['p50'] for r in cohort),median_run_p99=statistics.median(r['metrics'][key]['p99'] for r in cohort))
                groups.append(dict(family=family,phase=phase,engine=e,variant=v,metrics=metrics))
    effects=[]
    for f,p in [('blocks','block_place'),('blocks','block_remove'),('sky','sky_remove'),('sky','sky_place')]:
        for rep in range(1,5):
            def v(variant):return next(r['metrics']['completion_nanos']['mean'] for r in rows if (r['family'],r['phase'],r['engine'],r['variant'],r['repetition'])==(f,p,'pulsar',variant,rep))
            b,m,l,c=[v(x) for x in ['baseline','mask','late','both']]
            effects.append(dict(family=f,phase=p,repetition=rep,mask_effect_nanos=((m-b)+(c-l))/2,late_effect_nanos=((l-b)+(c-m))/2,interaction_nanos=c-m-l+b,combined_change_nanos=c-b))
    out=R/'analysis';out.mkdir(exist_ok=True);result=dict(rows=rows,groups=groups,effects=effects)
    (out/'results.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    flat=[dict(family=g['family'],phase=g['phase'],engine=g['engine'],variant=g['variant'],metric=k,**v) for g in groups for k,v in g['metrics'].items()]
    with (out/'summary.csv').open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(flat[0]));w.writeheader();w.writerows(flat)
    def get(f,p,e,v,k='completion_nanos'):return next(g['metrics'][k] for g in groups if (g['family'],g['phase'],g['engine'],g['variant'])==(f,p,e,v))
    text='# Controlled implementation candidates\n\n40 independent JVM launches, four per engine/variant/workload. Same native Lightbench commands, default automatic10 shared workers, copied worlds, artifact assertions and full light-volume qualification as the preceding studies. Profiling is disabled. Primary values are medians of four per-run means; ranges and percentiles are retained in CSV/JSON.\n\n'
    text+='## Native completion\n\n| Workload | Stock Pulsar µs | Direction mask µs | Late partner µs | Both µs | Starlight µs |\n|---|---:|---:|---:|---:|---:|\n'
    for f,p in [('blocks','block_place'),('blocks','block_remove'),('sky','sky_remove'),('sky','sky_place')]:
        values=[get(f,p,e,v)['median']/1000 for e,v in cases]
        text+=f"| {f}/{p} | "+' | '.join(f'{x:.2f}' for x in values)+' |\n'
    text+='\n## What changed\n\n'
    text+='- Direction mask: apply the same source-face exclusions to the existing six-direction bitmask once per queue entry, then select the existing ordered direction table. The old per-neighbor branch and the new table selection are equivalent for every64×64 mask pair, including queue flag high bits. Face-aware behavior remains.\n'
    text+='- Late partner: keep the same world/5×5 reservation, process the first light lane, then claim the other lane’s same-chunk task. Updates arriving while the first lane runs can still merge into the queued partner. Changes arriving after the partner starts retain a separate task and future. Worker count, admission/overlap rules, initial-light handling and light algorithms are retained.\n'
    text+='- No fallback notifications are deleted. Direct Chunk changes, equal-opacity face changes and contextual/Fluidlogged paths are retained. No reference-engine GPL source is copied.\n'
    text+='- Each variant JAR differs from the original only in the enumerated class entries. All other bytes/resources/metadata are identical, including the pre-tag version0.4.1-dev.24. The benchmark label is0.5.0 candidate; no release is tagged. Unmodified rebuilt LightQueue/PulsarEngine class bytes match the original.\n\n'
    text+='## Factor contrasts\n\nNegative is shorter completion. Per repetition: mask effect averages mask−stock and both−late; late effect averages late−stock and both−mask. Interaction=both−mask−late+stock. This accounts for measured implementation changes including JIT/layout effects, not just an instruction count.\n\n| Workload | Mean mask effect µs | Mean late effect µs | Mean interaction µs | Mean combined change µs |\n|---|---:|---:|---:|---:|\n'
    for f,p in [('blocks','block_place'),('blocks','block_remove'),('sky','sky_remove'),('sky','sky_place')]:
        cohort=[x for x in effects if x['family']==f and x['phase']==p]
        values=[statistics.mean(x[k] for x in cohort)/1000 for k in ['mask_effect_nanos','late_effect_nanos','interaction_nanos','combined_change_nanos']]
        text+=f"| {f}/{p} | "+' | '.join(f'{x:+.2f}' for x in values)+' |\n'
    text+='\nFour rotated condition orders are in protocol.json; no confidence interval or complete five-condition order balance is implied. Timings from other epochs are not subtracted. Native before/after light volumes and every edit probe passed; unit tests179 include concurrency, stop/arrival/future cases and exhaustive direction equivalence. These two fixtures alone are not a complete modpack/regression qualification; dense edits and generation should be checked before adopting a winner.\n'
    (out/'README.md').write_text(text,encoding='utf-8');print(text[:2400]);return result
if __name__=='__main__':analyze()

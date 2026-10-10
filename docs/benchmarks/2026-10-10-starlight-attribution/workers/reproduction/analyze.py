from pathlib import Path
import json,csv,statistics,math
from validate import load,selected,validate
R=Path(__file__).resolve().parents[1]
def stats(x):
    x=sorted(x);return dict(mean=statistics.mean(x),p50=statistics.median(x),p95=x[math.ceil(len(x)*.95)-1],p99=x[math.ceil(len(x)*.99)-1])
def analyze():
    assert load(R/'progress.json')['status'] in ['measurements-complete','analysis','complete']
    runs=sorted((R/'runs').glob('formal-*'));assert len(runs)==32
    rows=[]
    for run in runs:
        validate(run);_,family,rep,engine,pool,variant=run.name.split('-')
        b=load(next((run/'results').glob('*-updates-*.json')))['benchmark'];trace=load(run/'results/sampled-timing.json')['samples'] if variant=='on' else []
        for phase in b['phases']:
            p=phase['name'];raw=phase['samples'];m={}
            for k in ['completion_nanos','submission_nanos','barrier_nanos']:m[k]=stats([x[k] for x in raw])
            cut=len(raw)//4;m['late_completion_nanos']=stats([x['completion_nanos'] for x in raw[cut:]])
            chosen=[x for x in trace if x['phase']==p]
            if chosen:
                for k in chosen[0]:
                    if k in ['phase','index','overlap','completion_nanos','submission_nanos','barrier_nanos']:continue
                    values=[x[k] for x in chosen]
                    if any(v<0 for v in values):continue
                    m[k]=stats(values)
            rows.append(dict(run=run.name,family=family,repetition=int(rep[1:]),engine=engine,pool=pool,variant=variant,metrics=m,phase=p))
    groups=[]
    for family in ['blocks','sky']:
        for phase in sorted({x['phase'] for x in rows if x['family']==family}):
            for engine,pool,variant in [('pulsar','auto','original'),('pulsar','1','original'),('starlight','auto','original'),('pulsar','auto','on'),('pulsar','1','on')]:
                cohort=[x for x in rows if (x['family'],x['phase'],x['engine'],x['pool'],x['variant'])==(family,phase,engine,pool,variant)]
                assert len(cohort)==(4 if variant=='original' else 2)
                metrics={}
                for k in cohort[0]['metrics']:
                    values=[x['metrics'][k]['mean'] for x in cohort];metrics[k]=dict(median=statistics.median(values),min=min(values),max=max(values),median_run_p50=statistics.median(x['metrics'][k]['p50'] for x in cohort),median_run_p99=statistics.median(x['metrics'][k]['p99'] for x in cohort))
                groups.append(dict(family=family,phase=phase,engine=engine,pool=pool,variant=variant,independent_runs=len(cohort),metrics=metrics))
    out=R/'analysis';out.mkdir(exist_ok=True);result=dict(rows=rows,groups=groups)
    (out/'results.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    flat=[dict(family=g['family'],phase=g['phase'],engine=g['engine'],pool=g['pool'],variant=g['variant'],runs=g['independent_runs'],metric=k,**v) for g in groups for k,v in g['metrics'].items()]
    with (out/'summary.csv').open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(flat[0]));w.writeheader();w.writerows(flat)
    def get(f,p,e,pool,v,k='completion_nanos'):return next(g['metrics'][k]['median'] for g in groups if (g['family'],g['phase'],g['engine'],g['pool'],g['variant'])==(f,p,e,pool,v))
    text='# Worker-count control: unchanged Pulsar versus Starlight-1.12\n\n32 formal JVMs, full stored-light validation and actual live worker-count checks. The primary latency comparison uses unmodified original engine binaries with profiling disabled, four fresh JVMs per condition. The only Pulsar setting difference is automatic10 shared workers versus1 shared worker. Supplemental sampled stages use two JVMs per setting and are exploratory.\n\n'
    text+='## Native completion, four independent runs\n\n| Workload | Pulsar auto10 µs | Pulsar1 µs | Starlight µs | P1 vs P10 |\n|---|---:|---:|---:|---:|\n'
    for f,p in [('blocks','block_place'),('blocks','block_remove'),('sky','sky_remove'),('sky','sky_place')]:
        a,b,c=[get(f,p,e,pool,'original')/1000 for e,pool in [('pulsar','auto'),('pulsar','1'),('starlight','auto')]]
        text+=f'| {f}/{p} | {a:.2f} | {b:.2f} | {c:.2f} | {(b/a-1)*100:+.2f}% |\n'
    text+='\nValues are medians of per-run means; all run means/ranges and per-run percentiles are retained in CSV/JSON. This tuning experiment does not change the default -1 setting and does not measure multi-player or spatially independent bulk throughput. Starlight ignores the supplied Pulsar configuration file. Do not generalize one worker as best for every world/workload.\n\n'
    text+='## Supplemental stages: n2, sampled diagnostic edits\n\nBoth settings use the identical instrumented Pulsar binary, recorder and selected edit indices. These values locate a potential setting effect; they are not a replacement for the uninstrumented n4 latency comparison. Wall time includes preemption and probe effects.\n'
    for f,p,lane in [('blocks','block_place','block_'),('sky','sky_remove','sky_')]:
        text+=f'\n### {f}/{p}\n\n| Metric | Auto10 | One worker |\n|---|---:|---:|\n'
        for k in ['setup_nanos','propagation_nanos','publish_nanos','flood_nanos','positions','increase_entries','decrease_entries','tasks','engines']:
            scale=1000 if k.endswith('_nanos') else 1;label=k+' (µs)' if scale==1000 else k+'/edit'
            a,b=[get(f,p,'pulsar',pool,'on',lane+k)/scale for pool in ['auto','1']]
            text+=f'| {label} | {a:.2f} | {b:.2f} |\n'
    text+='\n## Scope and reproduction\n\nSame seed1 copied worlds, Minecraft1.12.2/Cleanroom0.6.12, Java25.0.4.1/G1/fixed8GiB/compact headers and fixed graphics/common mods as the matched-stage study. Blocks:5000 warmup/10000 measured pairs; Sky:2000/1000. A fresh JVM/world copy per condition. Default Version2 config retained; only `features.experimentalServerLightThreads` changes from -1 (observed10 workers) to1. No algorithm or functional code changes.\n\n'
    text+='The raw observer counts live threads named Pulsar-Parallel-; every Pulsar run must report1 or10 as intended. All initial/final stored-light volumes, per-edit probes, selected/native timing matches and exact discarded warmup capture counts must pass. Main-thread MSPT, presented FPS and engine-only memory are not measured by this experiment.\n'
    (out/'README.md').write_text(text,encoding='utf-8');print(text[:2200]);return result
if __name__=='__main__':analyze()

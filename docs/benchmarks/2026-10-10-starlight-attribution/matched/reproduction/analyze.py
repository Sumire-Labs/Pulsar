"""Matched-stage, sampled cross-engine analysis. Never infer a gap from unmatched timing epochs."""
from pathlib import Path
import json,statistics,csv,math,sys
from validate import load,selected,validate
R=Path(__file__).resolve().parents[1]
def stats(x):
    x=sorted(x);return dict(mean=statistics.mean(x),p50=statistics.median(x),p95=x[math.ceil(len(x)*.95)-1],p99=x[math.ceil(len(x)*.99)-1])
def analyze():
    assert load(R/'progress.json')['status'] in ['measurements-complete','analysis','complete']
    rows=[];trials=sorted((R/'runs').glob('formal-*'));assert len(trials)==48
    for run in trials:
        validate(run);stage=load(run/'stage.json');family,rep,engine,variant=run.name.split('-')[1:]
        b=load(next((run/'results').glob('*-updates-*.json')))['benchmark']
        trace=load(run/'results/sampled-timing.json')['samples'] if variant=='on' else []
        for phase in b['phases']:
            name=phase['name'];samples=phase['samples'];idx=[i for i in range(len(samples)) if selected(name,i)];others=[i for i in range(len(samples)) if not selected(name,i)]
            row=dict(run=run.name,family=family,repetition=int(rep[1:]),engine=engine,variant=variant,phase=name,samples=len(samples),selected_samples=len(idx),metrics={})
            for key in ['submission_nanos','barrier_nanos','completion_nanos']:
                row['metrics'][key]=stats([x[key] for x in samples])
            for label,indices in [('selected',idx),('other',others)]:
                row['metrics'][label+'_completion_nanos']=stats([samples[i]['completion_nanos'] for i in indices])
            row['metrics']['selected_minus_other_nanos']=dict(mean=row['metrics']['selected_completion_nanos']['mean']-row['metrics']['other_completion_nanos']['mean'])
            cut=len(samples)//4
            row['metrics']['late_completion_nanos']=stats([x['completion_nanos'] for x in samples[cut:]])
            for label,indices in [('selected',idx),('other',others)]:
                row['metrics']['late_'+label+'_completion_nanos']=stats([samples[i]['completion_nanos'] for i in indices if i>=cut])
            row['metrics']['late_selected_minus_other_nanos']=dict(mean=row['metrics']['late_selected_completion_nanos']['mean']-row['metrics']['late_other_completion_nanos']['mean'])
            if trace:
                captures=[dict(x) for x in trace if x['phase']==name]
                for x in captures:
                    first=min(x[lane+'first_engine_start_nanos'] for lane in ['sky_','block_'])
                    last=max(x[lane+'last_engine_end_nanos'] for lane in ['sky_','block_'])
                    x['before_first_engine_nanos']=first;x['engine_span_nanos']=last-first;x['after_last_engine_nanos']=x['completion_nanos']-last
                    assert first>=0 and last>=first and last<=x['completion_nanos']
                    x['all_engine_stages_nanos']=sum(x[lane+s+'_nanos'] for lane in ['sky_','block_'] for s in ['setup','sections','propagation','publish','cleanup'])
                    x['non_engine_nanos']=x['completion_nanos']-x['all_engine_stages_nanos']
                    x['between_engines_and_probe_nanos']=x['engine_span_nanos']-x['all_engine_stages_nanos']
                    assert x['non_engine_nanos']>=0 and x['between_engines_and_probe_nanos']>=0
                for key in captures[0]:
                    if key not in ['phase','index','overlap','submission_nanos','barrier_nanos','completion_nanos']:
                        values=[x[key] for x in captures]
                        if key.endswith('_nanos') and any(x<0 for x in values):continue
                        row['metrics'][key]=stats(values)
                        late=[x[key] for x in captures if x['index']>=cut]
                        row['metrics']['late_'+key]=stats(late)
            rows.append(row)
    groups=[]
    for family in ['blocks','sky']:
        for phase in sorted({x['phase'] for x in rows if x['family']==family}):
            for engine in ['pulsar','starlight']:
                for variant in ['original','off','on']:
                    cohort=[x for x in rows if (x['family'],x['phase'],x['engine'],x['variant'])==(family,phase,engine,variant)];assert len(cohort)==4
                    m={}
                    for key in cohort[0]['metrics']:
                        values=[x['metrics'][key]['mean'] for x in cohort]
                        m[key]=dict(median=statistics.median(values),min=min(values),max=max(values))
                        for percentile in ['p50','p95','p99']:
                            if all(percentile in x['metrics'][key] for x in cohort):
                                m[key]['median_run_'+percentile]=statistics.median(x['metrics'][key][percentile] for x in cohort)
                    groups.append(dict(family=family,phase=phase,engine=engine,variant=variant,runs=4,metrics=m))
    # Per-repetition matched engine contrasts and probe difference-in-differences.
    contrasts=[]
    for family,phase in [('blocks','block_place'),('blocks','block_remove'),('sky','sky_remove'),('sky','sky_place')]:
        for rep in range(1,5):
            def find(e,v):return next(x['metrics'] for x in rows if (x['family'],x['phase'],x['engine'],x['variant'],x['repetition'])==(family,phase,e,v,rep))
            for engine in ['pulsar','starlight']:
                on,off=find(engine,'on'),find(engine,'off')
                contrasts.append(dict(family=family,phase=phase,repetition=rep,engine=engine,captured_probe_excess_nanos=on['selected_minus_other_nanos']['mean']-off['selected_minus_other_nanos']['mean'],all_edit_probe_change_nanos=on['completion_nanos']['mean']-off['completion_nanos']['mean']))
    out=R/'analysis';out.mkdir(exist_ok=True);result=dict(rows=rows,groups=groups,probe_contrasts=contrasts)
    (out/'results.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    flat=[dict(family=g['family'],phase=g['phase'],engine=g['engine'],variant=g['variant'],metric=k,**v) for g in groups for k,v in g['metrics'].items()]
    with (out/'summary.csv').open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(flat[0]));w.writeheader();w.writerows(flat)
    def g(f,p,e,v,k='completion_nanos'):return next(x['metrics'][k]['median'] for x in groups if (x['family'],x['phase'],x['engine'],x['variant'])==(f,p,e,v))
    text='# Pulsar / Starlight-1.12 matched pipeline study\n\nFour independent JVMs per engine/condition/workload; 48 formal launches. Original, probe-disabled and sampled-probe conditions share one harness/common-mod set. Both engines use identical deterministic sample indices. Per-cell loops have no timestamps; data buffers are allocated before warmup and exported after measurement. Values are medians of independent per-run means, not pooled edits or confidence intervals.\n\n'
    text+='## Native completion and probe controls\n\n| Workload | Engine | Original µs | Disabled µs | Sampled on µs | On vs disabled |\n|---|---|---:|---:|---:|---:|\n'
    for f,p in [('blocks','block_place'),('blocks','block_remove'),('sky','sky_remove'),('sky','sky_place')]:
        for e in ['pulsar','starlight']:
            a,b,c=[g(f,p,e,v)/1000 for v in ['original','off','on']];text+=f'| {f}/{p} | {e} | {a:.2f} | {b:.2f} | {c:.2f} | {(c/b-1)*100:+.2f}% |\n'
    text+='\n## Captured-edit probe perturbation\n\nOverall overhead is diluted because only about1/16 edits are captured. Therefore, we separately estimate capture perturbation as (on selected − on others) − (disabled selected − disabled others), using the same edit-index selection. This is an observational contrast, not a guaranteed correction factor for individual stages.\n\n| Workload | Engine | Median capture excess µs | Run min–max µs |\n|---|---|---:|---:|\n'
    for f,p in [('blocks','block_place'),('sky','sky_remove')]:
        for e in ['pulsar','starlight']:
            x=[c['captured_probe_excess_nanos']/1000 for c in contrasts if (c['family'],c['phase'],c['engine'])==(f,p,e)]
            text+=f'| {f}/{p} | {e} | {statistics.median(x):.2f} | {min(x):.2f}–{max(x):.2f} |\n'
    text+='\n## Matched engine stages\n\nEach lane is shown separately; lane times and main-thread submission can overlap. prepare/flood are nested inside propagation. Do not sum every row into native completion time. Stage clocks measure wall time and may include OS preemption.\n'
    for f,p,lane in [('blocks','block_place','block_'),('sky','sky_remove','sky_')]:
        text+=f'\n### {f}/{p}: {lane[:-1]} lane\n\n| Stage | Pulsar µs/edit | Starlight µs/edit | Pulsar − Starlight µs |\n|---|---:|---:|---:|\n'
        for k in ['setup_nanos','sections_nanos','propagation_nanos','publish_nanos','cleanup_nanos','prepare_nanos','flood_nanos']:
            a,b=[g(f,p,e,'on',lane+k)/1000 for e in ['pulsar','starlight']];text+=f'| {k} | {a:.2f} | {b:.2f} | {a-b:+.2f} |\n'
        text+='\n| Captured edit / work | Pulsar | Starlight |\n|---|---:|---:|\n'
        for k in ['positions','increase_entries','decrease_entries','engines']:
            text+=f"| {lane+k} per edit | {g(f,p,'pulsar','on',lane+k):.2f} | {g(f,p,'starlight','on',lane+k):.2f} |\n"
        for k in ['before_first_engine_nanos','engine_span_nanos','after_last_engine_nanos']:
            text+=f"| {k} (µs) | {g(f,p,'pulsar','on',k)/1000:.2f} | {g(f,p,'starlight','on',k)/1000:.2f} |\n"
        text+='\nThe three engine-envelope intervals are non-overlapping per edit. The middle span includes both lanes and any gaps/coordination; it is not pure propagation CPU. Separate medians need not add exactly.\n'
    text+='\n## Additive decomposition of the captured completion gap\n\n'
    text+='Because runs reject overlapping engine writers, the two lanes’ five engine stages can be summed without double-counting engine intervals. Native completion minus this sum is the non-engine residual: queue/coordination, caller work, gaps and probe bookkeeping. '
    text+='This table uses the **arithmetic mean of four per-run captured means**, so component differences add exactly to the captured completion difference; other tables use medians. It is diagnostic wall-time accounting, not a correction for probe overhead or proof of a specific instruction-level cause.\n\n'
    for f,p in [('blocks','block_place'),('sky','sky_remove')]:
        text+=f'### {f}/{p}\n\n| Component | Pulsar µs | Starlight µs | P − S µs |\n|---|---:|---:|---:|\n'
        matched=[x for x in rows if x['family']==f and x['phase']==p and x['variant']=='on']
        for name in ['setup','sections','propagation','publish','cleanup','non_engine','selected_completion']:
            values=[]
            for e in ['pulsar','starlight']:
                cohort=[x for x in matched if x['engine']==e]
                if name in ['non_engine','selected_completion']:
                    value=statistics.mean(x['metrics'][name+'_nanos']['mean'] for x in cohort)
                else:value=statistics.mean(sum(x['metrics'][lane+name+'_nanos']['mean'] for lane in ['sky_','block_']) for x in cohort)
                values.append(value/1000)
            text+=f'| {name} | {values[0]:.2f} | {values[1]:.2f} | {values[0]-values[1]:+.2f} |\n'
    text+='\n## Methods, provenance and scope\n\n'
    text+='A fixed last75%-of-measured-indices sensitivity is retained in summary metrics (`late_*`) for all conditions and sampled stages. The cutoff was selected before inspecting numeric results, not tuned to a winning outcome. Primary tables retain all measured pairs. Early/late differences can reflect JIT, frequency, cache or other effects and do not identify a cause by themselves.\n\n'
    text+='The sampled recording path also executes during warmup. Every run must report the exact expected positive warmup capture count, fully drain it, clear its preallocated records and discard it before measured pairs. The preceding cold-sampler epoch was retained separately and is not pooled here. Active warmup reduces first-entry effects but does not prove perfect steady state; captured perturbation and run ranges remain necessary.\n\n'
    text+='- Same seed1 disposable worlds, fixed floorY3, flags16, 5000/10000 block warmup/measured pairs and2000/1000 roof-profile pairs. Sky roofY254,64×64; independent initial/final full Sky/Block volume checks and per-edit probes. Each sampled/native latency is exactly matched by phase/index.\n'
    text+='- Minecraft1.12.2/Cleanroom0.6.12, AdoptiumJava25.0.4.1/G1/fixed8GiB/compact headers, RyzenAI MAX+395/32 logical cores, Radeon8060S,1280×720/renderDistance4. Pulsar defaultConfigVersion2/-1 means10 shared workers. Settings/common mods are frozen. No external profiler agent.\n'
    text+='- Pulsar original source75b051d; current documentation-only source33d81bb has the same runtime source. Starlight0a7d94e uses the same manifest-cleaned original binary from the prior study. Only three engine source files and the resulting nested direction class differ in the Star probe binary;29 other original Java classes are byte-identical. No GPL reference source is integrated into Pulsar.\n'
    text+='- Starlight stages run synchronously on the server caller; Pulsar uses its existing shared scheduler. The probe records server engines only and rejects overlapping recording writers rather than silently accepting unsafe plain-buffer aggregation. It does not change lighting policy, scheduling or queue coalescing.\n'
    text+='- The four engine-condition orders are recorded in the protocol; they are the first four rows of a six-condition Williams design, not a complete six-repetition balance. Fresh JVM/world per run. Main-thread sleep/worker CPU/client mesh/network/presentation are separate scopes.\n'
    text+='- New measurements must be interpreted within this epoch; do not subtract these stage times from older62µs/55µs totals. Differences in stage time identify where time is spent, but a causal claim that a particular feature causes the gap needs an ablation or controlled implementation change.\n'
    (out/'README.md').write_text(text,encoding='utf-8')
    print(text[:2200]);return result
if __name__=='__main__':analyze()

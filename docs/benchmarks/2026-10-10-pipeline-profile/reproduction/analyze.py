"""Offline only: validate, aggregate independent runs and keep nested/overlapping timers distinct."""
from pathlib import Path
import json,csv,statistics,math,sys,zipfile,hashlib,re
from validate import validate
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def dist(x):
    x=sorted(x);return dict(mean=statistics.mean(x),p50=statistics.median(x),p95=x[math.ceil(.95*len(x))-1],p99=x[math.ceil(.99*len(x))-1])
def analyze():
    assert load(R/'progress.json')['status'] in ['measurements-complete','complete']
    runs=sorted((R/'runs').glob('formal-*'));assert len(runs)==24
    results=[]
    for run in runs:
        check=validate(run);b=load(next((run/'results').glob('*-updates-*.json')))['benchmark']
        family,rep,variant=run.name.split('-')[1:];rows=load(run/'results/phase-timing.json')['samples'] if variant=='on' else []
        for phase in b['phases']:
            row=dict(run=run.name,family=family,repetition=int(rep[1:]),variant=variant,phase=phase['name'],samples=len(phase['samples']))
            for field in ['submission_nanos','barrier_nanos','completion_nanos']:
                row[field]=dist([x[field] for x in phase['samples']])
            if rows:
                scoped=[x for x in rows if x['phase']==phase['name']]
                for x in scoped:
                    starts=[x[lane+'first_task_start_offset_nanos'] for lane in ['sky_','block_'] if x[lane+'tasks']>0]
                    ends=[x[lane+'last_task_end_offset_nanos'] for lane in ['sky_','block_'] if x[lane+'tasks']>0]
                    assert starts and ends
                    x['before_first_task_nanos']=min(starts)
                    x['worker_span_nanos']=max(ends)-min(starts)
                    x['after_last_task_nanos']=x['completion_nanos']-max(ends)
                for k in scoped[0]:
                    if k in ['phase','submission_nanos','barrier_nanos','completion_nanos']:continue
                    row[k]=dist([x.get(k,0) for x in scoped])
            results.append(row)
    groups=[]
    for family in ['blocks','sky']:
        phases=sorted({r['phase'] for r in results if r['family']==family})
        for phase in phases:
            for variant in ['baseline','off','on']:
                rows=[r for r in results if r['family']==family and r['phase']==phase and r['variant']==variant];assert len(rows)==4
                group=dict(family=family,phase=phase,variant=variant,independent_runs=4,metrics={})
                for key in rows[0]:
                    if not isinstance(rows[0][key],dict):continue
                    values=[r[key]['mean'] for r in rows]
                    group['metrics'][key]=dict(median_run_mean=statistics.median(values),min_run_mean=min(values),max_run_mean=max(values),median_run_p99=statistics.median(r[key]['p99'] for r in rows))
                groups.append(group)
    out=R/'analysis';out.mkdir(exist_ok=True)
    result=dict(runs=results,groups=groups)
    (out/'results.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    flat=[]
    for g in groups:
        for k,v in g['metrics'].items():flat.append(dict(family=g['family'],phase=g['phase'],variant=g['variant'],metric=k,**v))
    with (out/'summary.csv').open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(flat[0]));w.writeheader();w.writerows(flat)
    def g(f,p,v,k='completion_nanos'):return next(x['metrics'][k]['median_run_mean'] for x in groups if x['family']==f and x['phase']==p and x['variant']==v)
    text='# Pulsar update pipeline diagnostics — October 10, 2026\n\n'
    text+='Four independent JVM runs per condition; 24 formal launches, all workload checks passed. Separate diagnostic copies of Pulsar and Lightbench; original release benchmark inputs/results remain frozen. Values below are medians of four per-run means. These diagnostic runs do not replace the published 0.5.0 comparison.\n\n'
    text+='## Probe overhead controls\n\n| Fixture / action | Original (µs) | Probe build, disabled (µs) | Probe enabled (µs) | Enabled vs disabled | Disabled vs original |\n|---|---:|---:|---:|---:|---:|\n'
    for f,p in [('blocks','block_place'),('blocks','block_remove'),('sky','sky_remove'),('sky','sky_place')]:
        a,b,c=[g(f,p,v)/1000 for v in ['baseline','off','on']]
        text+=f'| {f}/{p} | {a:.2f} | {b:.2f} | {c:.2f} | {(c/b-1)*100:+.1f}% | {(b/a-1)*100:+.1f}% |\n'
    text+='\nBoth controls use the same diagnostic Lightbench JAR with its bridge disabled. The original Pulsar binary is SHA256 b985bc77f630da1a1f5f403d7bf0f218455e7f3a6a67f0bc88652838de9cffd2. '
    text+='The instrumented binary differs by optional timers only; its pre-tag version metadata differs. The enable flag is the intentional JVM/config difference. Probe overhead includes timestamp reads, atomic aggregation, changed JIT/code layout, per-sample allocation and possible GC/scheduling effects; this experiment cannot isolate those causes. Do not subtract a constant overhead from individual stages.\n\n'
    text+='**Placement probe overhead is about13% in this study, larger than the earlier approximately7µs cross-engine placement gap. The stage breakdown is diagnostic, not a microsecond-accurate reconstruction of uninstrumented Pulsar.** '
    text+='The original binary also ran at a different absolute placement time here than in the earlier published study, despite the same artifact and protocol. Execution-time environmental/JIT variation is not isolated; do not subtract these new stage times from old Starlight totals.\n\n'
    text+='## Per-lane stages, probe enabled\n\nAll stage values are wall time in µs per edit, summed over matching lane tasks. Timers can include OS preemption. Queue-to-task-start is a sum across tasks, not an exclusive critical-path slice. '
    text+='The prepare/flood rows are nested inside propagation. Sky and Block can overlap each other and server submission. **Do not add the entire table to obtain completion latency.**\n\n'
    labels={'queue_to_task_start':'Queue creation → task start (sum)','task_body':'Task body, excluding future completion','engine_setup':'Engine cache setup','engine_sections':'Section reconciliation','engine_propagation':'Propagation (inclusive)','engine_publish':'Publish SWMR + vanilla storage','engine_cleanup':'Cache cleanup','propagation_prepare':'↳ Column/check preparation','propagation_flood':'↳ Decrease + increase flood'}
    for f,p in [('blocks','block_place'),('sky','sky_remove')]:
        text+=f'### {f}/{p}\n\n'
        text+=f"Submission: **{g(f,p,'on','submission_nanos')/1000:.2f} µs**; completion barrier: **{g(f,p,'on','barrier_nanos')/1000:.2f} µs**; total completion: **{g(f,p,'on')/1000:.2f} µs**.\n\n"
        text+='| Worker stage | Sky lane (µs/edit) | Block lane (µs/edit) |\n|---|---:|---:|\n'
        for k,label in labels.items():text+=f"| {label} | {g(f,p,'on','sky_'+k+'_nanos')/1000:.2f} | {g(f,p,'on','block_'+k+'_nanos')/1000:.2f} |\n"
        text+='\n| Work / timeline | Sky lane | Block lane |\n|---|---:|---:|\n'
        for k in ['tasks','positions','increase_entries','decrease_entries']:
            text+=f"| Mean {k}/edit | {g(f,p,'on','sky_'+k):.2f} | {g(f,p,'on','block_'+k):.2f} |\n"
        for k in ['first_task_start_offset_nanos','last_task_end_offset_nanos']:
            text+=f"| {k} (µs from edit start) | {g(f,p,'on','sky_'+k)/1000:.2f} | {g(f,p,'on','block_'+k)/1000:.2f} |\n"
        text+='\nOffsets show first task entry and last body end; completion bookkeeping/wakeup follows. Not every lane necessarily receives exactly one task per edit because changes can be queued/claimed separately.\n\n'
        text+='Non-overlapping wall-clock envelope (µs/edit): '
        text+=', '.join(f"{label} **{g(f,p,'on',key)/1000:.2f}**" for key,label in [('before_first_task_nanos','edit start → first task'),('worker_span_nanos','first task → last body end'),('after_last_task_nanos','last body end → caller completion')])+'. '
        text+='These intervals sum to completion per sample; medians of separate run means need not sum exactly. The middle interval includes any gaps and overlapping main-thread work, so it is not pure propagation or total CPU.\n\n'
    text+='## Methods and limits\n\n'
    text+='- Blocks: `lightbench blockupdates 5000 10000`, open-sky glowstone; Sky: `lightbench updates 2000 1000`, tall-roof fixture. The roof fixture’s block phases are retained separately. Update flags16. Warmup is not instrumented.\n'
    text+='- Same copied worlds, seed1, common mods, Java25.0.4.1/Cleanroom0.6.12, fixed8GiB/G1/compact headers, 1280×720/renderDistance4, default ConfigVersion2 and automatic10 shared workers as the prior study. Only explicit JVM opt-in differs between enabled and disabled diagnostic builds.\n'
    text+='- Orders: original→off→on; off→on→original; on→original→off; on→off→original. Four repetitions, not a fully pair-balanced six-order design. Fresh JVM/world per condition.\n'
    text+='- Independent complete stored Sky/Block volumes before/after measurement, per-edit checks, native sample count and every diagnostic/native latency matched exactly. Nested timings and task-before-completion ordering were checked. Concurrent lane accumulation, sample reset, overlap guard and disabled behavior passed a separate probe self-test. Existing Pulsar175 and Lightbench60 tests passed in the diagnostic copies.\n'
    text+='- The profiler covers the default shared scheduler. It is not a dedicated-lane or client profiler. Per-cell loops have no added timestamp/atomic operation; timers are at task/engine boundaries. Queue timestamp begins at task construction, before insertion finishes; it includes wakeup/claim/partner-lane serialization, and is not pure OS scheduler delay.\n'
    text+='- Final engine publication includes SWMR and vanilla nibble synchronization. Client mesh rebuild, network latency and presented FPS are outside this server-light completion metric.\n'
    text+='- These measurements locate time inside Pulsar. They do **not** attribute the original roughly7µs placement gap to Starlight without equivalent per-stage Starlight instrumentation. The small Sky-opening gap must also be interpreted against run ranges and probe overhead. No engine algorithm was replaced or optimized during this diagnostic task.\n\n'
    text+='[Run ranges and full summary](summary.csv). Original benchmark: ../2026-10-10-pulsar-0.5.0/README.md. Instrumentation patches, probe source, validator, analysis and raw JSON are included locally. No GPL reference Java is copied into Pulsar or distributed with these results.\n'
    (out/'README.md').write_text(text,encoding='utf-8')
    print(text[:1700])
if __name__=='__main__':analyze()

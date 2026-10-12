from pathlib import Path
import json,statistics as st
R=Path(__file__).resolve().parents[1]
D=json.loads((R/'analysis/summary.json').read_text(encoding='utf-8'))
assert D['formal_runs']==48 and D['sampled_runs']==12
phases=[('block_place','Block placement'),('block_remove','Block removal'),('sky_remove','Sky opening'),('sky_place','Sky closing')]
cases=[('pulsar','legacy','Pulsar normal async + legacy wait'),('pulsar','future','Pulsar normal async + direct future'),('pulsar','gated','Pulsar held + worker + direct future'),('pulsar','gatedpoll','Pulsar held + worker + polling'),('pulsar','caller','Pulsar held + caller'),('starlight','legacy','Starlight-1.12')]
lines=['# Async handoff and completion waiting','',
'Current Pulsar scheduler3347942, source main87e75ec. Diagnostic builds labeled as the 0.5.0 candidate. This study changes execution placement and completion waiting without changing propagation or face-opacity semantics. No production runtime change is adopted.','',
'48 primary fresh-JVM trials (six conditions, two fixtures, four repetitions), plus12 separate sampled boundary trials. All primary trials have timing hooks disabled. Lightbench60 tests and the scheduler isolation self-test pass. Every measured edit and each initial/final light volume passes the retained checks.','',
'## Native completion latency','',
'Values are microseconds: median of four run means, followed by run min–max. These are isolated edit-completion latencies, not a throughput comparison.','',
'| Condition | Block placement | Block removal | Sky opening | Sky closing |','|---|---:|---:|---:|---:|']
for engine,mode,label in cases:
 values=[]
 for phase,title in phases:
  v=D['groups'][f'{phase}/{engine}/{mode}']['completion_us'];values.append(f"{v['median']:.2f} ({v['min']:.2f}–{v['max']:.2f})")
 lines.append('| '+label+' | '+' | '.join(values)+' |')
lines+=['','## Within-repetition comparisons','','Paired differences between fresh-JVM run means within the same repetition. The four repetitions are sequential counterbalanced trials, not simultaneous or randomized per-edit crossovers. Positive means the first condition takes longer.','','| Phase | Held worker/poll − held caller | Held worker/future − held worker/poll | Normal async/future − normal async/legacy |','|---|---:|---:|---:|']
for phase,title in phases:
 values=[]
 for pair in ['gatedpoll-minus-caller','gated-minus-gatedpoll','future-minus-legacy']:
  v=D['contrasts'][phase+'/'+pair];values.append(f"{v['median_delta_us']:+.2f} ({v['min_delta_us']:+.2f}…{v['max_delta_us']:+.2f})")
 lines.append('| '+title+' | '+' | '.join(values)+' |')
lines+=['','## Separate sampled boundaries','','Arithmetic mean of two captured run means per condition; the same deterministic1/16 indices are sampled. The active recording branch is warmed and discarded before measurement. These include timing overhead and must not be subtracted from the native medians above.','','| Phase / held execution | Submission | Dispatch interval | Task bodies | Between tasks | Completion observation | Total |','|---|---:|---:|---:|---:|---:|---:|']
for phase,title in phases:
 for mode,label in [('gated','worker/future'),('gatedpoll','worker/poll'),('caller','caller')]:
  rows=[x for x in D['sampled'] if x['phase']==phase and x['mode']==mode]
  values=[f'{st.mean(x[k] for x in rows):.2f}' for k in ['submit_us','dispatch_us','body_us','between_tasks_us','tail_us','completion_us']]
  lines.append('| '+title+' / '+label+' | '+' | '.join(values)+' |')
lines+=['','## Interpretation boundaries','',
'The primary execution-placement comparison is held worker/poll versus held caller. Both queue the complete producer edit before any worker can claim it. It includes releasing the workers and observing completion, OS scheduling and any execution-thread effects on the task body. It is not a measurement of semaphore cost or context-switch cost in isolation.','',
'Holding the producer changes batching and removes normal producer/consumer overlap. Consequently its difference cannot simply be subtracted from the normal Pulsar-versus-Starlight gap. The normal-async legacy control also uses the diagnostic scheduler class; its extra owner check and extracted execute method are shared across all Pulsar conditions. Main runtime itself is unchanged.','',
'Legacy Lightbench waiting calls the existing unload-budget API and then globally polls pending work. The direct-future diagnostic bypasses that budget and waits the existing nearby-chunk futures before the same global check. Changing the wait policy changes measured completion latency; neither result alone is pure propagation time. Aggregate await counters include preparation and warmup as well as measurement.','',
'See [METHOD.md](METHOD.md), [protocol.json](protocol.json), [summary.json](summary.json), [trials.csv](trials.csv) and [sampled.csv](sampled.csv) for exact definitions, last75% sensitivities and per-run data. Six first-harness native trials and three pilots are retained outside this package as superseded exploratory data, and are excluded from every table.','',
'The propagation-loop, direction-dependent opacity and other bookkeeping investigations remain deferred in [FOLLOW-UP.md](../FOLLOW-UP.md). No reference-engine code was copied into production.','',
'![Native completion comparison](handoff-latency.png)','',
'![Sampled boundary decomposition](handoff-boundaries.png)','',
'Raw JSON and process observations are in [raw-data.zip](raw-data.zip), with internal checksums. Engine binaries, worlds, account data and full game logs are excluded. Exact input hashes and changed class entries are retained in [manifest-final.json](manifest-final.json) and [variant-provenance.json](variant-provenance.json). Diagnostic sources and validation tools are under reproduction/.']
(R/'analysis/README.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Wrote numeric report tables; review findings before delivery')

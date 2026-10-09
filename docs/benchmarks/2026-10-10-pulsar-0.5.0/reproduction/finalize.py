"""Check the completed plan and add auditable publication supplements."""
from pathlib import Path
import csv, json, statistics, math, shutil
from collect import ROOT, load, LABELS

d=load(ROOT/'analysis/results.json')
expected={(f,r,e) for f in ['blocks','sky','continuous','flatgen','naturalgen','flattravel','naturaltravel']
          for r in range(1,5) for e in LABELS if not (e=='vanilla' and f in ['sky','continuous'])}
actual={(t['family'],t['repetition'],t['engine']) for t in d['trials']}
assert actual==expected and len(d['trials'])==104
assert not d['failures'] and all(t['valid'] for t in d['trials'])
assert all(s['complete_four_run_cohort'] for s in d['summary'])
assert d['parity']['flatgen']['matched'] and d['parity']['flattravel']['matched']
assert all(t['observer'].get('post_measurement_settle_ticks',0)>=100 for t in d['trials'])
assert {t['observer']['pulsar_parallel_worker_count'] for t in d['trials'] if t['engine']=='pulsar'}=={10}

# Cross-check independent aggregation against Lightbench's native per-run CSV.
checks=[]
for family in ['blocks','sky','flatgen']:
    paths=list((ROOT/'analysis'/('native-'+family)).glob('*/comparison.csv'))
    assert len(paths)==1
    native=list(csv.DictReader(paths[0].open(encoding='utf-8-sig')))
    for engine in LABELS:
        metrics=['generation_until_lit'] if family=='flatgen' else sorted({x['phase'] for x in native})
        for metric in metrics:
            rows=[x for x in native if x['engine']==engine and (family=='flatgen' or x['phase']==metric)]
            own=[x['average'] for x in d['metrics'] if x['family']==family and x['metric']==metric and x['engine']==engine and x['valid']]
            values=[float(x['total_nanos'] if family=='flatgen' else x['completion_average_nanos']) for x in rows]
            assert len(values)==len(own)
            assert all(math.isclose(x,y,rel_tol=1e-9,abs_tol=1e-5) for x,y in zip(sorted(values),sorted(own)))
            checks.append(dict(family=family,engine=engine,metric=metric,runs=len(own)))

drift=[]
for t in d['trials']:
    if t['family']!='blocks':continue
    p=next((ROOT/'runs'/t['run']/'results').glob('*-updates-*.json'))
    for phase in load(p)['benchmark']['phases']:
        x=[s['completion_nanos'] for s in phase['samples']]; n=len(x)//4
        drift.append(dict(run=t['run'],phase=phase['name'],
                          last_vs_first_quarter_median_percent=(statistics.median(x[-n:])/statistics.median(x[:n])-1)*100))
audit=dict(formal_trials=104,all_trials_valid=True,all_metric_groups_four_runs=True,
           native_comparator_crosschecks=checks,parity=d['parity'],timing_drift=drift)
(ROOT/'analysis/final-audit.json').write_text(json.dumps(audit,indent=2),encoding='utf-8')

def v(f,m,e,stat='median_of_run_means',scale=1e6):
    return next(s[stat]/scale for s in d['summary'] if s['family']==f and s['metric']==m and s['engine']==e)

for private in [False,True]:
    folder=ROOT/('personal-comparison' if private else 'publication')
    engines=list(LABELS) if private else ['vanilla','alfheim','pulsar']
    report=folder/'README.md'
    text=report.read_text(encoding='utf-8')
    text+='\n## Completed validation audit\n\nAll 104 formal JVM runs completed and passed their selected workload checks. Each metric cohort has four independent runs. '
    text+='Lightbench’s native strict comparator separately accepted 16 block-update, 12 Sky-update and 16 flat-generation reports; independently recomputed per-run timings match its CSV. '
    text+='Passing a selected workload is not proof that every stored-light cell in an arbitrary world is correct.\n\n'
    text+='| Cohort | Runs | Distinct terrain hashes | Distinct stored-light hashes | Equal-output comparison |\n|---|---:|---:|---:|---|\n'
    for f,p in d['parity'].items():
        light=str(p['distinct_light_hashes']) if 'gen' in f else 'Not fully hashed'
        text+=f"| {f} | {p['trials']} | {p['distinct_terrain_hashes']} | {light} | {'Yes' if p['matched'] else 'No'} |\n"
    text+='\nCounts above cover the full four-engine study. Travel checks top Sky/Block light at 1,600 positions; it hashes complete terrain, not complete light volumes. '
    text+='Normal generation and travel have output variation, including between independent runs; the one-mushroom pilot does not explain every formal difference. '
    text+='The cause and extent of those differences remain unisolated. Those timings remain descriptive.\n\n'
    text+='## Paced low-rate roof edits\n\nEdits are paced at 2 edits per 20 ticks, over 200 ticks (`lightbench spikes 2 10`). These are not large simultaneous edit bursts. '
    text+='The p99 values below are medians of each run’s p99 server tick, including non-edit ticks.\n\n'
    text+='| Engine | 2 edits/20 ticks: mean / p99 (ms) |\n|---|---:|\n'
    for e in engines:
        if e=='vanilla':text+='| Vanilla | Not validated |\n';continue
        cells=[f"{v('continuous',f'spikes_{r}_server_tick',e):.3f} / {v('continuous',f'spikes_{r}_server_tick',e,'median_run_p99'):.3f}" for r in [2]]
        text+=f"| {LABELS[e]} | {' | '.join(cells)} |\n"
    text+='\n![Paced edits](burst-server-ticks.png)\n\n## Continuous-load completion backlog\n\n'
    text+='| Engine | Post-run drain after 2048 edits/tick (ms) |\n|---|---:|\n'
    for e in engines:
        text+=f"| {LABELS[e]} | "+('Not validated' if e=='vanilla' else f"{v('continuous','tps_2048_post_drain',e):.3f}")+' |\n'
    text+='\nThis final barrier is outside the measured tick durations. It does not measure the maximum age of queued light work during the sweep.\n\n'
    text+='## Normal travel observations\n\n| Engine | Mean waypoint readiness (ms) |\n|---|---:|\n'
    for e in engines:text+=f"| {LABELS[e]} | {v('naturaltravel','readiness_latency',e):.3f} |\n"
    text+='\n![Normal travel, descriptive](normal-travel-descriptive.png)\n\n'
    text+='## Data and reproduction files\n\n'
    text+='- [Summary CSV](summary.csv), [summary and validation JSON](summary.json), [protocol](protocol.json), [artifact manifest](manifest.json).\n'
    text+='- Raw per-edit/per-tick JSON, validation audits, observer data and two-second process samples are retained in '
    text+='[four-engine-raw-data.zip](four-engine-raw-data.zip) (local supplemental bundle).\n' if private else '[benchmark-data-20261010.zip](benchmark-data-20261010.zip) (publication bundle).\n'
    text+='- Lightbench runtime source: `05596de`; offline comparator: `f6ccb91`. Build with JDK25 using `gradlew build`; no profiling instrumentation is attached to measured JVMs.\n'
    text+='- [Local companion and analysis source](reproduction/README.md). No worlds, account files or engine binaries are included in this documentation.\n'
    report.write_text(text,encoding='utf-8')
    summary=[s for s in d['summary'] if s['engine'] in engines]
    (folder/'summary.json').write_text(json.dumps(dict(summary=summary,parity=d['parity'],scene_parity=d['scene_parity'],failures=[]),indent=2),encoding='utf-8')
    with (folder/'summary.csv').open('w',newline='',encoding='utf-8') as stream:
        w=csv.DictWriter(stream,fieldnames=list(summary[0]));w.writeheader();w.writerows(summary)
    public_audit=dict(audit)
    public_audit['native_comparator_crosschecks']=[x for x in checks if x['engine'] in engines]
    public_audit['timing_drift']=[x for x in drift if private or not x['run'].endswith('starlight')]
    (folder/'validation-audit.json').write_text(json.dumps(public_audit,indent=2),encoding='utf-8')
    shutil.copyfile(ROOT/'protocol.json',folder/'protocol.json')
    shutil.copyfile(ROOT/'manifest-final.json',folder/'manifest.json')
    repro=folder/'reproduction';repro.mkdir(exist_ok=True)
    for leaf in ['collect.py','plot.py','report.py','finalize.py','package_results.py','check_bundle.py','FourEngineDriver.java','TravelProbe.java']:
        shutil.copyfile(ROOT/'tools'/leaf,repro/leaf)
    (repro/'README.md').write_text('''# Reproduction notes

The archived source is the local companion/analysis code used by this study. It is not a standalone Minecraft distribution. Lightbench is in Sumire-Labs/Lightbench, runtime commit 05596de and comparator f6ccb91. Engine artifacts and common mods are identified by SHA256 in the manifest.

Create disposable Minecraft1.12.2/Cleanroom0.6.12 profiles with the manifest's common mods, one lighting engine at a time, and identical options/default Version2 config. Use Java25.0.4.1, G1, 8GiB Xms/Xmx and compact object headers. Create fresh seed1 Superflat (uniform floor top at Y3) and default-normal worlds. Freeze copies before measurements. Spectator, day6000, no weather, no mob spawning, randomTickSpeed0, doFireTickfalse, doDaylightCyclefalse, doWeatherCyclefalse. Follow protocol.json commands and counterbalanced orders; use a separate fresh JVM/world copy per case. Do not reuse generated coordinates.

The driver intentionally activates only inside the owned Pulsar-SWMR-20261008/minecraft instance with a four-engine-bench.marker. Port its explicit path guard to a disposable instance; inspect its command/result/observer handling first. TravelProbe contains the route, seed-independent readiness checks, camera positions and terrain hash procedure. Compile both Java sources against the remapped Cleanroom0.6.13 Minecraft jar and Gson, then package classes plus standard Forge mod metadata. The harness used reflection for Lightbench and SRG names for game methods. All engines use this identical companion binary.

For offline regeneration, extract the raw bundle so runs/, protocol.json and manifest-final.json share a study root. Put these Python files in tools/. Set progress.json status to measurements-complete. Run collect.py, plot.py, report.py; the plot script uses tools/python-packages (matplotlib3.11.2/numpy2.5.3). Run Lightbench compareBenchmarks separately over explicit updates/gen result files, preserving adjacent generation audit JSON. Save each native comparison.csv under analysis/native-blocks|sky|flatgen/<one-output-directory>/. Then run finalize.py to assert all104 cohorts, cross-check native CSV, and add audit/supplements. Pilots and warmup-prefixed reports are not scored. Raw JSON timestamps are UTC.

The public raw bundle contains the 76 Vanilla/Alfheim/Pulsar runs. collect.py can aggregate this subset; finalize.py intentionally requires the separate full four-engine bundle (104 runs) to reproduce the complete-study audit. The original local automation and frozen template worlds remain outside the Git repository. These instructions and sources permit rebuilding the procedure, but the binary hashes are provenance records, not a guarantee of byte-identical builds or identical timings on another machine. No GPL reference-engine Java is included.
''',encoding='utf-8')

p=v('blocks','block_place','pulsar');q=v('blocks','block_remove','pulsar')
headline=f"In this validated glowstone fixture, Pulsar completed placement/removal in {p*1000:.1f}/{q*1000:.1f} µs: {v('blocks','block_place','vanilla')/p:.1f}×/{v('blocks','block_remove','vanilla')/q:.1f}× shorter completion times than Vanilla and {v('blocks','block_place','alfheim')/p:.1f}×/{v('blocks','block_remove','alfheim')/q:.1f}× shorter than Alfheim. These ratios describe this fixture, not general FPS."
base='https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-10-pulsar-0.5.0/'
methods='https://github.com/Sumire-Labs/Pulsar/blob/main/docs/benchmarks/2026-10-10-pulsar-0.5.0/README.md'
for site in ['github','curseforge','modrinth']:
    text='# Suggested performance copy\n\n'+headline+'\n\n'
    text+='Four independent JVM runs per supported condition, same hardware and settings, light-completion checks. Pulsar is labeled 0.5.0 (source75b051d). '
    text+='Flat generation was slower than Vanilla/Alfheim; normal generation had output differences and is not used for an equal-output speed claim. Vanilla did not validate the tall-roof Sky workload.\n\n'
    text+=f'![Validated block updates]({base}block-latency.png)\n\n![Continuous server-thread load]({base}continuous-server-ticks.png)\n\n'
    text+='The continuous graph measures main-thread MSPT, excluding async worker CPU; final drain is recorded separately. It is not a total-CPU or visual-latency speedup claim. '
    text+=f'[Methods, all results and caveats]({methods}).\n\n'
    text+='<!-- Local draft: GitHub URLs become usable only after these documentation assets are pushed. No upload, push, release or external post has been performed. CurseForge rich-text editor may require uploading PNGs or inserting these image URLs manually. -->\n'
    (ROOT/'publication'/(site+'-copy.md')).write_text(text,encoding='utf-8')
print(json.dumps(dict(trials=104,native_crosschecks=len(checks),failures=0,headline=headline),indent=2))

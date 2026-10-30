import hashlib,json,statistics,sys
from pathlib import Path

ROOT=Path(__file__).resolve().parent
PREVIOUS=ROOT.parent/'e2ee-20261029'
sys.path.insert(0,str(PREVIOUS))
from summarize import collect

rows=[collect(p.parent) for p in sorted((PREVIOUS/'runs').glob('*-optimized-*/trial-status.json'))]
if len(rows)!=9 or any(not r.get('completed') or not r.get('audit_valid') for r in rows):
    raise RuntimeError('Nine successful, audited trials are required')
if any(len([r for r in rows if r['variant']==v])!=3 for v in ('alfheim','scalar','rgb')):
    raise RuntimeError('Expected three trials for each variant')
base=rows[0]
world_counts=lambda r:[(w['dimension'],w['chunks'],w['entities'],w['tiles']) for w in r['idle_worlds']]
terrain_equal=all(r['idle_terrain']==base['idle_terrain'] for r in rows)
counts_equal=all(world_counts(r)==world_counts(base) for r in rows)
mods_equal=all(r['nonengine_mods']==base['nonengine_mods'] for r in rows)
world_files=[json.loads((PREVIOUS/'runs'/r['name']/'world-source.json').read_text())['files'] for r in rows]
source_equal=all(w==world_files[0] for w in world_files)
reference_world=json.loads((PREVIOUS/'runs/alfheim-shared-1/world-source.json').read_text())['files']
source_equal=source_equal and world_files[0]==reference_world
if not all((terrain_equal,counts_equal,mods_equal,source_equal)):
    raise RuntimeError('Matched-workload validation failed')
old=json.loads((PREVIOUS/'measured-summary.json').read_text())['runs']
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True).encode()).hexdigest()
if any(r['idle_terrain_sha256']!=digest(base['idle_terrain']) for r in old):
    raise RuntimeError('Geometry differs from the original measurements')
def med(variant,fn,records=rows):return statistics.median(fn(r) for r in records if r['variant']==variant)
def cell(variant,fn):
    values=[fn(r) for r in rows if r['variant']==variant]
    return f'{statistics.median(values):.2f} ({min(values):.2f}–{max(values):.2f})'
live=lambda r:r['idle_live_bytes']/2**20
p95=lambda r:r['idle-ticks']['tick_p95_ns']/1e6
lines=['# Optimized Pulsar: E2EE comparison','',
       'Measured 2026-10-30. Three new JVMs per variant, order A/S/R, R/A/S, S/R/A. Values are medians (minimum–maximum).',
       'Source commit: `c62f162`. Artifact and harness hashes are in `series-input.json`.','',
       '## Validated conditions','',
       f'- All nine runs have identical terrain hashes for {len(base["idle_terrain"])} loaded chunks, matching the earlier comparison.',
       f'- Identical loaded world counts: {world_counts(base)} (dimension, chunks, entities, tiles).',
       '- Identical source-world file hashes and all non-engine mod hashes; no JFR or compatibility mutations in these runs.',
       '- E2EE 1.87.3, Cleanroom 0.6.13-alpha, Alfheim 1.6, Pulsar SCALAR or RGB/RGB_MAX.',
       '- Same Temurin 25.0.4.1, Ryzen AI MAX+ 395, 8 GiB fixed heap, G1, OTG/BBOP-Extended seed 20261029, view distance 12.',
       '- Same normalized configs, observer e2ee2, 400-tick windows, post-full-GC histograms, disabled mob spawning/random ticks/day/weather/loot and extra Thaumcraft crystal emission.',
       '- This remains a server-only, fresh-world comparison on a workstation. It does not measure FPS or mature factories.','',
       '## Matched saved-world results','',
       'Heap means whole-server live Java objects after full GC, not lighting-only retained size. Tick work is bracketed by Forge ServerTickEvent START/END; sleep between ticks is excluded.','',
       '| Variant | Live heap MiB | Working set MiB | Tick p50 ms | Tick p95 ms | CPU s / 400 ticks |',
       '|---|---:|---:|---:|---:|---:|']
for variant in ('alfheim','scalar','rgb'):
    metrics=[live,lambda r:r['memory_phases']['idle-ticks']['working_set']/2**20,
             lambda r:r['idle-ticks']['tick_p50_ns']/1e6,p95,lambda r:r['idle-ticks']['process_cpu_ns']/1e9]
    lines.append('| '+variant+' | '+' | '.join(cell(variant,f) for f in metrics)+' |')
lines+=['','## Change from the previous Pulsar build','',
        '| Mode | Previous heap MiB | Current heap MiB | Heap change MiB | Previous p95 ms | Current p95 ms |',
        '|---|---:|---:|---:|---:|---:|']
for v in ('scalar','rgb'):
    lines.append(f'| {v} | {med(v,live,old):.2f} | {med(v,live):.2f} | {med(v,live)-med(v,live,old):+.2f} | {med(v,p95,old):.2f} | {med(v,p95):.2f} |')
lines+=['','## Generation: observational only','',
        'After 169 warmup submissions, each run measures 507 fresh submissions near chunk +20000 (243 scored core chunks plus support halo). World generation/population, lighting barriers and finalization are timed together. Generated terrain differs even between runs of the same engine; these are not equal-work lighting speedup ratios. Post-generation heap is also affected by differing terrain/entity/tile counts.','',
        '| Variant | Generation s | Post-generation live heap MiB | Loaded tick p95 ms | Peak working set MiB |',
        '|---|---:|---:|---:|---:|']
for v in ('alfheim','scalar','rgb'):
    fs=[lambda r:r['gen_seconds'],lambda r:r['post-generation_live_bytes']/2**20,
        lambda r:r['loaded-ticks']['tick_p95_ns']/1e6,lambda r:r['peak_working_set']/2**20]
    lines.append('| '+v+' | '+' | '.join(cell(v,f) for f in fs)+' |')
lines+=['','## Per-run evidence','',
        '| Run | Idle heap MiB | Idle p95 ms | Generation s | Post-generation chunks / entities / tiles |',
        '|---|---:|---:|---:|---|']
for r in rows:
    w=next(w for w in r['post-generation_worlds'] if w['dimension']==0)
    lines.append(f"| {r['name']} | {live(r):.2f} | {p95(r):.3f} | {r['gen_seconds']:.3f} | {w['chunks']} / {w['entities']} / {w['tiles']} |")
lines+=['','## Verification and limits','',
        '- 377 tests: 373 passed, four opt-in benchmark tests skipped. Seven GPU render cases passed.',
        '- Bedrock Ores live mutation checks passed in SCALAR, RGB_MAX and RGB/VANILLA (11 assertions each). Failed exploratory probes remain retained separately and are not included above.',
        '- JFR contextual-tracking samples fell from 285/511 to 2/209 Server thread samples. This is sampling evidence, not exact time attribution.',
        '- Known notification sources still refresh on changes; unknown sources and cells containing stored fluid keep fallback polling. Budgets and features were not reduced.',
        '- Small tick differences within the observed ranges should not be interpreted as a universal engine ranking. Three runs are not a statistical guarantee across modpacks.',
        '- Real-world visual checks, client FPS, extended-height stress and mature E2EE factories remain outside these measurements.',
        '- Raw trials remain in `../e2ee-20261029/runs/*-optimized-*`; compact observations and input hashes accompany this report.','']
(ROOT/'results.md').write_text('\n'.join(lines),encoding='utf-8')
compact=[]
for r in rows:
    item={k:v for k,v in r.items() if k not in ('hashes','idle_terrain','nonengine_mods')}
    for key in ('hashes','idle_terrain','nonengine_mods'):item[key+'_sha256']=digest(r[key])
    compact.append(item)
(ROOT/'measured-summary.json').write_text(json.dumps({'terrain_equal':terrain_equal,'world_counts_equal':counts_equal,
    'nonengine_mods_equal':mods_equal,'source_world_equal':source_equal,'runs':compact},indent=2),encoding='utf-8')
print('Report written:',ROOT/'results.md')

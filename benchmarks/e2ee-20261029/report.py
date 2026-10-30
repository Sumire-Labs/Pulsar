"""Generate a measured report; no extrapolation to client FPS or lighting-only cost."""
import hashlib, json, statistics
from pathlib import Path
from summarize import collect

root=Path(__file__).resolve().parent
rows=[collect(p.parent) for p in sorted((root/'runs').glob('*-shared-*/trial-status.json'))]
good=[r for r in rows if r.get('completed')]
if len(good)!=9: raise RuntimeError(f'Expected nine completed measured trials; have {len(good)}')
if any(not r['audit_valid'] for r in good): raise RuntimeError('A generation readiness audit failed')
if any(len([r for r in good if r['variant']==v])!=3 for v in ('alfheim','scalar','rgb')):
    raise RuntimeError('Expected exactly three trials per engine')
base=good[0]
terrain_ok=all(r['idle_terrain']==base['idle_terrain'] for r in good)
mods_ok=all(r['nonengine_mods']==base['nonengine_mods'] for r in good)
world_ok=all([(w['dimension'],w['chunks'],w['entities'],w['tiles']) for w in r['idle_worlds']]==
             [(w['dimension'],w['chunks'],w['entities'],w['tiles']) for w in base['idle_worlds']] for r in good)
if not mods_ok: raise RuntimeError('Non-engine mod inventories differ')

def values(variant,fn): return [fn(r) for r in good if r['variant']==variant]
def cell(variant,fn):
    nums=values(variant,fn)
    return f'{statistics.median(nums):.2f} ({min(nums):.2f}–{max(nums):.2f})'

lines=['# E2EE: Alfheim / Pulsar live server comparison','',
       'Measured 2026-10-30. Three fresh JVMs and identical saved-world copies per variant; order A/S/R, R/A/S, S/R/A.',
       'Values below are medians (minimum–maximum), not confidence intervals. Pilots are excluded.','',
       '## Conditions','',
       '- E2EE 1.87.3 server exclusions; Cleanroom 0.6.13-alpha (pack default is 0.6.11-alpha).',
       '- Alfheim 1.6; Pulsar 0.5.4-dev.50.local, SCALAR or RGB/RGB_MAX. No client connected.',
       '- Temurin 25.0.4.1, Ryzen AI MAX+ 395, 32 logical CPUs, 8 GiB fixed heap, G1.',
       '- Seed 20261029, OTG/BBOP-Extended, view distance 12. Common source: saved fresh Alfheim pilot world.',
       '- Mob spawning, random ticks, day/weather cycle and drops disabled; Thaumcraft extra crystal emission disabled.',
       '- TileEntity light tracking remains enabled, default 1,000 microsecond / 4,096 sample budget.',
       '- Local Windows workstation, not a dedicated isolated benchmark host. No concurrent builds during measured runs.',
       '- Histogram full GC and geometry audits occur outside timed windows. Tick windows contain 400 ticks each, bracketed by Forge ServerTickEvent START/END callbacks (not client frame time).','',
       '## Matched saved-world observations','',
       f'- Loaded terrain hashes equal across all nine runs: **{terrain_ok}** ({len(base["idle_terrain"])} chunks).',
       f'- Loaded dimension/chunk/entity/tile counts equal: **{world_ok}**.',
       f'- All non-engine mod file hashes equal: **{mods_ok}**.',
       '- Post-GC live heap is the class-histogram total for the entire server, not lighting-only memory.',
       '- Working set is sampled process physical memory; the fixed heap and JVM residency affect it.','',
       '| Variant | Live heap MiB | Idle working set MiB | Tick p50 ms | Tick p95 ms | Process CPU s / 400 ticks |',
       '|---|---:|---:|---:|---:|---:|']
for v in ('alfheim','scalar','rgb'):
    fs=[lambda r:r['idle_live_bytes']/2**20,lambda r:r['memory_phases']['idle-ticks']['working_set']/2**20,
        lambda r:r['idle-ticks']['tick_p50_ns']/1e6,lambda r:r['idle-ticks']['tick_p95_ns']/1e6,
        lambda r:r['idle-ticks']['process_cpu_ns']/1e9]
    lines.append('| '+v+' | '+' | '.join(cell(v,f) for f in fs)+' |')
lines+=['','## Generation observations: unequal terrain workload','',
        'Each run warms up 169 submissions then measures 507 submissions (243 scored core chunks, plus support halo) in three fresh areas near chunk +20000. Timing includes terrain generation/population, lighting, completion barriers and finalization. It is not lighting-only CPU time.',
        'The pack generated differing block-state terrain hashes despite a fixed seed. These values describe observed pack behavior, and **must not be used as a controlled lighting-engine speedup ratio**. Post-generation memory also includes different terrain/entity/tile populations.','',
        '| Variant | Generation seconds | Post-generation live heap MiB | Loaded tick p95 ms | Process peak working set MiB |',
        '|---|---:|---:|---:|---:|']
for v in ('alfheim','scalar','rgb'):
    fs=[lambda r:r['gen_seconds'],lambda r:r['post-generation_live_bytes']/2**20,
        lambda r:r['loaded-ticks']['tick_p95_ns']/1e6,lambda r:r['peak_working_set']/2**20]
    lines.append('| '+v+' | '+' | '.join(cell(v,f) for f in fs)+' |')
lines+=['','## Per-run observations','',
        '| Run | Idle live MiB | Generation s | Post-generation chunks / entities / tiles | Terrain differences vs first measured run |',
        '|---|---:|---:|---|---:|']
for r in good:
    w=next(w for w in r['post-generation_worlds'] if w['dimension']==0)
    diffs=sum(h.get('terrain_sha256')!=base['hashes'].get(k,{}).get('terrain_sha256') for k,h in r['hashes'].items())
    lines.append(f"| {r['name']} | {r['idle_live_bytes']/2**20:.2f} | {r['gen_seconds']:.3f} | {w['chunks']} / {w['entities']} / {w['tiles']} | {diffs}/{len(r['hashes'])} |")
lines+=['','## Limits and reproducibility','',
        '- A newly generated world does not represent a mature E2EE factory or multiplayer load. Client rendering/FPS and visual correctness were not tested.',
        '- RGB provides different lighting features; scalar light equality is not assumed for RGB_MAX.',
        '- A similar total-server heap does not prove equal lighting-engine retained memory; heap histograms lack ownership graphs.',
        '- Tick work excludes the server sleep between ticks; lower work at idle does not establish maximum player capacity.',
        '- TileEntity polling is a candidate for Pulsar tick overhead, based on its configured 1 ms budget; this run does not isolate its causal contribution.',
        '- Raw local artifacts: `runs/<name>/trial-status.json`, `benchmark-inventory.json`, `world-source.json`, `process-memory.csv`, `*-histogram.txt`, `benchmark-results/*.json`, and `BBOP-Extended/lightbench-results/*.json`.',
        '- Download provenance and normalized configs are retained alongside the controller. Servers were stopped after every run; no existing user world or Prism instance was modified.','']
(root/'results.md').write_text('\n'.join(lines),encoding='utf-8')
compact=[]
for row in good:
    item={k:v for k,v in row.items() if k not in ('hashes','idle_terrain','nonengine_mods')}
    for key in ('hashes','idle_terrain','nonengine_mods'):
        item[key+'_sha256']=hashlib.sha256(json.dumps(row[key],sort_keys=True).encode()).hexdigest()
    compact.append(item)
(root/'measured-summary.json').write_text(json.dumps({'idle_terrain_equal':terrain_ok,
    'idle_world_counts_equal':world_ok,'nonengine_mods_equal':mods_ok,'runs':compact},indent=2),encoding='utf-8')
print('Report written:',root/'results.md')

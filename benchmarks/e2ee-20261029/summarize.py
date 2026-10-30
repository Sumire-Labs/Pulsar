"""Summarize retained raw runs without hiding failed trials or parity differences."""
import csv, json, re, statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def one(directory, pattern):
    paths = list(directory.glob(pattern))
    if len(paths) != 1:
        raise ValueError(f'{directory}: expected one {pattern}, found {len(paths)}')
    return read(paths[0])

def collect(directory):
    row = read(directory/'trial-status.json')
    if not row.get('completed') or row.get('compat_only'):
        return row
    results = directory/'BBOP-Extended/lightbench-results'
    reports = [read(p) for p in results.glob('*.json')]
    report = next(r for r in reports if 'benchmark' in r)['benchmark']
    audit = next(r for r in reports if r.get('protocol') == 'halo2-score-core-v3')
    phase = next(p for p in report['phases'] if p['name'] == 'gen test')
    row.update(engine=report['engine'], audit_valid=audit['valid'],
               gen_seconds=phase['total_until_lit_nanos']/1e9,
               chunks_per_second=phase['chunks_per_second'],
               submitted_chunks=phase['chunk_count'])
    row['hashes'] = {f"{c['x']},{c['z']}": {k:c[k] for k in ('terrain_sha256','light_sha256') if k in c}
        for r in audit['regions'] for c in r['chunks'] if 'terrain_sha256' in c}
    for label in ('idle', 'post-generation'):
        histogram = (directory/f'{label}-histogram.txt').read_text()
        match = re.search(r'^Total\s+(\d+)\s+(\d+)', histogram, re.M)
        row[label+'_live_bytes'] = int(match[2])
        snapshot = one(directory/'benchmark-results', '*-'+label+'.json')
        row[label+'_worlds'] = snapshot['worlds']
    for label in ('idle-ticks', 'loaded-ticks'):
        metrics = one(directory/'benchmark-results', '*-'+label+'.json')
        row[label] = {k:metrics[k] for k in ('tick_p50_ns','tick_p95_ns','tick_p99_ns','elapsed_wall_ns','process_cpu_ns','worlds')}
    audits=list((directory/'benchmark-results').glob('*-idle-audit.json'))
    if audits:
        audit=read(audits[0])
        row['idle_terrain']={f"{c['dimension']},{c['x']},{c['z']}":c['terrain_sha256'] for c in audit['chunks']}
    inventory=read(directory/'benchmark-inventory.json')
    row['nonengine_mods']={name:digest for name,digest in inventory['mods'].items()
        if name!='Alfheim-1.6.jar' and not name.startswith('pulsar-')}
    with (directory/'process-memory.csv').open() as f:
        samples = list(csv.DictReader(f))
    row['peak_working_set'] = max(int(s['working_set']) for s in samples)
    row['peak_private_bytes'] = max(int(s['private_bytes']) for s in samples)
    row['memory_phases'] = {}
    for phase_name in sorted({s['phase'] for s in samples}):
        selected = [s for s in samples if s['phase'] == phase_name]
        row['memory_phases'][phase_name] = {k:statistics.median(int(s[k]) for s in selected) for k in ('working_set','private_bytes')}
    return row

if __name__ == '__main__':
    rows = [collect(p.parent) for p in sorted((ROOT/'runs').glob('*/trial-status.json'))]
    (ROOT/'summary.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
    for r in rows:
        if r.get('completed') and r.get('compat_only'):
            print(r['name'],'COMPATIBILITY_ONLY');continue
        if not r.get('completed'):
            print(r['name'], 'FAILED', r.get('error')); continue
        print(r['name'], 'valid',r['audit_valid'], 'gen_s',round(r['gen_seconds'],3),
              'live_MiB',round(r['post-generation_live_bytes']/2**20,2),
              'tick_p95_ms',round(r['loaded-ticks']['tick_p95_ns']/1e6,3),
              'worlds',[(w['dimension'],w['chunks'],w['entities'],w['tiles']) for w in r['post-generation_worlds']])

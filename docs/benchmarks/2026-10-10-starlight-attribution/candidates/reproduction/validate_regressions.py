from pathlib import Path
import json,hashlib
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def gen_hash(run):
    a=load(next((run/'results').glob('*-gen-audit-*.json')));assert a['valid']
    rows=sorted((c['x'],c['z'],c['terrain_sha256'],c['light_sha256']) for r in a['regions'] if r['phase']=='gen test' for c in r['chunks'] if c['role']=='core')
    assert len(rows)==289
    return hashlib.sha256(json.dumps(rows,separators=(',',':')).encode()).hexdigest()
def validate():
    result={}
    for variant in ['baseline','late']:
        suite=R/'runs'/('regression-'+variant+'-suite');assert load(suite/'status.json')['valid']
        scored=[]
        for path in (suite/'results').glob('*.json'):
            if path.name.startswith('warmup-'):continue
            doc=load(path)
            if 'benchmark' not in doc:continue
            b=doc['benchmark']
            if b['mode'] not in ['tps','spikes']:continue
            assert b['initial_fixture_light_verified'] and b['roof_and_light_verified'] and not b['wall_capped']
            assert b['measured_ticks']==b['requested_ticks']
            assert b['edits']==b['rate']*b['measured_ticks']//(20 if b['mode']=='spikes' else 1)
            scored.append((b['mode'],b['rate']))
        assert set(scored)=={('spikes',2),*[('tps',x) for x in [64,128,256,512,1024,2048]]}
        gen=R/'runs'/('regression-'+variant+'-flatgen');assert load(gen/'status.json')['valid']
        travel=R/'runs'/('regression-'+variant+'-flattravel');assert load(travel/'status.json')['valid']
        t=load(travel/'results/travel.json');assert t['valid'] and len(t['readiness_latency_nanos'])==32 and t['top_light_checks']==1600 and t['terrain_chunks']==180
        result[variant]=dict(suite_cases=scored,flat_generation_core_hash=gen_hash(gen),travel_terrain_hash=t['terrain_sha256'],travel_light_checks=t['top_light_checks'])
    assert result['baseline']['flat_generation_core_hash']==result['late']['flat_generation_core_hash']
    assert result['baseline']['travel_terrain_hash']==result['late']['travel_terrain_hash']
    (R/'regression-summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))
if __name__=='__main__':validate()

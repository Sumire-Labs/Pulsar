import json,sys
from pathlib import Path
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def validate(run):
    assert load(run/'status.json')['valid'],run
    paths=list((run/'results').glob('*-updates-*.json'));assert len(paths)==1
    b=load(paths[0])['benchmark']
    assert b['preflight']['initial_light_verified'] and b['preflight']['post_measurement_volume_verified']
    n=b['plan']['measured_pairs']; phases={p['name']:p['samples'] for p in b['phases']}
    assert all(p['all_samples_verified'] and len(p['samples'])==n and all(s['light_verified'] for s in p['samples']) for p in b['phases'])
    variant=load(run/'stage.json')['variant']
    if variant=='on':
        rows=load(run/'results/phase-timing.json')['samples'];assert len(rows)==len(phases)*n
        for phase,samples in phases.items():
            scoped=[x for x in rows if x['phase']==phase];assert len(scoped)==n
            for row,native in zip(scoped,samples):
                assert all(row[k]==native[k] for k in ['submission_nanos','barrier_nanos','completion_nanos'])
                assert all(v>=0 for k,v in row.items() if k.endswith('_nanos'))
                for lane in ['sky_','block_']:
                    body=sum(row[lane+'engine_'+s+'_nanos'] for s in ['setup','sections','propagation','publish','cleanup'])
                    assert body<=row[lane+'task_body_nanos']
                    assert row.get(lane+'last_task_end_offset_nanos',0)<=row['completion_nanos']
                    assert row[lane+'propagation_prepare_nanos']+row[lane+'propagation_flood_nanos']<=row[lane+'engine_propagation_nanos']
    else:assert not (run/'results/phase-timing.json').exists()
    return dict(run=run.name,variant=variant,pairs=n,phases=list(phases),valid=True)
if __name__=='__main__':print(json.dumps(validate(Path(sys.argv[1]))))

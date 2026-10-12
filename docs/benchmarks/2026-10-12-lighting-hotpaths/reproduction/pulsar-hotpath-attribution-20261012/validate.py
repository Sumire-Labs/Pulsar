from pathlib import Path
import json,sys
load=lambda p:json.loads(Path(p).read_text(encoding='utf-8-sig'))
def selected(phase,index):
    h=0
    for c in phase:h=(31*h+ord(c))&0xffffffff
    h^=index;h^=h>>16;h=(h*0x7feb352d)&0xffffffff;h^=h>>15;h=(h*0x846ca68b)&0xffffffff;h^=h>>16
    return h&15==0
def validate(run):
    run=Path(run);stage=load(run/'stage.json');assert load(run/'status.json')['valid'],run
    if stage['engine']=='pulsar':
        observer=load(run/'results/observer.json')
        assert observer['pulsar_parallel_worker_count']==(1 if stage['pool']=='1' else 10)
    paths=list((run/'results').glob('*-updates-*.json'));assert len(paths)==1
    b=load(paths[0])['benchmark'];assert b['engine']==stage['engine']
    assert b['preflight']['initial_light_verified'] and b['preflight']['post_measurement_volume_verified']
    phases={p['name']:p['samples'] for p in b['phases']};n=b['plan']['measured_pairs']
    assert all(p['all_samples_verified'] and len(p['samples'])==n and all(s['light_verified'] for s in p['samples']) for p in b['phases'])
    if stage['variant']=='on':
        doc=load(run/'results/sampled-timing.json'); expected_warm=sum(selected('warmup_'+phase,i) for phase in phases for i in range(b['plan']['warmup_pairs'])); assert doc['schema']==3 and doc['warmup_captured_edits']==expected_warm and expected_warm>0; rows=doc['samples'];expected={(phase,i) for phase in phases for i in range(n) if selected(phase,i)}
        assert {(x['phase'],x['index']) for x in rows}==expected and len(rows)==len(expected)
        for x in rows:
            assert not x['overlap'],(run,x['phase'],x['index'])
            native=phases[x['phase']][x['index']]
            assert all(x[k]==native[k] for k in ['submission_nanos','barrier_nanos','completion_nanos'])
            for lane in ['sky_','block_']:
                assert x[lane+'engines']>=1
                assert all(x[lane+s+'_nanos']>=0 for s in ['setup','sections','propagation','publish','cleanup','prepare','flood'])
                assert x[lane+'prepare_nanos']+x[lane+'flood_nanos']<=x[lane+'propagation_nanos']
                assert x[lane+'last_engine_end_nanos']<=x['completion_nanos']
                if stage['engine']=='pulsar':
                    assert x[lane+'tasks']>=1
                    assert sum(x[lane+s+'_nanos'] for s in ['setup','sections','propagation','publish','cleanup'])<=x[lane+'task_body_nanos']
                    assert x[lane+'last_task_end_nanos']<=x['completion_nanos']
        captured=len(rows)
    else:
        assert not (run/'results/sampled-timing.json').exists();captured=0
    return dict(run=run.name,engine=stage['engine'],variant=stage['variant'],pairs=n,captured=captured,valid=True)
if __name__=='__main__':print(json.dumps(validate(sys.argv[1])))

from pathlib import Path
import json,statistics,csv,hashlib,sys
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,'E:/Github/pulsar-050-release-bench-20261012/tools')
from collect import scored_hashes
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
metrics=[]; environments=[]; non_engine_mods=[]; trials=[]; shapes={}; generations={}
for directory in sorted((ROOT/'runs').iterdir()):
    if not (directory/'status.json').exists() or directory.name.startswith('pilot-'):continue
    status=read(directory/'status.json'); assert status['valid'],directory.name
    stage=read(directory/'stage.json'); variant=stage['engine']
    observer=read(directory/'results/observer.json'); assert not observer.get('failure')
    assert observer['pulsar_parallel_worker_count']==10
    trials.append({'run':directory.name,'variant':variant,'sha256':stage['sha256']})
    for path in sorted((directory/'results').glob('*.json')):
        if path.name.startswith('warmup-') or path.name=='observer.json':continue
        data=read(path)
        if path.name=='hotpath-audit.json':
            assert data['valid']; shapes[variant]=data; continue
        if 'benchmark' not in data:continue
        bench=data['benchmark']; mode=bench['mode']
        if mode=='updates':
            assert bench['preflight']['initial_light_verified'] and bench['preflight']['post_measurement_volume_verified']
            environments.append(data['environment'])
            non_engine_mods.append([m for m in data['mods'] if m['id']!='pulsar'])
            for phase in bench['phases']:
                assert phase['all_samples_verified'] and len(phase['samples'])==10000
                assert all(s['light_verified'] for s in phase['samples'])
                values=[s['completion_nanos'] for s in phase['samples']]
                metrics.append({'run':directory.name,'variant':variant,'metric':phase['name'],'mean_ns':statistics.mean(values),'samples':len(values)})
        elif mode in ['tps','spikes']:
            assert bench['roof_and_light_verified'] and bench['initial_fixture_light_verified'] and not bench['wall_capped']
            assert bench['measured_ticks']==bench['requested_ticks']
            metrics.append({'run':directory.name,'variant':variant,'metric':f"{mode}_{bench['rate']}_main_tick",'mean_ns':statistics.mean(bench['ticks_nanos']),'samples':len(bench['ticks_nanos'])})
            metrics.append({'run':directory.name,'variant':variant,'metric':f"{mode}_{bench['rate']}_post_drain",'mean_ns':bench['post_run_drain_nanos'],'samples':1})
        elif mode=='gen':
            audit=read(next((directory/'results').glob('*gen-audit*.json')))
            assert audit['valid']; generations[variant]=scored_hashes(audit)
summary=[]
for metric in sorted({m['metric'] for m in metrics}):
    medians={}
    for variant in ['baseline','candidate']:
        rows=[m for m in metrics if m['variant']==variant and m['metric']==metric]
        values=[m['mean_ns'] for m in rows]
        if not values:continue
        medians[variant]=statistics.median(values)
        summary.append({'metric':metric,'variant':variant,'jvm_runs':len(values),'median_of_run_means_ns':statistics.median(values),'minimum_run_mean_ns':min(values),'maximum_run_mean_ns':max(values)})
    if len(medians)==2:
        for row in summary:
            if row['metric']==metric:row['candidate_change_percent']=(medians['candidate']/medians['baseline']-1)*100
out=ROOT/'analysis'; out.mkdir(exist_ok=True)
complete=read(ROOT/'progress.json')['status']=='measurements-complete'
checks={'complete':complete,'all_completed_trials_valid':True,'completed_trials':len(trials),'same_update_environment':all(e==environments[0] for e in environments) if environments else False,'same_non_engine_mods':all(m==non_engine_mods[0] for m in non_engine_mods) if non_engine_mods else False,'shape_outputs_identical':shapes.get('baseline')==shapes.get('candidate') if len(shapes)==2 else None,'generation_outputs_identical':generations.get('baseline')==generations.get('candidate') if len(generations)==2 else None}
if complete:
    assert len(trials)==16 and all(checks[k] for k in ['same_update_environment','same_non_engine_mods','shape_outputs_identical','generation_outputs_identical'])
    assert all(s['jvm_runs']==4 for s in summary if s['metric'] in ['block_place','block_remove','sky_place','sky_remove'])
    assert len(shapes['baseline']['cases'])==15
    assert generations['baseline']['scored_chunks']==289
result={'checks':checks,'summary':summary,'trials':trials,'metrics':metrics,'generations':generations,'shapes':shapes}
(out/'results.json').write_bytes((json.dumps(result,indent=2)+'\n').encode())
if summary:
    with (out/'summary.csv').open('w',newline='',encoding='utf-8') as f:
        writer=csv.DictWriter(f,fieldnames=list(summary[0])); writer.writeheader();writer.writerows(summary)
print(json.dumps({'checks':checks,'updates':[s for s in summary if s['metric'] in ['block_place','block_remove','sky_place','sky_remove']]},indent=2))

from pathlib import Path
import json,hashlib
R=Path(__file__).resolve().parents[1]
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
names=['main-actual-updates','main-actual-shapes','main-actual-flatgen','main-origin-flatgen','main-actual-dense']
for name in names:assert load(R/'runs'/name/'status.json')['valid'],name
baseline=load(Path('E:/Github/pulsar-hotpath-attribution-20261012/runs/audit-main/results/hotpath-audit.json'))
assert load(R/'runs/main-actual-shapes/results/hotpath-audit.json')==baseline
def core(name):
 d=load(next((R/'runs'/name/'results').glob('*gen-audit*.json')));assert d['valid'];records=[]
 for region in d['regions']:
  if not region['core_hashes_recorded']:continue
  assert region['valid']
  for c in region['chunks']:
   if c['role']=='core':records.append((c['x'],c['z'],c['terrain_sha256'],c['light_sha256']))
 assert len(records)==289
 return sorted(records)
a=core('main-actual-flatgen');b=core('main-origin-flatgen');assert a==b
flat_hash=hashlib.sha256(json.dumps(a,separators=(',',':')).encode()).hexdigest()
dense=[]
for p in (R/'runs/main-actual-dense/results').glob('*.json'):
 if ('-tps-' not in p.name and '-spikes-' not in p.name) or p.name.startswith('warmup-'):continue
 d=load(p)['benchmark'];assert d['roof_and_light_verified'] and not d['wall_capped'];dense.append(p.name)
assert len(dense)>=7
result={'main_updates_valid':True,'main_shape_hashes_equal_original':True,'flatgen_core_chunks':289,'flatgen_all_terrain_and_light_hashes_equal_original':True,'sorted_core_hash':flat_hash,'dense_qualified_reports':dense,'dense_reports_are_correctness_regression_not_repeated_ranking':True}
(R/'analysis/main-regression-summary.json').write_bytes(json.dumps(result,indent=2).encode());print(json.dumps(result,indent=2))

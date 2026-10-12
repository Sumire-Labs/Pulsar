from pathlib import Path
import json,zipfile,hashlib,re,shutil,statistics as st
R=Path(__file__).resolve().parents[1];O=Path('E:/Github/pulsar-hotpath-attribution-20261012');F=Path('E:/Github/pulsar-empty-spin-20261012');P=Path('E:/Github/Pulsar/docs/benchmarks/2026-10-12-lighting-hotpaths')
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
D=load(R/'analysis/summary.json');assert D['runs']==20
P.mkdir(parents=True,exist_ok=True);(P/'reproduction').mkdir(exist_ok=True)
for name,path in [('air-paths',R/'analysis/summary.json'),('face-loop',O/'analysis/confirm-summary.json'),('idle-spin',F/'analysis/summary.json')]:shutil.copyfile(path,P/(name+'.json'))
for name,path in [('shape-audits.json',R/'analysis/audit-summary.json'),('main-proof.json',R/'analysis/main-proof.json'),('runtime-cache-inspection.json',F/'runs/runtime-inspection/results/cache-inspection.json')]:shutil.copyfile(path,P/name)
shutil.copyfile(R/'analysis/main-regression-summary.json',P/'main-regressions.json')
for p in (R/'analysis').glob('selected-comparison.*'):shutil.copyfile(p,P/p.name)
for study in [O,F,R]:
 folder=P/'reproduction'/study.name;folder.mkdir(exist_ok=True)
 for name in ['protocol.json','confirm-protocol.json','manifest-final.json','variant-provenance.json']:
  if (study/name).exists():shutil.copyfile(study/name,folder/name)
 for p in (study/'tools').glob('*.py'):shutil.copyfile(p,folder/p.name)
for p in [O/'tools/ReadHotSamples.java',O/'spin-src/ParallelLightScheduler.java',O/'repo/src/main/java/com/sumirelabs/hotpathaudit/HotpathAudit.java']:
 shutil.copyfile(p,P/'reproduction'/p.name)
for p in (O/'analysis').glob('*hotspots.tsv'):shutil.copyfile(p,P/p.name)
files=[]
for label,study,prefixes in [('face-loop',O,('confirm-','audit-')),('idle-spin',F,('formal-','audit-','runtime-inspection')),('air-paths',R,('formal-','audit-','main-'))]:
 for run in sorted((study/'runs').iterdir()):
  if not run.name.startswith(prefixes) or not (run/'status.json').exists():continue
  assert load(run/'status.json')['valid'],run
  for p in (run/'results').glob('*.json'):files.append((p,label+'/runs/'+run.name+'/results/'+p.name))
  for n in ['stage.json','status.json','process-samples.json']:
   if (run/n).exists():files.append((run/n,label+'/runs/'+run.name+'/'+n))
digests={}
with zipfile.ZipFile(P/'raw-data.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for p,arc in files:
  data=p.read_bytes();assert not re.search(rb'(?i)(accessToken|clientToken|refreshToken|--username|--uuid)',data),p
  z.writestr(arc,data);digests[arc]=hashlib.sha256(data).hexdigest()
 z.writestr('SHA256SUMS.json',json.dumps(digests,indent=2))
with zipfile.ZipFile(P/'raw-data.zip') as z:
 assert z.testzip() is None;assert all(hashlib.sha256(z.read(n)).hexdigest()==h for n,h in digests.items())
phases=[('block_place','Block placement'),('block_remove','Block removal'),('sky_remove','Sky opening'),('sky_place','Sky closing')]
lines=['# Lighting hot paths: controlled attribution and selected implementation','',
'The confirmed source-level issue in Sky was a source-face mask test inside every neighbor visit, including ordinary sources whose blocked-face mask is zero. The selected implementation resolves the permitted direction array once, only when the existing sided-source flag requires it. Direction order and mask membership remain identical.','',
'Block propagation also carried verified plain air through generic absorption, emission and sided-flag handling. The selected plain-air branch uses the prior level guard to perform the same write/queue operation directly; other states retain the original generic path. A bounded5µs recent-worker wait catches rapid follow-ups before parking, without holding dispatcher or queue monitors. Parallel worker count, exclusion, late partner claiming and future completion remain.','',
'## Final channel-specific comparison','',
'Four fresh-JVM run means per condition; values below are their medians in microseconds. All use the same command `lightbench updates 10000 10000`, offline launcher mode, default automatic10 shared workers and5µs recent-worker wait. The baseline already includes the selected source-face prefilter; it is not the original release. These data must not be pooled with earlier epochs.','',
'| Phase | Prefilter baseline | Selected Block air path | Starlight-1.12 |','|---|---:|---:|---:|']
for phase,title in phases:
 vals=[]
 for engine,variant in [('pulsar','base'),('pulsar','block'),('starlight','base')]:
  d=next(x for x in D['groups'] if x['engine']==engine and x['variant']==variant and x['phase']==phase)['completion_us'];vals.append(f"{d['median']:.2f} ({d['min']:.2f}–{d['max']:.2f})")
 lines.append('| '+title+' | '+' | '.join(vals)+' |')
lines+=['','The selected configuration puts placement in the same range as the comparator and is faster in the Sky/removal medians on this machine. Overlapping ranges are not evidence of universal ordering. Timings include the engine-specific completion adapters and are not CPU-only propagation time or FPS. The independent Source-face experiment changed Sky opening from647.61µs to601.04µs in its own cohort; its comparator was573.48µs. That experiment establishes a code effect, not an instruction-cycle breakdown of every remaining microsecond.','',
'## Correctness and preserved behavior','',
'All native trials passed every per-edit stored-light check and independent initial/final volume checks. Fifteen shape scenarios include air, stone, glass, both slab halves, eight stair orientations and water levels. Each scenario checks lamp-on/off/restored light over61,440 cells and both channels. All45 snapshots match the original implementation, including10 lit sided states. The main build has180 passing tests, including all4096 direction-mask/filter cases and2000 empty-to-work wake/future transitions. Actual main engine class bytes match the measured selected candidate. Further main-artifact regression results are in the retained proof and regression files.','',
'## Rejected hypotheses and measurement limits','',
'The reference normalizes empty block sections to an air/null cache view whereas the old Pulsar cache retained them. A guarded equivalent was tested while retaining all live vanilla light arrays. Runtime inspection found0 non-null empty sections in this fixture; the proposed optimization was not a cause of this benchmark gap and was rejected. Coordinate-width changes, Block direction-loop duplication and Sky plain-air loop duplication were also not selected. Mathematical equivalence or a single fast trial is insufficient for adoption.','',
'Earlier producer-held diagnostic controls used a1ms monitor wait for prior reservation cleanup. Although outside the edit clock, this changes inter-edit idle time and can alter body timings. Their held-worker/caller attribution is superseded. The new control spins only until prior cleanup finishes, without parking; its results are retained in idle-spin.json. Ordinary legacy/future cases did not use that hold and remain valid. Do not label all execution-placement differences as semaphore or context-switch cost, and do not subtract diagnostic boundaries from native means. No hardware cache-miss or retired-instruction counters were measured.','',
'No propagation features or fallback notifications were removed. Contextual/mod/fluid/sided states retain the original path when plain-air eligibility fails. No GPL reference-engine source was copied into production; LGPL is unchanged. Reference snapshot remains the pinned Starlight-1.12 commit0a7d94e7e3fd7333f0e31e896e8a5ff57fb57e4e. Environment matches the earlier studies: Minecraft1.12.2/Cleanroom0.6.12, AdoptiumJava25.0.4.1, fixed8GiB/G1/compact headers, render distance4,1280×720, FPS260(unlimited), vsync off, flat seed1 and disabled random ticking/weather/mobs.','',
'Raw per-edit JSON, input hashes, status and process observations are in raw-data.zip with internal checksums; JARs, worlds, account data and full logs are excluded. Source/validation/protocol inputs are under reproduction/. JFR hotspot summaries identify hot code, not isolated per-line costs; the detailed debug profile enables non-safepoint metadata. Screening is exploratory and not pooled into confirmation. The prior104-run all-engine publication remains a historical baseline, not a rerun of this implementation.']
(P/'README.md').write_bytes(('\n'.join(lines)+'\n').encode())
(P/'.gitattributes').write_bytes(b'* -text\n*.patch -whitespace\n')
print(json.dumps({'destination':str(P),'raw_files':len(files),'raw_bytes':(P/'raw-data.zip').stat().st_size},indent=2))

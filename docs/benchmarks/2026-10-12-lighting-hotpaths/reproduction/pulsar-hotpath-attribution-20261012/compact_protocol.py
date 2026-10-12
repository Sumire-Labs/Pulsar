from pathlib import Path
import json
R=Path('E:/Github/pulsar-hotpath-compact-20261012')
cases=[['pulsar','manual','legacy'],['pulsar','compact','legacy'],['pulsar','zero','legacy'],['pulsar','zero','gatedpoll'],['pulsar','zero','caller'],['starlight','manual','legacy']]
orders=[[0,5,1,2,3,4],[4,3,2,1,5,0],[2,0,4,5,1,3],[3,1,5,4,0,2]]
(R/'protocol.json').write_bytes(json.dumps({'source':'d9b357b','formal_runs':24,'cases':cases,'orders':orders,'command':'lightbench updates 10000 10000','profiling':False,'launch':'Prism supported --offline Serrecable; all conditions identical','scope':'Manual specialization vs compact source-face prefilter; plain-air propagation data constant zero vs cached info; matched held worker/poll vs caller execution under identical zero-info implementation. Same diagnostic scheduler in all Pulsar conditions. Do not pool previous epochs.'},indent=2).encode())
p=R/'run.ps1';t=p.read_text(encoding='utf-8');t=t.replace("-ArgumentList '-l','Pulsar-SWMR-20261008'","-ArgumentList '-l','Pulsar-SWMR-20261008','--offline','Serrecable'");p.write_bytes(t.encode())
print('Declared compact/air-data/dispatch controls, 24 fresh JVMs')

"""Repeat the established E2EE protocol with a pinned optimized artifact."""
import hashlib,json,subprocess,sys
from pathlib import Path

ROOT=Path(__file__).resolve().parent
PREVIOUS=ROOT.parent/'e2ee-20261029'
jar=Path(sys.argv[1]).resolve()
def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
digest=sha(jar)
harness=PREVIOUS/'tools/lightbench/build/libs/lightbench-1.1.0-bench9-e2ee2.jar'
world=PREVIOUS/'runs/alfheim-pilot/BBOP-Extended'
(ROOT/'series-input.json').write_text(json.dumps({'jar':str(jar),'sha256':digest,
    'harness':str(harness),'harness_sha256':sha(harness),'world_source':str(world),
    'order':[['alfheim','scalar','rgb'],['rgb','alfheim','scalar'],['scalar','rgb','alfheim']]},indent=2))
for repetition,order in enumerate([['alfheim','scalar','rgb'],['rgb','alfheim','scalar'],['scalar','rgb','alfheim']],1):
    for variant in order:
        if sha(jar)!=digest:raise RuntimeError('Artifact changed during measurements')
        name=f'{variant}-optimized-{repetition}'
        if (PREVIOUS/'runs'/name).exists():raise RuntimeError('Refusing to overwrite '+name)
        subprocess.run([sys.executable,str(PREVIOUS/'run_trial.py'),name,variant,
            '--world-source',str(world),'--pulsar-jar',str(jar),'--harness-jar',str(harness)],check=True)

"""Alternating serial trials; every JVM receives an identical saved-world copy."""
import subprocess, sys
from pathlib import Path

root=Path(__file__).resolve().parent
world=root/'runs/alfheim-pilot/BBOP-Extended'
orders=[['alfheim','scalar','rgb'],['rgb','alfheim','scalar'],['scalar','rgb','alfheim']]
for repetition,order in enumerate(orders,1):
    for variant in order:
        name=f'{variant}-shared-{repetition}'
        if (root/'runs'/name).exists():
            raise RuntimeError('Refusing to overwrite or silently skip '+name)
        subprocess.run([sys.executable,str(root/'run_trial.py'),name,variant,'--world-source',str(world)],check=True)
        subprocess.run([sys.executable,str(root/'summarize.py')],check=True)

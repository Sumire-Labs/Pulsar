from pathlib import Path
import json
R=Path(__file__).resolve().parents[1]
p=R/'lightbench-profile/src/main/java/com/sumirelabs/lightbench/LightProbe.java';t=p.read_text(encoding='utf-8')
t=t.replace('WAIT_MODE.equals("caller") || WAIT_MODE.equals("gated")','WAIT_MODE.equals("caller") || WAIT_MODE.equals("gated") || WAIT_MODE.equals("gatedpoll")')
needle='''                if (WAIT_MODE.equals("caller") && affectedPosition != null) {'''
assert needle in t
t=t.replace(needle,'''                if (WAIT_MODE.equals("gatedpoll") && affectedPosition != null) {
                    if(this.diagnosticEdit) this.diagnosticEnd.invoke(null,this.diagnosticWorld);
                } else if (WAIT_MODE.equals("caller") && affectedPosition != null) {''')
p.write_text(t,encoding='utf-8')
p=R/'run-study.ps1';t=p.read_text(encoding='utf-8')
t=t.replace("foreach($mode in @('caller','gated','future'))", "foreach($mode in @('caller','gated','gatedpoll','future'))")
t=t.replace("'pulsar-gated','pulsar-caller','starlight-legacy'","'pulsar-gated','pulsar-gatedpoll','pulsar-caller','starlight-legacy'")
t=t.replace('$orders=@(@(0,1,2,3,4),@(4,3,2,1,0),@(2,0,4,1,3),@(3,1,4,0,2))','$orders=@(@(0,1,2,3,4,5),@(5,4,3,2,1,0),@(2,0,5,1,4,3),@(3,4,1,5,0,2))')
t=t.replace("@('gated','caller')","@('gated','gatedpoll','caller')").replace("@('caller','gated')","@('caller','gatedpoll','gated')")
p.write_text(t,encoding='utf-8')
p=R/'protocol.json';doc=json.loads(p.read_text(encoding='utf-8'));doc.update(formal_runs=48,cases=['pulsar-legacy','pulsar-future','pulsar-gated','pulsar-gatedpoll','pulsar-caller','starlight-legacy'],orders=[[0,1,2,3,4,5],[5,4,3,2,1,0],[2,0,5,1,4,3],[3,4,1,5,0,2]],supplemental='2 reps x blocks/sky x gated/gatedpoll/caller, sampled schema3',primary_comparison='gatedpoll versus caller: same producer hold and full batch; release to worker plus global polling versus caller execution plus same global barrier. Gated versus gatedpoll: same handoff, direct future wait versus polling. Future versus legacy: wait policies under normal concurrency.',superseded='Initial5 native trials and3pilots retained under superseded/. They prompted adding the gatedpoll control; no data from the old harness is pooled into this epoch.')
p.write_text(json.dumps(doc,indent=2),encoding='utf-8')
print('Added gated polling control; all primary trials restart under one final harness')

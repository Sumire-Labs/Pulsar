from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=R/'repo/src/main/java/com/sumirelabs/pulsar/light/engine/ScalarBlockEngine.java'
t=p.read_text(encoding='utf-8');(R/'sources/ScalarBlockEngine.java').write_bytes(t.encode())
needle='            for (final AxisDirection propagate : checkDirections) {'
starts=[];at=0
while (at:=t.find(needle,at))!=-1:starts.append(at);at+=len(needle)
assert len(starts)==2
for a in reversed(starts):
 b=t.index('{',a);depth=1;end=b+1
 while depth:
  if t[end]=='{':depth+=1
  elif t[end]=='}':depth-=1
  end+=1
 body=t[a:end];check='                if ((srcBlockedFaces & (1 << propagate.ordinal())) != 0) continue;\n';assert body.count(check)==1
 fast=body.replace(check,'');t=t[:a]+'            if (srcBlockedFaces == 0) {\n'+fast+'\n            } else {\n'+body+'\n            }'+t[end:]
p.write_bytes(t.encode())
print('Prepared independent block-loop candidate')

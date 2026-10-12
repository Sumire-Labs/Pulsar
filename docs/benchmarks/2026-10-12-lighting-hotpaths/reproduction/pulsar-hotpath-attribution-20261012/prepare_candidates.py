from pathlib import Path
import json
R=Path(__file__).resolve().parents[1]
P=R/'repo/src/main/java/com/sumirelabs/pulsar/light/engine'
def write(p,t):p.write_bytes(t.encode('utf-8'))
for name in ['PulsarEngine.java','LightEngineCache.java','ScalarSkyEngine.java']:
 p=P/name;write(R/'sources'/name,p.read_text(encoding='utf-8'))
p=P/'PulsarEngine.java';t=p.read_text(encoding='utf-8')
old='return (x + ((long) z << COORD_X_BITS) + ((long) y << (COORD_X_BITS + COORD_Z_BITS)) + encodeOffset) & COORD_MASK;'
assert t.count(old)==1
write(p,t.replace(old,'return (x + (z << COORD_X_BITS) + (y << (COORD_X_BITS + COORD_Z_BITS)) + encodeOffset) & COORD_MASK;'))
p=P/'LightEngineCache.java';t=p.read_text(encoding='utf-8');old='return section == null ? Blocks.AIR.getDefaultState()';assert t.count(old)==2;write(p,t.replace(old,'return section == null ? this.airState'))
p=P/'ScalarSkyEngine.java';t=p.read_text(encoding='utf-8')
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
 fast=body.replace(check,'');replacement='            if (srcBlockedFaces == 0) {\n'+fast+'\n            } else {\n'+body+'\n            }'
 t=t[:a]+replacement+t[end:]
write(p,t)
print('Prepared three independent candidates; package changed classes separately')

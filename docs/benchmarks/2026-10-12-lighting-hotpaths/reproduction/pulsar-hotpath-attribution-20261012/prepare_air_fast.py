from pathlib import Path
import re
R=Path(__file__).resolve().parents[1];P=R/'repo/src/main/java/com/sumirelabs/pulsar/light/engine'
(P/'LightEngineCache.java').write_bytes((R/'sources/LightEngineCache.java').read_bytes())
test=R/'repo/src/test/java/com/sumirelabs/pulsar/light/engine/EmptyBlockSectionCacheTest.java'
if test.exists():(R/'sources/EmptyBlockSectionCacheTest.java').write_bytes(test.read_bytes());test.unlink()
def endbrace(t,start):
 pos=t.index('{',start);level=1;end=pos+1
 while level:
  if t[end]=='{':level+=1
  elif t[end]=='}':level-=1
  end+=1
 return end
for name in ['ScalarSkyEngine.java','ScalarBlockEngine.java']:
 p=P/name;backup=R/'sources'/(name.replace('.java','-pre-air-fast.java'))
 if not backup.exists():backup.write_bytes(p.read_bytes())
 t=backup.read_text(encoding='utf-8')
 for method,increase in [('performLightDecrease',False),('performLightIncrease',True)]:
  start=t.index('    protected void '+method+'()');end=endbrace(t,start);body=t[start:end]
  needle='                final boolean air = this.isPlainAir(';a=body.index(needle);lineend=body.index('\n',a)+1
  if increase:
   match=re.search(r'                \w+\.set\(localIndex, targetLevel\);',body);assert match
   write_start=match.start();push=body.index('                if (targetLevel > 1)',write_start);write_end=endbrace(body,push)
   fast=body[write_start:write_end]
   fast=re.sub(r'\s*\| sidedFlag\(destInfo\);',';',fast)
  else:
   match=re.search(r'                \w+\.set\(localIndex, 0\);',body);assert match
   write_start=match.start();push=body.index('                if (targetLevel > 0)',write_start);write_end=endbrace(body,push)
   fast=body[write_start:write_end];fast=re.sub(r'\s*\| sFlag;',';',fast)
   if 'final int emission = LightInfo.emission(info);' in fast:
    ea=fast.index('                final int emission = LightInfo.emission(info);')
    eb=endbrace(fast,fast.index('                if (emission > 0)',ea))
    fast=fast[:ea]+fast[eb:]
    fast=fast.replace('                // Independent of the emission re-seed (upstream): the decrease\n                // must keep walking past emitters, or removing a bright source\n                // leaves a permanent ghost region behind any dimmer emitter.\n','')
  assert 'sFlag' not in fast and 'destInfo' not in fast and 'LightInfo.emission(info)' not in fast
  fast='\n'.join('    '+line if line.strip() else line for line in fast.splitlines())
  inserted='''                // The earlier level guard proves this write for plain air. Its
                // opacity, emission and sided-source flags are all known zero.
                if (air) {
                    final int targetLevel = propagatedLevel - 1;
'''+fast+'''
                    continue;
                }
'''
  body=body[:lineend]+inserted+body[lineend:]
  # Generic path is now reached only for non-plain-air states.
  body=body.replace('air ? 0 : this.lightInfoAt','this.lightInfoAt')
  body=body.replace('air ? 1 : LightInfo.absorption','LightInfo.absorption')
  t=t[:start]+body+t[end:]
 p.write_bytes(t.encode())
print('Prepared plain-air-specific increase/decrease paths, generic feature paths retained')

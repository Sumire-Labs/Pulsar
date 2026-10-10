from pathlib import Path
import re
R=Path(__file__).resolve().parents[1]
for name in ['ScalarBlockEngine.java','ScalarSkyEngine.java']:
    p=R/'repo/src/main/java/com/sumirelabs/pulsar/light/engine'/name;t=p.read_text(encoding='utf-8')
    pattern=r'^            final AxisDirection\[\] checkDirections = OLD_CHECK_DIRECTIONS\[.*\];\n'
    t,n=re.subn(pattern,'',t,flags=re.M);assert n==2,(name,n)
    old='''            for (final AxisDirection propagate : checkDirections) {
                if ((srcBlockedFaces & (1 << propagate.ordinal())) != 0) continue;'''
    new='''            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[
                    PropagationDirections.allowedMask((int) (queueValue >>> DIRECTION_SHIFT), srcBlockedFaces)];
            for (final AxisDirection propagate : checkDirections) {'''
    assert t.count(old)==2,(name,t.count(old));p.write_text(t.replace(old,new),encoding='utf-8')
print('Four source-face branch loops replaced by equivalent filtered direction tables')

from pathlib import Path
R=Path(__file__).resolve().parents[1]
P=R/'repo/src/main/java/com/sumirelabs/pulsar/light/engine'
for name in ['ScalarSkyEngine.java','ScalarBlockEngine.java']:
 p=P/name;t=p.read_text(encoding='utf-8');needle='air ? this.plainAirInfo() :';assert t.count(needle)==2,(p,t.count(needle))
 t=t.replace(needle,'air ? 0 :')
 p.write_bytes(t.encode())
print('Plain-air lighting data is zero; cache-computed sentinel is not used by propagation. Guarded isPlainAir checks remain.')

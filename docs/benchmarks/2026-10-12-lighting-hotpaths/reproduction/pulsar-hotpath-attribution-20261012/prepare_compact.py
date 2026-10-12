from pathlib import Path
import sys
R=Path(__file__).resolve().parents[1];P=R/'repo/src/main/java/com/sumirelabs/pulsar/light/engine'
block_faces='--block-faces' in sys.argv;block_int='--block-int' in sys.argv
for name in ['PulsarEngine.java','LightEngineCache.java']:(P/name).write_bytes((R/'sources'/name).read_bytes())
for name in ['ScalarSkyEngine.java','ScalarBlockEngine.java']:
 t=(R/'sources'/name).read_text(encoding='utf-8')
 if name=='ScalarSkyEngine.java' or block_faces:
  needle='final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS'
  assert t.count(needle)==2
  t=t.replace(needle,'AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS')
  needle='            int srcBlockedFaces = 0;\n';assert t.count(needle)==2;t=t.replace(needle,'')
  needle='                srcBlockedFaces = LightInfo.faceBits(this.lightInfoAt(srcState, posX, posY, posZ));'
  assert t.count(needle)==2
  t=t.replace(needle,'''                final int srcBlockedFaces = LightInfo.faceBits(this.lightInfoAt(srcState, posX, posY, posZ));
                checkDirections = filterDirections((int) (queueValue >>> DIRECTION_SHIFT) & 63, srcBlockedFaces);''')
  check='                if ((srcBlockedFaces & (1 << propagate.ordinal())) != 0) continue;\n';assert t.count(check)==2;t=t.replace(check,'')
  at=t.rfind('}')
  t=t[:at]+'''    static AxisDirection[] filterDirections(final int directions, final int blockedFaces) {
        return OLD_CHECK_DIRECTIONS[directions & ~blockedFaces];
    }
'''+t[at:]
 if name=='ScalarBlockEngine.java' and block_int:
  t=t.replace('encodeCoords(','encodeBlockCoords(');at=t.rfind('}')
  t=t[:at]+'''    static long encodeBlockCoords(final int x, final int z, final int y, final int encodeOffset) {
        return (x + (z << COORD_X_BITS) + (y << (COORD_X_BITS + COORD_Z_BITS)) + encodeOffset) & COORD_MASK;
    }
'''+t[at:]
 (P/name).write_bytes(t.encode())
test=R/'repo/src/test/java/com/sumirelabs/pulsar/light/engine/BlockQueueCoordinateEncodingTest.java'
if not block_int and test.exists():
 (R/'sources/BlockQueueCoordinateEncodingTest.java').write_bytes(test.read_bytes());test.unlink()
if block_int and not test.exists():test.write_bytes((R/'sources/BlockQueueCoordinateEncodingTest.java').read_bytes())
(R/'repo/src/test/java/com/sumirelabs/pulsar/light/engine/SourceFaceDirectionFilterTest.java').write_bytes((R/'sources/SourceFaceDirectionFilterTest.java').read_bytes())
print('Prepared compact conditional direction filter',{'block_faces':block_faces,'block_int':block_int})

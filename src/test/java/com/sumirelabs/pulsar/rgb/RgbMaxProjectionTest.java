package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class RgbMaxProjectionTest {
    private static byte[] plane(int value) { byte[] result = new byte[2048]; Arrays.fill(result, (byte)value); return result; }
    @Test void maximumIsComputedPerNibbleAndCannotAliasMutableWorkerBytes() {
        byte[][][] live = {{plane(0x39)}, {plane(0x91)}, {plane(0x47)}};
        var snapshot = RgbSnapshot.captureMax(0, 1, live, null);
        assertEquals(9, snapshot.canonical(0, 0, 0)); assertEquals(9, snapshot.canonical(1, 0, 0));
        live[0][0][0] = 0;
        assertEquals(0x917, snapshot.get(0, 0, 0));
        assertEquals(9, snapshot.canonical(0, 0, 0));
    }
    @Test void canonicalPlaneIsReusedWhenHueChangesButMaximumDoesNot() throws Exception {
        var first = RgbSnapshot.captureMax(0, 1, new byte[][][]{{plane(0x44)}, {null}, {null}}, null);
        var second = RgbSnapshot.captureMax(0, 2, new byte[][][]{{null}, {plane(0x44)}, {null}}, first);
        Field canonical = RgbSnapshot.class.getDeclaredField("canonical"); canonical.setAccessible(true);
        // Single-channel canonical planes can alias the immutable channel directly.
        assertSame(((byte[][])canonical.get(first))[0], ((byte[][])canonical.get(second))[0]);
        var third = RgbSnapshot.captureMax(0, 3, new byte[][][]{{null}, {plane(0x44)}, {null}}, second);
        assertSame(((byte[][])canonical.get(second))[0], ((byte[][])canonical.get(third))[0]);
        assertEquals(0x400, first.get(0, 0, 0)); assertEquals(0x040, second.get(0, 0, 0));
    }
    @Test void anOldIndependentCanonicalPlaneCannotPoisonTheRgbMaximum() {
        byte[][][] data = {{plane(0x11)}, {null}, {null}};
        var old = new RgbSnapshot(0, 1, data, new byte[][]{plane(0xFF)});
        var projected = RgbSnapshot.captureMax(0, 2, data, old);
        assertEquals(1, projected.canonical(0, 0, 0)); assertEquals(15, old.canonical(0, 0, 0));
        var empty = RgbSnapshot.captureMax(0, 3, new byte[][][]{{null}, {null}, {null}}, projected);
        assertEquals(0, empty.canonical(0, 0, 0));
    }
}

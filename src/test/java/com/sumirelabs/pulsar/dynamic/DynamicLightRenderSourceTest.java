package com.sumirelabs.pulsar.dynamic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
class DynamicLightRenderSourceTest {

    private DynamicLightField field() {
        int r = 9, n = DynamicLightField.cube(r);
        short[] cells = new short[n];
        cells[r + (r * (r * 2 + 1) + r) * (r * 2 + 1)] = (short) 0xeeee;
        return new DynamicLightField(0, 0, 0, r, cells, new short[n * 4]);
    }
    @Test
    void matchesDistanceFalloffAndMovesContinuouslyWithinCell() {
        var f = field();
        float[] a = new float[4], b = new float[4];
        DynamicLightRenderSource.at(f, .5, .5, .5).sample(4.5, .5, .5, false, a);
        DynamicLightRenderSource.at(f, .6, .5, .5).sample(4.5, .5, .5, false, b);
        assertEquals(14 * (1 - 4 / 7.75), a[0], 1e-5);
        assertTrue(b[0] > a[0]);
        assertTrue(b[0] - a[0] < .2);
    }
    @Test
    void limitsOldMaskDisplacementAndKeepsSelfLightStable() {
        var f = field();
        var source = DynamicLightRenderSource.at(f, 100, 100, 100);
        assertEquals(.5, source.x());
        float[] v = new float[4];
        source.sample(100, 100, 100, true, v);
        assertArrayEquals(new float[]{14, 14, 14, 14}, v);
        assertFalse(DynamicLightRenderSource.at(f, .55, .5, .5).movedFrom(source));
        assertTrue(DynamicLightRenderSource.at(f, .7, .5, .5).movedFrom(source));
    }
    @Test
    void absorbedChannelsRemainAbsentInTheLightContribution() {
        var f = field();
        short[] loss = new short[f.size() * 4];
        for (int i = 0; i < f.size(); i++) {
            loss[i * 4] = (short) DynamicLightField.BLOCKED;
            loss[i * 4 + 1] = (short) DynamicLightField.BLOCKED;
        }
        short[] cells = new short[f.size()];
        int r = f.radius;
        cells[r + f.side * (r + f.side * r)] = (short) 0xeeee;
        var filtered = new DynamicLightField(0, 0, 0, r, cells, loss);
        float[] v = new float[4];
        DynamicLightRenderSource.at(filtered, .5, .5, .5).sample(2.5, .5, .5, false, v);
        assertTrue(v[0] > 0);
        assertEquals(0, v[1]);
        assertEquals(0, v[2]);
    }

}

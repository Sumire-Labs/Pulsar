package com.sumirelabs.pulsar.dynamic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
class DynamicLightSurfaceTest {

    @Test
    void keepsFractionalBrightnessAndExistingSkyOrBrighterBlocks() {
        assertEquals(15 << 20 | 120, DynamicLightSurface.lightmap(15 << 20 | 112, 7.5f));
        assertEquals(13 << 20 | 240, DynamicLightSurface.lightmap(13 << 20 | 240, 7.5f));
        assertEquals(3 << 20, DynamicLightSurface.lightmap(3 << 20, 0));
    }
    @Test
    void visibleSurfaceMaskDoesNotHalveLightAtAnOpaqueFloor() {
        int r = 2, s = r * 2 + 1, n = DynamicLightField.cube(r);
        short[] cells = new short[n], loss = new short[n * 4];
        cells[r + s * (r + s * r)] = (short) 0xeeee;
        for (int y = 0; y < r; y++)
            for (int z = 0; z < s; z++)
                for (int x = 0; x < s; x++)
                    for (int c = 0; c < 4; c++)
                        loss[(x + s * (z + s * y)) * 4 + c] = (short) DynamicLightField.BLOCKED;
        var field = new DynamicLightField(0, 1, 0, r, cells, loss);
        float[] v = new float[4];
        DynamicLightVisualSample.mergeSurface(field, .5, 1.5, .5, .5, 1, .5, .5, 1.501, .5, false, v);
        assertEquals(14 * (1 - .5 / 7.75), v[0], .0001);
    }

}

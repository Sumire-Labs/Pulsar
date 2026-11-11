package com.sumirelabs.pulsar.rgb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
class RgbAmbientFloorTest {

    @Test
    void dimColoredLightCannotEraseAmbientInAnotherChannel() {
        int[] map = new int[256];
        for (int sky = 0; sky < 16; sky++)
            for (int block = 0; block < 16; block++) {
                int v = 8 + Math.max(block, sky) * 15;
                map[sky * 16 + block] = 0xff000000 | v << 16 | v << 8 | v;
            }
        RgbAmbientFloor.update(map);
        try {
            for (int level = 0; level <= 15; level++) {
                int tinted = RgbAmbientFloor.preserve(-1, 0xff0000ff, level, 0);
                double lit = 8 + Math.max(0, level - .5) * 15;
                assertTrue((tinted >>> 8 & 255) * lit / 255 >= 8 - 1e-5, "level=" + level);
                assertEquals(tinted >>> 8 & 255, tinted >>> 16 & 255);
                assertEquals(255, tinted & 255);
            }
            // With no colored emission, the clamp retains only ambient, never full surviving canonical light.
            int dark = RgbAmbientFloor.preserve(-1, 0xff000000, 15, 0);
            assertTrue((dark & 255) < 12);
            assertEquals(0x7f000000, RgbAmbientFloor.preserve(0x7f000000, 0x7f000000, 5, 0));
        } finally {
            RgbAmbientFloor.update(null);
        }
    }

}

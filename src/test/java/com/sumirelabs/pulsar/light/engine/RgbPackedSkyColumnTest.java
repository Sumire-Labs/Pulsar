package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.rgb.PackedRgbLight;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbPackedSkyColumnTest {
    @Test void onlyFullComponentsAreDirectSkySources() {
        for (int r = 0; r < 16; r++) for (int g = 0; g < 16; g++) for (int b = 0; b < 16; b++) {
            int expected = PackedRgbLight.pack(r == 15 ? 15 : 0, g == 15 ? 15 : 0, b == 15 ? 15 : 0);
            assertEquals(expected, RgbPackedSkyColumnProcessor.fullMask(PackedRgbLight.pack(r, g, b)));
        }
    }
}

package com.sumirelabs.pulsar.api;

import com.sumirelabs.pulsar.rgb.RgbLight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackedLightColorTest {
    @Test void allValuesMatchEngineEncodingAndFilterConvention() {
        for (int color = 0; color <= 0xFFF; color++) {
            int r = PackedLightColor.red(color);
            int g = PackedLightColor.green(color);
            int b = PackedLightColor.blue(color);
            assertEquals(color, PackedLightColor.pack(r, g, b));
            assertEquals(RgbLight.channel(color, 0), r);
            assertEquals(RgbLight.channel(color, 1), g);
            assertEquals(RgbLight.channel(color, 2), b);
            assertEquals(RgbLight.pack(15 - r, 15 - g, 15 - b), PackedLightColor.complement(color));
            assertEquals(color, PackedLightColor.complement(PackedLightColor.complement(color)));
        }
    }

    @Test void rejectsWrongFormatsAndOutOfRangeComponents() {
        for (int invalid : new int[] {-1, 0x1000, 0xFF0000, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> PackedLightColor.checked(invalid));
            assertThrows(IllegalArgumentException.class, () -> PackedLightColor.red(invalid));
            assertThrows(IllegalArgumentException.class, () -> PackedLightColor.green(invalid));
            assertThrows(IllegalArgumentException.class, () -> PackedLightColor.blue(invalid));
            assertThrows(IllegalArgumentException.class, () -> PackedLightColor.complement(invalid));
        }
        for (int invalid : new int[] {-1, 16, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> PackedLightColor.pack(invalid, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> PackedLightColor.pack(0, invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> PackedLightColor.pack(0, 0, invalid));
        }
    }
}

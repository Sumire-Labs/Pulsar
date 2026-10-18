package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackedRgbLightTest {
    @Test void everyColorRoundTripsStorageAndQueueWithoutDamagingCoordinatesDirectionsOrFlags() {
        long otherBits = 0xF12345L | 0x3FL << 40 | 7L << 61;
        for (int rgb = 0; rgb < 4096; rgb++) {
            int packed = PackedRgbLight.fromCompact(rgb);
            assertEquals(rgb, PackedRgbLight.toCompact(packed));
            long encoded = PackedRgbLight.encodeQueueLevel(packed);
            assertEquals(0, encoded & otherBits);
            assertEquals(packed, PackedRgbLight.decodeQueueLevel(encoded | otherBits));
            assertEquals(otherBits, (encoded | otherBits) & ~((long) 0xFFF << 28));
        }
    }
    @Test void packedArithmeticMatchesIndependentScalarMathForAll16777216Pairs() {
        for (int a = 0; a < 4096; a++) for (int b = 0; b < 4096; b++) {
            int ar = a >>> 8, ag = a >>> 4 & 15, ab = a & 15;
            int br = b >>> 8, bg = b >>> 4 & 15, bb = b & 15;
            int expectedSub = Math.max(0, ar - br) << 8 | Math.max(0, ag - bg) << 4 | Math.max(0, ab - bb);
            int expectedMax = Math.max(ar, br) << 8 | Math.max(ag, bg) << 4 | Math.max(ab, bb);
            int expectedMask = (ar > br ? 0xF00 : 0) | (ag > bg ? 0x0F0 : 0) | (ab > bb ? 0x00F : 0);
            int pa = PackedRgbLight.fromCompact(a), pb = PackedRgbLight.fromCompact(b);
            assertEquals(expectedSub, PackedRgbLight.toCompact(PackedRgbLight.packedSubRGB(pa, pb)));
            assertEquals(expectedMax, PackedRgbLight.toCompact(PackedRgbLight.packedMax(pa, pb)));
            assertEquals(expectedMask, PackedRgbLight.toCompact(PackedRgbLight.maskWhereGreater(pa, pb)));
            assertEquals(expectedMask != 0, PackedRgbLight.anyComponentGreater(pa, pb));
        }
    }
}

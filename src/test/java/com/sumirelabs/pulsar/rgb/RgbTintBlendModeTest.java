package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbTintBlendModeTest {
    @Test void weightedModesHaveDistinctKnownMixedLightResults() {
        float[] tint = new float[3];
        RgbTintBlendMode.ADDITIVE.computeTint(15, 0, 0, 0, 0, 5, tint);
        assertArrayEquals(new float[]{1, 0, 1f / 3}, tint, .0001f);
        RgbTintBlendMode.LINEAR_WEIGHT.computeTint(15, 0, 0, 0, 0, 5, tint);
        assertArrayEquals(new float[]{.75f, 0, .25f}, tint, .0001f);
        RgbTintBlendMode.SQUARED_WEIGHT.computeTint(15, 0, 0, 0, 0, 5, tint);
        assertArrayEquals(new float[]{.9f, 0, .1f}, tint, .0001f);
    }

    @Test void everyModePreservesMaterialAlphaAndNeutralWhiteLight() {
        for (RgbTintBlendMode mode : RgbTintBlendMode.values()) {
            assertEquals(0x7F123456, mode.tintAbgr(0x7F123456, 15, 15, 15, 15, 0, 0, 0, 0, 1), mode.name());
            assertEquals(0x7F, mode.tintAbgr(0x7F123456, 15, 0, 0, 15, 0, 0, 5, 5, 1) >>> 24, mode.name());
        }
    }

    @Test void canonicalGameplayLightCannotTurnFullyAbsorbedLightWhite() {
        for (RgbTintBlendMode mode : RgbTintBlendMode.values()) {
            int dark = mode.tintAbgr(-1, 0, 0, 0, 15, 0, 0, 0, 0, 1);
            assertTrue((dark & 255) < 16, mode.name());
            assertEquals(dark & 255, dark >>> 8 & 255, mode.name());
            assertEquals(dark & 255, dark >>> 16 & 255, mode.name());
            assertEquals(-1, mode.tintAbgr(-1, 0, 0, 0, 0, 0, 0, 0, 0, 1), mode.name());
        }
    }

    @Test void legacyRemainsBitExactAgainstPrototypeAcrossLightCombinations() {
        for (int r = 0; r < 16; r++) for (int g = 0; g < 16; g++) for (int sky = 0; sky < 16; sky++) {
            assertEquals(RgbClient.modulateSky(0x73124598, r, g, 7, 15, sky, 4, 9, 15, .6f),
                    RgbTintBlendMode.LEGACY.tintAbgr(0x73124598, r, g, 7, 15, sky, 4, 9, 15, .6f));
        }
    }

    @Test void allModesRemainFiniteAndBoundedForNibbleInputs() {
        float[] out = new float[3];
        for (RgbTintBlendMode mode : RgbTintBlendMode.values())
            for (int r = 0; r < 16; r++) for (int g = 0; g < 16; g++) for (int b = 0; b < 16; b++) {
                mode.computeTint(r, g, b, 7.25f, 0, 2.5f, out);
                for (float c : out) assertTrue(Float.isFinite(c) && c >= 0 && c <= 1.00001f, mode.name());
            }
    }
}

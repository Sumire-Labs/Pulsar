package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderLightRulesTest {

    @Test
    void keepsLevelZeroAndOneInTheAmbientOcclusionPath() {
        assertEquals(0, RenderLightRules.ambientOcclusionEmission(0));
        assertEquals(0, RenderLightRules.ambientOcclusionEmission(1));
    }

    @Test
    void keepsStrongerEmittersOutOfTheAmbientOcclusionPath() {
        assertEquals(1, RenderLightRules.ambientOcclusionEmission(2));
        assertEquals(14, RenderLightRules.ambientOcclusionEmission(15));
    }
}

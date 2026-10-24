package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.api.RgbStateProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbEmissionPolicyTest {
    @Test void absoluteApiStrengthIsNotNormalizedToVanilla() {
        int properties = RgbStateProperties.SOURCE_ABSOLUTE | 0x840;
        assertEquals(0x840, RgbLightAccess.effectiveEmission(properties, 15, true));
        assertEquals(0x840, RgbLightAccess.effectiveEmission(properties, 15, false));
        assertEquals(0x840, RgbLightAccess.effectiveEmission(properties, 0, true));
        assertEquals(0, RgbLightAccess.effectiveEmission(properties, 0, false));
    }
    @Test void compatibilityCapsAbsolutePeakWithoutChangingHue() {
        int properties = RgbStateProperties.SOURCE_ABSOLUTE | 0xF70;
        assertEquals(0x520, RgbLightAccess.effectiveEmission(properties, 5, false));
        assertEquals(0xF70, RgbLightAccess.effectiveEmission(properties, 5, true));
    }
    @Test void existingChromaticityRegistrationsRetainContextualScaling() {
        assertEquals(0x520, RgbLightAccess.effectiveEmission(0xF70, 5, true));
        assertEquals(0x520, RgbLightAccess.effectiveEmission(0xF70, 5, false));
        assertEquals(0, RgbLightAccess.effectiveEmission(RgbStateProperties.SOURCE_ABSOLUTE, 15, true));
    }
}

package com.sumirelabs.pulsar.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThaumcraftCrystalLightingTest {

    @AfterEach
    void reset() {
        ThaumcraftCrystalLighting.initialize(false, 0);
    }

    @Test
    void raisesDimCrystalsWithoutDimmingOtherModsEmission() {
        ThaumcraftCrystalLighting.initialize(true, 10);
        assertEquals(10, ThaumcraftCrystalLighting.emission(1));
        assertEquals(10, ThaumcraftCrystalLighting.emission(10));
        assertEquals(14, ThaumcraftCrystalLighting.emission(14));
        assertEquals(10, ThaumcraftCrystalLighting.cacheProfile());
    }

    @Test
    void absentThaumcraftAndDisabledSettingPreserveOriginalLight() {
        ThaumcraftCrystalLighting.initialize(false, 10);
        assertEquals(0, ThaumcraftCrystalLighting.cacheProfile());
        assertEquals(1, ThaumcraftCrystalLighting.emission(1));
        ThaumcraftCrystalLighting.initialize(true, 0);
        assertEquals(0, ThaumcraftCrystalLighting.cacheProfile());
        assertEquals(1, ThaumcraftCrystalLighting.emission(1));
        assertEquals(14, ThaumcraftCrystalLighting.emission(14));
    }

    @Test
    void boundsConfigurationBeforeItBecomesACacheProfile() {
        ThaumcraftCrystalLighting.initialize(true, 999);
        assertEquals(15, ThaumcraftCrystalLighting.emission(1));
        assertEquals(15, ThaumcraftCrystalLighting.cacheProfile());
        ThaumcraftCrystalLighting.initialize(true, -1);
        assertEquals(1, ThaumcraftCrystalLighting.emission(1));
        assertEquals(0, ThaumcraftCrystalLighting.cacheProfile());
    }
}

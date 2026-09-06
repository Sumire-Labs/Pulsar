package com.sumirelabs.pulsar.light.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LightAttenuationTest {

    @Test
    void openWaterFacesKeepTheMediumAbsorption() {
        final boolean waterGeometry = LightAttenuation.usesAutomaticFaces(false, true, 3);
        assertFalse(waterGeometry);
        for (int face = 0; face < 6; ++face) {
            assertEquals(3, LightAttenuation.absorption(3, waterGeometry, false));
        }
    }

    @Test
    void partialGeometryKeepsOpenPathsButFullCubesDoNotAcquireThem() {
        final boolean slabGeometry = LightAttenuation.usesAutomaticFaces(false, false, 255);
        assertTrue(slabGeometry);
        assertEquals(1, LightAttenuation.absorption(15, slabGeometry, false));
        assertEquals(15, LightAttenuation.absorption(15, slabGeometry, true));
        assertFalse(LightAttenuation.usesAutomaticFaces(true, false, 255));
        assertFalse(LightAttenuation.usesAutomaticFaces(false, false, 0));
    }

    @Test
    void deepWaterColumnAgreesWithKnownUniformSolution() {
        assertArrayEquals(new int[]{15, 15, 12, 9, 6, 3, 0},
                initialColumn(new int[]{0, 0, 3, 3, 3, 3, 3}));
        assertArrayEquals(new int[]{15, 15, 12, 9, 6, 3, 0},
                columnWithBfsHandoff(new int[]{0, 0, 3, 3, 3, 3, 3}));
    }

    @Test
    void weakenedSkyLosesOneInAirIncludingAcrossAnEmptySection() {
        final int[] column = new int[35];
        column[0] = 3;
        final int[] baseline = initialColumn(column);
        final int[] propagated = columnWithBfsHandoff(column);
        assertEquals(12, propagated[0]);
        assertEquals(11, propagated[1]);
        assertEquals(1, propagated[11]);
        assertEquals(0, propagated[12]);
        assertEquals(0, propagated[32]);
        assertArrayEquals(baseline, propagated);
        for (int level = 0; level < 15; ++level) {
            assertFalse(LightAttenuation.canExtrudeDirectSky(level, 0, 0));
        }
    }

    @Test
    void waterPlacementRemovalAndRoofClosureHaveDistinctExpectedFields() {
        final int[] open = {0, 0, 0, 0};
        final int[] water = {0, 3, 0, 0};
        final int[] closed = {15, 3, 0, 0};
        assertArrayEquals(new int[]{15, 15, 15, 15}, columnWithBfsHandoff(open));
        assertArrayEquals(new int[]{15, 12, 11, 10}, columnWithBfsHandoff(water));
        assertArrayEquals(new int[]{0, 0, 0, 0}, columnWithBfsHandoff(closed));
        assertArrayEquals(new int[]{15, 12, 11, 10}, columnWithBfsHandoff(water));
        assertArrayEquals(new int[]{15, 15, 15, 15}, columnWithBfsHandoff(open));
    }

    @Test
    void everyOrdinaryEdgeAttenuatesAndOpaqueEmittersCanStillEmitIntoAir() {
        for (int opacity = 0; opacity <= 255; ++opacity) {
            for (final boolean sided : new boolean[]{false, true}) {
                for (final boolean solid : new boolean[]{false, true}) {
                    final int absorption = LightAttenuation.absorption(opacity, sided, solid);
                    assertTrue(absorption >= 1 && absorption <= 15);
                }
            }
        }
        // Outgoing source emission is not attenuated by the source's own opacity.
        assertEquals(14, 15 - LightAttenuation.absorption(0, false, false));
        assertEquals(12, 15 - LightAttenuation.absorption(3, false, false));
        assertEquals(0, 15 - LightAttenuation.absorption(15, false, false));
    }

    @Test
    void directSkyStopsAtEitherSideOfAPositiveOpacityBoundary() {
        assertTrue(LightAttenuation.canExtrudeDirectSky(15, 0, 0));
        assertFalse(LightAttenuation.canExtrudeDirectSky(15, 0, 3));
        assertFalse(LightAttenuation.canExtrudeDirectSky(15, 3, 0));
        assertFalse(LightAttenuation.canExtrudeDirectSky(15, 0, 15));
    }

    private static int[] initialColumn(final int[] opacity) {
        final int[] result = new int[opacity.length];
        int level = 15;
        for (int i = 0; i < opacity.length; ++i) {
            level = LightAttenuation.skyAfterOpacity(level, opacity[i]);
            result[i] = level;
        }
        return result;
    }

    /** Small optical fixture, not a replacement for an in-game engine test. */
    private static int[] columnWithBfsHandoff(final int[] opacity) {
        final int[] result = new int[opacity.length];
        int level = 15;
        int aboveOpacity = 0;
        boolean direct = true;
        for (int i = 0; i < opacity.length; ++i) {
            direct &= LightAttenuation.canExtrudeDirectSky(level, aboveOpacity, opacity[i]);
            if (!direct) {
                level = Math.max(0, level - LightAttenuation.absorption(opacity[i], false, false));
            }
            result[i] = level;
            aboveOpacity = opacity[i];
        }
        return result;
    }
}

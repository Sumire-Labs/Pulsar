package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NibbleArrayPublicationTest {
    @Test
    void stridedPublicationUsesOnlyTheSelectedChunksSectionsAndPreservesNullStates() {
        final var height = WorldHeightContext.contiguous(-8, 23);
        int count = height.getTotalLightSections();
        var cache = new SWMRNibbleArray[(count + 2) * 25];
        java.util.Arrays.fill(cache, new SWMRNibbleArray());
        var baseline = new SWMRNibbleArray[count];
        var expected = new SWMRNibbleArray[count];
        for (int i = 0; i < count; i++) {
            baseline[i] = new SWMRNibbleArray(null, true);
            expected[i] = i % 2 == 0 ? baseline[i] : new SWMRNibbleArray();
            cache[37 + i * 25] = i % 2 == 0 ? null : expected[i];
        }
        NibbleArrayPublication.mergeFullLightFromCache(baseline, cache, 37, 25);
        for (int i = 0; i < count; i++) assertSame(expected[i], baseline[i]);
        assertThrows(IllegalArgumentException.class,
                () -> NibbleArrayPublication.mergeFullLightFromCache(baseline, cache, cache.length - 1, 25));
    }

    @Test
    void fullRelightMergeRetainsNullStateObjectsWhereCacheHasJavaNull() {
        final SWMRNibbleArray retainedNullState = new SWMRNibbleArray(null, true);
        final SWMRNibbleArray[] initialized = {retainedNullState, new SWMRNibbleArray(null, true)};
        final SWMRNibbleArray replacement = new SWMRNibbleArray();
        final SWMRNibbleArray[] cache = {null, replacement};

        NibbleArrayPublication.mergeFullLight(initialized, cache);

        assertSame(retainedNullState, initialized[0]);
        assertSame(replacement, initialized[1]);
        assertTrue(initialized[0].isNullNibbleVisible());
        assertTrue(initialized[1].isUninitialisedVisible());
    }

    @Test
    void removedNullStateNibbleIsReusedWithoutReplacingItsPublishedReference() {
        final SWMRNibbleArray retainedNullState = new SWMRNibbleArray(null, true);
        final SWMRNibbleArray[] removed = new SWMRNibbleArray[3];
        removed[1] = retainedNullState;

        assertSame(retainedNullState, NibbleArrayPublication.takeRemovedNibble(removed, 1, false));
        assertNull(removed[1]);
        assertNull(NibbleArrayPublication.takeRemovedNibble(removed, 2, false));
        final SWMRNibbleArray fallback = NibbleArrayPublication.takeRemovedNibble(removed, 2, true);
        assertNotNull(fallback);
        assertTrue(fallback.isNullNibbleUpdating());
    }

    @Test
    void lightArrayIndicesFollowExtendedWorldHeightBounds() {
        final WorldHeightContext height = WorldHeightContext.contiguous(-4, 11);
        assertEquals(18, height.getTotalLightSections());
        assertEquals(0, NibbleArrayPublication.lightIndex(height, -5));
        assertEquals(1, NibbleArrayPublication.lightIndex(height, -4));
        assertEquals(5, NibbleArrayPublication.lightIndex(height, 0));
        assertEquals(17, NibbleArrayPublication.lightIndex(height, 12));
        assertEquals(-1, NibbleArrayPublication.lightIndex(height, 13));
    }
}

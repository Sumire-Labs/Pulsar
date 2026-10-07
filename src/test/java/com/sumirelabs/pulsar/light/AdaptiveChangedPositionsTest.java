package com.sumirelabs.pulsar.light;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveChangedPositionsTest {

    @Test
    void promotedBitmapMatchesTheSparsePositionsAndDeduplicates() {
        final IntOpenHashSet sparse = compactPositions(-65);
        final AdaptiveChangedPositions.PromotionResult result =
                AdaptiveChangedPositions.considerPromotion(sparse);
        assertNotNull(result.promoted);

        final int duplicate = pack(0, -65, 0);
        assertFalse(result.promoted.add(duplicate));
        assertEquals(sparse.size(), result.promoted.size());
        assertEquals(asSet(sparse), asSet(result.promoted));
        for (int packed : sparse) {
            assertTrue(result.promoted.contains(packed));
        }
    }

    @Test
    void denseStoragePreservesSignedAndExtendedWorldHeights() {
        final AdaptiveChangedPositions positions = promoteCompact(-65);
        final int[] heights = {-2048, -1, 0, 255, 4096, 32767};
        for (int height : heights) {
            positions.add(pack(7, height, 11));
            assertTrue(positions.contains(pack(7, height, 11)));
        }
        final Set<Integer> decodedHeights = new HashSet<>();
        final IntIterator iterator = positions.iterator();
        while (iterator.hasNext()) {
            decodedHeights.add(iterator.nextInt() >> 8);
        }
        for (int height : heights) {
            assertTrue(decodedHeights.contains(height));
        }
    }

    @Test
    void scatteredExtendedSectionsStaySparseAtThePromotionCheck() {
        final IntOpenHashSet sparse = new IntOpenHashSet();
        for (int index = 0; index < AdaptiveChangedPositions.DENSE_THRESHOLD; ++index) {
            sparse.add(pack(index & 15, index << 4, (index >>> 4) & 15));
        }
        final AdaptiveChangedPositions.PromotionResult result =
                AdaptiveChangedPositions.considerPromotion(sparse);

        assertNull(result.promoted);
        assertEquals(4096 * AdaptiveChangedPositions.DENSE_SECTION_OCCUPANCY,
                result.nextCheckSize);
    }

    @Test
    void denseModeSupportsRemovalClearAndIteratorExhaustion() {
        final AdaptiveChangedPositions positions = promoteCompact(-65);
        final int removed = pack(7, -64, 8);
        assertTrue(positions.rem(removed));
        assertFalse(positions.rem(removed));
        assertFalse(positions.contains(removed));
        assertEquals(AdaptiveChangedPositions.DENSE_THRESHOLD - 1, positions.size());

        final IntIterator iterator = positions.iterator();
        while (iterator.hasNext()) {
            iterator.nextInt();
        }
        assertThrows(NoSuchElementException.class, iterator::nextInt);

        positions.clear();
        assertTrue(positions.isEmpty());
        assertEquals(0, positions.size());
        assertTrue(positions.add(pack(1, -1, 2)));
        assertEquals(1, positions.size());
    }

    @Test
    void denseModeRoundTripsPackedIntegerEndpoints() {
        final AdaptiveChangedPositions positions = promoteCompact(64);
        final int minimum = Integer.MIN_VALUE;
        final int maximum = Integer.MAX_VALUE;
        assertEquals(minimum, pack(0, -8_388_608, 0));
        assertEquals(maximum, pack(15, 8_388_607, 15));
        positions.add(minimum);
        positions.add(maximum);

        final Set<Integer> iterated = asSet(positions);
        assertTrue(iterated.contains(minimum));
        assertTrue(iterated.contains(maximum));
        assertEquals(AdaptiveChangedPositions.DENSE_THRESHOLD + 2, iterated.size());
        assertTrue(positions.rem(minimum));
        assertTrue(positions.rem(maximum));
    }

    private static AdaptiveChangedPositions promoteCompact(final int firstY) {
        final AdaptiveChangedPositions.PromotionResult result =
                AdaptiveChangedPositions.considerPromotion(compactPositions(firstY));
        assertNotNull(result.promoted);
        return result.promoted;
    }

    private static IntOpenHashSet compactPositions(final int firstY) {
        final IntOpenHashSet positions = new IntOpenHashSet();
        for (int index = 0; index < AdaptiveChangedPositions.DENSE_THRESHOLD; ++index) {
            positions.add(pack(index & 15, firstY + (index >>> 8), (index >>> 4) & 15));
        }
        return positions;
    }

    private static Set<Integer> asSet(final Iterable<Integer> positions) {
        final Set<Integer> result = new HashSet<>();
        for (int position : positions) {
            result.add(position);
        }
        return result;
    }

    private static int pack(final int x, final int y, final int z) {
        return (x & 15) | ((z & 15) << 4) | (y << 8);
    }
}

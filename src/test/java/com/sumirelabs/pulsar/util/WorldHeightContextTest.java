package com.sumirelabs.pulsar.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldHeightContextTest {

    @Test
    void vanillaContextUsesVanillaBoundsAndStorageIndices() {
        final WorldHeightContext context = WorldHeightContext.VANILLA;

        assertEquals(0, context.getMinSection());
        assertEquals(15, context.getMaxSection());
        assertEquals(-1, context.getMinLightSection());
        assertEquals(16, context.getMaxLightSection());
        assertEquals(18, context.getTotalLightSections());
        assertEquals(0xFFFF, context.getFullChunkSectionMask());
        assertEquals(0, context.getStorageIndex(0));
        assertEquals(15, context.getStorageIndex(15));
        assertEquals(-1, context.getStorageIndex(-1));
        assertTrue(context.containsBlockY(0));
        assertTrue(context.containsBlockY(255));
        assertFalse(context.containsBlockY(-1));
        assertFalse(context.containsBlockY(256));
    }

    @Test
    void mappedContextSupportsDepthsUpdateStorageLayout() {
        // Depths Update default: logical sections -4..19. Vanilla/upper
        // sections keep indices 0..19; negative sections are appended from
        // closest to the surface (-1) to deepest (-4).
        final int[] storageMapping = new int[24];
        storageMapping[0] = 23; // section -4
        storageMapping[1] = 22; // section -3
        storageMapping[2] = 21; // section -2
        storageMapping[3] = 20; // section -1
        for (int sectionY = 0; sectionY <= 19; ++sectionY) {
            storageMapping[sectionY + 4] = sectionY;
        }

        final WorldHeightContext context = WorldHeightContext.mapped(-4, 19, storageMapping);

        assertEquals(-64, context.getMinBlockY());
        assertEquals(319, context.getMaxBlockY());
        assertEquals(26, context.getTotalLightSections());
        assertEquals(0xFFFFFF, context.getFullChunkSectionMask());
        assertEquals(23, context.getStorageIndex(-4));
        assertEquals(20, context.getStorageIndex(-1));
        assertEquals(0, context.getStorageIndex(0));
        assertEquals(19, context.getStorageIndex(19));
        assertEquals(-4, context.getSectionForStorageIndex(23));
        assertEquals(-1, context.getSectionForStorageIndex(20));
        assertEquals(0, context.getSectionForStorageIndex(0));
        assertEquals(0, context.getLightSectionIndex(-5));
        assertEquals(25, context.getLightSectionIndex(20));
        assertTrue(context.containsBlockY(-64));
        assertTrue(context.containsBlockY(319));
        assertFalse(context.containsBlockY(-65));
        assertFalse(context.containsBlockY(320));
    }

    @Test
    void mappedContextRejectsDuplicateStorageIndices() {
        assertThrows(IllegalArgumentException.class,
                () -> WorldHeightContext.mapped(-1, 0, new int[]{0, 0}));
    }

    @Test
    void mappedContextAllowsUnusedPhysicalStorageSlots() {
        // Depths Update retains all 16 vanilla storage slots even when a
        // configured build range only uses a subset of them.
        final WorldHeightContext context = WorldHeightContext.mapped(4, 19, new int[]{
                4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19
        });

        assertEquals(4, context.getStorageIndex(4));
        assertEquals(19, context.getStorageIndex(19));
        assertEquals(0xFFFFF, context.getFullChunkSectionMask());
        assertEquals(Integer.MIN_VALUE, context.getSectionForStorageIndex(0));
        assertEquals(4, context.getSectionForStorageIndex(4));
    }
}

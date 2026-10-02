package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkUpdateMasksTest {
    @Test void fullRefreshCoversEverySectionExactlyOnceWithoutAFullLifecyclePacket() {
        for(int full:new int[]{0xFFFF,0xFFFFFF,-1}) {
            int union=0;
            for(int mask:ChunkUpdateMasks.split(full,full)) {
                assertNotEquals(0,mask); assertNotEquals(0xFFFF,mask); assertNotEquals(full,mask);
                assertEquals(0,union&mask); union|=mask;
            }
            assertEquals(full,union);
        }
    }
    @Test void partialSelectionIsPreservedIncludingEmptyAndHighSections() {
        assertArrayEquals(new int[]{1<<5},ChunkUpdateMasks.split(1<<5,0xFFFF));
        assertArrayEquals(new int[]{Integer.MIN_VALUE},ChunkUpdateMasks.split(Integer.MIN_VALUE,-1));
        assertArrayEquals(new int[0],ChunkUpdateMasks.split(0,0xFFFF));
        int union=0; for(int mask:ChunkUpdateMasks.split(0xFFFF,-1)) { assertNotEquals(0xFFFF,mask); union|=mask; }
        assertEquals(0xFFFF,union);
    }
}

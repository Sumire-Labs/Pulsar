package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketOnlyLightSectionsTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test
    void preservesLavaLightAcrossAbsentNetherSectionWithoutChangingLiveStorage() {
        final WorldHeightContext height = WorldHeightContext.VANILLA;
        final ExtendedBlockStorage[] live = new ExtendedBlockStorage[16];
        final SWMRNibbleArray[] block = nibbles(height, 4, 14);
        final ExtendedBlockStorage[] packet = ChunkPacketLight.prepareSections(
                height, block, null, live, false, -1);
        assertNotSame(live, packet);
        assertNull(live[4]);
        assertTrue(packet[4].isEmpty());
        assertNull(packet[4].getSkyLight());
        assertEquals(14, packet[4].getBlockLight().get(15, 0, 8));
        assertFalse(ChunkPacketLight.isTrivialForPacket(packet[4], false));
        // A later publication must not change the size/extraction snapshot.
        block[height.getLightSectionIndex(4)].set(15, 0, 8, 3);
        block[height.getLightSectionIndex(4)].updateVisible();
        assertEquals(14, packet[4].getBlockLight().get(15, 0, 8));
    }

    @Test
    void keepsZeroLightAbsentAndExistingSectionsUntouched() {
        final WorldHeightContext height = WorldHeightContext.VANILLA;
        final ExtendedBlockStorage[] live = new ExtendedBlockStorage[16];
        live[4] = new ExtendedBlockStorage(64, false);
        final SWMRNibbleArray[] block = nibbles(height, 4, 14);
        block[height.getLightSectionIndex(5)] = new SWMRNibbleArray();
        assertSame(live, ChunkPacketLight.prepareSections(height, block, null, live, false, -1));
        assertEquals(0, live[4].getBlockLight().get(15, 0, 8));
        assertNull(live[5]);
        assertSame(live, ChunkPacketLight.prepareSections(height, null, null, live, false, -1));
    }

    @Test
    void respectsPhysicalSectionMaskIncludingNegativeWorldHeights() {
        final WorldHeightContext height = WorldHeightContext.mapped(-2, 1, new int[]{31, 17, 0, 1});
        final ExtendedBlockStorage[] live = new ExtendedBlockStorage[32];
        final SWMRNibbleArray[] block = nibbles(height, -2, 14);
        block[height.getLightSectionIndex(-1)] = block[height.getLightSectionIndex(-2)];
        final ExtendedBlockStorage[] packet = ChunkPacketLight.prepareSections(
                height, block, null, live, false, Integer.MIN_VALUE);
        assertNotNull(packet[31]);
        assertEquals(-32, packet[31].getYLocation());
        assertNull(packet[17]);
        assertSame(live, ChunkPacketLight.prepareSections(height, block, null, live, false, 0));
    }

    @Test
    void initializesSkyInTemporaryBlockLightSections() {
        final WorldHeightContext height = WorldHeightContext.VANILLA;
        final ExtendedBlockStorage[] live = new ExtendedBlockStorage[16];
        final SWMRNibbleArray[] block = nibbles(height, 4, 14);
        final ExtendedBlockStorage[] daylight = ChunkPacketLight.prepareSections(
                height, block, null, live, true, -1);
        assertEquals(15, daylight[4].getSkyLight().get(15, 0, 8));
        final SWMRNibbleArray[] sky = new SWMRNibbleArray[block.length];
        sky[height.getLightSectionIndex(5)] = new SWMRNibbleArray(); // Enclosed above.
        final ExtendedBlockStorage[] enclosed = ChunkPacketLight.prepareSections(
                height, block, sky, live, true, -1);
        assertEquals(0, enclosed[4].getSkyLight().get(15, 0, 8));
        assertNull(live[4]);
    }

    @Test
    void snapshotsEveryNonzeroVisibleByteIncludingHiddenLight() {
        final WorldHeightContext height = WorldHeightContext.VANILLA;
        final byte[] data = new byte[2048];
        data[2047] = (byte) 0xE0;
        final SWMRNibbleArray[] block = new SWMRNibbleArray[height.getTotalLightSections()];
        block[height.getLightSectionIndex(4)] = new SWMRNibbleArray(data, SWMRNibbleArray.INIT_STATE_HIDDEN);
        final ExtendedBlockStorage[] packet = ChunkPacketLight.prepareSections(
                height, block, null, new ExtendedBlockStorage[16], false, -1);
        assertEquals(14, packet[4].getBlockLight().get(15, 15, 15));
    }

    private static SWMRNibbleArray[] nibbles(WorldHeightContext height, int section, int level) {
        final SWMRNibbleArray[] result = new SWMRNibbleArray[height.getTotalLightSections()];
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.set(15, 0, 8, level);
        nibble.updateVisible();
        result[height.getLightSectionIndex(section)] = nibble;
        return result;
    }
}

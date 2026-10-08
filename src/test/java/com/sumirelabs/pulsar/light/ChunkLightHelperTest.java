package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLightHelperTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test
    void bulkInitializationMatchesCellReadsAcrossStatesAndNegativeHeights() {
        final WorldHeightContext height = WorldHeightContext.contiguous(-8, 15);
        final Random random = new Random(8317);
        for (int attempt = 0; attempt < 40; attempt++) {
            final SWMRNibbleArray[] sky = randomNibbles(height, random);
            final SWMRNibbleArray[] block = randomNibbles(height, random);
            // Include missing groups, not just missing section entries.
            final SWMRNibbleArray[] skyGroup = attempt % 7 == 0 ? null : sky;
            final SWMRNibbleArray[] blockGroup = attempt % 11 == 0 ? null : block;
            for (int sectionY = height.getMinLightSection() - 1;
                 sectionY <= height.getMaxLightSection() + 1; sectionY++) {
                final ExtendedBlockStorage section = new ExtendedBlockStorage(sectionY << 4, true);
                ChunkLightHelper.fillVanillaFromEngine(height, skyGroup, blockGroup, section, sectionY, true);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    final int worldY = (sectionY << 4) + y;
                    assertEquals(ChunkLightHelper.getBlockLight(height, blockGroup, x, worldY, z),
                            section.getBlockLight().get(x, y, z));
                    assertEquals(ChunkLightHelper.getSkyLight(height, skyGroup, x, worldY, z),
                            section.getSkyLight().get(x, y, z));
                }
            }
        }
    }

    @Test
    void noSkyWorldStillInitializesBlockLight() {
        final WorldHeightContext height = WorldHeightContext.VANILLA;
        final SWMRNibbleArray[] block = randomNibbles(height, new Random(15));
        final ExtendedBlockStorage section = new ExtendedBlockStorage(64, false);
        ChunkLightHelper.fillVanillaFromEngine(height, null, block, section, 4, false);
        assertNull(section.getSkyLight());
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(ChunkLightHelper.getBlockLight(height, block, x, 64 + y, z),
                    section.getBlockLight().get(x, y, z));
        }
    }

    @Test
    void partialSyncUsesPhysicalMappingIncludingSignBitAndLeavesOtherSectionsUntouched() {
        final WorldHeightContext height = WorldHeightContext.mapped(-2, 1, new int[]{31, 17, 0, 1});
        final ExtendedBlockStorage[] storage = new ExtendedBlockStorage[32];
        final SWMRNibbleArray[] block = new SWMRNibbleArray[height.getTotalLightSections()];
        final SWMRNibbleArray[] sky = new SWMRNibbleArray[block.length];
        for (int y = -2; y <= 1; y++) {
            storage[height.getStorageIndex(y)] = new ExtendedBlockStorage(y << 4, true);
            final byte[] bytes = new byte[2048];
            java.util.Arrays.fill(bytes, (byte) 0x73);
            block[height.getLightSectionIndex(y)] = new SWMRNibbleArray(bytes.clone());
            sky[height.getLightSectionIndex(y)] = new SWMRNibbleArray(bytes.clone());
        }
        final int mask = Integer.MIN_VALUE | (1 << 1);
        ChunkLightHelper.syncBlockToVanilla(height, block, storage, mask);
        ChunkLightHelper.syncSkyToVanilla(height, sky, storage, mask);
        for (int y = -2; y <= 1; y++) {
            final int slot = height.getStorageIndex(y);
            final int expected = slot == 31 || slot == 1 ? 0x73 : 0;
            for (byte value : storage[slot].getBlockLight().getData()) assertEquals(expected, value & 255);
            for (byte value : storage[slot].getSkyLight().getData()) assertEquals(expected, value & 255);
        }
        ChunkLightHelper.syncBlockToVanilla(height, block, storage, 0);
        assertEquals(0, storage[17].getBlockLight().get(0, 0, 0));
        ChunkLightHelper.syncBlockToVanilla(height, block, storage);
        ChunkLightHelper.syncSkyToVanilla(height, sky, storage);
        assertEquals(3, storage[17].getBlockLight().get(0, 0, 0));
        assertEquals(3, storage[0].getSkyLight().get(0, 0, 0));
    }

    private static SWMRNibbleArray[] randomNibbles(WorldHeightContext height, Random random) {
        final SWMRNibbleArray[] result = new SWMRNibbleArray[height.getTotalLightSections()];
        for (int i = 0; i < result.length; i++) {
            switch (random.nextInt(7)) {
                case 0 -> result[i] = null;
                case 1 -> result[i] = new SWMRNibbleArray(null, true);
                case 2 -> result[i] = new SWMRNibbleArray();
                default -> {
                    final byte[] bytes = new byte[2048];
                    random.nextBytes(bytes);
                    // Even an UNINIT carrying bytes must read as zero for sky;
                    // HIDDEN retains its visible data for the chunk getters.
                    result[i] = new SWMRNibbleArray(bytes, random.nextBoolean()
                            ? SWMRNibbleArray.INIT_STATE_INIT
                            : random.nextBoolean() ? SWMRNibbleArray.INIT_STATE_UNINIT
                            : SWMRNibbleArray.INIT_STATE_HIDDEN);
                }
            }
        }
        return result;
    }
}

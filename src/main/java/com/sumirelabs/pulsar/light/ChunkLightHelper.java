package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldUtil;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * Helpers to import vanilla {@link ExtendedBlockStorage} nibble arrays into
 * Pulsar's {@link SWMRNibbleArray} containers and to publish SWMR visible
 * data back into the vanilla nibbles for rendering.
 *
 * <p>Pulsar (scalar release) keeps one block-light and one sky-light nibble
 * group per chunk. RGB-related arguments and behaviour from the original
 * SuperNova source have been removed.
 */
public final class ChunkLightHelper {

    private ChunkLightHelper() {}

    /**
     * @return {@code true} if any non-null block-light nibble exists in the
     *         given group whose corresponding {@link ExtendedBlockStorage} is
     *         loaded.
     */
    public static boolean hasSavedBlockData(SWMRNibbleArray[] blockNibbles, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < blockNibbles.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) {
                continue;
            }
            if (!blockNibbles[i].isNullNibbleVisible()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wrap each loaded section's vanilla sky-light {@link NibbleArray} in a
     * fresh {@link SWMRNibbleArray}. If {@code onlyWhereNull} is true, only
     * untouched ({@code NULL} state) entries are imported.
     */
    public static void importVanillaSky(SWMRNibbleArray[] sky, ExtendedBlockStorage[] storageArrays, boolean onlyWhereNull) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < sky.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;
            final NibbleArray vanillaSky = storageArrays[sectionY].getSkyLight();
            if (vanillaSky == null) continue;
            if (onlyWhereNull && !sky[i].isNullNibbleVisible()) continue;
            sky[i] = SWMRNibbleArray.fromVanilla(vanillaSky);
        }
    }

    /**
     * Wrap each loaded section's vanilla block-light {@link NibbleArray} in a
     * fresh {@link SWMRNibbleArray}, replacing any existing entry.
     */
    public static void importVanillaBlock(SWMRNibbleArray[] block, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < block.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;
            final NibbleArray vanillaBlock = storageArrays[sectionY].getBlockLight();
            if (vanillaBlock == null) continue;
            block[i] = SWMRNibbleArray.fromVanilla(vanillaBlock);
        }
    }

    /** Publish SWMR sky visible data back into the vanilla nibble arrays. */
    public static void syncSkyToVanilla(SWMRNibbleArray[] skyNibbles, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < skyNibbles.length; ++i) {
            final SWMRNibbleArray skyNib = skyNibbles[i];
            if (skyNib == null) continue;

            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;

            final NibbleArray vanilla = storageArrays[sectionY].getSkyLight();
            if (vanilla == null) continue;

            final byte[] vanillaData = vanilla.getData();
            final byte[] data = skyNib.getVisibleData();
            if (data != null) {
                System.arraycopy(data, 0, vanillaData, 0, SWMRNibbleArray.ARRAY_SIZE);
            } else {
                Arrays.fill(vanillaData, (byte) 0xFF);
            }
        }
    }

    /** Publish SWMR block visible data back into the vanilla nibble arrays. */
    public static void syncBlockToVanilla(SWMRNibbleArray[] blockNibbles, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < blockNibbles.length; ++i) {
            final SWMRNibbleArray nib = blockNibbles[i];
            if (nib == null) continue;

            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;

            final NibbleArray vanilla = storageArrays[sectionY].getBlockLight();
            if (vanilla == null) continue;

            final byte[] vanillaData = vanilla.getData();
            final byte[] data = nib.getVisibleData();
            if (data != null) {
                System.arraycopy(data, 0, vanillaData, 0, SWMRNibbleArray.ARRAY_SIZE);
            } else {
                Arrays.fill(vanillaData, (byte) 0);
            }
        }
    }

    /**
     * Read the visible block-light value at world coordinates {@code (x, y, z)}.
     * Returns {@code 0} if the section is out of bounds or the nibble is null.
     */
    public static int getBlockLight(SWMRNibbleArray[] block, int x, int y, int z) {
        final int sectionY = y >> 4;
        final int minLightSection = WorldUtil.getMinLightSection();
        final int maxLightSection = WorldUtil.getMaxLightSection();

        if (sectionY > maxLightSection || sectionY < minLightSection) {
            return 0;
        }
        if (block == null) {
            return 0;
        }

        final int idx = sectionY - minLightSection;
        final SWMRNibbleArray nib = block[idx];
        if (nib == null) return 0;
        return nib.getVisible((x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
    }

    /**
     * Read the visible sky-light value at world coordinates {@code (x, y, z)}.
     * Returns {@code 15} above the world top, {@code 0} below the world
     * bottom, and {@code 15} when the section nibble is unmaterialised
     * (matches vanilla expectations).
     */
    public static int getSkyLight(SWMRNibbleArray[] sky, int x, int y, int z) {
        final int sectionY = y >> 4;
        final int minLightSection = WorldUtil.getMinLightSection();
        final int maxLightSection = WorldUtil.getMaxLightSection();

        if (sectionY > maxLightSection) {
            return 15;
        }
        if (sectionY < minLightSection) {
            return 0;
        }
        if (sky == null) {
            return 15;
        }

        final int idx = sectionY - minLightSection;
        final SWMRNibbleArray nib = sky[idx];
        if (nib == null || nib.isNullNibbleVisible() || nib.isUninitialisedVisible()) {
            return 15;
        }
        return nib.getVisible(x, y, z);
    }
}

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

    private ChunkLightHelper() {
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

    /**
     * Wrap each loaded section's vanilla nibble arrays WITHOUT copying: the
     * SWMR array shares the vanilla {@code byte[]} storage, so
     * {@code updateVisible()} publishes straight into what the renderer
     * reads. Thin-client use only — sharing the storage is safe only when
     * all light writes happen on the main thread.
     */
    public static void wrapVanillaBlock(SWMRNibbleArray[] block, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < block.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;
            final NibbleArray vanillaBlock = storageArrays[sectionY].getBlockLight();
            if (vanillaBlock == null) continue;
            block[i] = new SWMRNibbleArray(vanillaBlock.getData());
        }
    }

    /**
     * Sky-light variant of {@link #wrapVanillaBlock}.
     */
    public static void wrapVanillaSky(SWMRNibbleArray[] sky, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < sky.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;
            final NibbleArray vanillaSky = storageArrays[sectionY].getSkyLight();
            if (vanillaSky == null) continue;
            sky[i] = new SWMRNibbleArray(vanillaSky.getData());
        }
    }

    /**
     * Publish SWMR sky visible data back into the vanilla nibble arrays.
     */
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
            // Copy under the nibble's monitor: updateVisible() mutates the
            // visible array in place under the same lock, and an unlocked
            // arraycopy from another worker could publish torn bytes.
            synchronized (skyNib) {
                final byte[] data = skyNib.getVisibleData();
                if (data != null) {
                    System.arraycopy(data, 0, vanillaData, 0, SWMRNibbleArray.ARRAY_SIZE);
                } else if (skyNib.isUninitialisedVisible()) {
                    // UNINIT means "exists, all zero" (upstream reads it as
                    // 0). Filling 0xFF here painted every enclosed cave and
                    // ocean floor section fully bright.
                    Arrays.fill(vanillaData, (byte) 0);
                }
                // NULL state: leave the vanilla nibble untouched — the
                // vanilla column fill already approximates open-sky sections.
            }
        }
    }

    /**
     * Publish SWMR block visible data back into the vanilla nibble arrays.
     */
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
            synchronized (nib) {
                final byte[] data = nib.getVisibleData();
                if (data != null) {
                    System.arraycopy(data, 0, vanillaData, 0, SWMRNibbleArray.ARRAY_SIZE);
                } else {
                    Arrays.fill(vanillaData, (byte) 0);
                }
            }
        }
    }

    /**
     * Fill a freshly created {@link ExtendedBlockStorage}'s nibble arrays from
     * the SWMR visible state. A section created AFTER a chunk is light-ready
     * gets all-zero vanilla nibbles and nothing re-publishes the engine's
     * (already-visible, therefore not-dirty) data into them: the naive column
     * fill is gated on {@code !lightReady} and {@code onNibbleVisible} only
     * fires on dirty nibbles — so without this the section ships to clients
     * black. Root cause of the 2026-07 black-chunk regression.
     */
    public static void fillVanillaFromEngine(SWMRNibbleArray[] skyNibbles, SWMRNibbleArray[] blockNibbles,
                                             ExtendedBlockStorage section, int sectionY, boolean hasSky) {
        final NibbleArray blockArr = section.getBlockLight();
        final NibbleArray skyArr = hasSky ? section.getSkyLight() : null;
        final int baseY = sectionY << 4;
        for (int y = 0; y < 16; ++y) {
            for (int z = 0; z < 16; ++z) {
                for (int x = 0; x < 16; ++x) {
                    if (blockArr != null) {
                        blockArr.set(x, y, z, getBlockLight(blockNibbles, x, baseY + y, z));
                    }
                    if (skyArr != null) {
                        skyArr.set(x, y, z, getSkyLight(skyNibbles, x, baseY + y, z));
                    }
                }
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
        if (nib == null || nib.isNullNibbleVisible()) {
            // Upstream semantics: extrude from the first non-null nibble
            // above (its bottom layer carries the column's shadow); 15 only
            // when nothing above exists.
            for (int i = idx + 1; i < sky.length; ++i) {
                final SWMRNibbleArray above = sky[i];
                if (above == null || above.isNullNibbleVisible()) {
                    continue;
                }
                if (above.isUninitialisedVisible()) {
                    return 0;
                }
                return above.getVisible((x & 15) | ((z & 15) << 4));
            }
            return 15;
        }
        if (nib.isUninitialisedVisible()) {
            // UNINIT = exists, all zero — NOT unknown/15.
            return 0;
        }
        return nib.getVisible(x, y, z);
    }
}

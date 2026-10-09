package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Decides whether an otherwise block-empty section can be omitted from a
 * full chunk packet without losing light data.
 */
public final class ChunkPacketLight {

    private ChunkPacketLight() {
    }

    /**
     * Vanilla has no payload for a null storage slot, even when Pulsar holds
     * block light there. Supply light-only sections for this packet without
     * installing them into the live chunk or changing its emptiness maps.
     */
    public static ExtendedBlockStorage[] prepareSections(
            final WorldHeightContext height, final SWMRNibbleArray[] block,
            final SWMRNibbleArray[] sky, final ExtendedBlockStorage[] storage,
            final boolean hasSky, final int sectionMask) {
        ExtendedBlockStorage[] result = storage;
        if (block == null) return result;
        for (int sectionY = height.getMinSection(); sectionY <= height.getMaxSection(); sectionY++) {
            final int slot = height.getStorageIndex(sectionY);
            if (slot < 0 || slot >= storage.length || storage[slot] != null
                    || (sectionMask != -1 && (slot >= Integer.SIZE || (sectionMask & (1 << slot)) == 0))) {
                continue;
            }
            final int index = height.getLightSectionIndex(sectionY);
            if (index < 0 || index >= block.length || block[index] == null) continue;
            final byte[] light;
            synchronized (block[index]) {
                final byte[] visible = block[index].getVisibleData();
                if (PacketLightSection.isTrivial(true, visible, null, false)) continue;
                light = visible.clone();
            }
            final ExtendedBlockStorage section = new ExtendedBlockStorage(sectionY << 4, hasSky);
            ChunkLightHelper.fillVanillaFromEngine(height, sky, null, section, sectionY, hasSky);
            System.arraycopy(light, 0, section.getBlockLight().getData(), 0, light.length);
            if (result == storage) result = storage.clone();
            result[slot] = section;
        }
        return result;
    }

    /**
     * Vanilla only checks the section's block count. That drops block light,
     * or non-default sky light, when the affected 16-cubed section contains
     * no blocks (MC-80966). A packet may omit the section only when both its
     * blocks and every light array written by that packet are trivial.
     */
    public static boolean isTrivialForPacket(final ExtendedBlockStorage section,
                                             final boolean writeSkylight) {
        return PacketLightSection.isTrivial(
                section.isEmpty(),
                section.getBlockLight() == null ? null : section.getBlockLight().getData(),
                section.getSkyLight() == null ? null : section.getSkyLight().getData(),
                writeSkylight);
    }
}

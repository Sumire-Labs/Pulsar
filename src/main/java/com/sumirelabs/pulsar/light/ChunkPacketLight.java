package com.sumirelabs.pulsar.light;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Decides whether an otherwise block-empty section can be omitted from a
 * full chunk packet without losing light data.
 */
public final class ChunkPacketLight {

    private ChunkPacketLight() {
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

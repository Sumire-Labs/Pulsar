package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.ChunkPacketLight;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps non-trivial light in block-empty sections of full chunk packets.
 */
@Mixin(SPacketChunkData.class)
public abstract class MixinSPacketChunkData {

    @Redirect(
            method = "extractChunkData(Lnet/minecraft/network/PacketBuffer;Lnet/minecraft/world/chunk/Chunk;ZI)I",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;isEmpty()Z"),
            require = 0)
    private boolean pulsar$isSectionTrivialForExtraction(
            final ExtendedBlockStorage section, final PacketBuffer buffer,
            final Chunk chunk, final boolean writeSkylight, final int changedSectionFilter) {
        return ChunkPacketLight.isTrivialForPacket(section, writeSkylight);
    }

    @Redirect(
            method = "calculateChunkSize(Lnet/minecraft/world/chunk/Chunk;ZI)I",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;isEmpty()Z"),
            require = 0)
    private boolean pulsar$isSectionTrivialForSize(
            final ExtendedBlockStorage section, final Chunk chunk,
            final boolean writeSkylight, final int changedSectionFilter) {
        return ChunkPacketLight.isTrivialForPacket(section, writeSkylight);
    }
}

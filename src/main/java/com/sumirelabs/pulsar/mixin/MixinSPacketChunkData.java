package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.ChunkPacketLight;
import com.sumirelabs.pulsar.light.PulsarChunk;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps non-trivial light in block-empty sections of full chunk packets.
 */
@Mixin(SPacketChunkData.class)
public abstract class MixinSPacketChunkData {

    // Chunk packets serialize vanilla EBS arrays, so publish visible Pulsar light first.
    @Inject(
            method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V",
            at = @At("HEAD"),
            require = 0)
    private static void pulsar$syncVisibleLightBeforePacket(final Chunk chunk,
                                                            final int changedSectionFilter,
                                                            final CallbackInfo ci) {
        if (chunk instanceof PulsarChunk) {
            final PulsarChunk pulsarChunk = (PulsarChunk) chunk;
            if (pulsarChunk.pulsar$isLightReady()) {
                pulsarChunk.pulsar$syncLightToVanilla();
            }
        }
    }

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

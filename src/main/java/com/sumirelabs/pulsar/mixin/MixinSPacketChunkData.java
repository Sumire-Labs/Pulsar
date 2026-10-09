package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.ChunkPacketLight;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.util.WorldUtil;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps non-trivial light in empty or physically absent packet sections.
 */
@Mixin(SPacketChunkData.class)
public abstract class MixinSPacketChunkData {

    @Unique
    private ExtendedBlockStorage[] pulsar$packetSections;

    @Unique
    private ExtendedBlockStorage[] pulsar$getPacketSections(
            final Chunk chunk, final boolean hasSky, final int sectionMask) {
        if (this.pulsar$packetSections == null) {
            final ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
            this.pulsar$packetSections = chunk instanceof PulsarChunk
                    ? ChunkPacketLight.prepareSections(
                    WorldUtil.getHeightContext(chunk.getWorld()),
                    ((PulsarChunk) chunk).pulsar$getBlockNibbles(),
                    ((PulsarChunk) chunk).pulsar$getSkyNibbles(), storage, hasSky,
                    sectionMask == 0xFFFF ? -1 : sectionMask)
                    : storage;
        }
        return this.pulsar$packetSections;
    }

    @Redirect(method = "calculateChunkSize",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/Chunk;getBlockStorageArray()[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;"),
            require = 0)
    private ExtendedBlockStorage[] pulsar$sizePacketSections(
            final Chunk chunk, final Chunk owner, final boolean hasSky, final int sectionMask) {
        return this.pulsar$getPacketSections(chunk, hasSky, sectionMask);
    }

    @Redirect(method = "extractChunkData",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/Chunk;getBlockStorageArray()[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;"),
            require = 0)
    private ExtendedBlockStorage[] pulsar$extractPacketSections(
            final Chunk chunk, final PacketBuffer buffer, final Chunk owner,
            final boolean hasSky, final int sectionMask) {
        return this.pulsar$getPacketSections(chunk, hasSky, sectionMask);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", at = @At("RETURN"), require = 0)
    private void pulsar$releasePacketSections(final Chunk chunk, final int sectionMask, final CallbackInfo ci) {
        this.pulsar$packetSections = null;
    }

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
                // Vanilla treats 0xFFFF as a full lifecycle packet. Retain a
                // full sync there for height mods which append storage slots.
                pulsarChunk.pulsar$syncLightToVanilla(
                        changedSectionFilter == 0xFFFF ? -1 : changedSectionFilter);
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

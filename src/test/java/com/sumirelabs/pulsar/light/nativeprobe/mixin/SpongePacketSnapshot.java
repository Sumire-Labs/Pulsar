package com.sumirelabs.pulsar.light.nativeprobe.mixin;

import com.sumirelabs.pulsar.light.nativeprobe.PacketSnapshotProbe;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reproduces SpongeCommon stable-7 SPacketChunkDataMixin_Async_Lighting's two redirects.
 * Original algorithm: https://github.com/SpongePowered/SpongeCommon/blob/stable-7/src/main/java/org/spongepowered/common/mixin/optimization/network/play/server/SPacketChunkDataMixin_Async_Lighting.java
 * Snapshot counters are test instrumentation, not a replacement for a full Sponge server. */
@Mixin(SPacketChunkData.class)
public abstract class SpongePacketSnapshot implements PacketSnapshotProbe {
    @Unique private ExtendedBlockStorage[] snapshot;
    @Unique private int snapshots, extractions;

    @Redirect(method = "calculateChunkSize", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/chunk/Chunk;getBlockStorageArray()[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;"))
    private ExtendedBlockStorage[] snapshotForSize(Chunk chunk) {
        snapshots++;
        snapshot = chunk.getBlockStorageArray().clone();
        // Simulate a concurrent live-array replacement after Sponge snapshots
        // it. A compatible consumer must use the captured view, not reread live.
        chunk.getBlockStorageArray()[0] = null;
        return snapshot;
    }

    @Redirect(method = "extractChunkData", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/chunk/Chunk;getBlockStorageArray()[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;"))
    private ExtendedBlockStorage[] snapshotForExtraction(Chunk chunk) {
        extractions++;
        try { return snapshot; }
        finally { snapshot = null; }
    }

    public int snapshotCalls() { return snapshots; }
    public int extractionCalls() { return extractions; }
    public boolean snapshotReleased() { return snapshot == null; }
}

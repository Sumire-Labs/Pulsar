package com.sumirelabs.pulsar.mixin;

// REID coexistence: REID also @Unique-injects fields onto Chunk. The pulsar$
// prefix below avoids collisions with its biome-container state.

import com.sumirelabs.pulsar.api.ExtendedChunk;
import com.sumirelabs.pulsar.light.ChunkLightHelper;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.WorldUtil;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Owns Pulsar's per-chunk state and chunk lifecycle integration.
 *
 * <p>Vanilla lighting interception and new-section synchronization live in
 * {@link MixinChunkVanillaLighting} and {@link MixinChunkSectionChanges}; this
 * class deliberately stays focused on storage, load/unload and packet import.
 */
@Mixin(Chunk.class)
@SuppressWarnings("deprecation")
public abstract class MixinChunk implements PulsarChunk, ExtendedChunk {

    @Shadow
    @Final
    public int x;
    @Shadow
    @Final
    public int z;
    @Shadow
    public boolean isLightPopulated;
    @Shadow
    @Final
    private World world;
    @Unique
    private volatile SWMRNibbleArray[] pulsar$blockNibbles;
    @Unique
    private volatile SWMRNibbleArray[] pulsar$skyNibbles;
    @Unique
    private boolean[] pulsar$blockEmptinessMap;
    @Unique
    private boolean[] pulsar$skyEmptinessMap;
    @Unique
    private volatile boolean pulsar$lightReady;
    @Unique
    private volatile boolean pulsar$lightUsable;
    @Unique
    private volatile boolean pulsar$savedLightValid;
    @Unique
    private WorldHeightContext pulsar$heightContext;

    @Shadow
    public abstract ExtendedBlockStorage[] getBlockStorageArray();

    @Unique
    private WorldHeightContext pulsar$getHeightContext() {
        if (this.pulsar$heightContext == null) {
            this.pulsar$heightContext = WorldUtil.getHeightContext(this.world);
        }
        return this.pulsar$heightContext;
    }

    /**
     * Once Pulsar owns usable light state, expose it through the vanilla chunk
     * API. Empty sections have no vanilla storage but still have SWMR values.
     */
    @Inject(method = "getLightFor", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$getLightFor(final EnumSkyBlock lightType, final BlockPos pos,
                                    final CallbackInfoReturnable<Integer> cir) {
        if (!this.pulsar$isLightUsable()) {
            return;
        }

        final WorldHeightContext heightContext = this.pulsar$getHeightContext();
        if (lightType == EnumSkyBlock.SKY) {
            cir.setReturnValue(this.world.provider.hasSkyLight()
                    ? ChunkLightHelper.getSkyLight(
                    heightContext, this.pulsar$skyNibbles, pos.getX(), pos.getY(), pos.getZ())
                    : 0);
            return;
        }

        cir.setReturnValue(ChunkLightHelper.getBlockLight(
                heightContext, this.pulsar$blockNibbles, pos.getX(), pos.getY(), pos.getZ()));
    }

    @Inject(method = "<init>(Lnet/minecraft/world/World;II)V", at = @At("RETURN"), require = 0)
    private void pulsar$initFields(final World world, final int x, final int z, final CallbackInfo ci) {
        this.pulsar$heightContext = WorldUtil.getHeightContext(world);
        final int totalLightSections = this.pulsar$heightContext.getTotalLightSections();
        this.pulsar$blockNibbles = new SWMRNibbleArray[totalLightSections];
        this.pulsar$skyNibbles = new SWMRNibbleArray[totalLightSections];
        for (int i = 0; i < totalLightSections; ++i) {
            this.pulsar$blockNibbles[i] = new SWMRNibbleArray(null, true);
            this.pulsar$skyNibbles[i] = new SWMRNibbleArray(null, true);
        }
    }

    /**
     * Imports or restores light storage, registers the chunk and schedules the
     * server's initial propagation (or cheap persisted-light initialization).
     */
    @Inject(method = "onLoad", at = @At("HEAD"), require = 0)
    private void pulsar$onLoad(final CallbackInfo ci) {
        final Chunk self = (Chunk) (Object) this;

        // Restored SWMR nibbles contain boundary sections and state markers
        // that cannot be reconstructed from vanilla storage.
        if (!this.pulsar$savedLightValid) {
            ChunkLightHelper.importVanillaBlock(
                    this.pulsar$getHeightContext(), this.pulsar$blockNibbles, this.getBlockStorageArray());
            if (this.world.provider.hasSkyLight()) {
                ChunkLightHelper.importVanillaSky(
                        this.pulsar$getHeightContext(), this.pulsar$skyNibbles,
                        this.getBlockStorageArray(), false);
            }
        }

        // PlayerChunkMapEntry performs the real Pulsar readiness gate. Keep
        // vanilla from refusing the chunk before asynchronous lighting starts.
        this.isLightPopulated = true;

        final WorldLightManager manager = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (manager == null) {
            return;
        }
        manager.registerChunk(self);

        if (this.world.isRemote) {
            // Client onLoad precedes packet section deserialization; onRead
            // performs the import and queueing once storage exists.
            return;
        }

        final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(self);
        if (this.pulsar$savedLightValid) {
            this.pulsar$syncLightToVanilla();
            this.pulsar$lightReady = true;
            manager.queueChunkLoadInit(this.x, this.z, self, emptySections);
        } else {
            manager.queueChunkLight(this.x, this.z, self, emptySections);
        }
        manager.scheduleUpdate();
    }

    @Inject(method = "onUnload", at = @At("HEAD"), require = 0)
    private void pulsar$onUnload(final CallbackInfo ci) {
        final WorldLightManager manager = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (manager == null) {
            return;
        }

        // Wait before removing the completion entry; the reverse order makes
        // awaitPendingWork a no-op while a worker may still mutate this chunk.
        if (!this.world.isRemote) {
            final boolean workFinished = manager.awaitPendingWork(this.x, this.z);
            if (!workFinished || manager.hasPendingLightWork(this.x, this.z)) {
                // Invalidating readiness makes the save omit the cache so the
                // chunk relights instead of preserving a partial snapshot.
                this.pulsar$lightReady = false;
            }
        }
        manager.removeChunkFromQueues(this.x, this.z);
        manager.unregisterChunk(this.x, this.z);
    }

    /**
     * Wraps packet nibbles without copying on the thin client and queues only
     * the inexpensive cache/emptiness initialization.
     */
    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private void pulsar$onRead(final PacketBuffer buf, final int availableSections,
                               final boolean groundUpContinuous, final CallbackInfo ci) {
        if (!this.world.isRemote) {
            return;
        }
        final Chunk self = (Chunk) (Object) this;

        ChunkLightHelper.wrapVanillaBlock(
                this.pulsar$getHeightContext(), this.pulsar$blockNibbles, this.getBlockStorageArray());
        if (this.world.provider.hasSkyLight()) {
            ChunkLightHelper.wrapVanillaSky(
                    this.pulsar$getHeightContext(), this.pulsar$skyNibbles, this.getBlockStorageArray());
        }

        final WorldLightManager manager = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (manager == null) {
            return;
        }
        manager.registerChunk(self);
        this.pulsar$lightReady = true;
        manager.queueChunkLoadInit(
                this.x, this.z, self, PulsarEngine.getEmptySectionsForChunk(self));
    }

    @Override
    public boolean pulsar$isLightReady() {
        return this.pulsar$lightReady;
    }

    @Override
    public void pulsar$setLightReady(final boolean ready) {
        this.pulsar$lightReady = ready;
    }

    @Override
    public boolean pulsar$isLightUsable() {
        return this.pulsar$lightReady || this.pulsar$lightUsable;
    }

    @Override
    public void pulsar$setLightUsable(final boolean usable) {
        this.pulsar$lightUsable = usable;
    }

    @Override
    public void pulsar$setSavedLightValid(final boolean valid) {
        this.pulsar$savedLightValid = valid;
    }

    @Override
    public boolean pulsar$hasSavedLightValid() {
        return this.pulsar$savedLightValid;
    }

    @Override
    public void pulsar$syncLightToVanilla() {
        ChunkLightHelper.syncBlockToVanilla(
                this.pulsar$getHeightContext(), this.pulsar$blockNibbles, this.getBlockStorageArray());
        if (this.world.provider.hasSkyLight()) {
            ChunkLightHelper.syncSkyToVanilla(
                    this.pulsar$getHeightContext(), this.pulsar$skyNibbles, this.getBlockStorageArray());
        }
    }

    @Override
    public SWMRNibbleArray[] pulsar$getBlockNibbles() {
        return this.pulsar$blockNibbles;
    }

    @Override
    public void pulsar$setBlockNibbles(final SWMRNibbleArray[] nibbles) {
        this.pulsar$blockNibbles = nibbles;
    }

    @Override
    public boolean[] pulsar$getBlockEmptinessMap() {
        return this.pulsar$blockEmptinessMap;
    }

    @Override
    public void pulsar$setBlockEmptinessMap(final boolean[] emptinessMap) {
        this.pulsar$blockEmptinessMap = emptinessMap;
    }

    @Override
    public SWMRNibbleArray[] pulsar$getSkyNibbles() {
        return this.pulsar$skyNibbles;
    }

    @Override
    public void pulsar$setSkyNibbles(final SWMRNibbleArray[] nibbles) {
        this.pulsar$skyNibbles = nibbles;
    }

    @Override
    public boolean[] pulsar$getSkyEmptinessMap() {
        return this.pulsar$skyEmptinessMap;
    }

    @Override
    public void pulsar$setSkyEmptinessMap(final boolean[] emptinessMap) {
        this.pulsar$skyEmptinessMap = emptinessMap;
    }
}

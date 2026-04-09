package com.sumirelabs.pulsar.mixin;

// REID coexistence: see Pulsar plan §"REID 調査結果".
// REID also @Unique-injects fields onto Chunk (reid$biomeContainer); the
// pulsar$ prefix below avoids any name collision.

import com.sumirelabs.pulsar.api.ExtendedChunk;
import com.sumirelabs.pulsar.light.ChunkLightHelper;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.light.engine.PulsarEngine;
import com.sumirelabs.pulsar.util.WorldUtil;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Chunk.class)
@SuppressWarnings("deprecation")
public abstract class MixinChunk implements PulsarChunk, ExtendedChunk {

    @Shadow
    @Final
    private World world;

    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    @Shadow
    public boolean isLightPopulated;

    @Shadow
    public int[] heightMap;

    @Shadow
    public int[] precipitationHeightMap;

    @Shadow
    public int heightMapMinimum;

    @Shadow
    public abstract ExtendedBlockStorage[] getBlockStorageArray();

    @Shadow
    public abstract int getTopFilledSegment();

    @Shadow
    public abstract IBlockState getBlockState(int x, int y, int z);

    @Shadow
    public abstract void markDirty();

    @Unique
    private SWMRNibbleArray[] pulsar$blockNibbles;

    @Unique
    private SWMRNibbleArray[] pulsar$skyNibbles;

    @Unique
    private boolean[] pulsar$blockEmptinessMap;

    @Unique
    private boolean[] pulsar$skyEmptinessMap;

    @Unique
    private volatile boolean pulsar$lightReady;

    // ============================== Init ==============================

    @Inject(method = "<init>(Lnet/minecraft/world/World;II)V", at = @At("RETURN"), require = 0)
    private void pulsar$initFields(final World world, final int x, final int z, final CallbackInfo ci) {
        final int totalLightSections = WorldUtil.getTotalLightSections();
        this.pulsar$blockNibbles = new SWMRNibbleArray[totalLightSections];
        this.pulsar$skyNibbles = new SWMRNibbleArray[totalLightSections];
        for (int i = 0; i < totalLightSections; ++i) {
            this.pulsar$blockNibbles[i] = new SWMRNibbleArray(null, true);
            this.pulsar$skyNibbles[i] = new SWMRNibbleArray(null, true);
        }
    }

    // ============================== Lifecycle ==============================

    /**
     * Server-side: import vanilla nibbles into our SWMR mirrors, register the
     * chunk with the world's light manager and queue the initial BFS pass.
     *
     * <p>Mirrors {@code MixinChunk.supernova$onChunkLoad} from SuperNova
     * (1.7.10), simplified for scalar mode (no ChunkAPI / RGB persistence).
     */
    @Inject(method = "onLoad", at = @At("HEAD"), require = 0)
    private void pulsar$onLoad(final CallbackInfo ci) {
        final Chunk self = (Chunk) (Object) this;

        // Import vanilla nibbles into our SWMR mirrors so the engine has a
        // baseline to start from. For freshly generated chunks the vanilla
        // skyLight nibble is filled by pulsar$generateSkylightMap below; for
        // disk-loaded chunks it comes from the NBT.
        ChunkLightHelper.importVanillaBlock(this.pulsar$blockNibbles, this.getBlockStorageArray());
        if (this.world.provider.hasSkyLight()) {
            ChunkLightHelper.importVanillaSky(this.pulsar$skyNibbles, this.getBlockStorageArray(), false);
        }

        // Always mark light-populated so PlayerChunkMapEntry won't refuse to
        // ship the chunk to clients while Pulsar's BFS catches up. This is
        // the eager strategy (Hodgepodge MixinChunk_SendWithoutPopulation
        // equivalent in 1.7.10).
        this.isLightPopulated = true;

        final WorldLightManager mgr = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (mgr == null) return;

        mgr.registerChunk(self);

        if (this.world.isRemote) {
            // Client-side onLoad fires before fillChunk has filled the section
            // arrays. The actual import + queue happens in pulsar$onRead below.
            return;
        }

        // Server-side: queue the initial BFS so block emitters and full
        // sky-light propagation are computed asynchronously. completeInitialLighting
        // will set lightReady = true once both engines finish.
        final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(self);
        mgr.queueChunkLight(this.x, this.z, self, emptySections);
        mgr.scheduleUpdate();
    }

    @Inject(method = "onUnload", at = @At("HEAD"), require = 0)
    private void pulsar$onUnload(final CallbackInfo ci) {
        final WorldLightManager mgr = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (mgr != null) {
            mgr.removeChunkFromQueues(this.x, this.z);
            if (!this.world.isRemote) {
                mgr.awaitPendingWork(this.x, this.z);
            }
            mgr.unregisterChunk(this.x, this.z);
        }
    }

    /**
     * Client-side hook: a chunk packet has just been deserialised. Re-import
     * the vanilla nibbles (the server's view, possibly partial) and queue an
     * initial BFS so the client engine catches up to the post-server state.
     *
     * <p>Mirrors {@code MixinChunk.supernova$onFillChunk} from SuperNova
     * (1.7.10). 1.12.2 vanilla renamed {@code fillChunk} to
     * {@link Chunk#read(PacketBuffer, int, boolean)}.
     */
    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private void pulsar$onRead(final PacketBuffer buf, final int availableSections,
                               final boolean groundUpContinuous, final CallbackInfo ci) {
        if (!this.world.isRemote) return;
        final Chunk self = (Chunk) (Object) this;

        ChunkLightHelper.importVanillaBlock(this.pulsar$blockNibbles, this.getBlockStorageArray());
        if (this.world.provider.hasSkyLight()) {
            ChunkLightHelper.importVanillaSky(this.pulsar$skyNibbles, this.getBlockStorageArray(), false);
        }

        final WorldLightManager mgr = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (mgr == null) return;

        mgr.registerChunk(self);

        final Boolean[] emptySections = PulsarEngine.getEmptySectionsForChunk(self);
        mgr.queueChunkLight(this.x, this.z, self, emptySections);
        mgr.scheduleUpdate();

        // Sync our (just-imported) sky nibbles back to vanilla so renderers
        // see something reasonable before the BFS finishes.
        ChunkLightHelper.syncSkyToVanilla(this.pulsar$skyNibbles, this.getBlockStorageArray());
    }

    // ============================== Vanilla light bypasses ==============================

    /**
     * Replaces vanilla {@code Chunk.generateSkylightMap()} with a manual
     * heightmap rebuild + scalar sky-column walk that fills the vanilla
     * {@code skyLight} {@link NibbleArray}s with reasonable initial values.
     *
     * <p>This is a direct port of SuperNova's {@code supernova$generateSkylightMap}
     * (which itself emulates the vanilla logic but defers actual BFS
     * propagation to the async engine). Without this, freshly generated
     * chunks would be sent to clients with all-zero sky light → completely
     * dark world until BFS catches up.
     */
    @Inject(method = "generateSkylightMap", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$generateSkylightMap(final CallbackInfo ci) {
        final int topSegment = this.getTopFilledSegment();
        this.heightMapMinimum = Integer.MAX_VALUE;

        for (int lx = 0; lx < 16; ++lx) {
            for (int lz = 0; lz < 16; ++lz) {
                this.precipitationHeightMap[lx + (lz << 4)] = -999;

                for (int y = topSegment + 16 - 1; y > 0; --y) {
                    if (pulsar$opacityAt(lx, y - 1, lz) != 0) {
                        this.heightMap[lz << 4 | lx] = y;
                        if (y < this.heightMapMinimum) {
                            this.heightMapMinimum = y;
                        }
                        break;
                    }
                }
            }
        }

        if (this.world.provider.hasSkyLight()) {
            pulsar$fillVanillaSkyColumn(topSegment);
        }

        this.isLightPopulated = true;
        this.markDirty();
        ci.cancel();
    }

    /**
     * Vanilla-style scalar opacity lookup that does not require the chunk to
     * be fully loaded. Mirrors {@code Chunk.getBlockLightOpacity(int,int,int)}
     * but uses the local block-state lookup directly to keep the call cheap.
     */
    @Unique
    private int pulsar$opacityAt(final int x, final int y, final int z) {
        return this.getBlockState(x, y, z).getLightOpacity();
    }

    @Unique
    private void pulsar$fillVanillaSkyColumn(final int topSegment) {
        for (int lx = 0; lx < 16; ++lx) {
            for (int lz = 0; lz < 16; ++lz) {
                pulsar$fillVanillaSkyForColumn(lx, lz, topSegment);
            }
        }
    }

    /**
     * Vanilla-style sky-light column walk for a single (x, z): start at the
     * top with sky=15 and attenuate top-down by block opacity. Below the
     * first opaque block, transparent blocks attenuate by 1 (matches vanilla
     * behaviour). Mirrors SuperNova's {@code supernova$fillVanillaSkyForColumn}.
     */
    @Unique
    private void pulsar$fillVanillaSkyForColumn(final int x, final int z, final int topSegment) {
        int skyLevel = 15;
        for (int y = topSegment + 15; y >= 0; --y) {
            final ExtendedBlockStorage section = this.getBlockStorageArray()[y >> 4];
            if (section == null) {
                if (skyLevel != 15) {
                    skyLevel = Math.max(0, skyLevel - 1);
                }
                continue;
            }
            int opacity = section.get(x, y & 15, z).getLightOpacity();
            if (opacity == 0 && skyLevel != 15) {
                opacity = 1;
            }
            skyLevel = Math.max(0, skyLevel - opacity);
            final NibbleArray skyArr = section.getSkyLight();
            if (skyArr != null) {
                skyArr.set(x, y & 15, z, skyLevel);
            }
            if (skyLevel <= 0) break;
        }
    }

    /**
     * Bypass vanilla {@code recheckGaps}. Pulsar's edge-check phase covers
     * the same scenarios.
     */
    @Inject(method = "recheckGaps", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$recheckGaps(final boolean isClient, final CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * Bypass vanilla {@code enqueueRelightChecks}. Pulsar's WorldLightManager
     * schedules the equivalent work asynchronously.
     */
    @Inject(method = "enqueueRelightChecks", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$enqueueRelightChecks(final CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * Bypass vanilla {@code checkLight()}. Vanilla {@code Chunk.checkLight()}
     * is the canonical site that flips {@code isLightPopulated = true}; we
     * keep that flag true ourselves so that
     * {@code PlayerChunkMapEntry.canSendToPlayers()} doesn't refuse to ship
     * the chunk to the client.
     */
    @Inject(method = "checkLight()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$checkLight(final CallbackInfo ci) {
        this.isLightPopulated = true;
        ci.cancel();
    }

    /**
     * Always report the chunk as populated to {@code PlayerChunkMapEntry} so
     * that Pulsar's eager chunk-send strategy works regardless of which
     * vanilla flag the upstream gate happens to check.
     *
     * <p>Mirrors Hodgepodge's {@code MixinChunk_SendWithoutPopulation} from
     * 1.7.10 (which overrode {@code Chunk.func_150802_k()} for the same
     * reason). The 1.12.2 equivalent is {@link Chunk#isPopulated()}.
     *
     * <p>Gated by {@link com.sumirelabs.pulsar.config.PulsarConfig#sendChunksWithoutLight}
     * so users who prefer the conservative "wait until BFS is done" model
     * can flip the switch.
     */
    @Inject(method = "isPopulated", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$alwaysPopulated(final CallbackInfoReturnable<Boolean> cir) {
        if (com.sumirelabs.pulsar.config.PulsarConfig.sendChunksWithoutLight) {
            cir.setReturnValue(true);
        }
    }

    // ============================== PulsarChunk implementation ==============================

    @Override
    public boolean pulsar$isLightReady() {
        return this.pulsar$lightReady;
    }

    @Override
    public void pulsar$setLightReady(final boolean ready) {
        this.pulsar$lightReady = ready;
    }

    @Override
    public void pulsar$syncLightToVanilla() {
        ChunkLightHelper.syncBlockToVanilla(this.pulsar$blockNibbles, this.getBlockStorageArray());
        if (this.world.provider.hasSkyLight()) {
            ChunkLightHelper.syncSkyToVanilla(this.pulsar$skyNibbles, this.getBlockStorageArray());
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

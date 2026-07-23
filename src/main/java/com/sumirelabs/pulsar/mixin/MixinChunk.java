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
    public boolean isTerrainPopulated;

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

    @Unique
    private volatile boolean pulsar$savedLightValid;

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

        // Restored-from-NBT chunks keep their deserialised SWMR nibbles: they
        // are richer than what a vanilla import can reconstruct (they carry
        // the NULL/UNINIT/INIT states and the -1/16 boundary sections).
        if (!this.pulsar$savedLightValid) {
            // Import vanilla nibbles into our SWMR mirrors so the engine has a
            // baseline to start from. For freshly generated chunks the vanilla
            // skyLight nibble is filled by pulsar$generateSkylightMap below; for
            // disk-loaded chunks it comes from the NBT.
            ChunkLightHelper.importVanillaBlock(this.pulsar$blockNibbles, this.getBlockStorageArray());
            if (this.world.provider.hasSkyLight()) {
                ChunkLightHelper.importVanillaSky(this.pulsar$skyNibbles, this.getBlockStorageArray(), false);
            }
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

        if (this.pulsar$savedLightValid) {
            // Valid persisted light (Starlight-style): no relight needed. The
            // vanilla nibbles already carry the same values (they were synced
            // before the save). Only run the cheap nibble/emptiness-map init
            // so future BFS passes over this chunk have their caches ready.
            this.pulsar$lightReady = true;
            mgr.queueChunkLoadInit(this.x, this.z, self, PulsarEngine.getEmptySectionsForChunk(self));
            mgr.scheduleUpdate();
            return;
        }

        // Fresh or invalid-save chunk: queue the initial BFS so block emitters
        // and full sky-light propagation are computed asynchronously.
        // completeInitialLighting will set lightReady = true once both engines
        // finish.
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
     * Client-side hook: a chunk packet has just been deserialised. Import the
     * server's nibbles into our SWMR mirrors and trust them — the server has
     * already run (or restored) the full BFS, so re-lighting here would only
     * duplicate the work and double the render invalidations. Only the cheap
     * nibble/emptiness-map init is queued so later client-side BFS passes
     * (block changes) have their caches ready.
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

        this.pulsar$lightReady = true;
        mgr.queueChunkLoadInit(this.x, this.z, self, PulsarEngine.getEmptySectionsForChunk(self));
        mgr.scheduleUpdate();
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
     * is the canonical site that flips BOTH {@code isTerrainPopulated} and
     * {@code isLightPopulated} to {@code true} — {@code Chunk.populate}
     * relies on it for the terrain flag, so the replacement must set both or
     * freshly generated chunks are never considered populated (and never
     * saved as such).
     */
    @Inject(method = "checkLight()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$checkLight(final CallbackInfo ci) {
        this.isTerrainPopulated = true;
        this.isLightPopulated = true;
        ci.cancel();
    }

    /**
     * Gate chunk sending on Pulsar's BFS completion.
     *
     * <p>Default ({@code sendChunksWithoutLight = false}): the chunk is
     * populated exactly when {@code pulsar$lightReady}, so
     * {@code PlayerChunkMapEntry} retries each tick and clients only ever
     * receive fully-lit chunks. Chunks with valid persisted light are ready
     * the moment they load, so the delay only applies to freshly generated
     * (or legacy-world relit) chunks. The vanilla flags are deliberately NOT
     * consulted: worlds saved by earlier Pulsar builds carry
     * {@code TerrainPopulated=false} on disk (the cancelled
     * {@code checkLight()} never set it), and falling through to vanilla
     * would leave those chunks permanently unsent.
     *
     * <p>With {@code sendChunksWithoutLight = true}: always report populated —
     * the eager strategy inherited from Hodgepodge's
     * {@code MixinChunk_SendWithoutPopulation} (1.7.10). Fresh chunks may
     * briefly show pre-BFS light on clients.
     */
    @Inject(method = "isPopulated", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$gatePopulatedOnLight(final CallbackInfoReturnable<Boolean> cir) {
        if (com.sumirelabs.pulsar.config.PulsarConfig.features.sendChunksWithoutLight) {
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(this.pulsar$lightReady);
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
    public void pulsar$setSavedLightValid(final boolean valid) {
        this.pulsar$savedLightValid = valid;
    }

    @Override
    public boolean pulsar$hasSavedLightValid() {
        return this.pulsar$savedLightValid;
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

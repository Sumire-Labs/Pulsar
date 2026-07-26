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
import net.minecraft.util.math.BlockPos;
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
            // Valid persisted light (Starlight-style): no relight needed.
            // Re-sync SWMR → vanilla anyway (SuperNova does the same): if
            // anything wrote the vanilla nibbles behind the engine's back
            // before the save, the divergence would otherwise be permanent.
            this.pulsar$syncLightToVanilla();
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
            // Wait BEFORE removing the completion entry — the other order
            // made awaitPendingWork a guaranteed no-op and let workers keep
            // writing a chunk the main thread was saving.
            if (!this.world.isRemote) {
                mgr.awaitPendingWork(this.x, this.z);
            }
            mgr.removeChunkFromQueues(this.x, this.z);
            mgr.unregisterChunk(this.x, this.z);
        }
    }

    /**
     * Client-side hook: a chunk packet has just been deserialised. Wrap the
     * server's nibbles (shared storage, no copy) and trust them — the server
     * has already run (or restored) the full BFS. All client light writes
     * happen on the main thread, so the SWMR arrays can share the vanilla
     * byte[]s directly and publishes land straight in what the renderer
     * reads. Only the cheap nibble/emptiness-map init is queued so later
     * client-side BFS passes (block changes) have their caches ready.
     */
    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private void pulsar$onRead(final PacketBuffer buf, final int availableSections,
                               final boolean groundUpContinuous, final CallbackInfo ci) {
        if (!this.world.isRemote) return;
        final Chunk self = (Chunk) (Object) this;

        ChunkLightHelper.wrapVanillaBlock(this.pulsar$blockNibbles, this.getBlockStorageArray());
        if (this.world.provider.hasSkyLight()) {
            ChunkLightHelper.wrapVanillaSky(this.pulsar$skyNibbles, this.getBlockStorageArray());
        }

        final WorldLightManager mgr = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (mgr == null) return;

        mgr.registerChunk(self);

        this.pulsar$lightReady = true;
        mgr.queueChunkLoadInit(this.x, this.z, self, PulsarEngine.getEmptySectionsForChunk(self));
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

                // Vanilla starts this walk at topSegment + 16 — starting one
                // lower skipped the top row of the topmost filled section.
                for (int y = topSegment + 16; y > 0; --y) {
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

        // The naive column fill is only a pre-BFS baseline for freshly
        // generated chunks. setBlockState re-invokes generateSkylightMap
        // whenever a block lands in a new empty section — repainting a lit
        // chunk with naive values here would stomp the engine's results
        // (SuperNova suppressed that call site; gating on lightReady is the
        // injection-free equivalent).
        if (this.world.provider.hasSkyLight() && !this.pulsar$lightReady) {
            pulsar$fillVanillaSkyColumn(topSegment);
        }

        this.isLightPopulated = true;
        this.markDirty();
        ci.cancel();
    }

    /**
     * Heightmap-only replacement for vanilla {@code relightBlock} (SuperNova
     * does the same): vanilla's version writes 15/0 spans plus an
     * attenuation walk straight into the sky nibbles — the engine's copyback
     * target on the server and its live shared storage on the thin client.
     * The BFS handles the actual relight; only the heightmap bookkeeping is
     * kept.
     */
    @Inject(method = "relightBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$relightBlock(final int x, final int y, final int z, final CallbackInfo ci) {
        ci.cancel();
        final int old = this.heightMap[z << 4 | x] & 255;
        int newHeight = Math.max(old, y);
        while (newHeight > 0 && pulsar$opacityAt(x, newHeight - 1, z) == 0) {
            --newHeight;
        }
        if (newHeight == old) {
            return;
        }
        this.world.markBlocksDirtyVertical(x + (this.x << 4), z + (this.z << 4), newHeight, old);
        this.heightMap[z << 4 | x] = newHeight;
        if (newHeight < this.heightMapMinimum) {
            this.heightMapMinimum = newHeight;
        } else {
            int min = Integer.MAX_VALUE;
            for (int i = 0; i < 256; ++i) {
                if (this.heightMap[i] < min) {
                    min = this.heightMap[i];
                }
            }
            this.heightMapMinimum = min;
        }
        this.markDirty();
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

    // NOTE: the light gate for chunk sending lives in
    // MixinPlayerChunkMapEntry.sendToPlayers, NOT in isPopulated().
    // isPopulated() also gates World.setBlockState's notifyBlockUpdate —
    // overriding it suppressed block-change packets and render marks for
    // any chunk whose light (or neighbours' light) wasn't ready yet.

    /**
     * Client-side: a block placed into a previously empty section makes
     * vanilla create a fresh {@link ExtendedBlockStorage} with brand-new
     * nibble arrays. The thin client's SWMR wrappers must re-wrap that
     * section (shared storage) and both sides must tell the engine the
     * section is no longer empty, or the emptiness maps go stale.
     */
    @Unique
    private boolean pulsar$sectionWasEmpty;

    @Inject(method = "setBlockState", at = @At("HEAD"), require = 0)
    private void pulsar$preSetBlockState(final BlockPos pos, final IBlockState state,
                                         final CallbackInfoReturnable<IBlockState> cir) {
        final ExtendedBlockStorage[] storage = this.getBlockStorageArray();
        final int sy = pos.getY() >> 4;
        this.pulsar$sectionWasEmpty = sy >= 0 && sy < storage.length
                && storage[sy] == Chunk.NULL_BLOCK_STORAGE;
    }

    @Inject(method = "setBlockState", at = @At("RETURN"), require = 0)
    private void pulsar$postSetBlockState(final BlockPos pos, final IBlockState state,
                                          final CallbackInfoReturnable<IBlockState> cir) {
        if (!this.pulsar$sectionWasEmpty || cir.getReturnValue() == null) {
            return;
        }
        final int sy = pos.getY() >> 4;
        final ExtendedBlockStorage section = this.getBlockStorageArray()[sy];
        if (section == Chunk.NULL_BLOCK_STORAGE) {
            return;
        }
        this.pulsar$sectionWasEmpty = false;
        final int idx = sy - WorldUtil.getMinLightSection();
        // The fresh EBS has all-zero nibbles, and after lightReady nothing
        // re-publishes the engine's already-visible light into them (naive
        // fill is gated on !lightReady; onNibbleVisible fires on dirty
        // nibbles only, and no-op writes are skipped). Fill from the SWMR
        // state NOW or the section is sent/rendered black.
        ChunkLightHelper.fillVanillaFromEngine(this.pulsar$skyNibbles, this.pulsar$blockNibbles,
                section, sy, this.world.provider.hasSkyLight());
        if (this.world.isRemote) {
            final NibbleArray blockNib = section.getBlockLight();
            if (blockNib != null) {
                this.pulsar$blockNibbles[idx] = new SWMRNibbleArray(blockNib.getData());
            }
            final NibbleArray skyNib = section.getSkyLight();
            if (skyNib != null) {
                this.pulsar$skyNibbles[idx] = new SWMRNibbleArray(skyNib.getData());
            }
        }
        final WorldLightManager mgr = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (mgr != null) {
            mgr.queueSectionChange(this.x, sy, this.z, false);
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

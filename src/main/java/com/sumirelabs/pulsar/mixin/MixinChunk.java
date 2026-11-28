/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.mixin;

// REID coexistence: REID also @Unique-injects fields onto Chunk. The pulsar$
// prefix below avoids collisions with its biome-container state.

import java.util.Arrays;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
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

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

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
    @Final public int x;
    @Shadow
    @Final public int z;
    @Shadow public boolean isLightPopulated;
    @Shadow
    @Final private World world;
    @Unique private volatile SWMRNibbleArray[] pulsar$blockNibbles;
    @Unique private volatile SWMRNibbleArray[] pulsar$skyNibbles;
    @Unique private boolean[] pulsar$blockEmptinessMap;
    @Unique private boolean[] pulsar$skyEmptinessMap;
    @Unique private volatile boolean pulsar$lightReady;
    @Unique private volatile boolean pulsar$lightUsable;
    @Unique private volatile boolean pulsar$savedLightValid;
    @Unique private volatile boolean pulsar$lightDirty;

    public void pulsar$markLightDirty() {
        this.pulsar$lightDirty = true;
    }
    public void pulsar$clearLightDirty() {
        this.pulsar$lightDirty = false;
    }

    @Inject(method = "needsSaving", at = @At("RETURN"), cancellable = true)
    private void pulsar$savePublishedLight(boolean all, CallbackInfoReturnable<Boolean> cir) {
        if (!this.world.isRemote && this.pulsar$lightDirty)
            cir.setReturnValue(true);
    }
    @Unique private WorldHeightContext pulsar$heightContext;

    @Shadow
    public abstract ExtendedBlockStorage[] getBlockStorageArray();

    @Inject(method = "addTileEntity(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/tileentity/TileEntity;)V", at = @At("RETURN"))
    private void pulsar$trackTileAdded(final BlockPos pos, final net.minecraft.tileentity.TileEntity tile,
        final CallbackInfo ci) {
        this.pulsar$requestTileSample(pos);
    }

    @Inject(method = "removeTileEntity", at = @At("RETURN"))
    private void pulsar$trackTileRemoved(final BlockPos pos, final CallbackInfo ci) {
        this.pulsar$requestTileSample(pos);
    }

    @Unique
    private void pulsar$requestTileSample(final BlockPos pos) {
        if (this.world.isRemote)
            return;
        final WorldLightManager manager = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (manager != null) {
            manager.contextualLight().tileEntityChanged(pos);
            if (manager.rgbContextualLight() != null)
                manager.rgbContextualLight().request(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    @Unique
    private WorldHeightContext pulsar$getHeightContext() {
        if (this.pulsar$heightContext == null) {
            this.pulsar$heightContext = WorldUtil.getHeightContext(this.world);
        }
        return this.pulsar$heightContext;
    }

    @Inject(method = "read", at = @At("HEAD"), require = 0)
    private void pulsar$capturePacketBlockLight(final PacketBuffer buf, final int availableSections,
        final boolean full, final CallbackInfo ci,
        @Share("previousBlockLight") final LocalRef<byte[][]> previous) {
        if (!this.world.isRemote || !this.pulsar$isLightUsable() || full || availableSections == 0)
            return;
        final WorldLightManager manager = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (manager == null || manager.usesAuthoritativeClientLight(false))
            return;
        final WorldHeightContext height = this.pulsar$getHeightContext();
        final byte[][] snapshot = new byte[this.pulsar$blockNibbles.length][];
        for (int i = 0; i < snapshot.length; ++i) {
            if (!pulsar$packetIncludes(height, i, availableSections))
                continue;
            final SWMRNibbleArray nibble = this.pulsar$blockNibbles[i];
            final byte[] bytes = nibble == null ? null : nibble.getVisibleData();
            if (bytes != null)
                snapshot[i] = bytes.clone();
        }
        previous.set(snapshot);
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
        // Disk I/O can finish before a palette reload but register the chunk afterward.
        if (!this.world.isRemote && com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled()
            && self instanceof com.sumirelabs.pulsar.rgb.RgbChunk rgb) {
            if (this.pulsar$savedLightValid
                && rgb.pulsar$getSavedPaletteGeneration() != com.sumirelabs.pulsar.rgb.RgbPaletteOverrides
                    .current().generation)
                this.pulsar$savedLightValid = false;
            if (!this.pulsar$savedLightValid) {
                rgb.pulsar$setRgbStorage(null);
                rgb.pulsar$setRgbSkyStorage(null);
                rgb.pulsar$setRgbSnapshot(null);
                rgb.pulsar$setRgbSkySnapshot(null);
            }
        }

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
    }

    @Inject(method = "onUnload", at = @At("HEAD"), require = 0)
    private void pulsar$onUnload(final CallbackInfo ci) {
        final WorldLightManager manager = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (manager == null) {
            return;
        }

        final Chunk self = (Chunk) (Object) this;
        // Never hold the publication monitor while waiting for worker futures:
        // their final visible-light publication uses the same monitor.
        final boolean workFinished = this.world.isRemote
            || manager.awaitPendingWork(this.x, this.z);
        synchronized (self) {
            if (!this.world.isRemote
                && (!workFinished || manager.hasPendingLightWorkNear(this.x, this.z))) {
                // onUnload runs before chunk serialization. Keep this false
                // only when light work may still be in flight; a clean unload
                // must retain valid persisted light through ChunkDataEvent.Save.
                this.pulsar$lightReady = false;
                manager.recordUnloadLightInvalidation();
            }
            // Prevent any newly prepared engine cache from treating this
            // instance as a usable neighbour while it is being detached.
            this.pulsar$lightUsable = false;
            manager.removeChunkFromQueues(this.x, this.z);
            manager.unregisterChunk(this.x, this.z);
        }
    }

    /**
     * Wraps packet nibbles without copying on the thin client and queues cache initialization.
     */
    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private void pulsar$onRead(final PacketBuffer buf, final int availableSections,
        final boolean groundUpContinuous, final CallbackInfo ci,
        @Share("previousBlockLight") final LocalRef<byte[][]> previous) {
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
        final Boolean[] empty = PulsarEngine.getEmptySectionsForChunk(self);
        final byte[][] oldLight = previous.get();
        if (oldLight == null)
            manager.queueChunkLoadInit(this.x, this.z, self, empty);
        else
            manager.queueClientSectionPacket(self, empty,
                this.pulsar$packetBlockLightChanges(oldLight, availableSections));
    }

    @Unique
    private Int2IntOpenHashMap pulsar$packetBlockLightChanges(final byte[][] previous,
        final int mask) {
        final WorldHeightContext height = this.pulsar$getHeightContext();
        Int2IntOpenHashMap changes = null;
        for (int i = 0; i < previous.length; ++i) {
            if (!pulsar$packetIncludes(height, i, mask))
                continue;
            final SWMRNibbleArray nibble = this.pulsar$blockNibbles[i];
            final byte[] current = nibble == null ? null : nibble.getVisibleData();
            if (Arrays.equals(previous[i], current))
                continue;
            final int y = ((height.getMinLightSection() + i) << 4) << 8;
            for (int cell = 0; cell < 4096; ++cell) {
                int x = cell & 15, z = (cell >> 4) & 15, localY = cell >> 8;
                if (x != 0 && x != 15 && z != 0 && z != 15 && localY != 0 && localY != 15)
                    continue;
                int shift = (cell & 1) << 2;
                int oldLevel = previous[i] == null ? 0 : (previous[i][cell >> 1] >>> shift) & 15;
                int newLevel = current == null ? 0 : (current[cell >> 1] >>> shift) & 15;
                if (oldLevel == newLevel)
                    continue;
                if (changes == null)
                    changes = new Int2IntOpenHashMap();
                changes.put(y | cell, oldLevel << 4 | newLevel);
            }
        }
        return changes;
    }

    @Unique
    private static boolean pulsar$packetIncludes(final WorldHeightContext height, final int index, final int mask) {
        int slot = height.getStorageIndex(height.getMinLightSection() + index);
        return slot >= 0 && slot < Integer.SIZE && (mask & (1 << slot)) != 0;
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
        this.pulsar$syncLightToVanilla(-1);
    }

    @Override
    public void pulsar$syncLightToVanilla(final int sectionMask) {
        ChunkLightHelper.syncBlockToVanilla(
            this.pulsar$getHeightContext(), this.pulsar$blockNibbles, this.getBlockStorageArray(), sectionMask);
        if (this.world.provider.hasSkyLight()) {
            ChunkLightHelper.syncSkyToVanilla(
                this.pulsar$getHeightContext(), this.pulsar$skyNibbles, this.getBlockStorageArray(), sectionMask);
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

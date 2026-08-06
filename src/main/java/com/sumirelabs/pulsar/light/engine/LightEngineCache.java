package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.api.ExtendedWorld;
import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.light.LightStats;
import com.sumirelabs.pulsar.light.RenderBounds;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * Shared 5x5 chunk/section cache used by scalar light engines.
 *
 * <p>One engine instance processes one task at a time. Cache coordinates are
 * recentered for each task, then all references are cleared before the engine
 * returns to its worker pool.
 */
abstract class LightEngineCache {

    // index = x + (z * 5) + (y * 25), with x/z relative to the center chunk
    protected final ExtendedBlockStorage[] sectionCache;
    protected final SWMRNibbleArray[] nibbleCache;
    protected final boolean[] notifyUpdateCache;
    protected final long[] notifyBoundsCache;
    protected final Chunk[] chunkCache = new Chunk[5 * 5];
    protected final boolean[][] emptinessMapCache = new boolean[5 * 5][];
    protected final Object[] fluidCapCache = new Object[5 * 5];
    protected final boolean isClientSide;
    protected final World world;
    protected final WorldHeightContext heightContext;
    protected final int minLightSection;
    protected final int maxLightSection;
    protected final int minSection;
    protected final int maxSection;
    private final BlockPos.MutableBlockPos contextualLightPos = new BlockPos.MutableBlockPos();
    protected int encodeOffsetX;
    protected int encodeOffsetY;
    protected int encodeOffsetZ;
    protected int coordinateOffset;
    protected int chunkOffsetX;
    protected int chunkOffsetY;
    protected int chunkOffsetZ;
    protected int chunkIndexOffset;
    protected int chunkSectionIndexOffset;
    protected LightStats stats;

    LightEngineCache(final World world, final WorldHeightContext heightContext) {
        this.isClientSide = world.isRemote;
        this.world = world;
        this.heightContext = heightContext;
        this.minLightSection = heightContext.getMinLightSection();
        this.maxLightSection = heightContext.getMaxLightSection();
        this.minSection = heightContext.getMinSection();
        this.maxSection = heightContext.getMaxSection();

        final int totalLightSections = (this.maxLightSection - this.minLightSection + 1) + 2;
        final int cacheSize = 5 * 5 * totalLightSections;
        this.sectionCache = new ExtendedBlockStorage[cacheSize];
        this.nibbleCache = new SWMRNibbleArray[cacheSize];
        this.notifyUpdateCache = new boolean[cacheSize];
        this.notifyBoundsCache = new long[cacheSize];
    }

    public void setStats(final LightStats stats) {
        this.stats = stats;
    }

    protected final void setupCaches(final int centerX, final int centerY, final int centerZ,
                                     final boolean relaxed, final boolean tryToLoadChunksFor2Radius) {
        // Reset task state at entry. Overflow must remain observable after the
        // operation returns, and stale queue lengths must not cross tasks.
        this.resetTaskState();

        final int centerChunkX = centerX >> 4;
        final int centerChunkZ = centerZ >> 4;
        this.setupEncodeOffset(centerChunkX * 16 + 7, centerY, centerChunkZ * 16 + 7);

        final int radius = tryToLoadChunksFor2Radius ? 2 : 1;
        for (int deltaZ = -radius; deltaZ <= radius; ++deltaZ) {
            for (int deltaX = -radius; deltaX <= radius; ++deltaX) {
                final int chunkX = centerChunkX + deltaX;
                final int chunkZ = centerChunkZ + deltaZ;
                final boolean isTwoRadius = Math.max(Math.abs(deltaX), Math.abs(deltaZ)) == 2;
                final Chunk chunk = ((ExtendedWorld) this.world)
                        .pulsar$getAnyChunkImmediately(chunkX, chunkZ);

                if (chunk == null) {
                    if (relaxed | isTwoRadius) {
                        continue;
                    }
                    throw new IllegalArgumentException(
                            "Trying to propagate light update before 1 radius neighbours ready");
                }
                if (!this.canUseChunk(chunk)) {
                    continue;
                }

                this.setChunkInCache(chunkX, chunkZ, chunk);
                if (FluidLightBridge.LOADED) {
                    this.fluidCapCache[chunkX + 5 * chunkZ + this.chunkIndexOffset] =
                            FluidLightBridge.capabilityOf(chunk);
                }
                this.setEmptinessMapCache(chunkX, chunkZ, this.getEmptinessMap(chunk));
                if (!isTwoRadius) {
                    this.setBlocksForChunkInCache(chunkX, chunkZ, chunk.getBlockStorageArray());
                    this.setNibblesForChunkInCache(chunkX, chunkZ, this.getNibblesOnChunk(chunk));
                }
            }
        }
    }

    private void setupEncodeOffset(final int centerX, final int centerY, final int centerZ) {
        this.encodeOffsetX = 31 - centerX;
        this.encodeOffsetY = (-(this.minLightSection - 1) << 4);
        this.encodeOffsetZ = 31 - centerZ;
        this.coordinateOffset = this.encodeOffsetX + (this.encodeOffsetZ << 6) + (this.encodeOffsetY << 12);
        this.chunkOffsetX = 2 - (centerX >> 4);
        this.chunkOffsetY = -(this.minLightSection - 1);
        this.chunkOffsetZ = 2 - (centerZ >> 4);
        this.chunkIndexOffset = this.chunkOffsetX + (5 * this.chunkOffsetZ);
        this.chunkSectionIndexOffset = this.chunkIndexOffset + ((5 * 5) * this.chunkOffsetY);
    }

    protected final Chunk getChunkInCache(final int chunkX, final int chunkZ) {
        return this.chunkCache[chunkX + 5 * chunkZ + this.chunkIndexOffset];
    }

    protected final void setChunkInCache(final int chunkX, final int chunkZ, final Chunk chunk) {
        this.chunkCache[chunkX + 5 * chunkZ + this.chunkIndexOffset] = chunk;
    }

    /**
     * Resolves cached and context-sensitive block/fluid light properties.
     */
    protected final int lightInfoAt(final IBlockState state,
                                    final int worldX, final int worldY, final int worldZ) {
        int info = LightInfo.of(state);
        if (LightInfo.hasContextualValues(info)) {
            info = LightInfo.resolveContextual(
                    info, state, this.world, this.contextualLightPos, worldX, worldY, worldZ);
        }
        if (!FluidLightBridge.LOADED) {
            return info;
        }
        final Object capability =
                this.fluidCapCache[(worldX >> 4) + 5 * (worldZ >> 4) + this.chunkIndexOffset];
        return capability == null ? info : FluidLightBridge.merge(
                info, capability, worldX, worldY, worldZ, this.world, this.contextualLightPos);
    }

    protected final ExtendedBlockStorage getChunkSection(final int chunkX, final int chunkY,
                                                         final int chunkZ) {
        return this.sectionCache[
                chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
    }

    protected final void setChunkSectionInCache(final int chunkX, final int chunkY, final int chunkZ,
                                                final ExtendedBlockStorage section) {
        this.sectionCache[
                chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset] = section;
    }

    protected final void setBlocksForChunkInCache(final int chunkX, final int chunkZ,
                                                  final ExtendedBlockStorage[] sections) {
        for (int sectionY = this.minLightSection; sectionY <= this.maxLightSection; ++sectionY) {
            final int storageIndex = this.heightContext.getStorageIndex(sectionY);
            final ExtendedBlockStorage section = sections == null
                    || storageIndex < 0 || storageIndex >= sections.length
                    ? null : sections[storageIndex];
            this.sectionCache[chunkX + 5 * chunkZ + 5 * 5 * sectionY
                    + this.chunkSectionIndexOffset] = section;
        }
    }

    protected final SWMRNibbleArray getNibbleFromCache(final int chunkX, final int chunkY,
                                                       final int chunkZ) {
        return this.nibbleCache[
                chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
    }

    protected final void setNibbleInCache(final int chunkX, final int chunkY, final int chunkZ,
                                          final SWMRNibbleArray nibble) {
        this.nibbleCache[
                chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset] = nibble;
    }

    protected final void setNibblesForChunkInCache(final int chunkX, final int chunkZ,
                                                   final SWMRNibbleArray[] nibbles) {
        for (int sectionY = this.minLightSection; sectionY <= this.maxLightSection; ++sectionY) {
            this.setNibbleInCache(chunkX, sectionY, chunkZ,
                    nibbles == null ? null : nibbles[sectionY - this.minLightSection]);
        }
    }

    protected final boolean[] getEmptinessMap(final int chunkX, final int chunkZ) {
        return this.emptinessMapCache[chunkX + 5 * chunkZ + this.chunkIndexOffset];
    }

    protected final void setEmptinessMapCache(final int chunkX, final int chunkZ,
                                              final boolean[] emptinessMap) {
        this.emptinessMapCache[chunkX + 5 * chunkZ + this.chunkIndexOffset] = emptinessMap;
    }

    protected final void updateVisible() {
        for (int index = 0, max = this.nibbleCache.length; index < max; ++index) {
            final SWMRNibbleArray nibble = this.nibbleCache[index];
            final boolean notify = this.notifyUpdateCache[index];
            if (!notify && (nibble == null || !nibble.isDirty())) {
                continue;
            }
            if (nibble != null) {
                nibble.updateVisible();
            }
            this.onNibbleVisible(index, nibble);
            if (notify && this.isClientSide) {
                this.markRenderUpdate(index, this.notifyBoundsCache[index]);
            }
        }
    }

    private void markRenderUpdate(final int cacheIndex, final long bounds) {
        final int localChunkX = cacheIndex % 5;
        final int localChunkZ = (cacheIndex / 5) % 5;
        final int localSectionY = cacheIndex / 25;
        final int sectionX = (localChunkX - this.chunkOffsetX) << 4;
        final int sectionY = (localSectionY - this.chunkOffsetY) << 4;
        final int sectionZ = (localChunkZ - this.chunkOffsetZ) << 4;
        this.world.markBlockRangeForRenderUpdate(
                sectionX + RenderBounds.minX(bounds),
                sectionY + RenderBounds.minY(bounds),
                sectionZ + RenderBounds.minZ(bounds),
                sectionX + RenderBounds.maxX(bounds),
                sectionY + RenderBounds.maxY(bounds),
                sectionZ + RenderBounds.maxZ(bounds));
        if (LightStats.enabled) {
            LightStats.engineRenderMarks++;
        }
    }

    protected final void destroyCaches() {
        Arrays.fill(this.sectionCache, null);
        Arrays.fill(this.nibbleCache, null);
        Arrays.fill(this.chunkCache, null);
        Arrays.fill(this.emptinessMapCache, null);
        Arrays.fill(this.fluidCapCache, null);
        if (this.isClientSide) {
            Arrays.fill(this.notifyUpdateCache, false);
        }
    }

    protected final IBlockState getBlockState(final int worldX, final int worldY, final int worldZ) {
        final int sectionIndex = (worldX >> 4) + 5 * (worldZ >> 4)
                + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset;
        final ExtendedBlockStorage section = this.sectionCache[sectionIndex];
        return section == null ? Blocks.AIR.getDefaultState()
                : section.get(worldX & 15, worldY & 15, worldZ & 15);
    }

    protected final IBlockState getBlockStateFast(final int sectionIndex,
                                                  final int x, final int y, final int z) {
        final ExtendedBlockStorage section = this.sectionCache[sectionIndex];
        return section == null ? Blocks.AIR.getDefaultState() : section.get(x, y, z);
    }

    protected int getLightLevel(final int worldX, final int worldY, final int worldZ) {
        final SWMRNibbleArray nibble = this.nibbleCache[
                (worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4)
                        + this.chunkSectionIndexOffset];
        return nibble == null ? 0 : nibble.getUpdating(
                (worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8));
    }

    protected int getLightLevel(final int sectionIndex, final int localIndex) {
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];
        return nibble == null ? 0 : nibble.getUpdating(localIndex);
    }

    protected void setLightLevel(final int worldX, final int worldY, final int worldZ, final int level) {
        final int sectionIndex = (worldX >> 4) + 5 * (worldZ >> 4)
                + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset;
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];
        if (nibble == null) {
            return;
        }
        final int localIndex =
                (worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8);
        if (nibble.getUpdating(localIndex) == level) {
            return;
        }
        nibble.set(localIndex, level);
        this.postLightUpdate(sectionIndex, worldX & 15, worldY & 15, worldZ & 15);
    }

    protected final void postLightUpdate(final int sectionIndex,
                                         final int localX, final int localY, final int localZ) {
        if (!this.isClientSide) {
            return;
        }
        final long point = RenderBounds.pack(localX, localY, localZ, localX, localY, localZ);
        if (!this.notifyUpdateCache[sectionIndex]) {
            this.notifyUpdateCache[sectionIndex] = true;
            this.notifyBoundsCache[sectionIndex] = point;
        } else {
            this.notifyBoundsCache[sectionIndex] =
                    RenderBounds.union(this.notifyBoundsCache[sectionIndex], point);
        }
    }

    protected final SWMRNibbleArray[] getFilledEmptyLight() {
        final SWMRNibbleArray[] result =
                new SWMRNibbleArray[this.heightContext.getTotalLightSections()];
        for (int index = 0; index < result.length; ++index) {
            result[index] = new SWMRNibbleArray(null, true);
        }
        return result;
    }

    /**
     * Hook for lane-specific task state such as BFS queue lengths.
     */
    protected abstract void resetTaskState();

    protected abstract boolean[] getEmptinessMap(Chunk chunk);

    protected abstract SWMRNibbleArray[] getNibblesOnChunk(Chunk chunk);

    protected abstract boolean canUseChunk(Chunk chunk);

    /**
     * Called after a dirty nibble is published.
     */
    protected void onNibbleVisible(final int cacheIndex, final SWMRNibbleArray nibble) {
    }
}

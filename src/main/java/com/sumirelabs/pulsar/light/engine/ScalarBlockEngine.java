package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Scalar block-light engine. BFS propagation with per-block emission and
 * directional opacity. Mirrors {@code ScalarBlockEngine} from SuperNova
 * (1.7.10), with block-id lookups replaced by {@link IBlockState}.
 *
 * <p>For blocks that inherit Forge's context methods, opacity and emission
 * are memoised per state via {@link LightInfo}: the BFS hot loops read one
 * packed field instead of making virtual calls and hash lookups per visit.
 * Mod blocks that override the context-aware methods are detected once per
 * block class and queried with the engine's reusable mutable position, so
 * world/position-dependent light remains correct without per-visit garbage.
 */
@SuppressWarnings("deprecation")
public class ScalarBlockEngine extends PulsarEngine {

    public ScalarBlockEngine(final World world, final WorldHeightContext heightContext) {
        super(false, world, heightContext);
    }

    @Override
    protected boolean[] getEmptinessMap(final Chunk chunk) {
        return ((PulsarChunk) chunk).pulsar$getBlockEmptinessMap();
    }

    @Override
    protected void setEmptinessMap(final Chunk chunk, final boolean[] to) {
        ((PulsarChunk) chunk).pulsar$setBlockEmptinessMap(to);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesOnChunk(final Chunk chunk) {
        return ((PulsarChunk) chunk).pulsar$getBlockNibbles();
    }

    @Override
    protected void setNibbles(final Chunk chunk, final SWMRNibbleArray[] to) {
        ((PulsarChunk) chunk).pulsar$setBlockNibbles(to);
    }

    @Override
    protected boolean canUseChunk(final Chunk chunk) {
        return ((PulsarChunk) chunk).pulsar$isLightUsable();
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }
        final SWMRNibbleArray nib = this.nibbleCache[chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
        if (nib == null) {
            if (!initRemovedNibbles) {
                return;
            }
            this.setNibbleInCache(chunkX, chunkY, chunkZ, new SWMRNibbleArray());
        } else {
            nib.setNonNull();
        }
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        // Block light uses setHidden() — maintains data for decrease propagation
        final SWMRNibbleArray nib = this.nibbleCache[chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
        if (nib != null) {
            nib.setHidden();
        }
    }

    @Override
    protected void checkBlock(final int worldX, final int worldY, final int worldZ) {
        final int encodeOffset = this.coordinateOffset;
        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);

        final IBlockState state = this.getBlockState(worldX, worldY, worldZ);
        final int info = this.lightInfoAt(state, worldX, worldY, worldZ);
        final int emission = LightInfo.emission(info);

        // No "level unchanged" early-out (upstream): an equal level cannot
        // prove the propagation DIRECTIONS are unchanged — a slab swap can
        // keep this cell's level while flipping which faces it feeds.
        this.setLightLevel(worldX, worldY, worldZ, emission);

        if (emission > 0) {
            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | this.encodeQueueLevel(emission)
                    | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                    | sidedFlag(info));
        }

        // No sided flag on the decrease (upstream): the flag would mask
        // decrease directions with the NEW block's occlusion, but the light
        // being erased flowed through the OLD block.
        this.appendToDecreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                | this.encodeQueueLevel(currentLevel)
                | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT));
    }

    @Override
    protected int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect) {
        return this.calculateLightValueWithInfo(worldX, worldY, worldZ, expect,
                this.lightInfoAt(this.getBlockState(worldX, worldY, worldZ), worldX, worldY, worldZ));
    }

    private int calculateLightValueWithInfo(final int worldX, final int worldY, final int worldZ, final int expect, final int info) {
        int level = LightInfo.emission(info);

        if (level >= 14 || level > expect) {
            return level;
        }

        final int rawOpacity = LightInfo.opacity(info);
        final boolean sidedTransparent = rawOpacity > 1 && (info & LightInfo.REGISTRY) != 0;
        final int faceBits = LightInfo.faceBits(info);
        final int uniformAbsorption = !sidedTransparent ? Math.max(1, rawOpacity) : 0;
        final int sectionOffset = this.chunkSectionIndexOffset;

        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
            final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

            final int neighbourLevel = this.getLightLevel(sectionIndex, localIndex);

            final int absorption = sidedTransparent
                    ? ((faceBits & (1 << direction.ordinal())) != 0 ? rawOpacity : 1)
                    : uniformAbsorption;
            final int attenuated = neighbourLevel - absorption;
            if (attenuated > level) {
                level = attenuated;
            }

            if (level > expect) {
                return level;
            }
        }

        return level;
    }

    @Override
    protected void propagateBlockChanges(final Chunk atChunk, final int blockX, final int blockY, final int blockZ) {
        this.checkBlock(blockX, blockY, blockZ);
        this.performLightDecrease();
    }

    @Override
    protected void processBlockPositionChanges(final Chunk chunk, final int chunkX, final int chunkZ, final IntOpenHashSet changedPositions) {
        final int minBlockY = this.heightContext.getMinBlockY();
        final int maxBlockY = this.heightContext.getMaxBlockY();
        final IntIterator it = changedPositions.iterator();
        while (it.hasNext()) {
            final int packed = it.nextInt();
            final int worldY = packed >> 8;
            if (worldY < minBlockY || worldY > maxBlockY) {
                continue;
            }
            final int worldX = (chunkX << 4) | (packed & 15);
            final int worldZ = (chunkZ << 4) | ((packed >> 4) & 15);
            this.lastPositionsProcessed++;
            this.checkBlock(worldX, worldY, worldZ);
        }
        this.performLightDecrease();
    }

    @Override
    protected void lightChunk(final Chunk chunk, final boolean needsEdgeChecks) {
        final int offX = chunk.x << 4;
        final int offZ = chunk.z << 4;
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();

        for (int sectionY = this.minSection; sectionY <= this.maxSection; ++sectionY) {
            final int storageIndex = this.heightContext.getStorageIndex(sectionY);
            final ExtendedBlockStorage section = storageIndex >= 0 && storageIndex < sections.length
                    ? sections[storageIndex] : null;
            if (section == null || section.isEmpty()) {
                continue;
            }

            final int offY = sectionY << 4;
            final int sectionIdx = chunk.x + 5 * chunk.z + (5 * 5) * sectionY + this.chunkSectionIndexOffset;

            for (int index = 0; index < (16 * 16 * 16); ++index) {
                final int lx = index & 15;
                final int ly = index >>> 8;
                final int lz = (index >>> 4) & 15;

                final int worldX = offX | lx;
                final int worldY = offY | ly;
                final int worldZ = offZ | lz;

                final IBlockState state = this.getBlockStateFast(sectionIdx, lx, ly, lz);
                final int info = this.lightInfoAt(state, worldX, worldY, worldZ);
                final int emission = LightInfo.emission(info);
                if (emission <= 0) {
                    continue;
                }

                final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);
                if (emission <= currentLevel) {
                    continue;
                }

                this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, this.coordinateOffset)
                        | this.encodeQueueLevel(emission)
                        | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                        | sidedFlag(info));

                this.setLightLevel(worldX, worldY, worldZ, emission);
            }
        }

        if (needsEdgeChecks) {
            this.performLightIncrease();
            this.checkChunkEdges(chunk, this.minLightSection, this.maxLightSection);
        } else {
            this.propagateNeighbourLevels(chunk, this.minLightSection, this.maxLightSection);
            this.performLightIncrease();
        }
    }

    @Override
    protected void performLightIncrease() {
        long[] queue = this.increaseQueue;
        int queueReadIndex = 0;
        int queueLength = this.increaseQueueInitialLength;
        this.increaseQueueInitialLength = 0;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;
        final int encodeOffset = this.coordinateOffset;
        final int sectionOffset = this.chunkSectionIndexOffset;

        while (queueReadIndex < queueLength) {
            final long queueValue = queue[queueReadIndex++];

            final int posX = ((int) queueValue & 63) + decodeOffsetX;
            final int posZ = (((int) queueValue >>> 6) & 63) + decodeOffsetZ;
            final int posY = (((int) queueValue >>> 12) & COORD_Y_MASK) + decodeOffsetY;
            final int propagatedLevel = (int) ((queueValue >>> LIGHT_LEVEL_SHIFT) & 0xF);
            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[(int) ((queueValue >>> DIRECTION_SHIFT) & 63L)];

            final boolean hasSidedTransparent = (queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) != 0L;
            int srcBlockedFaces = 0;
            if (hasSidedTransparent) {
                final int srcIdx = (posX >> 4) + 5 * (posZ >> 4) + (5 * 5) * (posY >> 4) + sectionOffset;
                final IBlockState srcState = this.getBlockStateFast(srcIdx, posX & 15, posY & 15, posZ & 15);
                srcBlockedFaces = LightInfo.faceBits(this.lightInfoAt(srcState, posX, posY, posZ));
            }

            if ((queueValue & FLAG_RECHECK_LEVEL) != 0L) {
                if (this.getLightLevel(posX, posY, posZ) != propagatedLevel) {
                    continue;
                }
            } else if ((queueValue & FLAG_WRITE_LEVEL) != 0L) {
                this.setLightLevel(posX, posY, posZ, propagatedLevel);
            }

            for (final AxisDirection propagate : checkDirections) {
                if ((srcBlockedFaces & (1 << propagate.ordinal())) != 0) continue;

                final int offX = posX + propagate.x;
                final int offY = posY + propagate.y;
                final int offZ = posZ + propagate.z;

                final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                if (this.nibbleCache[sectionIndex] == null) {
                    continue;
                }

                final int currentLevel = this.getLightLevel(sectionIndex, localIndex);
                // Minimum absorption is 1, so propagatedLevel - 1 is the best
                // this neighbour could reach — skip the palette + LightInfo
                // reads entirely when it is already there (Starlight upstream
                // has the same early-out; ~half of frontier neighbours hit it).
                if (currentLevel >= propagatedLevel - 1) {
                    continue;
                }

                final IBlockState destState = this.getBlockStateFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int destInfo = this.lightInfoAt(destState, offX, offY, offZ);
                final int absorption = LightInfo.absorption(destInfo, destState, propagate.oppositeOrdinal);

                final int targetLevel = propagatedLevel - absorption;
                if (targetLevel <= currentLevel) {
                    continue;
                }

                // Write through the already-resolved nibble: the guards above
                // proved this is a real change, so setLightLevel's no-op check
                // and index recompute would be pure overhead here.
                this.nibbleCache[sectionIndex].set(localIndex, targetLevel);
                this.postLightUpdate(sectionIndex, offX & 15, offY & 15, offZ & 15);

                if (targetLevel > 1) {
                    if (queueLength >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        queue = this.resizeIncreaseQueue();
                    }
                    queue[queueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(targetLevel)
                            | (propagate.everythingButTheOppositeDirection << DIRECTION_SHIFT)
                            | sidedFlag(destInfo);
                }
            }
        }
        this.lastBfsIncreaseTotal += queueLength;
    }

    @Override
    protected void performLightDecrease() {
        long[] queue = this.decreaseQueue;
        long[] increaseQueue = this.increaseQueue;
        int queueReadIndex = 0;
        int queueLength = this.decreaseQueueInitialLength;
        this.decreaseQueueInitialLength = 0;
        int increaseQueueLength = this.increaseQueueInitialLength;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;
        final int encodeOffset = this.coordinateOffset;
        final int sectionOffset = this.chunkSectionIndexOffset;

        while (queueReadIndex < queueLength) {
            final long queueValue = queue[queueReadIndex++];

            final int posX = ((int) queueValue & 63) + decodeOffsetX;
            final int posZ = (((int) queueValue >>> 6) & 63) + decodeOffsetZ;
            final int posY = (((int) queueValue >>> 12) & COORD_Y_MASK) + decodeOffsetY;
            final int propagatedLevel = (int) ((queueValue >>> LIGHT_LEVEL_SHIFT) & 0xF);
            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[(int) ((queueValue >>> DIRECTION_SHIFT) & 63)];

            final boolean hasSidedTransparent = (queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) != 0L;
            int srcBlockedFaces = 0;
            if (hasSidedTransparent) {
                final int srcIdx = (posX >> 4) + 5 * (posZ >> 4) + (5 * 5) * (posY >> 4) + sectionOffset;
                final IBlockState srcState = this.getBlockStateFast(srcIdx, posX & 15, posY & 15, posZ & 15);
                srcBlockedFaces = LightInfo.faceBits(this.lightInfoAt(srcState, posX, posY, posZ));
            }

            for (final AxisDirection propagate : checkDirections) {
                if ((srcBlockedFaces & (1 << propagate.ordinal())) != 0) continue;

                final int offX = posX + propagate.x;
                final int offY = posY + propagate.y;
                final int offZ = posZ + propagate.z;

                final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;

                if (this.nibbleCache[sectionIndex] == null) {
                    continue;
                }

                final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);
                final int currentLevel = this.getLightLevel(sectionIndex, localIndex);
                if (currentLevel == 0) {
                    continue;
                }

                final IBlockState state = this.getBlockStateFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int info = this.lightInfoAt(state, offX, offY, offZ);
                final int absorption = LightInfo.absorption(info, state, propagate.oppositeOrdinal);

                final int targetLevel = propagatedLevel - absorption;
                final long sFlag = sidedFlag(info);

                if (currentLevel > targetLevel) {
                    if (increaseQueueLength >= increaseQueue.length) {
                        if (increaseQueue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        increaseQueue = this.resizeIncreaseQueue();
                    }
                    increaseQueue[increaseQueueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(currentLevel)
                            | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                            | FLAG_RECHECK_LEVEL
                            | sFlag;
                    continue;
                }

                // currentLevel != 0 was checked above — direct write, no no-op guard needed.
                this.nibbleCache[sectionIndex].set(localIndex, 0);
                this.postLightUpdate(sectionIndex, offX & 15, offY & 15, offZ & 15);

                final int emission = LightInfo.emission(info);
                if (emission > 0) {
                    if (increaseQueueLength >= increaseQueue.length) {
                        if (increaseQueue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        increaseQueue = this.resizeIncreaseQueue();
                    }
                    this.nibbleCache[sectionIndex].set(localIndex, emission);
                    this.postLightUpdate(sectionIndex, offX & 15, offY & 15, offZ & 15);
                    increaseQueue[increaseQueueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(emission)
                            | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                            | FLAG_WRITE_LEVEL
                            | sFlag;
                }

                // Independent of the emission re-seed (upstream): the decrease
                // must keep walking past emitters, or removing a bright source
                // leaves a permanent ghost region behind any dimmer emitter.
                if (targetLevel > 0) {
                    if (queueLength >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        queue = this.resizeDecreaseQueue();
                    }
                    queue[queueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(targetLevel)
                            | (propagate.everythingButTheOppositeDirection << DIRECTION_SHIFT)
                            | sFlag;
                }
            }
        }

        this.lastBfsDecreaseTotal += queueLength;
        this.increaseQueueInitialLength = increaseQueueLength;
        this.performLightIncrease();
    }

    @Override
    protected void onNibbleVisible(final int cacheIndex, final SWMRNibbleArray nibble) {
        if (nibble == null || !this.isVanillaStorageSection(cacheIndex)) {
            return;
        }
        final ExtendedBlockStorage section = this.getLiveChunkSection(cacheIndex);
        if (section == null) {
            return;
        }
        final byte[] srcData = nibble.getVisibleData();
        if (srcData == null) return;
        final NibbleArray vanilla = section.getBlockLight();
        if (vanilla == null) return;
        final byte[] dst = vanilla.getData();
        if (dst == srcData) return; // thin client: SWMR shares the vanilla storage
        System.arraycopy(srcData, 0, dst, 0, srcData.length);
    }
}

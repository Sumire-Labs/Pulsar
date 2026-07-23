package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldUtil;
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
 * <p>The {@link IBlockState#getLightValue()} / {@code getLightOpacity()}
 * overloads without the {@code IBlockAccess}/{@code BlockPos} context are
 * deprecated in Forge 1.12.2 in favour of the context-aware variants.
 * Pulsar uses the deprecated overloads because the context versions would
 * need a freshly-allocated {@link net.minecraft.util.math.BlockPos} per
 * neighbour read. Since they are position-independent, their results are
 * memoised per state via {@link LightInfo} — the BFS hot loops read a single
 * packed field instead of making virtual calls and hash lookups per visit.
 */
@SuppressWarnings("deprecation")
public class ScalarBlockEngine extends PulsarEngine {

    public ScalarBlockEngine(final World world) {
        super(false, world);
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
        return ((PulsarChunk) chunk).pulsar$isLightReady();
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
        final int info = LightInfo.of(state);
        final int emission = LightInfo.emission(info);

        final int calculatedLevel = this.calculateLightValueWithInfo(worldX, worldY, worldZ, 15, info);
        if (currentLevel == calculatedLevel) {
            return;
        }

        this.setLightLevel(worldX, worldY, worldZ, emission);

        final long sf = sidedFlag(info);

        if (emission > 0) {
            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | this.encodeQueueLevel(emission)
                    | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                    | sf);
        }

        this.appendToDecreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                | this.encodeQueueLevel(currentLevel)
                | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                | sf);
    }

    @Override
    protected int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect) {
        return this.calculateLightValueWithInfo(worldX, worldY, worldZ, expect,
                LightInfo.of(this.getBlockState(worldX, worldY, worldZ)));
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
        final int minBlockY = WorldUtil.getMinBlockY();
        final int maxBlockY = WorldUtil.getMaxBlockY();
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
            final ExtendedBlockStorage section = sections[sectionY - this.minSection];
            if (section == null || section.isEmpty()) {
                continue;
            }

            final int offY = sectionY << 4;
            final int sectionIdx = chunk.x + 5 * chunk.z + (5 * 5) * sectionY + this.chunkSectionIndexOffset;

            for (int index = 0; index < (16 * 16 * 16); ++index) {
                final int lx = index & 15;
                final int ly = index >>> 8;
                final int lz = (index >>> 4) & 15;

                final IBlockState state = this.getBlockStateFast(sectionIdx, lx, ly, lz);
                final int info = LightInfo.of(state);
                final int emission = LightInfo.emission(info);
                if (emission <= 0) {
                    continue;
                }

                final int worldX = offX | lx;
                final int worldY = offY | ly;
                final int worldZ = offZ | lz;

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
                srcBlockedFaces = LightInfo.faceBits(LightInfo.of(srcState));
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

                final IBlockState destState = this.getBlockStateFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int destInfo = LightInfo.of(destState);
                final int absorption = LightInfo.absorption(destInfo, destState, propagate.oppositeOrdinal);

                final int targetLevel = propagatedLevel - absorption;
                if (targetLevel <= currentLevel) {
                    continue;
                }

                this.setLightLevel(offX, offY, offZ, targetLevel);

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
                srcBlockedFaces = LightInfo.faceBits(LightInfo.of(srcState));
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
                final int info = LightInfo.of(state);
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

                this.setLightLevel(offX, offY, offZ, 0);

                final int emission = LightInfo.emission(info);
                if (emission > 0) {
                    if (increaseQueueLength >= increaseQueue.length) {
                        if (increaseQueue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        increaseQueue = this.resizeIncreaseQueue();
                    }
                    this.setLightLevel(offX, offY, offZ, emission);
                    increaseQueue[increaseQueueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(emission)
                            | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                            | FLAG_WRITE_LEVEL
                            | sFlag;
                } else if (currentLevel > 1) {
                    if (queueLength >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        queue = this.resizeDecreaseQueue();
                    }
                    queue[queueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(currentLevel)
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
        if (nibble == null) return;
        final int cy = cacheIndex / 25;
        final int sectionY = cy - this.chunkOffsetY;
        if (sectionY < this.minSection || sectionY > this.maxSection) return;
        final ExtendedBlockStorage section = this.sectionCache[cacheIndex];
        if (section == null) return;
        final byte[] srcData = nibble.getVisibleData();
        if (srcData == null) return;
        final NibbleArray vanilla = section.getBlockLight();
        if (vanilla == null) return;
        System.arraycopy(srcData, 0, vanilla.getData(), 0, srcData.length);
    }
}

package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Scalar sky-light engine. Two-phase BFS: vertical extrusion seeds light from
 * the world top, then horizontal propagation handles attenuation through
 * partially-occluding blocks. Mirrors {@code ScalarSkyEngine} from SuperNova
 * (1.7.10), with the block-id paths replaced by {@link IBlockState}.
 *
 * <p>Opacity/occlusion lookups come from the per-state {@link LightInfo}
 * cache — see {@link ScalarBlockEngine} javadoc for the rationale.
 */
@SuppressWarnings("deprecation")
public class ScalarSkyEngine extends PulsarEngine {

    private final SkyLightColumnProcessor columnProcessor;

    public ScalarSkyEngine(final World world, final WorldHeightContext heightContext) {
        super(true, world, heightContext);
        this.columnProcessor = new SkyLightColumnProcessor(this);
    }

    @Override
    protected boolean[] getEmptinessMap(final Chunk chunk) {
        return ((PulsarChunk) chunk).pulsar$getSkyEmptinessMap();
    }

    @Override
    protected void setEmptinessMap(final Chunk chunk, final boolean[] to) {
        ((PulsarChunk) chunk).pulsar$setSkyEmptinessMap(to);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesOnChunk(final Chunk chunk) {
        return ((PulsarChunk) chunk).pulsar$getSkyNibbles();
    }

    @Override
    protected void setNibbles(final Chunk chunk, final SWMRNibbleArray[] to) {
        ((PulsarChunk) chunk).pulsar$setSkyNibbles(to);
    }

    @Override
    protected boolean canUseChunk(final Chunk chunk) {
        return ((PulsarChunk) chunk).pulsar$isLightUsable();
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        this.columnProcessor.initNibble(
                chunkX, chunkY, chunkZ, extrude, initRemovedNibbles);
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        this.columnProcessor.setNibbleNull(chunkX, chunkY, chunkZ);
    }

    private void rewriteNibbleCacheForSkylight() {
        this.columnProcessor.rewriteNibbleCache();
    }

    @Override
    protected void prepareBatchedEdgeChecks(final int chunkX, final int chunkZ) {
        this.columnProcessor.prepareBatchedEdgeChecks(chunkX, chunkZ);
    }

    private boolean checkNullSection(final int chunkX, final int chunkY, final int chunkZ, final boolean extrudeInitialised) {
        return this.columnProcessor.checkNullSection(
                chunkX, chunkY, chunkZ, extrudeInitialised);
    }

    private int tryPropagateSkylight(final int worldX, int startY, final int worldZ, final boolean extrudeInitialised, final boolean delayLightSet) {
        return this.columnProcessor.tryPropagateSkylight(
                worldX, startY, worldZ, extrudeInitialised, delayLightSet);
    }

    /**
     * Batched form, matching upstream {@code SkyStarLightEngine
     * .propagateBlockChanges(…, Set&lt;BlockPos&gt;)}: caches are rewritten
     * once, ONE skylight column walk per changed COLUMN (from its highest
     * changed Y), every {@code checkBlock} seeded into the same queues and a
     * single BFS drain at the end. The inherited per-position loop re-ran
     * the full pipeline (cache rewrite + column walk + full drain) for every
     * block — on dense bulk edits (platform builds, /fill) that re-decreased
     * and re-flooded the same overlapping regions once per block.
     */
    @Override
    protected void processBlockPositionChanges(final Chunk chunk, final int chunkX, final int chunkZ,
                                               final IntOpenHashSet changedPositions) {
        this.columnProcessor.processBlockPositionChanges(chunkX, chunkZ, changedPositions);
    }

    @Override
    protected void propagateBlockChanges(final Chunk atChunk, final int blockX, final int blockY, final int blockZ) {
        this.columnProcessor.propagateBlockChange(blockX, blockY, blockZ);
    }

    @Override
    protected void checkBlock(final int worldX, final int worldY, final int worldZ) {
        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);
        final int encodeOffset = this.coordinateOffset;

        if (currentLevel == 15) {
            // Must re-propagate the clobbered source. Upstream sets the sided
            // flag because it does not know whether the block is
            // conditionally transparent.
            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | this.encodeQueueLevel(15)
                    | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                    | FLAG_HAS_SIDED_TRANSPARENT_BLOCKS);
        } else {
            this.setLightLevel(worldX, worldY, worldZ, 0);
        }

        // Unconditional (upstream SkyStarLightEngine.checkBlock): the
        // decrease pass both removes stale light AND — via its
        // brighter-neighbour recheck branch — re-seeds the flood from
        // adjacent sky sources. Queueing it only for non-source cells left
        // newly revealed cavities black and light under placed blocks stale.
        // NO sided flag here (upstream): it would mask decrease directions
        // with the NEW block's occlusion while erasing light that flowed
        // through the OLD block.
        this.appendToDecreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                | this.encodeQueueLevel(currentLevel)
                | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT));
    }

    @Override
    protected int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect) {
        if (expect == 15) {
            return expect;
        }

        final int sectionOffset = this.chunkSectionIndexOffset;
        final int info = this.lightInfoAt(this.getBlockState(worldX, worldY, worldZ), worldX, worldY, worldZ);
        final int rawOpacity = LightInfo.opacity(info);
        final boolean sidedTransparent = rawOpacity > 1 && (info & LightInfo.REGISTRY) != 0;
        final int faceBits = LightInfo.faceBits(info);
        final int uniformAbsorption = !sidedTransparent ? Math.max(1, rawOpacity) : 0;

        int level = 0;
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
    protected void lightChunk(final Chunk chunk, final boolean needsEdgeChecks) {
        this.rewriteNibbleCacheForSkylight();
        this.columnProcessor.resetNullPropagationChecks();

        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();

        int highestNonEmptySection = this.maxSection;
        int highestStorageIndex = this.heightContext.getStorageIndex(highestNonEmptySection);
        while (highestNonEmptySection >= this.minSection
                && (highestStorageIndex < 0 || highestStorageIndex >= sections.length
                || sections[highestStorageIndex] == null || sections[highestStorageIndex].isEmpty())) {
            this.checkNullSection(chunkX, highestNonEmptySection, chunkZ, false);

            for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
                final int neighbourX = chunkX + direction.x;
                final int neighbourZ = chunkZ + direction.z;
                final SWMRNibbleArray neighbourNibble = this.getNibbleFromCache(neighbourX, highestNonEmptySection, neighbourZ);
                if (neighbourNibble == null) continue;

                final int incX, incZ, startX, startZ;
                if (direction.x != 0) {
                    incX = 0;
                    incZ = 1;
                    startX = direction.x < 0 ? chunkX << 4 : chunkX << 4 | 15;
                    startZ = chunkZ << 4;
                } else {
                    incX = 1;
                    incZ = 0;
                    startZ = direction.z < 0 ? chunkZ << 4 : chunkZ << 4 | 15;
                    startX = chunkX << 4;
                }

                final int encodeOffset = this.coordinateOffset;
                final long propagateDir = 1L << direction.ordinal();

                for (int currY = highestNonEmptySection << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                    for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                        this.appendToIncreaseQueue(encodeCoords(currX, currZ, currY, encodeOffset)
                                | this.encodeQueueLevel(15)
                                | (propagateDir << DIRECTION_SHIFT));
                    }
                }
            }

            --highestNonEmptySection;
            highestStorageIndex = this.heightContext.getStorageIndex(highestNonEmptySection);
        }

        if (highestNonEmptySection >= this.minSection) {
            final int minX = chunkX << 4;
            final int maxX = chunkX << 4 | 15;
            final int minZ = chunkZ << 4;
            final int maxZ = chunkZ << 4 | 15;
            final int startY = highestNonEmptySection << 4 | 15;
            for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                for (int currX = minX; currX <= maxX; ++currX) {
                    this.tryPropagateSkylight(currX, startY + 1, currZ, false, false);
                }
            }
        }

        if (needsEdgeChecks) {
            this.performLightIncrease();
            for (int y = highestNonEmptySection; y >= this.minLightSection; --y) {
                this.checkNullSection(chunkX, y, chunkZ, false);
            }
            super.checkChunkEdges(chunk, this.minLightSection, highestNonEmptySection);
        } else {
            for (int y = highestNonEmptySection; y >= this.minLightSection; --y) {
                this.checkNullSection(chunkX, y, chunkZ, false);
            }
            this.propagateNeighbourLevels(chunk, this.minLightSection, highestNonEmptySection);
            this.performLightIncrease();
        }
    }

    @Override
    protected void checkChunkEdges(final Chunk chunk, final int fromSection, final int toSection) {
        this.columnProcessor.resetNullPropagationChecks();
        this.rewriteNibbleCacheForSkylight();

        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;
        for (int y = toSection; y >= fromSection; --y) {
            this.checkNullSection(chunkX, y, chunkZ, true);
        }
        super.checkChunkEdges(chunk, fromSection, toSection);
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
                // NOTE: no `currentLevel == 15` skip here. A sky-source
                // neighbour must fall through to the brighter-neighbour
                // branch below so it is re-queued as a RECHECK increase —
                // that is what re-floods the region this decrease is
                // darkening (upstream has no such skip). The branch never
                // decreases it, so sources stay intact.

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

                // Gate on targetLevel > 0 with targetLevel (upstream): the
                // level-1 boundary entries are what re-flood the cleared
                // region's outer ring from surrounding light.
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
        if (nibble == null) return;
        final int cy = cacheIndex / 25;
        final int sectionY = cy - this.chunkOffsetY;
        if (sectionY < this.minSection || sectionY > this.maxSection) return;
        final ExtendedBlockStorage section = this.sectionCache[cacheIndex];
        if (section == null) return;
        final byte[] srcData = nibble.getVisibleData();
        if (srcData == null) return;
        final NibbleArray vanilla = section.getSkyLight();
        if (vanilla == null) return;
        final byte[] dst = vanilla.getData();
        if (dst == srcData) return; // thin client: SWMR shares the vanilla storage
        System.arraycopy(srcData, 0, dst, 0, srcData.length);
    }
}

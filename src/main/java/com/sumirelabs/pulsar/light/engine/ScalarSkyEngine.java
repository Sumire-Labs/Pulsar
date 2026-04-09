package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * Scalar sky-light engine. Two-phase BFS: vertical extrusion seeds light from
 * the world top, then horizontal propagation handles attenuation through
 * partially-occluding blocks. Mirrors {@code ScalarSkyEngine} from SuperNova
 * (1.7.10), with the block-id paths replaced by {@link IBlockState}.
 *
 * <p>Uses the deprecated {@link IBlockState#getLightOpacity()} overload on
 * the BFS hot path — see {@link ScalarBlockEngine} javadoc for the
 * rationale.
 */
@SuppressWarnings("deprecation")
public class ScalarSkyEngine extends PulsarEngine {

    private final boolean[] nullPropagationCheckCache;

    public ScalarSkyEngine(final World world) {
        super(true, world);
        this.nullPropagationCheckCache = new boolean[WorldUtil.getTotalLightSections()];
        // Sky BFS covers far more blocks than block light — start with larger queues.
        this.increaseQueue = new long[1 << 18];
        this.decreaseQueue = new long[1 << 18];
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
        return ((PulsarChunk) chunk).pulsar$isLightReady();
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }
        SWMRNibbleArray nib = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nib == null) {
            if (!initRemovedNibbles) {
                return;
            }
            this.setNibbleInCache(chunkX, chunkY, chunkZ, nib = new SWMRNibbleArray(null, true));
        }
        this.initSkyNibble(nib, chunkX, chunkY, chunkZ, extrude);
    }

    private void initSkyNibble(final SWMRNibbleArray nibble, final int chunkX, final int chunkY, final int chunkZ, final boolean extrude) {
        if (chunkY > this.maxSection) {
            nibble.setFull();
            return;
        }
        if (chunkY < this.minSection) {
            nibble.setNonNull();
            nibble.setZero();
            return;
        }

        final boolean[] emptinessMap = this.getEmptinessMap(chunkX, chunkZ);
        int lowestNonEmpty = this.minSection - 1;
        for (int cy = this.maxSection; cy >= this.minSection; --cy) {
            if (emptinessMap != null) {
                if (emptinessMap[cy - this.minSection]) continue;
            } else {
                final ExtendedBlockStorage section = this.getChunkSection(chunkX, cy, chunkZ);
                if (section == null || section.isEmpty()) continue;
            }
            lowestNonEmpty = cy;
            break;
        }

        if (chunkY > lowestNonEmpty) {
            nibble.setNonNull();
            nibble.setFull();
        } else if (emptinessMap != null && emptinessMap[chunkY - this.minSection]) {
            if (extrude) {
                final SWMRNibbleArray above = this.getNibbleFromCache(chunkX, chunkY + 1, chunkZ);
                if (above != null && above.isFullUpdating()) {
                    nibble.setFull();
                } else {
                    nibble.setNonNull();
                    nibble.setZero();
                }
            } else {
                nibble.setNonNull();
            }
        } else {
            nibble.setNonNull();
        }
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        final SWMRNibbleArray nib = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nib != null) {
            nib.setNull();
        }
    }

    private void rewriteNibbleCacheForSkylight() {
        for (int index = 0, max = this.nibbleCache.length; index < max; ++index) {
            final SWMRNibbleArray nibble = this.nibbleCache[index];
            if (nibble != null && nibble.isNullNibbleUpdating()) {
                this.nibbleCache[index] = null;
                nibble.updateVisible();
            }
        }
    }

    private boolean checkNullSection(final int chunkX, final int chunkY, final int chunkZ, final boolean extrudeInitialised) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.nullPropagationCheckCache[chunkY - this.minLightSection]) {
            return false;
        }
        this.nullPropagationCheckCache[chunkY - this.minLightSection] = true;

        boolean needInitNeighbours = false;
        neighbour_search:
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                final SWMRNibbleArray nibble = this.getNibbleFromCache(dx + chunkX, chunkY, dz + chunkZ);
                if (nibble != null && !nibble.isNullNibbleUpdating()) {
                    needInitNeighbours = true;
                    break neighbour_search;
                }
            }
        }

        if (needInitNeighbours) {
            for (int dz = -1; dz <= 1; ++dz) {
                for (int dx = -1; dx <= 1; ++dx) {
                    this.initNibble(dx + chunkX, chunkY, dz + chunkZ, (dx | dz) != 0 || extrudeInitialised, true);
                }
            }
        }

        return needInitNeighbours;
    }

    private int getLightLevelExtruded(final int worldX, final int worldY, final int worldZ) {
        final int chunkX = worldX >> 4;
        int chunkY = worldY >> 4;
        final int chunkZ = worldZ >> 4;

        final int idx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
        if (this.nibbleCache[idx] != null) {
            return this.getLightLevel(idx, (worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8));
        }

        while (++chunkY <= this.maxLightSection) {
            final int nextIdx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
            if (this.nibbleCache[nextIdx] != null) {
                return this.getLightLevel(nextIdx, (worldX & 15) | ((worldZ & 15) << 4));
            }
        }
        return 15;
    }

    private int tryPropagateSkylight(final int worldX, int startY, final int worldZ, final boolean extrudeInitialised, final boolean delayLightSet) {
        final int encodeOffset = this.coordinateOffset;
        final long propagateDirection = AxisDirection.POSITIVE_Y.everythingButThisDirection;

        final int extrudedLevel = this.getLightLevelExtruded(worldX, startY + 1, worldZ);
        if (extrudedLevel == 0) {
            return startY;
        }

        this.checkNullSection(worldX >> 4, startY >> 4, worldZ >> 4, extrudeInitialised);

        int currentSky = extrudedLevel;

        IBlockState above = this.getBlockState(worldX, startY + 1, worldZ);
        int aboveMeta = above.getBlock().getMetaFromState(above);

        for (; startY >= (this.minLightSection << 4); --startY) {
            if ((startY & 15) == 15) {
                this.checkNullSection(worldX >> 4, startY >> 4, worldZ >> 4, extrudeInitialised);
            }
            final IBlockState current = this.getBlockState(worldX, startY, worldZ);
            final int meta = current.getBlock().getMetaFromState(current);

            // Check if light can pass DOWN through the above block
            final int aboveOpacity = above.getLightOpacity();
            if (aboveOpacity > 0) {
                if (!FaceOcclusion.hasSidedTransparency(above.getBlock()) || FaceOcclusion.isFaceSolid(above.getBlock(), aboveMeta, 5)) {
                    break;
                }
            }

            // Check if light can enter the current block from above
            final int currentOpacity = current.getLightOpacity();
            if (currentOpacity > 0) {
                if (!FaceOcclusion.hasSidedTransparency(current.getBlock()) || FaceOcclusion.isFaceSolid(current.getBlock(), meta, 4)) {
                    break;
                }
            }

            if (currentOpacity > 0) {
                currentSky -= currentOpacity;
                if (currentSky <= 0) {
                    break;
                }
            }

            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, startY, encodeOffset)
                    | this.encodeQueueLevel(currentSky)
                    | (propagateDirection << DIRECTION_SHIFT));

            if (this.getNibbleFromCache(worldX >> 4, startY >> 4, worldZ >> 4) == null) {
                --this.increaseQueueInitialLength;
                startY = startY & ~15;
                above = Blocks.AIR.getDefaultState();
                aboveMeta = 0;
            } else if (!delayLightSet) {
                this.setLightLevel(worldX, startY, worldZ, currentSky);
                above = current;
                aboveMeta = meta;
            } else {
                above = current;
                aboveMeta = meta;
            }
        }

        return startY;
    }

    @Override
    protected void propagateBlockChanges(final Chunk atChunk, final int blockX, final int blockY, final int blockZ) {
        this.rewriteNibbleCacheForSkylight();
        Arrays.fill(this.nullPropagationCheckCache, false);

        final int maxPropagationY = this.tryPropagateSkylight(blockX, blockY, blockZ, true, true);

        final long propagateDirection = AxisDirection.POSITIVE_Y.everythingButThisDirection;
        final int encodeOffset = this.coordinateOffset;

        final int extrudedLevel = this.getLightLevelExtruded(blockX, maxPropagationY, blockZ);
        if (extrudedLevel == 15) {
            this.checkNullSection(blockX >> 4, maxPropagationY >> 4, blockZ >> 4, true);

            for (int currY = maxPropagationY; currY >= (this.minLightSection << 4); --currY) {
                if ((currY & 15) == 15) {
                    this.checkNullSection(blockX >> 4, currY >> 4, blockZ >> 4, true);
                }

                final SWMRNibbleArray nib = this.nibbleCache[(blockX >> 4) + 5 * (blockZ >> 4) + (5 * 5) * (currY >> 4) + this.chunkSectionIndexOffset];
                if (nib == null) {
                    currY = currY & ~15;
                    continue;
                }

                final int currentLevel = this.getLightLevel(blockX, currY, blockZ);
                if (currentLevel != 15) {
                    break;
                }

                this.appendToDecreaseQueue(encodeCoords(blockX, blockZ, currY, encodeOffset)
                        | this.encodeQueueLevel(15)
                        | (propagateDirection << DIRECTION_SHIFT));
            }
        }

        this.applyDelayedQueue(this.increaseQueue, this.increaseQueueInitialLength, true);
        this.applyDelayedQueue(this.decreaseQueue, this.decreaseQueueInitialLength, false);

        this.checkBlock(blockX, blockY, blockZ);

        this.performLightDecrease();
    }

    private void applyDelayedQueue(final long[] queue, final int len, final boolean useLevel) {
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;

        for (int i = 0; i < len; ++i) {
            final long queueValue = queue[i];
            final int posX = ((int) queueValue & 63) + decodeOffsetX;
            final int posZ = (((int) queueValue >>> 6) & 63) + decodeOffsetZ;
            final int posY = (((int) queueValue >>> 12) & 0xFFFF) + decodeOffsetY;
            final int level = useLevel ? (int) ((queueValue >>> LIGHT_LEVEL_SHIFT) & 0xF) : 0;
            this.setLightLevel(posX, posY, posZ, level);
        }
    }

    @Override
    protected void checkBlock(final int worldX, final int worldY, final int worldZ) {
        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);
        final int encodeOffset = this.coordinateOffset;

        if (currentLevel == 15) {
            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | this.encodeQueueLevel(15)
                    | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT));
        } else {
            this.setLightLevel(worldX, worldY, worldZ, 0);
            this.appendToDecreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | this.encodeQueueLevel(currentLevel)
                    | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT));
        }
    }

    @Override
    protected int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect) {
        if (expect == 15) {
            return expect;
        }

        final int sectionOffset = this.chunkSectionIndexOffset;
        final IBlockState centerState = this.getBlockState(worldX, worldY, worldZ);
        final Block centerBlock = centerState.getBlock();
        final int rawOpacity = centerState.getLightOpacity();
        final int meta = centerBlock.getMetaFromState(centerState);
        final boolean sidedTransparent = rawOpacity > 1 && FaceOcclusion.hasSidedTransparency(centerBlock);
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
                    ? (FaceOcclusion.isFaceSolid(centerBlock, meta, direction.ordinal()) ? Math.max(1, rawOpacity) : 1)
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
        Arrays.fill(this.nullPropagationCheckCache, false);

        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();

        int highestNonEmptySection = this.maxSection;
        while (highestNonEmptySection >= this.minSection && (sections[highestNonEmptySection - this.minSection] == null
                || sections[highestNonEmptySection - this.minSection].isEmpty())) {
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
        Arrays.fill(this.nullPropagationCheckCache, false);
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
            Block srcBlock = null;
            int srcMeta = 0;
            boolean checkSourceFaces = false;
            if (hasSidedTransparent) {
                final int srcIdx = (posX >> 4) + 5 * (posZ >> 4) + (5 * 5) * (posY >> 4) + sectionOffset;
                final IBlockState srcState = this.getBlockStateFast(srcIdx, posX & 15, posY & 15, posZ & 15);
                srcBlock = srcState.getBlock();
                if (srcBlock != Blocks.AIR && FaceOcclusion.hasSidedTransparency(srcBlock)) {
                    srcMeta = srcBlock.getMetaFromState(srcState);
                    checkSourceFaces = true;
                }
            }

            if ((queueValue & FLAG_RECHECK_LEVEL) != 0L) {
                if (this.getLightLevel(posX, posY, posZ) != propagatedLevel) {
                    continue;
                }
            } else if ((queueValue & FLAG_WRITE_LEVEL) != 0L) {
                this.setLightLevel(posX, posY, posZ, propagatedLevel);
            }

            for (final AxisDirection propagate : checkDirections) {
                if (checkSourceFaces && FaceOcclusion.isFaceSolid(srcBlock, srcMeta, propagate.ordinal())) continue;

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
                final int absorption;
                if (destState.getBlock() == Blocks.AIR) {
                    absorption = 1;
                } else {
                    absorption = FaceOcclusion.resolveScalarAbsorption(destState, propagate.oppositeOrdinal);
                }

                final int targetLevel = propagatedLevel - absorption;
                if (targetLevel <= currentLevel) {
                    continue;
                }

                this.setLightLevel(offX, offY, offZ, targetLevel);
                this.postLightUpdate(sectionIndex);

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
                            | sidedFlag(destState);
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
            Block srcBlock = null;
            int srcMeta = 0;
            boolean checkSourceFaces = false;
            if (hasSidedTransparent) {
                final int srcIdx = (posX >> 4) + 5 * (posZ >> 4) + (5 * 5) * (posY >> 4) + sectionOffset;
                final IBlockState srcState = this.getBlockStateFast(srcIdx, posX & 15, posY & 15, posZ & 15);
                srcBlock = srcState.getBlock();
                if (srcBlock != Blocks.AIR && FaceOcclusion.hasSidedTransparency(srcBlock)) {
                    srcMeta = srcBlock.getMetaFromState(srcState);
                    checkSourceFaces = true;
                }
            }

            for (final AxisDirection propagate : checkDirections) {
                if (checkSourceFaces && FaceOcclusion.isFaceSolid(srcBlock, srcMeta, propagate.ordinal())) continue;

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
                if (currentLevel == 15) {
                    continue;
                }

                final IBlockState state = this.getBlockStateFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int absorption;
                if (state.getBlock() == Blocks.AIR) {
                    absorption = 1;
                } else {
                    absorption = FaceOcclusion.resolveScalarAbsorption(state, propagate.oppositeOrdinal);
                }

                final int targetLevel = propagatedLevel - absorption;
                final long sFlag = sidedFlag(state);

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
                this.postLightUpdate(sectionIndex);

                if (currentLevel > 1) {
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
        final NibbleArray vanilla = section.getSkyLight();
        if (vanilla == null) return;
        System.arraycopy(srcData, 0, vanilla.getData(), 0, srcData.length);
    }
}
